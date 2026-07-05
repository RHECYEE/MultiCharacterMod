package studio.ERM.strategic;

import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.ChunkPos;
import net.minecraft.world.World;
import net.minecraft.world.storage.WorldSavedData;

import java.util.HashMap;
import java.util.Map;

/**
 * FOG OF WAR — the settlement's shared CHARTED CHUNKS, each stamped with the world-time it was last
 * seen. The map starts BLACK; chunks are charted by PRESENCE (player walking, citizens working,
 * soldiers patrolling) and by SCOUT missions. What you saw stays on the map, but goes STALE: a chunk
 * fades toward black over a full in-game hour of nobody re-visiting it (out-of-date intel), and
 * brightens again the moment someone charts it. Areas around your living city therefore stay clear;
 * ground you abandon slowly darkens.
 */
public class ExploredMapData extends WorldSavedData {

    private static final String KEY = "erm_explored";

    /** chunk (packed long) -> world-time TICK it was last charted. */
    private final Map<Long, Long> chunkTick = new HashMap<>();

    public ExploredMapData() { super(KEY); }
    public ExploredMapData(String name) { super(name); }

    public static ExploredMapData get(World world) {
        ExploredMapData data = (ExploredMapData) world.getPerWorldStorage().getOrLoadData(ExploredMapData.class, KEY);
        if (data == null) {
            data = new ExploredMapData();
            world.getPerWorldStorage().setData(KEY, data);
        }
        return data;
    }

    public boolean isExplored(int cx, int cz) {
        return chunkTick.containsKey(ChunkPos.asLong(cx, cz));
    }

    /** Chart one chunk, refreshing its last-seen tick. Returns true when it was NEW. */
    public boolean mark(int cx, int cz, long now) {
        Long prev = chunkTick.put(ChunkPos.asLong(cx, cz), now);
        markDirty();
        return prev == null;
    }

    /** Chart a square of chunks around a block position (presence radius / scout reveal). */
    public int markAround(BlockPos at, int radiusChunks, long now) {
        int cx = at.getX() >> 4, cz = at.getZ() >> 4;
        int added = 0;
        for (int dx = -radiusChunks; dx <= radiusChunks; dx++) {
            for (int dz = -radiusChunks; dz <= radiusChunks; dz++) {
                if (mark(cx + dx, cz + dz, now)) added++;
            }
        }
        return added;
    }

    public int size() { return chunkTick.size(); }

    /** The FULL chart as packed [chunk, tick, chunk, tick, ...] longs (map-open sync). */
    public long[] snapshot() {
        long[] out = new long[chunkTick.size() * 2];
        int i = 0;
        for (Map.Entry<Long, Long> e : chunkTick.entrySet()) { out[i++] = e.getKey(); out[i++] = e.getValue(); }
        return out;
    }

    @Override
    public void readFromNBT(NBTTagCompound nbt) {
        chunkTick.clear();
        int[] flat = nbt.getIntArray("chunks");   // cx, cz pairs
        int[] secs = nbt.getIntArray("secs");     // parallel: world-second last charted
        for (int i = 0, j = 0; i + 1 < flat.length; i += 2, j++) {
            long tick = (j < secs.length ? (long) secs[j] * 20L : 0L);
            chunkTick.put(ChunkPos.asLong(flat[i], flat[i + 1]), tick);
        }
    }

    @Override
    public NBTTagCompound writeToNBT(NBTTagCompound nbt) {
        int[] flat = new int[chunkTick.size() * 2];
        int[] secs = new int[chunkTick.size()];
        int i = 0, j = 0;
        for (Map.Entry<Long, Long> e : chunkTick.entrySet()) {
            long l = e.getKey();
            flat[i++] = (int) (l & 0xFFFFFFFFL);   // cx (ChunkPos.asLong packs x in the low bits)
            flat[i++] = (int) (l >>> 32);           // cz
            secs[j++] = (int) (e.getValue() / 20L); // world-second (fits int for years of play)
        }
        nbt.setIntArray("chunks", flat);
        nbt.setIntArray("secs", secs);
        return nbt;
    }
}
