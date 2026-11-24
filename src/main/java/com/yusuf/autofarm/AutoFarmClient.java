package com.yusuf.autofarm;

import com.mojang.brigadier.arguments.IntegerArgumentType;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.PriorityQueue;
import java.util.Set;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandManager;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.block.CropBlock;
import net.minecraft.block.NetherWartBlock;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.client.util.InputUtil;
import net.minecraft.client.world.ClientWorld;
import net.minecraft.component.type.ItemEnchantmentsComponent;
import net.minecraft.enchantment.Enchantment;
import net.minecraft.enchantment.Enchantments;
import net.minecraft.entity.Entity;
import net.minecraft.entity.ItemEntity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.entity.player.PlayerInventory;
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
import net.minecraft.util.math.Position;
import net.minecraft.util.math.Vec3d;
import net.minecraft.util.math.Vec3i;
import net.minecraft.world.BlockView;
import net.minecraft.world.WorldView;

public class AutoFarmClient
implements ClientModInitializer {
    private static final int SEARCH_RADIUS = 12;
    private static final double WALK_STOP = 3.0;
    private static final double PLANT_STRICT = 0.3;
    private static final double PLANT_RELAXED = 0.9;
    private static final int AIM_TICKS = 1;
    private static final int TARGET_TIMEOUT = 400;
    private static final int MAX_EXPANSIONS = 3000;
    private static final int PATH_RANGE = 24;
    private static final String[] MODE_NAMES = new String[]{"1: Kır (olgun ekinleri kırar)", "2: Ek (boş tarlaları eker)", "3: Kır + Ek (kırar ve geri eker)", "4: Kemik tozu (baktığın blok: ek + toz + kır)"};
    private static final int[][] DIRS = new int[][]{{1, 0}, {-1, 0}, {0, 1}, {0, -1}};
    private final AutoFarmConfig cfg = AutoFarmConfig.get();
    private boolean enabled = false;
    private boolean openGui = false;
    private boolean prevG = false;
    private boolean announce = true;
    private BlockPos targetPos;
    private Kind targetKind;
    private ItemEntity targetItem;
    private int targetTicks;
    private int aimedTicks;
    private int actionCooldown;
    private int tickCounter;
    private int warnCooldown;
    private boolean keysHeld;
    private List<BlockPos> path;
    private int pathIdx;
    private int replans;
    private double approach = 0.3;
    private final Map<BlockPos, Integer> blacklist = new HashMap<BlockPos, Integer>();
    private final Map<BlockPos, Integer> replant = new HashMap<BlockPos, Integer>();
    private final Set<Integer> ignoredItems = new HashSet<Integer>();
    private final PlantBot plantBot = new PlantBot(this);
    private final Macro macro = new Macro(this);
    private boolean prevH = false;

    // PatatesBot hasat sistemi (Mod 1: Kır) için durum
    private boolean pbLevelPending = true;
    private final Avoid pbAvoid = new Avoid();
    private BlockPos pbTarget = null;
    private int pbTicks = 0;
    private int pbLevelY = 0;
    private int pbWait = 0;
    private final Map<BlockPos, Long> pbSkip = new HashMap<BlockPos, Long>();

    public void onInitializeClient() {
        this.cfg.load();
        ClientTickEvents.END_CLIENT_TICK.register(this::onTick);
        ClientCommandRegistrationCallback.EVENT.register((dispatcher, registryAccess) -> dispatcher.register(
            ClientCommandManager.literal("autofarm")
                .executes(ctx -> {
                    this.toggle(MinecraftClient.getInstance());
                    return 1;
                })
                .then(ClientCommandManager.literal("gui").executes(ctx -> {
                    this.openGui = true;
                    return 1;
                }))
                .then(ClientCommandManager.literal("macro")
                    .then(ClientCommandManager.literal("1").executes(ctx -> {
                        this.macro.start(MinecraftClient.getInstance(), 1);
                        return 1;
                    }))
                    .then(ClientCommandManager.literal("2").executes(ctx -> {
                        this.macro.start(MinecraftClient.getInstance(), 2);
                        return 1;
                    }))
                    .then(ClientCommandManager.literal("3").executes(ctx -> {
                        this.macro.start(MinecraftClient.getInstance(), 3);
                        return 1;
                    }))
                    .then(ClientCommandManager.literal("stop").executes(ctx -> {
                        this.macro.stop(MinecraftClient.getInstance(), "Makro durduruldu");
                        return 1;
                    })))
                .then(ClientCommandManager.literal("mode")
                    .then(ClientCommandManager.argument("n", IntegerArgumentType.integer(1, MODE_NAMES.length)).executes(ctx -> {
                        this.cfg.mode = IntegerArgumentType.getInteger(ctx, "n") - 1;
                        this.cfg.save();
                        this.resetState(MinecraftClient.getInstance());
                        this.say(MinecraftClient.getInstance(), "Mod -> " + MODE_NAMES[this.cfg.mode]);
                        return 1;
                    })))
                .then(ClientCommandManager.literal("speed")
                    .then(ClientCommandManager.argument("n", IntegerArgumentType.integer(1, 15)).executes(ctx -> {
                        this.cfg.speed = IntegerArgumentType.getInteger(ctx, "n");
                        this.cfg.save();
                        this.say(MinecraftClient.getInstance(), "Hız -> " + this.cfg.speed + "/15");
                        return 1;
                    })))));
    }

    Macro macro() {
        return this.macro;
    }

    public boolean isEnabled() {
        return this.enabled;
    }

    public void toggle(MinecraftClient mc) {
        this.enabled = !this.enabled;
        if (this.enabled && this.macro.isRunning()) {
            this.macro.stop(mc, "Makro durduruldu (çiftlik başladı)");
        }
        this.resetState(mc);
        String msg = "AutoFarm " + (this.enabled ? "AÇIK" : "KAPALI") + " | Mod " + MODE_NAMES[this.cfg.mode];
        this.say(mc, msg);
        if (mc.player != null) {
            mc.player.sendMessage((Text)Text.literal((String)("[AutoFarm] " + msg)), false);
        }
    }

    private void resetState(MinecraftClient mc) {
        this.clearTarget();
        this.blacklist.clear();
        this.replant.clear();
        this.ignoredItems.clear();
        this.pbReset();
        this.plantBot.reset();
        this.releaseKeys(mc);
    }

    void say(MinecraftClient mc, String msg) {
        if (mc.player != null) {
            mc.player.sendMessage((Text)Text.literal((String)msg), true);
        }
    }

    private void warn(MinecraftClient mc, String msg) {
        if (this.warnCooldown == 0) {
            this.say(mc, msg);
            this.warnCooldown = 40;
        }
    }

    private float lookStep() {
        return 9.0f * (float)this.cfg.speed;
    }

    private int actionDelay() {
        return this.cfg.speed >= 10 ? 0 : Math.max(1, 6 - this.cfg.speed / 2);
    }

    private void onTick(MinecraftClient mc) {
        ++this.tickCounter;
        if (this.announce && mc.player != null && mc.world != null) {
            this.announce = false;
            mc.player.sendMessage((Text)Text.literal((String)"[AutoFarm] Yüklendi. G = aç/kapat, R + G = menü, ya da /autofarm gui"), false);
        }
        this.macro.tick(mc);
        if (this.macro.isRunning() && mc.player != null && !(mc.currentScreen instanceof AutoFarmMacroScreen)) {
            boolean h = InputUtil.isKeyPressed((long)mc.getWindow().getHandle(), (int)72);
            if (h && !this.prevH) {
                this.macro.stop(mc, "Makro durduruldu (H)");
            }
            this.prevH = h;
        } else {
            this.prevH = false;
        }
        boolean g = false;
        boolean r = false;
        if (mc.currentScreen == null && mc.player != null) {
            long window = mc.getWindow().getHandle();
            g = InputUtil.isKeyPressed((long)window, (int)71);
            r = InputUtil.isKeyPressed((long)window, (int)82);
        }
        if (g && !this.prevG) {
            if (r) {
                this.openGui = true;
            } else {
                this.toggle(mc);
            }
        }
        this.prevG = g;
        if (this.openGui && mc.player != null && mc.currentScreen == null) {
            this.openGui = false;
            this.releaseKeys(mc);
            mc.setScreen(new AutoFarmScreen(this));
        } else {
            if (this.warnCooldown > 0) {
                --this.warnCooldown;
            }
            if (this.actionCooldown > 0) {
                --this.actionCooldown;
            }
            if (this.enabled && mc.player != null && mc.world != null && mc.interactionManager != null && (mc.currentScreen == null || this.cfg.mode == 1 && this.plantBot.wantsInventory(mc)) && !mc.player.isSpectator()) {
                if (this.tickCounter % 40 == 0) {
                    this.blacklist.values().removeIf(t -> t < this.tickCounter);
                    this.replant.values().removeIf(t -> t < this.tickCounter);
                }
                if (this.tickCounter % 1200 == 0) {
                    this.ignoredItems.clear();
                }
                if (this.cfg.mode == 3) {
                    this.releaseKeys(mc);
                    String lookMsg = LookFarm.tick(mc, this.cfg);
                    if (lookMsg != null) {
                        this.warn(mc, lookMsg);
                    }
                    return;
                }
                boolean harvestMode = this.cfg.mode == 0 || this.cfg.mode == 2;
                boolean plantMode = this.cfg.mode == 1 || this.cfg.mode == 2;
                if (harvestMode) {
                    ItemStack tool = mc.player.getMainHandStack();
                    if (this.cfg.requireHoe && !tool.isIn(ItemTags.HOES)) {
                        this.warn(mc, "Ana ele çapa al! (Çapa zorunluluğu açık)");
                        this.releaseKeys(mc);
                        return;
                    }
                    if (this.cfg.requireFortune && AutoFarmClient.fortuneLevel(tool) < 3) {
                        this.warn(mc, "Ana elde Servet III alet olmalı! (Servet III zorunluluğu açık)");
                        this.releaseKeys(mc);
                        return;
                    }
                }
                if (this.cfg.mode == 1) {
                    this.plantBot.tick(mc);
                    return;
                }
                if (this.cfg.mode == 0) {
                    this.pbHarvest(mc);
                    return;
                }
                Hand plantHand = null;
                Block plantBlock = null;
                if (plantMode) {
                    plantHand = this.findPlantHand(mc, this.cfg.mode == 1);
                    if (plantHand == null) {
                        this.supply(mc);
                        this.releaseKeys(mc);
                        return;
                    }
                    plantBlock = ((BlockItem)mc.player.getStackInHand(plantHand).getItem()).getBlock();
                }
                if (this.targetKind != null && !this.targetStillValid(mc, plantBlock)) {
                    this.clearTarget();
                }
                if (this.targetKind == null) {
                    this.findTarget(mc, plantBlock);
                    if (this.targetKind == null) {
                        this.releaseKeys(mc);
                        return;
                    }
                }
                if (++this.targetTicks > 400) {
                    this.releaseKeys(mc);
                    this.giveUp();
                } else {
                    this.step(mc, plantHand, plantBlock);
                }
            } else {
                this.releaseKeys(mc);
            }
        }
    }

    private void step(MinecraftClient mc, Hand plantHand, Block plantBlock) {
        ClientPlayerEntity p = mc.player;
        if (this.targetKind == Kind.ITEM) {
            Vec3d ip = this.targetItem.getPos();
            this.lookAt(p, ip.add(0.0, 0.1, 0.0));
            double hd = Math.hypot(ip.x - p.getX(), ip.z - p.getZ());
            if (hd <= 0.6) {
                this.releaseKeys(mc);
            } else {
                this.navigate(mc, ip, Kind.ITEM, 0.6);
            }
        } else {
            Vec3d aim = AutoFarmClient.aimPoint(this.targetPos, this.targetKind);
            this.lookAt(p, aim);
            double eyeDist = p.getEyePos().distanceTo(aim);
            double hd = Math.hypot(p.getX() - aim.x, p.getZ() - aim.z);
            boolean inReach = eyeDist <= 3.0;
            boolean standOk = this.targetKind == Kind.HARVEST ? inReach : hd <= this.approach;
            BlockHitResult bhr = null;
            boolean ok = false;
            if (inReach && p.raycast(p.getBlockInteractionRange(), 1.0f, false) instanceof BlockHitResult b && b.getType() == HitResult.Type.BLOCK) {
                bhr = b;
                if (this.targetKind == Kind.HARVEST) {
                    ok = b.getBlockPos().equals((Object)this.targetPos);
                } else {
                    ok = b.getBlockPos().equals((Object)this.targetPos.down()) && b.getSide() == Direction.UP;
                }
                if (!ok) {
                    BlockPos above;
                    BlockPos hp = b.getBlockPos();
                    BlockState hs = mc.world.getBlockState(hp);
                    boolean harvestMode = this.cfg.mode == 0 || this.cfg.mode == 2;
                    if (this.sameLevel(mc, hp) && harvestMode && AutoFarmClient.isMature(hs) && this.cfg.isSelected(hs.getBlock()) && !this.blacklist.containsKey(hp)) {
                        this.targetPos = hp.toImmutable();
                        this.targetKind = Kind.HARVEST;
                        ok = true;
                    } else if (plantBlock != null && b.getSide() == Direction.UP && this.sameLevel(mc, above = hp.up()) && mc.world.getBlockState(above).isAir() && (this.cfg.mode == 1 || this.replant.containsKey(above)) && plantBlock.getDefaultState().canPlaceAt((WorldView)mc.world, above)) {
                        this.targetPos = above.toImmutable();
                        this.targetKind = Kind.PLANT;
                        ok = true;
                    }
                }
            }
            if (!ok && !standOk) {
                this.aimedTicks = 0;
                if (this.targetKind == Kind.HARVEST) {
                    this.navigate(mc, aim, Kind.HARVEST, 3.0);
                } else {
                    this.navigate(mc, aim, Kind.PLANT, this.approach);
                }
            } else {
                this.releaseKeys(mc);
                if (!ok) {
                    this.aimedTicks = 0;
                } else if (++this.aimedTicks >= 1 && this.actionCooldown <= 0) {
                    if (this.targetKind == Kind.HARVEST) {
                        mc.interactionManager.attackBlock(this.targetPos, bhr.getSide());
                        p.swingHand(Hand.MAIN_HAND);
                        if (this.cfg.mode == 2) {
                            this.replant.put(this.targetPos.toImmutable(), this.tickCounter + 100);
                        }
                    } else {
                        ActionResult r = mc.interactionManager.interactBlock(p, plantHand, bhr);
                        if (r.isAccepted()) {
                            p.swingHand(plantHand);
                            this.replant.remove(this.targetPos);
                        } else {
                            this.blacklist.put(this.targetPos.toImmutable(), this.tickCounter + 200);
                        }
                    }
                    this.actionCooldown = this.actionDelay();
                    this.clearTarget();
                }
            }
        }
    }

    private Hand findPlantHand(MinecraftClient mc, boolean allowMain) {
        ClientPlayerEntity p = mc.player;
        if (allowMain && this.isSelectedSeed(p.getMainHandStack())) {
            return Hand.MAIN_HAND;
        }
        return this.isSelectedSeed(p.getOffHandStack()) ? Hand.OFF_HAND : null;
    }

    private void supply(MinecraftClient mc) {
        ClientPlayerEntity p = mc.player;
        ItemStack off = p.getOffHandStack();
        if (!off.isEmpty() && !AutoFarmClient.isSeed(off)) {
            this.warn(mc, "2. el dolu! Ekin otomatik 2. ele alınır, eli boşalt.");
        } else if (this.actionCooldown <= 0) {
            for (int i = 0; i < 36; ++i) {
                if (!this.isSelectedSeed(p.getInventory().getStack(i))) continue;
                int slot = i < 9 ? i + 36 : i;
                mc.interactionManager.clickSlot(p.playerScreenHandler.syncId, slot, 40, SlotActionType.SWAP, (PlayerEntity)p);
                this.actionCooldown = 10;
                return;
            }
            this.warn(mc, "Çantada seçili ekin bulunamadı!");
        }
    }

    private void findTarget(MinecraftClient mc, Block plantBlock) {
        ClientPlayerEntity p = mc.player;
        boolean harvest = this.cfg.mode == 0 || this.cfg.mode == 2;
        Vec3d eye = p.getEyePos();
        BlockPos origin = p.getBlockPos();
        double best = Double.MAX_VALUE;
        BlockPos bestPos = null;
        Kind bestKind = null;
        for (BlockPos pos : BlockPos.iterate((BlockPos)origin.add(-12, -1, -12), (BlockPos)origin.add(12, 1, 12))) {
            double d;
            if (this.blacklist.containsKey(pos) || !this.sameLevel(mc, pos)) continue;
            BlockState s = mc.world.getBlockState(pos);
            Kind k = null;
            if (harvest && AutoFarmClient.isMature(s) && this.cfg.isSelected(s.getBlock())) {
                k = Kind.HARVEST;
            } else if (plantBlock != null && s.isAir() && (this.cfg.mode == 1 || this.replant.containsKey(pos)) && plantBlock.getDefaultState().canPlaceAt((WorldView)mc.world, pos)) {
                k = Kind.PLANT;
            }
            if (k == null || !((d = eye.squaredDistanceTo(Vec3d.ofCenter((Vec3i)pos))) < best)) continue;
            best = d;
            bestPos = pos.toImmutable();
            bestKind = k;
        }
        if (bestPos != null) {
            this.targetPos = bestPos;
            this.targetKind = bestKind;
            this.targetTicks = 0;
            this.aimedTicks = 0;
        } else if (harvest) {
            Box box = p.getBoundingBox().expand(10.0, 3.0, 10.0);
            ItemEntity bestItem = null;
            double bd = Double.MAX_VALUE;
            for (ItemEntity ie : mc.world.getEntitiesByClass(ItemEntity.class, box, x -> !x.isRemoved() && !this.ignoredItems.contains(x.getId()))) {
                double d = ie.squaredDistanceTo((Entity)p);
                if (!(d < bd)) continue;
                bd = d;
                bestItem = ie;
            }
            if (bestItem != null) {
                this.targetItem = bestItem;
                this.targetKind = Kind.ITEM;
                this.targetTicks = 0;
                this.aimedTicks = 0;
            }
        }
    }

    private boolean targetStillValid(MinecraftClient mc, Block plantBlock) {
        switch (this.targetKind) {
            case HARVEST: {
                BlockState s = mc.world.getBlockState(this.targetPos);
                return AutoFarmClient.isMature(s) && this.cfg.isSelected(s.getBlock());
            }
            case PLANT:
                return plantBlock != null && mc.world.getBlockState(this.targetPos).isAir() && plantBlock.getDefaultState().canPlaceAt((WorldView)mc.world, this.targetPos);
            case ITEM:
                return this.targetItem != null && !this.targetItem.isRemoved();
            default:
                return false;
        }
    }

    private void clearTarget() {
        this.targetKind = null;
        this.targetPos = null;
        this.targetItem = null;
        this.targetTicks = 0;
        this.aimedTicks = 0;
        this.path = null;
        this.pathIdx = 0;
        this.replans = 0;
        this.approach = 0.3;
    }

    private void giveUp() {
        if (this.targetKind == Kind.ITEM && this.targetItem != null) {
            this.ignoredItems.add(this.targetItem.getId());
        } else if (this.targetPos != null) {
            this.blacklist.put(this.targetPos.toImmutable(), this.tickCounter + 600);
        }
        this.clearTarget();
    }

    private static Vec3d aimPoint(BlockPos pos, Kind kind) {
        return kind == Kind.HARVEST ? new Vec3d((double)pos.getX() + 0.5, (double)pos.getY() + 0.35, (double)pos.getZ() + 0.5) : new Vec3d((double)pos.getX() + 0.5, (double)(pos.getY() - 1) + 0.9375, (double)pos.getZ() + 0.5);
    }

    private void lookAt(ClientPlayerEntity p, Vec3d target) {
        float step = this.lookStep();
        Vec3d eye = p.getEyePos();
        double dx = target.x - eye.x;
        double dy = target.y - eye.y;
        double dz = target.z - eye.z;
        double h = Math.sqrt(dx * dx + dz * dz);
        float wantYaw = h < 0.5 ? p.getYaw() : (float)(Math.toDegrees(Math.atan2(dz, dx)) - 90.0);
        float wantPitch = (float)(-Math.toDegrees(Math.atan2(dy, h)));
        float yawDiff = MathHelper.wrapDegrees((float)(wantYaw - p.getYaw()));
        float pitchDiff = wantPitch - p.getPitch();
        p.setYaw(p.getYaw() + MathHelper.clamp((float)yawDiff, (float)(-step), (float)step));
        p.setPitch(MathHelper.clamp((float)(p.getPitch() + MathHelper.clamp((float)pitchDiff, (float)(-step), (float)step)), (float)-90.0f, (float)90.0f));
    }

    private static BlockPos feetCell(ClientPlayerEntity p) {
        return BlockPos.ofFloored((double)p.getX(), (double)p.getY(), (double)p.getZ());
    }

    private boolean sameLevel(MinecraftClient mc, BlockPos pos) {
        int feetY = AutoFarmClient.feetCell(mc.player).getY();
        return pos.getY() >= feetY - 1 && pos.getY() <= feetY + 1;
    }

    private void navigate(MinecraftClient mc, Vec3d goalAim, Kind kind, double radius) {
        ClientPlayerEntity p = mc.player;
        if (this.path == null) {
            List<BlockPos> found = this.findPath(mc, goalAim, kind, radius);
            if (found == null && kind == Kind.PLANT && radius < 0.9) {
                this.approach = 0.9;
                found = this.findPath(mc, goalAim, kind, 0.9);
            }
            if (found == null) {
                this.releaseKeys(mc);
                this.giveUp();
                return;
            }
            this.path = found;
            this.pathIdx = found.size() > 1 ? 1 : 0;
            if (++this.replans > 4) {
                this.releaseKeys(mc);
                this.giveUp();
                return;
            }
        }
        if (this.pathIdx >= this.path.size()) {
            this.path = null;
            this.releaseKeys(mc);
        } else {
            BlockPos wp = this.path.get(this.pathIdx);
            double cx = (double)wp.getX() + 0.5;
            double cz = (double)wp.getZ() + 0.5;
            double d = Math.hypot(cx - p.getX(), cz - p.getZ());
            if (d < 0.5 && Math.abs(p.getY() - (double)wp.getY()) < 1.6) {
                ++this.pathIdx;
                if (this.pathIdx >= this.path.size()) {
                    this.path = null;
                    this.releaseKeys(mc);
                    return;
                }
                wp = this.path.get(this.pathIdx);
                cx = (double)wp.getX() + 0.5;
                cz = (double)wp.getZ() + 0.5;
            } else if (d > 3.5) {
                this.path = null;
                this.releaseKeys(mc);
                return;
            }
            boolean needsClimb = wp.getY() > AutoFarmClient.feetCell(p).getY();
            boolean jump = needsClimb && p.isOnGround() && p.horizontalCollision;
            this.moveToward(mc, cx, cz, jump);
        }
    }

    private void moveToward(MinecraftClient mc, double tx, double tz, boolean jump) {
        ClientPlayerEntity p = mc.player;
        double dx = tx - p.getX();
        double dz = tz - p.getZ();
        float moveYaw = (float)(Math.toDegrees(Math.atan2(dz, dx)) - 90.0);
        float rel = MathHelper.wrapDegrees((float)(moveYaw - p.getYaw()));
        boolean f = Math.abs(rel) < 67.5f;
        boolean b = Math.abs(rel) > 112.5f;
        boolean r = rel > 22.5f && rel < 157.5f;
        boolean l = rel < -22.5f && rel > -157.5f;
        this.setKeys(mc, f, b, l, r, jump);
    }

    private void setKeys(MinecraftClient mc, boolean f, boolean b, boolean l, boolean r, boolean j) {
        mc.options.forwardKey.setPressed(f);
        mc.options.backKey.setPressed(b);
        mc.options.leftKey.setPressed(l);
        mc.options.rightKey.setPressed(r);
        mc.options.jumpKey.setPressed(j);
        this.keysHeld = f || b || l || r || j;
    }

    private void releaseKeys(MinecraftClient mc) {
        if (this.keysHeld) {
            this.setKeys(mc, false, false, false, false, false);
            mc.options.sprintKey.setPressed(false);
        }
    }

    private List<BlockPos> findPath(MinecraftClient mc, Vec3d aim, Kind kind, double radius) {
        ClientWorld w = mc.world;
        BlockPos start = AutoFarmClient.feetCell(mc.player);
        PriorityQueue<Node> open = new PriorityQueue<Node>(Comparator.comparingDouble(nx -> nx.f));
        HashMap<BlockPos, Node> best = new HashMap<BlockPos, Node>();
        HashSet<BlockPos> closed = new HashSet<BlockPos>();
        Node s = new Node(start);
        s.g = 0.0;
        s.f = AutoFarmClient.heuristic(start, aim);
        open.add(s);
        best.put(start, s);
        int expansions = 0;
        while (!open.isEmpty() && expansions < 3000) {
            Node cur = open.poll();
            if (!closed.add(cur.pos)) continue;
            ++expansions;
            if (this.isGoal(cur.pos, aim, kind, radius)) {
                ArrayList<BlockPos> out = new ArrayList<BlockPos>();
                Node n = cur;
                while (n != null) {
                    out.add(n.pos);
                    n = n.parent;
                }
                Collections.reverse(out);
                return out;
            }
            for (int[] d : DIRS) {
                for (int dy = 1; dy >= 0; --dy) {
                    BlockPos n = cur.pos.add(d[0], dy, d[1]);
                    if (closed.contains(n) || Math.abs(n.getX() - start.getX()) > 24 || Math.abs(n.getZ() - start.getZ()) > 24 || Math.abs(n.getY() - start.getY()) > 8 || !this.canMove(w, cur.pos, n, dy)) continue;
                    double cost = 1.0 + (dy > 0 ? 1.5 : 0.0) + (dy < 0 ? 1.0 : 0.0) + this.riskCost(w, n);
                    double g = cur.g + cost;
                    Node ex = (Node)best.get(n);
                    if (ex != null && !(g < ex.g)) continue;
                    Node nn = new Node(n);
                    nn.parent = cur;
                    nn.g = g;
                    nn.f = g + AutoFarmClient.heuristic(n, aim);
                    best.put(n, nn);
                    open.add(nn);
                }
            }
        }
        return null;
    }

    private static double heuristic(BlockPos pos, Vec3d aim) {
        return Math.hypot((double)pos.getX() + 0.5 - aim.x, (double)pos.getZ() + 0.5 - aim.z);
    }

    private boolean isGoal(BlockPos pos, Vec3d aim, Kind kind, double radius) {
        if (kind == Kind.HARVEST) {
            Vec3d eye = new Vec3d((double)pos.getX() + 0.5, (double)pos.getY() + 1.62, (double)pos.getZ() + 0.5);
            return eye.distanceTo(aim) <= 3.0;
        }
        double hd = Math.hypot((double)pos.getX() + 0.5 - aim.x, (double)pos.getZ() + 0.5 - aim.z);
        return kind == Kind.PLANT ? hd <= radius && Math.abs(pos.getY() - this.targetPos.getY()) <= 1 : hd <= radius && Math.abs((double)pos.getY() + 0.5 - aim.y) <= 1.6;
    }

    private boolean canMove(ClientWorld w, BlockPos c, BlockPos n, int dy) {
        return dy == 0 ? this.canStand(w, n) : this.canStand(w, n) && this.passable(w, c.up(2));
    }

    private boolean canStand(ClientWorld w, BlockPos p) {
        if (this.passable(w, p) && this.passable(w, p.up())) {
            BlockPos fp = p.down();
            BlockState floor = w.getBlockState(fp);
            if (floor.getCollisionShape((BlockView)w, fp).isEmpty()) {
                return false;
            }
            return !floor.getFluidState().isEmpty() ? false : !AutoFarmClient.isHazard(floor.getBlock());
        }
        return false;
    }

    private boolean passable(ClientWorld w, BlockPos pos) {
        BlockState s = w.getBlockState(pos);
        if (!s.getCollisionShape((BlockView)w, pos).isEmpty()) {
            return false;
        }
        return !s.getFluidState().isEmpty() ? false : !AutoFarmClient.isHazard(s.getBlock());
    }

    static boolean isHazard(Block b) {
        return b == Blocks.FIRE || b == Blocks.SOUL_FIRE || b == Blocks.CACTUS || b == Blocks.SWEET_BERRY_BUSH || b == Blocks.COBWEB || b == Blocks.POWDER_SNOW || b == Blocks.MAGMA_BLOCK || b == Blocks.CAMPFIRE || b == Blocks.SOUL_CAMPFIRE || b == Blocks.WITHER_ROSE;
    }

    private double riskCost(ClientWorld w, BlockPos n) {
        double cost = 0.0;
        for (int[] d : DIRS) {
            BlockPos q = n.add(d[0], 0, d[1]);
            if (!w.getFluidState(q).isEmpty()) {
                cost += w.getFluidState(q).isIn(FluidTags.LAVA) ? 8.0 : 2.0;
            }
            if (!w.getFluidState(q.down()).isEmpty()) {
                cost += w.getFluidState(q.down()).isIn(FluidTags.LAVA) ? 8.0 : 2.0;
            }
            if (!w.getBlockState(q.down()).getCollisionShape((BlockView)w, q.down()).isEmpty() || !w.getBlockState(q.down(2)).getCollisionShape((BlockView)w, q.down(2)).isEmpty()) continue;
            cost += 1.0;
        }
        return cost;
    }

    private static boolean isMature(BlockState state) {
        Block b = state.getBlock();
        if (b instanceof CropBlock) {
            CropBlock crop = (CropBlock)b;
            return crop.isMature(state);
        }
        return b instanceof NetherWartBlock ? state.get(NetherWartBlock.AGE) >= 3 : false;
    }

    private static boolean isSeed(ItemStack stack) {
        if (!stack.isEmpty() && stack.getItem() instanceof BlockItem bi) {
            Block b = bi.getBlock();
            return b instanceof CropBlock || b instanceof NetherWartBlock;
        }
        return false;
    }

    private boolean isSelectedSeed(ItemStack stack) {
        return AutoFarmClient.isSeed(stack) && this.cfg.isSelected(((BlockItem)stack.getItem()).getBlock());
    }

    private static int fortuneLevel(ItemStack stack) {
        ItemEnchantmentsComponent ench = stack.getEnchantments();
        for (RegistryEntry<Enchantment> e : ench.getEnchantments()) {
            if (!e.matchesKey(Enchantments.FORTUNE)) continue;
            return ench.getLevel(e);
        }
        return 0;
    }

    // ---- PlantBot (Mod 2: Ek) yardımcıları ----

    /** Menüde seçili bir ekinin tohumu mu? (nether siğili dahil) */
    boolean isPlantableSeed(ItemStack stack) {
        return this.isSelectedSeed(stack);
    }

    /** Bot kendiliğinden kapanırsa (tarla bitti, suda takıldı vb.). */
    void autoOff(MinecraftClient mc, String msg) {
        this.enabled = false;
        this.resetState(mc);
        this.say(mc, msg);
    }

    void markKeys() {
        this.keysHeld = true;
    }

    // ================= PatatesBot hasat sistemi (Mod 1: Kır) =================

    private void pbReset() {
        this.pbTarget = null;
        this.pbTicks = 0;
        this.pbSkip.clear();
        this.pbLevelPending = true;
        this.pbAvoid.reset();
    }

    private void pbStopMove(MinecraftClient mc) {
        this.setKeys(mc, false, false, false, false, false);
        mc.options.sprintKey.setPressed(false);
    }

    private void pbHarvest(MinecraftClient mc) {
        if (this.pbLevelPending) {
            this.pbLevelY = this.pbGroundPos(mc).getY() + 1;
            this.pbLevelPending = false;
        }
        if (!this.cfg.requireFortune) {
            this.pbSelectHoe(mc);
        }
        long now = mc.world.getTime();
        this.pbSkip.values().removeIf(t -> t < now);
        if (mc.player.isOnGround()) {
            this.pbLevelY = this.pbGroundPos(mc).getY() + 1;
        }
        Vec3d eye = mc.player.getEyePos();
        BlockPos o = mc.player.getBlockPos();
        int broken = 0;
        for (int dx = -5; dx <= 5 && broken < 4; ++dx) {
            for (int dz = -5; dz <= 5 && broken < 4; ++dz) {
                BlockPos p = new BlockPos(o.getX() + dx, this.pbLevelY, o.getZ() + dz);
                if (!this.pbIsCrop(mc, p) || eye.distanceTo(Vec3d.ofCenter((Vec3i)p)) > 4.0) continue;
                mc.interactionManager.attackBlock(p, Direction.UP);
                ++broken;
            }
        }
        if (broken > 0) {
            mc.player.swingHand(Hand.MAIN_HAND);
        }
        if (this.pbTarget == null || !this.pbIsCrop(mc, this.pbTarget)) {
            this.pbTarget = this.pbFindCrop(mc);
            this.pbTicks = 0;
            this.pbAvoid.newTarget();
        }
        if (this.pbTarget == null) {
            this.pbStopMove(mc);
            if (this.pbAvoid.drive(mc, null) == Avoid.Move.DROWNING) {
                this.autoOff(mc, "Suda takıldı, bot kapandı");
                return;
            }
            if (--this.pbWait <= 0) {
                this.say(mc, "Kırılacak ekin yok, bekliyorum...");
                this.pbWait = 60;
            }
            return;
        }
        Vec3d c = Vec3d.ofCenter((Vec3i)this.pbTarget);
        double dx = c.x - eye.x;
        double dy = c.y - eye.y;
        double dz = c.z - eye.z;
        double hor = Math.sqrt(dx * dx + dz * dz);
        mc.player.setPitch((float)MathHelper.clamp(-Math.toDegrees(Math.atan2(dy, hor)), -10.0, 45.0));
        Avoid.Move mv = this.pbAvoid.drive(mc, c);
        if (mv == Avoid.Move.DROWNING) {
            this.autoOff(mc, "Suda takıldı, bot kapandı");
            return;
        }
        if (mv == Avoid.Move.BLOCKED) {
            this.pbSkip.put(this.pbTarget.toImmutable(), now + 600L);
            this.pbTarget = null;
        }
        this.keysHeld = true;
        if (++this.pbTicks > 200) {
            this.pbSkip.put(this.pbTarget.toImmutable(), now + 600L);
            this.pbTarget = null;
        }
    }

    private boolean pbIsCrop(MinecraftClient mc, BlockPos p) {
        BlockState st = mc.world.getBlockState(p);
        return AutoFarmClient.isMature(st) && this.cfg.isSelected(st.getBlock());
    }

    private BlockPos pbFindCrop(MinecraftClient mc) {
        BlockPos o = mc.player.getBlockPos();
        BlockPos best = null;
        double bestD = Double.MAX_VALUE;
        for (int dx = -12; dx <= 12; ++dx) {
            for (int dz = -12; dz <= 12; ++dz) {
                BlockPos p = new BlockPos(o.getX() + dx, this.pbLevelY, o.getZ() + dz);
                if (this.pbSkip.containsKey(p) || !this.pbIsCrop(mc, p)) continue;
                double d = p.getSquaredDistance((Position)mc.player.getPos());
                if (!(d < bestD)) continue;
                bestD = d;
                best = p;
            }
        }
        return best;
    }

    private void pbSelectHoe(MinecraftClient mc) {
        PlayerInventory in = mc.player.getInventory();
        if (in.getStack(in.selectedSlot).isIn(ItemTags.HOES)) {
            return;
        }
        for (int i = 0; i < 9; ++i) {
            if (!in.getStack(i).isIn(ItemTags.HOES)) continue;
            in.setSelectedSlot(i);
            return;
        }
    }

    private BlockPos pbGroundPos(MinecraftClient mc) {
        BlockPos p = mc.player.getBlockPos();
        return mc.world.getBlockState(p).getCollisionShape(mc.world, p).isEmpty() ? p.down() : p;
    }

    private static enum Kind {
        HARVEST,
        PLANT,
        ITEM;

    }

    private static class Node {
        final BlockPos pos;
        Node parent;
        double g;
        double f;

        Node(BlockPos pos) {
            this.pos = pos;
        }
    }
}

