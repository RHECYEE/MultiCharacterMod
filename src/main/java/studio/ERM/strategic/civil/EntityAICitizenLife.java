package studio.ERM.strategic.civil;

import net.minecraft.entity.EntityCreature;
import net.minecraft.entity.ai.EntityAIBase;
import net.minecraft.item.ItemFood;
import net.minecraft.item.ItemStack;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.WorldServer;
import studio.ERM.war.districts.TileEntityDistrictMarker;

/**
 * CITIZEN LIFE — the off-work half of a civilian's day, injected into the worker's own AI list at
 * priority 0 (ABOVE {@link EntityAIDistrictWork}, which sits at 1, so night/hunger override work).
 *
 *   NIGHT   -> walk to the citizen's assigned bed (BedAssignmentData) and rest there (sleep pose).
 *   HUNGRY  -> during the day, when the fed timer lapses, walk to the nearest KITCHEN depot, eat one
 *              food item from it (or just visit if none), and reset the timer.
 *   else    -> yields, so district work / AW2's own AI runs.
 *
 * Yields while under attack (workers are labor, not militia — they flee).
 */
public class EntityAICitizenLife extends EntityAIBase {

    private static final int FED_TICKS = 12000;   // ~10 minutes between meals
    private static final double ARRIVE_SQ = 6.25;

    private final EntityCreature npc;
    private int repathCooldown;

    public EntityAICitizenLife(EntityCreature npc) {
        this.npc = npc;
        this.setMutexBits(1 | 2);
        // Stagger initial hunger so a whole district doesn't stampede the kitchen at once.
        if (!npc.getEntityData().hasKey("erm_fed_until")) {
            npc.getEntityData().setLong("erm_fed_until",
                    npc.world.getTotalWorldTime() + npc.getRNG().nextInt(FED_TICKS));
        }
    }

    private boolean underAttack() {
        return npc.getRevengeTarget() != null && npc.ticksExisted - npc.getRevengeTimer() < 120;
    }

    private boolean isNight() {
        return npc.world instanceof WorldServer && !npc.world.isDaytime();
    }

    private boolean isHungry() {
        return npc.world.getTotalWorldTime() >= npc.getEntityData().getLong("erm_fed_until");
    }

    private BlockPos bed() {
        try {
            BedAssignmentData.Claim c = BedAssignmentData.get(npc.world).claims.get(npc.getUniqueID());
            return c != null ? c.bed : null;
        } catch (Throwable t) { return null; }
    }

    @Override
    public boolean shouldExecute() {
        if (npc.isDead || underAttack()) return false;
        if (isNight()) return bed() != null;
        return isHungry() && nearestKitchen() != null;
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
        if (!(npc.world instanceof WorldServer)) return;
        WorldServer world = (WorldServer) npc.world;

        if (isNight()) {
            BlockPos b = bed();
            if (b == null) return;
            double dd = npc.getDistanceSq(b.getX() + 0.5, b.getY(), b.getZ() + 0.5);
            if (dd <= ARRIVE_SQ) {
                npc.getNavigator().clearPath();
                npc.setSneaking(true); // resting pose (full sleep render is a later pass)
            } else {
                npc.setSneaking(false);
                march(b);
            }
            return;
        }

        // HUNGRY: head to the kitchen and eat.
        BlockPos k = nearestKitchen();
        if (k == null) return;
        double dd = npc.getDistanceSq(k.getX() + 0.5, k.getY(), k.getZ() + 0.5);
        if (dd <= ARRIVE_SQ) {
            eatFromKitchen(world, k);
            npc.getEntityData().setLong("erm_fed_until", world.getTotalWorldTime() + FED_TICKS);
        } else {
            march(k);
        }
    }

    /** Consume one food item from the kitchen depot (if stocked) — the visible "getting fed". */
    private void eatFromKitchen(WorldServer world, BlockPos kitchenDepot) {
        net.minecraft.tileentity.TileEntity te = world.getTileEntity(kitchenDepot);
        if (!(te instanceof TileEntityDistrictMarker)) return;
        TileEntityDistrictMarker depot = (TileEntityDistrictMarker) te;
        for (int slot = 0; slot < depot.depot.getSlots(); slot++) {
            ItemStack s = depot.depot.getStackInSlot(slot);
            if (!s.isEmpty() && s.getItem() instanceof ItemFood) {
                s.shrink(1);
                depot.depot.setStackInSlot(slot, s.isEmpty() ? ItemStack.EMPTY : s);
                npc.heal(2.0F);
                return;
            }
        }
    }

    /** Depot position of the nearest KITCHEN district in this world, or null. */
    private BlockPos nearestKitchen() {
        try {
            CivilPlanData plan = CivilPlanData.get(npc.world);
            BlockPos best = null;
            double bd = Double.MAX_VALUE;
            for (CivilMarker m : plan.markers) {
                if (m.kind != CivilMarker.KITCHEN || !m.hasDepot()) continue;
                double d = npc.getDistanceSq(m.depotPos.getX(), m.depotPos.getY(), m.depotPos.getZ());
                if (d < bd) { bd = d; best = m.depotPos; }
            }
            return best;
        } catch (Throwable t) { return null; }
    }

    private void march(BlockPos goal) {
        if (--repathCooldown > 0 && !npc.getNavigator().noPath()) return;
        repathCooldown = 10;
        double gx = goal.getX() + 0.5, gz = goal.getZ() + 0.5;
        double dx = gx - npc.posX, dz = gz - npc.posZ;
        double dH = Math.sqrt(dx * dx + dz * dz);
        if (dH > 14.0) {
            double ux = dx / dH, uz = dz / dH;
            npc.getNavigator().tryMoveToXYZ(npc.posX + ux * 12.0, npc.posY, npc.posZ + uz * 12.0, 0.9D);
        } else {
            npc.getNavigator().tryMoveToXYZ(gx, goal.getY(), gz, 0.9D);
        }
    }

    @Override
    public void resetTask() {
        npc.setSneaking(false);
        npc.getNavigator().clearPath();
    }
}
