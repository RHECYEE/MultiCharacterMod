package studio.ERM.war.entities.ai;

import net.minecraft.entity.ai.EntityAIBase;
import net.minecraft.util.math.BlockPos;

import studio.ERM.war.entities.EntityModularCitizen;

import javax.annotation.Nullable;

public class EntityAIPatrol extends EntityAIBase {

    private static final double WALK_SPEED = 0.9D;
    private static final double ARRIVE_DISTANCE_SQ = 2.25D;

    private final EntityModularCitizen citizen;

    public EntityAIPatrol(EntityModularCitizen citizen) {
        this.citizen = citizen;
        this.setMutexBits(1);
    }

    @Override
    public boolean shouldExecute() {
        if (citizen.isRetreating()) return false;
        if (!citizen.isPatrolling()) return false;
        return citizen.getPatrolPoint1() != null && citizen.getPatrolPoint2() != null;
    }

    @Override
    public boolean shouldContinueExecuting() {
        if (citizen.isRetreating()) return false;
        if (!citizen.isPatrolling()) return false;
        return citizen.getPatrolPoint1() != null && citizen.getPatrolPoint2() != null;
    }

    @Override
    public void startExecuting() {
        moveToCurrentTarget();
    }

    @Override
    public void updateTask() {
        BlockPos target = getCurrentTarget();
        if (target == null) return;

        if (citizen.getDistanceSqToCenter(target) <= ARRIVE_DISTANCE_SQ) {
            citizen.setMovingToPoint1(!citizen.isMovingToPoint1());
            citizen.getNavigator().clearPath();
            moveToCurrentTarget();
        } else if (citizen.getNavigator().noPath()) {
            moveToCurrentTarget();
        }
    }

    @Override
    public void resetTask() {
        citizen.getNavigator().clearPath();
    }

    private void moveToCurrentTarget() {
        BlockPos target = getCurrentTarget();
        if (target == null) return;
        citizen.getNavigator().tryMoveToXYZ(target.getX() + 0.5D, target.getY(), target.getZ() + 0.5D, WALK_SPEED);
    }

    @Nullable
    private BlockPos getCurrentTarget() {
        BlockPos a = citizen.getPatrolPoint1();
        BlockPos b = citizen.getPatrolPoint2();
        if (a == null || b == null) return null;
        return citizen.isMovingToPoint1() ? a : b;
    }
}
