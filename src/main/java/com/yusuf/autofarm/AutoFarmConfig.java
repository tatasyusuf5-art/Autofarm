package com.yusuf.autofarm;

import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.block.Block;
import net.minecraft.block.Blocks;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;

public class AutoFarmConfig {
    public enum Crop {
        WHEAT(Blocks.WHEAT, "Buğday"),
        CARROT(Blocks.CARROTS, "Havuç"),
        POTATO(Blocks.POTATOES, "Patates"),
        BEETROOT(Blocks.BEETROOTS, "Pancar"),
        NETHER_WART(Blocks.NETHER_WART, "Nether Siğili");

        public final Block block;
        public final String label;

        Crop(Block block, String label) {
            this.block = block;
            this.label = label;
        }

        public static Crop of(Block b) {
            for (Crop c : values()) {
                if (c.block == b) return c;
            }
            return null;
        }
    }

    private static final AutoFarmConfig INSTANCE = new AutoFarmConfig();

    public static AutoFarmConfig get() {
        return INSTANCE;
    }

    public int mode = 0;               // 0: kır, 1: ek, 2: kır+ek
    public int speed = 5;              // 1..10
    public boolean requireHoe = false;
    public boolean requireFortune = false;   // Servet III
    public final boolean[] crops = {true, true, true, true, true};

    public boolean isSelected(Block b) {
        Crop c = Crop.of(b);
        return c != null && crops[c.ordinal()];
    }

    private Path file() {
        return FabricLoader.getInstance().getConfigDir().resolve("autofarm.properties");
    }

    public void load() {
        Path f = file();
        if (!Files.exists(f)) return;
        try (InputStream in = Files.newInputStream(f)) {
            Properties p = new Properties();
            p.load(in);
            mode = clamp(parse(p.getProperty("mode"), mode), 0, 2);
            speed = clamp(parse(p.getProperty("speed"), speed), 1, 10);
            requireHoe = Boolean.parseBoolean(p.getProperty("requireHoe", String.valueOf(requireHoe)));
            requireFortune = Boolean.parseBoolean(p.getProperty("requireFortune", String.valueOf(requireFortune)));
            for (Crop c : Crop.values()) {
                crops[c.ordinal()] = Boolean.parseBoolean(
                        p.getProperty("crop." + c.name(), String.valueOf(crops[c.ordinal()])));
            }
        } catch (IOException ignored) {
        }
    }

    public void save() {
        Properties p = new Properties();
        p.setProperty("mode", String.valueOf(mode));
        p.setProperty("speed", String.valueOf(speed));
        p.setProperty("requireHoe", String.valueOf(requireHoe));
        p.setProperty("requireFortune", String.valueOf(requireFortune));
        for (Crop c : Crop.values()) {
            p.setProperty("crop." + c.name(), String.valueOf(crops[c.ordinal()]));
        }
        try (OutputStream out = Files.newOutputStream(file())) {
            p.store(out, "AutoFarm ayarlari");
        } catch (IOException ignored) {
        }
    }

    private static int parse(String s, int def) {
        try {
            return s == null ? def : Integer.parseInt(s.trim());
        } catch (NumberFormatException e) {
            return def;
        }
    }

    private static int clamp(int v, int lo, int hi) {
        return Math.max(lo, Math.min(hi, v));
    }
}
