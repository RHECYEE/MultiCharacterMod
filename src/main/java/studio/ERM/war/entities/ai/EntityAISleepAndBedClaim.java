package studio.ERM.war.entities.ai;

import net.minecraft.block.BlockBed;
import net.minecraft.block.state.IBlockState;
import net.minecraft.entity.ai.EntityAIBase;
import net.minecraft.util.math.AxisAlignedBB;
import net.minecraft.util.math.BlockPos;

import studio.ERM.war.entities.EntityModularCitizen;
import studio.ERM.war.world.BedClaimData;

import java.util.UUID;

/**
 * Finds a nearby bed, claims it, and drives the citizen to sleep there at night / retreat.
 *
 * FIXES:
 * - Sets a synced "sleeping pose" flag so the renderer can lay the NPC down.
 * - Clears the sleeping flag during daytime / when no longer at the bed.
 */
public class EntityAISleepAndBedClaim extends EntityAIBase {

    private final EntityModularCitizen citizen;
    private final int searchRadius;

    private int scanCooldown = 0;
    private static final int SCAN_COOLDOWN_TICKS = 200;
    private static final double WALK_SPEED = 0.85D;

    public EntityAISleepAndBedClaim(EntityModularCitizen citizen, int searchRadius) {
        this.citizen = citizen;
        this.searchRadius = Math.max(8, searchRadius);
        this.setMutexBits(1);
    }

    @Override
    public boolean shouldExecute() {
        if (citizen == null || citizen.world == null || citizen.world.isRemote) return false;

        if (citizen.getClaimedBedPos() == null) {
            if (scanCooldown > 0) {
                scanCooldown--;
                return false;
            }
            return true;
        }

        return (!citizen.world.isDaytime()) || citizen.isRetreating();
    }

    @Override
    public boolean shouldContinueExecuting() {
        if (citizen == null || citizen.world == null || citizen.world.isRemote) return false;

        BlockPos bed = citizen.getClaimedBedPos();
        if (bed == null) return false;

        return (!citizen.world.isDaytime()) || citizen.isRetreating();
    }

    @Override
    public void startExecuting() {
        if (citizen == null || citizen.world == null || citizen.world.isRemote) return;

        if (citizen.getClaimedBedPos() == null) {
            tryFindAndClaimBed();
            scanCooldown = SCAN_COOLDOWN_TICKS;
        }
    }

    @Override
    public void resetTask() {
        if (citizen != null) {
            citizen.setSleepingPose(false);
        }
    }

    @Override
    public void updateTask() {
        if (citizen == null || citizen.world == null || citizen.world.isRemote) return;

        BlockPos bed = citizen.getClaimedBedPos();
        if (bed == null) {
            citizen.setSleepingPose(false);
            return;
        }

        boolean shouldSleepNow = (!citizen.world.isDaytime()) || citizen.isRetreating();

        // walk to bed
        double distSq = citizen.getDistanceSq(bed);
        if (distSq > 4.0D) {
            citizen.setSleepingPose(false);
            citizen.getNavigator().tryMoveToXYZ(bed.getX() + 0.5D, bed.getY() + 0.1D, bed.getZ() + 0.5D, WALK_SPEED);
        } else {
            citizen.getNavigator().clearPath();
            citizen.motionX = 0.0D;
            citizen.motionZ = 0.0D;

            // Only enter sleeping pose when it makes sense (night/retreat) and we're at the bed
            citizen.setSleepingPose(shouldSleepNow);
        }
    }

    private void tryFindAndClaimBed() {
        BlockPos base = citizen.getPosition();
        AxisAlignedBB box = new AxisAlignedBB(
                base.getX() - searchRadius, base.getY() - 8, base.getZ() - searchRadius,
                base.getX() + searchRadius, base.getY() + 8, base.getZ() + searchRadius
        );

        BedClaimData data = BedClaimData.get(citizen.world);
        UUID self = citizen.getUniqueID();

        BlockPos best = null;
        double bestDist = Double.MAX_VALUE;

        BlockPos.MutableBlockPos cur = new BlockPos.MutableBlockPos();
        for (int x = (int) box.minX; x <= box.maxX; x++) {
            for (int y = (int) box.minY; y <= box.maxY; y++) {
                for (int z = (int) box.minZ; z <= box.maxZ; z++) {
                    cur.setPos(x, y, z);
                    IBlockState state = citizen.world.getBlockState(cur);
                    if (!(state.getBlock() instanceof BlockBed)) continue;

                    BlockPos head = BedClaimData.normalizeToBedHead(citizen.world, cur.toImmutable());
                    if (head == null) continue;

                    if (data.isClaimedByOther(citizen.world, head, self)) continue;

                    double d = citizen.getDistanceSq(head);
                    if (d < bestDist) {
                        bestDist = d;
                        best = head;
                    }
                }
            }
        }

        if (best != null) {
            boolean ok = data.claimBed(citizen.world, best, self);
            if (ok) {
                citizen.setClaimedBedPos(best.toImmutable());
            }
        }
    }
}
