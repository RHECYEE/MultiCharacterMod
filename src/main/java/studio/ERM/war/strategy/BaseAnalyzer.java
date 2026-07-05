package studio.ERM.war.strategy;

import net.minecraft.block.material.Material;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * BASE-TYPE DETECTION. Answers "what KIND of problem is this base?" so the SiegeDirector can pick a
 * doctrine ("given our tech level, how do we solve it?") instead of treating every base like the same box.
 *
 * Classifies the target as SURFACE / UNDERGROUND / SKY / OCEAN with CONFIDENCE scores (not a hard label),
 * detects the REAL approach ground level per side (fixing roof/tree/tower "ground" false positives), and
 * emits doctrine hints. Run ONCE at siege start from the already-resolved base core/cluster -- it does NOT
 * scan the whole world; it samples a ring for ground level and reads the known value blocks (protected
 * blocks from {@link SiegeTargeting} + nearby tile entities) for the base's vertical posture.
 */
public final class BaseAnalyzer {

    private BaseAnalyzer() {}

    public enum BaseType { SURFACE, UNDERGROUND, SKY, OCEAN }

    /** Kinds of base access the siege can exploit as objectives instead of (or as well as) a fresh breach. */
    public enum AccessType {
        DOOR,        // a wooden/iron door  -> the natural entrance to assault/hold
        GATE,        // a fence gate        -> entrance
        TRAPDOOR,    // a trapdoor          -> a vertical hatch (often a bunker/roof hatch)
        LADDER_SHAFT,// ladder/vine column  -> a vertical shaft into an underground/sky base
        DOCK,        // walkable wood at the waterline -> an ocean base's boarding point
        BRIDGE       // man-made walkway over water -> the causeway in / out
    }

    /** One detected access point: where it is + what kind. Cheap value object. */
    public static final class AccessNode {
        public final BlockPos pos;
        public final AccessType type;
        public AccessNode(BlockPos pos, AccessType type) { this.pos = pos; this.type = type; }
        @Override public String toString() { return type + "@" + pos.getX() + "," + pos.getY() + "," + pos.getZ(); }
    }

    /** The analysis result the director consumes. */
    public static final class BaseAnalysis {
        public BlockPos core;
        public BaseType primaryType = BaseType.SURFACE;
        public double typeConfidence = 0.15;
        public double surfaceScore, undergroundScore, skyScore, oceanScore;
        public int approachY = 64;          // most-common walkable ground band around the base
        public int northApproachY = 64, southApproachY = 64, eastApproachY = 64, westApproachY = 64;
        public double pctBelow, pctAbove, pctOverWater; // value-block posture vs the approach level
        public int valueSamples;
        public String doctrineHints = "";
        /** Access points (entrances/shafts/docks/bridges) the siege can exploit/hold instead of a blind breach. */
        public final List<AccessNode> accessNodes = new ArrayList<>();

        /** Nearest access node of one of {@code types} to {@code from} (the army side), or null if none. */
        public AccessNode nearestAccessToward(BlockPos from, AccessType... types) {
            if (from == null || accessNodes.isEmpty()) return null;
            AccessNode best = null; double bestD = Double.MAX_VALUE;
            for (AccessNode n : accessNodes) {
                if (n == null || n.pos == null) continue;
                boolean ok = (types == null || types.length == 0);
                if (!ok) for (AccessType t : types) if (n.type == t) { ok = true; break; }
                if (!ok) continue;
                double d = n.pos.distanceSq(from);
                if (d < bestD) { bestD = d; best = n; }
            }
            return best;
        }

        /** The approach ground Y on the side of the base toward {@code from} (e.g. the army staging side). */
        public int approachYToward(BlockPos from) {
            if (core == null || from == null) return approachY;
            int dx = from.getX() - core.getX(), dz = from.getZ() - core.getZ();
            if (Math.abs(dx) >= Math.abs(dz)) return dx >= 0 ? eastApproachY : westApproachY;
            return dz >= 0 ? southApproachY : northApproachY;
        }

        @Override public String toString() {
            return primaryType + " " + pct(typeConfidence) + " approachY=" + approachY
                    + " (N" + northApproachY + " S" + southApproachY + " E" + eastApproachY + " W" + westApproachY + ")"
                    + " below=" + pct(pctBelow) + " above=" + pct(pctAbove) + " water=" + pct(pctOverWater)
                    + " scores[S" + pct(surfaceScore) + " U" + pct(undergroundScore) + " K" + pct(skyScore)
                    + " O" + pct(oceanScore) + "] samples=" + valueSamples
                    + " access=" + accessNodes.size() + " -> " + doctrineHints;
        }
        private static String pct(double d) { return (int) Math.round(d * 100) + "%"; }
    }

    /**
     * Analyze the base around {@code core}. {@code protectedBlocks} is the protected-block list from
     * SiegeTargeting (may be null/empty). Cheap: a ground ring + the known value blocks, never a full scan.
     */
    public static BaseAnalysis analyze(World world, BlockPos core, List<BlockPos> protectedBlocks) {
        BaseAnalysis a = new BaseAnalysis();
        a.core = core;
        if (world == null || core == null) return a;

        // 1. APPROACH GROUND LEVEL. Ring-sample walkable terrain (skipping trees/leaves so a canopy doesn't
        //    read as "ground"), bucket into 4-block bands, and take the most common band -- plus a per-side
        //    average. Also count how much of the ring is open water (ocean signal).
        int[] sideSum = new int[4], sideN = new int[4]; // 0=N(-z) 1=S(+z) 2=E(+x) 3=W(-x)
        Map<Integer, Integer> bands = new HashMap<>();
        int waterHits = 0, ringSamples = 0;
        for (int r = 24; r <= 64; r += 10) {
            for (int d = 0; d < 360; d += 30) {
                double ang = Math.toRadians(d);
                int x = core.getX() + (int) Math.round(Math.cos(ang) * r);
                int z = core.getZ() + (int) Math.round(Math.sin(ang) * r);
                int gy = walkableY(world, x, z);
                ringSamples++;
                if (isWaterTop(world, x, z)) waterHits++;
                bands.merge((gy / 4) * 4, 1, Integer::sum);
                int dx = x - core.getX(), dz = z - core.getZ();
                int side = (Math.abs(dx) >= Math.abs(dz)) ? (dx >= 0 ? 2 : 3) : (dz >= 0 ? 1 : 0);
                sideSum[side] += gy; sideN[side]++;
            }
        }
        int bestBand = 60, bestCount = -1;
        for (Map.Entry<Integer, Integer> e : bands.entrySet())
            if (e.getValue() > bestCount) { bestCount = e.getValue(); bestBand = e.getKey(); }
        a.approachY = bestBand + 2;
        a.northApproachY = sideN[0] > 0 ? sideSum[0] / sideN[0] : a.approachY;
        a.southApproachY = sideN[1] > 0 ? sideSum[1] / sideN[1] : a.approachY;
        a.eastApproachY  = sideN[2] > 0 ? sideSum[2] / sideN[2] : a.approachY;
        a.westApproachY  = sideN[3] > 0 ? sideSum[3] / sideN[3] : a.approachY;
        double oceanRing = ringSamples > 0 ? (double) waterHits / ringSamples : 0.0;

        // 2. VALUE-BLOCK POSTURE. Collect the Y of protected blocks + nearby tile entities and classify each
        //    vs that side's approach level (well below = underground, well above = sky) + over-water.
        // WEIGHTED by IMPORTANCE so the posture reflects the true CENTRE OF GRAVITY, not a flat tile-entity
        // count: protected blocks / storage / beds / spawners / command-district = 5; furnaces/machines/
        // crafting = 2; decorative + random containers = 1. A base reads "underground" only if its VALUABLE
        // blocks are below -- a few decorative TEs can't sway it.
        List<Object[]> contributors = new ArrayList<>(); // {BlockPos pos, Integer weight}
        java.util.Set<BlockPos> seen = new java.util.HashSet<>();
        if (protectedBlocks != null) for (BlockPos p : protectedBlocks)
            if (p != null && near(p, core, 64) && seen.add(p.toImmutable())) contributors.add(new Object[]{p, 5});
        try {
            for (Object o : new ArrayList<>(world.loadedTileEntityList)) {
                if (!(o instanceof TileEntity)) continue;
                BlockPos p = ((TileEntity) o).getPos();
                if (p != null && near(p, core, 64) && seen.add(p.toImmutable()))
                    contributors.add(new Object[]{p, weightOfTile(world, p)});
            }
        } catch (Throwable ignored) {}

        double totalW = 0, belowW = 0, aboveW = 0, waterW = 0;
        for (Object[] c : contributors) {
            BlockPos p = (BlockPos) c[0];
            int w = (Integer) c[1];
            totalW += w;
            int sideY = a.approachYToward(p);
            if (p.getY() <= sideY - 4) belowW += w;
            else if (p.getY() >= sideY + 5) aboveW += w;
            if (isWaterTop(world, p.getX(), p.getZ())) waterW += w;
        }
        a.valueSamples = contributors.size();
        if (totalW > 0) {
            a.pctBelow = belowW / totalW;
            a.pctAbove = aboveW / totalW;
            a.pctOverWater = waterW / totalW;
        }

        // 3. SCORE the four postures and pick the strongest (confidence = its score).
        a.oceanScore = Math.min(1.0, oceanRing * 1.2 + a.pctOverWater * 0.6);
        a.undergroundScore = a.pctBelow;
        a.skyScore = a.pctAbove;
        a.surfaceScore = Math.max(0.0, 1.0 - a.undergroundScore - a.skyScore - a.oceanScore) + 0.15;

        a.primaryType = BaseType.SURFACE; a.typeConfidence = a.surfaceScore;
        if (a.undergroundScore > a.typeConfidence) { a.primaryType = BaseType.UNDERGROUND; a.typeConfidence = a.undergroundScore; }
        if (a.skyScore > a.typeConfidence) { a.primaryType = BaseType.SKY; a.typeConfidence = a.skyScore; }
        if (a.oceanScore > a.typeConfidence) { a.primaryType = BaseType.OCEAN; a.typeConfidence = a.oceanScore; }
        // 4. ACCESS NODES. Find the base's real entrances/shafts/docks so the director can assault or HOLD
        //    them instead of blindly breaching a wall (underground = hold the exits; ocean = take the dock).
        try { detectAccessNodes(world, a); } catch (Throwable ignored) {}

        a.doctrineHints = doctrineFor(a.primaryType);
        return a;
    }

    /**
     * Scan a bounded box around the core for access STRUCTURES -- doors, gates, trapdoors, ladder/vine
     * shafts, and waterline docks/bridges -- and record them (deduped, capped) on the analysis. One-time
     * at siege start, fully guarded. Cheap: a coarse grid (step 2) over a narrow Y window around the
     * approach level, with an early-out once the cap is hit.
     */
    private static void detectAccessNodes(World world, BaseAnalysis a) {
        if (world == null || a == null || a.core == null) return;
        final int R = 32, STEP = 2, CAP = 24;
        final int yLo = a.approachY - 6, yHi = a.approachY + 18;
        java.util.Set<Long> seen = new java.util.HashSet<>();
        for (int dx = -R; dx <= R && a.accessNodes.size() < CAP; dx += STEP) {
            for (int dz = -R; dz <= R && a.accessNodes.size() < CAP; dz += STEP) {
                int x = a.core.getX() + dx, z = a.core.getZ() + dz;
                for (int y = yLo; y <= yHi; y++) {
                    AccessType t = accessTypeAt(world, x, y, z);
                    if (t == null) continue;
                    // Dedupe to a 3-block grid so a double door / tall ladder counts once.
                    long key = (((long) (x >> 2)) * 73856093) ^ (((long) (y >> 2)) * 19349663) ^ ((long) (z >> 2));
                    if (seen.add(key)) {
                        a.accessNodes.add(new AccessNode(new BlockPos(x, y, z), t));
                        if (a.accessNodes.size() >= CAP) break;
                    }
                }
            }
        }
    }

    /** Classify a single block as an access type, or null if it isn't one. Vanilla block classes +
     *  a waterline-wood heuristic for docks/bridges; mod doors usually extend the vanilla classes. */
    private static AccessType accessTypeAt(World world, int x, int y, int z) {
        try {
            BlockPos p = new BlockPos(x, y, z);
            net.minecraft.block.Block b = world.getBlockState(p).getBlock();
            if (b instanceof net.minecraft.block.BlockDoor)      return AccessType.DOOR;
            if (b instanceof net.minecraft.block.BlockFenceGate) return AccessType.GATE;
            if (b instanceof net.minecraft.block.BlockTrapDoor)  return AccessType.TRAPDOOR;
            if (b instanceof net.minecraft.block.BlockLadder
             || b instanceof net.minecraft.block.BlockVine)      return AccessType.LADDER_SHAFT;
            // Dock / bridge: a walkable WOOD-material block sitting directly over (or beside) water, with
            // air to stand on it -- a boarding point for an ocean base or a causeway across the moat.
            Material m = world.getBlockState(p).getMaterial();
            if (m == Material.WOOD && m.isSolid()
                    && world.getBlockState(p.up()).getMaterial() == Material.AIR) {
                if (world.getBlockState(p.down()).getMaterial() == Material.WATER) return AccessType.BRIDGE;
                for (net.minecraft.util.EnumFacing f : net.minecraft.util.EnumFacing.HORIZONTALS)
                    if (world.getBlockState(p.offset(f)).getMaterial() == Material.WATER) return AccessType.DOCK;
            }
        } catch (Throwable ignored) {}
        return null;
    }

    private static String doctrineFor(BaseType t) {
        switch (t) {
            case UNDERGROUND: return "camp entrances; engineers dig access shafts + widen tunnels; hold exits";
            case SKY:         return "bombard underside/supports; helis fast-rope onto platforms; siege towers at low tech";
            case OCEAN:       return "secure shoreline; naval S100 picket; bridges/causeways at low tech; target docks";
            default:          return "bombard walls; engineer breach; surge the corridor; capture heatspots";
        }
    }

    private static boolean near(BlockPos p, BlockPos c, int r) {
        return Math.abs(p.getX() - c.getX()) <= r && Math.abs(p.getZ() - c.getZ()) <= r;
    }

    /** Top WALKABLE terrain Y in a column, skipping vegetation (leaves/logs/plants/snow) so trees and
     *  canopies don't get mistaken for the approach ground. */
    private static int walkableY(World world, int x, int z) {
        try {
            int top = world.getTopSolidOrLiquidBlock(new BlockPos(x, 64, z)).getY();
            for (int y = top; y > 2; y--) {
                Material m = world.getBlockState(new BlockPos(x, y, z)).getMaterial();
                if (m == Material.AIR || m == Material.LEAVES || m == Material.WOOD || m == Material.PLANTS
                        || m == Material.VINE || m == Material.SNOW || m == Material.WATER) continue;
                if (m.isSolid()) return y + 1; // stand on the first real solid below any canopy
            }
        } catch (Throwable ignored) {}
        return 64;
    }

    private static boolean isWaterTop(World world, int x, int z) {
        try {
            BlockPos surface = world.getTopSolidOrLiquidBlock(new BlockPos(x, 64, z)).down();
            return world.getBlockState(surface).getMaterial() == Material.WATER;
        } catch (Throwable ignored) {}
        return false;
    }

    /** Importance weight of a tile entity for posture analysis (per the weighting comment above):
     *  storage / beds / spawners / control = 5, furnaces / machines / crafting = 2, decorative / other = 1.
     *  Matched on class name so it stays mod-agnostic. Tune freely. */
    private static int weightOfTile(World world, BlockPos p) {
        try {
            TileEntity te = world.getTileEntity(p);
            if (te == null) return 1;
            String cls = te.getClass().getName().toLowerCase();
            if (world.getBlockState(p).getBlock() instanceof net.minecraft.block.BlockBed
                    || cls.contains("chest") || cls.contains("barrel") || cls.contains("drawer")
                    || cls.contains("storage") || cls.contains("shulker") || cls.contains("spawner")
                    || cls.contains("drive") || cls.contains("cell") || cls.contains("vault")) {
                return 5;
            }
            if (cls.contains("furnace") || cls.contains("machine") || cls.contains("craft")
                    || cls.contains("smelter") || cls.contains("processor") || cls.contains("assembler")
                    || cls.contains("forge") || cls.contains("press") || cls.contains("brewing")) {
                return 2;
            }
            return 1;
        } catch (Throwable ignored) {
            return 1;
        }
    }
}
