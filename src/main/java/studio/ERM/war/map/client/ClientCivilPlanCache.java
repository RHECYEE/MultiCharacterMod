package studio.ERM.war.map.client;

import studio.ERM.strategic.civil.CivilMarker;

import java.util.ArrayList;
import java.util.List;

/** Client cache of the civilian infrastructure plan (roads + districts) + settlement stats; read by the war map GUI. */
public final class ClientCivilPlanCache {

    private static volatile List<CivilMarker> markers = new ArrayList<>();
    private static volatile int availWorkers, totalWorkers, availBeds, totalBeds;
    private static volatile List<studio.ERM.war.map.net.S2CCivilPlanSync.JobLine> jobs = new ArrayList<>();
    private static volatile List<studio.ERM.war.map.net.S2CCivilPlanSync.Deposit> deposits = new ArrayList<>();

    private ClientCivilPlanCache() {}

    public static void update(List<CivilMarker> list) {
        markers = (list != null) ? list : new ArrayList<>();
    }

    public static void updateJobs(List<studio.ERM.war.map.net.S2CCivilPlanSync.JobLine> list) {
        jobs = (list != null) ? list : new ArrayList<>();
    }

    public static List<studio.ERM.war.map.net.S2CCivilPlanSync.JobLine> jobs() {
        return jobs;
    }

    public static void updateDeposits(List<studio.ERM.war.map.net.S2CCivilPlanSync.Deposit> list) {
        deposits = (list != null) ? list : new ArrayList<>();
    }

    /** Player-known strategic resource deposits — the map's clickable camp-site icons. */
    public static List<studio.ERM.war.map.net.S2CCivilPlanSync.Deposit> deposits() {
        return deposits;
    }

    private static volatile boolean radarActive;
    private static volatile int aaScore;

    public static void updateAirDefense(boolean active, int score) {
        radarActive = active;
        aaScore = score;
    }

    /** A manned radar exists — the map may draw aircraft contacts (green bars). */
    public static boolean radarActive() { return radarActive; }
    public static int aaScore() { return aaScore; }

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
