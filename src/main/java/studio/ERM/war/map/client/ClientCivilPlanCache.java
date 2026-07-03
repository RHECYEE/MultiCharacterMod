package studio.ERM.war.map.client;

import studio.ERM.strategic.civil.CivilMarker;

import java.util.ArrayList;
import java.util.List;

/** Client cache of the civilian infrastructure plan (roads + districts); read by the war map GUI. */
public final class ClientCivilPlanCache {

    private static volatile List<CivilMarker> markers = new ArrayList<>();

    private ClientCivilPlanCache() {}

    public static void update(List<CivilMarker> list) {
        markers = (list != null) ? list : new ArrayList<>();
    }

    public static List<CivilMarker> markers() {
        return markers;
    }
}
