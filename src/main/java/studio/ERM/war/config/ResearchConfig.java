package studio.ERM.war.config;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import studio.ERM.EpochRunnerMod;

import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.Reader;
import java.io.Writer;
import java.util.HashMap;
import java.util.Map;

/**
 * RESEARCH COSTS (config/Homosapien/research_costs.json)
 *
 * We SCRUB AW2's per-node item resource requirements and replace them with two knobs:
 *   - a COMMAND-BUCK cost to QUEUE a research node, and
 *   - an EMPLOYEE-TIME cost (worker-seconds) that the Research district's assigned workers grind down.
 *
 * Both default from AW2's own research time so the whole tree is costed automatically; any node can be
 * overridden by id. The tree is huge, so the file ships with the formula + an empty overrides map.
 */
public final class ResearchConfig {

    private static final String CONFIG_FILE = "Homosapien/research_costs.json";
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    public static ConfigData data = new ConfigData();

    private ResearchConfig() {}

    public static class Override {
        public int commandBucks = -1;     // -1 = use formula
        public int employeeSeconds = -1;  // -1 = use formula
    }

    public static class ConfigData {
        /** Command Bucks to queue a node = round(aw2Time × cbPerAw2Time), min cbFloor. */
        public double cbPerAw2Time = 0.4;
        public int cbFloor = 10;
        /** Worker-seconds to complete = baseEmployeeSeconds + aw2Time × employeeSecPerAw2Time. */
        public int baseEmployeeSeconds = 45;
        public double employeeSecPerAw2Time = 0.08;
        /** Research points a single assigned worker contributes per real second. */
        public double pointsPerWorkerPerSecond = 1.0;
        /** Per-node overrides, keyed by AW2 research name. */
        public Map<String, Override> overrides = new HashMap<>();

        void sanitize() {
            if (cbPerAw2Time < 0) cbPerAw2Time = 0.4;
            if (cbFloor < 0) cbFloor = 10;
            if (baseEmployeeSeconds < 1) baseEmployeeSeconds = 45;
            if (employeeSecPerAw2Time < 0) employeeSecPerAw2Time = 0.08;
            if (pointsPerWorkerPerSecond <= 0) pointsPerWorkerPerSecond = 1.0;
            if (overrides == null) overrides = new HashMap<>();
        }
    }

    public static int cbCost(String id, int aw2Time) {
        Override o = data.overrides.get(id);
        if (o != null && o.commandBucks >= 0) return o.commandBucks;
        return Math.max(data.cbFloor, (int) Math.round(aw2Time * data.cbPerAw2Time));
    }

    public static int employeeSeconds(String id, int aw2Time) {
        Override o = data.overrides.get(id);
        if (o != null && o.employeeSeconds >= 0) return o.employeeSeconds;
        return Math.max(1, (int) Math.round(data.baseEmployeeSeconds + aw2Time * data.employeeSecPerAw2Time));
    }

    public static double pointsPerWorkerPerSecond() { return data.pointsPerWorkerPerSecond; }

    public static void load(File configDir) {
        if (configDir == null) return;
        File file = new File(configDir, CONFIG_FILE);
        File parent = file.getParentFile();
        if (parent != null && !parent.exists()) parent.mkdirs();
        if (!file.exists()) { data = new ConfigData(); save(configDir); return; }
        try (Reader reader = new FileReader(file)) {
            ConfigData loaded = GSON.fromJson(reader, ConfigData.class);
            if (loaded == null) { data = new ConfigData(); save(configDir); }
            else { loaded.sanitize(); data = loaded; }
            EpochRunnerMod.logger.info("[RESEARCH-CONFIG] loaded " + CONFIG_FILE);
        } catch (Exception e) {
            data = new ConfigData();
            save(configDir);
            EpochRunnerMod.logger.error("[RESEARCH-CONFIG] failed to load; regenerated defaults", e);
        }
    }

    public static void save(File configDir) {
        File file = new File(configDir, CONFIG_FILE);
        File parent = file.getParentFile();
        if (parent != null && !parent.exists()) parent.mkdirs();
        try (Writer writer = new FileWriter(file)) { GSON.toJson(data, writer); }
        catch (Exception e) { EpochRunnerMod.logger.error("[RESEARCH-CONFIG] failed to save", e); }
    }
}
