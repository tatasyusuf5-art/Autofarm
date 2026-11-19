package com.yusuf.autofarm;

import java.util.HashMap;
import java.util.Map;
import net.minecraft.util.Hand;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.entity.player.PlayerInventory;
import net.minecraft.screen.slot.SlotActionType;
import net.minecraft.item.BlockItem;
import net.minecraft.item.ItemStack;
import net.minecraft.block.Blocks;
import net.minecraft.block.Block;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.Position;
import net.minecraft.util.math.Vec3i;
import net.minecraft.util.math.Vec3d;
import net.minecraft.client.MinecraftClient;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.world.WorldView;
import net.minecraft.client.gui.screen.ingame.InventoryScreen;

public class PlantBot {
    private static final int PLANT_PER_TICK = 4;
    private static final double REACH = 4.0;
    private static final int SEARCH_RADIUS = 12;
    private final AutoFarmClient host;
    private boolean initPending = true;
    private Inv inv = Inv.FARM;
    private int timer = 0;
    private int waitMsgTimer = 0;
    private int refillSwaps = 0;
    private int levelY = 0;
    private BlockPos target = null;
    private int ticks = 0;
    private final Avoid avoid = new Avoid();
    private final Map<BlockPos, Long> skip = new HashMap<BlockPos, Long>();

    PlantBot(AutoFarmClient host) {
        this.host = host;
    }

    void reset() {
        this.initPending = true;
        this.avoid.reset();
    }

    boolean wantsInventory(MinecraftClient mc) {
        return this.inv != Inv.FARM && mc.currentScreen instanceof InventoryScreen;
    }

    void tick(MinecraftClient mc) {
        this.host.markKeys();
        if (this.initPending) {
            this.initPending = false;
            this.stopMove(mc);
            this.inv = Inv.FARM;
            this.target = null;
            this.ticks = 0;
            this.skip.clear();
            this.levelY = this.groundPos(mc).getY() + 1;
        }
        switch (this.inv.ordinal()) {
            case 1: {
                if (--this.timer <= 0) {
                    this.inv = Inv.SWAP;
                    this.timer = 6;
                }
                return;
            }
            case 2: {
                if (--this.timer > 0) {
                    return;
                }
                int slot = this.findSeedInMainInventory(mc);
                if (slot != -1) {
                    int hb = this.firstEmptyHotbar(mc);
                    if (hb == -1) {
                        hb = mc.player.getInventory().selectedSlot;
                    }
                    mc.interactionManager.clickSlot(mc.player.playerScreenHandler.syncId, slot, hb, SlotActionType.SWAP, mc.player);
                    if (++this.refillSwaps < 10 && this.firstEmptyHotbar(mc) != -1 && this.findSeedInMainInventory(mc) != -1) {
                        this.timer = 2;
                        return;
                    }
                }
                this.inv = Inv.CLOSE;
                this.timer = 6;
                return;
            }
            case 3: {
                if (--this.timer > 0) {
                    return;
                }
                mc.player.closeHandledScreen();
                this.inv = Inv.FARM;
                return;
            }
        }
        if (mc.currentScreen != null) {
            return;
        }
        if (!this.ensureSeedInHand(mc)) {
            this.stopMove(mc);
            if (this.findSeedInMainInventory(mc) != -1) {
                mc.setScreen(new InventoryScreen(mc.player));
                this.inv = Inv.OPEN_INV;
                this.timer = 6;
                this.refillSwaps = 0;
            } else if (--this.waitMsgTimer <= 0) {
                this.host.say(mc, "Envanterde seçili ekin yok, bekliyorum...");
                this.waitMsgTimer = 40;
            }
            return;
        }
        long now = mc.world.getTime();
        this.skip.values().removeIf(t -> t < now);
        if (mc.player.isOnGround()) {
            this.levelY = this.groundPos(mc).getY() + 1;
        }
        Vec3d eye = mc.player.getEyePos();
        BlockPos o = mc.player.getBlockPos();
        int placed = 0;
        for (int dx = -5; dx <= 5 && placed < 4; ++dx) {
            for (int dz = -5; dz <= 5 && placed < 4; ++dz) {
                BlockPos g;
                BlockPos c = new BlockPos(o.getX() + dx, this.levelY, o.getZ() + dz);
                if (this.skip.containsKey(c) || !this.canPlantAt(mc, c) || eye.distanceTo(Vec3d.ofCenter((g = c.down()))) > 4.0) continue;
                mc.interactionManager.interactBlock(mc.player, Hand.MAIN_HAND, new BlockHitResult(Vec3d.ofCenter(g), Direction.UP, g, false));
                this.skip.put(c.toImmutable(), now + 20L);
                ++placed;
            }
        }
        if (placed > 0) {
            mc.player.swingHand(Hand.MAIN_HAND);
        }
        if (this.target == null || this.skip.containsKey(this.target) || !this.canPlantAt(mc, this.target)) {
            this.target = this.findSpot(mc);
            this.ticks = 0;
            this.avoid.newTarget();
        }
        if (this.target == null) {
            this.stopMove(mc);
            if (this.avoid.drive(mc, null) == Avoid.Move.DROWNING) {
                this.host.autoOff(mc, "Suda takıldı, bot kapandı");
                return;
            }
            if (--this.waitMsgTimer <= 0) {
                this.host.say(mc, "Ekilecek yer yok, bekliyorum...");
                this.waitMsgTimer = 60;
            }
            return;
        }
        Vec3d c = Vec3d.ofCenter(this.target);
        double dx = c.x - eye.x;
        double dy = c.y - eye.y;
        double dz = c.z - eye.z;
        double hor = Math.sqrt(dx * dx + dz * dz);
        mc.player.setPitch((float)MathHelper.clamp(-Math.toDegrees(Math.atan2(dy, hor)), -10.0, 45.0));
        Avoid.Move mv = this.avoid.drive(mc, c);
        if (mv == Avoid.Move.DROWNING) {
            this.host.autoOff(mc, "Suda takıldı, bot kapandı");
            return;
        }
        if (mv == Avoid.Move.BLOCKED) {
            this.skip.put(this.target.toImmutable(), now + 600L);
            this.target = null;
            return;
        }
        if (++this.ticks > 200) {
            this.skip.put(this.target.toImmutable(), now + 600L);
            this.target = null;
        }
    }

    private Block seedBlock(MinecraftClient mc) {
        ItemStack s = mc.player.getMainHandStack();
        return this.host.isPlantableSeed(s) ? ((BlockItem)s.getItem()).getBlock() : null;
    }

    private boolean canPlantAt(MinecraftClient mc, BlockPos c) {
        Block sb = this.seedBlock(mc);
        if (sb == null) {
            return false;
        }
        return mc.world.getBlockState(c).isAir() && sb.getDefaultState().canPlaceAt(mc.world, c);
    }

    private BlockPos findSpot(MinecraftClient mc) {
        BlockPos o = mc.player.getBlockPos();
        BlockPos best = null;
        double bestD = Double.MAX_VALUE;
        for (int dx = -12; dx <= 12; ++dx) {
            for (int dz = -12; dz <= 12; ++dz) {
                double d;
                BlockPos p = new BlockPos(o.getX() + dx, this.levelY, o.getZ() + dz);
                if (this.skip.containsKey(p) || !this.canPlantAt(mc, p) || !((d = p.getSquaredDistance(mc.player.getPos())) < bestD)) continue;
                bestD = d;
                best = p;
            }
        }
        return best;
    }

    private BlockPos groundPos(MinecraftClient mc) {
        BlockPos p = mc.player.getBlockPos();
        return mc.world.getBlockState(p).getCollisionShape(mc.world, p).isEmpty() ? p.down() : p;
    }

    private void stopMove(MinecraftClient mc) {
        mc.options.forwardKey.setPressed(false);
        mc.options.sprintKey.setPressed(false);
        mc.options.jumpKey.setPressed(false);
        mc.options.backKey.setPressed(false);
        mc.options.leftKey.setPressed(false);
        mc.options.rightKey.setPressed(false);
    }

    private boolean ensureSeedInHand(MinecraftClient mc) {
        PlayerInventory in = mc.player.getInventory();
        if (this.host.isPlantableSeed(in.getStack(in.selectedSlot))) {
            return true;
        }
        for (int i = 0; i < 9; ++i) {
            if (!this.host.isPlantableSeed(in.getStack(i))) continue;
            in.setSelectedSlot(i);
            return true;
        }
        return false;
    }

    private int firstEmptyHotbar(MinecraftClient mc) {
        PlayerInventory in = mc.player.getInventory();
        for (int i = 0; i < 9; ++i) {
            if (!in.getStack(i).isEmpty()) continue;
            return i;
        }
        return -1;
    }

    private int findSeedInMainInventory(MinecraftClient mc) {
        PlayerInventory in = mc.player.getInventory();
        for (int i = 9; i < 36; ++i) {
            if (!this.host.isPlantableSeed(in.getStack(i))) continue;
            return i;
        }
        return -1;
    }

    private static enum Inv {
        FARM,
        OPEN_INV,
        SWAP,
        CLOSE;

    }
}

