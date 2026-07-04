package studio.ERM.strategic.civil.research;

import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.world.World;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * AW2 RESEARCH, read reflectively into our own node graph — the structural source for the Civ-style
 * tree GUI. AW2 already parses research nodes from JSON (ResearchRegistry.ResearchParser); we read
 * each ResearchGoal's name + dependencies + total time, derive a LEFT-TO-RIGHT column from dependency
 * depth (roots at column 0), and expose the player's completed / researchable / completed-deps state
 * via ResearchTracker. Everything degrades to an empty tree if AW2 isn't present.
 *
 * We SCRUB AW2's item resource requirements — our progression is Command Bucks (to queue) + research
 * worker-time (to complete), per {@link ResearchConfig}. AW2's time is kept only as the cost anchor.
 */
public final class ResearchTree {

    private ResearchTree() {}

    public static final class Node {
        public final String id;        // AW2 research name (the tracker key)
        public final String name;      // prettified display name
        public final List<String> deps;
        public final int aw2Time;      // AW2 getTotalResearchTime (cost anchor)
        public int column;             // dependency depth (0 = root)
        public int row;                // assigned lane within the column
        Node(String id, List<String> deps, int aw2Time) {
            this.id = id; this.deps = deps; this.aw2Time = aw2Time; this.name = prettify(id);
        }
    }

    private static boolean resolved, available;
    private static Class<?> cRegistry, cGoal, cTracker;
    private static java.lang.reflect.Method mAll, mName, mTime, mDeps, mCompletedFor, mAddResearch,
            mResearchable, mHasCompleted;
    private static Object trackerInstance;

    private static List<Node> cache;

    private static void resolve() {
        if (resolved) return;
        resolved = true;
        try {
            cRegistry = Class.forName("net.shadowmage.ancientwarfare.core.registry.ResearchRegistry");
            cGoal = Class.forName("net.shadowmage.ancientwarfare.core.research.ResearchGoal");
            cTracker = Class.forName("net.shadowmage.ancientwarfare.core.research.ResearchTracker");
            mAll = cRegistry.getMethod("getAllResearchGoals");
            mName = cGoal.getMethod("getName");
            mTime = cGoal.getMethod("getTotalResearchTime");
            mDeps = cGoal.getMethod("getDependencies");
            trackerInstance = cTracker.getField("INSTANCE").get(null);
            mCompletedFor = cTracker.getMethod("getCompletedResearchFor", World.class, String.class);
            mResearchable = cTracker.getMethod("getResearchableGoals", World.class, String.class);
            mHasCompleted = cTracker.getMethod("hasPlayerCompleted", World.class, String.class, String.class);
            mAddResearch = cTracker.getMethod("addResearch", World.class, String.class, String.class);
            available = true;
        } catch (Throwable t) {
            available = false;
        }
    }

    public static boolean available() { resolve(); return available; }

    /** The full node graph (cached), columns/rows laid out. Empty if AW2 is absent. */
    public static List<Node> nodes() {
        resolve();
        if (cache != null) return cache;
        List<Node> list = new ArrayList<>();
        if (!available) { cache = list; return list; }
        try {
            Collection<?> goals = (Collection<?>) mAll.invoke(null);
            Map<String, Node> byId = new LinkedHashMap<>();
            for (Object g : goals) {
                String id = (String) mName.invoke(g);
                int time = (int) mTime.invoke(g);
                List<String> deps = new ArrayList<>();
                Collection<?> depGoals = (Collection<?>) mDeps.invoke(g);
                for (Object d : depGoals) deps.add((String) mName.invoke(d));
                byId.put(id, new Node(id, deps, time));
            }
            layout(byId);
            list.addAll(byId.values());
        } catch (Throwable t) {
            available = false;
        }
        cache = list;
        return list;
    }

    /** Column = longest dependency chain to a root; row = packed lane within the column. */
    private static void layout(Map<String, Node> byId) {
        Map<String, Integer> depth = new HashMap<>();
        for (Node n : byId.values()) n.column = depthOf(n.id, byId, depth, new HashSet<>());
        Map<Integer, Integer> nextRow = new HashMap<>();
        // Stable order for deterministic rows.
        List<Node> sorted = new ArrayList<>(byId.values());
        sorted.sort((a, b) -> a.column != b.column ? a.column - b.column : a.id.compareTo(b.id));
        for (Node n : sorted) {
            int r = nextRow.getOrDefault(n.column, 0);
            n.row = r;
            nextRow.put(n.column, r + 1);
        }
    }

    private static int depthOf(String id, Map<String, Node> byId, Map<String, Integer> memo, Set<String> stack) {
        Integer m = memo.get(id);
        if (m != null) return m;
        Node n = byId.get(id);
        if (n == null || n.deps.isEmpty() || stack.contains(id)) { memo.put(id, 0); return 0; }
        stack.add(id);
        int max = 0;
        for (String d : n.deps) max = Math.max(max, depthOf(d, byId, memo, stack) + 1);
        stack.remove(id);
        memo.put(id, max);
        return max;
    }

    // ---- per-player state via AW2's tracker ----

    @SuppressWarnings("unchecked")
    public static Set<String> completed(World world, EntityPlayer p) {
        resolve();
        if (!available) return new HashSet<>();
        try { return (Set<String>) mCompletedFor.invoke(trackerInstance, world, p.getName()); }
        catch (Throwable t) { return new HashSet<>(); }
    }

    @SuppressWarnings("unchecked")
    public static Set<String> researchable(World world, EntityPlayer p) {
        resolve();
        if (!available) return new HashSet<>();
        try { return (Set<String>) mResearchable.invoke(trackerInstance, world, p.getName()); }
        catch (Throwable t) { return new HashSet<>(); }
    }

    public static boolean hasCompleted(World world, String playerName, String id) {
        resolve();
        if (!available) return false;
        try { return (boolean) mHasCompleted.invoke(trackerInstance, world, playerName, id); }
        catch (Throwable t) { return false; }
    }

    /** All of a node's dependencies satisfied for this player? */
    public static boolean depsMet(World world, String playerName, Node n) {
        for (String d : n.deps) if (!hasCompleted(world, playerName, d)) return false;
        return true;
    }

    /** Grant a completed research to AW2 (unlocks its recipes) when our node finishes. */
    public static void grant(World world, String playerName, String id) {
        resolve();
        if (!available) return;
        try { mAddResearch.invoke(trackerInstance, world, playerName, id); }
        catch (Throwable ignored) {}
    }

    public static Node byId(String id) {
        for (Node n : nodes()) if (n.id.equals(id)) return n;
        return null;
    }

    static String prettify(String id) {
        String s = id.replace('_', ' ').replace('.', ' ').trim();
        StringBuilder sb = new StringBuilder();
        boolean cap = true;
        for (char c : s.toCharArray()) {
            if (c == ' ') { cap = true; sb.append(c); }
            else { sb.append(cap ? Character.toUpperCase(c) : c); cap = false; }
        }
        return sb.toString();
    }
}
