package studio.ERM.war.map.client;

import studio.ERM.war.map.net.S2CStrategicSync;

import java.util.ArrayList;
import java.util.List;

/** Client cache of the strategic-map snapshot (see {@link S2CStrategicSync}); read by the war map GUI. */
public final class ClientStrategicCache {

    private static volatile List<S2CStrategicSync.Data> objects = new ArrayList<>();

    private ClientStrategicCache() {}

    public static void update(List<S2CStrategicSync.Data> list) {
        objects = (list != null) ? list : new ArrayList<>();
    }

    public static List<S2CStrategicSync.Data> snapshot() {
        return objects;
    }
}
