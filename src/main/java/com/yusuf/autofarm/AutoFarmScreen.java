package com.yusuf.autofarm;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.client.gui.widget.SliderWidget;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;

public class AutoFarmScreen extends Screen {
   private static final String[] MODE_NAMES = new String[]{"Mod 1: Kır", "Mod 2: Ek", "Mod 3: Kır + Ek", "Mod 4: Kemik tozu"};
   private final AutoFarmClient farm;
   private int titleY = 10;

   public AutoFarmScreen(AutoFarmClient client) {
      super(Text.literal("AutoFarm Ayarları"));
      this.farm = client;
   }

   protected void init() {
      AutoFarmConfig cfg = AutoFarmConfig.get();
      int rows = 9;
      int gap = Math.max(20, Math.min(22, (this.height - 36) / rows));
      int bw = 200;
      int half = 98;
      int left = this.width / 2 - bw / 2;
      int right = left + bw - half;
      int y = Math.max(24, this.height / 2 - rows * gap / 2);
      this.titleY = y - 14;
      this.addDrawableChild(ButtonWidget.builder(Text.literal(MODE_NAMES[cfg.mode]), b -> {
         cfg.mode = (cfg.mode + 1) % MODE_NAMES.length;
         b.setMessage(Text.literal(MODE_NAMES[cfg.mode]));
      }).dimensions(left, y, bw, 20).build());
      y += gap;
      this.addDrawableChild(new AutoFarmScreen.SpeedSlider(left, y, bw, 20));
      y += gap;
      this.addDrawableChild(ButtonWidget.builder(toggleText("Çapa", cfg.requireHoe), b -> {
         cfg.requireHoe = !cfg.requireHoe;
         b.setMessage(toggleText("Çapa", cfg.requireHoe));
      }).dimensions(left, y, half, 20).build());
      this.addDrawableChild(ButtonWidget.builder(toggleText("Servet III", cfg.requireFortune), b -> {
         cfg.requireFortune = !cfg.requireFortune;
         b.setMessage(toggleText("Servet III", cfg.requireFortune));
      }).dimensions(right, y, half, 20).build());
      y += gap;
      AutoFarmConfig.Crop[] crops = AutoFarmConfig.Crop.values();

      for (int i = 0; i < crops.length; i++) {
         AutoFarmConfig.Crop crop = crops[i];
         int x = i % 2 == 0 ? left : right;
         int yy = y + i / 2 * gap;
         this.addDrawableChild(ButtonWidget.builder(toggleText(crop.label, cfg.crops[i]), b -> {
            cfg.crops[crop.ordinal()] = !cfg.crops[crop.ordinal()];
            b.setMessage(toggleText(crop.label, cfg.crops[crop.ordinal()]));
         }).dimensions(x, yy, half, 20).build());
      }

      y += 3 * gap;

      // Farm alanı: baktığın bloğu köşe olarak kaydeder
      this.addDrawableChild(ButtonWidget.builder(cornerText(1), b -> {
         this.farm.setCorner(MinecraftClient.getInstance(), 1);
         b.setMessage(cornerText(1));
      }).dimensions(left, y, half, 20).build());
      this.addDrawableChild(ButtonWidget.builder(cornerText(2), b -> {
         this.farm.setCorner(MinecraftClient.getInstance(), 2);
         b.setMessage(cornerText(2));
      }).dimensions(right, y, half, 20).build());
      y += gap;
      this.addDrawableChild(ButtonWidget.builder(toggleText("Alan", cfg.useArea), b -> {
         cfg.useArea = !cfg.useArea;
         b.setMessage(toggleText("Alan", cfg.useArea));
      }).dimensions(left, y, half, 20).build());
      this.addDrawableChild(ButtonWidget.builder(Text.literal("Alanı Sil"), b -> {
         this.farm.clearArea();
         this.clearAndInit();
      }).dimensions(right, y, half, 20).build());
      y += gap;

      this.addDrawableChild(ButtonWidget.builder(this.startText(), b -> {
         this.farm.toggle(MinecraftClient.getInstance());
         b.setMessage(this.startText());
      }).dimensions(left, y, half, 20).build());
      this.addDrawableChild(ButtonWidget.builder(Text.literal("Kapat"), b -> this.close()).dimensions(right, y, half, 20).build());
   }

   private static Text cornerText(int idx) {
      AutoFarmConfig cfg = AutoFarmConfig.get();
      boolean set = idx == 1 ? cfg.hasP1 : cfg.hasP2;
      return Text.literal("Köşe " + idx + ": ")
         .append(Text.literal(set ? "✓ yenile" : "seç").formatted(set ? Formatting.GREEN : Formatting.YELLOW));
   }

   private Text startText() {
      return this.farm.isEnabled()
         ? Text.literal("Durdur").formatted(Formatting.RED)
         : Text.literal("Başlat").formatted(Formatting.GREEN);
   }

   private static Text toggleText(String label, boolean on) {
      return Text.literal(label + ": ")
         .append(Text.literal(on ? "AÇIK" : "KAPALI").formatted(on ? Formatting.GREEN : Formatting.RED));
   }

   public void render(DrawContext context, int mouseX, int mouseY, float delta) {
      super.render(context, mouseX, mouseY, delta);
      context.drawCenteredTextWithShadow(this.textRenderer, this.title, this.width / 2, this.titleY, 16777215);
   }

   public void removed() {
      AutoFarmConfig.get().save();
   }

   private static class SpeedSlider extends SliderWidget {
      SpeedSlider(int x, int y, int w, int h) {
         super(x, y, w, h, Text.empty(), (AutoFarmConfig.get().speed - 1) / 14.0);
         this.updateMessage();
      }

      private int speedValue() {
         return 1 + (int)Math.round(this.value * 14.0);
      }

      protected void updateMessage() {
         this.setMessage(Text.literal("Hız: " + this.speedValue() + " / 15"));
      }

      protected void applyValue() {
         AutoFarmConfig.get().speed = this.speedValue();
      }
   }
}
