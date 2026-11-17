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

/** Farmdan bagimsiz makrolar (komut + slot tiklama + esya koyma/atma). */
final class Macro {
    private enum T { CMD, CLICK, DEPOSIT, CLOSE, DROP }

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
    private int cycles = 0;
    private int repeat = 0;
    private String filter = "";
    private List<Integer> bulk = null;
    private int bulkIdx = 0;
    private int bulkSync = -1;

    Macro(AutoFarmClient host) {
        this.host = host;
    }

    boolean isRunning() {
        return this.running;
    }

    void start(MinecraftClient mc, int which) {
        if (mc.player == null || mc.world == null) {
            return;
        }
        AutoFarmConfig cfg = AutoFarmConfig.get();
        if (this.host.isEnabled()) {
            this.host.autoOff(mc, "Çiftlik durduruldu (makro başladı)");
        }
        this.steps.clear();
        this.which = which;
        this.delay = cfg.mint("delay", 1, 100);
        this.itemDelay = cfg.mint("itemDelay", 1, 40);
        if (which == 1) {
            this.filter = cfg.mget("m1f");
            this.repeat = cfg.mint("m1rep", 0, 100000);
            this.steps.add(new Step(T.CMD, cfg.mget("m1c1"), 0));
            this.steps.add(new Step(T.CLICK, "", cfg.mint("m1s1", 1, 200)));
            this.steps.add(new Step(T.CLICK, "", cfg.mint("m1s2", 1, 200)));
            this.steps.add(new Step(T.CMD, cfg.mget("m1c2"), 0));
            this.steps.add(new Step(T.CLICK, "", cfg.mint("m1s3", 1, 200)));
            this.steps.add(new Step(T.DEPOSIT, "", 0));
            this.steps.add(new Step(T.CLOSE, "", 0));
            this.steps.add(new Step(T.CLICK, "", cfg.mint("m1s4", 1, 200)));
            this.steps.add(new Step(T.CLOSE, "", 0));
        } else {
            this.filter = cfg.mget("m2f");
            this.repeat = 1;
            this.steps.add(new Step(T.CMD, cfg.mget("m2c"), 0));
            this.steps.add(new Step(T.CLICK, "", cfg.mint("m2s1", 1, 200)));
            this.steps.add(new Step(T.CLICK, "", cfg.mint("m2s2", 1, 200)));
            this.steps.add(new Step(T.DROP, "", 0));
        }
        this.idx = 0;
        this.cool = 0;
        this.wait = 0;
        this.cycles = 0;
        this.bulk = null;
        this.running = true;
        this.say(mc, "Makro " + which + " başladı (durdurmak: H ya da /autofarm macro stop)");
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
                        if (sl.inventory == mc.player.getInventory() && sl.hasStack() && this.matches(sl.getStack())) {
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
                        if (h.getSlot(i).hasStack() && this.matches(h.getSlot(i).getStack())) {
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
            if (this.which == 2 || (this.repeat > 0 && this.cycles >= this.repeat)) {
                this.stop(mc, "Makro " + this.which + " tamamlandı");
                return;
            }
            this.idx = 0;
        }
    }

    private boolean matches(ItemStack st) {
        String f = this.filter.trim().toLowerCase();
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
}
