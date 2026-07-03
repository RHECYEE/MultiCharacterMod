package studio.ERM.war.strategy;

/**
 * The state of the assault route from the staging area to the breach, derived PURELY from the engineer
 * work queue (see {@code SiegeDirector.recomputeRouteStatus}). It is the ONE shared signal between two
 * subsystems that otherwise had no common language:
 *
 *   - the ENGINEERS, who clear the route (they own the transition NEEDS_ENGINEER -> BEING_CLEARED -> CLEARED), and
 *   - the VEHICLES, who must wait for a CLEARED route before rolling through the breach instead of
 *     bulldozing into an un-breached wall.
 *
 * States:
 *   OPEN              nothing to clear -- no obstacle work was ever queued, or the route is not planned yet.
 *   NEEDS_ENGINEER    at least one obstacle task is queued and UNCLAIMED -- a crew needs to start it.
 *   BEING_CLEARED     a crew has CLAIMED an obstacle task and is visibly working it.
 *   CLEARED           all route work is done (the breach is open and the path is built) -- safe to roll through.
 *   BLOCKED_TOO_HARD  a unit reported it could not pass and the route could not be cleared -- sticky, set
 *                     externally (never derived); reset only by a fresh plan or a real clear.
 */
public enum RouteStatus {
    OPEN,
    NEEDS_ENGINEER,
    BEING_CLEARED,
    CLEARED,
    BLOCKED_TOO_HARD
}
