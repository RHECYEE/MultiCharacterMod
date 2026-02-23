package studio.ERM.config;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import studio.ERM.EpochRunnerMod;

import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.Reader;
import java.io.Writer;

/**
 * MOD CONFIG (V1)
 *
 * File: config/Homosapien/mod_config.json
 *
 * This is the catch-all for NON-war configuration.
 *
 * Owns...
 *  - Citizen / player-owned NPC knobs (non-war)
 *  - Skin pack / cosmetics preferences that are not part of the rival city war systems
 *  - General mod feature toggles not tied to war
 *
 * Does NOT own:
 *  - War master settings -> studio.ERM.war.config.WarMasterConfig
 *  - War levels tables -> studio.ERM.war.config.WarLevelsConfig
 *  - Rival city + district efficiencies -> studio.ERM.war.config.WarRivalCityDistrictsConfig
 */
public final class ModConfig {

    private static final String CONFIG_FILE = "Homosapien/mod_config.json";

    private static final Gson GSON = new GsonBuilder()
            .setPrettyPrinting()
            .create();

    private static ConfigData data = new ConfigData();

    private ModConfig() { }

    public static ConfigData get() {
        return data;
    }

    public static void load(File configDir) {
        if (configDir == null) throw new IllegalArgumentException("configDir cannot be null");

        File file = new File(configDir, CONFIG_FILE);
        File parent = file.getParentFile();
        if (parent != null && !parent.exists()) parent.mkdirs();

        if (!file.exists()) {
            data = new ConfigData();
            save(configDir);
            logInfo("[MOD-CONFIG] Generated " + CONFIG_FILE);
            return;
        }

        try (Reader reader = new FileReader(file)) {
            ConfigData loaded = GSON.fromJson(reader, ConfigData.class);
            if (loaded == null) {
                data = new ConfigData();
                save(configDir);
                logWarn("[MOD-CONFIG] " + CONFIG_FILE + " was empty/invalid JSON; regenerated defaults");
            } else {
                loaded.sanitize();
                data = loaded;
                logInfo("[MOD-CONFIG] Loaded " + CONFIG_FILE);
            }
        } catch (Exception e) {
            data = new ConfigData();
            save(configDir);
            logError("[MOD-CONFIG] Failed to load " + CONFIG_FILE + " ; regenerated defaults", e);
        }
    }

    public static void save(File configDir) {
        if (configDir == null) throw new IllegalArgumentException("configDir cannot be null");

        File file = new File(configDir, CONFIG_FILE);
        File parent = file.getParentFile();
        if (parent != null && !parent.exists()) parent.mkdirs();

        try (Writer writer = new FileWriter(file)) {
            GSON.toJson(data, writer);
        } catch (Exception e) {
            logError("[MOD-CONFIG] Failed to save " + CONFIG_FILE, e);
        }
    }

    private static void logInfo(String msg) {
        if (EpochRunnerMod.logger != null) EpochRunnerMod.logger.info(msg);
    }

    private static void logWarn(String msg) {
        if (EpochRunnerMod.logger != null) EpochRunnerMod.logger.warn(msg);
    }

    private static void logError(String msg, Throwable t) {
        if (EpochRunnerMod.logger != null) EpochRunnerMod.logger.error(msg, t);
    }

    // ======================================================================
    // DATA ROOT
    // ======================================================================

    public static final class ConfigData {
        public CitizenNpcSettings citizenNpcs = new CitizenNpcSettings();
        public SkinPackSettings skins = new SkinPackSettings();

        public boolean debugLogging = true;

        private void sanitize() {
            if (citizenNpcs == null) citizenNpcs = new CitizenNpcSettings();
            if (skins == null) skins = new SkinPackSettings();
            citizenNpcs.sanitize();
            skins.sanitize();
        }
    }

    // ======================================================================
    // SETTINGS
    // ======================================================================

    /**
     * Non-war behavior knobs for player-owned citizens / modular NPCs.
     * (War-facing behaviors like raid fleeing belong in WarMasterConfig.)
     */
    public static final class CitizenNpcSettings {
        public int minLoiterTimeTicks = 100;
        public int maxLoiterTimeTicks = 400;
        public int patrolWaitTimeSeconds = 60;

        public void sanitize() {
            if (minLoiterTimeTicks < 0) minLoiterTimeTicks = 0;
            if (maxLoiterTimeTicks < minLoiterTimeTicks) maxLoiterTimeTicks = minLoiterTimeTicks;
            if (patrolWaitTimeSeconds < 0) patrolWaitTimeSeconds = 0;
        }
    }

    /**
     * Skin pack selection controls.
     * These are intentionally generic; your SkinHandler can read these as needed.
     */
    public static final class SkinPackSettings {
        // If non-empty, the NPC spawner can prefer these skin group prefixes (AW2 skin packs)
        public String[] preferredSkinPrefixes = new String[] {
                "empire_", "roman_", "vik_", "samurai_"
        };

        // If true, allows random selection outside preferred prefixes when no match is found.
        public boolean allowFallbackToAnySkin = true;

        public void sanitize() {
            if (preferredSkinPrefixes == null) preferredSkinPrefixes = new String[0];
        }
    }
}
