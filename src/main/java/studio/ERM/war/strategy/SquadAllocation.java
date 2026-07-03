package studio.ERM.war.strategy;

import java.util.ArrayList;
import java.util.List;

/**
 * Pure squad-to-objective allocation math (no world state, no side effects). Two functions:
 *
 *   rankObjectives -- score each uncaptured objective and return them best-first, using the spec formula
 *       score = priority*4 + enemy*3 + tactical*3 - pathCost*2 - vertical*2 - assignedStrength.
 *       Only priority / pathCost / vertical have a VISIBLE source today; enemy / tactical /
 *       assignedStrength are stubbed to 0 -- a hidden score term the player can never perceive is wasted
 *       RTS depth. They stay in the formula (future-proof) but contribute nothing until a visible threat
 *       signal exists.
 *
 *   splitForces -- the 40/25/25/10 main-effort distribution: how many of N squads go to the top four
 *       ranked objectives. The player-legible signature is CONCENTRATION on the main effort (a thick mass
 *       on the top room, thin detachments elsewhere) -- not the arithmetic.
 */
public final class SquadAllocation {
    private SquadAllocation() {}

    /** The 40/25/25/10 main-effort split across the top objectives. */
    public static final double[] WEIGHTS = { 0.40, 0.25, 0.25, 0.10 };

    /** Spec score for one objective. Higher = more deserving of the main effort. */
    public static int scoreOf(SiegeObjective o) {
        if (o == null) return Integer.MIN_VALUE;
        final int enemy = 0, tactical = 0, assignedStrength = 0; // stub 0: no visible signal yet (D3)
        final int vertical = o.vertical ? 1 : 0;
        return o.priority * 4 + enemy * 3 + tactical * 3 - o.pathCost * 2 - vertical * 2 - assignedStrength;
    }

    /** Uncaptured, non-root objectives, best score first. */
    public static List<SiegeObjective> rankObjectives(List<SiegeObjective> graph) {
        List<SiegeObjective> out = new ArrayList<>();
        if (graph == null) return out;
        for (SiegeObjective o : graph) if (o != null && !o.root && !o.captured) out.add(o);
        out.sort((a, b) -> Integer.compare(scoreOf(b), scoreOf(a)));
        return out;
    }

    /**
     * Distribute {@code squadCount} squads across {@code objectiveCount} ranked objectives in a 40/25/25/10
     * split (index 0 = main effort). Returns an int[] of length {@code objectiveCount}; objectives beyond
     * the top four get 0 from the split (the loot-timeout fallback still captures them). Rounding remainder
     * folds into the main effort so no squad is lost.
     */
    public static int[] splitForces(int squadCount, int objectiveCount) {
        int n = Math.max(0, objectiveCount);
        int[] alloc = new int[n];
        if (n == 0 || squadCount <= 0) return alloc;
        int assigned = 0;
        int buckets = Math.min(n, WEIGHTS.length);
        for (int i = 0; i < buckets; i++) {
            int share = (int) Math.floor(squadCount * WEIGHTS[i]);
            alloc[i] = share;
            assigned += share;
        }
        alloc[0] += squadCount - assigned; // remainder (rounding + objectives beyond top 4) -> main effort
        return alloc;
    }
}
