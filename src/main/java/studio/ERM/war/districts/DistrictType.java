package studio.ERM.war.districts;

/**
 * District types used by the war system.
 *
 * The enum constants serve double duty:
 *  - They are stored on TileEntityDistrictMarker to label what kind of district it is.
 *  - Citizens carry a "current job" that corresponds to a district type.
 *
 * Some legacy code used aliases (AGRI, BUILDER, MILITARY). The canonical constants
 * are POWER, AGRICULTURE, RESOURCE, DEFENSE. The aliases forward to those via
 * {@link #getCanonical()}.
 */
public enum DistrictType {

    NONE,
    POWER,
    AGRICULTURE,
    RESOURCE,
    DEFENSE,
    INDUSTRY,

    // Aliases (kept for backwards compatibility)
    AGRI,
    BUILDER,
    MILITARY;

    /**
     * Effectiveness multiplier during the given event state.
     */
    public float getEffectiveness(EventState state) {
        if (state == null) return 1.0f;
        switch (state) {
            case ALERT:     return 0.9f;
            case RAID:      return 0.7f;
            case INVASION:  return 0.5f;
            case BATTLE:    return 0.3f;
            default:        return 1.0f;
        }
    }

    /**
     * Returns the canonical district type for aliases.
     * AGRI -> AGRICULTURE, BUILDER -> RESOURCE, MILITARY -> DEFENSE.
     */
    public DistrictType getCanonical() {
        switch (this) {
            case AGRI:     return AGRICULTURE;
            case BUILDER:  return RESOURCE;
            case MILITARY: return DEFENSE;
            default:       return this;
        }
    }

    public enum EventState {
        NORMAL,
        ALERT,
        RAID,
        INVASION,
        BATTLE
    }
}
