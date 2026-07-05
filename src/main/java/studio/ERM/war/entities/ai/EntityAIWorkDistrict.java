package studio.ERM.war.entities.ai;

import net.minecraft.entity.ai.EntityAIBase;
import net.minecraft.util.math.BlockPos;
import studio.ERM.war.entities.EntityModernCitizen;
import studio.ERM.war.districts.DistrictType;
import studio.ERM.EpochRunnerMod;

import java.util.Random;

/**
 * AI Task: Makes citizens go to their work district during the day and "loiter"
 * in a 20 block radius, providing industry boosts.
 */
public class EntityAIWorkDistrict extends EntityAIBase {
    private final EntityModernCitizen citizen;
    private BlockPos loiterTarget;
    private int loiterTime;
    private final Random rand = new Random();

    private static final int LOITER_RADIUS = 20;
    private static final int MIN_LOITER_TIME = 100; // 5 seconds
    private static final int MAX_LOITER_TIME = 400; // 20 seconds

    public EntityAIWorkDistrict(EntityModernCitizen citizen) {
        this.citizen = citizen;
        this.setMutexBits(1); // Movement task
    }

    @Override
    public boolean shouldExecute() {
        // Only work during daytime
        if (!citizen.world.isDaytime()) {
            return false;
        }

        // Don't work if retreating
        if (citizen.isRetreating()) {
            return false;
        }

        // Must have a job and work site
        if (!citizen.hasWorkSite() || citizen.getCurrentJob() == DistrictType.NONE) {
            return false;
        }

        return true;
    }

    @Override
    public boolean shouldContinueExecuting() {
        // Stop if night comes or starts retreating
        return citizen.world.isDaytime() &&
                !citizen.isRetreating() &&
                citizen.hasWorkSite();
    }

    @Override
    public void startExecuting() {
        // Pick initial loiter position near work site
        pickNewLoiterPosition();
    }

    @Override
    public void updateTask() {
        if (loiterTarget == null) {
            pickNewLoiterPosition();
            return;
        }

        // Navigate to loiter position
        double dist = citizen.getDistanceSq(loiterTarget);

        if (dist > 4.0D) {
            // Still moving to target
            citizen.getNavigator().tryMoveToXYZ(
                    loiterTarget.getX() + 0.5,
                    loiterTarget.getY(),
                    loiterTarget.getZ() + 0.5,
                    0.5D
            );
        } else {
            // Reached target, loiter here
            citizen.getNavigator().clearPath();
            loiterTime--;

            // Randomly look around
            if (rand.nextInt(20) == 0) {
                citizen.getLookHelper().setLookPosition(
                        citizen.posX + (rand.nextDouble() - 0.5) * 2,
                        citizen.posY + citizen.getEyeHeight(),
                        citizen.posZ + (rand.nextDouble() - 0.5) * 2,
                        10.0F,
                        citizen.getVerticalFaceSpeed()
                );
            }

            // Pick new position when loiter time expires
            if (loiterTime <= 0) {
                pickNewLoiterPosition();
            }
        }
    }

    @Override
    public void resetTask() {
        citizen.getNavigator().clearPath();
        loiterTarget = null;
        loiterTime = 0;
    }

    /**
     * Picks a random position within LOITER_RADIUS of the work site.
     */
    private void pickNewLoiterPosition() {
        BlockPos workSite = citizen.getWorkSite();
        if (workSite == null) return;

        // Pick random offset within radius
        int offsetX = rand.nextInt(LOITER_RADIUS * 2) - LOITER_RADIUS;
        int offsetZ = rand.nextInt(LOITER_RADIUS * 2) - LOITER_RADIUS;

        loiterTarget = workSite.add(offsetX, 0, offsetZ);

        // Find ground level at target position
        loiterTarget = citizen.world.getTopSolidOrLiquidBlock(loiterTarget);

        // Set random loiter duration
        loiterTime = MIN_LOITER_TIME + rand.nextInt(MAX_LOITER_TIME - MIN_LOITER_TIME);

        EpochRunnerMod.logger.info("[NUCLEAR-LOG] Citizen " + citizen.getEntityId() +
                " picked new loiter position: " + loiterTarget +
                " (will stay for " + (loiterTime / 20) + " seconds)");
    }
}