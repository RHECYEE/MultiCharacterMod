package studio.ERM.war.rival;

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
    // UTILITY METHODS
    // =====================================================================

    /**
     * Recalculate derived values after config changes.
     */
    public static void recalculateDerived() {
        plotSpacing = plotSize + roadWidth;
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
