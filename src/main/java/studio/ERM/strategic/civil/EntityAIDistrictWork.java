package studio.ERM.strategic.civil;

import net.minecraft.entity.EntityCreature;
import net.minecraft.entity.ai.EntityAIBase;
import net.minecraft.util.EnumHand;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.WorldServer;
import studio.ERM.EpochRunnerMod;

/**
 * THE CIVILIAN HIJACK — injected into an AW2 worker's own AI task list (priority 1, move+look
 * mutex) while the district system holds an assignment for it. Mirror of EntityAIDefendPlanOrder,
 * but the order is a WORK LOOP instead of a station:
 *
 *   TO_SPOT -> walk to the assigned spot inside the district polygon (clamped march when far)
 *   WORKING -> stand there, swing, look around (~12s), then roll the district's output table
 *              and re-roll a fresh spot (natural roaming). After enough yields: deposit run.
 *   TO_DEPOT -> walk to the depot block, unload everything carried, back to work.
 *
 * Yields to AW2's own AI when: no assignment (night/fired/district gone) or recently attacked
 * (workers flee — they are labor, not militia).
 */
public class EntityAIDistrictWork extends EntityAIBase {

    private static final int WORK_TICKS_MIN = 180;
    private static final int WORK_TICKS_MAX = 320;
    private static final int CARRY_BEFORE_DEPOSIT = 5;

    private final EntityCreature npc;
    private int workTimer;
    private int repathCooldown;
    private int swingTimer;

    public EntityAIDistrictWork(EntityCreature npc) {
        this.npc = npc;
        this.setMutexBits(1 | 2);
        EpochRunnerMod.logger.info("[DistrictAI] injected work task into " + npc.getName()
                + " (" + npc.getUniqueID() + ")");
    }

    private DistrictWorkExecutor.Assignment assignment() {
        return DistrictWorkExecutor.assignmentFor(npc);
    }

    private boolean underAttack() {
        return npc.getRevengeTarget() != null && npc.ticksExisted - npc.getRevengeTimer() < 120;
    }

    @Override
    public boolean shouldExecute() {
        return assignment() != null && !npc.isDead && !underAttack();
    }

    @Override
    public boolean shouldContinueExecuting() {
        return shouldExecute();
    }

    @Override
    public void startExecuting() {
        workTimer = 0;
        repathCooldown = 0;
    }

    @Override
    public void updateTask() {
        DistrictWorkExecutor.Assignment a = assignment();
        if (a == null || !(npc.world instanceof WorldServer)) return;
        WorldServer world = (WorldServer) npc.world;

        BlockPos goal = a.depositRun ? a.depot : a.workSpot;
        if (goal == null) return;
        double dd = npc.getDistanceSq(goal.getX() + 0.5, goal.getY(), goal.getZ() + 0.5);

        // ---- at the DEPOT: unload and go back to work ----
        if (a.depositRun) {
            if (dd <= 6.25) {
                DistrictWorkExecutor.depositCarried(world, npc, a);
                a.depositRun = false;
                BlockPos next = DistrictWorkExecutor.pickWorkSpot(world,
                        DistrictRegistry.byUid(world, a.districtUid), a.workSpot);
                if (next != null) a.workSpot = next;
                workTimer = 0;
            } else {
                marchToward(goal);
            }
            return;
        }

        // ---- at the WORK SPOT: work the cycle ----
        if (dd <= 9.0) {
            npc.getNavigator().clearPath();
            workTimer++;
            if (--swingTimer <= 0) {
                swingTimer = 18 + npc.getRNG().nextInt(14);
                npc.swingArm(EnumHand.MAIN_HAND);
                npc.getLookHelper().setLookPosition(
                        goal.getX() + 0.5 + (npc.getRNG().nextDouble() - 0.5) * 3.0,
                        goal.getY(),
                        goal.getZ() + 0.5 + (npc.getRNG().nextDouble() - 0.5) * 3.0,
                        10.0F, npc.getVerticalFaceSpeed());
            }
            int cycle = WORK_TICKS_MIN + (npc.getEntityId() % (WORK_TICKS_MAX - WORK_TICKS_MIN));
            if (workTimer >= cycle) {
                workTimer = 0;
                DistrictWorkExecutor.onWorkCycleComplete(world, npc, a);
                if (DistrictWorkExecutor.carriedCount(npc) >= CARRY_BEFORE_DEPOSIT) {
                    a.depositRun = true;
                } else {
                    BlockPos next = DistrictWorkExecutor.pickWorkSpot(world,
                            DistrictRegistry.byUid(world, a.districtUid), a.workSpot);
                    if (next != null) a.workSpot = next;
                }
            }
        } else {
            marchToward(goal);
        }
    }

    /** Same far-order discipline as the defense AI: clamp long legs to ~12-block waypoints. */
    private void marchToward(BlockPos goal) {
        if (--repathCooldown > 0 && !npc.getNavigator().noPath()) return;
        repathCooldown = 10;
        double gx = goal.getX() + 0.5, gz = goal.getZ() + 0.5;
        double dx = gx - npc.posX, dz = gz - npc.posZ;
        double dH = Math.sqrt(dx * dx + dz * dz);
        if (dH > 14.0) {
            double ux = dx / dH, uz = dz / dH;
            npc.getNavigator().tryMoveToXYZ(npc.posX + ux * 12.0, npc.posY, npc.posZ + uz * 12.0, 0.9D);
        } else {
            npc.getNavigator().tryMoveToXYZ(gx, goal.getY(), gz, 0.9D);
        }
    }

    @Override
    public void resetTask() {
        if (assignment() == null) npc.getNavigator().clearPath();
        workTimer = 0;
    }
}
