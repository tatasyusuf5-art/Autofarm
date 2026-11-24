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
    public int mode = 0;
    public int speed = 5;
    public boolean requireHoe = false;
    public boolean requireFortune = false;
    public final boolean[] crops = new boolean[]{true, true, true, true, true};
    private static final String[][] MACRO_DEFAULTS = new String[][]{
        {"m1c1", "/çiftçi"}, {"m1s1", "5"}, {"m1s2", "2"}, {"m1c2", "/order"}, {"m1s3", "1"},
        {"m1f", ""}, {"m1s4", "1"}, {"m1rep", "0"},
        {"m2c", "/çiftçi"}, {"m2s1", "5"}, {"m2s2", "2"}, {"m2f", ""}, {"m2rep", "0"},
        {"crep", "0"}, {"cwait", "0"},
        {"delay", "10"}, {"itemDelay", "2"}};
    /** Özel makro adım türleri (sırası ekrandaki düğmenin dönüş sırasıdır). */
    public static final String[] STEP_TYPES = new String[]{"CMD", "SLOT", "DEPOSIT", "DROP", "CLOSE", "WAIT", "HOTBAR"};
    public static final int MAX_STEPS = 60;
    /** Her adım: {tür, parametre}. */
    public final java.util.List<String[]> custom = new java.util.ArrayList<String[]>();
    private static final AutoFarmConfig INSTANCE = new AutoFarmConfig();
    private final java.util.Map<String, String> macro = new java.util.HashMap<String, String>();

    public AutoFarmConfig() {
        for (String[] d : MACRO_DEFAULTS) {
            this.macro.put(d[0], d[1]);
        }
    }

    public String mget(String k) {
        String v = this.macro.get(k);
        return v == null ? "" : v;
    }

    public void mset(String k, String v) {
        this.macro.put(k, v);
    }

    public int mint(String k, int min, int max) {
        int def = 0;
        for (String[] d : MACRO_DEFAULTS) {
            if (d[0].equals(k)) {
                def = parse(d[1], 0);
            }
        }
        return clamp(parse(this.macro.get(k), def), min, max);
    }

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
                for (String[] d : MACRO_DEFAULTS) {
                    this.macro.put(d[0], p.getProperty("macro." + d[0], this.macro.get(d[0])));
                }
                this.custom.clear();
                int n = AutoFarmConfig.clamp(AutoFarmConfig.parse(p.getProperty("custom.count"), 0), 0, MAX_STEPS);
                for (int i = 0; i < n; ++i) {
                    String type = p.getProperty("custom." + i + ".type", "CMD");
                    boolean known = false;
                    for (String t : STEP_TYPES) {
                        known |= t.equals(type);
                    }
                    this.custom.add(new String[]{known ? type : "CMD", p.getProperty("custom." + i + ".arg", "")});
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
        for (java.util.Map.Entry<String, String> e : this.macro.entrySet()) {
            p.setProperty("macro." + e.getKey(), e.getValue());
        }
        p.setProperty("custom.count", String.valueOf(this.custom.size()));
        for (int i = 0; i < this.custom.size(); ++i) {
            p.setProperty("custom." + i + ".type", this.custom.get(i)[0]);
            p.setProperty("custom." + i + ".arg", this.custom.get(i)[1] == null ? "" : this.custom.get(i)[1]);
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
