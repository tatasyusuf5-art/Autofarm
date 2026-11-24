package com.yusuf.autofarm;

import java.util.List;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.client.gui.widget.TextFieldWidget;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;

public class AutoFarmMacroScreen
extends Screen {
    private static final String[] TABS = new String[]{"Makro 1", "Makro 2", "Özel", "Gecikme"};
    private static final int TAB_CUSTOM = 2;
    private static final int TAB_DELAY = 3;
    private static final String[][] TAB1 = new String[][]{
        {"Komut 1", "m1c1"}, {"Slot 1", "m1s1"}, {"Slot 2", "m1s2"}, {"Komut 2", "m1c2"}, {"Slot 3", "m1s3"},
        {"Eşya filtresi (boş=hepsi)", "m1f"}, {"Slot 4 (E'den sonra)", "m1s4"}, {"Tekrar (0=sonsuz)", "m1rep"}};
    private static final String[][] TAB2 = new String[][]{
        {"Komut", "m2c"}, {"Slot 1", "m2s1"}, {"Slot 2", "m2s2"}, {"Eşya filtresi (boş=hepsi)", "m2f"},
        {"Tekrar (0=sonsuz)", "m2rep"}};
    private static final String[][] TAB_D = new String[][]{
        {"Adım gecikmesi (tick)", "delay"}, {"Eşya gecikmesi (tick)", "itemDelay"}};
    /** AutoFarmConfig.STEP_TYPES ile ayni sirada. */
    private static final String[] TYPE_LABEL = new String[]{
        "Komut/Mesaj", "Slota tıkla", "Eşya koy", "Eşya at", "Menüyü kapat", "Bekle (sn)", "Hotbar slotu"};
    private static final int PER_PAGE = 4;
    private final AutoFarmClient farm;
    private int tab;
    private int page = 0;
    private final int fieldsY = 44;
    private String[][] rows = TAB1;

    public AutoFarmMacroScreen(AutoFarmClient farm, int tab) {
        super(Text.literal("AutoFarm Makrolar"));
        this.farm = farm;
        this.tab = tab;
    }

    private static int typeIndex(String id) {
        for (int i = 0; i < AutoFarmConfig.STEP_TYPES.length; ++i) {
            if (AutoFarmConfig.STEP_TYPES[i].equals(id)) {
                return i;
            }
        }
        return 0;
    }

    protected void init() {
        AutoFarmConfig cfg = AutoFarmConfig.get();
        int tw = 64;
        int tx = this.width / 2 - (tw * TABS.length + 4 * (TABS.length - 1)) / 2;
        for (int i = 0; i < TABS.length; ++i) {
            final int ti = i;
            this.addDrawableChild(ButtonWidget.builder(Text.literal((i == this.tab ? "> " : "") + TABS[i]), b -> {
                this.tab = ti;
                this.clearAndInit();
            }).dimensions(tx + i * (tw + 4), 14, tw, 20).build());
        }
        if (this.tab == TAB_CUSTOM) {
            this.initCustom(cfg);
            return;
        }
        this.rows = this.tab == 0 ? TAB1 : (this.tab == 1 ? TAB2 : TAB_D);
        int gap = 22;
        int fx = this.width / 2 + 10;
        int y = this.fieldsY;
        for (String[] r : this.rows) {
            this.addField(cfg, r[1], fx, y, 140);
            y += gap;
        }
        y += 6;
        int left = this.width / 2 - 100;
        if (this.tab < 2) {
            final int which = this.tab + 1;
            this.addDrawableChild(ButtonWidget.builder(Text.literal("Başlat: Makro " + which).formatted(Formatting.GREEN), b -> {
                cfg.save();
                this.close();
                this.farm.macro().start(MinecraftClient.getInstance(), which);
            }).dimensions(left, y, 98, 20).build());
            this.addDrawableChild(ButtonWidget.builder(Text.literal("Durdur").formatted(Formatting.RED), b -> this.farm.macro().stop(MinecraftClient.getInstance(), "Makro durduruldu")).dimensions(left + 102, y, 98, 20).build());
            y += gap;
        }
        this.addDrawableChild(ButtonWidget.builder(Text.literal("Geri"), b -> {
            cfg.save();
            MinecraftClient.getInstance().setScreen(new AutoFarmScreen(this.farm));
        }).dimensions(left, y, 200, 20).build());
    }

    private void addField(AutoFarmConfig cfg, String key, int x, int y, int w) {
        TextFieldWidget tf = new TextFieldWidget(this.textRenderer, x, y, w, 18, Text.literal(key));
        tf.setMaxLength(64);
        tf.setText(cfg.mget(key));
        tf.setChangedListener(v -> cfg.mset(key, v));
        this.addDrawableChild(tf);
    }

    // ---------------- Özel makro sekmesi ----------------

    private int pages(AutoFarmConfig cfg) {
        return Math.max(1, (cfg.custom.size() + PER_PAGE - 1) / PER_PAGE);
    }

    private void initCustom(AutoFarmConfig cfg) {
        List<String[]> steps = cfg.custom;
        this.page = Math.max(0, Math.min(this.page, this.pages(cfg) - 1));
        int x0 = this.width / 2 - 153;
        int y = 40;
        for (int r = 0; r < PER_PAGE; ++r) {
            final int i = this.page * PER_PAGE + r;
            if (i >= steps.size()) {
                break;
            }
            final String[] st = steps.get(i);
            final int ti = typeIndex(st[0]);
            this.addDrawableChild(ButtonWidget.builder(Text.literal(TYPE_LABEL[ti]), b -> {
                st[0] = AutoFarmConfig.STEP_TYPES[(typeIndex(st[0]) + 1) % AutoFarmConfig.STEP_TYPES.length];
                if (st[0].equals("CLOSE")) {
                    st[1] = "";
                }
                this.clearAndInit();
            }).dimensions(x0 + 16, y, 100, 20).build());
            TextFieldWidget tf = new TextFieldWidget(this.textRenderer, x0 + 120, y + 1, 118, 18, Text.literal("parametre"));
            tf.setMaxLength(80);
            tf.setText(st[1] == null ? "" : st[1]);
            tf.setChangedListener(v -> st[1] = v);
            tf.setEditable(!st[0].equals("CLOSE"));
            this.addDrawableChild(tf);
            this.addDrawableChild(ButtonWidget.builder(Text.literal("^"), b -> {
                if (i > 0) {
                    steps.add(i - 1, steps.remove(i));
                    if (i % PER_PAGE == 0) {
                        --this.page;
                    }
                }
                this.clearAndInit();
            }).dimensions(x0 + 242, y, 20, 20).build());
            this.addDrawableChild(ButtonWidget.builder(Text.literal("v"), b -> {
                if (i < steps.size() - 1) {
                    steps.add(i + 1, steps.remove(i));
                    if (i % PER_PAGE == PER_PAGE - 1) {
                        ++this.page;
                    }
                }
                this.clearAndInit();
            }).dimensions(x0 + 264, y, 20, 20).build());
            this.addDrawableChild(ButtonWidget.builder(Text.literal("X").formatted(Formatting.RED), b -> {
                steps.remove(i);
                this.clearAndInit();
            }).dimensions(x0 + 286, y, 20, 20).build());
            y += 22;
        }
        int ctrlY = 40 + PER_PAGE * 22 + 2;
        this.addDrawableChild(ButtonWidget.builder(Text.literal("<"), b -> {
            --this.page;
            this.clearAndInit();
        }).dimensions(x0, ctrlY, 24, 20).build()).active = this.page > 0;
        this.addDrawableChild(ButtonWidget.builder(Text.literal(">"), b -> {
            ++this.page;
            this.clearAndInit();
        }).dimensions(x0 + 28, ctrlY, 24, 20).build()).active = this.page < this.pages(cfg) - 1;
        this.addDrawableChild(ButtonWidget.builder(Text.literal("+ Adım ekle"), b -> {
            if (steps.size() < AutoFarmConfig.MAX_STEPS) {
                steps.add(new String[]{"CMD", ""});
                this.page = this.pages(cfg) - 1;
            }
            this.clearAndInit();
        }).dimensions(x0 + 150, ctrlY, 156, 20).build());

        int optY = ctrlY + 26;
        this.addField(cfg, "crep", x0 + 96, optY, 40);
        this.addField(cfg, "cwait", x0 + 222, optY, 48);

        int btnY = optY + 24;
        this.addDrawableChild(ButtonWidget.builder(Text.literal("Başlat").formatted(Formatting.GREEN), b -> {
            cfg.save();
            this.close();
            this.farm.macro().start(MinecraftClient.getInstance(), 3);
        }).dimensions(x0, btnY, 98, 20).build());
        this.addDrawableChild(ButtonWidget.builder(Text.literal("Durdur").formatted(Formatting.RED), b -> this.farm.macro().stop(MinecraftClient.getInstance(), "Makro durduruldu")).dimensions(x0 + 102, btnY, 98, 20).build());
        this.addDrawableChild(ButtonWidget.builder(Text.literal("Geri"), b -> {
            cfg.save();
            MinecraftClient.getInstance().setScreen(new AutoFarmScreen(this.farm));
        }).dimensions(x0 + 204, btnY, 102, 20).build());
    }

    public void render(DrawContext context, int mouseX, int mouseY, float delta) {
        super.render(context, mouseX, mouseY, delta);
        if (this.tab == TAB_CUSTOM) {
            this.renderCustom(context);
            return;
        }
        int y = this.fieldsY + 5;
        for (String[] r : this.rows) {
            context.drawTextWithShadow(this.textRenderer, r[0], this.width / 2 - 150, y, 0xFFFFFF);
            y += 22;
        }
        if (this.tab == TAB_DELAY) {
            context.drawCenteredTextWithShadow(this.textRenderer, "20 tick = 1 saniye", this.width / 2, y + 4, 0xAAAAAA);
        } else {
            context.drawCenteredTextWithShadow(this.textRenderer, "Slot 1 = menünün sol üst kutusu", this.width / 2, this.height - 14, 0xAAAAAA);
        }
    }

    private void renderCustom(DrawContext context) {
        AutoFarmConfig cfg = AutoFarmConfig.get();
        int x0 = this.width / 2 - 153;
        int rowsShown = Math.min(PER_PAGE, Math.max(0, cfg.custom.size() - this.page * PER_PAGE));
        for (int r = 0; r < rowsShown; ++r) {
            int n = this.page * PER_PAGE + r + 1;
            context.drawTextWithShadow(this.textRenderer, n + ".", x0, 40 + r * 22 + 6, 0xFFFFFF);
        }
        if (cfg.custom.isEmpty()) {
            context.drawCenteredTextWithShadow(this.textRenderer, "Henüz adım yok. \"+ Adım ekle\" ile başla.", this.width / 2, 40 + 30, 0xAAAAAA);
        }
        int ctrlY = 40 + PER_PAGE * 22 + 2;
        context.drawTextWithShadow(this.textRenderer, (this.page + 1) + "/" + this.pages(cfg), x0 + 58, ctrlY + 6, 0xFFFFFF);
        int optY = ctrlY + 26;
        context.drawTextWithShadow(this.textRenderer, "Tekrar (0=sonsuz)", x0, optY + 5, 0xFFFFFF);
        context.drawTextWithShadow(this.textRenderer, "Tur arası (sn)", x0 + 142, optY + 5, 0xFFFFFF);
        int hintY = optY + 24 + 24;
        context.drawTextWithShadow(this.textRenderer, "Komut/Mesaj: /komut ya da yazı   Slot, Hotbar: sayı   Bekle: saniye", x0, hintY, 0xAAAAAA);
        context.drawTextWithShadow(this.textRenderer, "Eşya koy/at: filtre, virgülle (boş = hepsi)   H = durdur", x0, hintY + 11, 0xAAAAAA);
    }

    public void removed() {
        AutoFarmConfig.get().save();
    }
}
