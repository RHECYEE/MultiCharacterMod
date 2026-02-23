package studio.ERM.war;

import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.ChunkPos;
import studio.ERM.war.world.WarWorldData;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

/**
 * WarMapOverlay (compat + data spine)
 *
 * JourneyMap has been removed.
 * Existing systems may still call WarMapOverlay.* to publish map-relevant state.
 *
 * This class stores lightweight snapshots for the Tactical War Map UI to render later.
 * It is safe on both logical sides (no client-only classes).
 */
public final class WarMapOverlay {

    private static final Map<ChunkPos, String> TERRITORY = new HashMap<>();
    private static final Map<String, Marker> MARKERS = new HashMap<>();

    private WarMapOverlay() {}

    public static final class Marker {
        public final String id;
        public final BlockPos pos;
        public final String label;

        public Marker(String id, BlockPos pos, String label) {
            this.id = id;
            this.pos = pos;
            this.label = label;
        }
    }

    public static void updateChunkTerritory(ChunkPos pos, String owner) {
        if (pos == null) return;
        if (owner == null) owner = "NEUTRAL";
        synchronized (TERRITORY) {
            if ("NEUTRAL".equalsIgnoreCase(owner)) {
                TERRITORY.remove(pos);
            } else {
                TERRITORY.put(pos, owner);
            }
        }
    }

    public static void addBattleMarker(String id, BlockPos pos, String label) {
        if (id == null || pos == null) return;
        if (label == null) label = id;
        synchronized (MARKERS) {
            MARKERS.put(id, new Marker(id, pos, label));
        }
    }

    public static void removeMarker(String id) {
        if (id == null) return;
        synchronized (MARKERS) {
            MARKERS.remove(id);
        }
    }

    public static void setRivalCityMarker(BlockPos center, int level) {
        if (center == null) return;
        addBattleMarker("rival_city", center, "Rival City (Lvl " + level + ")");
    }

    public static void refreshFromWorldData(WarWorldData data) {
        if (data == null) return;
        synchronized (TERRITORY) {
            TERRITORY.clear();
            for (Map.Entry<ChunkPos, String> e : data.getTerritoryMap().entrySet()) {
                ChunkPos cp = e.getKey();
                String owner = e.getValue();
                if (cp != null && owner != null && !"NEUTRAL".equalsIgnoreCase(owner)) {
                    TERRITORY.put(cp, owner);
                }
            }
        }
    }

    public static void forceRefresh(WarWorldData data) {
        refreshFromWorldData(data);
    }

    public static int getOverlayCount() {
        synchronized (TERRITORY) {
            return TERRITORY.size();
        }
    }

    public static Map<ChunkPos, String> getTerritorySnapshot() {
        synchronized (TERRITORY) {
            return Collections.unmodifiableMap(new HashMap<>(TERRITORY));
        }
    }

    public static Map<String, Marker> getMarkerSnapshot() {
        synchronized (MARKERS) {
            return Collections.unmodifiableMap(new HashMap<>(MARKERS));
        }
    }
}