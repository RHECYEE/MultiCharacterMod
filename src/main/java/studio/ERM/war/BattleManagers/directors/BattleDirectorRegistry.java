package studio.ERM.war.BattleManagers.directors;

import studio.ERM.war.BattleManagers.cards.UnitCard;
import studio.ERM.war.BattleManagers.cards.UnitCardRegistry;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Central registry of all available Battle Director types.
 *
 * IMPORTANT:
 * - This registry is a data registry; it should not hard-filter by CP when listing available directors.
 * - CP enforcement happens server-side when actually deploying (DeployedBattleManager.deployBattle).
 */
public final class BattleDirectorRegistry {

    private static final Map<String, BattleDirectorEntry> REGISTRY = new LinkedHashMap<>();
    private static boolean initialized = false;

    private BattleDirectorRegistry() {}

    /**
     * Initialize with default battle directors.
     * Call this during mod initialization.
     */
    public static void init() {
        if (initialized) return;
        initialized = true;

        // ============================================================
        // REGISTER YOUR BATTLE DIRECTORS HERE
        // ============================================================

        // Basic skirmish - cheap, small-scale engagement
        register(new BattleDirectorEntry(
                "skirmish",
                "Skirmish",
                "Small engagement. Light resistance.",
                25,  // CP cost
                1,   // Min level
                () -> {
                    UnitCard card = UnitCardRegistry.get("ShieldWall");
                    if (card == null) card = UnitCardRegistry.getDefault();
                    return new SkirmishDirector(card);
                }
        ));

        // Ambush - moderate cost, enemy has positional advantage
        register(new BattleDirectorEntry(
                "ambush",
                "Ambush",
                "Enemy forces lie in wait. Prepare for a fight.",
                50,  // CP cost
                1,   // Min level
                () -> {
                    UnitCard card = UnitCardRegistry.get("Phalanx");
                    if (card == null) card = UnitCardRegistry.getDefault();
                    return new AmbushDirector(card);
                }
        ));

        // Patrol encounter - moderate engagement
        register(new BattleDirectorEntry(
                "patrol",
                "Patrol Encounter",
                "Engage enemy patrol. Medium resistance.",
                40,  // CP cost
                1,   // Min level
                () -> {
                    UnitCard card = UnitCardRegistry.get("ShieldWall");
                    if (card == null) card = UnitCardRegistry.getDefault();
                    return new PatrolDirector(card);
                }
        ));

        // Defensive stand - player defends a position
        register(new BattleDirectorEntry(
                "defense",
                "Defensive Stand",
                "Hold your ground against waves of attackers.",
                75,  // CP cost
                2,   // Min level
                () -> {
                    UnitCard card = UnitCardRegistry.get("Phalanx");
                    if (card == null) card = UnitCardRegistry.getDefault();
                    return new DefensiveStandDirector(card);
                }
        ));

        // Assault - large scale attack
        register(new BattleDirectorEntry(
                "assault",
                "Assault",
                "Full-scale assault on enemy position. Heavy resistance.",
                100, // CP cost
                3,   // Min level
                () -> {
                    UnitCard card = UnitCardRegistry.get("HeavyInfantry");
                    if (card == null) card = UnitCardRegistry.getDefault();
                    return new AssaultDirector(card);
                }
        ));

        // Siege - expensive, large-scale battle
        register(new BattleDirectorEntry(
                "siege",
                "Siege Battle",
                "Massive engagement. Prepare for a prolonged fight.",
                150, // CP cost
                4,   // Min level
                () -> {
                    UnitCard card = UnitCardRegistry.get("SiegeUnit");
                    if (card == null) card = UnitCardRegistry.getDefault();
                    return new SiegeDirector(card);
                }
        ));

        // Debug circle - for testing (FREE)
        register(new BattleDirectorEntry(
                "debug_circle",
                "[Debug] Circle Test",
                "Debug formation test. Spawns orbiting unit.",
                0,   // CP cost
                0,   // Min level (always available)
                () -> {
                    UnitCard card = UnitCardRegistry.get("ShieldWall");
                    if (card == null) card = UnitCardRegistry.getDefault();
                    return new DebugCircleDirector(card);
                }
        ));
    }

    /**
     * Register a new battle director type.
     * IDs are stored normalized to lowercase.
     */
    public static void register(BattleDirectorEntry entry) {
        if (entry == null) return;

        String id = normalizeId(entry.getId());
        if (id.isEmpty()) return;

        REGISTRY.put(id, entry);
    }

    /**
     * Back-compat API: Get a director entry by ID (case-insensitive).
     */
    public static BattleDirectorEntry get(String id) {
        return getById(id);
    }

    /**
     * Preferred API: Get a director entry by ID (case-insensitive).
     */
    public static BattleDirectorEntry getById(String id) {
        String key = normalizeId(id);
        if (key.isEmpty()) return null;
        return REGISTRY.get(key);
    }

    /**
     * Get all registered director entries.
     */
    public static Collection<BattleDirectorEntry> getAll() {
        return Collections.unmodifiableCollection(REGISTRY.values());
    }

    /**
     * Get all directors available to a player at a given level.
     *
     * IMPORTANT: This does NOT filter by CP.
     * The CP parameter is kept only for signature compatibility with older call sites.
     */
    public static List<BattleDirectorEntry> getAvailable(int playerLevel, int ignoredPlayerCp) {
        List<BattleDirectorEntry> available = new ArrayList<>();
        for (BattleDirectorEntry entry : REGISTRY.values()) {
            if (entry != null && entry.getMinLevel() <= playerLevel) {
                available.add(entry);
            }
        }
        return available;
    }

    /**
     * Check if a director can be afforded (simple helper).
     * This reflects the base entry CP cost; authoritative charging happens server-side.
     */
    public static boolean canAfford(String directorId, int playerCp) {
        BattleDirectorEntry entry = getById(directorId);
        return entry != null && playerCp >= Math.max(0, entry.getCpCost());
    }

    private static String normalizeId(String id) {
        if (id == null) return "";
        String s = id.trim();
        if (s.isEmpty()) return "";
        return s.toLowerCase(Locale.ROOT);
    }
}
