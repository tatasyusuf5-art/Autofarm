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
 * Legit yardımcı otomasyon: güvenli yol bulup hedefe yürür, bloğa bakar (kamera görünür şekilde döner),
 * gerçek bakış ışınıyla (raycast) vurduğu bloğu kırar / eker.
 *
 * Tuşlar: varsayılan G = aç/kapat, R + G = ayar menüsü (ikisi de menüden değiştirilebilir).
 */
public class AutoFarmClient implements ClientModInitializer {
    private static final int SEARCH_RADIUS = 12;     // tarla kaydı yoksa kullanılan serbest arama yarıçapı
    private static final double WALK_STOP_LEGIT = 4.2;   // legit modda kırma için hedefe bu kadar yaklaşınca dur (reach sınırına yakın: durmadan, yürürken de kırabilsin)
    private static final double PLANT_STRICT = 0.3;
    private static final double PLANT_RELAXED = 0.9;
    private static final int AIM_TICKS = 1;
    private static final int TARGET_TIMEOUT = 400;
    private static final int MAX_EXPANSIONS = 3000;
    private static final int PATH_RANGE = 28;

    private enum Kind { HARVEST, PLANT, ITEM }

    private static final String[] MODE_NAMES = {
            "1: Kır (olgun ekinleri kırar)",
            "2: Ek (boş tarlaları eker)",
            "3: Kır + Ek (kırdığını geri eker)",
            "4: Kır + Ek + Ek (kırdığını eker, ayrıca boş tarlaları da eker)"
    };

    private static final int[][] DIRS = {{1, 0}, {-1, 0}, {0, 1}, {0, -1}};

    private final AutoFarmConfig cfg = AutoFarmConfig.get();

    private boolean enabled = false;
    private boolean openGui = false;
    private boolean prevToggle = false;
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

    // tarla rotası (boustrophedon sıralı hücre listesi, aktif kat için)
    private List<BlockPos> route;
    private int routeIdx;
    private int currentFloor;

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

    /** Oyuncunun baktığı bloğu tarla köşesi olarak kaydeder (menüden ya da /autofarm corner ile). */
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
        if (idx == 0 || cfg.fx2 != 0 || cfg.fz2 != 0) {
            cfg.fieldSet = (idx == 1) || cfg.fieldSet;
        }
        // iki köşe de bir kere girildiyse tarla aktif say
        cfg.fieldSet = true;
        cfg.save();
        route = null;
        say(mc, (idx == 0 ? "1." : "2.") + " köşe kaydedildi: " + pos.toShortString());
        mc.player.sendMessage(Text.literal("[AutoFarm] " + (idx == 0 ? "1." : "2.")
                + " köşe kaydedildi: " + pos.toShortString()), false);
    }

    private void resetState(MinecraftClient mc) {
        clearTarget();
        blacklist.clear();
        replant.clear();
        ignoredItems.clear();
        route = null;
        routeIdx = 0;
        currentFloor = 0;
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

    // Hız ayarı (1..15): bakış dönüş hızı ve işlemler arası bekleme.
    // Godmode açıkken bakış/bekleme sınırları en üst legit seviyeye sabitlenir.
    private float lookStep() {
        if (cfg.godMode) return 180f;   // aynı tick içinde istenen açıya tam dönebilir (reach dışına çıkarmaz)
        return 9f * cfg.speed;          // 10 -> 90 (eski 10 ile aynı), 15 -> 135 (+%50)
    }

    private int actionDelay() {
        if (cfg.godMode) return 0;
        return cfg.speed >= 10 ? 0 : Math.max(1, 6 - cfg.speed / 2);
    }

    private double reach() {
        // Godmode bile vanilla etkileşim mesafesini aşmaz; oyuncunun gerçek
        // blockInteractionRange değeri kullanılır (en üst legit sınır).
        return 0; // kullanılmıyor, raycast doğrudan player.getBlockInteractionRange() çağırıyor
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
        // Mod 3: sadece kırdığı yeri geri eker. Mod 4: kırdığı yeri VE serbest boş tarlaları eker.
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
                releaseKeys(mc);
                return;
            }
            plantBlock = ((BlockItem) mc.player.getStackInHand(plantHand).getItem()).getBlock();
        }

        if (targetKind != null && !targetStillValid(mc, plantBlock)) {
            clearTarget();
        }
        if (targetKind == null) {
            findTarget(mc, plantBlock, harvestMode, plantFreeTiles);
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

    private static String keyName(int code) {
        return InputUtil.fromKeyCode(code, 0).getLocalizedText().getString();
    }

    private void step(MinecraftClient mc, Hand plantHand, Block plantBlock) {
        ClientPlayerEntity p = mc.player;
        double walkStop = Math.min(WALK_STOP_LEGIT, p.getBlockInteractionRange());

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
        boolean inReach = eyeDist <= walkStop;
        boolean standOk = (targetKind == Kind.HARVEST) ? inReach : (hd <= approach);

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
                if (!ok) {
                    BlockPos hp = b.getBlockPos();
                    BlockState hs = mc.world.getBlockState(hp);
                    boolean harvestMode = cfg.mode == 0 || cfg.mode == 2 || cfg.mode == 3;
                    if (sameLevel(mc, hp) && harvestMode && isMature(hs) && cfg.isSelected(hs.getBlock())
                            && !blacklist.containsKey(hp)) {
                        targetPos = hp.toImmutable();
                        targetKind = Kind.HARVEST;
                        ok = true;
                    } else if (plantBlock != null && b.getSide() == Direction.UP) {
                        BlockPos above = hp.up();
                        if (sameLevel(mc, above)
                                && mc.world.getBlockState(above).isAir()
                                && (cfg.mode == 1 || cfg.mode == 3 || replant.containsKey(above))
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
                navigate(mc, aim, Kind.HARVEST, walkStop);
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
            if (cfg.mode == 2 || cfg.mode == 3) {
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

    private void findTarget(MinecraftClient mc, Block plantBlock, boolean harvest, boolean plantFreeTiles) {
        ClientPlayerEntity p = mc.player;

        if (cfg.fieldSet) {
            findTargetInRoute(mc, plantBlock, harvest, plantFreeTiles);
            if (targetKind != null) return;
        } else {
            findTargetFreeScan(mc, plantBlock, harvest, plantFreeTiles);
            if (targetKind != null) return;
        }

        // Blok hedefi yoksa yere düşen ekinleri topla (serbest arama, tarla sınırından bağımsız)
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

    /** Tarla sınırı yokken eski davranış: en yakın geçerli hedefi serbestçe ara. */
    private void findTargetFreeScan(MinecraftClient mc, Block plantBlock, boolean harvest, boolean plantFreeTiles) {
        ClientPlayerEntity p = mc.player;
        Vec3d eye = p.getEyePos();
        BlockPos origin = feetCell(p);

        double best = Double.MAX_VALUE;
        BlockPos bestPos = null;
        Kind bestKind = null;

        for (BlockPos pos : BlockPos.iterate(
                origin.add(-SEARCH_RADIUS, -1, -SEARCH_RADIUS),
                origin.add(SEARCH_RADIUS, 1, SEARCH_RADIUS))) {
            if (blacklist.containsKey(pos)) continue;
            if (!sameLevel(mc, pos)) continue;
            BlockState s = mc.world.getBlockState(pos);
            Kind k = null;
            if (harvest && isMature(s) && cfg.isSelected(s.getBlock())) {
                k = Kind.HARVEST;
            } else if (plantFreeTiles && plantBlock != null && s.isAir()
                    && plantBlock.getDefaultState().canPlaceAt(mc.world, pos)) {
                k = Kind.PLANT;
            } else if (plantBlock != null && s.isAir() && replant.containsKey(pos)
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
        }
    }

    /**
     * Tarla sınırı kayıtlıysa: önceden hesaplanmış satır-satır (boustrophedon) rotayı takip eder.
     * Kat bittiğinde kaydedilen köşelerin X/Z sınırını bir üst kata taşır (cfg.floors kadar).
     */
    private void findTargetInRoute(MinecraftClient mc, Block plantBlock, boolean harvest, boolean plantFreeTiles) {
        ClientPlayerEntity p = mc.player;

        if (route == null) {
            buildRoute(mc);
        }
        if (route == null || route.isEmpty()) return;

        // mevcut rota içinden geçerli bir sonraki hedefi bul (kırma önceliği, sonra ekim)
        for (int tries = 0; tries < route.size(); tries++) {
            if (routeIdx >= route.size()) {
                // bu kat bitti: bir üst kata geç
                currentFloor++;
                if (currentFloor >= cfg.floors) {
                    return;   // tüm katlar tamamlandı
                }
                route = null;
                buildRoute(mc);
                if (route == null || route.isEmpty()) return;
                routeIdx = 0;
            }
            BlockPos pos = route.get(routeIdx);
            if (blacklist.containsKey(pos)) {
                routeIdx++;
                continue;
            }
            BlockState s = mc.world.getBlockState(pos);
            if (harvest && isMature(s) && cfg.isSelected(s.getBlock())) {
                targetPos = pos;
                targetKind = Kind.HARVEST;
                targetTicks = 0;
                aimedTicks = 0;
                return;
            }
            if (plantBlock != null && s.isAir() && plantBlock.getDefaultState().canPlaceAt(mc.world, pos)
                    && (plantFreeTiles || replant.containsKey(pos))) {
                targetPos = pos;
                targetKind = Kind.PLANT;
                targetTicks = 0;
                aimedTicks = 0;
                return;
            }
            routeIdx++;
        }
    }

    /** İki köşe arasındaki tüm hücreleri, en kısa yürüyüş için satır-satır (boustrophedon) sıralar. */
    private void buildRoute(MinecraftClient mc) {
        int minX = Math.min(cfg.fx1, cfg.fx2), maxX = Math.max(cfg.fx1, cfg.fx2);
        int minZ = Math.min(cfg.fz1, cfg.fz2), maxZ = Math.max(cfg.fz1, cfg.fz2);
        int y = cfg.baseFloorY() + currentFloor;

        List<BlockPos> list = new ArrayList<>();
        boolean reverse = false;
        for (int x = minX; x <= maxX; x++) {
            if (!reverse) {
                for (int z = minZ; z <= maxZ; z++) list.add(new BlockPos(x, y, z));
            } else {
                for (int z = maxZ; z >= minZ; z--) list.add(new BlockPos(x, y, z));
            }
            reverse = !reverse;
        }
        route = list;
        routeIdx = 0;
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
        if (cfg.fieldSet && route != null) {
            routeIdx++;   // rotadaki bu hücreyi atla, takılıp kalma
        }
        clearTarget();
    }

    // ------------------------------------------------------------------ bakış

    private static Vec3d aimPoint(BlockPos pos, Kind kind) {
        if (kind == Kind.HARVEST) {
            return new Vec3d(pos.getX() + 0.5, pos.getY() + 0.35, pos.getZ() + 0.5);
        }
        return new Vec3d(pos.getX() + 0.5, pos.getY() - 1 + 0.9375, pos.getZ() + 0.5);
    }

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

    // ------------------------------------------------------------------ yürüme (yol bulma ile)

    private static BlockPos feetCell(ClientPlayerEntity p) {
        return BlockPos.ofFloored(p.getX(), p.getY(), p.getZ());
    }

    private boolean sameLevel(MinecraftClient mc, BlockPos pos) {
        int feetY = feetCell(mc.player).getY();
        return pos.getY() >= feetY - 1 && pos.getY() <= feetY + 1;
    }

    private void navigate(MinecraftClient mc, Vec3d goalAim, Kind kind, double radius) {
        ClientPlayerEntity p = mc.player;

        // Hızlı yol: hedef, önümüzdeki düz çizgide ve engelsizse A* çalıştırmadan
        // direkt o yöne yürü. Tarlada sıra boyunca sürekli koşmayı sağlayan asıl kısım
        // burasıdır; A* sadece gerçekten engele takılınca devreye girer.
        if (path == null && straightLineClear(mc, goalAim)) {
            moveToward(mc, goalAim.x, goalAim.z, false);
            return;
        }

        if (path == null) {
            List<BlockPos> found = findPath(mc, goalAim, kind, radius);
            if (found == null && kind == Kind.PLANT && radius < PLANT_RELAXED) {
                approach = PLANT_RELAXED;
                found = findPath(mc, goalAim, kind, PLANT_RELAXED);
            }
            if (found == null) {
                releaseKeys(mc);
                giveUp();
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
            path = null;
            releaseKeys(mc);
            return;
        }

        BlockPos wp = path.get(pathIdx);
        double cx = wp.getX() + 0.5;
        double cz = wp.getZ() + 0.5;
        double d = Math.hypot(cx - p.getX(), cz - p.getZ());

        if (d < 1.1 && Math.abs(p.getY() - wp.getY()) < 1.6) {
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
            path = null;
            releaseKeys(mc);
            return;
        }

        boolean needsClimb = wp.getY() > feetCell(p).getY();
        boolean jump = needsClimb && p.isOnGround() && p.horizontalCollision;
        moveToward(mc, cx, cz, jump);
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
        // İleri giderken koşsun (Sprint tuşunu da tutarak) — tarlada sürekli hızlı yürüsün.
        mc.options.sprintKey.setPressed(f && !b);
        keysHeld = f || b || l || r || j;
    }

    private void releaseKeys(MinecraftClient mc) {
        if (keysHeld) {
            setKeys(mc, false, false, false, false, false);
        }
        mc.options.sprintKey.setPressed(false);
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

                    double cost = 1.0 + (dy > 0 ? 1.5 : 0.0) + riskCost(w, n);
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
            return eye.distanceTo(aim) <= WALK_STOP_LEGIT;
        }
        double hd = Math.hypot(pos.getX() + 0.5 - aim.x, pos.getZ() + 0.5 - aim.z);
        if (kind == Kind.PLANT) {
            return hd <= radius && Math.abs(pos.getY() - targetPos.getY()) <= 1;
        }
        return hd <= radius && Math.abs(pos.getY() + 0.5 - aim.y) <= 1.6;
    }

    private boolean canMove(ClientWorld w, BlockPos c, BlockPos n, int dy) {
        if (dy == 0) {
            return canStand(w, n);
        }
        return canStand(w, n) && passable(w, c.up(2));
    }

    /**
     * Oyuncudan hedefe düz çizgide, aradaki hücreler yürünebilir mi diye hızlı bir kontrol.
     * Satır içinde ardışık ekinleri tararken her adımda A* çalıştırmamak için kullanılır:
     * yol açıksa doğrudan yürür, kapalıysa navigate() otomatik olarak tam A*'a döner.
     */
    private boolean straightLineClear(MinecraftClient mc, Vec3d goalAim) {
        ClientPlayerEntity p = mc.player;
        ClientWorld w = mc.world;
        BlockPos start = feetCell(p);
        BlockPos end = BlockPos.ofFloored(goalAim.x, p.getY(), goalAim.z);
        if (start.equals(end)) return true;

        double dx = end.getX() - start.getX();
        double dz = end.getZ() - start.getZ();
        double dist = Math.hypot(dx, dz);
        if (dist > 10) return false;   // çok uzaksa A*'a bırak, daha güvenli planlar
        int steps = (int) Math.ceil(dist);
        for (int i = 1; i <= steps; i++) {
            double t = (double) i / steps;
            BlockPos probe = BlockPos.ofFloored(start.getX() + dx * t + 0.5, start.getY(), start.getZ() + dz * t + 0.5);
            if (!canStand(w, probe)) return false;
            if (Math.abs(probe.getY() - start.getY()) > 0) return false;   // düz çizgi modunda kat değişimi yok
        }
        return true;
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
