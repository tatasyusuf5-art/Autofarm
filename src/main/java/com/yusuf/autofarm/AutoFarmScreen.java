package com.yusuf.autofarm;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.client.gui.widget.SliderWidget;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;

public class AutoFarmScreen extends Screen {
    private static final String[] MODE_NAMES = {
            "Mod 1: Kır", "Mod 2: Ek", "Mod 3: Kır + Ek"
    };

    private final AutoFarmClient farm;
    private int titleY = 10;

    public AutoFarmScreen(AutoFarmClient client) {
        super(Text.literal("AutoFarm Ayarları"));
        this.farm = client;
    }

    @Override
    protected void init() {
        AutoFarmConfig cfg = AutoFarmConfig.get();
        int gap = 22;
        int bw = 200;
        int half = 98;
        int left = this.width / 2 - bw / 2;
        int right = left + bw - half;
        int rows = 7;
        int y = Math.max(24, this.height / 2 - (rows * gap) / 2);
        titleY = y - 14;

        // Mod seçimi
        addDrawableChild(ButtonWidget.builder(Text.literal(MODE_NAMES[cfg.mode]), b -> {
            cfg.mode = (cfg.mode + 1) % 3;
            b.setMessage(Text.literal(MODE_NAMES[cfg.mode]));
        }).dimensions(left, y, bw, 20).build());
        y += gap;

        // Hız
        addDrawableChild(new SpeedSlider(left, y, bw, 20));
        y += gap;

        // Zorunluluklar
        addDrawableChild(ButtonWidget.builder(toggleText("Çapa", cfg.requireHoe), b -> {
            cfg.requireHoe = !cfg.requireHoe;
            b.setMessage(toggleText("Çapa", cfg.requireHoe));
        }).dimensions(left, y, half, 20).build());
        addDrawableChild(ButtonWidget.builder(toggleText("Servet III", cfg.requireFortune), b -> {
            cfg.requireFortune = !cfg.requireFortune;
            b.setMessage(toggleText("Servet III", cfg.requireFortune));
        }).dimensions(right, y, half, 20).build());
        y += gap;

        // Ekin seçimi
        AutoFarmConfig.Crop[] crops = AutoFarmConfig.Crop.values();
        for (int i = 0; i < crops.length; i++) {
            final AutoFarmConfig.Crop crop = crops[i];
            int x = (i % 2 == 0) ? left : right;
            int yy = y + (i / 2) * gap;
            addDrawableChild(ButtonWidget.builder(toggleText(crop.label, cfg.crops[i]), b -> {
                cfg.crops[crop.ordinal()] = !cfg.crops[crop.ordinal()];
                b.setMessage(toggleText(crop.label, cfg.crops[crop.ordinal()]));
            }).dimensions(x, yy, half, 20).build());
        }
        y += 3 * gap;

        // Başlat/Durdur + Kapat
        addDrawableChild(ButtonWidget.builder(startText(), b -> {
            farm.toggle(MinecraftClient.getInstance());
            b.setMessage(startText());
        }).dimensions(left, y, half, 20).build());
        addDrawableChild(ButtonWidget.builder(Text.literal("Kapat"), b -> this.close())
                .dimensions(right, y, half, 20).build());
    }

    private Text startText() {
        return farm.isEnabled()
                ? Text.literal("Durdur").formatted(Formatting.RED)
                : Text.literal("Başlat").formatted(Formatting.GREEN);
    }

    private static Text toggleText(String label, boolean on) {
        return Text.literal(label + ": ")
                .append(Text.literal(on ? "AÇIK" : "KAPALI").formatted(on ? Formatting.GREEN : Formatting.RED));
    }

    @Override
    public void render(DrawContext context, int mouseX, int mouseY, float delta) {
        super.render(context, mouseX, mouseY, delta);
        context.drawCenteredTextWithShadow(this.textRenderer, this.title, this.width / 2, titleY, 0xFFFFFF);
    }

    @Override
    public void removed() {
        AutoFarmConfig.get().save();
    }

    private static class SpeedSlider extends SliderWidget {
        SpeedSlider(int x, int y, int w, int h) {
            super(x, y, w, h, Text.empty(), (AutoFarmConfig.get().speed - 1) / 9.0);
            updateMessage();
        }

        private int speedValue() {
            return 1 + (int) Math.round(this.value * 9);
        }

        @Override
        protected void updateMessage() {
            setMessage(Text.literal("Hız: " + speedValue() + " / 10"));
        }

        @Override
        protected void applyValue() {
            AutoFarmConfig.get().speed = speedValue();
        }
    }
}
