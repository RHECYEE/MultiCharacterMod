package studio.ERM.war.map.net;

import io.netty.buffer.ByteBuf;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.WorldServer;
import net.minecraftforge.fml.common.network.simpleimpl.IMessage;
import net.minecraftforge.fml.common.network.simpleimpl.IMessageHandler;
import net.minecraftforge.fml.common.network.simpleimpl.MessageContext;
import studio.ERM.war.districts.TileEntityDistrictMarker;

/**
 * Client -> server edit of a district depot's settings from the Universal District Controller.
 * Currently just the desired worker count (templates edit through container slot clicks, which
 * are already server-authoritative). Distance-validated against the depot block.
 */
public class C2SDistrictDepotEdit implements IMessage {

    public static final int OP_WORKER_DELTA = 0;
    public static final int OP_TOGGLE_SUBMODE = 1;

    private BlockPos pos = BlockPos.ORIGIN;
    private int op = OP_WORKER_DELTA;
    private int value; // worker delta, or (for submode) the number of modes to cycle through

    public C2SDistrictDepotEdit() {}

    public C2SDistrictDepotEdit(BlockPos pos, int workerDelta) {
        this.pos = pos;
        this.op = OP_WORKER_DELTA;
        this.value = workerDelta;
    }

    /** Cycle the district's sub-mode (Tree Farm <-> Fruit Farm) among {@code modeCount} modes. */
    public static C2SDistrictDepotEdit toggleSubMode(BlockPos pos, int modeCount) {
        C2SDistrictDepotEdit p = new C2SDistrictDepotEdit();
        p.pos = pos; p.op = OP_TOGGLE_SUBMODE; p.value = modeCount;
        return p;
    }

    @Override
    public void fromBytes(ByteBuf buf) {
        pos = BlockPos.fromLong(buf.readLong());
        op = buf.readByte();
        value = buf.readByte();
    }

    @Override
    public void toBytes(ByteBuf buf) {
        buf.writeLong(pos.toLong());
        buf.writeByte(op);
        buf.writeByte(value);
    }

    public static class Handler implements IMessageHandler<C2SDistrictDepotEdit, IMessage> {
        @Override
        public IMessage onMessage(C2SDistrictDepotEdit msg, MessageContext ctx) {
            EntityPlayerMP player = ctx.getServerHandler().player;
            ((WorldServer) player.world).addScheduledTask(() -> {
                if (player.getDistanceSq(msg.pos.getX() + 0.5, msg.pos.getY() + 0.5,
                        msg.pos.getZ() + 0.5) > 64.0) return;
                TileEntity te = player.world.getTileEntity(msg.pos);
                if (!(te instanceof TileEntityDistrictMarker)) return;
                TileEntityDistrictMarker depot = (TileEntityDistrictMarker) te;
                if (msg.op == OP_TOGGLE_SUBMODE) {
                    int modes = Math.max(1, msg.value);
                    depot.setSubMode((depot.getSubMode() + 1) % modes);
                } else {
                    depot.setDesiredWorkers(depot.getDesiredWorkers() + msg.value);
                }
            });
            return null;
        }
    }
}
