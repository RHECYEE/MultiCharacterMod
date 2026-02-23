package studio.ERM.war.entities.ai;

import net.minecraft.tileentity.TileEntity;
import net.minecraft.util.EnumFacing;
import net.minecraft.util.math.BlockPos;
import net.minecraftforge.items.CapabilityItemHandler;
import net.minecraftforge.items.IItemHandler;
import net.minecraftforge.items.ItemHandlerHelper;
import net.minecraft.item.ItemStack;
import net.minecraft.entity.ai.EntityAIBase;

import studio.ERM.war.entities.EntityModularCitizen;

import javax.annotation.Nullable;

/**
 * "Human item duct" job:
 * - If citizen has source + destination positions set, it periodically transfers items:
 *   - Pull up to 16 items per cycle from source handler
 *   - Insert into destination handler
 *
 * This runs even during the day; citizens will walk between endpoints, acting like an early itemduct.
 *
 * Configuration:
 * - Citizen NBT must contain:
 *   - erm_itemduct_src (BlockPos)
 *   - erm_itemduct_dst (BlockPos)
 *   - erm_itemduct_src_face (EnumFacing index)
 *   - erm_itemduct_dst_face (EnumFacing index)
 */
public class EntityAIItemDuctTransfer extends EntityAIBase {

    private static final double WALK_SPEED = 0.95D;
    private static final int TRANSFER_INTERVAL_TICKS = 40; // every 2 seconds
    private static final int MAX_ITEMS_PER_CYCLE = 16;
    private static final double INTERACT_DISTANCE_SQ = 2.25D;

    private final EntityModularCitizen citizen;
    private int tickCounter = 0;

    private BlockPos src;
    private BlockPos dst;

    private boolean goingToSource = true;

    public EntityAIItemDuctTransfer(EntityModularCitizen citizen) {
        this.citizen = citizen;
        this.setMutexBits(1); // movement
    }

    @Override
    public boolean shouldExecute() {
        if (citizen.isRetreating()) return false;

        // Only run if it's an item duct job or endpoints are configured
        if (!"item_duct".equalsIgnoreCase(citizen.getJobId()) && !"itemduct".equalsIgnoreCase(citizen.getJobId())) {
            return false;
        }

        src = citizen.getItemDuctSource();
        dst = citizen.getItemDuctDest();
        return src != null && dst != null;
    }

    @Override
    public boolean shouldContinueExecuting() {
        if (citizen.isRetreating()) return false;
        return src != null && dst != null;
    }

    @Override
    public void startExecuting() {
        tickCounter = 0;
        goToCurrentTarget();
    }

    @Override
    public void updateTask() {
        tickCounter++;

        if (goingToSource) {
            if (citizen.getDistanceSqToCenter(src) <= INTERACT_DISTANCE_SQ) {
                citizen.getNavigator().clearPath();
                if (tickCounter >= TRANSFER_INTERVAL_TICKS) {
                    tickCounter = 0;
                    boolean movedAnything = transferOnce(true);
                    // If nothing moved, still proceed to destination to avoid stall loops
                    goingToSource = false;
                    goToCurrentTarget();
                }
            } else {
                if (citizen.getNavigator().noPath()) {
                    goToCurrentTarget();
                }
            }
        } else {
            if (citizen.getDistanceSqToCenter(dst) <= INTERACT_DISTANCE_SQ) {
                citizen.getNavigator().clearPath();
                if (tickCounter >= TRANSFER_INTERVAL_TICKS) {
                    tickCounter = 0;
                    transferOnce(false);
                    goingToSource = true;
                    goToCurrentTarget();
                }
            } else {
                if (citizen.getNavigator().noPath()) {
                    goToCurrentTarget();
                }
            }
        }
    }

    @Override
    public void resetTask() {
        tickCounter = 0;
        citizen.getNavigator().clearPath();
    }

    private void goToCurrentTarget() {
        BlockPos target = goingToSource ? src : dst;
        if (target != null) {
            citizen.getNavigator().tryMoveToXYZ(target.getX() + 0.5D, target.getY(), target.getZ() + 0.5D, WALK_SPEED);
        }
    }

    private boolean transferOnce(boolean pullFromSource) {
        if (src == null || dst == null) return false;

        TileEntity teSrc = citizen.world.getTileEntity(src);
        TileEntity teDst = citizen.world.getTileEntity(dst);
        if (teSrc == null || teDst == null) return false;

        EnumFacing faceSrc = citizen.getItemDuctSourceFace();
        EnumFacing faceDst = citizen.getItemDuctDestFace();

        IItemHandler handlerSrc = teSrc.getCapability(CapabilityItemHandler.ITEM_HANDLER_CAPABILITY, faceSrc);
        IItemHandler handlerDst = teDst.getCapability(CapabilityItemHandler.ITEM_HANDLER_CAPABILITY, faceDst);
        if (handlerSrc == null || handlerDst == null) return false;

        int moved = 0;

        // Pull phase: move items into citizen inventory (simulated carry)
        if (pullFromSource) {
            for (int i = 0; i < handlerSrc.getSlots(); i++) {
                if (moved >= MAX_ITEMS_PER_CYCLE) break;

                ItemStack stackIn = handlerSrc.getStackInSlot(i);
                if (stackIn.isEmpty()) continue;

                // Extract 1 at a time for "carry" feel
                ItemStack extracted = handlerSrc.extractItem(i, 1, false);
                if (extracted.isEmpty()) continue;

                ItemStack remainder = ItemHandlerHelper.insertItem(citizen.getInternalInv(), extracted, false);
                if (!remainder.isEmpty()) {
                    // Put back remainder and stop
                    ItemHandlerHelper.insertItem(handlerSrc, remainder, false);
                    break;
                }
                moved++;
            }
            return moved > 0;
        }

        // Push phase: push from citizen inventory into destination
        for (int slot = 0; slot < citizen.getInternalInv().getSlots(); slot++) {
            if (moved >= MAX_ITEMS_PER_CYCLE) break;

            ItemStack carried = citizen.getInternalInv().getStackInSlot(slot);
            if (carried.isEmpty()) continue;

            // Move 1 item at a time
            ItemStack one = carried.copy();
            one.setCount(1);

            ItemStack remainder = ItemHandlerHelper.insertItem(handlerDst, one, false);
            if (!remainder.isEmpty()) {
                // destination full for this item
                continue;
            }

            citizen.getInternalInv().extractItem(slot, 1, false);
            moved++;
        }

        return moved > 0;
    }
}
