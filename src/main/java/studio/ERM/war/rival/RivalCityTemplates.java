package studio.ERM.war.rival;

import java.util.Random;

/**
 * Provides template names for rival city buildings by level.
 */
public class RivalCityTemplates {

    private static final String[] LOW_LEVEL = {"HOUSING", "FACTORY", "SILO"};
    private static final String[] MID_LEVEL = {"HOUSING", "FACTORY", "SILO", "SKYSCRAPER", "FORT"};
    private static final String[] HIGH_LEVEL = {"SKYSCRAPER", "FACTORY", "FORT", "COOLING_TOWER", "ANTENNA"};

    /**
     * Pick a random template name appropriate for the given city level.
     */
    public static String pickNextTemplate(int level, Random rand) {
        String[] pool;
        if (level <= 3) {
            pool = LOW_LEVEL;
        } else if (level <= 6) {
            pool = MID_LEVEL;
        } else {
            pool = HIGH_LEVEL;
        }
        return pool[rand.nextInt(pool.length)];
    }
}
