package studio.ERM.war.entities.ai;

import net.minecraft.entity.ai.EntityAIBase;
import net.minecraft.entity.player.EntityPlayer;

import studio.ERM.war.entities.EntityModularCitizen;

/**
 * Harass behavior:
 * - If citizen needs bed or food, follow the nearest player (within radius).
 * - Speech is handled by EntityModularCitizen.processInteract() (per your requirement).
 */
public class EntityAIHarassForNeeds extends EntityAIBase {

    private static final double FOLLOW_SPEED = 0.95D;
    private static final float FOLLOW_RANGE = 16.0F;

    private final EntityModularCitizen citizen;
    private EntityPlayer targetPlayer;

    public EntityAIHarassForNeeds(EntityModularCitizen citizen) {
        this.citizen = citizen;
        this.setMutexBits(1); // movement
    }

    @Override
    public boolean shouldExecute() {
        if (citizen.isRetreating()) return false;
        if (!(citizen.needsBed() || citizen.needsFood())) return false;

        targetPlayer = citizen.world.getClosestPlayerToEntity(citizen, FOLLOW_RANGE);
        return targetPlayer != null;
    }

    @Override
    public boolean shouldContinueExecuting() {
        if (citizen.isRetreating()) return false;
        if (!(citizen.needsBed() || citizen.needsFood())) return false;
        if (targetPlayer == null || !targetPlayer.isEntityAlive()) return false;
        return citizen.getDistance(targetPlayer) <= FOLLOW_RANGE * 1.5F;
    }

    @Override
    public void updateTask() {
        if (targetPlayer == null) return;
        citizen.getNavigator().tryMoveToEntityLiving(targetPlayer, FOLLOW_SPEED);
    }

    @Override
    public void resetTask() {
        targetPlayer = null;
        citizen.getNavigator().clearPath();
    }
}
