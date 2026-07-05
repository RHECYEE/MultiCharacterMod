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
        public String note = "Per-weapon classes (exact registry name -> AP|AT|AA, dual like AP/AA = best of "
                + "both vs each target), keyword fallback for unlisted weapons, and the damage chart "
                + "[class][target]: targets are infantry/armor/air.";
        /** EXACT registry-name assignments (the user's classification table). Checked first. */
        public java.util.Map<String, String> weapons = defaultWeapons();
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
            if (weapons == null) weapons = defaultWeapons();
        }
    }

    /** The user's per-weapon classification table (2026-07-05), shipped as the config default. */
    private static java.util.Map<String, String> defaultWeapons() {
        java.util.Map<String, String> w = new java.util.LinkedHashMap<>();
        // AW2 melee — all AP (listed for completeness/visibility in the json).
        for (String n : new String[]{"diamond_lance", "diamond_spear", "giant_club", "golden_cleaver",
                "golden_halberd", "golden_lance", "golden_spear", "ice_spear", "iron_cleaver",
                "iron_halberd", "iron_lance", "iron_spear", "macuahuitl"}) {
            w.put("ancientwarfarenpc:" + n, "AP");
        }
        // Flan's modern/WW2 table.
        w.put("flansmod:2pdr", "AT");          w.put("flansmod:50cal", "AP/AA");
        w.put("flansmod:aa12", "AP");          w.put("flansmod:acr", "AP");
        w.put("flansmod:adar15", "AP");        w.put("flansmod:ak47", "AP");
        w.put("flansmod:ak74", "AP");          w.put("flansmod:an94", "AP");
        w.put("flansmod:ash12", "AP");         w.put("flansmod:at4", "AT");
        w.put("flansmod:aug", "AP");           w.put("flansmod:b4crossbow", "AP");
        w.put("flansmod:bar", "AP");           w.put("flansmod:barrett", "AP/AT");
        w.put("flansmod:bazooka", "AT");       w.put("flansmod:bofors", "AA/AT");
        w.put("flansmod:bren", "AP/AA");       w.put("flansmod:browning", "AP");
        w.put("flansmod:c4", "AT/AP");         w.put("flansmod:claymore", "AP");
        w.put("flansmod:coldware2", "AP");     w.put("flansmod:colt", "AP");
        w.put("flansmod:crossbow", "AP");      w.put("flansmod:deserteagle", "AP");
        w.put("flansmod:devotionx55", "AP");   w.put("flansmod:dp28", "AP/AA");
        w.put("flansmod:dragunov", "AP");      w.put("flansmod:eldiablo", "AP");
        w.put("flansmod:emp4", "AP");          w.put("flansmod:executioner", "AP");
        w.put("flansmod:famas", "AP");         w.put("flansmod:fg42", "AP");
        w.put("flansmod:flak38", "AA/AP");     w.put("flansmod:flak42", "AA/AP");
        w.put("flansmod:flak88", "AA/AT");     w.put("flansmod:flakvierling", "AA/AP");
        w.put("flansmod:flamethrower", "AP");  w.put("flansmod:fmg", "AP");
        w.put("flansmod:fnscar", "AP");        w.put("flansmod:g3", "AP");
        w.put("flansmod:g36", "AP");           w.put("flansmod:g43", "AP");
        w.put("flansmod:galil", "AP");         w.put("flansmod:gau19", "AP/AA");
        w.put("flansmod:gl1", "AP/AT");        w.put("flansmod:gl6", "AP/AT");
        w.put("flansmod:glock", "AP");         w.put("flansmod:hcar", "AP");
        w.put("flansmod:hellfire", "AT");      w.put("flansmod:hk416", "AP");
        w.put("flansmod:honeybadger", "AP");   w.put("flansmod:hydra70", "AT/AP");
        w.put("flansmod:judge", "AP");         w.put("flansmod:jury", "AP");
        w.put("flansmod:kar98k", "AP");        w.put("flansmod:kar98ksniper", "AP");
        w.put("flansmod:kcasmart50", "AP/AT"); w.put("flansmod:kcasmartcarbine", "AP");
        w.put("flansmod:kcasmartpistol", "AP"); w.put("flansmod:krissvector", "AP");
        w.put("flansmod:l86", "AP");           w.put("flansmod:l96", "AP");
        w.put("flansmod:leeenfield", "AP");    w.put("flansmod:leeenfieldsniper", "AP");
        w.put("flansmod:luger", "AP");         w.put("flansmod:m1014", "AP");
        w.put("flansmod:m14", "AP");           w.put("flansmod:m157mm", "AT/AP");
        w.put("flansmod:m16a4", "AP");         w.put("flansmod:m1887", "AP");
        w.put("flansmod:m1911", "AP");         w.put("flansmod:m1carbine", "AP");
        w.put("flansmod:m1garand", "AP");      w.put("flansmod:m21", "AP");
        w.put("flansmod:m249", "AP/AA");       w.put("flansmod:m3a1", "AP");
        w.put("flansmod:m40a3", "AP");         w.put("flansmod:m45quad", "AA/AP");
        w.put("flansmod:m60", "AP/AA");        w.put("flansmod:m67", "AP");
        w.put("flansmod:m72law", "AT");        w.put("flansmod:m9", "AP");
        w.put("flansmod:makarov", "AP");       w.put("flansmod:mastiff1218", "AP");
        w.put("flansmod:mg151", "AA/AP");      w.put("flansmod:mg42", "AP/AA");
        w.put("flansmod:millsbomb", "AP");     w.put("flansmod:mim23", "AA");
        w.put("flansmod:minigun", "AP/AA");    w.put("flansmod:mk2frag", "AP");
        w.put("flansmod:mk4rocket", "AT/AP");  w.put("flansmod:mlrs6", "AT/AP");
        w.put("flansmod:molotovcocktail", "AP/AT"); w.put("flansmod:mp40", "AP");
        w.put("flansmod:mp44", "AP");          w.put("flansmod:mp5", "AP");
        w.put("flansmod:mp7", "AP");           w.put("flansmod:mtar", "AP");
        w.put("flansmod:mwknife", "AP");       w.put("flansmod:mwriotshield", "AP");
        w.put("flansmod:nagant", "AP");        w.put("flansmod:nagantsniper", "AP");
        w.put("flansmod:napalm", "AP/AT");     w.put("flansmod:ntw20", "AT/AP");
        w.put("flansmod:p90", "AP");           w.put("flansmod:pak40", "AT");
        w.put("flansmod:panzerfaust", "AT");   w.put("flansmod:panzerfaust3", "AT");
        w.put("flansmod:panzerschreck", "AT"); w.put("flansmod:paw20", "AT/AP");
        w.put("flansmod:peacekeeper", "AP");   w.put("flansmod:piat", "AT");
        w.put("flansmod:ppsh", "AP");          w.put("flansmod:pr3", "AP");
        w.put("flansmod:r700", "AP");          w.put("flansmod:r870", "AP");
        w.put("flansmod:rgd33", "AP");         w.put("flansmod:rock", "AP");
        w.put("flansmod:rpd", "AP/AA");        w.put("flansmod:rpg", "AT/AP");
        w.put("flansmod:rpk", "AP/AA");        w.put("flansmod:sentrygun", "AP/AA");
        w.put("flansmod:sg550", "AP");         w.put("flansmod:sidewinder7", "AA");
        w.put("flansmod:sigp226", "AP");       w.put("flansmod:sigp232", "AP");
        w.put("flansmod:skorpion", "AP");      w.put("flansmod:spas", "AP");
        w.put("flansmod:springfield", "AP");   w.put("flansmod:springfieldsniper", "AP");
        w.put("flansmod:sten", "AP");          w.put("flansmod:stielhandgranate", "AP");
        w.put("flansmod:stinger", "AA");       w.put("flansmod:thompson", "AP");
        w.put("flansmod:torpedo", "AT");       w.put("flansmod:trenchgun", "AP");
        w.put("flansmod:trigat", "AT");        w.put("flansmod:tripletake", "AP");
        w.put("flansmod:tt33", "AP");          w.put("flansmod:type100", "AP");
        w.put("flansmod:type14", "AP");        w.put("flansmod:type38", "AP");
        w.put("flansmod:type38sniper", "AP");  w.put("flansmod:type99", "AP");
        w.put("flansmod:usp", "AP");           w.put("flansmod:uzi", "AP");
        w.put("flansmod:vickersk", "AP/AA");   w.put("flansmod:voltv3", "AP");
        w.put("flansmod:w1200", "AP");         w.put("flansmod:webley", "AP");
        w.put("flansmod:ww2knife", "AP");      w.put("flansmod:ww2mine", "AT/AP");
        return w;
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

    /** Classify a weapon by its (lowercased) ShortName / registry / display name (KEYWORD fallback —
     *  exact table entries resolve through {@link #multFor} with dual-class support). */
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

    /**
     * The full lookup: EXACT table entry first (dual classes like "AP/AA" take the BEST multiplier
     * of their listed classes against this target — the weapon is genuinely effective as both),
     * keyword classification as the fallback for anything unlisted.
     */
    public static double multFor(String weaponRegistryName, int targetCategory) {
        try {
            if (weaponRegistryName != null) {
                String assigned = data.weapons.get(weaponRegistryName.toLowerCase());
                if (assigned != null && !assigned.isEmpty()) {
                    double best = 0;
                    for (String part : assigned.split("/")) {
                        int cls = "AT".equalsIgnoreCase(part.trim()) ? CLASS_AT
                                : "AA".equalsIgnoreCase(part.trim()) ? CLASS_AA : CLASS_AP;
                        best = Math.max(best, mult(cls, targetCategory));
                    }
                    return best > 0 ? best : 1.0;
                }
            }
        } catch (Throwable ignored) {}
        return mult(classify(weaponRegistryName), targetCategory);
    }
}
