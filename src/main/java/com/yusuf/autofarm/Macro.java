package com.yusuf.autofarm;

import java.util.ArrayList;
import java.util.List;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.screen.ingame.HandledScreen;
import net.minecraft.client.gui.screen.ingame.InventoryScreen;
import net.minecraft.item.ItemStack;
import net.minecraft.registry.Registries;
import net.minecraft.screen.ScreenHandler;
import net.minecraft.screen.slot.Slot;
import net.minecraft.screen.slot.SlotActionType;
import net.minecraft.text.Text;

/**
 * Farmdan bagimsiz makrolar.
 * 1 ve 2: hazir makrolar, 3: kullanicinin adim adim kurdugu ozel makro.
 */
final class Macro {
    /** CMD: komut, MSG: sohbet mesaji, CLICK: acik menude slota tikla, DEPOSIT: esyalari acik menuye koy,
     *  CLOSE: menuyu kapat (E), DROP: esyalari yere at, WAIT: bekle, HOTBAR: hotbar slotu sec. */
    private enum T { CMD, MSG, CLICK, DEPOSIT, CLOSE, DROP, WAIT, HOTBAR }

    private static final class Step {
        final T t;
        final String s;
        final int n;

        Step(T t, String s, int n) {
            this.t = t;
            this.s = s;
            this.n = n;
        }
    }

    private static final int GUI_TIMEOUT = 100;
    private final AutoFarmClient host;
    private final List<Step> steps = new ArrayList<Step>();
    private boolean running = false;
    private int which = 0;
    private int idx = 0;
    private int cool = 0;
    private int wait = 0;
    private int delay = 10;
    private int itemDelay = 2;
    private int cycleWait = 0;
    private int cycles = 0;
    private int repeat = 0;
    private List<Integer> bulk = null;
    private int bulkIdx = 0;
    private int bulkSync = -1;

    Macro(AutoFarmClient host) {
        this.host = host;
    }

    boolean isRunning() {
        return this.running;
    }

    private String name() {
        return this.which == 3 ? "Özel makro" : "Makro " + this.which;
    }

    void start(MinecraftClient mc, int which) {
        if (mc.player == null || mc.world == null) {
            return;
        }
        AutoFarmConfig cfg = AutoFarmConfig.get();
        this.which = which;
        this.steps.clear();
        this.cycleWait = 0;
        if (which == 1) {
            this.repeat = cfg.mint("m1rep", 0, 100000);
            this.steps.add(new Step(T.CMD, cfg.mget("m1c1"), 0));
            this.steps.add(new Step(T.CLICK, "", cfg.mint("m1s1", 1, 200)));
            this.steps.add(new Step(T.CLICK, "", cfg.mint("m1s2", 1, 200)));
            this.steps.add(new Step(T.CMD, cfg.mget("m1c2"), 0));
            this.steps.add(new Step(T.CLICK, "", cfg.mint("m1s3", 1, 200)));
            this.steps.add(new Step(T.DEPOSIT, cfg.mget("m1f"), 0));
            this.steps.add(new Step(T.CLOSE, "", 0));
            this.steps.add(new Step(T.CLICK, "", cfg.mint("m1s4", 1, 200)));
            this.steps.add(new Step(T.CLOSE, "", 0));
        } else if (which == 2) {
            this.repeat = cfg.mint("m2rep", 0, 100000);
            this.steps.add(new Step(T.CMD, cfg.mget("m2c"), 0));
            this.steps.add(new Step(T.CLICK, "", cfg.mint("m2s1", 1, 200)));
            this.steps.add(new Step(T.CLICK, "", cfg.mint("m2s2", 1, 200)));
            this.steps.add(new Step(T.DROP, cfg.mget("m2f"), 0));
        } else {
            this.repeat = cfg.mint("crep", 0, 100000);
            this.cycleWait = secondsToTicks(cfg.mget("cwait"));
            this.buildCustom(cfg);
            if (this.steps.isEmpty()) {
                this.say(mc, "Özel makroda hiç adım yok. Makrolar > Özel sekmesinden adım ekle.");
                return;
            }
        }
        if (this.host.isEnabled()) {
            this.host.autoOff(mc, "Çiftlik durduruldu (makro başladı)");
        }
        this.delay = cfg.mint("delay", 1, 100);
        this.itemDelay = cfg.mint("itemDelay", 1, 40);
        this.idx = 0;
        this.cool = 0;
        this.wait = 0;
        this.cycles = 0;
        this.bulk = null;
        this.running = true;
        this.say(mc, this.name() + " başladı (durdurmak: H ya da /autofarm macro stop)");
    }

    private void buildCustom(AutoFarmConfig cfg) {
        for (String[] st : cfg.custom) {
            String arg = st[1] == null ? "" : st[1].trim();
            switch (st[0]) {
                case "CMD":
                    if (!arg.isEmpty()) {
                        this.steps.add(arg.startsWith("/") ? new Step(T.CMD, arg, 0) : new Step(T.MSG, arg, 0));
                    }
                    break;
                case "SLOT":
                    this.steps.add(new Step(T.CLICK, "", clamp(parseInt(arg, 1), 1, 200)));
                    break;
                case "DEPOSIT":
                    this.steps.add(new Step(T.DEPOSIT, arg, 0));
                    break;
                case "DROP":
                    this.steps.add(new Step(T.DROP, arg, 0));
                    break;
                case "CLOSE":
                    this.steps.add(new Step(T.CLOSE, "", 0));
                    break;
                case "WAIT":
                    this.steps.add(new Step(T.WAIT, "", secondsToTicks(arg)));
                    break;
                case "HOTBAR":
                    this.steps.add(new Step(T.HOTBAR, "", clamp(parseInt(arg, 1), 1, 9)));
                    break;
                default:
                    break;
            }
        }
    }

    void stop(MinecraftClient mc, String msg) {
        boolean was = this.running;
        this.running = false;
        this.bulk = null;
        if (was && mc != null && mc.player != null) {
            this.say(mc, msg);
        }
    }

    private void say(MinecraftClient mc, String msg) {
        if (mc.player != null) {
            mc.player.sendMessage(Text.literal("[AutoFarm] " + msg), false);
        }
    }

    void tick(MinecraftClient mc) {
        if (!this.running) {
            return;
        }
        if (mc.player == null || mc.world == null || mc.interactionManager == null) {
            this.running = false;
            return;
        }
        if (this.cool > 0) {
            --this.cool;
            return;
        }
        Step s = this.steps.get(this.idx);
        switch (s.t) {
            case CMD: {
                String c = s.s.trim();
                while (c.startsWith("/")) {
                    c = c.substring(1);
                }
                if (!c.isEmpty()) {
                    mc.player.networkHandler.sendChatCommand(c);
                }
                this.next(mc);
                return;
            }
            case MSG: {
                String m = s.s.trim();
                if (!m.isEmpty()) {
                    mc.player.networkHandler.sendChatMessage(m);
                }
                this.next(mc);
                return;
            }
            case CLICK: {
                if (!this.hasContainer(mc)) {
                    this.waitGui(mc);
                    return;
                }
                ScreenHandler h = mc.player.currentScreenHandler;
                int slot = s.n - 1;
                if (slot >= h.slots.size()) {
                    this.stop(mc, "Slot " + s.n + " bu menüde yok, makro durdu");
                    return;
                }
                mc.interactionManager.clickSlot(h.syncId, slot, 0, SlotActionType.PICKUP, mc.player);
                this.next(mc);
                return;
            }
            case DEPOSIT: {
                if (!this.hasContainer(mc)) {
                    this.waitGui(mc);
                    return;
                }
                ScreenHandler h = mc.player.currentScreenHandler;
                if (this.bulk != null && this.bulkSync != h.syncId) {
                    this.bulk = null;
                }
                if (this.bulk == null) {
                    this.bulk = new ArrayList<Integer>();
                    this.bulkIdx = 0;
                    this.bulkSync = h.syncId;
                    for (Slot sl : h.slots) {
                        if (sl.inventory == mc.player.getInventory() && sl.hasStack() && matches(sl.getStack(), s.s)) {
                            this.bulk.add(sl.id);
                        }
                    }
                }
                if (this.bulkIdx < this.bulk.size()) {
                    int id = this.bulk.get(this.bulkIdx++);
                    if (id < h.slots.size() && h.getSlot(id).hasStack()) {
                        mc.interactionManager.clickSlot(h.syncId, id, 0, SlotActionType.QUICK_MOVE, mc.player);
                        this.cool = this.itemDelay - 1;
                    }
                    return;
                }
                this.bulk = null;
                this.next(mc);
                return;
            }
            case CLOSE: {
                if (mc.currentScreen != null) {
                    mc.player.closeHandledScreen();
                }
                this.next(mc);
                return;
            }
            case DROP: {
                if (mc.currentScreen != null) {
                    mc.player.closeHandledScreen();
                    this.cool = this.delay;
                    return;
                }
                ScreenHandler h = mc.player.playerScreenHandler;
                if (this.bulk == null) {
                    this.bulk = new ArrayList<Integer>();
                    this.bulkIdx = 0;
                    for (int i = 9; i <= 44 && i < h.slots.size(); ++i) {
                        if (h.getSlot(i).hasStack() && matches(h.getSlot(i).getStack(), s.s)) {
                            this.bulk.add(i);
                        }
                    }
                }
                if (this.bulkIdx < this.bulk.size()) {
                    int id = this.bulk.get(this.bulkIdx++);
                    if (id < h.slots.size() && h.getSlot(id).hasStack()) {
                        mc.interactionManager.clickSlot(h.syncId, id, 1, SlotActionType.THROW, mc.player);
                        this.cool = this.itemDelay - 1;
                    }
                    return;
                }
                this.bulk = null;
                this.next(mc);
                return;
            }
            case WAIT: {
                this.next(mc);
                if (this.running) {
                    this.cool = Math.max(this.cool, s.n);
                }
                return;
            }
            case HOTBAR: {
                mc.player.getInventory().setSelectedSlot(s.n - 1);
                this.next(mc);
                return;
            }
        }
    }

    private boolean hasContainer(MinecraftClient mc) {
        return mc.currentScreen instanceof HandledScreen<?> && !(mc.currentScreen instanceof InventoryScreen);
    }

    private void waitGui(MinecraftClient mc) {
        if (++this.wait > GUI_TIMEOUT) {
            this.stop(mc, "Menü açılmadı, makro durdu (komut/slot ayarlarını kontrol et)");
        }
    }

    private void next(MinecraftClient mc) {
        this.wait = 0;
        this.cool = this.delay;
        if (++this.idx >= this.steps.size()) {
            ++this.cycles;
            if (this.repeat > 0 && this.cycles >= this.repeat) {
                this.stop(mc, this.name() + " tamamlandı");
                return;
            }
            this.idx = 0;
            this.cool = Math.max(this.cool, this.cycleWait);
        }
    }

    /** Filtre bos ise her esya; doluysa virgullu parcalardan biri esya kimliginde ya da adinda gecmeli. */
    private static boolean matches(ItemStack st, String filter) {
        String f = filter == null ? "" : filter.trim().toLowerCase();
        if (f.isEmpty()) {
            return true;
        }
        String id = Registries.ITEM.getId(st.getItem()).getPath().toLowerCase();
        String name = st.getName().getString().toLowerCase();
        for (String part : f.split(",")) {
            String p = part.trim();
            if (!p.isEmpty() && (id.contains(p) || name.contains(p))) {
                return true;
            }
        }
        return false;
    }

    private static int parseInt(String s, int def) {
        try {
            return Integer.parseInt(s.trim());
        } catch (RuntimeException e) {
            return def;
        }
    }

    private static int clamp(int v, int lo, int hi) {
        return Math.max(lo, Math.min(hi, v));
    }

    /** "1.5" ya da "1,5" saniyeyi tick'e cevirir (en fazla 1 saat). */
    private static int secondsToTicks(String s) {
        try {
            double sec = Double.parseDouble(s.trim().replace(',', '.'));
            return clamp((int)Math.round(sec * 20.0), 0, 72000);
        } catch (RuntimeException e) {
            return 0;
        }
    }
}
