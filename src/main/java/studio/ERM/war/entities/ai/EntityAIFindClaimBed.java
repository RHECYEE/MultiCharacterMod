package studio.ERM.war.entities.ai;

import net.minecraft.block.BlockBed;
import net.minecraft.block.state.IBlockState;
import net.minecraft.entity.ai.EntityAIBase;
import net.minecraft.util.math.AxisAlignedBB;
import net.minecraft.util.math.BlockPos;

import studio.ERM.war.entities.EntityModernCitizen;
import studio.ERM.war.world.BedClaimData;

import java.util.UUID;

public class EntityAIFindClaimBed extends EntityAIBase {

    private final EntityModernCitizen citizen;
    private final int radius;
    private int cooldown = 0;

    public EntityAIFindClaimBed(EntityModernCitizen citizen, int radius) {
        this.citizen = citizen;
        this.radius = Math.max(8, radius);
        this.setMutexBits(1);
    }

    @Override
    public boolean shouldExecute() {
        if (citizen == null || citizen.world == null || citizen.world.isRemote) return false;
        if (citizen.hasBed()) return false;
        if (cooldown > 0) {
            cooldown--;
            return false;
        }
        return true;
    }

    @Override
    public void startExecuting() {
        if (citizen == null || citizen.world == null || citizen.world.isRemote) return;

        BlockPos base = citizen.getPosition();
        AxisAlignedBB box = new AxisAlignedBB(
                base.getX() - radius, base.getY() - 8, base.getZ() - radius,
                base.getX() + radius, base.getY() + 8, base.getZ() + radius
        );

        BlockPos.MutableBlockPos cur = new BlockPos.MutableBlockPos();
        BedClaimData data = BedClaimData.get(citizen.world);
        UUID self = citizen.getUniqueID();

        BlockPos bestHead = null;
        double bestDist = Double.MAX_VALUE;

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
                        bestHead = head;
                    }
                }
            }
        }

        if (bestHead != null) {
            if (data.claimBed(citizen.world, bestHead, self)) {
                citizen.setAssignedBed(bestHead);
            }
        }

        cooldown = 200;
    }
}
