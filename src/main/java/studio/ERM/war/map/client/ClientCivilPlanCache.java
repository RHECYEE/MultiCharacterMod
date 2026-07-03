package studio.ERM.war.map.client;

import studio.ERM.strategic.civil.CivilMarker;

import java.util.ArrayList;
import java.util.List;

/** Client cache of the civilian infrastructure plan (roads + districts) + settlement stats; read by the war map GUI. */
public final class ClientCivilPlanCache {

    private static volatile List<CivilMarker> markers = new ArrayList<>();
    private static volatile int availWorkers, totalWorkers, availBeds, totalBeds;

    private ClientCivilPlanCache() {}

    public static void update(List<CivilMarker> list) {
        markers = (list != null) ? list : new ArrayList<>();
    }

    public static void updateStats(int availWorkers, int totalWorkers, int availBeds, int totalBeds) {
        ClientCivilPlanCache.availWorkers = availWorkers;
        ClientCivilPlanCache.totalWorkers = totalWorkers;
        ClientCivilPlanCache.availBeds = availBeds;
        ClientCivilPlanCache.totalBeds = totalBeds;
    }

    public static List<CivilMarker> markers() {
        return markers;
    }

    public static int availWorkers() { return availWorkers; }
    public static int totalWorkers() { return totalWorkers; }
    public static int availBeds()    { return availBeds; }
    public static int totalBeds()    { return totalBeds; }
}
