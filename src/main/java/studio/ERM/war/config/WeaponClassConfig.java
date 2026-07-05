package studio.ERM.war.config;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import studio.ERM.EpochRunnerMod;

import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.Reader;
import java.io.Writer;
import java.util.Arrays;
import java.util.List;

/**
 * FLAN WEAPON CLASSIFICATION (config/Homosapien/weapon_classes.json) — the AT / AP / AA doctrine:
 * weapons are classed by ShortName/item-name KEYWORDS, and a DAMAGE CHART scales what each class
 * does to each target category (infantry / armor / air). AT shreds armor but wastes on infantry;
 * AP is the rifleman's baseline; AA owns the sky and little else.
 *
 * Consumers: the flak net (AA vs AIR), soldier gunfire (class vs living targets), and every future
 * damage path the modern-warfare overhaul adds — one chart, applied wherever WE deal the damage.
 */
public final class WeaponClassConfig {

    private static final String CONFIG_FILE = "Homosapien/weapon_classes.json";
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    public static final int CLASS_AP = 0, CLASS_AT = 1, CLASS_AA = 2;
    public static final int TARGET_INFANTRY = 0, TARGET_ARMOR = 1, TARGET_AIR = 2;

    public static class ConfigData {
        public String note = "Weapon class keywords (matched against lowercase Flan ShortName/item name) "
                + "+ the damage chart [class][target]: targets are infantry/armor/air.";
        public List<String> atKeywords = Arrays.asList(
                "rpg", "bazooka", "panzerfaust", "panzerschreck", "at4", "law", "recoilless",
                "antitank", "anti_tank", "hydra", "tow");
        public List<String> aaKeywords = Arrays.asList(
                "aa", "flak", "bofors", "stinger", "mim", "sam", "igla", "antiair", "anti_air");
        /** chart[class][target] — class AP/AT/AA x target infantry/armor/air. */
        public double[][] chart = {
                {1.15, 0.35, 0.50},  // AP: the infantry baseline, pings off plate + planes
                {0.60, 2.00, 0.40},  // AT: armor-killer, clumsy against men + fast movers
                {0.50, 0.25, 2.50},  // AA: owns the sky
        };

        void sanitize() {
            if (chart == null || chart.length != 3) chart = new ConfigData().chart;
            for (int i = 0; i < 3; i++) {
                if (chart[i] == null || chart[i].length != 3) chart[i] = new ConfigData().chart[i];
            }
            if (atKeywords == null) atKeywords = new ConfigData().atKeywords;
            if (aaKeywords == null) aaKeywords = new ConfigData().aaKeywords;
        }
    }

    public static ConfigData data = new ConfigData();

    private WeaponClassConfig() {}

    public static void load(File configDir) {
        File file = new File(configDir, CONFIG_FILE);
        File parent = file.getParentFile();
        if (parent != null && !parent.exists()) parent.mkdirs();
        if (!file.exists()) {
            data = new ConfigData();
            try (Writer w = new FileWriter(file)) { GSON.toJson(data, w); } catch (Exception ignored) {}
            EpochRunnerMod.logger.info("[WEAPONS] Generated default " + CONFIG_FILE);
            return;
        }
        try (Reader reader = new FileReader(file)) {
            ConfigData loaded = GSON.fromJson(reader, ConfigData.class);
            if (loaded != null) { loaded.sanitize(); data = loaded; }
            EpochRunnerMod.logger.info("[WEAPONS] Loaded " + CONFIG_FILE);
        } catch (Exception e) {
            EpochRunnerMod.logger.warn("[WEAPONS] Failed to load " + CONFIG_FILE + " — defaults in force");
            data = new ConfigData();
        }
    }

    /** Classify a weapon by its (lowercased) ShortName / registry / display name. */
    public static int classify(String weaponName) {
        if (weaponName == null || weaponName.isEmpty()) return CLASS_AP;
        String low = weaponName.toLowerCase();
        for (String kw : data.atKeywords) if (low.contains(kw)) return CLASS_AT;
        for (String kw : data.aaKeywords) if (low.contains(kw)) return CLASS_AA;
        return CLASS_AP;
    }

    /** The damage chart: what {@code weaponClass} does to {@code targetCategory}. */
    public static double mult(int weaponClass, int targetCategory) {
        try {
            return data.chart[Math.max(0, Math.min(2, weaponClass))][Math.max(0, Math.min(2, targetCategory))];
        } catch (Throwable t) {
            return 1.0;
        }
    }
}
