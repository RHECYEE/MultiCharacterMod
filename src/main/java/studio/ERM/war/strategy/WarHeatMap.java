package studio.ERM.war.strategy;

import net.minecraft.block.state.IBlockState;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;
import net.minecraft.world.chunk.Chunk;
import studio.ERM.handlers.ProtectionHandler;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Per-dimension store of {@link StrategicChunk} heat. The chunk-scale brain: scan chunks, score their
 * tile entities + protected blocks, classify each chunk, and group hot chunks into a base cluster the
 * Siege Director can reason about (find core / perimeter / breach).
 *
 * First-version scan is event-light and on-demand (the {@code /war heat} command). The full design
 * adds event-driven updates + a throttled background queue + persistence + heat diffusion/decay.
 */
public class WarHeatMap {

    private static final Map<Integer, WarHeatMap> BY_DIM = new HashMap<>();

    public static WarHeatMap get(World world) {
        int dim = world.provider.getDimension();
        synchronized (BY_DIM) {
            return BY_DIM.computeIfAbsent(dim, d -> new WarHeatMap());
        }
    }

    private final Map<Long, StrategicChunk> chunks = new HashMap<>();

    public StrategicChunk get(int cx, int cz) { return chunks.get(StrategicChunk.chunkKey(cx, cz)); }

    /** (Re)scan one loaded chunk: score its tile entities + the player's protected blocks, classify it. */
    public StrategicChunk scanChunk(World world, int cx, int cz) {
        StrategicChunk sc = new StrategicChunk(cx, cz);
        Chunk chunk = world.getChunkProvider().getLoadedChunk(cx, cz);
        if (chunk != null) {
            // Tile entities are the cheap, high-signal pass (chests/furnaces/machines/AE2/RS/beds/...).
            for (Map.Entry<BlockPos, TileEntity> e : new ArrayList<>(chunk.getTileEntityMap().entrySet())) {
                TileEntity te = e.getValue();
                if (te == null) continue;
                BlockPos pos = e.getKey();
                IBlockState st;
                try { st = world.getBlockState(pos); } catch (Throwable t) { continue; }
                HeatScoring.score(sc, st, te, ProtectionHandler.isProtected(world, pos));
            }
            // Non-TE blocks the player explicitly protected (walls, etc.) read as defensive structure.
            try {
                for (BlockPos ignored : ProtectionHandler.protectedPositionsInChunk(world, cx, cz)) {
                    sc.structuralHeat += 40;
                    sc.defenseHeat += 40;
                }
            } catch (Throwable ignored) {}
        }
        sc.lastScannedTime = world.getTotalWorldTime();
        sc.classify();
        chunks.put(sc.key(), sc);
        return sc;
    }

    /** Scan a (2*radius+1)^2 block of chunks centred on a chunk coordinate. */
    public List<StrategicChunk> scanArea(World world, int centerCx, int centerCz, int radius) {
        List<StrategicChunk> out = new ArrayList<>();
        for (int dx = -radius; dx <= radius; dx++) {
            for (int dz = -radius; dz <= radius; dz++) {
                out.add(scanChunk(world, centerCx + dx, centerCz + dz));
            }
        }
        return out;
    }

    /** The single hottest scanned chunk (a good cluster seed). */
    public StrategicChunk hottest() {
        StrategicChunk best = null;
        for (StrategicChunk c : chunks.values()) {
            if (best == null || c.totalHeat() > best.totalHeat()) best = c;
        }
        return best;
    }

    /** BFS-group neighbouring chunks with meaningful heat into the base cluster around {@code seed}. */
    public List<StrategicChunk> cluster(StrategicChunk seed, double threshold) {
        List<StrategicChunk> out = new ArrayList<>();
        if (seed == null) return out;
        Set<Long> seen = new HashSet<>();
        Deque<StrategicChunk> q = new ArrayDeque<>();
        q.add(seed); seen.add(seed.key());
        int[][] dirs = {{1,0},{-1,0},{0,1},{0,-1},{1,1},{1,-1},{-1,1},{-1,-1}};
        while (!q.isEmpty()) {
            StrategicChunk c = q.poll();
            out.add(c);
            for (int[] d : dirs) {
                StrategicChunk n = get(c.chunkX + d[0], c.chunkZ + d[1]);
                if (n != null && !seen.contains(n.key()) && n.totalHeat() >= threshold) {
                    seen.add(n.key());
                    q.add(n);
                }
            }
        }
        return out;
    }

    /** Within a cluster, the CORE = strongest combined storage+machine+living (NOT necessarily the bed). */
    public StrategicChunk coreOf(List<StrategicChunk> cluster) {
        StrategicChunk best = null;
        double bestScore = -1;
        for (StrategicChunk c : cluster) {
            double s = c.storageHeat + c.machineHeat + c.livingHeat + c.powerHeat;
            if (s > bestScore) { bestScore = s; best = c; }
        }
        return best;
    }
}
