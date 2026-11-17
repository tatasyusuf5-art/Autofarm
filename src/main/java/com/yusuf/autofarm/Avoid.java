package com.yusuf.autofarm;

import java.util.HashMap;
import java.util.Map;
import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.client.MinecraftClient;
import net.minecraft.registry.Registries;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;

/**
 * Su / fener (lantern) / tehlikeli blok kacinma yardimcisi.
 * Hiz ve tick sayisini degistirmez; sadece hareket yonunu secer.
 */
final class Avoid {
    enum Move { MOVING, HOLD, BLOCKED, DROWNING }

    private static final double LOOK = 1.6;
    private static final double STEP = 0.4;
    private static final double SIDE = 0.3;
    private static final int[] OFFSETS = {25, 50, 75, 100, 130, 160};
    private static final Map<Block, Boolean> LAMP = new HashMap<Block, Boolean>();

    private int side = 1;
    private int waterTicks = 0;
    private double bestDist = Double.MAX_VALUE;
    private int stall = 0;
    private int blocked = 0;

    void reset() {
        this.side = 1;
        this.waterTicks = 0;
        this.newTarget();
    }

    void newTarget() {
        this.bestDist = Double.MAX_VALUE;
        this.stall = 0;
        this.blocked = 0;
    }

    /** to == null: hedef yok; sadece suda isek kurtar. */
    Move drive(MinecraftClient mc, Vec3d to) {
        Vec3d p = mc.player.getPos();
        float want = Float.NaN;
        double hor = 0.0;
        if (to != null) {
            double dx = to.x - p.x;
            double dz = to.z - p.z;
            hor = Math.sqrt(dx * dx + dz * dz);
            want = (float) (Math.toDegrees(Math.atan2(dz, dx)) - 90.0);
        }
        if (mc.player.isTouchingWater()) {
            return this.rescue(mc, want);
        }
        this.waterTicks = 0;
        if (to == null) {
            return Move.HOLD;
        }
        if (hor < this.bestDist - 0.3) {
            this.bestDist = hor;
            this.stall = 0;
        } else if (++this.stall > 60) {
            return Move.BLOCKED;
        }
        double look = Math.min(LOOK, hor + 0.3);
        float yaw = this.pickYaw(mc, p, want, look);
        if (Float.isNaN(yaw)) {
            mc.options.forwardKey.setPressed(false);
            mc.options.sprintKey.setPressed(false);
            mc.options.jumpKey.setPressed(false);
            return ++this.blocked > 10 ? Move.BLOCKED : Move.HOLD;
        }
        this.blocked = 0;
        mc.player.setYaw(yaw);
        mc.options.forwardKey.setPressed(true);
        mc.options.sprintKey.setPressed(true);
        mc.options.jumpKey.setPressed(mc.player.horizontalCollision);
        return Move.MOVING;
    }

    private float pickYaw(MinecraftClient mc, Vec3d p, float want, double look) {
        if (this.clear(mc, p, want, look)) {
            return want;
        }
        for (int k : OFFSETS) {
            for (int i = 0; i < 2; ++i) {
                int s = i == 0 ? this.side : -this.side;
                float y = want + s * k;
                if (this.clear(mc, p, y, look)) {
                    this.side = s;
                    return y;
                }
            }
        }
        return Float.NaN;
    }

    private boolean clear(MinecraftClient mc, Vec3d p, float yaw, double look) {
        double r = Math.toRadians(yaw);
        double fx = -Math.sin(r);
        double fz = Math.cos(r);
        double px = Math.cos(r);
        double pz = Math.sin(r);
        int yLow = MathHelper.floor(p.y - 0.5);
        int yHigh = MathHelper.floor(p.y + 1.7);
        for (double d = STEP; d <= look + 1.0E-6; d += STEP) {
            for (int l = -1; l <= 1; ++l) {
                double x = p.x + fx * d + px * SIDE * l;
                double z = p.z + fz * d + pz * SIDE * l;
                int bx = MathHelper.floor(x);
                int bz = MathHelper.floor(z);
                for (int y = yLow; y <= yHigh; ++y) {
                    if (this.isBad(mc, new BlockPos(bx, y, bz))) {
                        return false;
                    }
                }
            }
        }
        return true;
    }

    private boolean isBad(MinecraftClient mc, BlockPos pos) {
        BlockState s = mc.world.getBlockState(pos);
        if (!s.getFluidState().isEmpty()) {
            return true;
        }
        Block b = s.getBlock();
        if (b == Blocks.AIR) {
            return false;
        }
        return isLamp(b) || AutoFarmClient.isHazard(b);
    }

    private static boolean isLamp(Block b) {
        Boolean v = LAMP.get(b);
        if (v == null) {
            String path = Registries.BLOCK.getId(b).getPath();
            v = path.contains("lantern") || path.contains("chain");
            LAMP.put(b, v);
        }
        return v;
    }

    // ---- Sudan cikis ----

    private Move rescue(MinecraftClient mc, float want) {
        if (++this.waterTicks > 600) {
            return Move.DROWNING;
        }
        BlockPos o = mc.player.getBlockPos();
        Vec3d bank = null;
        double bestAng = Double.MAX_VALUE;
        for (int r = 1; r <= 6 && bank == null; ++r) {
            for (int dx = -r; dx <= r; ++dx) {
                for (int dz = -r; dz <= r; ++dz) {
                    if (Math.max(Math.abs(dx), Math.abs(dz)) != r) {
                        continue;
                    }
                    for (int dy = 1; dy >= -2; --dy) {
                        BlockPos q = new BlockPos(o.getX() + dx, o.getY() + dy, o.getZ() + dz);
                        if (!this.dryStand(mc, q)) {
                            continue;
                        }
                        double ang = 0.0;
                        if (!Float.isNaN(want)) {
                            double yaw = Math.toDegrees(Math.atan2(dz, dx)) - 90.0;
                            ang = Math.abs(MathHelper.wrapDegrees(yaw - want));
                        }
                        if (ang < bestAng) {
                            bestAng = ang;
                            bank = Vec3d.ofBottomCenter(q);
                        }
                        break;
                    }
                }
            }
        }
        Vec3d p = mc.player.getPos();
        if (bank != null) {
            double dx = bank.x - p.x;
            double dz = bank.z - p.z;
            mc.player.setYaw((float) (Math.toDegrees(Math.atan2(dz, dx)) - 90.0));
        } else if (!Float.isNaN(want)) {
            mc.player.setYaw(want);
        }
        mc.player.setPitch(0.0f);
        mc.options.forwardKey.setPressed(true);
        mc.options.sprintKey.setPressed(false);
        mc.options.jumpKey.setPressed(true);
        return Move.MOVING;
    }

    private boolean dryStand(MinecraftClient mc, BlockPos q) {
        BlockPos f = q.down();
        BlockState fs = mc.world.getBlockState(f);
        if (fs.getCollisionShape(mc.world, f).isEmpty() || !fs.getFluidState().isEmpty()) {
            return false;
        }
        if (isLamp(fs.getBlock()) || AutoFarmClient.isHazard(fs.getBlock())) {
            return false;
        }
        for (int i = 0; i < 2; ++i) {
            BlockPos c = q.up(i);
            BlockState s = mc.world.getBlockState(c);
            if (!s.getFluidState().isEmpty() || !s.getCollisionShape(mc.world, c).isEmpty()) {
                return false;
            }
            if (isLamp(s.getBlock()) || AutoFarmClient.isHazard(s.getBlock())) {
                return false;
            }
        }
        return true;
    }
}
