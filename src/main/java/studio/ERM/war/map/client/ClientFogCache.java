package studio.ERM.war.map.client;

import net.minecraft.util.math.ChunkPos;

import java.util.HashSet;
import java.util.Set;

/** Client copy of the charted-chunks set (fog of war). Version bumps invalidate the terrain cache. */
public final class ClientFogCache {

    private static final Set<Long> CHUNKS = new HashSet<>();
    private static volatile int version = 0;
    private static volatile boolean everSynced = false;

    private ClientFogCache() {}

    public static synchronized void replaceAll(long[] chunks) {
        CHUNKS.clear();
        for (long c : chunks) CHUNKS.add(c);
        everSynced = true;
        version++;
    }

    public static synchronized void merge(long[] chunks) {
        boolean changed = false;
        for (long c : chunks) changed |= CHUNKS.add(c);
        if (changed) version++;
    }

    public static boolean isExplored(int cx, int cz) {
        return CHUNKS.contains(ChunkPos.asLong(cx, cz));
    }

    /** Before the first full sync lands, DON'T fog anything (a beat of gray beats a black flash). */
    public static boolean ready() { return everSynced; }

    public static int version() { return version; }
}
