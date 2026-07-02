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

    public static final String[] NAMES = {
            "Defensive Line", "Strongpoint", "Vehicle Position", "AA Position",
            "Rally Point", "Reserve Area", "Fallback Line", "Patrol Route" };

    public int type = LINE;
    public final List<BlockPos> points = new ArrayList<>();

    public boolean isPolyline() {
        return type == LINE || type == FALLBACK_LINE || type == PATROL_ROUTE;
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
        points.clear();
        int[] flat = tag.getIntArray("pts");
        for (int i = 0; i + 1 < flat.length; i += 2) points.add(new BlockPos(flat[i], 0, flat[i + 1]));
    }

    public void toBytes(ByteBuf buf) {
        buf.writeByte(type);
        buf.writeShort(points.size());
        for (BlockPos p : points) { buf.writeInt(p.getX()); buf.writeInt(p.getZ()); }
    }

    public static DefenseMarker fromBytes(ByteBuf buf) {
        DefenseMarker m = new DefenseMarker();
        m.type = buf.readByte();
        int n = buf.readShort();
        for (int i = 0; i < n; i++) m.points.add(new BlockPos(buf.readInt(), 0, buf.readInt()));
        return m;
    }
}
