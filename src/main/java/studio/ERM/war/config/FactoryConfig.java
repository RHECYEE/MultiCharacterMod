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
 * FACTORY tuning (config/Homosapien/factory.json). Each manned assembly SEAT crafts its recipe once
 * per interval; higher rival levels run the line faster (better tech). Interval seconds =
 * max(minCraftSeconds, baseCraftSeconds − rivalLevel × secondsFasterPerRivalLevel).
 */
public final class FactoryConfig {

    private static final String CONFIG_FILE = "Homosapien/factory.json";
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    public static ConfigData data = new ConfigData();

    private FactoryConfig() {}

    public static class ConfigData {
        public int baseCraftSeconds = 30;
        public int secondsFasterPerRivalLevel = 2;
        public int minCraftSeconds = 6;

        void sanitize() {
            if (baseCraftSeconds < 1) baseCraftSeconds = 30;
            if (secondsFasterPerRivalLevel < 0) secondsFasterPerRivalLevel = 2;
            if (minCraftSeconds < 1) minCraftSeconds = 6;
        }
    }

    /** Ticks between crafts for one seat at the given rival level. */
    public static int craftIntervalTicks(int rivalLevel) {
        int secs = Math.max(data.minCraftSeconds,
                data.baseCraftSeconds - rivalLevel * data.secondsFasterPerRivalLevel);
        return secs * 20;
    }

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
            EpochRunnerMod.logger.info("[FACTORY-CONFIG] loaded " + CONFIG_FILE);
        } catch (Exception e) {
            data = new ConfigData();
            save(configDir);
            EpochRunnerMod.logger.error("[FACTORY-CONFIG] failed to load; regenerated defaults", e);
        }
    }

    public static void save(File configDir) {
        File file = new File(configDir, CONFIG_FILE);
        File parent = file.getParentFile();
        if (parent != null && !parent.exists()) parent.mkdirs();
        try (Writer writer = new FileWriter(file)) { GSON.toJson(data, writer); }
        catch (Exception e) { EpochRunnerMod.logger.error("[FACTORY-CONFIG] failed to save", e); }
    }
}
