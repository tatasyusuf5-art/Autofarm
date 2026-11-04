package com.yusuf.autofarm;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.block.Block;
import net.minecraft.block.Blocks;

public class AutoFarmConfig {
   private static final AutoFarmConfig INSTANCE = new AutoFarmConfig();
   public int mode = 0;
   public int speed = 5;
   public boolean requireHoe = false;
   public boolean requireFortune = false;
   public final boolean[] crops = new boolean[]{true, true, true, true, true};

   // Farm alanı: iki köşe. Y = çiftlik toprağının (farmland) katı.
   public boolean useArea = true;
   public boolean hasP1 = false;
   public boolean hasP2 = false;
   public int p1x;
   public int p1y;
   public int p1z;
   public int p2x;
   public int p2y;
   public int p2z;

   public static AutoFarmConfig get() {
      return INSTANCE;
   }

   public boolean isSelected(Block b) {
      AutoFarmConfig.Crop c = AutoFarmConfig.Crop.of(b);
      return c != null && this.crops[c.ordinal()];
   }

   public boolean hasArea() {
      return this.hasP1 && this.hasP2;
   }

   public int minX() {
      return Math.min(this.p1x, this.p2x);
   }

   public int maxX() {
      return Math.max(this.p1x, this.p2x);
   }

   public int minZ() {
      return Math.min(this.p1z, this.p2z);
   }

   public int maxZ() {
      return Math.max(this.p1z, this.p2z);
   }

   /** Çiftlik toprağının Y katı (1. köşenin katı). */
   public int farmY() {
      return this.p1y;
   }

   public boolean contains(int x, int z) {
      return x >= this.minX() && x <= this.maxX() && z >= this.minZ() && z <= this.maxZ();
   }

   public void clearArea() {
      this.hasP1 = false;
      this.hasP2 = false;
   }

   private Path file() {
      return FabricLoader.getInstance().getConfigDir().resolve("autofarm.properties");
   }

   public void load() {
      Path f = this.file();
      if (Files.exists(f)) {
         try (InputStream in = Files.newInputStream(f)) {
            Properties p = new Properties();
            p.load(in);
            this.mode = clamp(parse(p.getProperty("mode"), this.mode), 0, 3);
            this.speed = clamp(parse(p.getProperty("speed"), this.speed), 1, 15);
            this.requireHoe = Boolean.parseBoolean(p.getProperty("requireHoe", String.valueOf(this.requireHoe)));
            this.requireFortune = Boolean.parseBoolean(p.getProperty("requireFortune", String.valueOf(this.requireFortune)));

            for (AutoFarmConfig.Crop c : AutoFarmConfig.Crop.values()) {
               this.crops[c.ordinal()] = Boolean.parseBoolean(p.getProperty("crop." + c.name(), String.valueOf(this.crops[c.ordinal()])));
            }

            this.useArea = Boolean.parseBoolean(p.getProperty("useArea", String.valueOf(this.useArea)));
            this.hasP1 = Boolean.parseBoolean(p.getProperty("hasP1", "false"));
            this.hasP2 = Boolean.parseBoolean(p.getProperty("hasP2", "false"));
            this.p1x = parse(p.getProperty("p1x"), 0);
            this.p1y = parse(p.getProperty("p1y"), 0);
            this.p1z = parse(p.getProperty("p1z"), 0);
            this.p2x = parse(p.getProperty("p2x"), 0);
            this.p2y = parse(p.getProperty("p2y"), 0);
            this.p2z = parse(p.getProperty("p2z"), 0);
         } catch (IOException var10) {
         }
      }
   }

   public void save() {
      Properties p = new Properties();
      p.setProperty("mode", String.valueOf(this.mode));
      p.setProperty("speed", String.valueOf(this.speed));
      p.setProperty("requireHoe", String.valueOf(this.requireHoe));
      p.setProperty("requireFortune", String.valueOf(this.requireFortune));

      for (AutoFarmConfig.Crop c : AutoFarmConfig.Crop.values()) {
         p.setProperty("crop." + c.name(), String.valueOf(this.crops[c.ordinal()]));
      }

      p.setProperty("useArea", String.valueOf(this.useArea));
      p.setProperty("hasP1", String.valueOf(this.hasP1));
      p.setProperty("hasP2", String.valueOf(this.hasP2));
      p.setProperty("p1x", String.valueOf(this.p1x));
      p.setProperty("p1y", String.valueOf(this.p1y));
      p.setProperty("p1z", String.valueOf(this.p1z));
      p.setProperty("p2x", String.valueOf(this.p2x));
      p.setProperty("p2y", String.valueOf(this.p2y));
      p.setProperty("p2z", String.valueOf(this.p2z));

      try (OutputStream out = Files.newOutputStream(this.file())) {
         p.store(out, "AutoFarm ayarlari");
      } catch (IOException var8) {
      }
   }

   private static int parse(String s, int def) {
      try {
         return s == null ? def : Integer.parseInt(s.trim());
      } catch (NumberFormatException var3) {
         return def;
      }
   }

   private static int clamp(int v, int lo, int hi) {
      return Math.max(lo, Math.min(hi, v));
   }

   public static enum Crop {
      WHEAT(Blocks.WHEAT, "Buğday"),
      CARROT(Blocks.CARROTS, "Havuç"),
      POTATO(Blocks.POTATOES, "Patates"),
      BEETROOT(Blocks.BEETROOTS, "Pancar"),
      NETHER_WART(Blocks.NETHER_WART, "Nether Siğili");

      public final Block block;
      public final String label;

      private Crop(Block block, String label) {
         this.block = block;
         this.label = label;
      }

      public static AutoFarmConfig.Crop of(Block b) {
         for (AutoFarmConfig.Crop c : values()) {
            if (c.block == b) {
               return c;
            }
         }

         return null;
      }
   }
}
