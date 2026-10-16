package com.yusuf.autofarm;

import com.mojang.brigadier.arguments.IntegerArgumentType;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandManager;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.block.CropBlock;
import net.minecraft.block.NetherWartBlock;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.client.option.KeyBinding;
import net.minecraft.client.util.InputUtil;
import net.minecraft.entity.ItemEntity;
import net.minecraft.item.BlockItem;
import net.minecraft.item.ItemStack;
import net.minecraft.registry.tag.FluidTags;
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
 */
public class AutoFarmClient implements ClientModInitializer {
    private static final int SEARCH_RADIUS = 12;     // hedef arama yarıçapı
    private static final double WALK_STOP = 3.0;     // hedefe bu kadar yaklaşınca dur
    private static final float LOOK_STEP = 15f;      // tick başına en fazla dönüş (derece)
    private static final int ACTION_DELAY = 3;       // iki işlem arası tick
    private static final int AIM_TICKS = 2;          // işlemden önce hedefe sabit bakma süresi
    private static final int TARGET_TIMEOUT = 120;   // hedefe ulaşamazsa vazgeç (tick)

    private enum Kind { HARVEST, PLANT, ITEM }

    private static final String[] MODE_NAMES = {
            "1: Kır (olgun ekinleri kırar)",
            "2: Ek (boş tarlaları eker)",
            "3: Kır + Ek (kırar ve geri eker)"
    };

    private boolean enabled = false;
    private int mode = 0;

    private BlockPos targetPos;
    private Kind targetKind;
    private ItemEntity targetItem;
    private int targetTicks, aimedTicks, actionCooldown, tickCounter, warnCooldown;
    private boolean fwdHeld, jumpHeld;

    private final Map<BlockPos, Integer> blacklist = new HashMap<>();
    private final Map<BlockPos, Integer> replant = new HashMap<>();
    private final Set<Integer> ignoredItems = new HashSet<>();

    private KeyBinding toggleKey;
    private KeyBinding modeKey;

    @Override
    public void onInitializeClient() {
        toggleKey = KeyBindingHelper.registerKeyBinding(new KeyBinding(
                "key.autofarm.toggle", InputUtil.Type.KEYSYM, GLFW.GLFW_KEY_R, "category.autofarm"));
        modeKey = KeyBindingHelper.registerKeyBinding(new KeyBinding(
                "key.autofarm.mode", InputUtil.Type.KEYSYM, GLFW.GLFW_KEY_G, "category.autofarm"));

        ClientTickEvents.END_CLIENT_TICK.register(this::onTick);

        ClientCommandRegistrationCallback.EVENT.register((dispatcher, registryAccess) ->
                dispatcher.register(ClientCommandManager.literal("autofarm")
                        .executes(ctx -> {
                            toggle(MinecraftClient.getInstance());
                            return 1;
                        })
                        .then(ClientCommandManager.literal("mode")
                                .then(ClientCommandManager.argument("n", IntegerArgumentType.integer(1, 3))
                                        .executes(ctx -> {
                                            setMode(MinecraftClient.getInstance(),
                                                    IntegerArgumentType.getInteger(ctx, "n") - 1);
                                            return 1;
                                        })))));
    }

    // ------------------------------------------------------------------ kontrol

    private void toggle(MinecraftClient mc) {
        enabled = !enabled;
        resetState(mc);
        say(mc, "AutoFarm " + (enabled ? "AÇIK" : "KAPALI") + " | Mod " + MODE_NAMES[mode]);
    }

    private void setMode(MinecraftClient mc, int m) {
        mode = m;
        resetState(mc);
        say(mc, "Mod -> " + MODE_NAMES[mode]);
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

    // ------------------------------------------------------------------ ana döngü

    private void onTick(MinecraftClient mc) {
        tickCounter++;
        while (toggleKey.wasPressed()) toggle(mc);
        while (modeKey.wasPressed()) setMode(mc, (mode + 1) % MODE_NAMES.length);

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

        // Elde ekin gerekliliği
        Hand plantHand = null;
        Block plantBlock = null;
        if (mode == 2) {
            if (isSeed(mc.player.getStackInHand(Hand.OFF_HAND))) {
                plantHand = Hand.OFF_HAND;
            } else {
                warn(mc, "Mod 3 için 2. ele ekin (tohum/havuç/patates...) al!");
                releaseKeys(mc);
                return;
            }
        } else if (mode == 1) {
            if (isSeed(mc.player.getStackInHand(Hand.MAIN_HAND))) {
                plantHand = Hand.MAIN_HAND;
            } else if (isSeed(mc.player.getStackInHand(Hand.OFF_HAND))) {
                plantHand = Hand.OFF_HAND;
            } else {
                warn(mc, "Ekmek için elinde ekin olmalı!");
                releaseKeys(mc);
                return;
            }
        }
        if (plantHand != null) {
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
            if (mode == 2) {
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
        actionCooldown = ACTION_DELAY;
        clearTarget();
    }

    // ------------------------------------------------------------------ hedef bulma

    private void findTarget(MinecraftClient mc, Block plantBlock) {
        ClientPlayerEntity p = mc.player;
        boolean harvest = (mode == 0 || mode == 2);
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
            if (harvest && isMature(s)) {
                k = Kind.HARVEST;
            } else if (plantBlock != null && s.isAir()
                    && (mode == 1 || replant.containsKey(pos))
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
            case HARVEST -> isMature(mc.world.getBlockState(targetPos));
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
        Vec3d eye = p.getEyePos();
        double dx = target.x - eye.x;
        double dy = target.y - eye.y;
        double dz = target.z - eye.z;
        double h = Math.sqrt(dx * dx + dz * dz);

        float wantYaw = (float) (Math.toDegrees(Math.atan2(dz, dx)) - 90.0);
        float wantPitch = (float) (-Math.toDegrees(Math.atan2(dy, h)));

        float yawDiff = MathHelper.wrapDegrees(wantYaw - p.getYaw());
        float pitchDiff = wantPitch - p.getPitch();

        p.setYaw(p.getYaw() + MathHelper.clamp(yawDiff, -LOOK_STEP, LOOK_STEP));
        p.setPitch(MathHelper.clamp(p.getPitch() + MathHelper.clamp(pitchDiff, -LOOK_STEP, LOOK_STEP), -90f, 90f));

        return Math.abs(yawDiff) > LOOK_STEP ? Math.abs(yawDiff) - LOOK_STEP : 0f;
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
}
