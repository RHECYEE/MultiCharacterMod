package studio.ERM.war.entities.ai;

import net.minecraft.entity.ai.EntityAIBase;
import net.minecraft.entity.monster.IMob;
import net.minecraft.util.math.AxisAlignedBB;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.World;
import net.minecraft.entity.EntityLivingBase;
import net.minecraft.entity.EntityCreature;
import net.minecraft.entity.ai.RandomPositionGenerator;

import studio.ERM.war.entities.EntityModularCitizen;

import javax.annotation.Nullable;

/**
 * Retreat behavior:
 * - If war is active: stop working tasks, go indoors / to bed.
 * - If hostiles are within 20 blocks: flee away from them.
 */
public class EntityAIRetreatOnWar extends EntityAIBase {

    private static final double WALK_SPEED = 0.9D;
    private static final double FLEE_SPEED = 1.15D;
    private static final double HOSTILE_RADIUS = 20.0D;
    private static final int CHECK_INTERVAL_TICKS = 20;

    private final EntityModularCitizen citizen;
    private int tickCounter = 0;

    public EntityAIRetreatOnWar(EntityModularCitizen citizen) {
        this.citizen = citizen;
        this.setMutexBits(1); // movement
    }

    @Override
    public boolean shouldExecute() {
        return citizen.isRetreating();
    }

    @Override
    public boolean shouldContinueExecuting() {
        return citizen.isRetreating();
    }

    @Override
    public void updateTask() {
        tickCounter++;
        if (tickCounter < CHECK_INTERVAL_TICKS) {
            return;
        }
        tickCounter = 0;

        // 1) If hostiles nearby: flee away
        EntityLivingBase hostile = findNearestHostile(citizen.world, citizen.getPosition());
        if (hostile != null) {
            Vec3d fleeTarget = RandomPositionGenerator.findRandomTargetBlockAwayFrom((EntityCreature) citizen, 16, 7, new Vec3d(hostile.posX, hostile.posY, hostile.posZ));
            if (fleeTarget != null) {
                citizen.getNavigator().tryMoveToXYZ(fleeTarget.x, fleeTarget.y, fleeTarget.z, FLEE_SPEED);
                return;
            }
        }

        // 2) Otherwise: go to bed position if available
        BlockPos bed = citizen.getClaimedBedPos();
        if (bed != null) {
            citizen.getNavigator().tryMoveToXYZ(bed.getX() + 0.5D, bed.getY() + 0.0D, bed.getZ() + 0.5D, WALK_SPEED);
        }
    }

    @Nullable
    private EntityLivingBase findNearestHostile(World world, BlockPos pos) {
        AxisAlignedBB box = new AxisAlignedBB(
                pos.getX() - HOSTILE_RADIUS, pos.getY() - 8, pos.getZ() - HOSTILE_RADIUS,
                pos.getX() + HOSTILE_RADIUS, pos.getY() + 8, pos.getZ() + HOSTILE_RADIUS
        );
        EntityLivingBase nearest = null;
        double best = Double.MAX_VALUE;

        for (EntityLivingBase e : world.getEntitiesWithinAABB(EntityLivingBase.class, box)) {
            if (e == null) continue;
            if (e == citizen) continue;
            if (!(e instanceof IMob)) continue;
            if (!e.isEntityAlive()) continue;

            double d = citizen.getDistanceSq(e);
            if (d < best) {
                best = d;
                nearest = e;
            }
        }
        return nearest;
    }
}
