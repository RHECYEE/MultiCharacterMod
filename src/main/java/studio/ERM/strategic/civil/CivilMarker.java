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
    public static final int MINING = 13;      // QUARRY: workers dig the marked zone one block at a time
    public static final int QUARRY = 13;      // alias — MINING is the quarry now
    public static final int HUNTING = 14;     // open wilderness (Strategic Mission, not a district)

    public static final String[] NAMES = {
            "Road", "Residential", "Barracks", "Warehouse", "Armory",
            "Kitchen", "Hospital", "Factory", "Research", "Trade Depot",
            "Fishing", "Lumber", "Farm", "Quarry", "Hunting" };

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

    // ==================== ROAD-only per-SEGMENT state ====================
    // Segment i = points[i] -> points[i+1]. Roads AUTODETECT their surface: RoadNetworkManager
    // periodically samples the blocks beneath each loaded segment; the majority valid block
    // becomes that segment's material, and the match fraction becomes its CONDITION. Explosions
    // and combat lower condition simply by destroying surface blocks — no damage hook needed.
    public static final int COND_EXCELLENT = 0;
    public static final int COND_GOOD = 1;
    public static final int COND_FAIR = 2;
    public static final int COND_POOR = 3;
    public static final int COND_DESTROYED = 4;
    public static final int COND_UNSAMPLED = 5;
    public static final String[] CONDITION_NAMES = {
            "Excellent", "Good", "Fair", "Poor", "Destroyed", "Unsurveyed" };

    /** Majority block registry name per segment ("" = unknown). SERVER truth; drives repair. */
    public String[] segMaterial = new String[0];
    /** Condition per segment (COND_*). Persisted so the map shows state for unloaded roads. */
    public byte[] segCondition = new byte[0];
    /** Synced-only map tint per segment (vanilla MapColor of the material; 0 = use default). */
    public int[] segColor = new int[0];
    /** Synced-only friendly material label per segment ("Gravel", "Stone Bricks", ...). */
    public String[] segLabel = new String[0];

    /** Number of segments this road has (0 for districts / degenerate roads). */
    public int segmentCount() {
        return isRoad() ? Math.max(0, points.size() - 1) : 0;
    }

    /** (Re)size the per-segment arrays to match the point list, preserving existing entries. */
    public void ensureSegArrays() {
        int n = segmentCount();
        if (segMaterial.length != n) {
            String[] mat = new String[n];
            byte[] cond = new byte[n];
            int[] col = new int[n];
            String[] lab = new String[n];
            for (int i = 0; i < n; i++) {
                mat[i] = i < segMaterial.length ? segMaterial[i] : "";
                cond[i] = i < segCondition.length ? segCondition[i] : (byte) COND_UNSAMPLED;
                col[i] = i < segColor.length ? segColor[i] : 0;
                lab[i] = i < segLabel.length ? segLabel[i] : "";
            }
            segMaterial = mat; segCondition = cond; segColor = col; segLabel = lab;
        }
    }

    /** Worst (highest) condition value across sampled segments; UNSAMPLED when none sampled. */
    public int worstCondition() {
        int worst = -1;
        for (byte c : segCondition) if (c != COND_UNSAMPLED) worst = Math.max(worst, c);
        return worst == -1 ? COND_UNSAMPLED : worst;
    }

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
        if (isRoad() && segMaterial.length > 0) {
            net.minecraft.nbt.NBTTagList mats = new net.minecraft.nbt.NBTTagList();
            for (String s : segMaterial) mats.appendTag(new net.minecraft.nbt.NBTTagString(s == null ? "" : s));
            tag.setTag("segMat", mats);
            tag.setByteArray("segCond", segCondition.clone());
        }
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
        ensureSegArrays();
        if (tag.hasKey("segMat")) {
            net.minecraft.nbt.NBTTagList mats = tag.getTagList("segMat", 8);
            byte[] cond = tag.getByteArray("segCond");
            for (int i = 0; i < segMaterial.length && i < mats.tagCount(); i++) {
                segMaterial[i] = mats.getStringTagAt(i);
                if (i < cond.length) segCondition[i] = cond[i];
            }
        }
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
        if (isRoad()) {
            ensureSegArrays();
            buf.writeShort(segCondition.length);
            for (int i = 0; i < segCondition.length; i++) {
                buf.writeByte(segCondition[i]);
                buf.writeInt(segColor[i]);
                net.minecraftforge.fml.common.network.ByteBufUtils.writeUTF8String(
                        buf, segLabel[i] == null ? "" : segLabel[i]);
            }
        }
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
        if (m.isRoad()) {
            m.ensureSegArrays();
            int segs = buf.readShort();
            for (int i = 0; i < segs; i++) {
                byte cond = buf.readByte();
                int color = buf.readInt();
                String label = net.minecraftforge.fml.common.network.ByteBufUtils.readUTF8String(buf);
                if (i < m.segCondition.length) {
                    m.segCondition[i] = cond;
                    m.segColor[i] = color;
                    m.segLabel[i] = label;
                }
            }
        }
        return m;
    }
}
