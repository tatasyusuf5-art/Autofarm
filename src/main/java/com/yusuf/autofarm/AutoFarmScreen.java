package com.yusuf.autofarm;

import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.client.gui.widget.SliderWidget;
import net.minecraft.client.gui.widget.TextFieldWidget;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;

public class AutoFarmScreen extends Screen {
    private static final String[] MODE_NAMES = {
            "Mod 1: Kır", "Mod 2: Ek", "Mod 3: Kır + Ek", "Mod 4: Kır + Ek + Ek"
    };

    private final AutoFarmClient farm;
    private int titleY = 10;
    private TextFieldWidget floorsField;

    public AutoFarmScreen(AutoFarmClient client) {
        super(Text.literal("AutoFarm Ayarları"));
        this.farm = client;
    }

    @Override
    protected void init() {
        AutoFarmConfig cfg = AutoFarmConfig.get();
        int gap = 22;
        int bw = 220;
        int half = 108;
        int left = this.width / 2 - bw / 2;
        int right = left + bw - half;
        int rows = 11;
        int y = Math.max(20, this.height / 2 - (rows * gap) / 2);
        titleY = y - 14;

        // Mod seçimi
        addDrawableChild(ButtonWidget.builder(Text.literal(MODE_NAMES[cfg.mode]), b -> {
            cfg.mode = (cfg.mode + 1) % MODE_NAMES.length;
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

        // Godmode
        addDrawableChild(ButtonWidget.builder(godText(cfg.godMode), b -> {
            cfg.godMode = !cfg.godMode;
            b.setMessage(godText(cfg.godMode));
        }).dimensions(left, y, bw, 20).build());
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

        // Tarla: köşe kaydet butonları + kat sayısı
        addDrawableChild(ButtonWidget.builder(Text.literal("1. Köşeyi Kaydet"), b -> {
            farm.saveFieldCorner(this.client, 0);
            b.setMessage(Text.literal("1. Köşe ✓"));
        }).dimensions(left, y, half, 20).build());
        addDrawableChild(ButtonWidget.builder(Text.literal("2. Köşeyi Kaydet"), b -> {
            farm.saveFieldCorner(this.client, 1);
            b.setMessage(Text.literal("2. Köşe ✓"));
        }).dimensions(right, y, half, 20).build());
        y += gap;

        floorsField = new TextFieldWidget(this.textRenderer, left, y, 60, 20, Text.literal("Kat"));
        floorsField.setText(String.valueOf(cfg.floors));
        floorsField.setChangedListener(txt -> {
            try {
                int v = Integer.parseInt(txt.trim());
                cfg.floors = Math.max(1, Math.min(64, v));
            } catch (NumberFormatException ignored) {
            }
        });
        addDrawableChild(floorsField);
        addDrawableChild(ButtonWidget.builder(Text.literal("Tarlayı Temizle"), b -> {
            cfg.fieldSet = false;
            cfg.save();
        }).dimensions(left + 68, y, bw - 68, 20).build());
        y += gap;

        // Tuş atamaları
        addDrawableChild(ButtonWidget.builder(Text.literal("Tuş Atamaları"), b ->
                this.client.setScreen(new AutoFarmKeybindScreen(this)))
                .dimensions(left, y, bw, 20).build());
        y += gap;

        // Başlat/Durdur + Kapat
        addDrawableChild(ButtonWidget.builder(startText(), b -> {
            farm.toggle(this.client);
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

    private static Text godText(boolean on) {
        return Text.literal("Godmode: ")
                .append(Text.literal(on ? "AÇIK" : "KAPALI").formatted(on ? Formatting.GOLD : Formatting.RED));
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
            super(x, y, w, h, Text.empty(), (AutoFarmConfig.get().speed - 1) / 14.0);
            updateMessage();
        }

        private int speedValue() {
            return 1 + (int) Math.round(this.value * 14);
        }

        @Override
        protected void updateMessage() {
            setMessage(Text.literal("Hız: " + speedValue() + " / 15"));
        }

        @Override
        protected void applyValue() {
            AutoFarmConfig.get().speed = speedValue();
        }
    }
}
