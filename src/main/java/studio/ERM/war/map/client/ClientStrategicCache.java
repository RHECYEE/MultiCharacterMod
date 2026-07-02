package studio.ERM.war.map.client;

import studio.ERM.war.map.net.S2CStrategicSync;

import java.util.ArrayList;
import java.util.List;

/** Client cache of the strategic-map snapshot (see {@link S2CStrategicSync}); read by the war map GUI. */
public final class ClientStrategicCache {

    private static volatile List<S2CStrategicSync.Data> objects = new ArrayList<>();
    private static volatile int[] friendlyDots = new int[0];
    private static volatile int[] enemyDots = new int[0];
    private static volatile boolean siegeActive = false;
    private static volatile int siegeX, siegeZ;

    private ClientStrategicCache() {}

    public static void update(List<S2CStrategicSync.Data> list, int[] fDots, int[] eDots,
                              boolean siege, int sx, int sz) {
        objects = (list != null) ? list : new ArrayList<>();
        friendlyDots = (fDots != null) ? fDots : new int[0];
        enemyDots = (eDots != null) ? eDots : new int[0];
        siegeActive = siege;
        siegeX = sx;
        siegeZ = sz;
    }

    public static List<S2CStrategicSync.Data> snapshot() { return objects; }
    public static int[] friendlyDots() { return friendlyDots; }
    public static int[] enemyDots() { return enemyDots; }
    public static boolean siegeActive() { return siegeActive; }
    public static int siegeX() { return siegeX; }
    public static int siegeZ() { return siegeZ; }
    /** Friendly soldier count (for the "Units assigned X/Y" readout). */
    public static int friendlyCount() { return friendlyDots.length / 2; }
}
