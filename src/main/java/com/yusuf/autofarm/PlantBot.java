package com.yusuf.autofarm;

import net.minecraft.block.BlockState;
import net.minecraft.block.CropBlock;
import net.minecraft.block.Blocks;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.screen.ingame.InventoryScreen;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.entity.player.PlayerInventory;
import net.minecraft.item.BlockItem;
import net.minecraft.item.ItemStack;
import net.minecraft.registry.tag.FluidTags;
import net.minecraft.screen.slot.SlotActionType;
import net.minecraft.util.Hand;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;
import net.minecraft.util.math.Vec3i;
import net.minecraft.world.BlockView;

/**
 * Mod 2: Ek. PatatesBot'un ekme sistemi birebir (şerit/yılan düzeni, tick başına 1 ekim,
 * her ekimde kamera hedefe döner, bloğun ortasına tıklar). Tek fark: ekilecek şey
 * patates yerine menüde seçili ekinlerdir (patates, havuç, buğday, pancar).
 */
public class PlantBot {
    private enum Inv { FARM, OPEN_INV, SWAP, CLOSE }

    private enum Next { SNAKE, BP_OUT, BP_FWD, BP_BACK }

    private final AutoFarmClient host;
    private boolean initPending = true;
    private Inv inv = Inv.FARM;
    private int timer = 0;
    private int waitMsgTimer = 0;
    private Direction dir = Direction.NORTH;
    private Direction side = null;
    private boolean sideStep = false;
    private boolean brake = false;
    private boolean near = false;
    private int nearTicks = 0;
    private Direction walkDir = Direction.NORTH;
    private Vec3d walkGoal = null;
    private Next next = Next.SNAKE;
    private Direction bpSide = null;
    private BlockPos bpOrigin = null;
    private int waterTicks = 0;
    private int brakeTicks = 0;
    private int walkTicks = 0;
    private int refillSwaps = 0;
    private int stuckTicks = 0;
    private Vec3d walkStart = null;
    private Vec3d laneC = null;

    PlantBot(AutoFarmClient host) {
        this.host = host;
    }

    void reset() {
        this.initPending = true;
    }

    /** Bot kendi envanter ekranını açtıysa tick'lerin çalışmaya devam etmesi gerekir. */
    boolean wantsInventory(MinecraftClient mc) {
        return this.inv != Inv.FARM && mc.currentScreen instanceof InventoryScreen;
    }

    private void disable(MinecraftClient mc, String msg) {
        this.stopMove(mc);
        this.host.autoOff(mc, msg);
    }

    void tick(MinecraftClient mc) {
        this.host.markKeys();
        if (this.initPending) {
            this.initPending = false;
            this.stopMove(mc);
            this.inv = Inv.FARM;
            this.dir = Direction.fromHorizontalDegrees((double)mc.player.getYaw());
            this.side = null;
            this.sideStep = false;
            this.near = true;
            this.nearTicks = 0;
            this.waterTicks = 0;
            this.brake = false;
            this.laneC = Vec3d.ofCenter((Vec3i)this.groundPos(mc));
        }
        switch (this.inv) {
            case OPEN_INV: {
                if (--this.timer <= 0) {
                    this.inv = Inv.SWAP;
                    this.timer = 6;
                }
                return;
            }
            case SWAP: {
                if (--this.timer > 0) {
                    return;
                }
                int slot = this.findSeedInMainInventory(mc);
                if (slot != -1) {
                    int hb = this.firstEmptyHotbar(mc);
                    if (hb == -1) {
                        hb = mc.player.getInventory().selectedSlot;
                    }
                    mc.interactionManager.clickSlot(mc.player.playerScreenHandler.syncId, slot, hb, SlotActionType.SWAP, (PlayerEntity)mc.player);
                    if (++this.refillSwaps < 10 && this.firstEmptyHotbar(mc) != -1 && this.findSeedInMainInventory(mc) != -1) {
                        this.timer = 2;
                        return;
                    }
                }
                this.inv = Inv.CLOSE;
                this.timer = 6;
                return;
            }
            case CLOSE: {
                if (--this.timer > 0) {
                    return;
                }
                mc.player.closeHandledScreen();
                this.inv = Inv.FARM;
                return;
            }
            default:
                break;
        }
        if (mc.currentScreen != null) {
            return;
        }
        if (!this.ensureSeedInHand(mc)) {
            this.stopMove(mc);
            if (this.findSeedInMainInventory(mc) != -1) {
                mc.setScreen(new InventoryScreen((PlayerEntity)mc.player));
                this.inv = Inv.OPEN_INV;
                this.timer = 6;
                this.refillSwaps = 0;
            } else if (--this.waitMsgTimer <= 0) {
                this.host.say(mc, "Envanterde seçili ekin yok, bekliyorum...");
                this.waitMsgTimer = 40;
            }
            return;
        }
        mc.options.leftKey.setPressed(false);
        mc.options.rightKey.setPressed(false);
        if (mc.player.isTouchingWater() && !this.sideStep) {
            if (++this.waterTicks > 200) {
                this.disable(mc, "Suda takıldı, bot kapandı");
                return;
            }
            Direction go = this.waterTicks > 25 ? this.findBankDir(mc) : this.dir;
            this.smoothRotate(mc, go.getPositiveHorizontalDegrees(), 45.0f);
            mc.options.forwardKey.setPressed(true);
            mc.options.sprintKey.setPressed(false);
            boolean lowAir = mc.player.getAir() < 120;
            mc.options.jumpKey.setPressed(lowAir || this.waterJump(mc, go));
            return;
        }
        if (this.waterTicks > 0) {
            this.waterTicks = 0;
            this.near = true;
            this.nearTicks = 0;
            this.laneC = Vec3d.ofCenter((Vec3i)this.groundPos(mc));
        }
        Direction facing = this.sideStep ? this.walkDir : this.dir;
        BlockPos ground = this.groundPos(mc);
        BlockPos nearTarget = null;
        if (this.near && !this.sideStep) {
            if (this.canPlant(mc, ground)) {
                nearTarget = ground;
            } else if (this.canPlant(mc, ground.offset(this.dir))) {
                nearTarget = ground.offset(this.dir);
            }
            if (nearTarget == null || ++this.nearTicks > 40) {
                this.near = false;
            }
        }
        float pitchGoal = this.near && nearTarget != null ? this.pitchToward(mc, nearTarget) : 45.0f;
        float remaining = this.smoothRotate(mc, facing.getPositiveHorizontalDegrees(), pitchGoal);
        Vec3d vel = mc.player.getVelocity();
        double spd = vel.horizontalLength();
        if (this.brake && spd > 0.05 && ++this.brakeTicks <= 8) {
            mc.options.forwardKey.setPressed(false);
            mc.options.sprintKey.setPressed(false);
            Vec3d look = Vec3d.fromPolar((float)0.0f, (float)mc.player.getYaw());
            double along = (vel.x * look.x + vel.z * look.z) / spd;
            mc.options.backKey.setPressed(along > 0.7);
            return;
        }
        this.brake = false;
        this.brakeTicks = 0;
        mc.options.backKey.setPressed(false);
        boolean aligned = remaining < 12.0f;
        if (!aligned) {
            mc.options.forwardKey.setPressed(false);
            mc.options.sprintKey.setPressed(false);
            return;
        }
        if (this.sideStep) {
            this.doWalk(mc);
            return;
        }
        if (this.near) {
            mc.options.forwardKey.setPressed(false);
            mc.options.sprintKey.setPressed(false);
            this.tryPlant(mc);
            return;
        }
        int la = this.lookAhead(mc, ground);
        this.stuckTicks = mc.player.horizontalCollision ? ++this.stuckTicks : 0;
        if (la == 0 && this.stuckTicks > 15) {
            this.stuckTicks = 0;
            la = 1;
        }
        if (la == 1) {
            this.beginSideStep(mc, ground);
            return;
        }
        if (la == 2) {
            this.beginBypass(mc, ground);
            return;
        }
        BlockPos a2 = ground.offset(this.dir, 2);
        boolean clear2 = this.walkable(mc, a2) || mc.world.getBlockState(a2).getFluidState().isIn(FluidTags.WATER);
        mc.options.forwardKey.setPressed(true);
        mc.options.sprintKey.setPressed(clear2);
        mc.options.jumpKey.setPressed(false);
        this.strafeFix(mc, this.dir);
        this.tryPlant(mc);
    }

    private void tryPlant(MinecraftClient mc) {
        BlockPos target = this.findPlantSpot(mc);
        if (target == null) {
            return;
        }
        Vec3d eye = mc.player.getEyePos();
        Vec3d c = Vec3d.ofCenter((Vec3i)target);
        double dx = c.x - eye.x;
        double dy = c.y + 0.1 - eye.y;
        double dz = c.z - eye.z;
        double hor = Math.sqrt(dx * dx + dz * dz);
        mc.player.setYaw((float)(Math.toDegrees(Math.atan2(dz, dx)) - 90.0));
        mc.player.setPitch((float)MathHelper.clamp((double)(-Math.toDegrees(Math.atan2(dy, hor))), (double)-10.0, (double)90.0));
        mc.interactionManager.interactBlock(mc.player, Hand.MAIN_HAND, new BlockHitResult(c, Direction.UP, target, false));
        mc.player.swingHand(Hand.MAIN_HAND);
    }

    private BlockPos findPlantSpot(MinecraftClient mc) {
        BlockPos o = this.groundPos(mc);
        Direction fwd = this.sideStep ? this.walkDir : this.dir;
        Direction left = fwd.rotateYCounterclockwise();
        for (int ileri = 0; ileri <= 2; ++ileri) {
            for (int yan = -1; yan <= 1; ++yan) {
                BlockPos p = o.offset(fwd, ileri).offset(left, yan);
                if (!this.canPlant(mc, p)) continue;
                return p;
            }
        }
        return null;
    }

    private float pitchToward(MinecraftClient mc, BlockPos b) {
        Vec3d eye = mc.player.getEyePos();
        double tx = (double)b.getX() + 0.5;
        double ty = (double)b.getY() + 0.9375;
        double tz = (double)b.getZ() + 0.5;
        double horiz = (tx - eye.x) * (double)this.dir.getOffsetX() + (tz - eye.z) * (double)this.dir.getOffsetZ();
        double vert = eye.y - ty;
        double deg = Math.toDegrees(Math.atan2(vert, Math.max(horiz, 0.0)));
        return (float)MathHelper.clamp((double)deg, (double)0.0, (double)89.0);
    }

    private void beginSideStep(MinecraftClient mc, BlockPos ground) {
        this.stopMove(mc);
        this.brake = true;
        Direction chosen = null;
        if (this.side == null) {
            for (Direction d : new Direction[]{this.dir.rotateYClockwise(), this.dir.rotateYCounterclockwise()}) {
                if (!this.isFloor(mc.world.getBlockState(ground.offset(d)))) continue;
                chosen = d;
                break;
            }
        } else if (this.isFloor(mc.world.getBlockState(ground.offset(this.side)))) {
            chosen = this.side;
        }
        if (chosen == null) {
            this.disable(mc, "Tarla bitti, bot kapandı");
            return;
        }
        this.walkDir = this.side = chosen;
        this.walkGoal = Vec3d.ofCenter((Vec3i)ground.offset(this.side, 2));
        this.next = Next.SNAKE;
        this.walkTicks = 0;
        this.sideStep = true;
    }

    private void beginBypass(MinecraftClient mc, BlockPos ground) {
        this.stopMove(mc);
        this.brake = true;
        this.bpOrigin = ground;
        this.walkDir = this.bpSide;
        this.walkGoal = Vec3d.ofCenter((Vec3i)ground.offset(this.bpSide));
        this.next = Next.BP_OUT;
        this.walkTicks = 0;
        this.sideStep = true;
    }

    private void doWalk(MinecraftClient mc) {
                if (++this.walkTicks > 120) {
            this.disable(mc, "Dolanırken takıldı, bot kapandı");
            return;
        }
        Vec3d pos = mc.player.getPos();
        if (this.walkTicks == 1) {
            this.walkStart = pos;
        }
        double remain = (this.walkGoal.x - pos.x) * (double)this.walkDir.getOffsetX() + (this.walkGoal.z - pos.z) * (double)this.walkDir.getOffsetZ();
        Vec3d v = mc.player.getVelocity();
        double speedAlong = Math.max(0.0, v.x * (double)this.walkDir.getOffsetX() + v.z * (double)this.walkDir.getOffsetZ());
        boolean tooFar = this.walkStart != null && this.walkStart.distanceTo(pos) > 3.5;
        if (remain < 0.15 + speedAlong * 1.3 || tooFar) {
            this.stopMove(mc);
            this.brake = true;
            this.walkTicks = 0;
            switch (this.next) {
                case SNAKE: {
                    this.laneC = this.walkGoal;
                    this.dir = this.dir.getOpposite();
                    this.sideStep = false;
                    this.near = true;
                    this.nearTicks = 0;
                    break;
                }
                case BP_OUT: {
                    this.laneC = this.walkGoal;
                    this.walkDir = this.dir;
                    this.walkGoal = Vec3d.ofCenter((Vec3i)this.bpOrigin.offset(this.dir, 2));
                    this.next = Next.BP_FWD;
                    break;
                }
                case BP_FWD: {
                    this.walkDir = this.bpSide.getOpposite();
                    this.walkGoal = Vec3d.ofCenter((Vec3i)this.bpOrigin.offset(this.dir, 2));
                    this.next = Next.BP_BACK;
                    break;
                }
                case BP_BACK: {
                    this.laneC = this.walkGoal;
                    this.sideStep = false;
                    this.near = true;
                    this.nearTicks = 0;
                }
            }
            return;
        }
        mc.options.forwardKey.setPressed(true);
        mc.options.sprintKey.setPressed(false);
        mc.options.jumpKey.setPressed(mc.player.isTouchingWater() && this.waterJump(mc));
        if (this.next == Next.BP_FWD) {
            this.strafeFix(mc, this.walkDir);
        }
    }

    private boolean waterJump(MinecraftClient mc) {
        return this.waterJump(mc, this.sideStep ? this.walkDir : this.dir);
    }

    private Direction findBankDir(MinecraftClient mc) {
        BlockPos f = mc.player.getBlockPos();
        Direction best = this.dir;
        int bestK = 99;
        block0: for (Direction d : new Direction[]{this.dir, this.dir.rotateYClockwise(), this.dir.rotateYCounterclockwise(), this.dir.getOpposite()}) {
            for (int k = 1; k <= 5; ++k) {
                BlockPos b = f.offset(d, k);
                BlockState st = mc.world.getBlockState(b);
                if (st.getFluidState().isIn(FluidTags.WATER) || st.getCollisionShape((BlockView)mc.world, b).isEmpty()) continue;
                if (k >= bestK) continue block0;
                bestK = k;
                best = d;
                continue block0;
            }
        }
        return best;
    }

    private boolean waterJump(MinecraftClient mc, Direction d) {
        if (mc.player.horizontalCollision) {
            return true;
        }
        BlockPos f = mc.player.getBlockPos().offset(d);
        BlockState st = mc.world.getBlockState(f);
        return !st.getFluidState().isIn(FluidTags.WATER) && !st.getCollisionShape((BlockView)mc.world, f).isEmpty();
    }

    private void strafeFix(MinecraftClient mc, Direction facing) {
        mc.options.leftKey.setPressed(false);
        mc.options.rightKey.setPressed(false);
        if (this.laneC == null) {
            return;
        }
        Direction right = facing.rotateYClockwise();
        Vec3d pos = mc.player.getPos();
        Vec3d vel = mc.player.getVelocity();
        double off = (pos.x - this.laneC.x) * (double)right.getOffsetX() + (pos.z - this.laneC.z) * (double)right.getOffsetZ();
        double vlat = vel.x * (double)right.getOffsetX() + vel.z * (double)right.getOffsetZ();
        double pred = off + vlat * 2.0;
        if (pred > 0.12) {
            mc.options.leftKey.setPressed(true);
        } else if (pred < -0.12) {
            mc.options.rightKey.setPressed(true);
        }
    }

    private float smoothRotate(MinecraftClient mc, float targetYaw, float targetPitch) {
        float curYaw = mc.player.getYaw();
        float dy = MathHelper.clamp((float)MathHelper.wrapDegrees((float)(targetYaw - curYaw)), (float)-30.0f, (float)30.0f);
        mc.player.setYaw(curYaw + dy);
        float dp = MathHelper.clamp((float)(targetPitch - mc.player.getPitch()), (float)-12.0f, (float)12.0f);
        mc.player.setPitch(mc.player.getPitch() + dp);
        return Math.abs(MathHelper.wrapDegrees((float)(targetYaw - mc.player.getYaw())));
    }

    private BlockPos groundPos(MinecraftClient mc) {
        BlockPos p = mc.player.getBlockPos();
        return mc.world.getBlockState(p).isOf(Blocks.FARMLAND) ? p : p.down();
    }

    private boolean isFloor(BlockState st) {
        return st.isOf(Blocks.FARMLAND);
    }

    private boolean walkable(MinecraftClient mc, BlockPos p) {
        if (!this.isFloor(mc.world.getBlockState(p))) {
            return false;
        }
        for (int i = 1; i <= 2; ++i) {
            BlockPos up = p.up(i);
            if (mc.world.getBlockState(up).getCollisionShape((BlockView)mc.world, up).isEmpty()) continue;
            return false;
        }
        return true;
    }

    private int lookAhead(MinecraftClient mc, BlockPos ground) {
        BlockPos ahead = ground.offset(this.dir);
        if (this.walkable(mc, ahead)) {
            return 0;
        }
        BlockState st = mc.world.getBlockState(ahead);
        if (st.getFluidState().isIn(FluidTags.WATER)) {
            for (int k = 2; k <= 4; ++k) {
                BlockPos b = ground.offset(this.dir, k);
                if (this.walkable(mc, b)) {
                    return 0;
                }
                if (!mc.world.getBlockState(b).getFluidState().isIn(FluidTags.WATER)) break;
            }
            return 1;
        }
        if (this.walkable(mc, ground.offset(this.dir, 2))) {
            Direction[] order = this.side != null
                ? new Direction[]{this.side.getOpposite(), this.side}
                : new Direction[]{this.dir.rotateYClockwise(), this.dir.rotateYCounterclockwise()};
            for (Direction d : order) {
                BlockPos l0 = ground.offset(d);
                if (!this.walkable(mc, l0) || !this.walkable(mc, l0.offset(this.dir)) || !this.walkable(mc, l0.offset(this.dir, 2))) continue;
                this.bpSide = d;
                return 2;
            }
        }
        return 1;
    }

    private boolean canPlant(MinecraftClient mc, BlockPos p) {
        BlockState st = mc.world.getBlockState(p);
        boolean ok = st.isOf(Blocks.FARMLAND);
        return ok && mc.world.getBlockState(p.up()).isAir();
    }

    private boolean isTool(ItemStack st) {
        return this.host.isPlantableSeed(st) && ((BlockItem)st.getItem()).getBlock() instanceof CropBlock;
    }

    private boolean ensureSeedInHand(MinecraftClient mc) {
        PlayerInventory in = mc.player.getInventory();
        if (this.isTool(in.getStack(in.selectedSlot))) {
            return true;
        }
        for (int i = 0; i < 9; ++i) {
            if (!this.isTool(in.getStack(i))) continue;
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
            if (!this.isTool(in.getStack(i))) continue;
            return i;
        }
        return -1;
    }

    private void stopMove(MinecraftClient mc) {
        mc.options.forwardKey.setPressed(false);
        mc.options.sprintKey.setPressed(false);
        mc.options.jumpKey.setPressed(false);
        mc.options.backKey.setPressed(false);
        mc.options.leftKey.setPressed(false);
        mc.options.rightKey.setPressed(false);
    }
}
