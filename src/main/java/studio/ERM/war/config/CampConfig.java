package studio.ERM.war.config;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import studio.ERM.EpochRunnerMod;

import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.Reader;
import java.io.Writer;

/**
 * CAMP TUNING (config/Homosapien/camps.json) — the strategic extraction-camp knobs the design
 * calls "configurable": expansion caps/cadence, claim footprint per camp level, production scaling,
 * teamster cadence, upgrade ceiling. Loaded next to the other Homosapien json configs.
 */
public final class CampConfig {

    private static final String CONFIG_FILE = "Homosapien/camps.json";
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    public static class ConfigData {
        public String note = "Strategic extraction camp tuning. claimRadiusChunksByLevel index 0 = level 1.";
        /** Rival max active camps = base + rivalLevel / perLevelDiv. */
        public int maxCampsBase = 3;
        public int maxCampsPerLevelDiv = 3;
        /** Rival expansion/upgrade evaluation cadence (Minecraft days). */
        public int evalEveryDays = 6;
        /** Camp claim footprint: radius in CHUNKS per camp level ("configurable target chunks"). */
        public int[] claimRadiusChunksByLevel = {1, 2, 3};
        /** Camp upgrade ceiling. */
        public int maxCampLevel = 3;
        /** Production multiplier added per camp level above 1. */
        public double productionPerCampLevel = 0.5;
        /** Strategic units per delivered item. */
        public int unitsPerItem = 8;
        /** Teamster collection cadence (Minecraft days) + the minimum load worth a trip. */
        public int teamsterEveryDays = 2;
        public int teamsterMinUnits = 64;
        /** Rival auto-upgrades one camp per eval while reserves justify it. */
        public boolean rivalAutoUpgrade = true;

        void sanitize() {
            if (maxCampsBase < 1) maxCampsBase = 1;
            if (maxCampsPerLevelDiv < 1) maxCampsPerLevelDiv = 1;
            if (evalEveryDays < 1) evalEveryDays = 1;
            if (claimRadiusChunksByLevel == null || claimRadiusChunksByLevel.length == 0)
                claimRadiusChunksByLevel = new int[]{1, 2, 3};
            if (maxCampLevel < 1) maxCampLevel = 1;
            if (productionPerCampLevel < 0) productionPerCampLevel = 0;
            if (unitsPerItem < 1) unitsPerItem = 1;
            if (teamsterEveryDays < 1) teamsterEveryDays = 1;
            if (teamsterMinUnits < 1) teamsterMinUnits = 1;
        }
    }

    public static ConfigData data = new ConfigData();

    private CampConfig() {}

    public static void load(File configDir) {
        File file = new File(configDir, CONFIG_FILE);
        File parent = file.getParentFile();
        if (parent != null && !parent.exists()) parent.mkdirs();
        if (!file.exists()) {
            data = new ConfigData();
            try (Writer w = new FileWriter(file)) { GSON.toJson(data, w); } catch (Exception ignored) {}
            EpochRunnerMod.logger.info("[CAMPS] Generated default " + CONFIG_FILE);
            return;
        }
        try (Reader reader = new FileReader(file)) {
            ConfigData loaded = GSON.fromJson(reader, ConfigData.class);
            if (loaded != null) { loaded.sanitize(); data = loaded; }
            EpochRunnerMod.logger.info("[CAMPS] Loaded " + CONFIG_FILE);
        } catch (Exception e) {
            EpochRunnerMod.logger.warn("[CAMPS] Failed to load " + CONFIG_FILE + " — defaults in force");
            data = new ConfigData();
        }
    }

    public static int claimRadiusChunks(int campLevel) {
        int[] arr = data.claimRadiusChunksByLevel;
        return arr[Math.max(0, Math.min(campLevel - 1, arr.length - 1))];
    }

    public static int maxRivalCamps(int rivalLevel) {
        return data.maxCampsBase + Math.max(0, rivalLevel) / data.maxCampsPerLevelDiv;
    }
}
