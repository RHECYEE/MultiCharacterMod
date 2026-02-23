package studio.ERM.war.map.client;

import studio.ERM.war.map.net.PacketDeployedBattlesSync;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Client-side cache of deployed battles for map display.
 * Updated by server sync packets.
 */
public final class ClientDeployedBattleCache {

    private static final List<PacketDeployedBattlesSync.DeployedBattleData> CACHE = new ArrayList<>();
    private static long lastUpdateTime = 0;

    private ClientDeployedBattleCache() {}

    /**
     * Update cache from server packet
     */
    public static void updateFromServer(List<PacketDeployedBattlesSync.DeployedBattleData> battles) {
        synchronized (CACHE) {
            CACHE.clear();
            if (battles != null) {
                CACHE.addAll(battles);
            }
            lastUpdateTime = System.currentTimeMillis();
        }
    }

    /**
     * Get all cached deployed battles
     */
    public static List<PacketDeployedBattlesSync.DeployedBattleData> getAll() {
        synchronized (CACHE) {
            return new ArrayList<>(CACHE);
        }
    }

    /**
     * Get deployed battles owned by the current player
     */
    public static List<PacketDeployedBattlesSync.DeployedBattleData> getOwnBattles() {
        synchronized (CACHE) {
            List<PacketDeployedBattlesSync.DeployedBattleData> result = new ArrayList<>();
            for (PacketDeployedBattlesSync.DeployedBattleData data : CACHE) {
                if (data.isOwner) {
                    result.add(data);
                }
            }
            return result;
        }
    }

    /**
     * Check if cache has been updated recently
     */
    public static boolean isStale(long maxAgeMs) {
        return System.currentTimeMillis() - lastUpdateTime > maxAgeMs;
    }

    /**
     * Clear the cache (e.g., on disconnect)
     */
    public static void clear() {
        synchronized (CACHE) {
            CACHE.clear();
            lastUpdateTime = 0;
        }
    }
}
