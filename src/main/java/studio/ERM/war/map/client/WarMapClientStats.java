package studio.ERM.war.map.client;

/**
 * Client-side, render-safe cache of war HUD stats for the Tactical War Map.
 *
 * This is intentionally dumb: it only stores the latest values that arrived
 * from the server (currently piggybacked on {@link studio.ERM.war.map.net.PacketIntelSnapshot}).
 *
 * The GUI reads these values every frame without doing any networking,
 * scoreboard parsing, or world-data lookups.
 */
public final class WarMapClientStats {

    private static boolean hasSnapshot = false;

    private static int cachedCommandPoints = 0;
    private static int cachedAirDefense = 0;
    private static int cachedEra = 1;
    private static int cachedTension = 0;

    private static boolean cachedBattleActive = false;

    private WarMapClientStats() {
    }

    public static void reset() {
        hasSnapshot = false;
        cachedCommandPoints = 0;
        cachedAirDefense = 0;
        cachedEra = 1;
        cachedTension = 0;
        cachedBattleActive = false;
    }

    /**
     * Called on the CLIENT thread.
     */
    public static void applyFromSnapshot(int commandPoints, int airDefense, int era, int tension, boolean battleActive) {
        hasSnapshot = true;
        cachedCommandPoints = Math.max(0, commandPoints);
        cachedAirDefense = clamp(airDefense, 0, 100);
        cachedEra = Math.max(1, era);
        cachedTension = clamp(tension, 0, 100);
        cachedBattleActive = battleActive;
    }

    public static Integer getCommandPointsOrNull() {
        return hasSnapshot ? cachedCommandPoints : null;
    }

    public static Integer getAirDefenseOrNull() {
        return hasSnapshot ? cachedAirDefense : null;
    }

    public static Integer getEraOrNull() {
        return hasSnapshot ? cachedEra : null;
    }

    public static Integer getTensionOrNull() {
        return hasSnapshot ? cachedTension : null;
    }

    /**
     * Get command points with default value if no snapshot received
     */
    public static int getCommandPoints() {
        return hasSnapshot ? cachedCommandPoints : 0;
    }

    /**
     * Get era/level with default value if no snapshot received
     */
    public static int getEra() {
        return hasSnapshot ? cachedEra : 1;
    }

    /**
     * Check if we have received stats from the server
     */
    public static boolean hasSnapshot() {
        return hasSnapshot;
    }

    public static boolean isBattleActive() {
        return hasSnapshot && cachedBattleActive;
    }

    private static int clamp(int v, int min, int max) {
        if (v < min) return min;
        if (v > max) return max;
        return v;
    }
}
