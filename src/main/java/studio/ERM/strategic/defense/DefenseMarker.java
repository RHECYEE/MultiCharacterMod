package studio.ERM.strategic.defense;

import io.netty.buffer.ByteBuf;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.util.math.BlockPos;

import java.util.ArrayList;
import java.util.List;

/**
 * PHASE 2 — one marker of the player's DEFENSIVE PLAN (drawn on the war map's Military overlay).
 * A marker is a TYPE plus one or more points (point markers use one; lines/routes use a polyline).
 * The player draws DOCTRINE, not orders — the Military AI staffs these with whatever is available.
 */
public class DefenseMarker {

    // Marker types (order = the P-key cycle in the map GUI).
    public static final int LINE = 0;          // infantry firing line (polyline)
    public static final int STRONGPOINT = 1;   // always-occupied position (point, top priority)
    public static final int VEHICLE = 2;       // armored vehicle position (point)
    public static final int AA = 3;            // anti-air battery position (point)
    public static final int RALLY = 4;         // assembly point before deployment (point)
    public static final int RESERVE = 5;       // reserves wait here until needed (point)
    public static final int FALLBACK_LINE = 6; // secondary line used when FALL BACK is hit (polyline)
    public static final int PATROL_ROUTE = 7;  // peacetime patrol circuit (polyline)
    public static final int LZ = 8;            // friendly helicopter landing zone (reinforce/medevac/logistics)
    public static final int MEDICAL = 9;       // casualty collection point (future: downed soldiers carried here)
    public static final int ENGAGEMENT_ZONE = 10; // hold fire until enemies ENTER this circle, then open up

    public static final String[] NAMES = {
            "Defensive Line", "Strongpoint", "Vehicle Position", "AA Position",
            "Rally Point", "Reserve Area", "Fallback Line", "Patrol Route",
            "Helicopter LZ", "Medical Point", "Engagement Zone" };

    /** Formation names for the properties panel (Auto = the AI picks; behaviors land incrementally). */
    public static final String[] FORMATIONS = {
            "Auto", "Line", "Spread Out", "Shield Wall", "Column", "Wedge", "Square",
            "Eng. Escort", "Veh. Escort" };

    public int type = LINE;
    public final List<BlockPos> points = new ArrayList<>();
    // Stable identity for map->server property edits (uid survives save/load).
    public int uid = (int) (Math.random() * Integer.MAX_VALUE);
    // Troops assigned to this marker (1-255), edited from the map's properties panel.
    public int assigned = 1;
    // Properties-panel fields: staffing priority (higher fills first within its class), formation
    // preset, and behavior flags (stored now; behaviors arrive incrementally).
    public int priority = 50;
    public int formation = 0;
    public boolean allowVehicles = false;
    public boolean reservePosition = false;

    public boolean isPolyline() {
        return type == LINE || type == FALLBACK_LINE || type == PATROL_ROUTE
                || type == ENGAGEMENT_ZONE; // zone = 2 clicks: centre then radius edge
    }

    /** Marker types whose troop count is player-adjustable. */
    public boolean isAssignable() {
        return type == LINE || type == STRONGPOINT || type == FALLBACK_LINE || type == PATROL_ROUTE;
    }

    public static String nameOf(int type) {
        return (type >= 0 && type < NAMES.length) ? NAMES[type] : "Marker";
    }

    /** Marker centroid (for nearest-marker removal + range checks). */
    public BlockPos center() {
        if (points.isEmpty()) return BlockPos.ORIGIN;
        long sx = 0, sz = 0;
        for (BlockPos p : points) { sx += p.getX(); sz += p.getZ(); }
        return new BlockPos((int) (sx / points.size()), 0, (int) (sz / points.size()));
    }

    public NBTTagCompound writeToNBT(NBTTagCompound tag) {
        tag.setInteger("type", type);
        tag.setInteger("uid", uid);
        tag.setInteger("assigned", assigned);
        tag.setInteger("priority", priority);
        tag.setInteger("formation", formation);
        tag.setBoolean("allowVehicles", allowVehicles);
        tag.setBoolean("reservePos", reservePosition);
        int[] flat = new int[points.size() * 2];
        for (int i = 0; i < points.size(); i++) {
            flat[i * 2] = points.get(i).getX();
            flat[i * 2 + 1] = points.get(i).getZ();
        }
        tag.setIntArray("pts", flat);
        return tag;
    }

    public void readFromNBT(NBTTagCompound tag) {
        type = tag.getInteger("type");
        if (tag.hasKey("uid")) uid = tag.getInteger("uid");
        assigned = Math.max(1, Math.min(255, tag.hasKey("assigned") ? tag.getInteger("assigned") : 1));
        priority = tag.hasKey("priority") ? tag.getInteger("priority") : 50;
        formation = Math.max(0, Math.min(FORMATIONS.length - 1, tag.getInteger("formation")));
        allowVehicles = tag.getBoolean("allowVehicles");
        reservePosition = tag.getBoolean("reservePos");
        points.clear();
        int[] flat = tag.getIntArray("pts");
        for (int i = 0; i + 1 < flat.length; i += 2) points.add(new BlockPos(flat[i], 0, flat[i + 1]));
    }

    public void toBytes(ByteBuf buf) {
        buf.writeByte(type);
        buf.writeInt(uid);
        buf.writeShort(assigned);
        buf.writeShort(priority);
        buf.writeByte(formation);
        buf.writeBoolean(allowVehicles);
        buf.writeBoolean(reservePosition);
        buf.writeShort(points.size());
        for (BlockPos p : points) { buf.writeInt(p.getX()); buf.writeInt(p.getZ()); }
    }

    public static DefenseMarker fromBytes(ByteBuf buf) {
        DefenseMarker m = new DefenseMarker();
        m.type = buf.readByte();
        m.uid = buf.readInt();
        m.assigned = Math.max(1, Math.min(255, buf.readShort()));
        m.priority = buf.readShort();
        m.formation = Math.max(0, Math.min(FORMATIONS.length - 1, buf.readByte()));
        m.allowVehicles = buf.readBoolean();
        m.reservePosition = buf.readBoolean();
        int n = buf.readShort();
        for (int i = 0; i < n; i++) m.points.add(new BlockPos(buf.readInt(), 0, buf.readInt()));
        return m;
    }
}
