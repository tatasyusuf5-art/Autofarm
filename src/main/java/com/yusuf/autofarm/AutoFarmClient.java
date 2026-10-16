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
    private static final double WALK_STOP = 2.6;     // hedefe bu kadar yaklaşınca dur
    private static final int AIM_TICKS = 2;          // işlemden önce hedefe sabit bakma süresi
    private static final int TARGET_TIMEOUT = 140;   // hedefe ulaşamazsa vazgeç (tick)
    private static final int AVOID_DURATION = 18;    // engelden kaçınma süresi (tick)

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
    private boolean fwdHeld, jumpHeld, leftHeld, rightHeld;

    /** Engelden kaçınma: -1 sol, +1 sağ, 0 yok */
    private int avoidDir = 0;
    private int avoidTicks = 0;
    private int stuckTicks = 0;
    private Vec3d lastPos = null;

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
                                    openGui = true;
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
        avoidDir = 0;
        avoidTicks = 0;
        stuckTicks = 0;
        lastPos = null;
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

    private float lookStep() {
        return 5f + 3f * cfg.speed;           // 8 .. 35 derece/tick
    }

    private int actionDelay() {
        return Math.max(1, 11 - cfg.speed);   // 10 .. 1 tick
    }

    // ------------------------------------------------------------------ ana döngü

    private void onTick(MinecraftClient mc) {
        tickCounter++;

        if (announce && mc.player != null && mc.world != null) {
            announce = false;
            mc.player.sendMessage(Text.literal(
                    "[AutoFarm] Yüklendi. G = aç/kapat, R + G = menü, ya da /autofarm gui"), false);
        }

        boolean g = false;
        boolean r = false;
        if (mc.currentScreen == null && mc.player != null) {
            long window = mc.getWindow().getHandle();
            g = org.lwjgl.glfw.GLFW.glfwGetKey(window, GLFW.GLFW_KEY_G) == GLFW.GLFW_PRESS;
            r = org.lwjgl.glfw.GLFW.glfwGetKey(window, GLFW.GLFW_KEY_R) == GLFW.GLFW_PRESS;
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
                || mc.currentScreen != null || mc.player.isDead()) {
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

        Hand plantHand = null;
        Block plantBlock = null;
        if (plantMode) {
            plantHand = findPlantHand(mc, cfg.mode == 1);
            if (plantHand == null) {
                supply(mc);
                releaseKeys(mc);
                return;
            }
            plantBlock = ((BlockItem) mc.player.getStackInHand(plantHand).getItem()).getBlock();
        }

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

        // Takılma tespiti
        Vec3d now = p.getPos();
        if (lastPos != null) {
            double moved = Math.hypot(now.x - lastPos.x, now.z - lastPos.z);
            if (moved < 0.05 && (fwdHeld || leftHeld || rightHeld)) {
                stuckTicks++;
            } else {
                stuckTicks = 0;
            }
        }
        lastPos = now;

        // Yerdeki eşyaları topla
        if (targetKind == Kind.ITEM) {
            Vec3d ip = targetItem.getPos();
            float err = lookAt(p, ip.add(0, 0.1, 0));
            double hd = Math.hypot(ip.x - p.getX(), ip.z - p.getZ());
            if (hd > 0.6) {
                if (isDangerous(mc, true)) {
                    startAvoid(mc);
                }
                navigate(mc, err < 45f);
            } else {
                stopMove(mc);
            }
            return;
        }

        Vec3d aim = aimPoint(targetPos, targetKind);
        float yawErr = lookAt(p, aim);
        double dist = p.getEyePos().distanceTo(aim);

        if (dist > WALK_STOP) {
            aimedTicks = 0;
            if (isDangerous(mc, true) || stuckTicks > 8 || p.horizontalCollision) {
                startAvoid(mc);
            }
            navigate(mc, yawErr < 45f);
            return;
        }

        stopMove(mc);

        // Gerçek bakış ışını hedefi gösteriyor mu?
        HitResult hr = p.raycast(p.getBlockInteractionRange(), 1.0f, false);
        BlockHitResult bhr = null;
        boolean ok = false;
        if (hr instanceof BlockHitResult b && b.getType() == HitResult.Type.BLOCK) {
            bhr = b;
            if (targetKind == Kind.HARVEST) {
                ok = b.getBlockPos().equals(targetPos);
            } else {
                ok = b.getBlockPos().equals(targetPos.down())
                        && b.getSide() == Direction.UP;
            }
        }

        if (!ok) {
            aimedTicks = 0;
            if (targetKind == Kind.PLANT || targetKind == Kind.HARVEST) {
                if (avoidTicks <= 0) {
                    startAvoid(mc);
                }
                navigate(mc, false);
            }
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
                blacklist.put(targetPos.toImmutable(), tickCounter + 160);
            }
        }
        actionCooldown = actionDelay();
        avoidDir = 0;
        avoidTicks = 0;
        stuckTicks = 0;
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
        avoidDir = 0;
        avoidTicks = 0;
        stuckTicks = 0;
        clearTarget();
    }

    // ------------------------------------------------------------------ bakış & hareket

    private static Vec3d aimPoint(BlockPos pos, Kind kind) {
        if (kind == Kind.HARVEST) {
            return new Vec3d(pos.getX() + 0.5, pos.getY() + 0.25, pos.getZ() + 0.5);
        }
        return new Vec3d(pos.getX() + 0.5, pos.getY() - 1 + 0.85, pos.getZ() + 0.5);
    }

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

    private void navigate(MinecraftClient mc, boolean wantForward) {
        ClientPlayerEntity p = mc.player;

        if (avoidTicks > 0) {
            avoidTicks--;
            setMove(mc, true, avoidDir < 0, avoidDir > 0);
            boolean jump = p.horizontalCollision && p.isOnGround();
            mc.options.jumpKey.setPressed(jump);
            jumpHeld = jump;
            if (avoidTicks == 0) {
                avoidDir = 0;
            }
            return;
        }

        setMove(mc, wantForward, false, false);
        boolean jump = wantForward && p.horizontalCollision && p.isOnGround();
        mc.options.jumpKey.setPressed(jump);
        jumpHeld = jump;
    }

    private void stopMove(MinecraftClient mc) {
        setMove(mc, false, false, false);
        if (jumpHeld) {
            mc.options.jumpKey.setPressed(false);
            jumpHeld = false;
        }
    }

    private void setMove(MinecraftClient mc, boolean fwd, boolean left, boolean right) {
        mc.options.forwardKey.setPressed(fwd);
        mc.options.leftKey.setPressed(left);
        mc.options.rightKey.setPressed(right);
        fwdHeld = fwd;
        leftHeld = left;
        rightHeld = right;
    }

    private void releaseKeys(MinecraftClient mc) {
        if (fwdHeld || leftHeld || rightHeld || jumpHeld) {
            mc.options.forwardKey.setPressed(false);
            mc.options.leftKey.setPressed(false);
            mc.options.rightKey.setPressed(false);
            mc.options.jumpKey.setPressed(false);
            fwdHeld = leftHeld = rightHeld = jumpHeld = false;
        }
    }

    private void startAvoid(MinecraftClient mc) {
        if (avoidTicks > 0) return;
        int preferred = chooseSaferSide(mc);
        avoidDir = preferred != 0 ? preferred : (tickCounter % 2 == 0 ? 1 : -1);
        avoidTicks = AVOID_DURATION;
        stuckTicks = 0;
    }

    private int chooseSaferSide(MinecraftClient mc) {
        ClientPlayerEntity p = mc.player;
        Vec3d eye = p.getEyePos();
        Vec3d toTarget;
        if (targetKind == Kind.ITEM && targetItem != null) {
            toTarget = targetItem.getPos().subtract(eye);
        } else if (targetPos != null) {
            toTarget = Vec3d.ofCenter(targetPos).subtract(eye);
        } else {
            return 0;
        }
        double len = Math.hypot(toTarget.x, toTarget.z);
        if (len < 1e-4) return 0;
        double rx = -toTarget.z / len;
        double rz = toTarget.x / len;

        int leftScore = sideScore(mc, eye, -rx, -rz);
        int rightScore = sideScore(mc, eye, rx, rz);
        if (leftScore > rightScore) return -1;
        if (rightScore > leftScore) return 1;
        return 0;
    }

    private int sideScore(MinecraftClient mc, Vec3d from, double dx, double dz) {
        int score = 0;
        for (int step = 1; step <= 3; step++) {
            BlockPos pos = BlockPos.ofFloored(from.add(dx * step, 0, dz * step));
            BlockState ground = mc.world.getBlockState(pos.down());
            BlockState feet = mc.world.getBlockState(pos);
            BlockState head = mc.world.getBlockState(pos.up());

            if (isFluidDanger(feet) || isFluidDanger(head) || isFluidDanger(ground)) {
                score -= 5;
                continue;
            }
            if (ground.getCollisionShape(mc.world, pos.down()).isEmpty()
                    && ground.getFluidState().isEmpty()) {
                score -= 4;
                continue;
            }
            if (isSolidObstacle(feet, mc, pos) || isSolidObstacle(head, mc, pos.up())) {
                score -= 3;
                continue;
            }
            score += 2;
        }
        return score;
    }

    private static boolean isFluidDanger(BlockState s) {
        return s.getFluidState().isIn(FluidTags.LAVA)
                || s.getFluidState().isIn(FluidTags.WATER);
    }

    private static boolean isSolidObstacle(BlockState s, MinecraftClient mc, BlockPos pos) {
        if (s.isAir()) return false;
        Block b = s.getBlock();
        if (b instanceof CropBlock || b instanceof NetherWartBlock) return false;
        return !s.getCollisionShape(mc.world, pos).isEmpty();
    }

    private boolean isDangerous(MinecraftClient mc, boolean checkBody) {
        ClientPlayerEntity p = mc.player;
        Vec3d dir = Vec3d.fromPolar(0, p.getYaw());
        Vec3d base = p.getPos();

        for (int i = 1; i <= 3; i++) {
            Vec3d probe = base.add(dir.multiply(i));
            BlockPos pos = BlockPos.ofFloored(probe);
            BlockState ground = mc.world.getBlockState(pos.down());
            BlockState feet = mc.world.getBlockState(pos);
            BlockState head = mc.world.getBlockState(pos.up());

            if (isFluidDanger(feet) || isFluidDanger(head) || isFluidDanger(ground)) return true;

            if (ground.getCollisionShape(mc.world, pos.down()).isEmpty()
                    && ground.getFluidState().isEmpty()) {
                return true;
            }

            if (checkBody) {
                if (isSolidObstacle(feet, mc, pos) || isSolidObstacle(head, mc, pos.up())) {
                    return true;
                }
            }
        }
        return false;
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
