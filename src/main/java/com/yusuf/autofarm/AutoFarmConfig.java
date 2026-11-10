package com.yusuf.autofarm;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.OpenOption;
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

    public static AutoFarmConfig get() {
        return INSTANCE;
    }

    public boolean isSelected(Block b) {
        Crop c = Crop.of(b);
        return c != null && this.crops[c.ordinal()];
    }

    private Path file() {
        return FabricLoader.getInstance().getConfigDir().resolve("autofarm.properties");
    }

    public void load() {
        Path f = this.file();
        if (Files.exists(f, new LinkOption[0])) {
            try (InputStream in = Files.newInputStream(f, new OpenOption[0]);){
                Properties p = new Properties();
                p.load(in);
                this.mode = AutoFarmConfig.clamp(AutoFarmConfig.parse(p.getProperty("mode"), this.mode), 0, 3);
                this.speed = AutoFarmConfig.clamp(AutoFarmConfig.parse(p.getProperty("speed"), this.speed), 1, 15);
                this.requireHoe = Boolean.parseBoolean(p.getProperty("requireHoe", String.valueOf(this.requireHoe)));
                this.requireFortune = Boolean.parseBoolean(p.getProperty("requireFortune", String.valueOf(this.requireFortune)));
                for (Crop c : Crop.values()) {
                    this.crops[c.ordinal()] = Boolean.parseBoolean(p.getProperty("crop." + c.name(), String.valueOf(this.crops[c.ordinal()])));
                }
            }
            catch (IOException iOException) {
                // empty catch block
            }
        }
    }

    public void save() {
        Properties p = new Properties();
        p.setProperty("mode", String.valueOf(this.mode));
        p.setProperty("speed", String.valueOf(this.speed));
        p.setProperty("requireHoe", String.valueOf(this.requireHoe));
        p.setProperty("requireFortune", String.valueOf(this.requireFortune));
        for (Crop c : Crop.values()) {
            p.setProperty("crop." + c.name(), String.valueOf(this.crops[c.ordinal()]));
        }
        try (OutputStream out = Files.newOutputStream(this.file(), new OpenOption[0]);){
            p.store(out, "AutoFarm ayarlari");
        }
        catch (IOException iOException) {
            // empty catch block
        }
    }

    private static int parse(String s, int def) {
        try {
            return s == null ? def : Integer.parseInt(s.trim());
        }
        catch (NumberFormatException var3) {
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

        public static Crop of(Block b) {
            for (Crop c : Crop.values()) {
                if (c.block != b) continue;
                return c;
            }
            return null;
        }
    }
}

