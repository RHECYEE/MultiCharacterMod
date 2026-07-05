package studio.ERM.strategic.civil.logistics;

import net.minecraft.entity.EntityCreature;
import net.minecraft.entity.ai.EntityAIBase;
import net.minecraft.util.EnumHand;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.WorldServer;
import studio.ERM.strategic.civil.RoadRouter;

import java.util.List;

/**
 * THE COURIER HIJACK — injected (priority 0, move+look mutex) while the LogisticsManager holds a
 * job for this worker. The classic courier loop, physically walked:
 *
 *   TO_SOURCE -> walk to the source depot (ROADS PREFERRED via RoadRouter), swing, load cargo
 *   TO_DEST   -> carry it (chest in hand) to the destination depot, swing, unload
 *   released  -> back to the labor pool for the next assignment
 *
 * Yields to AW2's own AI when unassigned or recently attacked (couriers drop nothing but their
 * dignity — cargo loss on death is handled by the manager).
 */
public class EntityAICourierWork extends EntityAIBase {

    private final EntityCreature npc;
    private int repathCooldown;
    /** Road-preferred waypoints for the current leg (null = walk direct). */
    private List<BlockPos> waypoints;
    private int waypointIdx;
    private int legState = -1; // job.state the cached waypoints were computed for

    public EntityAICourierWork(EntityCreature npc) {
        this.npc = npc;
        this.setMutexBits(1 | 2);
    }

    private CourierJob job() {
        return npc.world instanceof WorldServer
                ? LogisticsManager.jobFor((WorldServer) npc.world, npc) : null;
    }

    private boolean underAttack() {
        return npc.getRevengeTarget() != null && npc.ticksExisted - npc.getRevengeTimer() < 120;
    }

    @Override
    public boolean shouldExecute() {
        CourierJob j = job();
        return j != null && !j.done && j.state != CourierJob.STATE_PENDING
                && !npc.isDead && !underAttack();
    }

    @Override
    public boolean shouldContinueExecuting() {
        return shouldExecute();
    }

    @Override
    public void startExecuting() {
        repathCooldown = 0;
        waypoints = null;
        legState = -1;
    }

    @Override
    public void updateTask() {
        CourierJob job = job();
        if (job == null || !(npc.world instanceof WorldServer)) return;
        WorldServer world = (WorldServer) npc.world;

        BlockPos goal = job.state == CourierJob.STATE_TO_SOURCE ? job.src : job.dst;

        // New leg -> ask the road network for a preferred route once.
        if (legState != job.state) {
            legState = job.state;
            waypoints = RoadRouter.route(world, npc.getPosition(), goal);
            waypointIdx = 0;
        }

        // Arrived at the depot?
        double dd = npc.getDistanceSq(goal.getX() + 0.5, goal.getY() + 0.5, goal.getZ() + 0.5);
        if (dd <= 7.0) {
            npc.getNavigator().clearPath();
            npc.swingArm(EnumHand.MAIN_HAND);
            if (job.state == CourierJob.STATE_TO_SOURCE) {
                LogisticsManager.arrivedAtSource(world, npc, job);
                waypoints = null;
                legState = -1;
            } else {
                LogisticsManager.arrivedAtDest(world, npc, job);
            }
            return;
        }

        // Follow the road waypoints, then close on the depot itself.
        BlockPos target = goal;
        if (waypoints != null && waypointIdx < waypoints.size()) {
            BlockPos wp = waypoints.get(waypointIdx);
            BlockPos stand = world.isBlockLoaded(wp, false)
                    ? world.getTopSolidOrLiquidBlock(new BlockPos(wp.getX(), 64, wp.getZ())) : wp;
            double wd = npc.getDistanceSq(wp.getX() + 0.5, npc.posY, wp.getZ() + 0.5);
            if (wd <= 12.0) {
                waypointIdx++;
            } else {
                target = stand;
            }
        }
        marchToward(target);
    }

    /** Clamped legs like every hijack AI, so far orders don't stall vanilla navigation. */
    private void marchToward(BlockPos goal) {
        if (--repathCooldown > 0 && !npc.getNavigator().noPath()) return;
        repathCooldown = 10;
        double gx = goal.getX() + 0.5, gz = goal.getZ() + 0.5;
        double dx = gx - npc.posX, dz = gz - npc.posZ;
        double dH = Math.sqrt(dx * dx + dz * dz);
        if (dH > 14.0) {
            double ux = dx / dH, uz = dz / dH;
            npc.getNavigator().tryMoveToXYZ(npc.posX + ux * 12.0, npc.posY, npc.posZ + uz * 12.0, 0.95D);
        } else {
            npc.getNavigator().tryMoveToXYZ(gx, goal.getY(), gz, 0.95D);
        }
    }

    @Override
    public void resetTask() {
        if (job() == null) npc.getNavigator().clearPath();
        waypoints = null;
        legState = -1;
    }
}
