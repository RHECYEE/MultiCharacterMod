package studio.ERM.war.map.client;

import net.minecraft.util.math.ChunkPos;

import java.util.HashMap;
import java.util.Map;

/**
 * Client copy of the charted-chunks map (fog of war): chunk -> world-time TICK it was last seen.
 * Payloads arrive as packed [chunk, tick] long pairs. Each chunk AGES toward black over a full
 * in-game hour of nobody re-charting it (out-of-date intel); the moment it's re-seen, its tick
 * refreshes and it snaps back to clear. Version bumps invalidate the terrain cache on membership
 * change (aging alone is computed live per-frame, no version churn).
 */
public final class ClientFogCache {

    /** Full black after one in-game hour (24000-tick day / 24) of no re-charting. */
    private static final long FADE_TICKS = 1000L;

    private static final Map<Long, Long> CHUNK_TICK = new HashMap<>();
    private static volatile int version = 0;
    private static volatile boolean everSynced = false;

    private ClientFogCache() {}

    /** Ingest packed [chunk, tick, chunk, tick, ...] pairs. */
    private static void ingest(long[] packed) {
        for (int i = 0; i + 1 < packed.length; i += 2) {
            Long prev = CHUNK_TICK.get(packed[i]);
            long t = packed[i + 1];
            if (prev == null || t > prev) CHUNK_TICK.put(packed[i], t);
        }
    }

    public static synchronized void replaceAll(long[] packed) {
        CHUNK_TICK.clear();
        ingest(packed);
        everSynced = true;
        version++;
    }

    public static synchronized void merge(long[] packed) {
        int before = CHUNK_TICK.size();
        ingest(packed);
        if (CHUNK_TICK.size() != before) version++;
    }

    /** Charted at all (any age) — used by the terrain cache to decide if a tile is known. */
    public static boolean isExplored(int cx, int cz) {
        return CHUNK_TICK.containsKey(ChunkPos.asLong(cx, cz));
    }

    /**
     * Fog opacity for a chunk, 0 (fully clear) .. 255 (unviewable black), given the current world
     * tick. Never-charted -> 255. Fresh -> ~0. The curve stays low for most of the hour then rises
     * sharply near the end, so recent intel reads clear and only truly-stale ground blacks out.
     */
    public static int fogAlpha(int cx, int cz, long nowTick) {
        Long seen = CHUNK_TICK.get(ChunkPos.asLong(cx, cz));
        if (seen == null) return 255;
        long age = nowTick - seen;
        if (age <= 0) return 0;
        if (age >= FADE_TICKS) return 255;
        double f = (double) age / FADE_TICKS;      // 0..1 through the hour
        double curved = f * f * f;                 // slight fade early, black at the end
        return (int) (curved * 255.0);
    }

    /** Before the first full sync lands, DON'T fog anything (a beat of gray beats a black flash). */
    public static boolean ready() { return everSynced; }

    public static int version() { return version; }
}
