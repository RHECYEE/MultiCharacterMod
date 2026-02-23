package studio.ERM.war.BattleManagers.cards;

import java.util.Collections;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.Map;

/**
 * v7 — Full card registry with infantry, vehicle, siege, and elite variants.
 *
 * Previously only "ShieldWall" and "SkirmishLine" existed, but the director
 * registry referenced "Phalanx", "HeavyInfantry", "SiegeUnit" which were never
 * registered → null cards → no puppets spawned.
 *
 * Cards registered:
 *   ShieldWall      - 12 melee-heavy, tight formation
 *   SkirmishLine    - 12 ranged-heavy, loose, supports volley
 *   Phalanx         - 16 heavy-melee, very tight, slow
 *   HeavyInfantry   - 14 balanced melee+heavy
 *   LightCavalry    - 6 fast ranged (vehicle slots at high level)
 *   SiegeUnit       - 8 heavy+special, ring formation (siege engineers)
 *   EliteSquad      - 6 all-heavy, tight ring
 *   MixedCompany    - 16 balanced all roles
 *   VehiclePlatoon  - 4 vehicle-sized, wide spacing
 *   ScoutTeam       - 4 ranged, very loose
 */
public final class UnitCardRegistry {

    private static final Map<String, UnitCard> CARDS = new HashMap<>();

    static {
        registerDefaults();
    }

    private UnitCardRegistry() {}

    private static void registerDefaults() {
        // ── BASIC INFANTRY ──

        register(new UnitCard("ShieldWall", 12, SpacingProfile.SHIELD_WALL, false,
                weights(80, 15, 5, 0)));

        register(new UnitCard("SkirmishLine", 12, SpacingProfile.LOOSE_SWARM, true,
                weights(35, 60, 5, 0)));

        register(new UnitCard("Phalanx", 16, SpacingProfile.SHIELD_WALL, false,
                weights(60, 10, 25, 5)));

        register(new UnitCard("HeavyInfantry", 14, SpacingProfile.SHIELD_WALL, false,
                weights(45, 15, 35, 5)));

        // ── SPECIALIZED ──

        register(new UnitCard("LightCavalry", 6, SpacingProfile.LOOSE_SWARM, true,
                weights(20, 60, 10, 10)));

        register(new UnitCard("SiegeUnit", 8, SpacingProfile.RING, false,
                weights(15, 15, 40, 30)));

        register(new UnitCard("EliteSquad", 6, SpacingProfile.RING, false,
                weights(10, 10, 70, 10)));

        register(new UnitCard("MixedCompany", 16, SpacingProfile.LOOSE_SWARM, true,
                weights(30, 30, 25, 15)));

        // ── VEHICLES ──

        register(new UnitCard("VehiclePlatoon", 4, SpacingProfile.LOOSE_SWARM, false,
                weights(0, 0, 90, 10)));

        // ── SCOUTS ──

        register(new UnitCard("ScoutTeam", 4, SpacingProfile.LOOSE_SWARM, true,
                weights(20, 70, 5, 5)));
    }

    private static EnumMap<UnitRole, Integer> weights(int melee, int ranged, int heavy, int special) {
        EnumMap<UnitRole, Integer> map = new EnumMap<>(UnitRole.class);
        map.put(UnitRole.MELEE, melee);
        map.put(UnitRole.RANGED, ranged);
        map.put(UnitRole.HEAVY, heavy);
        map.put(UnitRole.SPECIAL, special);
        return map;
    }

    public static void register(UnitCard card) {
        if (card == null || card.getName().isEmpty()) return;
        CARDS.put(card.getName().toLowerCase(), card);
    }

    public static UnitCard get(String name) {
        if (name == null) return null;
        return CARDS.get(name.trim().toLowerCase());
    }

    public static UnitCard getDefault() {
        UnitCard card = CARDS.get("shieldwall");
        if (card != null) return card;
        if (!CARDS.isEmpty()) return CARDS.values().iterator().next();
        return null;
    }

    public static Map<String, UnitCard> all() {
        return Collections.unmodifiableMap(CARDS);
    }
}
