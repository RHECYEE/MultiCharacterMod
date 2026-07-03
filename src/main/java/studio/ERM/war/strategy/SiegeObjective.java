package studio.ERM.war.strategy;

import net.minecraft.util.math.BlockPos;

import java.util.ArrayList;
import java.util.List;

/**
 * One node in the siege's objective graph -- a typed view over the informal heatspot list. The breach
 * corridor is the ROOT; every interior loot/heat target is a node hanging off it. The graph is built as a
 * read-only SHADOW of the working {@code heatspots}/{@code capturedSpots} state (see
 * {@code SiegeDirector.buildObjectiveGraph}), so it changes no behavior on its own; it is the substrate
 * the squad-allocation scorer ranks over.
 *
 * Scoring inputs consumed by {@link SquadAllocation}: {@code priority} (interior-value rank),
 * {@code pathCost} (rough reach cost), {@code vertical} (loot above the breach foot costs more to reach).
 * {@code defenders}/{@code neighbors} are reserved for later -- there is no visible threat signal yet, so
 * {@code defenders} stays 0 (a correct-but-invisible enemy count would be wasted RTS depth).
 */
public final class SiegeObjective {
    public final BlockPos pos;
    public final int priority;      // higher = more valuable (nearest-core scan order = value rank)
    public final int pathCost;      // rough cost to reach (distance proxy from the breach/staging)
    public final boolean vertical;  // loot sits above the breach foot -> needs a vertical climb
    public final boolean root;      // true only for the breach-corridor root node
    public int defenders = 0;       // reserved/stub: no visible threat signal yet
    public boolean captured = false;
    public boolean pathCleared = false;
    public final List<SiegeObjective> neighbors = new ArrayList<>();

    public SiegeObjective(BlockPos pos, int priority, int pathCost, boolean vertical, boolean root) {
        this.pos = pos;
        this.priority = priority;
        this.pathCost = pathCost;
        this.vertical = vertical;
        this.root = root;
    }

    /** The breach corridor itself -- the root every interior objective hangs off. */
    public static SiegeObjective breachRoot(BlockPos breach) {
        return new SiegeObjective(breach, Integer.MAX_VALUE, 0, false, true);
    }
}
