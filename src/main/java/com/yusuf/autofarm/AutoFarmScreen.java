package com.yusuf.autofarm;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.client.gui.widget.SliderWidget;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;

public class AutoFarmScreen
extends Screen {
    private static final String[] MODE_NAMES = new String[]{"Mod 1: Kır", "Mod 2: Ek", "Mod 3: Kır + Ek", "Mod 4: Kemik tozu"};
    private final AutoFarmClient farm;
    private int titleY = 10;

    public AutoFarmScreen(AutoFarmClient client) {
        super(Text.literal("AutoFarm Ayarları"));
        this.farm = client;
    }

    protected void init() {
        AutoFarmConfig cfg = AutoFarmConfig.get();
        int gap = 22;
        int bw = 200;
        int half = 98;
        int left = this.width / 2 - bw / 2;
        int right = left + bw - half;
        int rows = 8;
        int y = Math.max(24, this.height / 2 - rows * gap / 2);
        this.titleY = y - 14;
        this.addDrawableChild(ButtonWidget.builder(Text.literal(MODE_NAMES[cfg.mode]), b -> {
            cfg.mode = (cfg.mode + 1) % MODE_NAMES.length;
            b.setMessage(Text.literal(MODE_NAMES[cfg.mode]));
        }).dimensions(left, y, bw, 20).build());
        this.addDrawableChild(new SpeedSlider(left, y += gap, bw, 20));
        this.addDrawableChild(ButtonWidget.builder(AutoFarmScreen.toggleText("Çapa", cfg.requireHoe), b -> {
            cfg.requireHoe = !cfg.requireHoe;
            b.setMessage(AutoFarmScreen.toggleText("Çapa", cfg.requireHoe));
        }).dimensions(left, y += gap, half, 20).build());
        this.addDrawableChild(ButtonWidget.builder(AutoFarmScreen.toggleText("Servet III", cfg.requireFortune), b -> {
            cfg.requireFortune = !cfg.requireFortune;
            b.setMessage(AutoFarmScreen.toggleText("Servet III", cfg.requireFortune));
        }).dimensions(right, y, half, 20).build());
        y += gap;
        AutoFarmConfig.Crop[] crops = AutoFarmConfig.Crop.values();
        for (int i = 0; i < crops.length; ++i) {
            AutoFarmConfig.Crop crop = crops[i];
            int x = i % 2 == 0 ? left : right;
            int yy = y + i / 2 * gap;
            this.addDrawableChild(ButtonWidget.builder(AutoFarmScreen.toggleText(crop.label, cfg.crops[i]), b -> {
                cfg.crops[crop.ordinal()] = !cfg.crops[crop.ordinal()];
                b.setMessage(AutoFarmScreen.toggleText(crop.label, cfg.crops[crop.ordinal()]));
            }).dimensions(x, yy, half, 20).build());
        }
        this.addDrawableChild(ButtonWidget.builder(this.startText(), b -> {
            this.farm.toggle(MinecraftClient.getInstance());
            b.setMessage(this.startText());
        }).dimensions(left, y += 3 * gap, half, 20).build());
        this.addDrawableChild(ButtonWidget.builder(Text.literal("Kapat"), b -> this.close()).dimensions(right, y, half, 20).build());
        this.addDrawableChild(ButtonWidget.builder(Text.literal("Makrolar..."), b -> MinecraftClient.getInstance().setScreen(new AutoFarmMacroScreen(this.farm, 0))).dimensions(left, y + gap, bw, 20).build());
    }

    private Text startText() {
        return this.farm.isEnabled() ? Text.literal("Durdur").formatted(Formatting.RED) : Text.literal("Başlat").formatted(Formatting.GREEN);
    }

    private static Text toggleText(String label, boolean on) {
        return Text.literal((label + ": ")).append(Text.literal((on ? "AÇIK" : "KAPALI")).formatted(on ? Formatting.GREEN : Formatting.RED));
    }

    public void render(DrawContext context, int mouseX, int mouseY, float delta) {
        super.render(context, mouseX, mouseY, delta);
        context.drawCenteredTextWithShadow(this.textRenderer, this.title, this.width / 2, this.titleY, 0xFFFFFF);
    }

    public void removed() {
        AutoFarmConfig.get().save();
    }

    private static class SpeedSlider
    extends SliderWidget {
        SpeedSlider(int x, int y, int w, int h) {
            super(x, y, w, h, Text.empty(), (double)(AutoFarmConfig.get().speed - 1) / 14.0);
            this.updateMessage();
        }

        private int speedValue() {
            return 1 + (int)Math.round(this.value * 14.0);
        }

        protected void updateMessage() {
            this.setMessage(Text.literal(("Hız: " + this.speedValue() + " / 15")));
        }

        protected void applyValue() {
            AutoFarmConfig.get().speed = this.speedValue();
        }
    }
}

