package studio.ERM.strategic.defense;

import net.minecraft.entity.EntityCreature;
import net.minecraft.entity.EntityLivingBase;
import net.minecraft.entity.ai.EntityAIBase;
import net.minecraft.util.math.BlockPos;
import studio.ERM.EpochRunnerMod;

/**
 * THE REAL HIJACK — injected INTO the npc's own AI task list (priority 0, movement+look mutex) so the
 * vanilla task system suspends AW2's movement tasks while a defense-plan order exists.
 *
 * INSTRUMENTED end-to-end per the debug protocol: injection / shouldExecute (2s per npc) / START /
 * TICK (2s) / every PATH call with its boolean result / RESET with reason. All server-side.
 *
 * DEBUG_FORCE_MOVEMENT: while true (diagnosis mode), ALL combat-yield logic is disabled — the task
 * ignores fights and forces movement to the slot. If movement works with this on, the bug is the
 * final-say/yield contract, not injection/pathing. Flip false to restore the yield contract.
 */
public class EntityAIDefendPlanOrder extends EntityAIBase {

    /** Diagnosis switch (user protocol check D): true = ignore fights, force movement. */
    public static boolean DEBUG_FORCE_MOVEMENT = true;

    private final EntityCreature npc;
    private int repathCooldown;
    private long lastShouldLog = 0;
    private long lastTickLog = 0;

    public EntityAIDefendPlanOrder(EntityCreature npc) {
        this.npc = npc;
        // Movement + look mutex (check E): only-look would let AW2 movement tasks keep overwriting.
        this.setMutexBits(1 | 2);
        EpochRunnerMod.logger.info("[DefenseAI] injected entity=" + npc.getUniqueID()
                + " name=" + npc.getName()
                + " class=" + npc.getClass().getName()
                + " type=" + Aw2Npc.fullType(npc)
                + " dim=" + npc.world.provider.getDimension()
                + " remote=" + npc.world.isRemote);
    }

    private BlockPos order() {
        return DefensePlanExecutor.orderFor(npc);
    }

    private boolean adjacentFight() {
        if (DEBUG_FORCE_MOVEMENT) return false; // check D: no combat yield in diagnosis mode
        EntityLivingBase t = npc.getAttackTarget();
        return t != null && !t.isDead && npc.getDistanceSq(t) < 64.0;
    }

    private double distSqToOrder(BlockPos o) {
        return npc.getDistanceSq(o.getX() + 0.5, o.getY(), o.getZ() + 0.5);
    }

    @Override
    public boolean shouldExecute() {
        // Check C: minimal conditions — an order exists, the npc is alive. (Server-side by construction:
        // the executor only injects on WorldServer entities.)
        BlockPos o = order();
        boolean fight = adjacentFight();
        boolean run = o != null && !npc.isDead && !fight && distSqToOrder(o) > 6.25;

        long now = npc.world.getTotalWorldTime();
        if (now - lastShouldLog >= 40) { // once per 2s per npc
            lastShouldLog = now;
            String yield = (o == null) ? "no-order" : fight ? "adjacent-fight"
                    : (distSqToOrder(o) <= 6.25) ? "on-station" : "none";
            EpochRunnerMod.logger.info("[DefenseAI] shouldExecute entity=" + npc.getUniqueID()
                    + " name=" + npc.getName()
                    + " hasOrder=" + (o != null)
                    + " pos=" + (o == null ? "null" : (o.getX() + " " + o.getY() + " " + o.getZ()))
                    + " distSq=" + (o == null ? -1 : (int) distSqToOrder(o))
                    + " yieldingReason=" + yield
                    + " -> " + run);
        }
        return run;
    }

    @Override
    public boolean shouldContinueExecuting() {
        BlockPos o = order();
        return o != null && !npc.isDead && !adjacentFight() && distSqToOrder(o) > 4.0;
    }

    @Override
    public void startExecuting() {
        repathCooldown = 0;
        BlockPos o = order();
        EpochRunnerMod.logger.info("[DefenseAI] START entity=" + npc.getUniqueID()
                + " target=" + (o == null ? "null" : (o.getX() + " " + o.getY() + " " + o.getZ())));
    }

    @Override
    public void updateTask() {
        BlockPos o = order();
        if (o == null) return;

        long now = npc.world.getTotalWorldTime();
        if (now - lastTickLog >= 40) { // once per 2s
            lastTickLog = now;
            EpochRunnerMod.logger.info("[DefenseAI] TICK entity=" + npc.getUniqueID()
                    + " distSq=" + (int) distSqToOrder(o)
                    + " noPath=" + npc.getNavigator().noPath()
                    + " target=" + o.getX() + " " + o.getY() + " " + o.getZ()
                    + " current=" + (int) npc.posX + " " + (int) npc.posY + " " + (int) npc.posZ
                    + " fighting=" + (npc.getAttackTarget() != null)
                    + " forced=" + DEBUG_FORCE_MOVEMENT);
        }

        if (--repathCooldown > 0 && !npc.getNavigator().noPath()) return;
        repathCooldown = 10; // keep repathing every 10t while off-station (check I)
        boolean ok = npc.getNavigator().tryMoveToXYZ(o.getX() + 0.5, o.getY(), o.getZ() + 0.5, 1.15D);
        EpochRunnerMod.logger.info("[DefenseAI] PATH entity=" + npc.getUniqueID()
                + " target=" + o.getX() + " " + o.getY() + " " + o.getZ() + " result=" + ok);
    }

    @Override
    public void resetTask() {
        BlockPos o = order();
        String reason = (o == null) ? "order-removed"
                : adjacentFight() ? "adjacent-fight"
                : (distSqToOrder(o) <= 4.0) ? "arrived" : "other";
        EpochRunnerMod.logger.info("[DefenseAI] RESET entity=" + npc.getUniqueID() + " reason=" + reason);
        // Check I: do NOT clearPath unless the order is actually gone — a cleared path on a combat
        // yield handed control straight back to AW2.
        if (o == null) {
            npc.getNavigator().clearPath();
        }
    }
}
