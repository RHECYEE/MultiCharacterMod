package studio.ERM.strategic.civil;

import net.minecraft.entity.EntityCreature;
import net.minecraft.entity.ai.EntityAIBase;
import net.minecraft.util.EnumHand;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.WorldServer;

/**
 * THE ROAD CREW HIJACK — injected (priority 0, move+look mutex) while RoadNetworkManager holds a
 * road assignment for this worker. Walk to the next damaged spot, swing at it for a moment, place
 * the material (one visible block per stop — the physical-execution doctrine), move to the next.
 * Yields to AW2's own AI when unassigned or recently attacked (builders are labor, not militia).
 */
public class EntityAIRoadRepair extends EntityAIBase {

    private static final int SWINGS_PER_FIX = 3;

    private final EntityCreature npc;
    private int swingTimer;
    private int swings;
    private int repathCooldown;

    public EntityAIRoadRepair(EntityCreature npc) {
        this.npc = npc;
        this.setMutexBits(1 | 2);
    }

    private RoadNetworkManager.RepairOp currentOp() {
        Integer road = RoadNetworkManager.builderRoad(npc);
        return road == null ? null : RoadNetworkManager.peekRepair(road);
    }

    private boolean underAttack() {
        return npc.getRevengeTarget() != null && npc.ticksExisted - npc.getRevengeTimer() < 120;
    }

    @Override
    public boolean shouldExecute() {
        return currentOp() != null && !npc.isDead && !underAttack();
    }

    @Override
    public boolean shouldContinueExecuting() {
        return shouldExecute();
    }

    @Override
    public void startExecuting() {
        swings = 0;
        swingTimer = 0;
        repathCooldown = 0;
    }

    @Override
    public void updateTask() {
        RoadNetworkManager.RepairOp op = currentOp();
        if (op == null || !(npc.world instanceof WorldServer)) return;
        WorldServer world = (WorldServer) npc.world;

        double dd = npc.getDistanceSq(op.pos.getX() + 0.5, op.pos.getY(), op.pos.getZ() + 0.5);
        if (dd > 7.0) {
            marchToward(op.pos);
            swings = 0;
            return;
        }

        // On site: face the hole, swing a few times, then fix it.
        npc.getNavigator().clearPath();
        npc.getLookHelper().setLookPosition(op.pos.getX() + 0.5, op.pos.getY(),
                op.pos.getZ() + 0.5, 10.0F, npc.getVerticalFaceSpeed());
        if (--swingTimer <= 0) {
            swingTimer = 14;
            npc.swingArm(EnumHand.MAIN_HAND);
            if (++swings >= SWINGS_PER_FIX) {
                swings = 0;
                Integer road = RoadNetworkManager.builderRoad(npc);
                if (road != null) RoadNetworkManager.completeRepair(world, npc, road);
            }
        }
    }

    /** Same far-order discipline as the district AI: clamp long legs to ~12-block waypoints. */
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
        if (currentOp() == null) npc.getNavigator().clearPath();
        swings = 0;
    }
}
