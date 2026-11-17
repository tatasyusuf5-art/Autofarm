package com.yusuf.autofarm;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.client.gui.widget.TextFieldWidget;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;

public class AutoFarmMacroScreen
extends Screen {
    private static final String[] TABS = new String[]{"Makro 1", "Makro 2", "Gecikme"};
    private static final String[][] TAB1 = new String[][]{
        {"Komut 1", "m1c1"}, {"Slot 1", "m1s1"}, {"Slot 2", "m1s2"}, {"Komut 2", "m1c2"}, {"Slot 3", "m1s3"},
        {"Eşya filtresi (boş=hepsi)", "m1f"}, {"Slot 4 (E'den sonra)", "m1s4"}, {"Tekrar (0=sonsuz)", "m1rep"}};
    private static final String[][] TAB2 = new String[][]{
        {"Komut", "m2c"}, {"Slot 1", "m2s1"}, {"Slot 2", "m2s2"}, {"Eşya filtresi (boş=hepsi)", "m2f"}};
    private static final String[][] TAB3 = new String[][]{
        {"Adım gecikmesi (tick)", "delay"}, {"Eşya gecikmesi (tick)", "itemDelay"}};
    private final AutoFarmClient farm;
    private int tab;
    private int fieldsY = 44;
    private String[][] rows;

    public AutoFarmMacroScreen(AutoFarmClient farm, int tab) {
        super(Text.literal("AutoFarm Makrolar"));
        this.farm = farm;
        this.tab = tab;
    }

    protected void init() {
        AutoFarmConfig cfg = AutoFarmConfig.get();
        this.rows = this.tab == 0 ? TAB1 : (this.tab == 1 ? TAB2 : TAB3);
        int tw = 70;
        int tx = this.width / 2 - (tw * 3 + 8) / 2;
        for (int i = 0; i < TABS.length; ++i) {
            final int ti = i;
            this.addDrawableChild(ButtonWidget.builder(Text.literal((i == this.tab ? "> " : "") + TABS[i]), b -> {
                this.tab = ti;
                this.clearAndInit();
            }).dimensions(tx + i * (tw + 4), 14, tw, 20).build());
        }
        int gap = 22;
        int fx = this.width / 2 + 10;
        int y = this.fieldsY;
        for (String[] r : this.rows) {
            final String key = r[1];
            TextFieldWidget tf = new TextFieldWidget(this.textRenderer, fx, y, 140, 18, Text.literal(r[0]));
            tf.setMaxLength(64);
            tf.setText(cfg.mget(key));
            tf.setChangedListener(v -> cfg.mset(key, v));
            this.addDrawableChild(tf);
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

    public void render(DrawContext context, int mouseX, int mouseY, float delta) {
        super.render(context, mouseX, mouseY, delta);
        int y = this.fieldsY + 5;
        for (String[] r : this.rows) {
            context.drawTextWithShadow(this.textRenderer, r[0], this.width / 2 - 150, y, 0xFFFFFF);
            y += 22;
        }
        if (this.tab == 2) {
            context.drawCenteredTextWithShadow(this.textRenderer, "20 tick = 1 saniye", this.width / 2, y + 4, 0xAAAAAA);
        } else {
            context.drawCenteredTextWithShadow(this.textRenderer, "Slot 1 = menünün sol üst kutusu", this.width / 2, this.height - 14, 0xAAAAAA);
        }
    }

    public void removed() {
        AutoFarmConfig.get().save();
    }
}
