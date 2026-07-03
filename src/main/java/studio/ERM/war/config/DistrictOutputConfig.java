package studio.ERM.war.config;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import studio.ERM.EpochRunnerMod;

import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.Reader;
import java.io.Writer;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

/**
 * DISTRICT OUTPUT TABLES (config/Homosapien/district_outputs.json)
 *
 * The district-centric economy: every district kind has a rate coefficient and a WEIGHTED output
 * list, each entry gated by the master progression switch — the RIVAL level. Districts do not
 * magically produce everything; workers roll from the allowed rows of their district's table:
 *
 *   fishing { rateCoefficient=1.0, outputs=[ {level 0, minecraft:fish, w100},
 *                                            {level 1, minecraft:fish:1, w25}, ... ] }
 *
 * "item" accepts "modid:name" or "modid:name:meta". Rows whose item can't be resolved at runtime
 * (missing mod) are skipped by the roll, not fatal. Keys are CivilMarker.configKey() strings
 * ("fishing", "mining", "trade_depot", ...) so new district kinds need only a config entry.
 *
 * No separate district XP system: RIVAL level is the ONLY unlock axis, same switch that drives
 * rival expansion, raid pressure and tech era.
 */
public final class DistrictOutputConfig {

    private static final String CONFIG_FILE = "Homosapien/district_outputs.json";

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final Random RNG = new Random();

    public static ConfigData data = new ConfigData();

    private DistrictOutputConfig() {}

    // ==================================================================
    // DATA MODEL
    // ==================================================================

    public static class OutputEntry {
        /** Minimum RIVAL level for this row to be in the roll pool (0 = always). */
        public int level = 0;
        /** "modid:name" or "modid:name:meta". */
        public String item = "";
        /** Relative weight among currently-unlocked rows. */
        public int weight = 1;

        public OutputEntry() {}

        public OutputEntry(int level, String item, int weight) {
            this.level = level; this.item = item; this.weight = weight;
        }
    }

    public static class DistrictTable {
        /** Scales how often a worker's work-tick actually yields an item (1.0 = baseline). */
        public double rateCoefficient = 1.0;
        public List<OutputEntry> outputs = new ArrayList<>();

        public DistrictTable() {}

        public DistrictTable(double rate, OutputEntry... rows) {
            this.rateCoefficient = rate;
            for (OutputEntry r : rows) outputs.add(r);
        }
    }

    public static class ConfigData {
        /** Keyed by CivilMarker.configKey() ("fishing", "mining", ...). */
        public Map<String, DistrictTable> districts = defaults();

        void sanitize() {
            if (districts == null || districts.isEmpty()) districts = defaults();
            for (DistrictTable t : districts.values()) {
                if (t.outputs == null) t.outputs = new ArrayList<>();
                if (t.rateCoefficient <= 0) t.rateCoefficient = 1.0;
                t.outputs.removeIf(e -> e == null || e.item == null || e.item.isEmpty());
                for (OutputEntry e : t.outputs) {
                    if (e.weight < 1) e.weight = 1;
                    if (e.level < 0) e.level = 0;
                }
            }
        }
    }

    // ==================================================================
    // DEFAULT TABLES (the design examples, verbatim where given)
    // ==================================================================

    private static Map<String, DistrictTable> defaults() {
        Map<String, DistrictTable> d = new HashMap<>();
        d.put("fishing", new DistrictTable(1.0,
                new OutputEntry(0, "minecraft:fish", 100),
                new OutputEntry(1, "minecraft:fish:1", 25),   // salmon
                new OutputEntry(2, "minecraft:fish:2", 10),   // clownfish
                new OutputEntry(3, "minecraft:fish:3", 5)));  // pufferfish
        d.put("mining", new DistrictTable(0.35,
                new OutputEntry(0, "minecraft:cobblestone", 100),
                new OutputEntry(0, "minecraft:coal", 35),
                new OutputEntry(1, "minecraft:iron_ore", 25),
                new OutputEntry(2, "minecraft:redstone", 15),
                new OutputEntry(2, "minecraft:gold_ore", 10),
                new OutputEntry(3, "minecraft:diamond", 3),
                new OutputEntry(4, "immersiveengineering:ore:0", 8)));
        d.put("lumber", new DistrictTable(0.8,
                new OutputEntry(0, "minecraft:log", 100),
                new OutputEntry(0, "minecraft:sapling", 20),
                new OutputEntry(0, "minecraft:apple", 8),
                new OutputEntry(1, "minecraft:log:1", 40),    // spruce
                new OutputEntry(2, "minecraft:log2", 15)));   // acacia
        d.put("farm", new DistrictTable(0.9,
                new OutputEntry(0, "minecraft:wheat", 100),
                new OutputEntry(0, "minecraft:carrot", 40),
                new OutputEntry(0, "minecraft:potato", 40),
                new OutputEntry(1, "minecraft:beetroot", 25),
                new OutputEntry(2, "minecraft:pumpkin", 12),
                new OutputEntry(3, "minecraft:melon", 12)));
        d.put("hunting", new DistrictTable(0.6,
                new OutputEntry(0, "minecraft:leather", 60),
                new OutputEntry(0, "minecraft:beef", 100),
                new OutputEntry(0, "minecraft:porkchop", 80),
                new OutputEntry(0, "minecraft:feather", 40),
                new OutputEntry(1, "minecraft:rabbit", 30),
                new OutputEntry(2, "minecraft:mutton", 30)));
        return d;
    }

    // ==================================================================
    // LOAD / SAVE (same manual-GSON pattern as WarMasterConfig)
    // ==================================================================

    public static void load(File configDir) {
        if (configDir == null) throw new IllegalArgumentException("configDir cannot be null");

        File file = new File(configDir, CONFIG_FILE);
        File parent = file.getParentFile();
        if (parent != null && !parent.exists()) parent.mkdirs();

        if (!file.exists()) {
            data = new ConfigData();
            save(configDir);
            logInfo("[DISTRICT-CONFIG] Generated " + CONFIG_FILE);
            return;
        }

        try (Reader reader = new FileReader(file)) {
            ConfigData loaded = GSON.fromJson(reader, ConfigData.class);
            if (loaded == null) {
                data = new ConfigData();
                save(configDir);
                logInfo("[DISTRICT-CONFIG] " + CONFIG_FILE + " was empty/invalid; regenerated defaults");
            } else {
                loaded.sanitize();
                data = loaded;
                logInfo("[DISTRICT-CONFIG] Loaded " + CONFIG_FILE + " (" + data.districts.size() + " tables)");
            }
        } catch (Exception e) {
            data = new ConfigData();
            save(configDir);
            logError("[DISTRICT-CONFIG] Failed to load " + CONFIG_FILE + " ; regenerated defaults", e);
        }
    }

    public static void save(File configDir) {
        File file = new File(configDir, CONFIG_FILE);
        File parent = file.getParentFile();
        if (parent != null && !parent.exists()) parent.mkdirs();
        try (Writer writer = new FileWriter(file)) {
            GSON.toJson(data, writer);
        } catch (Exception e) {
            logError("[DISTRICT-CONFIG] Failed to save " + CONFIG_FILE, e);
        }
    }

    // ==================================================================
    // ROLLS
    // ==================================================================

    public static DistrictTable table(String key) {
        return data.districts.get(key);
    }

    public static double rateCoefficient(String key) {
        DistrictTable t = table(key);
        return t != null ? t.rateCoefficient : 0.0;
    }

    /** Does this district kind produce anything at all (has a table with rows)? */
    public static boolean produces(String key) {
        DistrictTable t = table(key);
        return t != null && !t.outputs.isEmpty();
    }

    /**
     * Weighted roll over the rows unlocked at {@code rivalLevel}. Returns EMPTY when the kind has
     * no table, nothing is unlocked yet, or the winning row's item can't be resolved (missing mod
     * rows lose their chance silently — by design, so tables can reference optional mods).
     */
    public static ItemStack rollOutput(String key, int rivalLevel) {
        DistrictTable t = table(key);
        if (t == null || t.outputs.isEmpty()) return ItemStack.EMPTY;

        int total = 0;
        for (OutputEntry e : t.outputs) if (e.level <= rivalLevel) total += e.weight;
        if (total <= 0) return ItemStack.EMPTY;

        int pick = RNG.nextInt(total);
        for (OutputEntry e : t.outputs) {
            if (e.level > rivalLevel) continue;
            pick -= e.weight;
            if (pick < 0) return resolve(e.item);
        }
        return ItemStack.EMPTY;
    }

    /** "modid:name" or "modid:name:meta" -> stack of 1, or EMPTY if unresolvable. */
    public static ItemStack resolve(String id) {
        if (id == null || id.isEmpty()) return ItemStack.EMPTY;
        String name = id;
        int meta = 0;
        int last = id.lastIndexOf(':');
        // A trailing all-digits segment after the SECOND colon is damage/meta.
        if (last > id.indexOf(':') && last < id.length() - 1) {
            String tail = id.substring(last + 1);
            if (tail.chars().allMatch(Character::isDigit)) {
                meta = Integer.parseInt(tail);
                name = id.substring(0, last);
            }
        }
        Item item = Item.getByNameOrId(name);
        return item == null ? ItemStack.EMPTY : new ItemStack(item, 1, meta);
    }

    private static void logInfo(String msg) {
        if (EpochRunnerMod.logger != null) EpochRunnerMod.logger.info(msg);
    }

    private static void logError(String msg, Throwable t) {
        if (EpochRunnerMod.logger != null) EpochRunnerMod.logger.error(msg, t);
    }
}
