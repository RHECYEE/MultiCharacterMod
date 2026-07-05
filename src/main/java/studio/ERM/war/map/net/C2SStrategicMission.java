package studio.ERM.war.map.net;

import io.netty.buffer.ByteBuf;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.world.WorldServer;
import net.minecraftforge.fml.common.network.simpleimpl.IMessage;
import net.minecraftforge.fml.common.network.simpleimpl.IMessageHandler;
import net.minecraftforge.fml.common.network.simpleimpl.MessageContext;
import studio.ERM.strategic.civil.StrategicMissionManager;

/** Launch a Strategic Mission from the Civilian map (right-click, Inspect mode) to a world target. */
public class C2SStrategicMission implements IMessage {

    private int type, x, z;

    public C2SStrategicMission() {}

    public C2SStrategicMission(int type, int x, int z) {
        this.type = type; this.x = x; this.z = z;
    }

    @Override
    public void fromBytes(ByteBuf buf) {
        type = buf.readByte();
        x = buf.readInt();
        z = buf.readInt();
    }

    @Override
    public void toBytes(ByteBuf buf) {
        buf.writeByte(type);
        buf.writeInt(x);
        buf.writeInt(z);
    }

    public static class Handler implements IMessageHandler<C2SStrategicMission, IMessage> {
        @Override
        public IMessage onMessage(C2SStrategicMission msg, MessageContext ctx) {
            EntityPlayerMP player = ctx.getServerHandler().player;
            ((WorldServer) player.world).addScheduledTask(() ->
                    StrategicMissionManager.launch((WorldServer) player.world, msg.type, msg.x, msg.z,
                            player.getUniqueID()));
            return null;
        }
    }
}
