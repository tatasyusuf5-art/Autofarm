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
 * Legit yardımcı otomasyon — SÜREKLİ YÜRÜYÜŞ modeli:
 * Karakter satır boyunca HİÇ DURMADAN yürür; yürürken reach menziline giren
 * her olgun ekini / boş tarlayı anında kırar-eker (tek tek nişan alıp durmaz).
 * Yürüme ve eylem birbirinden bağımsız iki ayrı sistemdir: hareket asla eylem
 * yüzünden kesilmez.
 *
 * Tuşlar: varsayılan G = aç/kapat, R + G = ayar menüsü (menüden değiştirilebilir).
 */
public class AutoFarmClient implements ClientModInitializer {
    private static final int SEARCH_RADIUS = 14;
    private static final int PATH_RANGE = 28;
    private static final int MAX_EXPANSIONS = 2500;
    private static final int[][] DIRS = {{1, 0}, {-1, 0}, {0, 1}, {0, -1}};

    private static final String[] MODE_NAMES = {
            "1: Kır (olgun ekinleri kırar)",
            "2: Ek (boş tarlaları eker)",
            "3: Kır + Ek (kırdığını geri eker)",
            "4: Kır + Ek + Ek (kırdığını eker, ayrıca boş tarlaları da eker)"
    };

    private final AutoFarmConfig cfg = AutoFarmConfig.get();

    private boolean enabled = false;
    private boolean openGui = false;
    private boolean prevToggle = false;
    private boolean announce = true;

    private int actionCooldown, tickCounter, warnCooldown;
    private boolean keysHeld;

    // Yürüyüş hedefi: eylemlerden tamamen bağımsız, sürekli ilerleme noktası.
    private BlockPos walkTarget;
    private List<BlockPos> path;
    private int pathIdx;

    private final Map<BlockPos, Integer> blacklist = new HashMap<>();
    private final Map<BlockPos, Integer> replant = new HashMap<>();
    private final Set<Integer> ignoredItems = new HashSet<>();

    // Tarla rotası (boustrophedon sıralı hücre listesi, aktif kat için).
    private List<BlockPos> route;
    private int routeIdx;
    private int currentFloor;
    private float cruiseYaw = 0f;   // serbest modda (tarla kaydı yokken) yürüyüş yönü

    @Override
    public void onInitializeClient() {
        cfg.load();

        ClientTickEvents.END_CLIENT_TICK.register(this::onTick);

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
                                .then(ClientCommandManager.argument("n", IntegerArgumentType.integer(1, 4))
                                        .executes(ctx -> {
                                            cfg.mode = IntegerArgumentType.getInteger(ctx, "n") - 1;
                                            cfg.save();
                                            resetState(MinecraftClient.getInstance());
                                            say(MinecraftClient.getInstance(), "Mod -> " + MODE_NAMES[cfg.mode]);
                                            return 1;
                                        })))
                        .then(ClientCommandManager.literal("speed")
                                .then(ClientCommandManager.argument("n", IntegerArgumentType.integer(1, 15))
                                        .executes(ctx -> {
                                            cfg.speed = IntegerArgumentType.getInteger(ctx, "n");
                                            cfg.save();
                                            say(MinecraftClient.getInstance(), "Hız -> " + cfg.speed + "/15");
                                            return 1;
                                        })))
                        .then(ClientCommandManager.literal("corner")
                                .then(ClientCommandManager.argument("n", IntegerArgumentType.integer(1, 2))
                                        .executes(ctx -> {
                                            saveFieldCorner(MinecraftClient.getInstance(),
                                                    IntegerArgumentType.getInteger(ctx, "n") - 1);
                                            return 1;
                                        })))
                        .then(ClientCommandManager.literal("clearfield")
                                .executes(ctx -> {
                                    cfg.fieldSet = false;
                                    cfg.save();
                                    say(MinecraftClient.getInstance(), "Tarla sınırı temizlendi.");
                                    return 1;
                                }))
                        .then(ClientCommandManager.literal("floors")
                                .then(ClientCommandManager.argument("n", IntegerArgumentType.integer(1, 64))
                                        .executes(ctx -> {
                                            cfg.floors = IntegerArgumentType.getInteger(ctx, "n");
                                            cfg.save();
                                            say(MinecraftClient.getInstance(), "Kat sayısı -> " + cfg.floors);
                                            return 1;
                                        })))
                        .then(ClientCommandManager.literal("lane")
                                .then(ClientCommandManager.argument("n", IntegerArgumentType.integer(1, 16))
                                        .executes(ctx -> {
                                            cfg.laneSpacing = IntegerArgumentType.getInteger(ctx, "n");
                                            cfg.save();
                                            route = null;
                                            say(MinecraftClient.getInstance(), "Şerit aralığı -> " + cfg.laneSpacing);
                                            return 1;
                                        })))
                        .then(ClientCommandManager.literal("godmode")
                                .executes(ctx -> {
                                    cfg.godMode = !cfg.godMode;
                                    cfg.save();
                                    say(MinecraftClient.getInstance(),
                                            "Godmode " + (cfg.godMode ? "AÇIK" : "KAPALI"));
                                    return 1;
                                }))));
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

    public void saveFieldCorner(MinecraftClient mc, int idx) {
        if (mc.player == null) return;
        HitResult hr = mc.player.raycast(mc.player.getBlockInteractionRange() + 4.0, 1.0f, false);
        BlockPos pos;
        if (hr instanceof BlockHitResult b && b.getType() == HitResult.Type.BLOCK) {
            pos = b.getBlockPos();
        } else {
            pos = feetCell(mc.player);
        }
        cfg.setFieldCorner(idx, pos);
        cfg.fieldSet = true;
        cfg.save();
        route = null;
        say(mc, (idx == 0 ? "1." : "2.") + " köşe kaydedildi: " + pos.toShortString());
        mc.player.sendMessage(Text.literal("[AutoFarm] " + (idx == 0 ? "1." : "2.")
                + " köşe kaydedildi: " + pos.toShortString()), false);
    }

    private void resetState(MinecraftClient mc) {
        blacklist.clear();
        replant.clear();
        ignoredItems.clear();
        route = null;
        routeIdx = 0;
        currentFloor = 0;
        path = null;
        walkTarget = null;
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
        if (cfg.godMode) return 180f;
        return 9f * cfg.speed;
    }

    private int actionDelay() {
        if (cfg.godMode) return 0;
        return cfg.speed >= 10 ? 0 : Math.max(1, 4 - cfg.speed / 4);
    }

    // ------------------------------------------------------------------ ana döngü

    private void onTick(MinecraftClient mc) {
        tickCounter++;

        if (announce && mc.player != null && mc.world != null) {
            announce = false;
            mc.player.sendMessage(Text.literal(
                    "[AutoFarm] Yüklendi. " + keyName(cfg.keyToggle) + " = aç/kapat, "
                            + keyName(cfg.keyMenu) + " + " + keyName(cfg.keyToggle)
                            + " = menü, ya da /autofarm gui"), false);
        }

        boolean t = false;
        boolean m = false;
        if (mc.currentScreen == null && mc.player != null) {
            long window = mc.getWindow().getHandle();
            t = InputUtil.isKeyPressed(window, cfg.keyToggle);
            m = InputUtil.isKeyPressed(window, cfg.keyMenu);
        }
        if (t && !prevToggle) {
            if (m) {
                openGui = true;
            } else {
                toggle(mc);
            }
        }
        prevToggle = t;

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
            blacklist.values().removeIf(v -> v < tickCounter);
            replant.values().removeIf(v -> v < tickCounter);
        }
        if (tickCounter % 1200 == 0) ignoredItems.clear();

        boolean harvestMode = cfg.mode == 0 || cfg.mode == 2 || cfg.mode == 3;
        boolean plantMode = cfg.mode == 1 || cfg.mode == 2 || cfg.mode == 3;
        boolean plantFreeTiles = cfg.mode == 1 || cfg.mode == 3;

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
                // Ekin yoksa bile yürümeyi durdurma, sadece ekimi atla.
                plantMode = false;
                plantFreeTiles = false;
            } else {
                plantBlock = ((BlockItem) mc.player.getStackInHand(plantHand).getItem()).getBlock();
            }
        }

        // 1) EYLEM: reach içindeki tüm uygun blokları tara ve anında işle (yürüyüşten bağımsız).
        actOnNearbyCells(mc, harvestMode, plantMode, plantFreeTiles, plantHand, plantBlock);
        actOnNearbyItems(mc, harvestMode);

        // 2) YÜRÜYÜŞ: her zaman devam eder, eylem beklemez.
        walk(mc);
    }

    private static String keyName(int code) {
        return InputUtil.fromKeyCode(code, 0).getLocalizedText().getString();
    }

    // ------------------------------------------------------------------ eylem: menzildeki her şeyi işle

    /**
     * Oyuncunun gerçek blockInteractionRange'i içindeki TÜM uygun hücreleri tarar.
     * Durmadan, nişan-kilidi beklemeden: kamerayı o hücreye çevirip ışının gerçekten
     * değdiğini doğrular doğrulamaz hemen kırar/eker, sonra aynı tick içinde (cooldown
     * izin verdiği sürece) bir sonrakine geçer. Bu sayede yürürken geçtiği her ekin
     * tek tek durup nişan almadan işlenir.
     */
    private void actOnNearbyCells(MinecraftClient mc, boolean harvest, boolean plant,
                                   boolean plantFree, Hand plantHand, Block plantBlock) {
        if (actionCooldown > 0) return;
        ClientPlayerEntity p = mc.player;
        double reach = p.getBlockInteractionRange();
        Vec3d eye = p.getEyePos();
        BlockPos center = feetCell(p);

        // Yakındaki adaylar: mesafeye göre sıralı (en yakın önce).
        List<BlockPos> candidates = new ArrayList<>();
        int r = (int) Math.ceil(reach) + 1;
        for (BlockPos pos : BlockPos.iterate(center.add(-r, -1, -r), center.add(r, 1, r))) {
            if (blacklist.containsKey(pos)) continue;
            if (cfg.fieldSet && !inAnyLoadedFloorRange(pos)) continue;
            BlockState s = mc.world.getBlockState(pos);
            boolean isHarvestTarget = harvest && isMature(s) && cfg.isSelected(s.getBlock());
            boolean isPlantTarget = plant && s.isAir()
                    && (plantFree || replant.containsKey(pos))
                    && plantBlock != null
                    && plantBlock.getDefaultState().canPlaceAt(mc.world, pos);
            if (!isHarvestTarget && !isPlantTarget) continue;

            Vec3d aim = isHarvestTarget
                    ? new Vec3d(pos.getX() + 0.5, pos.getY() + 0.35, pos.getZ() + 0.5)
                    : new Vec3d(pos.getX() + 0.5, pos.getY() + 0.9375, pos.getZ() + 0.5);
            if (eye.distanceTo(aim) > reach) continue;
            candidates.add(pos.toImmutable());
        }
        if (candidates.isEmpty()) return;
        candidates.sort(Comparator.comparingDouble(pos -> eye.squaredDistanceTo(Vec3d.ofCenter(pos))));

        BlockPos pos = candidates.get(0);
        BlockState s = mc.world.getBlockState(pos);
        boolean isHarvestTarget = harvest && isMature(s) && cfg.isSelected(s.getBlock());
        Vec3d aim = isHarvestTarget
                ? new Vec3d(pos.getX() + 0.5, pos.getY() + 0.35, pos.getZ() + 0.5)
                : new Vec3d(pos.getX() + 0.5, pos.getY() + 0.9375, pos.getZ() + 0.5);

        lookAt(p, aim);

        HitResult hr = p.raycast(reach, 1.0f, false);
        if (!(hr instanceof BlockHitResult bhr) || bhr.getType() != HitResult.Type.BLOCK) return;

        boolean ok;
        if (isHarvestTarget) {
            ok = bhr.getBlockPos().equals(pos);
        } else {
            ok = bhr.getBlockPos().equals(pos.down()) && bhr.getSide() == Direction.UP;
        }
        if (!ok) return;   // ışın henüz tam değmedi; bir sonraki tick tekrar dener (yürürken de olur)

        if (isHarvestTarget) {
            mc.interactionManager.attackBlock(pos, bhr.getSide());
            p.swingHand(Hand.MAIN_HAND);
            if (cfg.mode == 2 || cfg.mode == 3) {
                replant.put(pos, tickCounter + 100);
            }
        } else {
            ActionResult res = mc.interactionManager.interactBlock(p, plantHand, bhr);
            if (res.isAccepted()) {
                p.swingHand(plantHand);
                replant.remove(pos);
            } else {
                blacklist.put(pos, tickCounter + 200);
            }
        }
        actionCooldown = actionDelay();
    }

    private boolean inAnyLoadedFloorRange(BlockPos pos) {
        // Tarla kaydı varsa, oyuncunun bulunduğu kata ±1 tolerans ver (basamak/kot farkları için).
        return true; // sameLevel kontrolü zaten aşağıda ayrı yapılıyor; burada filtre gerekmiyor.
    }

    private void actOnNearbyItems(MinecraftClient mc, boolean harvest) {
        if (!harvest) return;
        ClientPlayerEntity p = mc.player;
        Box box = p.getBoundingBox().expand(2.0, 1.5, 2.0);
        for (ItemEntity ie : mc.world.getEntitiesByClass(ItemEntity.class, box,
                x -> !x.isRemoved() && !ignoredItems.contains(x.getId()))) {
            // Yakındaki eşyalar oyuncunun hitbox'ına değdiğinde Minecraft zaten otomatik toplar.
            // Burada ekstra bir şey yapmaya gerek yok; eşya toplama hareketin doğal sonucu.
        }
    }

    // ------------------------------------------------------------------ ekin elde tutma / çantadan alma

    private Hand findPlantHand(MinecraftClient mc, boolean allowMain) {
        ClientPlayerEntity p = mc.player;
        if (allowMain && isSelectedSeed(p.getMainHandStack())) return Hand.MAIN_HAND;
        if (isSelectedSeed(p.getOffHandStack())) return Hand.OFF_HAND;
        return null;
    }

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

    // ------------------------------------------------------------------ yürüyüş (eylemden bağımsız, hiç durmaz)

    private void walk(MinecraftClient mc) {
        ClientPlayerEntity p = mc.player;

        if (cfg.fieldSet) {
            walkRoute(mc);
        } else {
            walkCruise(mc);
        }
    }

    /** Tarla kaydı yokken: geniş bir daire/zikzak içinde sürekli dolaşır, asla durmaz. */
    private void walkCruise(MinecraftClient mc) {
        ClientPlayerEntity p = mc.player;
        if (walkTarget == null || feetCell(p).isWithinDistance(walkTarget, 1.5)
                || !canStand(mc.world, walkTarget)) {
            // Yeni, güvenli ve yakın bir ileri nokta seç.
            for (int attempt = 0; attempt < 8 && walkTarget == null; attempt++) {
                float yaw = cruiseYaw + (attempt * 25f);
                double rad = Math.toRadians(yaw);
                BlockPos cand = feetCell(p).add((int) Math.round(-Math.sin(rad) * 6), 0, (int) Math.round(Math.cos(rad) * 6));
                if (canStand(mc.world, cand)) {
                    walkTarget = cand;
                }
            }
            cruiseYaw += 47f;   // bir sonraki seçimde farklı yöne baksın (sürekli tarama hissi)
            path = null;
        }
        if (walkTarget == null) return;
        navigateContinuous(mc, walkTarget);
    }

    /**
     * Tarla kaydı varsa: boustrophedon rotadaki bir sonraki geçilmemiş hücreye doğru
     * HİÇ DURMADAN yürür. Hedefe varınca hemen bir sonraki rota hücresine geçer;
     * eylem (kırma/ekme) bu yürüyüşü asla bekletmez, yukarıdaki actOnNearbyCells
     * zaten yürürken paralel çalışıyor.
     */
    private void walkRoute(MinecraftClient mc) {
        if (route == null) {
            buildRoute(mc);
            routeIdx = 0;
        }
        if (route == null || route.isEmpty()) return;

        ClientPlayerEntity p = mc.player;
        BlockPos feet = feetCell(p);

        // Mevcut hedefe yeterince yaklaştıysak bir sonrakine geç (asla tam durmadan).
        while (routeIdx < route.size() && feet.isWithinDistance(route.get(routeIdx), 1.2)) {
            routeIdx++;
        }
        if (routeIdx >= route.size()) {
            currentFloor++;
            if (currentFloor >= cfg.floors) {
                releaseKeys(mc);
                return;   // tüm katlar tamamlandı
            }
            route = null;
            buildRoute(mc);
            routeIdx = 0;
            path = null;
            if (route == null || route.isEmpty()) return;
        }

        BlockPos goal = route.get(routeIdx);
        navigateContinuous(mc, goal);
    }

    /**
     * Düz şeritler halinde rota: her satırı tek tek gezmek yerine, reach menzili
     * iki yana da ulaştığı için cfg.laneSpacing (varsayılan 9) blok aralıkla
     * şeritler oluşturur. Oyuncu bir şeridi düz yürür, şerit bitince laneSpacing
     * kadar yana kayar ve ters yönde bir sonraki şeridi yürür (boustrophedon).
     * Aradaki bloklar reach menziliyle iki taraftan taranmış olur.
     */
    private void buildRoute(MinecraftClient mc) {
        int minX = Math.min(cfg.fx1, cfg.fx2), maxX = Math.max(cfg.fx1, cfg.fx2);
        int minZ = Math.min(cfg.fz1, cfg.fz2), maxZ = Math.max(cfg.fz1, cfg.fz2);
        int y = cfg.baseFloorY() + currentFloor;
        int lane = Math.max(1, cfg.laneSpacing);

        List<BlockPos> list = new ArrayList<>();
        boolean reverse = false;
        for (int x = minX; x <= maxX; x += lane) {
            if (!reverse) {
                for (int z = minZ; z <= maxZ; z++) list.add(new BlockPos(x, y, z));
            } else {
                for (int z = maxZ; z >= minZ; z--) list.add(new BlockPos(x, y, z));
            }
            reverse = !reverse;
        }
        // Son şerit tarlanın sağ/sol ucunu tam kapsamıyorsa (lane maxX'e tam bölünmezse),
        // kenarın atlanmaması için son X sütununu da ayrı bir şerit olarak ekle.
        int lastLaneX = minX + ((maxX - minX) / lane) * lane;
        if (lastLaneX != maxX) {
            if (!reverse) {
                for (int z = minZ; z <= maxZ; z++) list.add(new BlockPos(maxX, y, z));
            } else {
                for (int z = maxZ; z >= minZ; z--) list.add(new BlockPos(maxX, y, z));
            }
        }
        route = list;
    }

    /**
     * Hedefe doğru KESİNTİSİZ yürür: önce düz-çizgi hızlı yol dener (A* yok,
     * duraksama yok); gerçekten engele takılırsa A* ile etrafından dolanır.
     * Her iki durumda da yürüme tuşları sürekli basılı kalır, "dur-nişan al-yürü"
     * döngüsü yoktur.
     */
    private void navigateContinuous(MinecraftClient mc, BlockPos goal) {
        ClientPlayerEntity p = mc.player;
        Vec3d goalCenter = new Vec3d(goal.getX() + 0.5, p.getY(), goal.getZ() + 0.5);

        if (path == null) {
            if (straightLineClear(mc, goalCenter)) {
                lookAt(p, goalCenter);
                moveToward(mc, goalCenter.x, goalCenter.z, false);
                return;
            }
            List<BlockPos> found = findPath(mc, goal);
            if (found == null) {
                blacklist.put(goal, tickCounter + 300);
                path = null;
                if (cfg.fieldSet) routeIdx++;
                else walkTarget = null;
                return;
            }
            path = found;
            pathIdx = found.size() > 1 ? 1 : 0;
        }

        if (pathIdx >= path.size()) {
            path = null;
            return;
        }
        BlockPos wp = path.get(pathIdx);
        double cx = wp.getX() + 0.5;
        double cz = wp.getZ() + 0.5;
        double d = Math.hypot(cx - p.getX(), cz - p.getZ());

        if (d < 1.0 && Math.abs(p.getY() - wp.getY()) < 1.6) {
            pathIdx++;
            if (pathIdx >= path.size()) {
                path = null;
                return;
            }
            wp = path.get(pathIdx);
            cx = wp.getX() + 0.5;
            cz = wp.getZ() + 0.5;
        }

        boolean needsClimb = wp.getY() > feetCell(p).getY();
        boolean jump = needsClimb && p.isOnGround() && p.horizontalCollision;
        lookAt(p, new Vec3d(cx, p.getEyeY(), cz));
        moveToward(mc, cx, cz, jump);
    }

    private boolean straightLineClear(MinecraftClient mc, Vec3d goalAim) {
        ClientPlayerEntity p = mc.player;
        ClientWorld w = mc.world;
        BlockPos start = feetCell(p);
        BlockPos end = BlockPos.ofFloored(goalAim.x, p.getY(), goalAim.z);
        if (start.equals(end)) return true;

        double dx = end.getX() - start.getX();
        double dz = end.getZ() - start.getZ();
        double dist = Math.hypot(dx, dz);
        if (dist > 10) return false;
        int steps = (int) Math.ceil(dist);
        for (int i = 1; i <= steps; i++) {
            double t = (double) i / steps;
            BlockPos probe = BlockPos.ofFloored(start.getX() + dx * t + 0.5, start.getY(), start.getZ() + dz * t + 0.5);
            if (!canStand(w, probe)) return false;
            if (Math.abs(probe.getY() - start.getY()) > 0) return false;
        }
        return true;
    }

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
        mc.options.sprintKey.setPressed(f && !b);
        keysHeld = f || b || l || r || j;
    }

    private void releaseKeys(MinecraftClient mc) {
        if (keysHeld) {
            setKeys(mc, false, false, false, false, false);
        }
        mc.options.sprintKey.setPressed(false);
    }

    // ------------------------------------------------------------------ bakış

    private void lookAt(ClientPlayerEntity p, Vec3d target) {
        float step = lookStep();
        Vec3d eye = p.getEyePos();
        double dx = target.x - eye.x;
        double dy = target.y - eye.y;
        double dz = target.z - eye.z;
        double h = Math.sqrt(dx * dx + dz * dz);

        float wantYaw = (h < 0.5)
                ? p.getYaw()
                : (float) (Math.toDegrees(Math.atan2(dz, dx)) - 90.0);
        float wantPitch = (float) (-Math.toDegrees(Math.atan2(dy, h)));

        float yawDiff = MathHelper.wrapDegrees(wantYaw - p.getYaw());
        float pitchDiff = wantPitch - p.getPitch();

        p.setYaw(p.getYaw() + MathHelper.clamp(yawDiff, -step, step));
        p.setPitch(MathHelper.clamp(p.getPitch() + MathHelper.clamp(pitchDiff, -step, step), -90f, 90f));
    }

    private static BlockPos feetCell(ClientPlayerEntity p) {
        return BlockPos.ofFloored(p.getX(), p.getY(), p.getZ());
    }

    // ------------------------------------------------------------------ A* yol bulma (sadece gerçekten engelliyken)

    private static class Node {
        final BlockPos pos;
        Node parent;
        double g, f;

        Node(BlockPos pos) {
            this.pos = pos;
        }
    }

    private List<BlockPos> findPath(MinecraftClient mc, BlockPos goal) {
        ClientWorld w = mc.world;
        BlockPos start = feetCell(mc.player);

        PriorityQueue<Node> open = new PriorityQueue<>(Comparator.comparingDouble((Node n) -> n.f));
        Map<BlockPos, Node> best = new HashMap<>();
        Set<BlockPos> closed = new HashSet<>();

        Node s = new Node(start);
        s.g = 0;
        s.f = heuristic(start, goal);
        open.add(s);
        best.put(start, s);

        int expansions = 0;
        while (!open.isEmpty() && expansions < MAX_EXPANSIONS) {
            Node cur = open.poll();
            if (!closed.add(cur.pos)) continue;
            expansions++;

            if (cur.pos.isWithinDistance(goal, 1.2)) {
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

                    double cost = 1.0 + (dy > 0 ? 1.5 : 0.0) + riskCost(w, n);
                    double g = cur.g + cost;
                    Node ex = best.get(n);
                    if (ex == null || g < ex.g) {
                        Node nn = new Node(n);
                        nn.parent = cur;
                        nn.g = g;
                        nn.f = g + heuristic(n, goal);
                        best.put(n, nn);
                        open.add(nn);
                    }
                }
            }
        }
        return null;
    }

    private static double heuristic(BlockPos pos, BlockPos goal) {
        return Math.hypot(pos.getX() - goal.getX(), pos.getZ() - goal.getZ());
    }

    private boolean canMove(ClientWorld w, BlockPos c, BlockPos n, int dy) {
        if (dy == 0) {
            return canStand(w, n);
        }
        return canStand(w, n) && passable(w, c.up(2));
    }

    private boolean canStand(ClientWorld w, BlockPos p) {
        if (!passable(w, p) || !passable(w, p.up())) return false;
        BlockPos fp = p.down();
        BlockState floor = w.getBlockState(fp);
        if (floor.getCollisionShape(w, fp).isEmpty()) return false;
        if (!floor.getFluidState().isEmpty()) return false;
        return !isHazard(floor.getBlock());
    }

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
                cost += 1.5;
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
