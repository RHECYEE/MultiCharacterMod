package studio.ERM.strategic.civil;

import io.netty.buffer.ByteBuf;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.util.math.BlockPos;

import java.util.ArrayList;
import java.util.List;

/**
 * One piece of the player's CIVILIAN INFRASTRUCTURE PLAN, drawn on the war map's Civilian tab.
 *
 * Two shapes exist:
 *  - ROAD: infrastructure, not a district. A POLYLINE (>=2 points). Roads are preferred pathing
 *    for civilians/military/convoys/traders and may extend beyond the player's claims into
 *    NEUTRAL land — but never into rival or other-claimed territory (validated server-side).
 *  - Districts: POLYGONS (>=3 vertices) that generate jobs and logistics. Districts live INSIDE
 *    the player's claimed territory (validated server-side). The deep behaviors (block scans,
 *    couriers, workers, controller GUIs) hang off these shapes in later phases.
 */
public class CivilMarker {

    // Kinds (order = the Civilian tab's tool list). ROAD is the only polyline.
    public static final int ROAD = 0;
    public static final int RESIDENTIAL = 1;  // beds/houses -> housing + population capacity
    public static final int BARRACKS = 2;     // military housing (beds/lockers/chests)
    public static final int WAREHOUSE = 3;    // chests/crates/barrels -> logistics source
    public static final int ARMORY = 4;       // weapons/armor/ammo -> military logistics origin
    public static final int KITCHEN = 5;      // food/water/rations -> feeds citizens + garrisons
    public static final int HOSPITAL = 6;     // medicine/beds -> future casualty system
    public static final int FACTORY = 7;      // production blocks -> one production district
    public static final int RESEARCH = 8;     // research center -> tech tree (own GUI later)
    public static final int TRADE_DEPOT = 9;  // imports/exports/Command Buck exchange (own GUI later)
    // Resource districts: citizens work natural features INSIDE the polygon (shoreline, woods,
    // fields, veins, wilderness) and their yields roll from DistrictOutputConfig's weighted,
    // RIVAL-level-gated tables. The polygon is the WORK AREA; the depot block is the inventory.
    public static final int FISHING = 10;     // rivers/lakes inside the polygon
    public static final int LUMBER = 11;      // wooded areas
    public static final int FARM = 12;        // actual fields
    public static final int MINING = 13;      // exposed veins/caves/marked zones
    public static final int HUNTING = 14;     // open wilderness

    public static final String[] NAMES = {
            "Road", "Residential", "Barracks", "Warehouse", "Armory",
            "Kitchen", "Hospital", "Factory", "Research", "Trade Depot",
            "Fishing", "Lumber", "Farm", "Mining", "Hunting" };

    public int kind = ROAD;
    /** Polyline points (ROAD) or polygon vertices in draw order (districts). Y is ignored. */
    public final List<BlockPos> points = new ArrayList<>();
    // Stable identity for future map->server property edits (survives save/load).
    public int uid = (int) (Math.random() * Integer.MAX_VALUE);
    /**
     * The district's DEPOT: the District Marker block (specialized inventory) workers deposit
     * into and couriers service. Bound with the Pen & Paper's Courier Marker mode (or on block
     * placement). Null until tagged. THE DEPOT IS THE PERSISTENT PART of the district — NPCs are
     * replaceable labor; the depot, filters and worker count survive any citizen's death.
     */
    public BlockPos depotPos = null;

    // TRANSIENT map-display state (network only, never NBT): filled server-side at sync time so
    // the Civilian tab's district panel can show "Workers X/Y" without opening the depot GUI.
    public int assignedWorkers = 0;
    public int desiredWorkers = 0;

    /** Roads draw as open polylines; every other kind closes into a polygon. */
    public boolean isRoad() {
        return kind == ROAD;
    }

    /** Minimum committed points: a line needs 2, a polygon needs 3. */
    public int minPoints() {
        return isRoad() ? 2 : 3;
    }

    public static String nameOf(int kind) {
        return (kind >= 0 && kind < NAMES.length) ? NAMES[kind] : "Infrastructure";
    }

    /** Key into DistrictOutputConfig's tables: lowercase name, spaces -> underscores ("trade_depot"). */
    public String configKey() {
        return nameOf(kind).toLowerCase().replace(' ', '_');
    }

    public boolean hasDepot() {
        return depotPos != null;
    }

    /** Point-in-polygon (even-odd ray cast on X/Z). Roads contain nothing. */
    public boolean contains(int x, int z) {
        if (isRoad() || points.size() < 3) return false;
        boolean in = false;
        for (int i = 0, j = points.size() - 1; i < points.size(); j = i++) {
            int xi = points.get(i).getX(), zi = points.get(i).getZ();
            int xj = points.get(j).getX(), zj = points.get(j).getZ();
            if ((zi > z) != (zj > z)
                    && x < (double) (xj - xi) * (z - zi) / (double) (zj - zi) + xi) {
                in = !in;
            }
        }
        return in;
    }

    /** Centroid (nearest-removal + icon anchor). */
    public BlockPos center() {
        if (points.isEmpty()) return BlockPos.ORIGIN;
        long sx = 0, sz = 0;
        for (BlockPos p : points) { sx += p.getX(); sz += p.getZ(); }
        return new BlockPos((int) (sx / points.size()), 0, (int) (sz / points.size()));
    }

    public NBTTagCompound writeToNBT(NBTTagCompound tag) {
        tag.setInteger("kind", kind);
        tag.setInteger("uid", uid);
        int[] flat = new int[points.size() * 2];
        for (int i = 0; i < points.size(); i++) {
            flat[i * 2] = points.get(i).getX();
            flat[i * 2 + 1] = points.get(i).getZ();
        }
        tag.setIntArray("pts", flat);
        if (depotPos != null) tag.setIntArray("depot",
                new int[]{depotPos.getX(), depotPos.getY(), depotPos.getZ()});
        return tag;
    }

    public void readFromNBT(NBTTagCompound tag) {
        kind = tag.getInteger("kind");
        if (tag.hasKey("uid")) uid = tag.getInteger("uid");
        points.clear();
        int[] flat = tag.getIntArray("pts");
        for (int i = 0; i + 1 < flat.length; i += 2) points.add(new BlockPos(flat[i], 0, flat[i + 1]));
        int[] dep = tag.getIntArray("depot");
        depotPos = dep.length == 3 ? new BlockPos(dep[0], dep[1], dep[2]) : null;
    }

    public void toBytes(ByteBuf buf) {
        buf.writeByte(kind);
        buf.writeInt(uid);
        buf.writeShort(points.size());
        for (BlockPos p : points) { buf.writeInt(p.getX()); buf.writeInt(p.getZ()); }
        buf.writeBoolean(depotPos != null);
        if (depotPos != null) {
            buf.writeInt(depotPos.getX()); buf.writeInt(depotPos.getY()); buf.writeInt(depotPos.getZ());
        }
        buf.writeShort(assignedWorkers);
        buf.writeShort(desiredWorkers);
    }

    public static CivilMarker fromBytes(ByteBuf buf) {
        CivilMarker m = new CivilMarker();
        m.kind = buf.readByte();
        m.uid = buf.readInt();
        int n = buf.readShort();
        for (int i = 0; i < n; i++) m.points.add(new BlockPos(buf.readInt(), 0, buf.readInt()));
        if (buf.readBoolean()) m.depotPos = new BlockPos(buf.readInt(), buf.readInt(), buf.readInt());
        m.assignedWorkers = buf.readShort();
        m.desiredWorkers = buf.readShort();
        return m;
    }
}
