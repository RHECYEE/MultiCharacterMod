package studio.ERM.war.config;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import studio.ERM.EpochRunnerMod;
import studio.ERM.war.raid.SmartRaidSystem;
import studio.ERM.war.config.WarLevelsConfig;

import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.Reader;
import java.io.Writer;
import java.util.HashMap;
import java.util.Map;

/**
 * WAR MASTER CONFIG (V1)
 *
 * File: config/Homosapien/war_master.json
 *
 * Owns:
 *  - Global war behavior (timers, radii, aggression)
 *  - Economy rules (CP costs, incomes)
 *  - Raid behavior rules (targeting logic, limits)
 *  - Tension/escalation rules
 *  - Vehicle/fuel behavior (NOT per-level unlocks)
 *  - Territory rules (build restrictions, claim behavior)
 *  - Sabotage rules
 *  - AW2 integration knobs
 *  - Aircraft stats tuning
 *  - Debug flags
 *
 * Does NOT own:
 *  - Level tables -> WarLevelsConfig
 *  - Rival city + district efficiencies -> WarRivalCityDistrictsConfig
 *  - Citizen/NPC behavior & skins -> CitizenNpcSkinsConfig
 */
public final class WarMasterConfig {

    private static final String CONFIG_FILE = "Homosapien/war_master.json";

    private static final Gson GSON = new GsonBuilder()
            .setPrettyPrinting()
            .create();

    public static ConfigData data = new ConfigData();

    private WarMasterConfig() {}

    public static ConfigData get() {
        return data;
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
            reloadSmartRaidTargets();
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

        reloadSmartRaidTargets();
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

    private static void reloadSmartRaidTargets() {
        try {
            SmartRaidSystem.reloadTargetBlockConfig();
        } catch (Throwable t) {
            logWarn("[WAR-CONFIG] Could not reload SmartRaidSystem target blocks");
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
        public BattleSettings battle = new BattleSettings();
        public EconomySettings economy = new EconomySettings();
        public RaidSettings raids = new RaidSettings();
        public TensionSettings tension = new TensionSettings();
        public VehicleSettings vehicles = new VehicleSettings();
        public TerritorySettings territory = new TerritorySettings();
        public SabotageSettings sabotage = new SabotageSettings();
        public Aw2Settings aw2 = new Aw2Settings();
        public Map<String, AircraftStats> aircraftSettings = new HashMap<>();

        public boolean enableNarrator = true;
        public boolean narratorDryHumor = true;
        public boolean debugLogging = true;

        // ----------------------------------------------------------------------
        // Legacy direct-field access (kept for compile compatibility)
        // ----------------------------------------------------------------------

        // BattleManager direct reads
        public int battleEngageDelayTicks = 0;
        public int battleAutoStartChunkRadius = 0;
        public int battleEngagePlayerRadiusBlocks = 64;

        // Claim income handler direct reads
        public int claimIncomeIntervalTicks = 6000; // 5 minutes @ 20tps
        public int cpPerClaimIncome = 0;

        // Refugee manager / claims direct reads
        public int claimCostCP = 0;

        // ItemAirTargetDesignator direct reads
        public boolean enableAw2FactionAirSupportGate = false;
        public String aw2AirSupportFactionName = "bandit";
        public int aw2MinStandingForAirSupport = 0;

        // Smart raid target block lists
        public String[] smartRaidHighValueBlocks = new String[0];
        public String[] smartRaidPowerBlocks = new String[0];
        public String[] smartRaidDoorBlocks = new String[0];
        public String[] smartRaidStorageBlocks = new String[0];

        // Invasion settings direct reads
        public InvasionSettings invasion = new InvasionSettings();

        // Base spawner structure table direct reads
        public Map<Integer, java.util.List<StructureEntry>> baseStructuresByEra = new HashMap<>();

        private void sanitize() {
            if (battle == null) battle = new BattleSettings();
            if (economy == null) economy = new EconomySettings();
            if (raids == null) raids = new RaidSettings();
            if (tension == null) tension = new TensionSettings();
            if (vehicles == null) vehicles = new VehicleSettings();
            if (territory == null) territory = new TerritorySettings();
            if (sabotage == null) sabotage = new SabotageSettings();
            if (aw2 == null) aw2 = new Aw2Settings();
            if (aircraftSettings == null) aircraftSettings = new HashMap<>();
            if (smartRaidHighValueBlocks == null) smartRaidHighValueBlocks = new String[0];
            if (smartRaidPowerBlocks == null) smartRaidPowerBlocks = new String[0];
            if (smartRaidDoorBlocks == null) smartRaidDoorBlocks = new String[0];
            if (smartRaidStorageBlocks == null) smartRaidStorageBlocks = new String[0];
            if (invasion == null) invasion = new InvasionSettings();
            if (baseStructuresByEra == null) baseStructuresByEra = new HashMap<>();

            battle.sanitize();
            economy.sanitize();
            raids.sanitize();
            tension.sanitize();
            vehicles.sanitize();
            territory.sanitize();
            sabotage.sanitize();
            aw2.sanitize();

            invasion.sanitize();

            sanitizeBaseStructures();

            // Keep aircraft stats entries sane (no null values)
            Map<String, AircraftStats> cleaned = new HashMap<>();
            for (Map.Entry<String, AircraftStats> e : aircraftSettings.entrySet()) {
                if (e.getKey() == null) continue;
                String key = e.getKey().trim();
                if (key.isEmpty()) continue;
                AircraftStats v = e.getValue();
                if (v == null) v = new AircraftStats();
                v.sanitize();
                cleaned.put(key, v);
            }
            aircraftSettings = cleaned;
        }

        private void sanitizeBaseStructures() {
            if (baseStructuresByEra == null) {
                baseStructuresByEra = new HashMap<>();
                return;
            }
            Map<Integer, java.util.List<StructureEntry>> cleaned = new HashMap<>();
            for (Map.Entry<Integer, java.util.List<StructureEntry>> e : baseStructuresByEra.entrySet()) {
                if (e == null) continue;
                Integer era = e.getKey();
                if (era == null) continue;
                java.util.List<StructureEntry> list = e.getValue();
                if (list == null) continue;
                java.util.List<StructureEntry> out = new java.util.ArrayList<>();
                for (StructureEntry se : list) {
                    if (se == null) continue;
                    se.sanitize();
                    if (!se.isEmpty()) out.add(se);
                }
                if (!out.isEmpty()) cleaned.put(era, out);
            }
            baseStructuresByEra = cleaned;
        }

    }

    // ======================================================================
    // SETTINGS
    // ======================================================================

    public static final class BattleSettings {
        public int battleRequestCooldownTicks = 20 * 60 * 10; // 10 minutes
        public int battleStartDelayTicks = 20 * 30;           // 30 seconds
        public int battleResolutionRadius = 256;

        public int maxDenialsBeforeForced = 3;
        public int forcedBattleGraceDays = 7;

        public void sanitize() {
            if (battleRequestCooldownTicks < 20 * 10) battleRequestCooldownTicks = 20 * 10;
            if (battleStartDelayTicks < 0) battleStartDelayTicks = 0;
            if (battleResolutionRadius < 32) battleResolutionRadius = 32;
            if (maxDenialsBeforeForced < 0) maxDenialsBeforeForced = 0;
            if (forcedBattleGraceDays < 0) forcedBattleGraceDays = 0;
        }
    }

    public static final class EconomySettings {
        public int cpStartingBalance = 0;
        public int cpPerDayPassive = 0;

        public int claimChunkCost = 50;
        public int districtMarkerCost = 35;

        public void sanitize() {
            if (cpStartingBalance < 0) cpStartingBalance = 0;
            if (cpPerDayPassive < 0) cpPerDayPassive = 0;
            if (claimChunkCost < 0) claimChunkCost = 0;
            if (districtMarkerCost < 0) districtMarkerCost = 0;
        }
    }

    public static final class RaidSettings {
        public int raidCheckIntervalTicks = 20 * 60; // 60 seconds
        public int raidMinDaysBetween = 2;
        public int raidMaxDaysBetween = 5;

        public int hostileFleeRadius = 20;

        // Smart raid behavior rule knobs (tables are in WarLevelsConfig)
        public boolean targetHighEmcFirst = true;
        public float highEmcWeightBias = 2.5f;
        public long ignoreItemsBelowEmc = 0L;

        public void sanitize() {
            if (raidCheckIntervalTicks < 20 * 10) raidCheckIntervalTicks = 20 * 10;
            if (raidMinDaysBetween < 0) raidMinDaysBetween = 0;
            if (raidMaxDaysBetween < raidMinDaysBetween) raidMaxDaysBetween = raidMinDaysBetween;
            if (hostileFleeRadius < 4) hostileFleeRadius = 4;

            if (highEmcWeightBias < 1f) highEmcWeightBias = 1f;
            if (ignoreItemsBelowEmc < 0L) ignoreItemsBelowEmc = 0L;
        }
    }

    public static final class TensionSettings {
        public int tensionIncreasePerDeniedBattle = 8;
        public int tensionIncreasePerRaid = 2;
        public int tensionDecayPerDay = 1;

        public int tensionThresholdInvasion = 70;
        public int tensionThresholdWar = 90;

        public void sanitize() {
            if (tensionIncreasePerDeniedBattle < 0) tensionIncreasePerDeniedBattle = 0;
            if (tensionIncreasePerRaid < 0) tensionIncreasePerRaid = 0;
            if (tensionDecayPerDay < 0) tensionDecayPerDay = 0;

            if (tensionThresholdInvasion < 0) tensionThresholdInvasion = 0;
            if (tensionThresholdWar < 0) tensionThresholdWar = 0;
        }
    }

    public static final class VehicleSettings {
        public String fuelItemId = "minecraft:coal";
        public int fuelSearchRadius = 12;
        public int fuelCheckIntervalTicks = 40;
        public float fuelEfficiency = 1.0f;

        public void sanitize() {
            if (fuelItemId == null) fuelItemId = "minecraft:coal";
            fuelItemId = fuelItemId.trim();
            if (fuelItemId.isEmpty()) fuelItemId = "minecraft:coal";

            if (fuelSearchRadius < 1) fuelSearchRadius = 1;
            if (fuelCheckIntervalTicks < 1) fuelCheckIntervalTicks = 1;
            if (fuelEfficiency <= 0f) fuelEfficiency = 1.0f;
        }
    }

    public static final class TerritorySettings {
        public boolean enableBuildingRestrictions = true;
        public boolean allowBreakingInUnclaimed = true;

        public void sanitize() {
            // booleans are inherently valid
        }
    }

    public static final class SabotageSettings {
        public int sabotageCheckIntervalTicks = 20 * 60; // 60 seconds
        public int sabotageMinDaysBetween = 4;
        public int sabotageMaxDaysBetween = 10;

        public void sanitize() {
            if (sabotageCheckIntervalTicks < 20 * 10) sabotageCheckIntervalTicks = 20 * 10;
            if (sabotageMinDaysBetween < 0) sabotageMinDaysBetween = 0;
            if (sabotageMaxDaysBetween < sabotageMinDaysBetween) sabotageMaxDaysBetween = sabotageMinDaysBetween;
        }
    }

    public static final class Aw2Settings {
        public boolean enableAw2Integration = true;
        public String aw2FactionName = "bandit";

        public void sanitize() {
            if (aw2FactionName == null) aw2FactionName = "bandit";
            aw2FactionName = aw2FactionName.trim();
            if (aw2FactionName.isEmpty()) aw2FactionName = "bandit";
        }
    }

    public static final class AircraftStats {
        public float health = 1.0f;
        public float speed = 1.0f;
        public float turn = 1.0f;
        public float damage = 1.0f;

        // Optional overrides used by EntityGhostAircraft.
        // 0 means "use the entity's default".
        public int bombsRemaining = 0;
        public int maxMissiles = 0;
        public float bombDamage = 0.0f;
        public float aimAccuracy = 0.0f;

        public void sanitize() {
            if (health <= 0f) health = 1.0f;
            if (speed <= 0f) speed = 1.0f;
            if (turn <= 0f) turn = 1.0f;
            if (damage <= 0f) damage = 1.0f;
            if (bombsRemaining < 0) bombsRemaining = 0;
            if (maxMissiles < 0) maxMissiles = 0;
            if (bombDamage < 0.0f) bombDamage = 0.0f;
            if (aimAccuracy < 0.0f) aimAccuracy = 0.0f;
        }
    }

    public static final class InvasionSettings {
        // If true, players cannot sleep in beds while an invasion is active.
        public boolean blockSleepingDuringInvasion = true;

        // Optional sacrifice item to skip the next invasion tick.
        public String sacrificeItemId = "minecraft:diamond";
        public int sacrificeItemCount = 1;

        // Scheduling (in Minecraft days). The handler will pick a random value in this range.
        public int minDaysBetweenInvasions = 4;
        public int maxDaysBetweenInvasions = 10;

        public void sanitize() {
            if (sacrificeItemId == null) sacrificeItemId = "minecraft:diamond";
            if (sacrificeItemCount < 0) sacrificeItemCount = 0;
            if (minDaysBetweenInvasions < 0) minDaysBetweenInvasions = 0;
            if (maxDaysBetweenInvasions < minDaysBetweenInvasions) maxDaysBetweenInvasions = minDaysBetweenInvasions;
        }
    }

    /**
     * Template/command entry for world/base spawns.
     * This used to live in WarConfigManager world.json; it now lives in war_master.json.
     */
    public static final class StructureEntry {
        public String fileName = ""; // NBT / schematic file under /structures
        public String command = "";  // server command (e.g., /summonvehicle t34)
        public int count = 1;          // number of spawns to attempt
        public int weight = 10;        // weighted random selection

        public void sanitize() {
            if (fileName == null) fileName = "";
            if (command == null) command = "";
            if (count < 1) count = 1;
            if (weight < 0) weight = 0;
        }

        public boolean isEmpty() {
            return (fileName.trim().isEmpty() && command.trim().isEmpty());
        }
    }

    // ======================================================================
    // LEGACY HELPERS (NO EXTRA CONFIG FILES)
    // ======================================================================

    /**
     * Legacy: callers used to grab battle weapon pool via WarMasterConfig.
     * The pool lives in WarLevelsConfig now.
     */
    public static String[] getBattleWeaponPool(int level) {
        return WarLevelsConfig.getBattleWeaponPool(level);
    }

    /**
     * Exposes the live config root while keeping old direct field access working.
     */
    public static ConfigData getDataUnsafe() {
        return data;
    }
}
