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

    private BlockPos pos = BlockPos.ORIGIN;
    private int workerDelta;

    public C2SDistrictDepotEdit() {}

    public C2SDistrictDepotEdit(BlockPos pos, int workerDelta) {
        this.pos = pos;
        this.workerDelta = workerDelta;
    }

    @Override
    public void fromBytes(ByteBuf buf) {
        pos = BlockPos.fromLong(buf.readLong());
        workerDelta = buf.readByte();
    }

    @Override
    public void toBytes(ByteBuf buf) {
        buf.writeLong(pos.toLong());
        buf.writeByte(workerDelta);
    }

    public static class Handler implements IMessageHandler<C2SDistrictDepotEdit, IMessage> {
        @Override
        public IMessage onMessage(C2SDistrictDepotEdit msg, MessageContext ctx) {
            EntityPlayerMP player = ctx.getServerHandler().player;
            ((WorldServer) player.world).addScheduledTask(() -> {
                if (player.getDistanceSq(msg.pos.getX() + 0.5, msg.pos.getY() + 0.5,
                        msg.pos.getZ() + 0.5) > 64.0) return;
                TileEntity te = player.world.getTileEntity(msg.pos);
                if (te instanceof TileEntityDistrictMarker) {
                    TileEntityDistrictMarker depot = (TileEntityDistrictMarker) te;
                    depot.setDesiredWorkers(depot.getDesiredWorkers() + msg.workerDelta);
                }
            });
            return null;
        }
    }
}
