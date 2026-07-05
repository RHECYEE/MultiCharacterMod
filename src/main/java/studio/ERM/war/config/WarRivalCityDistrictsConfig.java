package studio.ERM.war.config;

/**
 * Rival-city district efficiency knobs.
 *
 * Owns the per-district production tuning for rival-city / war districts.
 * Mirrors the nested-{@code districts} accessor shape used by the other war
 * config classes for backward compatibility.
 */
public class WarRivalCityDistrictsConfig {

    private static final WarRivalCityDistrictsConfig INSTANCE = new WarRivalCityDistrictsConfig();

    public static WarRivalCityDistrictsConfig get() {
        return INSTANCE;
    }

    public final Districts districts = new Districts();

    public static class Districts {
        /** Base command-point production for a military district before worker scaling. */
        public int cpProductionBase = 5;
    }
}
