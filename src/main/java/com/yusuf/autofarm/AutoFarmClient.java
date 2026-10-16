package com.yusuf.autofarm;

import com.mojang.brigadier.arguments.IntegerArgumentType;
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

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.PriorityQueue;
import java.util.Set;

/**
 * Legit yardımcı otomasyon: güvenli yol bulup hedefe yürür, bloğa bakar (kamera görünür şekilde döner),
 * gerçek bakış ışınıyla (raycast) vurduğu bloğu kırar / eker.
 *
 * Tuşlar: G = aç/kapat, R + G = ayar menüsü.
 */
public class AutoFarmClient implements ClientModInitializer {
    private static final int SEARCH_RADIUS = 12;     // hedef arama yarıçapı
    private static final double WALK_STOP = 3.0;     // kırma için hedefe bu kadar yaklaşınca dur
    private static final double PLANT_STRICT = 0.3;  // ekmek için hedef hücrenin üstüne kadar yaklaş
    private static final double PLANT_RELAXED = 0.9; // oraya gidilemezse biraz daha uzaktan dene
    private static final int AIM_TICKS = 1;          // işlemden önce hedefe sabit bakma süresi
    private static final int TARGET_TIMEOUT = 400;   // hedefe ulaşamazsa vazgeç (tick)
    private static final int MAX_EXPANSIONS = 3000;  // yol bulma sınırı
    private static final int PATH_RANGE = 24;

    private enum Kind { HARVEST, PLANT, ITEM }

    private static final String[] MODE_NAMES = {
            "1: Kır (olgun ekinleri kırar)",
            "2: Ek (boş tarlaları eker)",
            "3: Kır + Ek (kırar ve geri eker)"
    };

    private static final int[][] DIRS = {{1, 0}, {-1, 0}, {0, 1}, {0, -1}};

    private final AutoFarmConfig cfg = AutoFarmConfig.get();

    private boolean enabled = false;
    private boolean openGui = false;
    private boolean prevG = false;
    private boolean announce = true;

    private BlockPos targetPos;
    private Kind targetKind;
    private ItemEntity targetItem;
    private int targetTicks, aimedTicks, actionCooldown, tickCounter, warnCooldown;
    private boolean keysHeld;

    // yol takibi
    private List<BlockPos> path;
    private int pathIdx;
    private int replans;
    private double approach = PLANT_STRICT;

    private final Map<BlockPos, Integer> blacklist = new HashMap<>();
    private final Map<BlockPos, Integer> replant = new HashMap<>();
    private final Set<Integer> ignoredItems = new HashSet<>();

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
        return 20f + 7f * cfg.speed;          // 27 .. 90 derece/tick
    }

    private int actionDelay() {
        return cfg.speed >= 10 ? 0 : Math.max(1, 6 - cfg.speed / 2);   // hız 10'da bekleme yok
    }

    // ------------------------------------------------------------------ ana döngü

    private void onTick(MinecraftClient mc) {
        tickCounter++;

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

        step(mc, plantHand, plantBlock);
    }

    private void step(MinecraftClient mc, Hand plantHand, Block plantBlock) {
        ClientPlayerEntity p = mc.player;

        // Yerdeki eşyaları topla
        if (targetKind == Kind.ITEM) {
            Vec3d ip = targetItem.getPos();
            lookAt(p, ip.add(0, 0.1, 0));
            double hd = Math.hypot(ip.x - p.getX(), ip.z - p.getZ());
            if (hd <= 0.6) {
                releaseKeys(mc);
            } else {
                navigate(mc, ip, Kind.ITEM, 0.6);
            }
            return;
        }

        Vec3d aim = aimPoint(targetPos, targetKind);
        lookAt(p, aim);

        double eyeDist = p.getEyePos().distanceTo(aim);
        double hd = Math.hypot(p.getX() - aim.x, p.getZ() - aim.z);
        boolean inReach = eyeDist <= WALK_STOP;
        boolean standOk = (targetKind == Kind.HARVEST) ? inReach : (hd <= approach);

        // Gerçek bakış ışını hedefi gösteriyor mu?
        BlockHitResult bhr = null;
        boolean ok = false;
        if (inReach) {
            HitResult hr = p.raycast(p.getBlockInteractionRange(), 1.0f, false);
            if (hr instanceof BlockHitResult b && b.getType() == HitResult.Type.BLOCK) {
                bhr = b;
                if (targetKind == Kind.HARVEST) {
                    ok = b.getBlockPos().equals(targetPos);
                } else {
                    ok = b.getBlockPos().equals(targetPos.down()) && b.getSide() == Direction.UP;
                }
                // Araya başka bir ekin girdiyse, ışının gösterdiği geçerli hedefi kullan
                if (!ok) {
                    BlockPos hp = b.getBlockPos();
                    BlockPos feet = feetCell(p);
                    boolean sameLevel = hp.getY() == feet.getY();
                    BlockState hs = mc.world.getBlockState(hp);
                    boolean harvestMode = cfg.mode == 0 || cfg.mode == 2;
                    if (sameLevel && harvestMode && isMature(hs) && cfg.isSelected(hs.getBlock())
                            && !blacklist.containsKey(hp)) {
                        targetPos = hp.toImmutable();
                        targetKind = Kind.HARVEST;
                        ok = true;
                    } else if (plantBlock != null && b.getSide() == Direction.UP) {
                        BlockPos above = hp.up();
                        if (above.getY() == feet.getY()
                                && mc.world.getBlockState(above).isAir()
                                && (cfg.mode == 1 || replant.containsKey(above))
                                && plantBlock.getDefaultState().canPlaceAt(mc.world, above)) {
                            targetPos = above.toImmutable();
                            targetKind = Kind.PLANT;
                            ok = true;
                        }
                    }
                }
            }
        }

        if (!ok && !standOk) {
            aimedTicks = 0;
            if (targetKind == Kind.HARVEST) {
                navigate(mc, aim, Kind.HARVEST, WALK_STOP);
            } else {
                navigate(mc, aim, Kind.PLANT, approach);
            }
            return;
        }

        releaseKeys(mc);
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
                origin.add(-SEARCH_RADIUS, 0, -SEARCH_RADIUS),
                origin.add(SEARCH_RADIUS, 0, SEARCH_RADIUS))) {
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
        path = null;
        pathIdx = 0;
        replans = 0;
        approach = PLANT_STRICT;
    }

    private void giveUp() {
        if (targetKind == Kind.ITEM && targetItem != null) {
            ignoredItems.add(targetItem.getId());
        } else if (targetPos != null) {
            blacklist.put(targetPos.toImmutable(), tickCounter + 600);
        }
        clearTarget();
    }

    // ------------------------------------------------------------------ bakış

    private static Vec3d aimPoint(BlockPos pos, Kind kind) {
        if (kind == Kind.HARVEST) {
            return new Vec3d(pos.getX() + 0.5, pos.getY() + 0.35, pos.getZ() + 0.5);
        }
        // ekim: toprağın üst yüzeyi
        return new Vec3d(pos.getX() + 0.5, pos.getY() - 1 + 0.9, pos.getZ() + 0.5);
    }

    /** Kamerayı hedefe doğru kademeli çevirir. */
    private void lookAt(ClientPlayerEntity p, Vec3d target) {
        float step = lookStep();
        Vec3d eye = p.getEyePos();
        double dx = target.x - eye.x;
        double dy = target.y - eye.y;
        double dz = target.z - eye.z;
        double h = Math.sqrt(dx * dx + dz * dz);

        float wantYaw = (h < 0.5)
                ? p.getYaw()   // tam üstündeyken yaw titremesin
                : (float) (Math.toDegrees(Math.atan2(dz, dx)) - 90.0);
        float wantPitch = (float) (-Math.toDegrees(Math.atan2(dy, h)));

        float yawDiff = MathHelper.wrapDegrees(wantYaw - p.getYaw());
        float pitchDiff = wantPitch - p.getPitch();

        p.setYaw(p.getYaw() + MathHelper.clamp(yawDiff, -step, step));
        p.setPitch(MathHelper.clamp(p.getPitch() + MathHelper.clamp(pitchDiff, -step, step), -90f, 90f));
    }

    // ------------------------------------------------------------------ yürüme (yol bulma ile)

    private static BlockPos feetCell(ClientPlayerEntity p) {
        // çiftlik toprağı gibi yarım bloklarda ayaklar bir üst hücrede sayılsın
        return BlockPos.ofFloored(p.getX(), p.getY() + 0.2, p.getZ());
    }

    private void navigate(MinecraftClient mc, Vec3d goalAim, Kind kind, double radius) {
        ClientPlayerEntity p = mc.player;

        if (path == null) {
            List<BlockPos> found = findPath(mc, goalAim, kind, radius);
            if (found == null && kind == Kind.PLANT && radius < PLANT_RELAXED) {
                approach = PLANT_RELAXED;
                found = findPath(mc, goalAim, kind, PLANT_RELAXED);
            }
            if (found == null) {
                releaseKeys(mc);
                giveUp();      // güvenli yol yok: bu hedefi bırak
                return;
            }
            path = found;
            pathIdx = found.size() > 1 ? 1 : 0;
            if (++replans > 4) {
                releaseKeys(mc);
                giveUp();
                return;
            }
        }

        if (pathIdx >= path.size()) {
            path = null;   // varış noktasına geldik ama hâlâ uzak: yeniden planla
            releaseKeys(mc);
            return;
        }

        BlockPos wp = path.get(pathIdx);
        double cx = wp.getX() + 0.5;
        double cz = wp.getZ() + 0.5;
        double d = Math.hypot(cx - p.getX(), cz - p.getZ());

        if (d < 0.4 && Math.abs(p.getY() - wp.getY()) < 1.3) {
            pathIdx++;
            if (pathIdx >= path.size()) {
                path = null;
                releaseKeys(mc);
                return;
            }
            wp = path.get(pathIdx);
            cx = wp.getX() + 0.5;
            cz = wp.getZ() + 0.5;
        } else if (d > 3.5) {
            path = null;   // yoldan çıktık
            releaseKeys(mc);
            return;
        }

        boolean jump = p.isOnGround() && wp.getY() > feetCell(p).getY();
        moveToward(mc, cx, cz, jump);
    }

    /** Kamera hedefe bakarken, yürüme tuşlarını hareket yönüne göre seçer (ileri/geri/yan). */
    private void moveToward(MinecraftClient mc, double tx, double tz, boolean jump) {
        ClientPlayerEntity p = mc.player;
        double dx = tx - p.getX();
        double dz = tz - p.getZ();
        float moveYaw = (float) (Math.toDegrees(Math.atan2(dz, dx)) - 90.0);
        float rel = MathHelper.wrapDegrees(moveYaw - p.getYaw());

        boolean f = Math.abs(rel) < 67.5f;
        boolean b = Math.abs(rel) > 112.5f;
        boolean r = rel > 22.5f && rel < 157.5f;
        boolean l = rel < -22.5f && rel > -157.5f;
        setKeys(mc, f, b, l, r, jump);
    }

    private void setKeys(MinecraftClient mc, boolean f, boolean b, boolean l, boolean r, boolean j) {
        mc.options.forwardKey.setPressed(f);
        mc.options.backKey.setPressed(b);
        mc.options.leftKey.setPressed(l);
        mc.options.rightKey.setPressed(r);
        mc.options.jumpKey.setPressed(j);
        keysHeld = f || b || l || r || j;
    }

    private void releaseKeys(MinecraftClient mc) {
        if (keysHeld) {
            setKeys(mc, false, false, false, false, false);
        }
    }

    // ------------------------------------------------------------------ A* yol bulma

    private static class Node {
        final BlockPos pos;
        Node parent;
        double g, f;

        Node(BlockPos pos) {
            this.pos = pos;
        }
    }

    private List<BlockPos> findPath(MinecraftClient mc, Vec3d aim, Kind kind, double radius) {
        ClientWorld w = mc.world;
        BlockPos start = feetCell(mc.player);

        PriorityQueue<Node> open = new PriorityQueue<>(Comparator.comparingDouble((Node n) -> n.f));
        Map<BlockPos, Node> best = new HashMap<>();
        Set<BlockPos> closed = new HashSet<>();

        Node s = new Node(start);
        s.g = 0;
        s.f = heuristic(start, aim);
        open.add(s);
        best.put(start, s);

        int expansions = 0;
        while (!open.isEmpty() && expansions < MAX_EXPANSIONS) {
            Node cur = open.poll();
            if (!closed.add(cur.pos)) continue;
            expansions++;

            if (isGoal(cur.pos, aim, kind, radius)) {
                List<BlockPos> out = new ArrayList<>();
                for (Node n = cur; n != null; n = n.parent) out.add(n.pos);
                Collections.reverse(out);
                return out;
            }

            for (int[] d : DIRS) {
                for (int dy = 1; dy >= 0; dy--) {
                    BlockPos n = cur.pos.add(d[0], dy, d[1]);
                    if (closed.contains(n)) continue;
                    if (Math.abs(n.getX() - start.getX()) > PATH_RANGE
                            || Math.abs(n.getZ() - start.getZ()) > PATH_RANGE
                            || Math.abs(n.getY() - start.getY()) > 8) continue;
                    if (!canMove(w, cur.pos, n, dy)) continue;

                    double cost = 1.0 + (dy > 0 ? 1.5 : 0.0) + (dy < 0 ? 1.0 : 0.0) + riskCost(w, n);
                    double g = cur.g + cost;
                    Node ex = best.get(n);
                    if (ex == null || g < ex.g) {
                        Node nn = new Node(n);
                        nn.parent = cur;
                        nn.g = g;
                        nn.f = g + heuristic(n, aim);
                        best.put(n, nn);
                        open.add(nn);
                    }
                }
            }
        }
        return null;
    }

    private static double heuristic(BlockPos pos, Vec3d aim) {
        return Math.hypot(pos.getX() + 0.5 - aim.x, pos.getZ() + 0.5 - aim.z);
    }

    private boolean isGoal(BlockPos pos, Vec3d aim, Kind kind, double radius) {
        if (kind == Kind.HARVEST) {
            Vec3d eye = new Vec3d(pos.getX() + 0.5, pos.getY() + 1.62, pos.getZ() + 0.5);
            return eye.distanceTo(aim) <= WALK_STOP;
        }
        double hd = Math.hypot(pos.getX() + 0.5 - aim.x, pos.getZ() + 0.5 - aim.z);
        if (kind == Kind.PLANT) {
            return hd <= radius && pos.getY() == targetPos.getY();
        }
        // ITEM
        return hd <= radius && Math.abs(pos.getY() + 0.5 - aim.y) <= 1.6;
    }

    private boolean canMove(ClientWorld w, BlockPos c, BlockPos n, int dy) {
        // Aşağı adım yok: farm katlarından düşüp altındaki tarlayı ezmeyelim.
        if (dy == 0) {
            return canStand(w, n);
        }
        // zıplayarak bir blok yukarı: üst hücrede boşluk gerekir
        return canStand(w, n) && passable(w, c.up(2));
    }

    /** Ayaklar ve kafa hücresi boş, altı sağlam ve güvenli mi? */
    private boolean canStand(ClientWorld w, BlockPos p) {
        if (!passable(w, p) || !passable(w, p.up())) return false;
        BlockPos fp = p.down();
        BlockState floor = w.getBlockState(fp);
        if (floor.getCollisionShape(w, fp).isEmpty()) return false;
        if (!floor.getFluidState().isEmpty()) return false;
        return !isHazard(floor.getBlock());
    }

    /** Su/lav yok, çarpışma yok (zincir, fener, çit...), tehlikeli blok yok. */
    private boolean passable(ClientWorld w, BlockPos pos) {
        BlockState s = w.getBlockState(pos);
        if (!s.getCollisionShape(w, pos).isEmpty()) return false;
        if (!s.getFluidState().isEmpty()) return false;
        return !isHazard(s.getBlock());
    }

    private static boolean isHazard(Block b) {
        return b == Blocks.FIRE || b == Blocks.SOUL_FIRE || b == Blocks.CACTUS
                || b == Blocks.SWEET_BERRY_BUSH || b == Blocks.COBWEB || b == Blocks.POWDER_SNOW
                || b == Blocks.MAGMA_BLOCK || b == Blocks.CAMPFIRE || b == Blocks.SOUL_CAMPFIRE
                || b == Blocks.WITHER_ROSE;
    }

    /** Su, lav ve uçurum kenarına yakın hücrelere ceza: en güvenli yol seçilsin. */
    private double riskCost(ClientWorld w, BlockPos n) {
        double cost = 0;
        for (int[] d : DIRS) {
            BlockPos q = n.add(d[0], 0, d[1]);
            if (!w.getFluidState(q).isEmpty()) {
                cost += w.getFluidState(q).isIn(FluidTags.LAVA) ? 8 : 2;
            }
            if (!w.getFluidState(q.down()).isEmpty()) {
                cost += w.getFluidState(q.down()).isIn(FluidTags.LAVA) ? 8 : 2;
            }
            if (w.getBlockState(q.down()).getCollisionShape(w, q.down()).isEmpty()
                    && w.getBlockState(q.down(2)).getCollisionShape(w, q.down(2)).isEmpty()) {
                cost += 1.5;   // uçurum kenarı
            }
        }
        return cost;
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
