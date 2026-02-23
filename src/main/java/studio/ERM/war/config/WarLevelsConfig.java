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
import java.util.List;
import java.util.Random;

/**
 * WAR LEVELS CONFIG (V1)
 *
 * File: config/Homosapien/war_levels.json
 *
 * Owns:
 *  - ALL level-based scaling tables (1..maxLevel)
 *  - Weapons / Vehicles unlock pools per level
 *  - Siege machine pools per level (AW2 vehicle type names OR entity registry ids)
 *  - Battle / Raid / Invasion / Air package level knobs
 *
 * Does NOT own:
 *  - Rival city generation knobs -> WarRivalCityDistrictsConfig
 *  - Global war behaviors/budgets -> WarMasterConfig
 *  - Citizen/NPC behavior & skins -> CitizenNpcSkinsConfig
 *
 * Siege machine authoring rules (per level):
 *  - If entry contains ':' -> treated as an Entity registry id (ex: ancientwarfarevehicle:vehicle)
 *  - If entry does NOT contain ':' -> treated as an AW2 VehicleType name (ex: ballista, trebuchet, etc.)
 *
 * IMPORTANT:
 *  - AW2 siege machines are commonly spawned as ancientwarfarevehicle:vehicle with a VehicleType applied.
 *    This config supports that by allowing raw AW2 VehicleType names in siegeMachineTypes.
 */
public final class WarLevelsConfig {

    private static final String CONFIG_FILE = "Homosapien/war_levels.json";

    private static final Gson GSON = new GsonBuilder()
            .setPrettyPrinting()
            .create();

    private static ConfigData data = new ConfigData();

    private static final Random RNG = new Random();

    private WarLevelsConfig() {}

    public static ConfigData get() {
        return data;
    }

    /**
     * Legacy hook: older code called this during init to ensure
     * server/client had their config loaded.
     *
     * In 1.12.2 we load configs from disk on the logical server.
     * If you later add network sync, wire it here.
     */
    public static void forceSync() {
        // Intentionally empty for now; config load happens from disk.
    }

    public static int getMaxDefinedLevel() {
        if (data.levels == null || data.levels.length == 0) return 1;
        return data.levels.length;
    }

    public static String[] getBattleWeaponPool(int level) {
        LevelData lv = getLevel(level);
        if (lv == null || lv.weaponItemIds == null || lv.weaponItemIds.isEmpty()) return new String[0];
        return lv.weaponItemIds.toArray(new String[0]);
    }

    public static String[] getVehicleShortNamesForLevel(int level) {
        LevelData lv = getLevel(level);
        if (lv == null || lv.vehicleShortNames == null || lv.vehicleShortNames.isEmpty()) return new String[0];
        return lv.vehicleShortNames.toArray(new String[0]);
    }

    /**
     * Siege pool for the given level.
     *
     * Entries are either:
     *  - Entity registry ids (contain ':')  -> spawn as entity
     *  - AW2 VehicleType names (no ':')    -> spawn ancientwarfarevehicle:vehicle and apply vehicle type
     */
    public static String[] getSiegeMachinePoolForLevel(int level) {
        LevelData lv = getLevel(level);
        if (lv == null || lv.siegeMachineTypes == null || lv.siegeMachineTypes.isEmpty()) return new String[0];
        return lv.siegeMachineTypes.toArray(new String[0]);
    }

    /**
     * Level-projected air package configuration.
     *
     * IMPORTANT: This must NOT extend AirPackageLevel.
     * AirPackageLevel is final in this codebase, and AirStrikeController reads these fields directly.
     */
    public static final class AirPackageConfig {
        // Fields AirStrikeController expects
        public int aircraftCount = 1;
        public int escortCount = 0;
        public String[] aircraftTypes = new String[0];

        // Keep legacy knobs so existing JSON doesn't become nonsense.
        public int aircraftCountMin = 1;
        public int aircraftCountMax = 1;
        public int payloadStrengthMin = 1;
        public int payloadStrengthMax = 1;

        public AirPackageConfig() {}

        public AirPackageConfig(AirPackageLevel src) {
            if (src != null) {
                this.aircraftCountMin = src.aircraftCountMin;
                this.aircraftCountMax = src.aircraftCountMax;
                this.payloadStrengthMin = src.payloadStrengthMin;
                this.payloadStrengthMax = src.payloadStrengthMax;
            }
            projectRuntimeFields();
        }

        public void projectRuntimeFields() {
            int min = Math.max(0, aircraftCountMin);
            int max = Math.max(min, aircraftCountMax);
            if (max == 0) {
                aircraftCount = 0;
            } else if (max == min) {
                aircraftCount = max;
            } else {
                aircraftCount = min + RNG.nextInt((max - min) + 1);
            }
            if (aircraftTypes == null) aircraftTypes = new String[0];
            if (escortCount < 0) escortCount = 0;
        }
    }

    public static AirPackageConfig getAirPackageConfig(int level) {
        LevelData lv = getLevel(level);
        if (lv == null) return new AirPackageConfig();
        AirPackageConfig cfg = new AirPackageConfig(lv.air);
        if (cfg.aircraftTypes == null) cfg.aircraftTypes = new String[0];
        cfg.projectRuntimeFields();
        return cfg;
    }

    /**
     * Legacy per-level rival city knobs. These now live in WarRivalCityDistrictsConfig,
     * but a small projection is kept here because multiple systems already query it.
     */
    public static final class RivalCityConfig {
        public int guardCount = 10;
        public int garrisonAnchorCount = 3;
    }

    public static RivalCityConfig getRivalCityConfig(int level) {
        int lvl = clampLevel(level);
        RivalCityConfig out = new RivalCityConfig();
        out.guardCount = Math.max(4, 6 + (lvl * 2));
        out.garrisonAnchorCount = Math.max(1, 1 + (lvl / 3));
        return out;
    }

    public static void load(File configDir) {
        if (configDir == null) {
            throw new IllegalArgumentException("configDir cannot be null");
        }

        File file = new File(configDir, CONFIG_FILE);
        File parent = file.getParentFile();
        if (parent != null && !parent.exists()) parent.mkdirs();

        if (!file.exists()) {
            data = new ConfigData();
            save(configDir);
            logInfo("[WAR-CONFIG] Generated " + CONFIG_FILE);
            return;
        }

        try (Reader reader = new FileReader(file)) {
            ConfigData loaded = GSON.fromJson(reader, ConfigData.class);
            if (loaded == null) {
                data = new ConfigData();
                save(configDir);
                logWarn("[WAR-CONFIG] " + CONFIG_FILE + " was empty/invalid JSON; regenerated defaults");
            } else {
                loaded.sanitize();
                data = loaded;
                logInfo("[WAR-CONFIG] Loaded " + CONFIG_FILE);
            }
        } catch (Exception e) {
            data = new ConfigData();
            save(configDir);
            logError("[WAR-CONFIG] Failed to load " + CONFIG_FILE + " ; regenerated defaults", e);
        }
    }

    public static void save(File configDir) {
        if (configDir == null) {
            throw new IllegalArgumentException("configDir cannot be null");
        }

        File file = new File(configDir, CONFIG_FILE);
        File parent = file.getParentFile();
        if (parent != null && !parent.exists()) parent.mkdirs();

        try (Writer writer = new FileWriter(file)) {
            GSON.toJson(data, writer);
        } catch (Exception e) {
            logError("[WAR-CONFIG] Failed to save " + CONFIG_FILE, e);
        }
    }

    public static int clampLevel(int level) {
        if (data.levels == null || data.levels.length == 0) return 1;
        if (level < 1) return 1;
        if (level > data.levels.length) return data.levels.length;
        return level;
    }

    public static LevelData getLevel(int level) {
        int idx = clampLevel(level) - 1;
        return data.levels[idx];
    }

    public static String pickRandomWeaponIdForLevel(int level) {
        LevelData lv = getLevel(level);
        if (lv.weaponItemIds == null || lv.weaponItemIds.isEmpty()) return "";
        return lv.weaponItemIds.get(RNG.nextInt(lv.weaponItemIds.size()));
    }

    public static String pickRandomVehicleShortNameForLevel(int level) {
        LevelData lv = getLevel(level);
        if (lv.vehicleShortNames == null || lv.vehicleShortNames.isEmpty()) return "";
        return lv.vehicleShortNames.get(RNG.nextInt(lv.vehicleShortNames.size()));
    }

    public static String pickRandomSiegeMachineForLevel(int level) {
        LevelData lv = getLevel(level);
        if (lv.siegeMachineTypes == null || lv.siegeMachineTypes.isEmpty()) return "";
        return lv.siegeMachineTypes.get(RNG.nextInt(lv.siegeMachineTypes.size()));
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
        public LevelData[] levels = createDefaultLevels();

        private void sanitize() {
            if (levels == null || levels.length == 0) {
                levels = createDefaultLevels();
                return;
            }

            for (int i = 0; i < levels.length; i++) {
                if (levels[i] == null) levels[i] = new LevelData();
                levels[i].sanitize(i + 1);
            }
        }
    }

    // ======================================================================
    // LEVEL DATA
    // ======================================================================

    public static final class LevelData {

        // Level index (redundant but useful for authoring clarity)
        public int level = 1;

        // Unlock pools
        public List<String> weaponItemIds = new ArrayList<>();
        public List<String> vehicleShortNames = new ArrayList<>();

        /**
         * Siege machine pool (per level).
         *
         * Authoring:
         *  - "trebuchet" (no ':') => interpreted as AW2 VehicleType name
         *  - "ancientwarfarevehicle:vehicle" (contains ':') => interpreted as entity registry id
         */
        public List<String> siegeMachineTypes = new ArrayList<>();

        // Event knobs
        public RaidLevel raid = new RaidLevel();
        public BattleLevel battle = new BattleLevel();
        public InvasionLevel invasion = new InvasionLevel();
        public AirPackageLevel air = new AirPackageLevel();

        public void sanitize(int expectedLevel) {
            if (level < 1) level = expectedLevel;
            if (weaponItemIds == null) weaponItemIds = new ArrayList<>();
            if (vehicleShortNames == null) vehicleShortNames = new ArrayList<>();
            if (siegeMachineTypes == null) siegeMachineTypes = new ArrayList<>();
            if (raid == null) raid = new RaidLevel();
            if (battle == null) battle = new BattleLevel();
            if (invasion == null) invasion = new InvasionLevel();
            if (air == null) air = new AirPackageLevel();

            raid.sanitize();
            battle.sanitize();
            invasion.sanitize();
            air.sanitize();
        }
    }

    public static final class RaidLevel {
        public int minUnits = 6;
        public int maxUnits = 12;
        public int radiusBlocks = 80;
        public int durationTicks = 20 * 60 * 3; // 3 minutes

        // Optional: EMC budget scaling table hook (logic belongs in WarMasterConfig)
        public long emcBudgetToSteal = 0L;

        public void sanitize() {
            if (minUnits < 0) minUnits = 0;
            if (maxUnits < minUnits) maxUnits = minUnits;
            if (radiusBlocks < 16) radiusBlocks = 16;
            if (durationTicks < 20 * 10) durationTicks = 20 * 10;
            if (emcBudgetToSteal < 0L) emcBudgetToSteal = 0L;
        }
    }

    public static final class BattleLevel {
        public int minUnits = 16;
        public int maxUnits = 28;
        public int officers = 1;
        public int durationTicks = 20 * 60 * 10; // 10 minutes

        public void sanitize() {
            if (minUnits < 0) minUnits = 0;
            if (maxUnits < minUnits) maxUnits = minUnits;
            if (officers < 0) officers = 0;
            if (durationTicks < 20 * 30) durationTicks = 20 * 30;
        }
    }

    public static final class InvasionLevel {
        public int minWaves = 2;
        public int maxWaves = 4;
        public int unitsPerWaveMin = 8;
        public int unitsPerWaveMax = 14;

        public void sanitize() {
            if (minWaves < 0) minWaves = 0;
            if (maxWaves < minWaves) maxWaves = minWaves;
            if (unitsPerWaveMin < 0) unitsPerWaveMin = 0;
            if (unitsPerWaveMax < unitsPerWaveMin) unitsPerWaveMax = unitsPerWaveMin;
        }
    }

    public static final class AirPackageLevel {
        public int aircraftCountMin = 0;
        public int aircraftCountMax = 1;

        // bombs/rockets interpreted by your airstrike system
        public int payloadStrengthMin = 0;
        public int payloadStrengthMax = 1;

        public void sanitize() {
            if (aircraftCountMin < 0) aircraftCountMin = 0;
            if (aircraftCountMax < aircraftCountMin) aircraftCountMax = aircraftCountMin;

            if (payloadStrengthMin < 0) payloadStrengthMin = 0;
            if (payloadStrengthMax < payloadStrengthMin) payloadStrengthMax = payloadStrengthMin;
        }
    }

    // ======================================================================
    // DEFAULTS
    // ======================================================================

    private static LevelData[] createDefaultLevels() {
        LevelData[] arr = new LevelData[10];

        for (int i = 0; i < arr.length; i++) {
            int lvl = i + 1;
            LevelData d = new LevelData();
            d.level = lvl;

            // Basic scaling
            d.raid.minUnits = 4 + (lvl * 1);
            d.raid.maxUnits = 8 + (lvl * 2);
            d.raid.radiusBlocks = 70 + (lvl * 5);
            d.raid.durationTicks = 20 * 60 * (2 + (lvl / 2));

            d.battle.minUnits = 12 + (lvl * 2);
            d.battle.maxUnits = 20 + (lvl * 3);
            d.battle.officers = Math.max(1, lvl / 3);
            d.battle.durationTicks = 20 * 60 * (6 + (lvl / 2));

            d.invasion.minWaves = 1 + (lvl / 3);
            d.invasion.maxWaves = 2 + (lvl / 2);
            d.invasion.unitsPerWaveMin = 6 + lvl;
            d.invasion.unitsPerWaveMax = 10 + (lvl * 2);

            d.air.aircraftCountMin = (lvl >= 4) ? 1 : 0;
            d.air.aircraftCountMax = (lvl >= 6) ? 2 : d.air.aircraftCountMin;
            d.air.payloadStrengthMin = (lvl >= 5) ? 1 : 0;
            d.air.payloadStrengthMax = (lvl >= 8) ? 2 : d.air.payloadStrengthMin;

            // Example default pools (replace with real IDs/shortNames)
            d.weaponItemIds.add("minecraft:bow");
            d.weaponItemIds.add("minecraft:iron_sword");
            if (lvl >= 3) d.weaponItemIds.add("flansmod:ak47");
            if (lvl >= 5) d.weaponItemIds.add("techguns:smg");
            if (lvl >= 7) d.weaponItemIds.add("techguns:assault_rifle");
            if (lvl >= 9) d.weaponItemIds.add("flansmod:m4");

            d.vehicleShortNames.add("jeep");
            if (lvl >= 4) d.vehicleShortNames.add("halftrack");
            if (lvl >= 6) d.vehicleShortNames.add("t34");
            if (lvl >= 8) d.vehicleShortNames.add("abrams");
            if (lvl >= 7) d.vehicleShortNames.add("bf109");

            // Siege machines: empty by default; modpack authors fill this in.
            // Examples:
            //  - "ballista" (AW2 VehicleType name)
            //  - "trebuchet" (AW2 VehicleType name)
            //  - "ancientwarfarevehicle:vehicle" (entity registry id, if you have custom entities)
            d.siegeMachineTypes = new ArrayList<>();

            // Example EMC budget scaling (optional)
            d.raid.emcBudgetToSteal = 256L * lvl * lvl;

            d.sanitize(lvl);
            arr[i] = d;
        }

        return arr;
    }

    // ======================================================================
    // LEGACY / CONVENIENCE ACCESSORS
    // ======================================================================

    /**
     * Convenience view used by older raid systems.
     * This is derived from the per-level tables (LevelData.raid + LevelData.weaponItemIds).
     * It is NOT separately serialized.
     */
    public static final class RaidConfig {
        public final int level;
        public final int infantryCount;
        public final int rangedCount;
        public final String[] weapons;
        public final long emcBudgetToSteal;

        private RaidConfig(int level, int infantryCount, int rangedCount, String[] weapons, long emcBudgetToSteal) {
            this.level = level;
            this.infantryCount = infantryCount;
            this.rangedCount = rangedCount;
            this.weapons = weapons;
            this.emcBudgetToSteal = emcBudgetToSteal;
        }

        /**
         * Rolls the EMC loot budget this raid should attempt to steal.
         * - If emcBudgetToSteal is > 0, clamps it to the player's EMC so we don't exceed inventory reality.
         * - Otherwise, uses a conservative percentage of player EMC.
         */
        public long rollLootBudget(int playerEmc) {
            long player = Math.max(0L, (long) playerEmc);
            if (emcBudgetToSteal > 0L) {
                return Math.max(1L, Math.min(player, emcBudgetToSteal));
            }
            long pct = Math.max(250L, (long) (player * 0.35));
            return Math.max(1L, pct);
        }
    }

    /**
     * Older code expects a RaidConfig per level.
     */
    public static RaidConfig getRaidConfig(int level) {
        LevelData d = getLevel(level);
        if (d == null) return null;

        int min = Math.max(0, d.raid.minUnits);
        int max = Math.max(min, d.raid.maxUnits);

        int total = (min + max) / 2;
        if (total <= 0) total = 6;

        int ranged = Math.max(0, (int) Math.round(total * 0.30));
        int infantry = Math.max(0, total - ranged);

        String[] weapons = (d.weaponItemIds == null || d.weaponItemIds.isEmpty())
                ? new String[] { "minecraft:iron_sword", "minecraft:bow" }
                : d.weaponItemIds.toArray(new String[0]);

        return new RaidConfig(d.level, infantry, ranged, weapons, Math.max(0L, d.raid.emcBudgetToSteal));
    }
}
