package studio.ERM.strategic.civil;

import net.minecraft.tileentity.TileEntity;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;
import studio.ERM.war.districts.TileEntityDistrictMarker;
import studio.ERM.war.rival.RivalCityManager;
import studio.ERM.war.rival.RivalCityState;

import javax.annotation.Nullable;
import java.util.Map;

/**
 * SERVER-SIDE DISTRICT LOOKUPS — the glue between the two halves of a district:
 *
 *   polygon (CivilMarker)            = the WORK AREA drawn on the map's Civilian tab
 *   depot (TileEntityDistrictMarker) = the persistent inventory workers/couriers service
 *
 * Binding is two-way: marker.depotPos points at the block, the tile's districtUid points back at
 * the polygon. Bind happens when the depot block is placed inside a polygon or when the Pen &
 * Paper's Courier Marker tags it.
 *
 * Also owns the RIVAL-level read used to gate DistrictOutputConfig tables: the master progression
 * switch is whatever level the world's rival civilization has reached (max across cities in the
 * dimension — sieges and expansion already track it there; districts must not invent their own).
 */
public final class DistrictRegistry {

    private DistrictRegistry() {}

    /** First district polygon (not road) containing (x,z), or null. */
    @Nullable
    public static CivilMarker districtAt(World world, int x, int z) {
        for (CivilMarker m : CivilPlanData.get(world).markers) {
            if (!m.isRoad() && m.contains(x, z)) return m;
        }
        return null;
    }

    @Nullable
    public static CivilMarker districtAt(World world, BlockPos pos) {
        return districtAt(world, pos.getX(), pos.getZ());
    }

    /** The polygon with this uid, or null. */
    @Nullable
    public static CivilMarker byUid(World world, int uid) {
        if (uid == -1) return null;
        for (CivilMarker m : CivilPlanData.get(world).markers) {
            if (m.uid == uid) return m;
        }
        return null;
    }

    /**
     * Bind the depot tile at {@code pos} to the district polygon containing it. Rebinding to a
     * different polygon clears the old polygon's depotPos if it pointed here. Returns the bound
     * marker, or null when the block isn't inside any district polygon.
     */
    @Nullable
    public static CivilMarker bindDepot(World world, BlockPos pos) {
        TileEntity te = world.getTileEntity(pos);
        if (!(te instanceof TileEntityDistrictMarker)) return null;
        TileEntityDistrictMarker depot = (TileEntityDistrictMarker) te;

        CivilMarker district = districtAt(world, pos);
        if (district == null) return null;

        CivilPlanData plan = CivilPlanData.get(world);
        // One depot per district; one district per depot. Steal cleanly on rebind.
        for (CivilMarker m : plan.markers) {
            if (m != district && pos.equals(m.depotPos)) m.depotPos = null;
        }
        district.depotPos = pos;
        depot.setDistrictUid(district.uid);
        plan.markDirty();
        return district;
    }

    /** The bound depot tile of a district, or null if unbound / not loaded / broken. */
    @Nullable
    public static TileEntityDistrictMarker depotOf(World world, CivilMarker district) {
        if (district == null || district.depotPos == null) return null;
        if (!world.isBlockLoaded(district.depotPos)) return null;
        TileEntity te = world.getTileEntity(district.depotPos);
        return te instanceof TileEntityDistrictMarker ? (TileEntityDistrictMarker) te : null;
    }

    /** Depot broken or polygon deleted -> drop the stale half of the binding. */
    public static void unbindDepot(World world, BlockPos pos) {
        CivilPlanData plan = CivilPlanData.get(world);
        boolean dirty = false;
        for (CivilMarker m : plan.markers) {
            if (pos.equals(m.depotPos)) { m.depotPos = null; dirty = true; }
        }
        if (dirty) plan.markDirty();
    }

    /**
     * Item handlers of every CONTAINER BLOCK inside a district polygon (chests, crates, anything
     * exposing the item capability), EXCLUDING the depot itself. Bounded scan (max 4096 columns,
     * surface ±10) with a handler cap — this is how "the Warehouse reads all inventories".
     */
    public static java.util.List<net.minecraftforge.items.IItemHandler> districtInventories(
            World world, CivilMarker district) {
        java.util.List<net.minecraftforge.items.IItemHandler> out = new java.util.ArrayList<>();
        if (district == null || district.isRoad() || district.points.size() < 3) return out;
        int minX = Integer.MAX_VALUE, maxX = Integer.MIN_VALUE;
        int minZ = Integer.MAX_VALUE, maxZ = Integer.MIN_VALUE;
        for (BlockPos p : district.points) {
            minX = Math.min(minX, p.getX()); maxX = Math.max(maxX, p.getX());
            minZ = Math.min(minZ, p.getZ()); maxZ = Math.max(maxZ, p.getZ());
        }
        int columns = 0;
        for (int x = minX; x <= maxX && out.size() < 32; x++) {
            for (int z = minZ; z <= maxZ && out.size() < 32; z++) {
                if (!district.contains(x, z)) continue;
                if (++columns > 4096) return out;
                BlockPos probe = new BlockPos(x, 64, z);
                if (!world.isBlockLoaded(probe, false)) continue;
                int h = world.getHeight(x, z);
                for (int y = Math.max(1, h - 10); y <= h + 6; y++) {
                    BlockPos p = new BlockPos(x, y, z);
                    if (p.equals(district.depotPos)) continue;
                    TileEntity te = world.getTileEntity(p);
                    if (te == null) continue;
                    try {
                        net.minecraftforge.items.IItemHandler inv = te.getCapability(
                                net.minecraftforge.items.CapabilityItemHandler.ITEM_HANDLER_CAPABILITY, null);
                        if (inv != null) out.add(inv);
                    } catch (Throwable ignored) {}
                }
            }
        }
        return out;
    }

    /**
     * THE master progression level gating district outputs: the highest RIVAL city level in this
     * dimension (1 when no rival exists yet — level-0 table rows are always unlocked anyway).
     */
    public static int rivalLevel(World world) {
        int max = 1;
        try {
            Map<String, RivalCityState> cities = RivalCityManager.getDimensionCities(world);
            for (RivalCityState s : cities.values()) max = Math.max(max, s.level);
        } catch (Throwable ignored) {}
        return max;
    }
}
