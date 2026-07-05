package studio.ERM.war.BattleManagers.directors;

import studio.ERM.war.BattleManagers.api.IBattleDirector;

import java.util.function.Supplier;

/**
 * Registry entry for a Battle Director type.
 * Contains metadata for display in the map GUI and a factory to create instances.
 */
public final class BattleDirectorEntry {

    private final String id;
    private final String displayName;
    private final String description;
    private final int cpCost;
    private final int minLevel;
    private final Supplier<IBattleDirector> factory;

    public BattleDirectorEntry(String id, String displayName, String description, 
                                int cpCost, int minLevel, Supplier<IBattleDirector> factory) {
        this.id = id;
        this.displayName = displayName;
        this.description = description;
        this.cpCost = cpCost;
        this.minLevel = minLevel;
        this.factory = factory;
    }

    /**
     * Unique identifier for this director type (e.g., "skirmish", "ambush", "siege")
     */
    public String getId() {
        return id;
    }

    /**
     * Human-readable name shown in the deployment menu
     */
    public String getDisplayName() {
        return displayName;
    }

    /**
     * Brief description of what this battle type involves
     */
    public String getDescription() {
        return description;
    }

    /**
     * Command Point cost to deploy this battle
     */
    public int getCpCost() {
        return cpCost;
    }

    /**
     * Minimum player era/level required to deploy this battle type
     */
    public int getMinLevel() {
        return minLevel;
    }

    /**
     * Creates a new instance of this director type
     */
    public IBattleDirector createDirector() {
        return factory.get();
    }

    @Override
    public String toString() {
        return "BattleDirectorEntry{" + id + ", cost=" + cpCost + "}";
    }
}
