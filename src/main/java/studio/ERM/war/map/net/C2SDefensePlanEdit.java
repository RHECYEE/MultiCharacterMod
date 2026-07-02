package studio.ERM.war.map.net;

import io.netty.buffer.ByteBuf;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.world.WorldServer;
import net.minecraftforge.fml.common.network.simpleimpl.IMessage;
import net.minecraftforge.fml.common.network.simpleimpl.IMessageHandler;
import net.minecraftforge.fml.common.network.simpleimpl.MessageContext;
import studio.ERM.strategic.defense.DefenseMarker;
import studio.ERM.strategic.defense.DefensePlanData;

/**
 * PHASE 2 — client -> server edit of the defensive plan (drawn on the war map's Military overlay).
 * Ops: ADD a marker / REMOVE the marker nearest a point / CLEAR the plan / SET the FALL BACK state /
 * REQUEST a fresh sync. Every op answers with a full {@link S2CDefensePlanSync} snapshot.
 */
public class C2SDefensePlanEdit implements IMessage {

    public static final int OP_ADD = 0;
    public static final int OP_REMOVE_NEAREST = 1;
    public static final int OP_CLEAR = 2;
    public static final int OP_SET_FALLBACK = 3;
    public static final int OP_REQUEST_SYNC = 4;

    private int op = OP_REQUEST_SYNC;
    private DefenseMarker marker = null; // ADD
    private int x, z;                    // REMOVE_NEAREST
    private boolean flag;                // SET_FALLBACK

    public C2SDefensePlanEdit() {}

    public static C2SDefensePlanEdit add(DefenseMarker m) {
        C2SDefensePlanEdit p = new C2SDefensePlanEdit();
        p.op = OP_ADD; p.marker = m; return p;
    }

    public static C2SDefensePlanEdit removeNearest(int x, int z) {
        C2SDefensePlanEdit p = new C2SDefensePlanEdit();
        p.op = OP_REMOVE_NEAREST; p.x = x; p.z = z; return p;
    }

    public static C2SDefensePlanEdit clearAll() {
        C2SDefensePlanEdit p = new C2SDefensePlanEdit();
        p.op = OP_CLEAR; return p;
    }

    public static C2SDefensePlanEdit setFallback(boolean active) {
        C2SDefensePlanEdit p = new C2SDefensePlanEdit();
        p.op = OP_SET_FALLBACK; p.flag = active; return p;
    }

    public static C2SDefensePlanEdit requestSync() {
        return new C2SDefensePlanEdit();
    }

    @Override
    public void fromBytes(ByteBuf buf) {
        op = buf.readByte();
        switch (op) {
            case OP_ADD:            marker = DefenseMarker.fromBytes(buf); break;
            case OP_REMOVE_NEAREST: x = buf.readInt(); z = buf.readInt(); break;
            case OP_SET_FALLBACK:   flag = buf.readBoolean(); break;
            default: break;
        }
    }

    @Override
    public void toBytes(ByteBuf buf) {
        buf.writeByte(op);
        switch (op) {
            case OP_ADD:            marker.toBytes(buf); break;
            case OP_REMOVE_NEAREST: buf.writeInt(x); buf.writeInt(z); break;
            case OP_SET_FALLBACK:   buf.writeBoolean(flag); break;
            default: break;
        }
    }

    public static class Handler implements IMessageHandler<C2SDefensePlanEdit, IMessage> {
        @Override
        public IMessage onMessage(C2SDefensePlanEdit msg, MessageContext ctx) {
            EntityPlayerMP player = ctx.getServerHandler().player;
            ((WorldServer) player.world).addScheduledTask(() -> {
                DefensePlanData plan = DefensePlanData.get(player.world);
                switch (msg.op) {
                    case OP_ADD:
                        if (msg.marker != null && !msg.marker.points.isEmpty()) {
                            plan.markers.add(msg.marker);
                            plan.markDirty();
                        }
                        break;
                    case OP_REMOVE_NEAREST:
                        plan.removeNearest(msg.x, msg.z, 24.0);
                        break;
                    case OP_CLEAR:
                        plan.markers.clear();
                        plan.markDirty();
                        break;
                    case OP_SET_FALLBACK:
                        plan.fallbackActive = msg.flag;
                        plan.markDirty();
                        studio.ERM.EpochRunnerMod.logger.info("[Defense] FALL BACK "
                                + (msg.flag ? "ORDERED" : "rescinded") + " by " + player.getName());
                        break;
                    default:
                        break;
                }
                TacticalWarMapNetwork.sendTo(new S2CDefensePlanSync(plan.markers, plan.fallbackActive), player);
            });
            return null;
        }
    }
}
