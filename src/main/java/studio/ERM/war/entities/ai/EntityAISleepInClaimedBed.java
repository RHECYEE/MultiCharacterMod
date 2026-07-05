package studio.ERM.war.entities.ai;

import net.minecraft.entity.ai.EntityAIBase;
import net.minecraft.util.math.BlockPos;

import studio.ERM.war.entities.EntityModernCitizen;

public class EntityAISleepInClaimedBed extends EntityAIBase {

    private final EntityModernCitizen citizen;
    private boolean reached;

    private static final double SPEED = 0.85D;

    public EntityAISleepInClaimedBed(EntityModernCitizen citizen) {
        this.citizen = citizen;
        this.setMutexBits(1);
    }

    @Override
    public boolean shouldExecute() {
        if (citizen == null || citizen.world == null || citizen.world.isRemote) return false;
        if (!citizen.hasBed()) return false;

        boolean night = !citizen.world.isDaytime();
        boolean retreat = citizen.isRetreating();
        return night || retreat;
    }

    @Override
    public boolean shouldContinueExecuting() {
        if (citizen == null || citizen.world == null || citizen.world.isRemote) return false;
        if (!citizen.hasBed()) return false;

        boolean night = !citizen.world.isDaytime();
        boolean retreat = citizen.isRetreating();
        return night || retreat;
    }

    @Override
    public void startExecuting() {
        reached = false;
    }

    @Override
    public void updateTask() {
        BlockPos bed = citizen.getAssignedBed();
        if (bed == null) return;

        double distSq = citizen.getDistanceSq(bed);
        if (distSq > 4.0D) {
            citizen.getNavigator().tryMoveToXYZ(bed.getX() + 0.5D, bed.getY(), bed.getZ() + 0.5D, SPEED);
            reached = false;
        } else {
            citizen.getNavigator().clearPath();
            citizen.motionX = 0.0D;
            citizen.motionZ = 0.0D;
            reached = true;
        }
    }
}
