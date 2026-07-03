package studio.ERM.strategic.civil;

import net.minecraft.block.BlockBed;
import net.minecraft.block.state.IBlockState;
import net.minecraft.entity.EntityCreature;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;

import java.util.ArrayList;
import java.util.List;

/**
 * Settlement readouts for the Civilian tab sidebar: civilian labor supply and housing. Computed
 * SERVER-SIDE at sync time (map open / civil-plan edit) and shipped in S2CCivilPlanSync — cheap
 * enough for that cadence, never every tick.
 *
 *   workers: player-owned AW2 "worker" NPCs loaded in the world; "available" = not district-assigned
 *   beds:    bed HEAD blocks inside Residential/Barracks polygons; "available" = total (bed CLAIMING
 *            is a later phase — until then every counted bed is free)
 */
public final class CivilStats {

    /** {availWorkers, totalWorkers, availBeds, totalBeds}. */
    public int availWorkers, totalWorkers, availBeds, totalBeds;

    private CivilStats() {}

    // Guard rails so a huge Residential polygon can't stall the server thread on map-open.
    private static final int MAX_COLUMNS = 8192;
    private static final int Y_WINDOW = 12;

    public static CivilStats compute(World world) {
        CivilStats s = new CivilStats();

        // ---- workers ----
        for (Object o : world.loadedEntityList) {
            if (o instanceof EntityCreature) {
                EntityCreature c = (EntityCreature) o;
                if (DistrictWorkExecutor.isAnyWorker(c)) {
                    s.totalWorkers++;
                    if (DistrictWorkExecutor.isFreeWorker(c)) s.availWorkers++;
                }
            }
        }

        // ---- beds (Residential + Barracks polygons) ----
        List<CivilMarker> housing = new ArrayList<>();
        for (CivilMarker m : CivilPlanData.get(world).markers) {
            if (!m.isRoad() && (m.kind == CivilMarker.RESIDENTIAL || m.kind == CivilMarker.BARRACKS)) {
                housing.add(m);
            }
        }
        int columns = 0;
        outer:
        for (CivilMarker m : housing) {
            int minX = Integer.MAX_VALUE, maxX = Integer.MIN_VALUE;
            int minZ = Integer.MAX_VALUE, maxZ = Integer.MIN_VALUE;
            for (BlockPos p : m.points) {
                minX = Math.min(minX, p.getX()); maxX = Math.max(maxX, p.getX());
                minZ = Math.min(minZ, p.getZ()); maxZ = Math.max(maxZ, p.getZ());
            }
            if (minX > maxX) continue;
            for (int x = minX; x <= maxX; x++) {
                for (int z = minZ; z <= maxZ; z++) {
                    if (!m.contains(x, z)) continue;
                    if (++columns > MAX_COLUMNS) break outer;
                    if (!world.isBlockLoaded(new BlockPos(x, 64, z), false)) continue;
                    int surfaceY = world.getHeight(x, z);
                    int lo = Math.max(0, surfaceY - Y_WINDOW), hi = surfaceY + Y_WINDOW;
                    for (int y = lo; y <= hi; y++) {
                        IBlockState st = world.getBlockState(new BlockPos(x, y, z));
                        if (st.getBlock() instanceof BlockBed
                                && st.getValue(BlockBed.PART) == BlockBed.EnumPartType.HEAD) {
                            s.totalBeds++;
                        }
                    }
                }
            }
        }
        // Bed CLAIMING isn't built yet -> every bed is currently free.
        s.availBeds = s.totalBeds;
        return s;
    }
}
