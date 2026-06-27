package studio.ERM.war.strategy;

import net.minecraft.block.state.IBlockState;
import net.minecraft.init.Blocks;
import net.minecraft.util.ResourceLocation;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;
import studio.ERM.handlers.ProtectionHandler;

import java.util.ArrayList;
import java.util.List;

/**
 * "Where is the castle?" -- the single shared targeting resolution used by BOTH the SiegeDirector and
 * the {@code /war heat} debug command, so what the command shows is EXACTLY what the siege will aim at.
 *
 * Search order, strongest signal first:
 *   1. PROTECTED blocks (the defender's protection-stick markers). Stored globally, so range-independent.
 *   2. STRUCTURE scan (man-made building blocks) -- for a base that wasn't protect-sticked.
 *   3. HEAT core (tile-entity / chest / machine concentration).
 *   4. the trigger point.
 */
public final class SiegeTargeting {

    private SiegeTargeting() {}

    /** Resolution result -- the chosen target plus everything needed to explain/visualize the decision. */
    public static final class Result {
        public BlockPos target;
        public String reason = "trigger";        // protected | structure | heat | trigger
        public List<BlockPos> protectedBlocks = new ArrayList<>();
        public List<StrategicChunk> scanned = new ArrayList<>();
        public List<StrategicChunk> heatCluster;
        public StrategicChunk heatCore;
        public StrategicChunk hottest;
    }

    public static int surfaceY(World world, int x, int z) {
        return Math.max(62, world.getTopSolidOrLiquidBlock(new BlockPos(x, 64, z)).getY());
    }

    /**
     * Full resolution. ALWAYS scans heat (so the debug board can show it even when another signal wins),
     * then picks the target by the priority order above.
     */
    public static Result resolve(World world, BlockPos around, int protectedRadius, int fortressRadius,
                                 int heatChunkRadius) {
        Result r = new Result();
        r.target = around; // default = the trigger point

        try {
            WarHeatMap map = WarHeatMap.get(world);
            r.scanned = map.scanArea(world, around.getX() >> 4, around.getZ() >> 4, heatChunkRadius);
            r.hottest = map.hottest();
            if (r.hottest != null) {
                r.heatCluster = map.cluster(r.hottest, 30.0);
                r.heatCore = map.coreOf(r.heatCluster);
            }
        } catch (Throwable ignored) {}

        // 1. Protected blocks.
        r.protectedBlocks = ProtectionHandler.protectedPositionsNear(world, around, protectedRadius);
        BlockPos pc = protectedCore(world, around, r.protectedBlocks);
        if (pc != null) { r.target = pc; r.reason = "protected"; return r; }

        // 2. Structure.
        BlockPos fc = findFortressCenter(world, around, fortressRadius);
        if (fc != null) { r.target = fc; r.reason = "structure"; return r; }

        // 3. Heat core.
        if (r.hottest != null && r.hottest.totalHeat() >= 60 && r.heatCore != null) {
            int cx = (r.heatCore.chunkX << 4) + 8, cz = (r.heatCore.chunkZ << 4) + 8;
            r.target = new BlockPos(cx, surfaceY(world, cx, cz), cz);
            r.reason = "heat";
        }
        return r;
    }

    /** Seed on the protected block nearest the trigger, return the centroid of that 80-block cluster. */
    public static BlockPos protectedCore(World world, BlockPos around, List<BlockPos> pts) {
        if (pts == null || pts.size() < 8) return null;
        BlockPos seed = null; long best = Long.MAX_VALUE;
        for (BlockPos p : pts) {
            long dx = p.getX() - around.getX(), dz = p.getZ() - around.getZ();
            long d = dx * dx + dz * dz;
            if (d < best) { best = d; seed = p; }
        }
        long sx = 0, sz = 0; int n = 0; final long clusterR2 = 80L * 80L;
        for (BlockPos p : pts) {
            long dx = p.getX() - seed.getX(), dz = p.getZ() - seed.getZ();
            if (dx * dx + dz * dz <= clusterR2) { sx += p.getX(); sz += p.getZ(); n++; }
        }
        if (n < 6) return null;
        int cx = (int) (sx / n), cz = (int) (sz / n);
        return new BlockPos(cx, surfaceY(world, cx, cz), cz);
    }

    /** Build-weighted centroid of man-made blocks around a point (the castle), or null if none. */
    public static BlockPos findFortressCenter(World world, BlockPos around, int radius) {
        long sumX = 0, sumZ = 0, weight = 0;
        final int step = 3;
        for (int dx = -radius; dx <= radius; dx += step) {
            for (int dz = -radius; dz <= radius; dz += step) {
                int x = around.getX() + dx, z = around.getZ() + dz;
                int surf = surfaceY(world, x, z);
                int built = 0;
                for (int y = surf - 3; y <= surf + 22; y++) {
                    try { if (isManMade(world.getBlockState(new BlockPos(x, y, z)))) built++; }
                    catch (Throwable ignored) {}
                }
                if (built >= 3) { sumX += (long) x * built; sumZ += (long) z * built; weight += built; }
            }
        }
        if (weight < 24) return null;
        int cx = (int) (sumX / weight), cz = (int) (sumZ / weight);
        return new BlockPos(cx, surfaceY(world, cx, cz), cz);
    }

    /** True for common player-built fortress materials (not natural terrain). */
    public static boolean isManMade(IBlockState st) {
        try {
            net.minecraft.block.Block b = st.getBlock();
            if (b == Blocks.AIR) return false;
            ResourceLocation rn = b.getRegistryName();
            if (rn == null) return false;
            String n = rn.getPath();
            return n.contains("cobblestone") || n.contains("stonebrick") || n.contains("stone_brick")
                || n.contains("brick") || n.contains("planks") || n.contains("log") || n.contains("_wall")
                || n.contains("fence") || n.contains("_stairs") || n.contains("_slab") || n.contains("glass")
                || n.contains("concrete") || n.contains("nether_brick") || n.contains("quartz")
                || n.contains("sandstone") || n.contains("obsidian") || n.contains("iron_bars")
                || n.contains("_door") || n.contains("polished") || n.contains("chiseled")
                || n.contains("pillar") || n.contains("terracotta") || n.contains("prismarine");
        } catch (Throwable t) { return false; }
    }
}
