package com.yusuf.autofarm;

import com.mojang.brigadier.arguments.IntegerArgumentType;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandManager;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.class_1268;
import net.minecraft.class_1269;
import net.minecraft.class_1542;
import net.minecraft.class_1713;
import net.minecraft.class_1747;
import net.minecraft.class_1799;
import net.minecraft.class_1887;
import net.minecraft.class_1893;
import net.minecraft.class_2248;
import net.minecraft.class_2302;
import net.minecraft.class_2338;
import net.minecraft.class_2350;
import net.minecraft.class_238;
import net.minecraft.class_239;
import net.minecraft.class_2421;
import net.minecraft.class_243;
import net.minecraft.class_2561;
import net.minecraft.class_2680;
import net.minecraft.class_310;
import net.minecraft.class_3486;
import net.minecraft.class_3489;
import net.minecraft.class_3532;
import net.minecraft.class_3675;
import net.minecraft.class_3965;
import net.minecraft.class_6880;
import net.minecraft.class_746;
import net.minecraft.class_9304;
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
    private static final double WALK_STOP = 2.6;     // hedefe bu kadar yaklaşınca dur (daha yakın = daha temiz bakış)
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

    private class_2338 targetPos;
    private Kind targetKind;
    private class_1542 targetItem;
    private int targetTicks, aimedTicks, actionCooldown, tickCounter, warnCooldown;
    private boolean fwdHeld, jumpHeld, leftHeld, rightHeld;

    /** Engelden kaçınma: -1 sol, +1 sağ, 0 yok */
    private int avoidDir = 0;
    private int avoidTicks = 0;
    private int stuckTicks = 0;
    private class_243 lastPos = null;

    private final Map<class_2338, Integer> blacklist = new HashMap<>();
    private final Map<class_2338, Integer> replant = new HashMap<>();
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
                            toggle(class_310.method_1551());
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
                                            resetState(class_310.method_1551());
                                            say(class_310.method_1551(), "Mod -> " + MODE_NAMES[cfg.mode]);
                                            return 1;
                                        })))
                        .then(ClientCommandManager.literal("speed")
                                .then(ClientCommandManager.argument("n", IntegerArgumentType.integer(1, 10))
                                        .executes(ctx -> {
                                            cfg.speed = IntegerArgumentType.getInteger(ctx, "n");
                                            cfg.save();
                                            say(class_310.method_1551(), "Hız -> " + cfg.speed + "/10");
                                            return 1;
                                        })))));
    }

    // ------------------------------------------------------------------ kontrol

    public boolean isEnabled() {
        return enabled;
    }

    public void toggle(class_310 mc) {
        enabled = !enabled;
        resetState(mc);
        String msg = "AutoFarm " + (enabled ? "AÇIK" : "KAPALI") + " | Mod " + MODE_NAMES[cfg.mode];
        say(mc, msg);
        if (mc.field_1724 != null) {
            mc.field_1724.method_7353(class_2561.method_43470("[AutoFarm] " + msg), false);
        }
    }

    private void resetState(class_310 mc) {
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

    private void say(class_310 mc, String msg) {
        if (mc.field_1724 != null) {
            mc.field_1724.method_7353(class_2561.method_43470(msg), true);
        }
    }

    private void warn(class_310 mc, String msg) {
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

    private void onTick(class_310 mc) {
        tickCounter++;

        // Oyuna girince yüklendiğini göster
        if (announce && mc.field_1724 != null && mc.field_1687 != null) {
            announce = false;
            mc.field_1724.method_7353(class_2561.method_43470(
                    "[AutoFarm] Yüklendi. G = aç/kapat, R + G = menü, ya da /autofarm gui"), false);
        }

        // Tuşlar doğrudan klavyeden okunur (başka modlarla tuş çakışmasından etkilenmez)
        boolean g = false;
        boolean r = false;
        if (mc.field_1755 == null && mc.field_1724 != null) {
            long window = mc.method_22683().method_4490();
            g = class_3675.method_15987(window, GLFW.GLFW_KEY_G);
            r = class_3675.method_15987(window, GLFW.GLFW_KEY_R);
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

        if (openGui && mc.field_1724 != null && mc.field_1755 == null) {
            openGui = false;
            releaseKeys(mc);
            mc.method_1507(new AutoFarmScreen(this));
            return;
        }

        if (warnCooldown > 0) warnCooldown--;
        if (actionCooldown > 0) actionCooldown--;

        if (!enabled || mc.field_1724 == null || mc.field_1687 == null || mc.field_1761 == null
                || mc.field_1755 != null || mc.field_1724.method_7325()) {
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
            class_1799 tool = mc.field_1724.method_6047();
            if (cfg.requireHoe && !tool.method_31573(class_3489.field_42613)) {
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
        class_1268 plantHand = null;
        class_2248 plantBlock = null;
        if (plantMode) {
            plantHand = findPlantHand(mc, cfg.mode == 1);   // Mod 2: ana/2. el, Mod 3: sadece 2. el
            if (plantHand == null) {
                supply(mc);
                releaseKeys(mc);
                return;
            }
            plantBlock = ((class_1747) mc.field_1724.method_5998(plantHand).method_7909()).method_7711();
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

    private void step(class_310 mc, class_1268 plantHand) {
        class_746 p = mc.field_1724;

        // Takılma tespiti (neredeyse yerinde sayıyorsa)
        class_243 now = p.method_19538();
        if (lastPos != null) {
            double moved = Math.hypot(now.field_1352 - lastPos.field_1352, now.field_1350 - lastPos.field_1350);
            if (moved < 0.05 && (fwdHeld || leftHeld || rightHeld)) {
                stuckTicks++;
            } else {
                stuckTicks = 0;
            }
        }
        lastPos = now;

        // Yerdeki eşyaları topla
        if (targetKind == Kind.ITEM) {
            class_243 ip = targetItem.method_19538();
            float err = lookAt(p, ip.method_1031(0, 0.1, 0));
            double hd = Math.hypot(ip.field_1352 - p.method_23317(), ip.field_1350 - p.method_23321());
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

        class_243 aim = aimPoint(targetPos, targetKind);
        float yawErr = lookAt(p, aim);
        double dist = p.method_33571().method_1022(aim);

        // Uzaktaysa yürü; engel varsa etrafından dolaş
        if (dist > WALK_STOP) {
            aimedTicks = 0;
            if (isDangerous(mc, true) || stuckTicks > 8 || p.field_5976) {
                startAvoid(mc);
            }
            navigate(mc, yawErr < 45f);
            return;
        }

        // Yakındayız — hareketi durdur, net bakış al
        stopMove(mc);

        // Gerçek bakış ışını hedefi gösteriyor mu?
        class_239 hr = p.method_5745(p.method_55754(), 1.0f, false);
        class_3965 bhr = null;
        boolean ok = false;
        if (hr instanceof class_3965 b && b.method_17783() == class_239.class_240.field_1332) {
            bhr = b;
            if (targetKind == Kind.HARVEST) {
                ok = b.method_17777().equals(targetPos);
            } else {
                // Ekim: toprağın üst yüzeyi. Komşu ekin ray'i keserse ok=false olur
                ok = b.method_17777().equals(targetPos.method_10074())
                        && b.method_17780() == class_2350.field_11036;
            }
        }

        if (!ok) {
            aimedTicks = 0;
            // Ekimde önündeki ekin / engel bakışı kesiyorsa yana kayıp temiz açı ara
            if (targetKind == Kind.PLANT || targetKind == Kind.HARVEST) {
                if (avoidTicks <= 0) {
                    startAvoid(mc);
                }
                // Hafif yana kayarak bakış açısını temizle
                navigate(mc, false);
            }
            return;
        }

        if (++aimedTicks < AIM_TICKS || actionCooldown > 0) return;

        if (targetKind == Kind.HARVEST) {
            mc.field_1761.method_2910(targetPos, bhr.method_17780());
            p.method_6104(class_1268.field_5808);
            if (cfg.mode == 2) {
                replant.put(targetPos.method_10062(), tickCounter + 100);
            }
        } else {
            class_1269 r = mc.field_1761.method_2896(p, plantHand, bhr);
            if (r.method_23665()) {
                p.method_6104(plantHand);
                replant.remove(targetPos);
            } else {
                // Ekim başarısız (muhtemelen hâlâ engel) → kısa blacklist
                blacklist.put(targetPos.method_10062(), tickCounter + 160);
            }
        }
        actionCooldown = actionDelay();
        avoidDir = 0;
        avoidTicks = 0;
        stuckTicks = 0;
        clearTarget();
    }

    // ------------------------------------------------------------------ ekin elde tutma / çantadan alma

    private class_1268 findPlantHand(class_310 mc, boolean allowMain) {
        class_746 p = mc.field_1724;
        if (allowMain && isSelectedSeed(p.method_6047())) return class_1268.field_5808;
        if (isSelectedSeed(p.method_6079())) return class_1268.field_5810;
        return null;
    }

    /** Çantadaki seçili ekini 2. ele alır. */
    private void supply(class_310 mc) {
        class_746 p = mc.field_1724;
        class_1799 off = p.method_6079();
        if (!off.method_7960() && !isSeed(off)) {
            warn(mc, "2. el dolu! Ekin otomatik 2. ele alınır, eli boşalt.");
            return;
        }
        if (actionCooldown > 0) return;
        for (int i = 0; i < 36; i++) {
            if (isSelectedSeed(p.method_31548().method_5438(i))) {
                int slot = i < 9 ? i + 36 : i;
                mc.field_1761.method_2906(p.field_7498.field_7763, slot, 40,
                        class_1713.field_7791, p);
                actionCooldown = 10;
                return;
            }
        }
        warn(mc, "Çantada seçili ekin bulunamadı!");
    }

    // ------------------------------------------------------------------ hedef bulma

    private void findTarget(class_310 mc, class_2248 plantBlock) {
        class_746 p = mc.field_1724;
        boolean harvest = (cfg.mode == 0 || cfg.mode == 2);
        class_243 eye = p.method_33571();
        class_2338 origin = p.method_24515();

        double best = Double.MAX_VALUE;
        class_2338 bestPos = null;
        Kind bestKind = null;

        for (class_2338 pos : class_2338.method_10097(
                origin.method_10069(-SEARCH_RADIUS, -2, -SEARCH_RADIUS),
                origin.method_10069(SEARCH_RADIUS, 2, SEARCH_RADIUS))) {
            if (blacklist.containsKey(pos)) continue;
            class_2680 s = mc.field_1687.method_8320(pos);
            Kind k = null;
            if (harvest && isMature(s) && cfg.isSelected(s.method_26204())) {
                k = Kind.HARVEST;
            } else if (plantBlock != null && s.method_26215()
                    && (cfg.mode == 1 || replant.containsKey(pos))
                    && plantBlock.method_9564().method_26184(mc.field_1687, pos)) {
                k = Kind.PLANT;
            }
            if (k == null) continue;
            double d = eye.method_1025(class_243.method_24953(pos));
            if (d < best) {
                best = d;
                bestPos = pos.method_10062();
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
            class_238 box = p.method_5829().method_1009(10, 3, 10);
            class_1542 bestItem = null;
            double bd = Double.MAX_VALUE;
            for (class_1542 ie : mc.field_1687.method_8390(class_1542.class, box,
                    x -> !x.method_31481() && !ignoredItems.contains(x.method_5628()))) {
                double d = ie.method_5858(p);
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

    private boolean targetStillValid(class_310 mc, class_2248 plantBlock) {
        return switch (targetKind) {
            case HARVEST -> {
                class_2680 s = mc.field_1687.method_8320(targetPos);
                yield isMature(s) && cfg.isSelected(s.method_26204());
            }
            case PLANT -> plantBlock != null
                    && mc.field_1687.method_8320(targetPos).method_26215()
                    && plantBlock.method_9564().method_26184(mc.field_1687, targetPos);
            case ITEM -> targetItem != null && !targetItem.method_31481();
        };
    }

    private void clearTarget() {
        targetKind = null;
        targetPos = null;
        targetItem = null;
        targetTicks = 0;
        aimedTicks = 0;
        // avoid state'i burada sıfırlama — aynı bölgede başka hedefe geçerken
        // kaçınma devam etsin diye leave it
    }

    private void giveUp() {
        if (targetKind == Kind.ITEM && targetItem != null) {
            ignoredItems.add(targetItem.method_5628());
        } else if (targetPos != null) {
            blacklist.put(targetPos.method_10062(), tickCounter + 600);
        }
        avoidDir = 0;
        avoidTicks = 0;
        stuckTicks = 0;
        clearTarget();
    }

    // ------------------------------------------------------------------ bakış & hareket

    private static class_243 aimPoint(class_2338 pos, Kind kind) {
        if (kind == Kind.HARVEST) {
            // Ekinin ortası (komşu ekinle çakışmayı azaltmak için biraz aşağı)
            return new class_243(pos.method_10263() + 0.5, pos.method_10264() + 0.25, pos.method_10260() + 0.5);
        }
        // Ekim: tarla bloğunun üst yüzeyi — daha dik bakış için biraz daha aşağı hedefle
        return new class_243(pos.method_10263() + 0.5, pos.method_10264() - 1 + 0.85, pos.method_10260() + 0.5);
    }

    /** Kamerayı hedefe doğru kademeli çevirir; kalan yaw hatasını (derece) döndürür. */
    private float lookAt(class_746 p, class_243 target) {
        float step = lookStep();
        class_243 eye = p.method_33571();
        double dx = target.field_1352 - eye.field_1352;
        double dy = target.field_1351 - eye.field_1351;
        double dz = target.field_1350 - eye.field_1350;
        double h = Math.sqrt(dx * dx + dz * dz);

        float wantYaw = (float) (Math.toDegrees(Math.atan2(dz, dx)) - 90.0);
        float wantPitch = (float) (-Math.toDegrees(Math.atan2(dy, h)));

        float yawDiff = class_3532.method_15393(wantYaw - p.method_36454());
        float pitchDiff = wantPitch - p.method_36455();

        p.method_36456(p.method_36454() + class_3532.method_15363(yawDiff, -step, step));
        p.method_36457(class_3532.method_15363(p.method_36455() + class_3532.method_15363(pitchDiff, -step, step), -90f, 90f));

        return Math.abs(yawDiff) > step ? Math.abs(yawDiff) - step : 0f;
    }

    /** İleri + (gerekirse) yana kayarak en güvenli yoldan ilerle. */
    private void navigate(class_310 mc, boolean wantForward) {
        class_746 p = mc.field_1724;

        if (avoidTicks > 0) {
            avoidTicks--;
            // Kaçınma sırasında hafif ileri + yana
            setMove(mc, true, avoidDir < 0, avoidDir > 0);
            // Engel hâlâ varsa zıpla (basamak / alçak fener kenarı)
            boolean jump = p.field_5976 && p.method_24828();
            mc.field_1690.field_1903.method_23481(jump);
            jumpHeld = jump;
            if (avoidTicks == 0) {
                avoidDir = 0;
            }
            return;
        }

        setMove(mc, wantForward, false, false);
        boolean jump = wantForward && p.field_5976 && p.method_24828();
        mc.field_1690.field_1903.method_23481(jump);
        jumpHeld = jump;
    }

    private void stopMove(class_310 mc) {
        setMove(mc, false, false, false);
        if (jumpHeld) {
            mc.field_1690.field_1903.method_23481(false);
            jumpHeld = false;
        }
    }

    private void setMove(class_310 mc, boolean fwd, boolean left, boolean right) {
        mc.field_1690.field_1894.method_23481(fwd);
        mc.field_1690.field_1913.method_23481(left);   // A
        mc.field_1690.field_1849.method_23481(right);  // D
        fwdHeld = fwd;
        leftHeld = left;
        rightHeld = right;
    }

    private void releaseKeys(class_310 mc) {
        if (fwdHeld || leftHeld || rightHeld || jumpHeld) {
            mc.field_1690.field_1894.method_23481(false);
            mc.field_1690.field_1913.method_23481(false);
            mc.field_1690.field_1849.method_23481(false);
            mc.field_1690.field_1903.method_23481(false);
            fwdHeld = leftHeld = rightHeld = jumpHeld = false;
        }
    }

    /** Engel / su / lav / uçurum algılanınca yana kaçınmayı başlat. */
    private void startAvoid(class_310 mc) {
        if (avoidTicks > 0) return;
        // Hedefe göre hangi taraf daha açık, onu tercih et
        int preferred = chooseSaferSide(mc);
        avoidDir = preferred != 0 ? preferred : (tickCounter % 2 == 0 ? 1 : -1);
        avoidTicks = AVOID_DURATION;
        stuckTicks = 0;
    }

    /**
     * Hedefin soluna / sağına bakarak su veya katı engel daha az olan tarafı seç.
     * @return -1 sol, +1 sağ, 0 belirsiz
     */
    private int chooseSaferSide(class_310 mc) {
        class_746 p = mc.field_1724;
        class_243 eye = p.method_33571();
        class_243 toTarget;
        if (targetKind == Kind.ITEM && targetItem != null) {
            toTarget = targetItem.method_19538().method_1020(eye);
        } else if (targetPos != null) {
            toTarget = class_243.method_24953(targetPos).method_1020(eye);
        } else {
            return 0;
        }
        // Yatay dik (sol / sağ) vektör
        double len = Math.hypot(toTarget.field_1352, toTarget.field_1350);
        if (len < 1e-4) return 0;
        double rx = -toTarget.field_1350 / len;
        double rz = toTarget.field_1352 / len;

        int leftScore = sideScore(mc, eye, -rx, -rz);
        int rightScore = sideScore(mc, eye, rx, rz);
        if (leftScore > rightScore) return -1;
        if (rightScore > leftScore) return 1;
        return 0;
    }

    /** Bir yöne 1-3 blok bak: su/lav/uçurum/katı engel yoksa yüksek puan. */
    private int sideScore(class_310 mc, class_243 from, double dx, double dz) {
        int score = 0;
        for (int step = 1; step <= 3; step++) {
            class_2338 pos = class_2338.method_49638(
                    from.method_1031(dx * step, 0, dz * step));
            class_2680 ground = mc.field_1687.method_8320(pos.method_10074());
            class_2680 feet = mc.field_1687.method_8320(pos);
            class_2680 head = mc.field_1687.method_8320(pos.method_10084());

            // Lav veya su → kötü
            if (isFluidDanger(feet) || isFluidDanger(head) || isFluidDanger(ground)) {
                score -= 5;
                continue;
            }
            // Uçurum (alt boş)
            if (ground.method_26220(mc.field_1687, pos.method_10074()).method_1110()
                    && ground.method_26227().method_15769()) {
                score -= 4;
                continue;
            }
            // Ayak veya kafa hizasında katı engel (fener, duvar, ekin dışı blok)
            if (isSolidObstacle(feet, mc, pos) || isSolidObstacle(head, mc, pos.method_10084())) {
                score -= 3;
                continue;
            }
            score += 2;
        }
        return score;
    }

    private static boolean isFluidDanger(class_2680 s) {
        return s.method_26227().method_15767(class_3486.field_15518)   // lava
                || s.method_26227().method_15767(class_3486.field_15517); // water
    }

    /** Fener, duvar, çit vb. — ekin / hava / tarla değilse engel say. */
    private static boolean isSolidObstacle(class_2680 s, class_310 mc, class_2338 pos) {
        if (s.method_26215()) return false;
        class_2248 b = s.method_26204();
        if (b instanceof class_2302 || b instanceof class_2421) return false; // ekin serbest
        // Çarpışma kutusu boşsa (halı, basınç plakası) sorun değil
        return !s.method_26220(mc.field_1687, pos).method_1110();
    }

    /**
     * Önümüzdeki yol tehlikeli mi? (su, lav, uçurum, katı engel)
     * @param checkBody true ise ayak + kafa yüksekliğini de kontrol et (fener için)
     */
    private boolean isDangerous(class_310 mc, boolean checkBody) {
        class_746 p = mc.field_1724;
        class_243 dir = class_243.method_1030(0, p.method_36454());
        class_243 base = p.method_19538();

        for (int i = 1; i <= 3; i++) {
            class_243 probe = base.method_1019(dir.method_1021(i));
            class_2338 pos = class_2338.method_49638(probe);
            class_2680 ground = mc.field_1687.method_8320(pos.method_10074());
            class_2680 feet = mc.field_1687.method_8320(pos);
            class_2680 head = mc.field_1687.method_8320(pos.method_10084());

            if (isFluidDanger(feet) || isFluidDanger(head) || isFluidDanger(ground)) return true;

            // Uçurum
            if (ground.method_26220(mc.field_1687, pos.method_10074()).method_1110()
                    && ground.method_26227().method_15769()) {
                return true;
            }

            if (checkBody) {
                if (isSolidObstacle(feet, mc, pos) || isSolidObstacle(head, mc, pos.method_10084())) {
                    return true;
                }
            }
        }
        return false;
    }

    // ------------------------------------------------------------------ yardımcılar

    private static boolean isMature(class_2680 state) {
        class_2248 b = state.method_26204();
        if (b instanceof class_2302 crop) {
            return crop.method_9825(state);
        }
        if (b instanceof class_2421) {
            return state.method_11654(class_2421.field_11306) >= 3;
        }
        return false;
    }

    private static boolean isSeed(class_1799 stack) {
        if (stack.method_7960() || !(stack.method_7909() instanceof class_1747 bi)) return false;
        class_2248 b = bi.method_7711();
        return b instanceof class_2302 || b instanceof class_2421;
    }

    private boolean isSelectedSeed(class_1799 stack) {
        return isSeed(stack) && cfg.isSelected(((class_1747) stack.method_7909()).method_7711());
    }

    private static int fortuneLevel(class_1799 stack) {
        class_9304 ench = stack.method_58657();
        for (class_6880<class_1887> e : ench.method_57534()) {
            if (e.method_40225(class_1893.field_9130)) {
                return ench.method_57536(e);
            }
        }
        return 0;
    }
}
