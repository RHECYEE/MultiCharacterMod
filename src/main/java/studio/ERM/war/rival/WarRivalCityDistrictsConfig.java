package studio.ERM.war.config;

/**
 * Stub class for compatibility.
 * District configuration is now handled by RivalCityConfig.
 */
public class WarRivalCityDistrictsConfig {
    
    public static class DistrictType {
        public static final String RESIDENTIAL = "residential";
        public static final String COMMERCIAL = "commercial";
        public static final String INDUSTRIAL = "industrial";
        public static final String MILITARY = "military";
    }
    
    public static boolean isEnabled() {
        return false; // Disabled - using new system
    }
}
