package studio.ERM.strategic.civil.evac;

import net.minecraft.entity.EntityCreature;
import net.minecraft.entity.ai.EntityAIBase;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.WorldServer;

/**
 * Injected civilian evacuation task (priority 0). While its dimension is evacuating and an Evacuation
 * Point exists, it overrides the citizen's work + life AI (movement mutex) and marches the citizen to
 * the nearest point, then holds there. Goes inert the moment the order is lifted, so normal behaviour
 * resumes automatically.
 */
public class EntityAIEvacuate extends EntityAIBase {

    private final EntityCreature npc;
    private int repathCooldown;
    private BlockPos target;

    public EntityAIEvacuate(EntityCreature npc) {
        this.npc = npc;
        this.setMutexBits(1 | 2);
    }

    @Override
    public boolean shouldExecute() {
        if (npc.isDead || !(npc.world instanceof WorldServer)) return false;
        if (!EvacuationManager.isEvacuating(npc.world)) return false;
        target = EvacuationData.get(npc.world).nearest(npc.getPosition());
        return target != null;
    }

    @Override
    public boolean shouldContinueExecuting() {
        return shouldExecute();
    }

    @Override
    public void startExecuting() {
        repathCooldown = 0;
    }

    @Override
    public void updateTask() {
        if (target == null) return;
        double dd = npc.getDistanceSq(target.getX() + 0.5, target.getY(), target.getZ() + 0.5);
        if (dd <= 9.0) {
            npc.getNavigator().clearPath();
            npc.setSneaking(true); // sheltering in place
            return;
        }
        npc.setSneaking(false);
        if (--repathCooldown > 0 && !npc.getNavigator().noPath()) return;
        repathCooldown = 10;
        double gx = target.getX() + 0.5, gz = target.getZ() + 0.5;
        double dx = gx - npc.posX, dz = gz - npc.posZ;
        double dH = Math.sqrt(dx * dx + dz * dz);
        if (dH > 14.0) {
            double ux = dx / dH, uz = dz / dH;
            npc.getNavigator().tryMoveToXYZ(npc.posX + ux * 12.0, npc.posY, npc.posZ + uz * 12.0, 1.1D);
        } else {
            npc.getNavigator().tryMoveToXYZ(gx, target.getY(), gz, 1.1D);
        }
    }

    @Override
    public void resetTask() {
        npc.setSneaking(false);
        npc.getNavigator().clearPath();
    }
}
