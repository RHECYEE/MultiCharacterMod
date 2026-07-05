package studio.ERM.strategic.civil;

import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.PriorityQueue;
import java.util.Set;

/**
 * ROAD-PREFERRED ROUTING — the strategic navigation layer roads plug into.
 *
 * Pathfinding is NOT restricted to roads; roads REDUCE path cost. The graph is every road point
 * (consecutive points = edges, with nearby points of any two roads fused into junctions). Edge
 * cost scales with segment CONDITION — a well-kept road is roughly half off-road cost, a
 * DESTROYED one is worse than open ground, so traffic naturally detours around damage.
 *
 * route(world, from, to) returns intermediate waypoints (road entry -> along the network -> road
 * exit), or null when the network doesn't actually help (detour too long / no road nearby) —
 * callers then walk direct exactly as before. Consumers this round: couriers. Traders, patrols,
 * convoys and formations adopt the same call later.
 */
public final class RoadRouter {

    /** Off-road movement cost per block = 1.0; road cost per block by condition. */
    private static final double[] CONDITION_COST = {0.45, 0.5, 0.65, 0.85, 1.6, 0.6};
    private static final double JUNCTION_SNAP = 12.0;   // fuse road points closer than this
    private static final double MAX_ENTRY_DIST = 64.0;  // how far off-road an entry point may be
    private static final double DETOUR_FACTOR = 1.35;   // give up when road path exceeds direct x this

    private RoadRouter() {}

    private static class Node {
        final int x, z;
        final List<int[]> edges = new ArrayList<>(); // {nodeIndex, cost*1000}
        Node(int x, int z) { this.x = x; this.z = z; }
    }

    /**
     * Waypoints from {@code from} to {@code to} preferring roads, or null when direct is as good.
     * Waypoints are XZ only (y=0); consumers stand on the surface at each point.
     */
    public static List<BlockPos> route(World world, BlockPos from, BlockPos to) {
        List<CivilMarker> roads = new ArrayList<>();
        for (CivilMarker m : CivilPlanData.get(world).markers) {
            if (m.isRoad() && m.points.size() >= 2) roads.add(m);
        }
        if (roads.isEmpty()) return null;

        double direct = dist(from.getX(), from.getZ(), to.getX(), to.getZ());
        if (direct < 48.0) return null; // short hops never need the network

        // ---- build the graph (roads are few; rebuilding per call is cheap) ----
        List<Node> nodes = new ArrayList<>();
        for (CivilMarker road : roads) {
            road.ensureSegArrays();
            int base = nodes.size();
            for (BlockPos p : road.points) nodes.add(new Node(p.getX(), p.getZ()));
            for (int i = 0; i < road.segmentCount(); i++) {
                Node a = nodes.get(base + i), b = nodes.get(base + i + 1);
                int cond = road.segCondition[i];
                double mult = CONDITION_COST[Math.max(0, Math.min(cond, CONDITION_COST.length - 1))];
                int cost = (int) (dist(a.x, a.z, b.x, b.z) * mult * 1000);
                a.edges.add(new int[]{base + i + 1, cost});
                b.edges.add(new int[]{base + i, cost});
            }
        }
        // Junctions: fuse near-touching points across (or within) roads with a free-ish link.
        for (int i = 0; i < nodes.size(); i++) {
            for (int j = i + 1; j < nodes.size(); j++) {
                double d = dist(nodes.get(i).x, nodes.get(i).z, nodes.get(j).x, nodes.get(j).z);
                if (d > 0 && d <= JUNCTION_SNAP) {
                    int cost = (int) (d * 0.5 * 1000);
                    nodes.get(i).edges.add(new int[]{j, cost});
                    nodes.get(j).edges.add(new int[]{i, cost});
                }
            }
        }

        // ---- entry/exit: nearest node to each end ----
        int entry = nearestNode(nodes, from.getX(), from.getZ(), MAX_ENTRY_DIST);
        int exit = nearestNode(nodes, to.getX(), to.getZ(), MAX_ENTRY_DIST);
        if (entry == -1 || exit == -1 || entry == exit) return null;

        // ---- A* over the graph ----
        int n = nodes.size();
        double[] g = new double[n];
        int[] cameFrom = new int[n];
        java.util.Arrays.fill(g, Double.MAX_VALUE);
        java.util.Arrays.fill(cameFrom, -1);
        g[entry] = 0;
        PriorityQueue<int[]> open = new PriorityQueue<>((a, b) -> Double.compare(
                g[a[0]] + h(nodes, a[0], nodes.get(exit)), g[b[0]] + h(nodes, b[0], nodes.get(exit))));
        open.add(new int[]{entry});
        Set<Integer> closed = new HashSet<>();
        while (!open.isEmpty()) {
            int cur = open.poll()[0];
            if (cur == exit) break;
            if (!closed.add(cur)) continue;
            for (int[] e : nodes.get(cur).edges) {
                double ng = g[cur] + e[1] / 1000.0;
                if (ng < g[e[0]]) {
                    g[e[0]] = ng;
                    cameFrom[e[0]] = cur;
                    open.add(new int[]{e[0]});
                }
            }
        }
        if (g[exit] == Double.MAX_VALUE) return null;

        // Total trip vs direct: only take the road when it actually helps.
        double total = dist(from.getX(), from.getZ(), nodes.get(entry).x, nodes.get(entry).z)
                + g[exit]
                + dist(nodes.get(exit).x, nodes.get(exit).z, to.getX(), to.getZ());
        if (total > direct * DETOUR_FACTOR) return null;

        // Reconstruct entry -> exit.
        List<BlockPos> path = new ArrayList<>();
        for (int cur = exit; cur != -1; cur = cameFrom[cur]) {
            path.add(new BlockPos(nodes.get(cur).x, 0, nodes.get(cur).z));
            if (cur == entry) break;
        }
        Collections.reverse(path);
        return path.size() >= 2 ? path : null;
    }

    private static int nearestNode(List<Node> nodes, int x, int z, double maxDist) {
        int best = -1;
        double bd = maxDist * maxDist;
        for (int i = 0; i < nodes.size(); i++) {
            double dx = nodes.get(i).x - x, dz = nodes.get(i).z - z;
            double d = dx * dx + dz * dz;
            if (d < bd) { bd = d; best = i; }
        }
        return best;
    }

    private static double h(List<Node> nodes, int i, Node goal) {
        return dist(nodes.get(i).x, nodes.get(i).z, goal.x, goal.z) * 0.45; // admissible (best road)
    }

    private static double dist(int x1, int z1, int x2, int z2) {
        double dx = x2 - x1, dz = z2 - z1;
        return Math.sqrt(dx * dx + dz * dz);
    }
}
