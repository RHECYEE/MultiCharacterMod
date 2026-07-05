package studio.ERM.war.BattleManagers.cards;

import java.util.Collections;
import java.util.EnumMap;
import java.util.Map;

/**
 * A UnitCard is the director-level object bought with reinforcement points.
 * It describes the size, spacing, and role weights that drive slot payload selection.
 */
public class UnitCard {

    private final String name;
    private final int slotCount;
    private final SpacingProfile spacingProfile;
    private final boolean supportsVolley;

    private final EnumMap<UnitRole, Integer> roleWeights;

    public UnitCard(String name,
                    int slotCount,
                    SpacingProfile spacingProfile,
                    boolean supportsVolley,
                    Map<UnitRole, Integer> roleWeights) {
        this.name = name == null ? "" : name.trim();
        this.slotCount = Math.max(1, slotCount);
        this.spacingProfile = spacingProfile == null ? SpacingProfile.LOOSE_SWARM : spacingProfile;
        this.supportsVolley = supportsVolley;

        EnumMap<UnitRole, Integer> tmp = new EnumMap<>(UnitRole.class);
        if (roleWeights != null) {
            for (Map.Entry<UnitRole, Integer> e : roleWeights.entrySet()) {
                if (e.getKey() == null) continue;
                tmp.put(e.getKey(), Math.max(0, e.getValue() == null ? 0 : e.getValue()));
            }
        }
        this.roleWeights = tmp;
    }

    public String getName() {
        return name;
    }

    public int getSlotCount() {
        return slotCount;
    }

    public SpacingProfile getSpacingProfile() {
        return spacingProfile;
    }

    public boolean supportsVolley() {
        return supportsVolley;
    }

    public Map<UnitRole, Integer> getRoleWeights() {
        return Collections.unmodifiableMap(roleWeights);
    }
}
