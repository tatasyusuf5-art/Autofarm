package com.yusuf.autofarm;

import com.mojang.brigadier.arguments.IntegerArgumentType;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandManager;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.block.CropBlock;
import net.minecraft.block.NetherWartBlock;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.client.util.InputUtil;
import net.minecraft.component.type.ItemEnchantmentsComponent;
import net.minecraft.enchantment.Enchantment;
import net.minecraft.enchantment.Enchantments;
import net.minecraft.entity.ItemEntity;
import net.minecraft.item.BlockItem;
import net.minecraft.item.ItemStack;
import net.minecraft.registry.entry.RegistryEntry;
import net.minecraft.registry.tag.FluidTags;
import net.minecraft.registry.tag.ItemTags;
import net.minecraft.screen.slot.SlotActionType;
import net.minecraft.text.Text;
import net.minecraft.util.ActionResult;
import net.minecraft.util.Hand;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.hit.HitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;
import org.lwjgl.glfw.GLFW;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * Legit yardımcı otomasyon: hedefe yürür, bloğa bakar (kamera görünür şekilde döner),
 * gerçek bakış ışınıyla (raycast) vurduğu bloğu kırar / eker.
 *
 * Tuşlar: G = aç/kapat, R + G = ayar menüsü.
 */
public class AutoFarmClient implements ClientModInitializer {
    private static final int SEARCH_RADIUS = 12;     // hedef arama yarıçapı
    private static final double WALK_STOP = 3.0;     // hedefe bu kadar yaklaşınca dur
    private static final int AIM_TICKS = 2;          // işlemden önce hedefe sabit bakma süresi
    private static final int TARGET_TIMEOUT = 120;   // hedefe ulaşamazsa vazgeç (tick)

    private enum Kind { HARVEST, PLANT, ITEM }

    private static final String[] MODE_NAMES = {
            "1: Kır (olgun ekinleri kırar)",
            "2: Ek (boş tarlaları eker)",
            "3: Kır + Ek (kırar ve geri eker)"
    };

    private final AutoFarmConfig cfg = AutoFarmConfig.get();

    private boolean enabled = false;
    private boolean openGui = false;

    private BlockPos targetPos;
    private Kind targetKind;
    private ItemEntity targetItem;
    private int targetTicks, aimedTicks, actionCooldown, tickCounter, warnCooldown;
    private boolean fwdHeld, jumpHeld;

    private final Map<BlockPos, Integer> blacklist = new HashMap<>();
    private final Map<BlockPos, Integer> replant = new HashMap<>();
    private final Set<Integer> ignoredItems = new HashSet<>();

    private boolean prevG = false;
    private boolean prevR = false;
    private boolean announce = true;

    @Override
    public void onInitializeClient() {
        cfg.load();

        ClientTickEvents.END_CLIENT_TICK.register(this::onTick);

        // /autofarm, /autofarm gui, /autofarm mode <1-3>, /autofarm speed <1-10>
        ClientCommandRegistrationCallback.EVENT.register((dispatcher, registryAccess) ->
                dispatcher.register(ClientCommandManager.literal("autofarm")
                        .executes(ctx -> {
                            toggle(MinecraftClient.getInstance());
                            return 1;
                        })
                        .then(ClientCommandManager.literal("gui")
                                .executes(ctx -> {
                                    openGui = true;   // sohbet kapandıktan sonra bir sonraki tickte açılır
                                    return 1;
                                }))
                        .then(ClientCommandManager.literal("mode")
                                .then(ClientCommandManager.argument("n", IntegerArgumentType.integer(1, 3))
                                        .executes(ctx -> {
                                            cfg.mode = IntegerArgumentType.getInteger(ctx, "n") - 1;
                                            cfg.save();
                                            resetState(MinecraftClient.getInstance());
                                            say(MinecraftClient.getInstance(), "Mod -> " + MODE_NAMES[cfg.mode]);
                                            return 1;
                                        })))
                        .then(ClientCommandManager.literal("speed")
                                .then(ClientCommandManager.argument("n", IntegerArgumentType.integer(1, 10))
                                        .executes(ctx -> {
                                            cfg.speed = IntegerArgumentType.getInteger(ctx, "n");
                                            cfg.save();
                                            say(MinecraftClient.getInstance(), "Hız -> " + cfg.speed + "/10");
                                            return 1;
                                        })))));
    }

    // ------------------------------------------------------------------ kontrol

    public boolean isEnabled() {
        return enabled;
    }

    public void toggle(MinecraftClient mc) {
        enabled = !enabled;
        resetState(mc);
        String msg = "AutoFarm " + (enabled ? "AÇIK" : "KAPALI") + " | Mod " + MODE_NAMES[cfg.mode];
        say(mc, msg);
        if (mc.player != null) {
            mc.player.sendMessage(Text.literal("[AutoFarm] " + msg), false);
        }
    }

    private void resetState(MinecraftClient mc) {
        clearTarget();
        blacklist.clear();
        replant.clear();
        ignoredItems.clear();
        releaseKeys(mc);
    }

    private void say(MinecraftClient mc, String msg) {
        if (mc.player != null) {
            mc.player.sendMessage(Text.literal(msg), true);
        }
    }

    private void warn(MinecraftClient mc, String msg) {
        if (warnCooldown == 0) {
            say(mc, msg);
            warnCooldown = 40;
        }
    }

    // Hız ayarı (1..10): bakış dönüş hızı ve işlemler arası bekleme
    private float lookStep() {
        return 5f + 3f * cfg.speed;           // 8 .. 35 derece/tick
    }

    private int actionDelay() {
        return Math.max(1, 11 - cfg.speed);   // 10 .. 1 tick
    }

    // ------------------------------------------------------------------ ana döngü

    private void onTick(MinecraftClient mc) {
        tickCounter++;

        // Oyuna girince yüklendiğini göster
        if (announce && mc.player != null && mc.world != null) {
            announce = false;
            mc.player.sendMessage(Text.literal(
                    "[AutoFarm] Yüklendi. G = aç/kapat, R + G = menü, ya da /autofarm gui"), false);
        }

        // Tuşlar doğrudan klavyeden okunur (başka modlarla tuş çakışmasından etkilenmez)
        boolean g = false;
        boolean r = false;
        if (mc.currentScreen == null && mc.player != null) {
            long window = mc.getWindow().getHandle();
            g = InputUtil.isKeyPressed(window, GLFW.GLFW_KEY_G);
            r = InputUtil.isKeyPressed(window, GLFW.GLFW_KEY_R);
        }
        if (g && !prevG) {
            if (r) {
                openGui = true;
            } else {
                toggle(mc);
            }
        }
        prevG = g;
        prevR = r;

        if (openGui && mc.player != null && mc.currentScreen == null) {
            openGui = false;
            releaseKeys(mc);
            mc.setScreen(new AutoFarmScreen(this));
            return;
        }

        if (warnCooldown > 0) warnCooldown--;
        if (actionCooldown > 0) actionCooldown--;

        if (!enabled || mc.player == null || mc.world == null || mc.interactionManager == null
                || mc.currentScreen != null || mc.player.isSpectator()) {
            releaseKeys(mc);
            return;
        }

        if (tickCounter % 40 == 0) {
            blacklist.values().removeIf(t -> t < tickCounter);
            replant.values().removeIf(t -> t < tickCounter);
        }
        if (tickCounter % 1200 == 0) ignoredItems.clear();

        boolean harvestMode = cfg.mode == 0 || cfg.mode == 2;
        boolean plantMode = cfg.mode == 1 || cfg.mode == 2;

        // Alet zorunlulukları (kırma modlarında ana el)
        if (harvestMode) {
            ItemStack tool = mc.player.getMainHandStack();
            if (cfg.requireHoe && !tool.isIn(ItemTags.HOES)) {
                warn(mc, "Ana ele çapa al! (Çapa zorunluluğu açık)");
                releaseKeys(mc);
                return;
            }
            if (cfg.requireFortune && fortuneLevel(tool) < 3) {
                warn(mc, "Ana elde Servet III alet olmalı! (Servet III zorunluluğu açık)");
                releaseKeys(mc);
                return;
            }
        }

        // Ekim için elde seçili ekin
        Hand plantHand = null;
        Block plantBlock = null;
        if (plantMode) {
            plantHand = findPlantHand(mc, cfg.mode == 1);   // Mod 2: ana/2. el, Mod 3: sadece 2. el
            if (plantHand == null) {
                supply(mc);
                releaseKeys(mc);
                return;
            }
            plantBlock = ((BlockItem) mc.player.getStackInHand(plantHand).getItem()).getBlock();
        }

        // Hedef geçerli mi?
        if (targetKind != null && !targetStillValid(mc, plantBlock)) {
            clearTarget();
        }
        if (targetKind == null) {
            findTarget(mc, plantBlock);
            if (targetKind == null) {
                releaseKeys(mc);
                return;
            }
        }
        if (++targetTicks > TARGET_TIMEOUT) {
            releaseKeys(mc);
            giveUp();
            return;
        }

        step(mc, plantHand);
    }

    private void step(MinecraftClient mc, Hand plantHand) {
        ClientPlayerEntity p = mc.player;

        // Yerdeki eşyaları topla
        if (targetKind == Kind.ITEM) {
            Vec3d ip = targetItem.getPos();
            float err = lookAt(p, ip.add(0, 0.1, 0));
            double hd = Math.hypot(ip.x - p.getX(), ip.z - p.getZ());
            if (hd > 0.6) {
                if (dangerAhead(mc)) {
                    releaseKeys(mc);
                    giveUp();
                    return;
                }
                walk(mc, err < 40f);
            } else {
                walk(mc, false);
            }
            return;
        }

        Vec3d aim = aimPoint(targetPos, targetKind);
        float yawErr = lookAt(p, aim);
        double dist = p.getEyePos().distanceTo(aim);

        if (dist > WALK_STOP) {
            aimedTicks = 0;
            if (dangerAhead(mc)) {
                releaseKeys(mc);
                giveUp();
                return;
            }
            walk(mc, yawErr < 40f);
            return;
        }
        walk(mc, false);

        // Gerçek bakış ışını hedefi gösteriyor mu?
        HitResult hr = p.raycast(p.getBlockInteractionRange(), 1.0f, false);
        BlockHitResult bhr = null;
        boolean ok = false;
        if (hr instanceof BlockHitResult b && b.getType() == HitResult.Type.BLOCK) {
            bhr = b;
            if (targetKind == Kind.HARVEST) {
                ok = b.getBlockPos().equals(targetPos);
            } else {
                ok = b.getBlockPos().equals(targetPos.down()) && b.getSide() == Direction.UP;
            }
        }
        if (!ok) {
            aimedTicks = 0;
            return;
        }
        if (++aimedTicks < AIM_TICKS || actionCooldown > 0) return;

        if (targetKind == Kind.HARVEST) {
            mc.interactionManager.attackBlock(targetPos, bhr.getSide());
            p.swingHand(Hand.MAIN_HAND);
            if (cfg.mode == 2) {
                replant.put(targetPos.toImmutable(), tickCounter + 100);
            }
        } else {
            ActionResult r = mc.interactionManager.interactBlock(p, plantHand, bhr);
            if (r.isAccepted()) {
                p.swingHand(plantHand);
                replant.remove(targetPos);
            } else {
                blacklist.put(targetPos.toImmutable(), tickCounter + 200);
            }
        }
        actionCooldown = actionDelay();
        clearTarget();
    }

    // ------------------------------------------------------------------ ekin elde tutma / çantadan alma

    private Hand findPlantHand(MinecraftClient mc, boolean allowMain) {
        ClientPlayerEntity p = mc.player;
        if (allowMain && isSelectedSeed(p.getMainHandStack())) return Hand.MAIN_HAND;
        if (isSelectedSeed(p.getOffHandStack())) return Hand.OFF_HAND;
        return null;
    }

    /** Çantadaki seçili ekini 2. ele alır. */
    private void supply(MinecraftClient mc) {
        ClientPlayerEntity p = mc.player;
        ItemStack off = p.getOffHandStack();
        if (!off.isEmpty() && !isSeed(off)) {
            warn(mc, "2. el dolu! Ekin otomatik 2. ele alınır, eli boşalt.");
            return;
        }
        if (actionCooldown > 0) return;
        for (int i = 0; i < 36; i++) {
            if (isSelectedSeed(p.getInventory().getStack(i))) {
                int slot = i < 9 ? i + 36 : i;
                mc.interactionManager.clickSlot(p.playerScreenHandler.syncId, slot, 40,
                        SlotActionType.SWAP, p);
                actionCooldown = 10;
                return;
            }
        }
        warn(mc, "Çantada seçili ekin bulunamadı!");
    }

    // ------------------------------------------------------------------ hedef bulma

    private void findTarget(MinecraftClient mc, Block plantBlock) {
        ClientPlayerEntity p = mc.player;
        boolean harvest = (cfg.mode == 0 || cfg.mode == 2);
        Vec3d eye = p.getEyePos();
        BlockPos origin = p.getBlockPos();

        double best = Double.MAX_VALUE;
        BlockPos bestPos = null;
        Kind bestKind = null;

        for (BlockPos pos : BlockPos.iterate(
                origin.add(-SEARCH_RADIUS, -2, -SEARCH_RADIUS),
                origin.add(SEARCH_RADIUS, 2, SEARCH_RADIUS))) {
            if (blacklist.containsKey(pos)) continue;
            BlockState s = mc.world.getBlockState(pos);
            Kind k = null;
            if (harvest && isMature(s) && cfg.isSelected(s.getBlock())) {
                k = Kind.HARVEST;
            } else if (plantBlock != null && s.isAir()
                    && (cfg.mode == 1 || replant.containsKey(pos))
                    && plantBlock.getDefaultState().canPlaceAt(mc.world, pos)) {
                k = Kind.PLANT;
            }
            if (k == null) continue;
            double d = eye.squaredDistanceTo(Vec3d.ofCenter(pos));
            if (d < best) {
                best = d;
                bestPos = pos.toImmutable();
                bestKind = k;
            }
        }

        if (bestPos != null) {
            targetPos = bestPos;
            targetKind = bestKind;
            targetTicks = 0;
            aimedTicks = 0;
            return;
        }

        // Blok hedefi yoksa yere düşen ekinleri topla
        if (harvest) {
            Box box = p.getBoundingBox().expand(10, 3, 10);
            ItemEntity bestItem = null;
            double bd = Double.MAX_VALUE;
            for (ItemEntity ie : mc.world.getEntitiesByClass(ItemEntity.class, box,
                    x -> !x.isRemoved() && !ignoredItems.contains(x.getId()))) {
                double d = ie.squaredDistanceTo(p);
                if (d < bd) {
                    bd = d;
                    bestItem = ie;
                }
            }
            if (bestItem != null) {
                targetItem = bestItem;
                targetKind = Kind.ITEM;
                targetTicks = 0;
                aimedTicks = 0;
            }
        }
    }

    private boolean targetStillValid(MinecraftClient mc, Block plantBlock) {
        return switch (targetKind) {
            case HARVEST -> {
                BlockState s = mc.world.getBlockState(targetPos);
                yield isMature(s) && cfg.isSelected(s.getBlock());
            }
            case PLANT -> plantBlock != null
                    && mc.world.getBlockState(targetPos).isAir()
                    && plantBlock.getDefaultState().canPlaceAt(mc.world, targetPos);
            case ITEM -> targetItem != null && !targetItem.isRemoved();
        };
    }

    private void clearTarget() {
        targetKind = null;
        targetPos = null;
        targetItem = null;
        targetTicks = 0;
        aimedTicks = 0;
    }

    private void giveUp() {
        if (targetKind == Kind.ITEM && targetItem != null) {
            ignoredItems.add(targetItem.getId());
        } else if (targetPos != null) {
            blacklist.put(targetPos.toImmutable(), tickCounter + 600);
        }
        clearTarget();
    }

    // ------------------------------------------------------------------ bakış & hareket

    private static Vec3d aimPoint(BlockPos pos, Kind kind) {
        if (kind == Kind.HARVEST) {
            return new Vec3d(pos.getX() + 0.5, pos.getY() + 0.35, pos.getZ() + 0.5);
        }
        // ekim: toprağın üst yüzeyi
        return new Vec3d(pos.getX() + 0.5, pos.getY() - 1 + 0.9, pos.getZ() + 0.5);
    }

    /** Kamerayı hedefe doğru kademeli çevirir; kalan yaw hatasını (derece) döndürür. */
    private float lookAt(ClientPlayerEntity p, Vec3d target) {
        float step = lookStep();
        Vec3d eye = p.getEyePos();
        double dx = target.x - eye.x;
        double dy = target.y - eye.y;
        double dz = target.z - eye.z;
        double h = Math.sqrt(dx * dx + dz * dz);

        float wantYaw = (float) (Math.toDegrees(Math.atan2(dz, dx)) - 90.0);
        float wantPitch = (float) (-Math.toDegrees(Math.atan2(dy, h)));

        float yawDiff = MathHelper.wrapDegrees(wantYaw - p.getYaw());
        float pitchDiff = wantPitch - p.getPitch();

        p.setYaw(p.getYaw() + MathHelper.clamp(yawDiff, -step, step));
        p.setPitch(MathHelper.clamp(p.getPitch() + MathHelper.clamp(pitchDiff, -step, step), -90f, 90f));

        return Math.abs(yawDiff) > step ? Math.abs(yawDiff) - step : 0f;
    }

    private void walk(MinecraftClient mc, boolean forward) {
        ClientPlayerEntity p = mc.player;
        mc.options.forwardKey.setPressed(forward);
        fwdHeld = forward;
        boolean jump = forward && p.horizontalCollision && p.isOnGround();
        mc.options.jumpKey.setPressed(jump);
        jumpHeld = jump;
    }

    private void releaseKeys(MinecraftClient mc) {
        if (fwdHeld) {
            mc.options.forwardKey.setPressed(false);
            fwdHeld = false;
        }
        if (jumpHeld) {
            mc.options.jumpKey.setPressed(false);
            jumpHeld = false;
        }
    }

    /** Önümüzde uçurum ya da lav var mı? */
    private boolean dangerAhead(MinecraftClient mc) {
        Vec3d dir = Vec3d.fromPolar(0, mc.player.getYaw());
        BlockPos ahead = BlockPos.ofFloored(mc.player.getPos().add(dir));
        for (int i = 0; i <= 3; i++) {
            BlockPos q = ahead.down(i);
            BlockState s = mc.world.getBlockState(q);
            if (s.getFluidState().isIn(FluidTags.LAVA)) return true;
            if (!s.getCollisionShape(mc.world, q).isEmpty() || !s.getFluidState().isEmpty()) return false;
        }
        return true;
    }

    // ------------------------------------------------------------------ yardımcılar

    private static boolean isMature(BlockState state) {
        Block b = state.getBlock();
        if (b instanceof CropBlock crop) {
            return crop.isMature(state);
        }
        if (b instanceof NetherWartBlock) {
            return state.get(NetherWartBlock.AGE) >= 3;
        }
        return false;
    }

    private static boolean isSeed(ItemStack stack) {
        if (stack.isEmpty() || !(stack.getItem() instanceof BlockItem bi)) return false;
        Block b = bi.getBlock();
        return b instanceof CropBlock || b instanceof NetherWartBlock;
    }

    private boolean isSelectedSeed(ItemStack stack) {
        return isSeed(stack) && cfg.isSelected(((BlockItem) stack.getItem()).getBlock());
    }

    private static int fortuneLevel(ItemStack stack) {
        ItemEnchantmentsComponent ench = stack.getEnchantments();
        for (RegistryEntry<Enchantment> e : ench.getEnchantments()) {
            if (e.matchesKey(Enchantments.FORTUNE)) {
                return ench.getLevel(e);
            }
        }
        return 0;
    }
}
