package studio.ERM.war.entities.ai;

import net.minecraft.item.ItemFood;
import net.minecraft.item.ItemStack;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.util.EnumFacing;
import net.minecraft.util.math.AxisAlignedBB;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;
import net.minecraftforge.items.CapabilityItemHandler;
import net.minecraftforge.items.IItemHandler;
import net.minecraftforge.items.ItemHandlerHelper;

import studio.ERM.war.blocks.TileEntityBanquetHall;
import studio.ERM.war.entities.EntityModularCitizen;

import javax.annotation.Nullable;

/**
 * Once per Minecraft day, within a job-specific time window, citizens walk to a Banquet Hall and pull food.
 *
 * - Uses assigned hall position if present; otherwise searches within radius for nearest hall TE.
 * - Pulls up to pullLimitItems items (1-by-1) into the citizen's internal inventory.
 */
public class EntityAIBanquetHallVisit extends net.minecraft.entity.ai.EntityAIBase {

    private static final double WALK_SPEED = 0.85D;

    // Default job windows (ticks within 0..23999)
    private static final int CITIZEN_WINDOW_START = 6000;   // ~morning
    private static final int CITIZEN_WINDOW_END = 11000;

    private static final int ITEMDUCT_WINDOW_START = 3000;  // earlier
    private static final int ITEMDUCT_WINDOW_END = 7000;

    private final EntityModularCitizen citizen;
    private final int searchRadius;
    private final int pullLimitItems;

    private BlockPos targetHallPos;

    public EntityAIBanquetHallVisit(EntityModularCitizen citizen, int searchRadius, int pullLimitItems) {
        this.citizen = citizen;
        this.searchRadius = Math.max(16, searchRadius);
        this.pullLimitItems = Math.max(1, pullLimitItems);
        this.setMutexBits(1); // movement
    }

    @Override
    public boolean shouldExecute() {
        if (citizen.isRetreating()) return false;
        if (!citizen.needsFood()) return false;

        long day = citizen.world.getWorldTime() / 24000L;
        if (citizen.getLastVisitDay() == day) return false;

        int[] window = getJobWindow(citizen.getJobId());
        int visitTick = citizen.getJobVisitTickOrInit(window[0], window[1]);
        int now = (int) (citizen.world.getWorldTime() % 24000L);

        // Slack so they don't miss it due to AI timing
        int slack = 600;
        if (now < (visitTick - slack) || now > (visitTick + slack)) return false;

        targetHallPos = resolveHallPos();
        return targetHallPos != null;
    }

    @Override
    public boolean shouldContinueExecuting() {
        if (citizen.isRetreating()) return false;
        if (targetHallPos == null) return false;
        return citizen.getDistanceSqToCenter(targetHallPos) > 2.25D;
    }

    @Override
    public void startExecuting() {
        if (targetHallPos != null) {
            citizen.getNavigator().tryMoveToXYZ(
                    targetHallPos.getX() + 0.5D,
                    targetHallPos.getY(),
                    targetHallPos.getZ() + 0.5D,
                    WALK_SPEED
            );
        }
    }

    @Override
    public void updateTask() {
        if (targetHallPos == null) return;

        double d = citizen.getDistanceSqToCenter(targetHallPos);
        if (d <= 2.25D) {
            TileEntity te = citizen.world.getTileEntity(targetHallPos);
            IItemHandler handler = null;

            if (te instanceof TileEntityBanquetHall) {
                handler = te.getCapability(CapabilityItemHandler.ITEM_HANDLER_CAPABILITY, EnumFacing.UP);
            } else if (te != null && te.hasCapability(CapabilityItemHandler.ITEM_HANDLER_CAPABILITY, EnumFacing.UP)) {
                handler = te.getCapability(CapabilityItemHandler.ITEM_HANDLER_CAPABILITY, EnumFacing.UP);
            }

            if (handler != null) {
                int pulled = pullFoodFrom(handler, pullLimitItems);
                if (pulled > 0) {
                    long day = citizen.world.getWorldTime() / 24000L;
                    citizen.setLastVisitDay(day);
                }
            }

            citizen.getNavigator().clearPath();
            targetHallPos = null;
        }
    }

    @Override
    public void resetTask() {
        targetHallPos = null;
        citizen.getNavigator().clearPath();
    }

    private int[] getJobWindow(String jobId) {
        if ("item_duct".equalsIgnoreCase(jobId) || "itemduct".equalsIgnoreCase(jobId)) {
            return new int[]{ITEMDUCT_WINDOW_START, ITEMDUCT_WINDOW_END};
        }
        return new int[]{CITIZEN_WINDOW_START, CITIZEN_WINDOW_END};
    }

    @Nullable
    private BlockPos resolveHallPos() {
        // 1) If assigned, validate it
        BlockPos assigned = citizen.getBanquetHallPos();
        if (assigned != null) {
            TileEntity te = citizen.world.getTileEntity(assigned);
            if (te instanceof TileEntityBanquetHall) return assigned;
        }

        // 2) Search nearest within radius
        BlockPos base = citizen.getPosition();
        AxisAlignedBB box = new AxisAlignedBB(
                base.getX() - searchRadius, base.getY() - 8, base.getZ() - searchRadius,
                base.getX() + searchRadius, base.getY() + 8, base.getZ() + searchRadius
        );

        BlockPos best = null;
        double bestDist = Double.MAX_VALUE;
        boolean bestIsHall = false;

        for (TileEntity te : citizen.world.loadedTileEntityList) {
            if (!(te instanceof TileEntityBanquetHall)) continue;

            BlockPos p = te.getPos();

            // 1.12.2 AxisAlignedBB.contains takes Vec3d, not 3 doubles
            if (!box.contains(new Vec3d(p.getX() + 0.5D, p.getY() + 0.5D, p.getZ() + 0.5D))) continue;

            double dist = citizen.getDistanceSqToCenter(p);
            if (dist < bestDist) {
                bestDist = dist;
                best = p;
                bestIsHall = true;
            }
        }

        // 3) KITCHEN districts feed citizens too: they go to whichever is NEAREST -- a kitchen depot
        //    stocked with food, or a hall. (updateTask's pull path already handles any IItemHandler TE.)
        try {
            for (studio.ERM.strategic.civil.CivilMarker m
                    : studio.ERM.strategic.civil.CivilPlanData.get(citizen.world).markers) {
                if (m.isRoad() || m.kind != studio.ERM.strategic.civil.CivilMarker.KITCHEN || !m.hasDepot()) continue;
                BlockPos p = m.depotPos;
                if (p == null) continue;
                if (!box.contains(new Vec3d(p.getX() + 0.5D, p.getY() + 0.5D, p.getZ() + 0.5D))) continue;
                TileEntity te = citizen.world.getTileEntity(p);
                if (te == null || !te.hasCapability(CapabilityItemHandler.ITEM_HANDLER_CAPABILITY, EnumFacing.UP)) continue;
                if (!holdsAnyFood(te.getCapability(CapabilityItemHandler.ITEM_HANDLER_CAPABILITY, EnumFacing.UP))) continue;
                double dist = citizen.getDistanceSqToCenter(p);
                if (dist < bestDist) {
                    bestDist = dist;
                    best = p;
                    bestIsHall = false;
                }
            }
        } catch (Throwable ignored) {}

        if (best != null && bestIsHall) {
            citizen.setBanquetHallPos(best); // only halls are cached as the standing assignment
        }
        return best;
    }

    private static boolean holdsAnyFood(@Nullable IItemHandler h) {
        if (h == null) return false;
        for (int i = 0; i < h.getSlots(); i++) {
            ItemStack s = h.getStackInSlot(i);
            if (!s.isEmpty() && s.getItem() instanceof ItemFood) return true;
        }
        return false;
    }

    private int pullFoodFrom(IItemHandler from, int maxItems) {
        int pulled = 0;

        for (int i = 0; i < from.getSlots(); i++) {
            if (pulled >= maxItems) break;

            ItemStack s = from.getStackInSlot(i);
            if (s.isEmpty()) continue;
            if (!(s.getItem() instanceof ItemFood)) continue;

            ItemStack extracted = from.extractItem(i, 1, false);
            if (extracted.isEmpty()) continue;

            ItemStack remainder = ItemHandlerHelper.insertItem(citizen.getInternalInv(), extracted, false);
            if (!remainder.isEmpty()) {
                // Put back if citizen can't carry
                ItemHandlerHelper.insertItem(from, remainder, false);
                break;
            }

            pulled++;
        }

        return pulled;
    }
}
