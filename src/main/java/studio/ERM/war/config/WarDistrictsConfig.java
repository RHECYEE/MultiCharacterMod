package studio.ERM.war.config;

import java.util.*;

/**
 * Configuration for player-owned district system.
 * Districts are economic/production zones for players, NOT related to rival cities.
 */
public class WarDistrictsConfig {
    
    // ========== DISTRICT TYPES ==========
    
    public enum DistrictType {
        RESIDENTIAL("residential", "Housing and population"),
        COMMERCIAL("commercial", "Trade and commerce"),
        INDUSTRIAL("industrial", "Production and manufacturing"),
        MILITARY("military", "Defense and training"),
        POWER("power", "Energy generation"),
        AGRICULTURAL("agricultural", "Food production"),
        RESEARCH("research", "Technology and research");
        
        public final String id;
        public final String description;
        
        DistrictType(String id, String description) {
            this.id = id;
            this.description = description;
        }
        
        public static DistrictType fromId(String id) {
            for (DistrictType type : values()) {
                if (type.id.equalsIgnoreCase(id)) {
                    return type;
                }
            }
            return null;
        }
    }
    
    // ========== DISTRICT SETTINGS ==========
    
    /** Enable district system */
    public static boolean DISTRICTS_ENABLED = true;
    
    /** Max districts per player */
    public static int MAX_DISTRICTS_PER_PLAYER = 10;
    
    /** Radius of a district (in blocks) */
    public static int DISTRICT_RADIUS = 32;
    
    /** Min distance between districts */
    public static int MIN_DISTRICT_SPACING = 64;
    
    /** Allow overlapping districts */
    public static boolean ALLOW_DISTRICT_OVERLAP = false;
    
    // ========== BUILDER SETTINGS ==========
    
    /** Builder work cooldown (ticks before completing repair) */
    public static int BUILDER_BASE_COOLDOWN = 100;
    
    /** Builder search range for repair orders */
    public static double BUILDER_RANGE = 64.0;
    
    /** Base CP production rate */
    public static double CP_PRODUCTION_BASE = 1.0;
    
    // ========== PRODUCTION SETTINGS ==========
    
    /** Base production rate per district tick */
    public static double BASE_PRODUCTION_RATE = 1.0;
    
    /** Ticks between district production updates */
    public static int PRODUCTION_UPDATE_INTERVAL = 100; // 5 seconds
    
    /** Max production storage per district */
    public static int MAX_DISTRICT_STORAGE = 10000;
    
    // ========== RESIDENTIAL DISTRICT ==========
    
    /** Population per residential building */
    public static int POPULATION_PER_BUILDING = 10;
    
    /** Max population per residential district */
    public static int MAX_RESIDENTIAL_POPULATION = 500;
    
    /** Housing production rate (CP per tick) */
    public static double RESIDENTIAL_CP_RATE = 0.5;
    
    // ========== COMMERCIAL DISTRICT ==========
    
    /** Trade income multiplier */
    public static double COMMERCIAL_INCOME_MULTIPLIER = 1.5;
    
    /** Commerce production rate (money per tick) */
    public static double COMMERCIAL_MONEY_RATE = 2.0;
    
    // ========== INDUSTRIAL DISTRICT ==========
    
    /** Industry production rate (resources per tick) */
    public static double INDUSTRIAL_PRODUCTION_RATE = 3.0;
    
    /** Power consumption per industrial district */
    public static int INDUSTRIAL_POWER_CONSUMPTION = 50;
    
    // ========== MILITARY DISTRICT ==========
    
    /** Defense bonus per military district */
    public static double MILITARY_DEFENSE_BONUS = 0.1; // 10% per district
    
    /** Training speed multiplier */
    public static double MILITARY_TRAINING_SPEED = 1.5;
    
    /** Spawn rate for garrison troops */
    public static int MILITARY_GARRISON_SPAWN_RATE = 200; // ticks
    
    // ========== POWER DISTRICT ==========
    
    /** Power generation per power district */
    public static int POWER_GENERATION_AMOUNT = 100;
    
    /** Power transmission range (blocks) */
    public static int POWER_TRANSMISSION_RANGE = 128;
    
    // ========== AGRICULTURAL DISTRICT ==========
    
    /** Food production rate */
    public static double AGRICULTURAL_FOOD_RATE = 2.5;
    
    /** Bonus crop growth speed in district */
    public static double AGRICULTURAL_GROWTH_MULTIPLIER = 2.0;
    
    // ========== RESEARCH DISTRICT ==========
    
    /** Research points per tick */
    public static double RESEARCH_POINTS_RATE = 1.0;
    
    /** Experience bonus for nearby players */
    public static double RESEARCH_XP_MULTIPLIER = 1.25;
    
    // ========== DISTRICT REWARDS ==========
    
    /** CP reward for completing district building */
    public static int DISTRICT_COMPLETION_CP_REWARD = 100;
    
    /** CP reward per production cycle */
    public static int DISTRICT_PRODUCTION_CP_REWARD = 10;
    
    /** Money reward per production cycle */
    public static int DISTRICT_PRODUCTION_MONEY_REWARD = 50;
    
    // ========== DISTRICT UPGRADE SYSTEM ==========
    
    /** Enable district upgrades */
    public static boolean ENABLE_DISTRICT_UPGRADES = true;
    
    /** Max upgrade level */
    public static int MAX_UPGRADE_LEVEL = 5;
    
    /** Upgrade cost multiplier per level */
    public static double UPGRADE_COST_MULTIPLIER = 1.5;
    
    /** Production bonus per upgrade level */
    public static double UPGRADE_PRODUCTION_BONUS = 0.2; // 20% per level
    
    // ========== DISTRICT REQUIREMENTS ==========
    
    /** Resources required to build each district type */
    public static final Map<DistrictType, Map<String, Integer>> DISTRICT_BUILD_COSTS = new HashMap<>();
    
    static {
        // Residential
        Map<String, Integer> residentialCost = new HashMap<>();
        residentialCost.put("wood", 100);
        residentialCost.put("stone", 50);
        DISTRICT_BUILD_COSTS.put(DistrictType.RESIDENTIAL, residentialCost);
        
        // Commercial
        Map<String, Integer> commercialCost = new HashMap<>();
        commercialCost.put("wood", 75);
        commercialCost.put("stone", 75);
        commercialCost.put("gold", 50);
        DISTRICT_BUILD_COSTS.put(DistrictType.COMMERCIAL, commercialCost);
        
        // Industrial
        Map<String, Integer> industrialCost = new HashMap<>();
        industrialCost.put("stone", 150);
        industrialCost.put("iron", 100);
        DISTRICT_BUILD_COSTS.put(DistrictType.INDUSTRIAL, industrialCost);
        
        // Military
        Map<String, Integer> militaryCost = new HashMap<>();
        militaryCost.put("stone", 100);
        militaryCost.put("iron", 75);
        militaryCost.put("wood", 50);
        DISTRICT_BUILD_COSTS.put(DistrictType.MILITARY, militaryCost);
        
        // Power
        Map<String, Integer> powerCost = new HashMap<>();
        powerCost.put("iron", 100);
        powerCost.put("redstone", 50);
        powerCost.put("coal", 100);
        DISTRICT_BUILD_COSTS.put(DistrictType.POWER, powerCost);
        
        // Agricultural
        Map<String, Integer> agriculturalCost = new HashMap<>();
        agriculturalCost.put("wood", 50);
        agriculturalCost.put("dirt", 100);
        agriculturalCost.put("seeds", 25);
        DISTRICT_BUILD_COSTS.put(DistrictType.AGRICULTURAL, agriculturalCost);
        
        // Research
        Map<String, Integer> researchCost = new HashMap<>();
        researchCost.put("stone", 75);
        researchCost.put("gold", 50);
        researchCost.put("books", 25);
        DISTRICT_BUILD_COSTS.put(DistrictType.RESEARCH, researchCost);
    }
    
    // ========== SINGLETON ACCESS ==========
    
    private static final WarDistrictsConfig INSTANCE = new WarDistrictsConfig();
    
    public static WarDistrictsConfig get() {
        return INSTANCE;
    }
    
    // Nested districts config for backward compatibility
    public final Districts districts = new Districts();
    
    public static class Districts {
        public int builderBaseCooldown = BUILDER_BASE_COOLDOWN;
        public double builderRange = BUILDER_RANGE;
        public int cpProductionBase = (int)CP_PRODUCTION_BASE;
        
        // Power district settings
        public int rfExportThreshold = 1000; // RF needed to export
        public float rfMultiplierPerNPC = 0.1f; // 10% bonus per NPC
        public int cpPerExportBatch = 5; // CP gained per export
        
        // Expertise settings
        public int expertiseBaseRate = 2400; // Base rate in ticks (2 minutes)
    }
    
    // ========== HELPER METHODS ==========
    
    /**
     * Check if districts are enabled.
     */
    public static boolean isEnabled() {
        return DISTRICTS_ENABLED;
    }
    
    /**
     * Get production rate for a district type.
     */
    public static double getProductionRate(DistrictType type) {
        switch (type) {
            case RESIDENTIAL: return RESIDENTIAL_CP_RATE;
            case COMMERCIAL: return COMMERCIAL_MONEY_RATE;
            case INDUSTRIAL: return INDUSTRIAL_PRODUCTION_RATE;
            case AGRICULTURAL: return AGRICULTURAL_FOOD_RATE;
            case RESEARCH: return RESEARCH_POINTS_RATE;
            default: return BASE_PRODUCTION_RATE;
        }
    }
    
    /**
     * Get build cost for a district type.
     */
    public static Map<String, Integer> getBuildCost(DistrictType type) {
        return DISTRICT_BUILD_COSTS.getOrDefault(type, new HashMap<>());
    }
    
    /**
     * Calculate upgrade cost for a district.
     */
    public static Map<String, Integer> getUpgradeCost(DistrictType type, int currentLevel) {
        if (!ENABLE_DISTRICT_UPGRADES || currentLevel >= MAX_UPGRADE_LEVEL) {
            return new HashMap<>();
        }
        
        Map<String, Integer> baseCost = getBuildCost(type);
        Map<String, Integer> upgradeCost = new HashMap<>();
        
        double multiplier = Math.pow(UPGRADE_COST_MULTIPLIER, currentLevel);
        
        for (Map.Entry<String, Integer> entry : baseCost.entrySet()) {
            upgradeCost.put(entry.getKey(), (int) (entry.getValue() * multiplier));
        }
        
        return upgradeCost;
    }
    
    /**
     * Calculate production bonus from upgrade level.
     */
    public static double getProductionBonus(int upgradeLevel) {
        return 1.0 + (upgradeLevel * UPGRADE_PRODUCTION_BONUS);
    }
}
