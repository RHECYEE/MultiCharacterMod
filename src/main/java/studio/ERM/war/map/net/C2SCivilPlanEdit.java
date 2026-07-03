package studio.ERM.war.map.net;

import io.netty.buffer.ByteBuf;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.ChunkPos;
import net.minecraft.util.text.TextComponentString;
import net.minecraft.util.text.TextFormatting;
import net.minecraft.world.WorldServer;
import net.minecraftforge.fml.common.network.simpleimpl.IMessage;
import net.minecraftforge.fml.common.network.simpleimpl.IMessageHandler;
import net.minecraftforge.fml.common.network.simpleimpl.MessageContext;
import studio.ERM.strategic.civil.CivilMarker;
import studio.ERM.strategic.civil.CivilPlanData;
import studio.ERM.war.world.WarWorldData;

/**
 * Client -> server edit of the civilian infrastructure plan (roads + district polygons drawn on
 * the map's Civilian tab). Every op answers with a full {@link S2CCivilPlanSync} snapshot.
 *
 * Server-side territory rules (the map is a request, the server is the authority):
 *  - ROADS may run through the player's own claims and NEUTRAL land, but never into chunks
 *    claimed by a rival or another faction. Segments are sampled, not just vertices.
 *  - DISTRICTS must lie inside the player's own claimed territory (every vertex + centroid).
 */
public class C2SCivilPlanEdit implements IMessage {

    public static final int OP_ADD = 0;
    public static final int OP_REMOVE_NEAREST = 1;
    public static final int OP_REQUEST_SYNC = 2;

    private int op = OP_REQUEST_SYNC;
    private CivilMarker marker = null; // ADD
    private int x, z;                  // REMOVE_NEAREST

    public C2SCivilPlanEdit() {}

    public static C2SCivilPlanEdit add(CivilMarker m) {
        C2SCivilPlanEdit p = new C2SCivilPlanEdit();
        p.op = OP_ADD; p.marker = m; return p;
    }

    public static C2SCivilPlanEdit removeNearest(int x, int z) {
        C2SCivilPlanEdit p = new C2SCivilPlanEdit();
        p.op = OP_REMOVE_NEAREST; p.x = x; p.z = z; return p;
    }

    public static C2SCivilPlanEdit requestSync() {
        return new C2SCivilPlanEdit();
    }

    @Override
    public void fromBytes(ByteBuf buf) {
        op = buf.readByte();
        switch (op) {
            case OP_ADD:            marker = CivilMarker.fromBytes(buf); break;
            case OP_REMOVE_NEAREST: x = buf.readInt(); z = buf.readInt(); break;
            default: break;
        }
    }

    @Override
    public void toBytes(ByteBuf buf) {
        buf.writeByte(op);
        switch (op) {
            case OP_ADD:            marker.toBytes(buf); break;
            case OP_REMOVE_NEAREST: buf.writeInt(x); buf.writeInt(z); break;
            default: break;
        }
    }

    public static class Handler implements IMessageHandler<C2SCivilPlanEdit, IMessage> {
        @Override
        public IMessage onMessage(C2SCivilPlanEdit msg, MessageContext ctx) {
            EntityPlayerMP player = ctx.getServerHandler().player;
            ((WorldServer) player.world).addScheduledTask(() -> {
                CivilPlanData plan = CivilPlanData.get(player.world);
                switch (msg.op) {
                    case OP_ADD:
                        if (msg.marker != null && msg.marker.points.size() >= msg.marker.minPoints()
                                && msg.marker.points.size() <= 256) {
                            String deny = validate(player, msg.marker);
                            if (deny == null) {
                                plan.markers.add(msg.marker);
                                plan.markDirty();
                            } else {
                                player.sendMessage(new TextComponentString(TextFormatting.RED + deny));
                            }
                        }
                        break;
                    case OP_REMOVE_NEAREST:
                        plan.removeNearest(msg.x, msg.z, 32.0);
                        break;
                    default:
                        break;
                }
                sendSync(player, plan);
            });
            return null;
        }

        /** Populate each district's live worker counts + the settlement stats, then ship it. */
        static void sendSync(EntityPlayerMP player, CivilPlanData plan) {
            for (studio.ERM.strategic.civil.CivilMarker m : plan.markers) {
                if (m.isRoad()) continue;
                m.assignedWorkers = studio.ERM.strategic.civil.DistrictWorkExecutor.assignedTo(m.uid);
                studio.ERM.war.districts.TileEntityDistrictMarker depot =
                        studio.ERM.strategic.civil.DistrictRegistry.depotOf(player.world, m);
                m.desiredWorkers = depot != null ? depot.getDesiredWorkers() : 0;
            }
            studio.ERM.strategic.civil.CivilStats st =
                    studio.ERM.strategic.civil.CivilStats.compute(player.world);
            TacticalWarMapNetwork.sendTo(new S2CCivilPlanSync(
                    plan.markers, st.availWorkers, st.totalWorkers, st.availBeds, st.totalBeds), player);
        }

        /** Null when the marker is allowed; otherwise the player-facing reason it was refused. */
        private static String validate(EntityPlayerMP player, CivilMarker m) {
            WarWorldData data = WarWorldData.get(player.world);
            if (data == null) return null;
            String me = player.getUniqueID().toString();

            if (m.isRoad()) {
                // Walk every segment in ~8-block steps: a road may pass through own + NEUTRAL
                // chunks only. Vertex-only checks would let segments cut corners through claims.
                for (int i = 0; i + 1 < m.points.size(); i++) {
                    BlockPos a = m.points.get(i), b = m.points.get(i + 1);
                    double dx = b.getX() - a.getX(), dz = b.getZ() - a.getZ();
                    int steps = Math.max(1, (int) Math.ceil(Math.sqrt(dx * dx + dz * dz) / 8.0));
                    for (int s = 0; s <= steps; s++) {
                        int wx = a.getX() + (int) Math.round(dx * s / steps);
                        int wz = a.getZ() + (int) Math.round(dz * s / steps);
                        if (isForeign(data.getOwner(new ChunkPos(wx >> 4, wz >> 4)), me)) {
                            return "Road refused: it enters territory claimed by another faction.";
                        }
                    }
                }
                return null;
            }

            // Districts generate jobs + logistics: they only work on land you actually hold.
            for (BlockPos p : m.points) {
                if (!isOwn(data.getOwner(new ChunkPos(p.getX() >> 4, p.getZ() >> 4)), me)) {
                    return CivilMarker.nameOf(m.kind) + " district refused: it must lie inside your claimed territory.";
                }
            }
            BlockPos c = m.center();
            if (!isOwn(data.getOwner(new ChunkPos(c.getX() >> 4, c.getZ() >> 4)), me)) {
                return CivilMarker.nameOf(m.kind) + " district refused: it must lie inside your claimed territory.";
            }
            return null;
        }

        /** Claimed by someone who is not the player ("PLAYER" is the legacy own-faction bucket). */
        private static boolean isForeign(String owner, String me) {
            return !"NEUTRAL".equals(owner) && !me.equals(owner) && !"PLAYER".equals(owner);
        }

        private static boolean isOwn(String owner, String me) {
            return me.equals(owner) || "PLAYER".equals(owner);
        }
    }
}
