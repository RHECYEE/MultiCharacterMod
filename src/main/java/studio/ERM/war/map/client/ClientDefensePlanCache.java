package studio.ERM.war.map.client;

import studio.ERM.strategic.defense.DefenseMarker;

import java.util.ArrayList;
import java.util.List;

/** Client cache of the defensive plan (markers + fall-back state); read/edited by the war map GUI. */
public final class ClientDefensePlanCache {

    private static volatile List<DefenseMarker> markers = new ArrayList<>();
    private static volatile boolean fallbackActive = false;

    private ClientDefensePlanCache() {}

    public static void update(List<DefenseMarker> list, boolean fallback) {
        markers = (list != null) ? list : new ArrayList<>();
        fallbackActive = fallback;
    }

    public static List<DefenseMarker> markers() {
        return markers;
    }

    public static boolean isFallbackActive() {
        return fallbackActive;
    }
}
