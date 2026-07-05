package studio.ERM.war.config;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
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
import java.util.Set;

/**
 * THE AW2 SCHEMATIC CATALOG (config/Homosapien/schematic_catalog.json) — one shared index of the
 * MohawkyPack .aws templates, organized by purpose (from AW2_Schematic_Organization.xlsx):
 *
 *   "Nation States"            -> nation-state settlement buildings (per AWNation* pack)
 *   "Outposts & Camps"         -> strategic camps: mission camps, frontier posts, siege staging
 *   "Castles & Fortifications" -> defensive works for nations + high-level rivals
 *   "Empire"                   -> the Empire pack's civic set
 *   "Level 0 Tribal / Tents"   -> pre-civilization camps (survey/camp missions, early rivals)
 *   "Siege Forts & Castles"    -> the SIEGING ARMY's progressive siege works
 *
 * Every consumer (rival city ALREADY places templates by name; nation states, strategic camps and
 * the siege director come next) pulls names from here and matches them against what AW2 actually
 * has loaded — missing templates are skipped silently so pack changes never crash a system.
 */
public final class SchematicCatalog {

    private static final String CONFIG_FILE = "Homosapien/schematic_catalog.json";
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final Random RNG = new Random();

    // Canonical category keys.
    public static final String NATION_STATES = "Nation States";
    public static final String OUTPOSTS_CAMPS = "Outposts & Camps";
    public static final String CASTLES = "Castles & Fortifications";
    public static final String EMPIRE = "Empire";
    public static final String TRIBAL = "Level 0 Tribal / Tents";
    public static final String SIEGE_FORTS = "Siege Forts & Castles";

    public static class ConfigData {
        public String note = "AW2 schematic catalog. categories -> nation pack -> template names. "
                + "Regenerate from AW2_Schematic_Organization.xlsx.";
        public Map<String, Map<String, List<String>>> categories = new HashMap<>();
    }

    public static ConfigData data = new ConfigData();

    private SchematicCatalog() {}

    public static void load(File configDir) {
        File file = new File(configDir, CONFIG_FILE);
        File parent = file.getParentFile();
        if (parent != null && !parent.exists()) parent.mkdirs();
        if (!file.exists()) {
            data = new ConfigData();
            try (Writer w = new FileWriter(file)) { GSON.toJson(data, w); } catch (Exception ignored) {}
            logInfo("[SCHEMATICS] Generated empty " + CONFIG_FILE + " — export the xlsx to fill it.");
            return;
        }
        try (Reader reader = new FileReader(file)) {
            ConfigData loaded = GSON.fromJson(reader, ConfigData.class);
            if (loaded != null && loaded.categories != null) {
                data = loaded;
                int n = 0;
                for (Map<String, List<String>> c : data.categories.values())
                    for (List<String> v : c.values()) n += v.size();
                logInfo("[SCHEMATICS] Loaded " + CONFIG_FILE + " (" + data.categories.size()
                        + " categories, " + n + " templates)");
            }
        } catch (Exception e) {
            logInfo("[SCHEMATICS] Failed to load " + CONFIG_FILE + " — using empty catalog");
            data = new ConfigData();
        }
    }

    /** All catalogued names in a category (all nation packs), or only one pack when given. */
    public static List<String> names(String category, String nationOrNull) {
        List<String> out = new ArrayList<>();
        Map<String, List<String>> subs = data.categories.get(category);
        if (subs == null) return out;
        for (Map.Entry<String, List<String>> e : subs.entrySet()) {
            if (nationOrNull != null && !e.getKey().equalsIgnoreCase(nationOrNull)) continue;
            out.addAll(e.getValue());
        }
        return out;
    }

    /** The nation packs present in a category (AWNationNogg, AWNationEmpire, ...). */
    public static List<String> nations(String category) {
        Map<String, List<String>> subs = data.categories.get(category);
        return subs == null ? new ArrayList<>() : new ArrayList<>(subs.keySet());
    }

    /**
     * Catalogued names that AW2 has ACTUALLY loaded right now — the safe pick pool. Empty when
     * AW2 is absent or the packs changed. (Same guarded-reflection doctrine as the rival city.)
     */
    public static List<String> existing(String category, String nationOrNull) {
        List<String> out = new ArrayList<>();
        Set<String> loaded = loadedTemplates();
        if (loaded == null) return out;
        for (String n : names(category, nationOrNull)) {
            if (loaded.contains(n)) out.add(n);
        }
        return out;
    }

    /** A random loaded template from a category (optionally one nation pack), or null. */
    public static String pick(String category, String nationOrNull) {
        List<String> pool = existing(category, nationOrNull);
        return pool.isEmpty() ? null : pool.get(RNG.nextInt(pool.size()));
    }

    private static Set<String> loadedTemplates() {
        try {
            Class.forName("net.shadowmage.ancientwarfare.structure.template.StructureTemplateManager");
            return net.shadowmage.ancientwarfare.structure.template.StructureTemplateManager.getTemplates();
        } catch (Throwable t) {
            return null;
        }
    }

    private static void logInfo(String msg) {
        if (EpochRunnerMod.logger != null) EpochRunnerMod.logger.info(msg);
    }
}
