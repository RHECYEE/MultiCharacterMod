package studio.ERM.strategic;

import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.ChunkPos;
import net.minecraft.world.World;
import net.minecraft.world.storage.WorldSavedData;

import java.util.ArrayDeque;
import java.util.HashSet;
import java.util.Set;

/**
 * FOG OF WAR — the settlement's shared CHARTED CHUNKS. The strategic map starts BLACK; chunks are
 * charted by PRESENCE (the player walking, citizens working, soldiers patrolling) and by SCOUT
 * missions. What the map shows is what someone actually saw — everywhere else stays dark, and the
 * chart never un-explores (your information can simply be out of date).
 *
 * Storage is one shared set per world (single-player settlement doctrine: your citizens chart for
 * you). A small RECENT ring feeds delta-sync so the open map darkens/undarkens live without ever
 * reshipping the full chart.
 */
public class ExploredMapData extends WorldSavedData {

    private static final String KEY = "erm_explored";
    private static final int RECENT_CAP = 512;

    private final Set<Long> chunks = new HashSet<>();
    private final ArrayDeque<Long> recent = new ArrayDeque<>();

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
        return chunks.contains(ChunkPos.asLong(cx, cz));
    }

    /** Chart one chunk. Returns true when it was NEW (feeds the recent ring + dirty flag). */
    public boolean mark(int cx, int cz) {
        long key = ChunkPos.asLong(cx, cz);
        if (!chunks.add(key)) return false;
        recent.addLast(key);
        while (recent.size() > RECENT_CAP) recent.removeFirst();
        markDirty();
        return true;
    }

    /** Chart a square of chunks around a block position (presence radius / scout reveal). */
    public int markAround(BlockPos at, int radiusChunks) {
        int cx = at.getX() >> 4, cz = at.getZ() >> 4;
        int added = 0;
        for (int dx = -radiusChunks; dx <= radiusChunks; dx++) {
            for (int dz = -radiusChunks; dz <= radiusChunks; dz++) {
                if (mark(cx + dx, cz + dz)) added++;
            }
        }
        return added;
    }

    public int size() { return chunks.size(); }

    /** The FULL chart as a packed array (map-open sync). */
    public long[] snapshot() {
        long[] out = new long[chunks.size()];
        int i = 0;
        for (Long l : chunks) out[i++] = l;
        return out;
    }

    /** The recently-charted ring (delta sync riding the 2s civil-plan feed). */
    public long[] recentSnapshot() {
        long[] out = new long[recent.size()];
        int i = 0;
        for (Long l : recent) out[i++] = l;
        return out;
    }

    @Override
    public void readFromNBT(NBTTagCompound nbt) {
        chunks.clear();
        recent.clear();
        // Stored as paired ints (NBT has no long-array tag readable pre-1.12 style helpers here;
        // int pairs keep it simple + versionable).
        int[] flat = nbt.getIntArray("chunks");
        for (int i = 0; i + 1 < flat.length; i += 2) {
            chunks.add(ChunkPos.asLong(flat[i], flat[i + 1]));
        }
    }

    @Override
    public NBTTagCompound writeToNBT(NBTTagCompound nbt) {
        int[] flat = new int[chunks.size() * 2];
        int i = 0;
        for (Long l : chunks) {
            flat[i++] = (int) (l & 0xFFFFFFFFL);         // cx (ChunkPos.asLong packs x in the low bits)
            flat[i++] = (int) (l >>> 32);                 // cz
        }
        nbt.setIntArray("chunks", flat);
        return nbt;
    }
}
