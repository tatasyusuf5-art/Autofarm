package com.yusuf.autofarm;

import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.block.Block;
import net.minecraft.block.Blocks;
import net.minecraft.util.math.BlockPos;

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

    // 0: kır, 1: ek, 2: kır+ek (kırdığını eker), 3: kır+ek+ek (kırdığını eker + boş tarlayı da eker)
    public int mode = 0;
    public int speed = 5;              // 1..15
    public boolean requireHoe = false;
    public boolean requireFortune = false;   // Servet III
    public boolean godMode = false;          // reach/engel sınırlarını en üst legit seviyeye çıkarır
    public final boolean[] crops = {true, true, true, true, true};

    // Tuş atamaları (GLFW key kodları)
    public int keyToggle = org.lwjgl.glfw.GLFW.GLFW_KEY_G;
    public int keyMenu = org.lwjgl.glfw.GLFW.GLFW_KEY_R;

    // Tarla sınırı (kaydedilen iki köşe, dünya koordinatı)
    public boolean fieldSet = false;
    public int fx1, fy1, fz1, fx2, fy2, fz2;
    public int floors = 1;             // kaç kat yukarı tekrarlansın

    public boolean isSelected(Block b) {
        Crop c = Crop.of(b);
        return c != null && crops[c.ordinal()];
    }

    public void setFieldCorner(int idx, BlockPos pos) {
        if (idx == 0) {
            fx1 = pos.getX();
            fy1 = pos.getY();
            fz1 = pos.getZ();
        } else {
            fx2 = pos.getX();
            fy2 = pos.getY();
            fz2 = pos.getZ();
        }
    }

    public boolean insideField(BlockPos pos, int floorIndex) {
        if (!fieldSet) return true;
        int minX = Math.min(fx1, fx2), maxX = Math.max(fx1, fx2);
        int minZ = Math.min(fz1, fz2), maxZ = Math.max(fz1, fz2);
        int baseY = Math.min(fy1, fy2);
        int y = baseY + floorIndex;
        return pos.getX() >= minX && pos.getX() <= maxX
                && pos.getZ() >= minZ && pos.getZ() <= maxZ
                && pos.getY() == y;
    }

    public int baseFloorY() {
        return Math.min(fy1, fy2);
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
            mode = clamp(parse(p.getProperty("mode"), mode), 0, 3);
            speed = clamp(parse(p.getProperty("speed"), speed), 1, 15);
            requireHoe = Boolean.parseBoolean(p.getProperty("requireHoe", String.valueOf(requireHoe)));
            requireFortune = Boolean.parseBoolean(p.getProperty("requireFortune", String.valueOf(requireFortune)));
            godMode = Boolean.parseBoolean(p.getProperty("godMode", String.valueOf(godMode)));
            for (Crop c : Crop.values()) {
                crops[c.ordinal()] = Boolean.parseBoolean(
                        p.getProperty("crop." + c.name(), String.valueOf(crops[c.ordinal()])));
            }
            keyToggle = parse(p.getProperty("keyToggle"), keyToggle);
            keyMenu = parse(p.getProperty("keyMenu"), keyMenu);

            fieldSet = Boolean.parseBoolean(p.getProperty("fieldSet", "false"));
            fx1 = parse(p.getProperty("fx1"), 0);
            fy1 = parse(p.getProperty("fy1"), 0);
            fz1 = parse(p.getProperty("fz1"), 0);
            fx2 = parse(p.getProperty("fx2"), 0);
            fy2 = parse(p.getProperty("fy2"), 0);
            fz2 = parse(p.getProperty("fz2"), 0);
            floors = clamp(parse(p.getProperty("floors"), 1), 1, 64);
        } catch (IOException ignored) {
        }
    }

    public void save() {
        Properties p = new Properties();
        p.setProperty("mode", String.valueOf(mode));
        p.setProperty("speed", String.valueOf(speed));
        p.setProperty("requireHoe", String.valueOf(requireHoe));
        p.setProperty("requireFortune", String.valueOf(requireFortune));
        p.setProperty("godMode", String.valueOf(godMode));
        for (Crop c : Crop.values()) {
            p.setProperty("crop." + c.name(), String.valueOf(crops[c.ordinal()]));
        }
        p.setProperty("keyToggle", String.valueOf(keyToggle));
        p.setProperty("keyMenu", String.valueOf(keyMenu));

        p.setProperty("fieldSet", String.valueOf(fieldSet));
        p.setProperty("fx1", String.valueOf(fx1));
        p.setProperty("fy1", String.valueOf(fy1));
        p.setProperty("fz1", String.valueOf(fz1));
        p.setProperty("fx2", String.valueOf(fx2));
        p.setProperty("fy2", String.valueOf(fy2));
        p.setProperty("fz2", String.valueOf(fz2));
        p.setProperty("floors", String.valueOf(floors));
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
