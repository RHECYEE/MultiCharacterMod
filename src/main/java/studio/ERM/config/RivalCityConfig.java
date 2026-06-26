package studio.ERM.config;

/**
 * External configuration bridge for rival city Flans vehicle settings.
 * Referenced by RivalCityManager and RivalCitySpawner.
 */
public class RivalCityConfig {

    public static boolean enableFlansVehicles = true;
    public static int flansVehiclesStartLevel = 3;
    public static int flansVehiclesPassive = 2;
    public static int flansVehiclesPerGrowth = 2;
    public static boolean validateFlansTypeBeforeSpawn = true;
    public static boolean enableVehicleVsVehicle = true;

    /** Pipe-delimited Flans vehicle shortnames per level index (0-based). */
    public static String[] flansVehiclesByLevel = {
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
}
