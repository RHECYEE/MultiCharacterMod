package studio.ERM.strategic.defense;

import net.minecraft.entity.EntityCreature;
import net.minecraft.entity.EntityLivingBase;
import net.minecraft.entity.ai.EntityAIBase;
import net.minecraft.util.math.BlockPos;

/**
 * THE REAL HIJACK. A one-shot navigator call from outside gets overwritten within a tick by AW2's own
 * AI (wander / move-home / guard / follow all re-path every tick) — which is why the guards "did not
 * care about orders at all". This task is INJECTED INTO the NPC's own AI task list at priority 0 with
 * the movement mutex, so while a defense-plan order exists the vanilla task system itself SUSPENDS
 * every conflicting AW2 movement task. The military AI now genuinely owns the soldier's legs.
 *
 * Yield rules (the "final say" contract):
 *   - An ADJACENT fight (attack target within 8 blocks) takes over — the soldier defends itself; the
 *     executor cancels DISTANT chases each pass, which re-arms this task.
 *   - ON STATION (within ~2.5 blocks of the order) the task yields, freeing the gun/combat AI to aim
 *     and shoot from the position it is holding. Straying re-engages the march.
 *
 * Orders come from {@link DefensePlanExecutor#orderFor} (refreshed every executor pass); when the map
 * has no order for this npc the task is inert and AW2 behaves normally.
 */
public class EntityAIDefendPlanOrder extends EntityAIBase {

    private final EntityCreature npc;
    private int repathCooldown;

    public EntityAIDefendPlanOrder(EntityCreature npc) {
        this.npc = npc;
        this.setMutexBits(1); // movement: suspends every conflicting lower-priority movement task
    }

    private BlockPos order() {
        return DefensePlanExecutor.orderFor(npc);
    }

    private boolean adjacentFight() {
        EntityLivingBase t = npc.getAttackTarget();
        return t != null && !t.isDead && npc.getDistanceSq(t) < 64.0;
    }

    private double distSqToOrder(BlockPos o) {
        return npc.getDistanceSq(o.getX() + 0.5, o.getY(), o.getZ() + 0.5);
    }

    @Override
    public boolean shouldExecute() {
        BlockPos o = order();
        return o != null && !adjacentFight() && distSqToOrder(o) > 9.0;
    }

    @Override
    public boolean shouldContinueExecuting() {
        BlockPos o = order();
        return o != null && !adjacentFight() && distSqToOrder(o) > 6.25;
    }

    @Override
    public void startExecuting() {
        repathCooldown = 0;
    }

    @Override
    public void updateTask() {
        if (--repathCooldown > 0) return;
        repathCooldown = 10; // re-path every 0.5s -- AW2 can no longer steal the path between orders
        BlockPos o = order();
        if (o == null) return;
        npc.getNavigator().tryMoveToXYZ(o.getX() + 0.5, o.getY(), o.getZ() + 0.5, 1.15D);
    }

    @Override
    public void resetTask() {
        npc.getNavigator().clearPath();
    }
}
