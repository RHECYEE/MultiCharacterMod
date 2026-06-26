package studio.ERM.war.rival;

import net.minecraft.util.math.BlockPos;

/**
 * RivalCityConfig - Configuration constants for the rival city system.
 *
 * Extracted from the monolithic RivalCityManager for better organization.
 * These values can be modified at runtime or via config files.
 */
public class RivalCityConfig {

    // =====================================================================
    // SPAWN & GROWTH CONFIGURATION
    // =====================================================================

    public static int minSpawnDistance = 300;
    public static int maxSpawnDistance = 600;
    public static int npcSpawnIntervalTicks = 2400;
    public static int growthIntervalTicks = 6000;
    public static float growthRate = 1.0f;
    public static int maxRivalLevel = 10;
    public static int maxLevel = 10;
    public static int npcsPerLevel = 3;

    // =====================================================================
    // RING/DENSITY CONFIGURATION
    // =====================================================================

    public static int baseRingStep = 24;
    public static int ringsPerGrowthBase = 3;
    public static int ringsPerGrowthScale = 1;
    public static int maxStructureAttemptsPerGrow = 350;

    // =====================================================================
    // DENSITY BACKFILL
    // =====================================================================

    public static int minStructuresPerRing = 8;
    public static float backfillProbability = 0.4f;
    public static int backfillMaxAttempts = 80;

    // =====================================================================
    // GRID CITY CONFIGURATION
    // =====================================================================

    public static int plotSize = 20;
    public static int roadWidth = 4;
    public static int plotSpacing = plotSize + roadWidth;
    public static int majorRoadEvery = 4;
    public static int maxPlotsPerGrowth = 14;
    public static float backfillEmptyPlotChance = 0.55f;
    public static int backfillEmptyPlotAttempts = 28;

    // =====================================================================
    // ROAD CONFIGURATION
    // =====================================================================

    public static int mainRoadWidth = 3;
    public static int sideRoadWidth = 2;
    public static int maxRoadConnections = 4;

    // =====================================================================
    // SITE PLACEMENT CONFIGURATION
    // =====================================================================

    public static final int MAX_SITE_GRADE = 6;
    public static final int MAX_FILL_HEIGHT = 4;
    public static final int LOT_BUFFER_BLOCKS = 2;
    public static final int MAX_ROOF_SCAN_DOWN = 28;
    public static final int NATURAL_SURFACE_SCAN = 2;

    // =====================================================================
    // DESTRUCTION/REDEVELOPMENT CONFIGURATION
    // =====================================================================

    public static final float DESTROYED_THRESHOLD = 0.35f;
    public static final int REDEVELOP_COOLDOWN_TICKS = 20 * 60 * 2; // 2 minutes

    // =====================================================================
    // CORE PROTECTION CONFIGURATION
    // =====================================================================

    public static int coreExclusionRadius = 40;
    public static boolean generateCoreMoat = true;
    public static int moatRadius = 30;
    public static int moatWidth = 4;
    public static int moatDepth = 5;
    public static boolean generateMoatBridges = true;
    public static int bridgeWidth = 3;
    public static boolean alignMainStreetToBridge = true;

    // =====================================================================
    // CITY ZONES (for main street generation)
    // =====================================================================

    public enum CityZone {
        CORE(0, 40),
        MAIN_STREET(40, 120),
        RESIDENTIAL(80, 200),
        INDUSTRIAL(150, 300);

        public final int innerRadius;
        public final int outerRadius;

        CityZone(int inner, int outer) {
            this.innerRadius = inner;
            this.outerRadius = outer;
        }
    }

    // =====================================================================
    // HEAL CONFIGURATION
    // =====================================================================

    private static final ConfigData CONFIG = new ConfigData();

    public static ConfigData get() {
        return CONFIG;
    }

    public static class ConfigData {
        public int healMaxPlotsPerRun = 5;
        public int healRadiusBlocks = 8;
        public int healFillDepth = 4;
        public int healClearAbove = 10;
    }

    // =====================================================================
    // CARRIER PAYLOAD CONFIGURATION (for UnitCompositionResolver)
    // =====================================================================

    public static String carrierMeleePayload = "aw2:soldier";
    public static String carrierRangedPayload = "aw2:archer";
    public static String carrierHeavyPayload = "aw2:elite";
    public static String carrierSpecialPayload = "aw2:leader";

    /**
     * Get the carrier payload string for a given war level and role.
     */
    public static String getCarrierPayload(int warLevel, String roleName) {
        if (roleName == null) return carrierMeleePayload;
        switch (roleName.toUpperCase()) {
            case "MELEE":   return carrierMeleePayload;
            case "RANGED":  return carrierRangedPayload;
            case "HEAVY":   return carrierHeavyPayload;
            case "SPECIAL": return carrierSpecialPayload;
            default:        return carrierMeleePayload;
        }
    }

    // =====================================================================
    // UTILITY METHODS
    // =====================================================================

    // =====================================================================
    // PATROL / GARRISON CONFIGURATION
    // =====================================================================

    /** Number of pilots per foot-patrol squad. */
    public static int PATROL_SQUAD_SIZE = 3;

    /** City level at which garrison vehicles start appearing. */
    public static int GARRISON_START_LEVEL = 3;

    /** Extra blend radius (blocks) used when smoothing flattened terrain edges. */
    public static int FLATTEN_SMOOTH_RADIUS = 8;

    /** Comma-separated patrol weapon registry names per 0-based level index. */
    public static String[] patrolWeaponsByLevel = {
            "minecraft:wooden_sword",                 // Level 1
            "minecraft:stone_sword",                  // Level 2
            "minecraft:stone_sword,minecraft:bow",    // Level 3
            "minecraft:iron_sword,minecraft:bow",     // Level 4
            "minecraft:iron_sword,minecraft:bow",     // Level 5
            "minecraft:iron_sword,minecraft:bow",     // Level 6
            "minecraft:diamond_sword,minecraft:bow",  // Level 7
            "minecraft:diamond_sword,minecraft:bow",  // Level 8
            "minecraft:diamond_sword,minecraft:bow",  // Level 9
            "minecraft:diamond_sword,minecraft:bow"   // Level 10
    };

    /** Pipe-delimited Flans garrison vehicle shortnames per 0-based level index. */
    public static String[] garrisonVehiclesByLevel = {
            "jeep",                  // Level 1
            "jeep",                  // Level 2
            "jeep|gaz",              // Level 3
            "jeep|gaz|halftrack",    // Level 4
            "gaz|halftrack|panzer4", // Level 5
            "halftrack|panzer4",     // Level 6
            "panzer4|tiger",         // Level 7
            "tiger|t34",             // Level 8
            "tiger|t34|m1a1",        // Level 9
            "m1a1|t90"               // Level 10
    };

    /** Weapon registry names available to patrols at the given city level. */
    public static String[] getPatrolWeaponsForLevel(int level) {
        int idx = Math.max(0, Math.min(patrolWeaponsByLevel.length - 1, level - 1));
        String entry = patrolWeaponsByLevel[idx];
        if (entry == null || entry.isEmpty()) return new String[0];
        return entry.split(",");
    }

    /** Flans vehicle shortnames available to garrisons at the given city level. */
    public static String[] getGarrisonVehiclesForLevel(int level) {
        if (level < GARRISON_START_LEVEL) return new String[0];
        int idx = Math.max(0, Math.min(garrisonVehiclesByLevel.length - 1, level - 1));
        String entry = garrisonVehiclesByLevel[idx];
        if (entry == null || entry.isEmpty()) return new String[0];
        return entry.split("\\|");
    }

    /**
     * Recalculate derived values after config changes.
     */
    public static void recalculateDerived() {
        plotSpacing = plotSize + roadWidth;
    }

    /**
     * Check if a position is inside the core exclusion zone.
     */
    public static boolean isInCoreExclusionZone(BlockPos pos, BlockPos cityCenter) {
        if (pos == null || cityCenter == null) return false;
        double dx = pos.getX() - cityCenter.getX();
        double dz = pos.getZ() - cityCenter.getZ();
        double dist = Math.sqrt(dx * dx + dz * dz);
        return dist < coreExclusionRadius;
    }

    /**
     * Apply density settings based on city level.
     * Higher levels get tighter plots and stronger infill.
     */
    public static void applyDensityByLevel(RivalCityState state) {
        if (state == null) return;

        int lvl = Math.max(1, Math.min(10, state.level));

        // Plot size shrinks from ~20 -> ~14 as level rises
        int plot = plotSize - (lvl - 1);
        plot = Math.max(14, plot);

        // Roads shrink from 4 -> 2
        int road = roadWidth;
        if (lvl >= 6) road = 3;
        if (lvl >= 9) road = 2;

        state.gridPlotSize = plot;
        state.gridRoadWidth = road;
        state.gridSpacing = plot + road;

        // Tune global backfill settings for this level
        minStructuresPerRing = 6 + (lvl * 2);
        backfillProbability = Math.min(0.85f, 0.35f + (lvl * 0.05f));
        backfillMaxAttempts = 50 + (lvl * 12);
    }
}
