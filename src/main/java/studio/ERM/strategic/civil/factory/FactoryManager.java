package studio.ERM.strategic.civil.factory;

import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.inventory.IInventory;
import net.minecraft.inventory.InventoryCrafting;
import net.minecraft.item.ItemStack;
import net.minecraft.item.crafting.CraftingManager;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.WorldServer;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import net.minecraftforge.fml.common.gameevent.TickEvent;
import net.minecraftforge.items.ItemHandlerHelper;
import studio.ERM.EpochRunnerMod;
import studio.ERM.strategic.civil.CivilMarker;
import studio.ERM.strategic.civil.CivilPlanData;
import studio.ERM.strategic.civil.DistrictRegistry;
import studio.ERM.strategic.civil.DistrictWorkExecutor;
import studio.ERM.war.config.FactoryConfig;
import studio.ERM.war.districts.TileEntityAssemblySeat;
import studio.ERM.war.districts.TileEntityDistrictMarker;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * THE FACTORY — assembly SEATS inside a Factory district form its production line. Each second this
 * pass counts the seats in every Factory polygon (SEATS DICTATE EMPLOYEES: the district's desired
 * worker count tracks the seat count), then runs each MANNED seat's recipe on the craft timer: a
 * worker is "at" a seat when the district has at least that many assigned scientists. A craft pulls
 * the recipe's inputs from the Factory depot stock and pushes the vanilla result back into it, once
 * per {@link FactoryConfig} interval (30s base, faster at higher rival levels).
 */
public final class FactoryManager {

    private static final int Y_WINDOW = 16;
    private static final int MAX_COLUMNS = 8192;

    private FactoryManager() {}

    private static final DummyCraft DUMMY = new DummyCraft();

    @SubscribeEvent
    public static void onWorldTick(TickEvent.WorldTickEvent e) {
        if (e.phase != TickEvent.Phase.END || e.side.isClient() || !(e.world instanceof WorldServer)) return;
        WorldServer world = (WorldServer) e.world;
        if (world.getTotalWorldTime() % 20 != 0) return; // once per second

        CivilPlanData plan = CivilPlanData.get(world);
        int rivalLevel = DistrictRegistry.rivalLevel(world);
        int interval = FactoryConfig.craftIntervalTicks(rivalLevel);
        long now = world.getTotalWorldTime();

        for (CivilMarker m : plan.markers) {
            if (m.isRoad() || m.kind != CivilMarker.FACTORY || !m.hasDepot()) continue;
            TileEntityDistrictMarker depot = DistrictRegistry.depotOf(world, m);
            if (depot == null) continue;

            List<TileEntityAssemblySeat> seats = scanSeats(world, m);
            // SEATS DICTATE EMPLOYEES: keep the district's desired worker count in step with the seats.
            if (depot.getDesiredWorkers() != seats.size()) depot.setDesiredWorkers(seats.size());
            if (seats.isEmpty()) continue;

            int workers = DistrictWorkExecutor.assignedTo(m.uid);
            int active = Math.min(seats.size(), workers);
            for (int i = 0; i < active; i++) {
                TileEntityAssemblySeat seat = seats.get(i);
                if (!seat.hasRecipe() || now < seat.nextCraftTick) continue;
                if (tryCraft(world, seat, depot)) {
                    seat.nextCraftTick = now + interval;
                }
            }
        }
    }

    /** Attempt one craft: verify inputs in the depot, consume them, output the result. */
    private static boolean tryCraft(WorldServer world, TileEntityAssemblySeat seat, TileEntityDistrictMarker depot) {
        // Build the result.
        for (int i = 0; i < 9; i++) {
            ItemStack s = seat.recipe.getStackInSlot(i).copy();
            s.setCount(s.isEmpty() ? 0 : 1);
            DUMMY.setInventorySlotContents(i, s);
        }
        ItemStack result = CraftingManager.findMatchingResult(DUMMY, world);
        if (result.isEmpty()) return false;

        // Aggregate the required inputs (item+meta -> count).
        Map<String, Integer> need = new HashMap<>();
        for (int i = 0; i < 9; i++) {
            ItemStack s = seat.recipe.getStackInSlot(i);
            if (!s.isEmpty()) need.merge(key(s), 1, Integer::sum);
        }
        // Availability check.
        for (Map.Entry<String, Integer> en : need.entrySet()) {
            if (countInDepot(depot, en.getKey()) < en.getValue()) return false;
        }
        // Output must fit.
        if (!ItemHandlerHelper.insertItemStacked(depot.depot, result.copy(), true).isEmpty()) return false;

        // Consume + produce.
        for (Map.Entry<String, Integer> en : need.entrySet()) consumeFromDepot(depot, en.getKey(), en.getValue());
        ItemHandlerHelper.insertItemStacked(depot.depot, result.copy(), false);
        if (DistrictWorkExecutor.VERBOSE) EpochRunnerMod.logger.info("[Factory] crafted " + result.getDisplayName());
        return true;
    }

    private static String key(ItemStack s) {
        return net.minecraft.item.Item.getIdFromItem(s.getItem()) + ":" + s.getMetadata();
    }

    private static int countInDepot(TileEntityDistrictMarker depot, String key) {
        int n = 0;
        for (int i = 0; i < depot.depot.getSlots(); i++) {
            ItemStack s = depot.depot.getStackInSlot(i);
            if (!s.isEmpty() && key(s).equals(key)) n += s.getCount();
        }
        return n;
    }

    private static void consumeFromDepot(TileEntityDistrictMarker depot, String key, int count) {
        for (int i = 0; i < depot.depot.getSlots() && count > 0; i++) {
            ItemStack s = depot.depot.getStackInSlot(i);
            if (s.isEmpty() || !key(s).equals(key)) continue;
            int take = Math.min(s.getCount(), count);
            depot.depot.extractItem(i, take, false);
            count -= take;
        }
    }

    /** Assembly seats inside a Factory polygon (bounded surface-window column scan). */
    private static List<TileEntityAssemblySeat> scanSeats(WorldServer world, CivilMarker m) {
        List<TileEntityAssemblySeat> seats = new ArrayList<>();
        int minX = Integer.MAX_VALUE, maxX = Integer.MIN_VALUE, minZ = Integer.MAX_VALUE, maxZ = Integer.MIN_VALUE;
        for (BlockPos p : m.points) {
            minX = Math.min(minX, p.getX()); maxX = Math.max(maxX, p.getX());
            minZ = Math.min(minZ, p.getZ()); maxZ = Math.max(maxZ, p.getZ());
        }
        if (minX > maxX) return seats;
        int columns = 0;
        for (int x = minX; x <= maxX; x++) {
            for (int z = minZ; z <= maxZ; z++) {
                if (!m.contains(x, z)) continue;
                if (++columns > MAX_COLUMNS) return seats;
                if (!world.isBlockLoaded(new BlockPos(x, 64, z), false)) continue;
                int sy = world.getHeight(x, z);
                for (int y = Math.max(0, sy - Y_WINDOW); y <= sy + Y_WINDOW; y++) {
                    TileEntity te = world.getTileEntity(new BlockPos(x, y, z));
                    if (te instanceof TileEntityAssemblySeat) seats.add((TileEntityAssemblySeat) te);
                }
            }
        }
        return seats;
    }

    private static class DummyCraft extends InventoryCrafting {
        DummyCraft() {
            super(new net.minecraft.inventory.Container() {
                @Override public boolean canInteractWith(EntityPlayer p) { return false; }
                @Override public void onCraftMatrixChanged(IInventory inv) {}
            }, 3, 3);
        }
    }
}
