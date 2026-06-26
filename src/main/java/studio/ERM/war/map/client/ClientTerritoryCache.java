package studio.ERM.war.map.client;

import net.minecraft.util.math.ChunkPos;

import java.util.HashMap;
import java.util.Map;

/**
 * Client-side cache of territory ownership for rendering on the tactical map.
 * Updated via {@link studio.ERM.war.map.net.S2CTerritorySync} packets from the server.
 */
public final class ClientTerritoryCache {

    private static final Map<ChunkPos, String> territory = new HashMap<>();
    private static long lastUpdateTick = 0;

    private ClientTerritoryCache() {}

    public static void updateFromServer(Map<ChunkPos, String> snapshot) {
        synchronized (territory) {
            territory.clear();
            if (snapshot != null) {
                territory.putAll(snapshot);
            }
            lastUpdateTick = System.currentTimeMillis();
        }
    }

    /** Get a snapshot copy for rendering. */
    public static Map<ChunkPos, String> getSnapshot() {
        synchronized (territory) {
            return new HashMap<>(territory);
        }
    }

    /** Get the owner of a specific chunk. */
    public static String getOwner(ChunkPos pos) {
        synchronized (territory) {
            return territory.getOrDefault(pos, "NEUTRAL");
        }
    }

    public static boolean hasData() {
        synchronized (territory) {
            return !territory.isEmpty();
        }
    }

    public static void clear() {
        synchronized (territory) {
            territory.clear();
            lastUpdateTick = 0;
        }
    }
}
