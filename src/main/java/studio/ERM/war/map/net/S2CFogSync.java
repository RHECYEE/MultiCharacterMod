package studio.ERM.war.map.net;

import io.netty.buffer.ByteBuf;
import net.minecraft.client.Minecraft;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraftforge.fml.common.network.simpleimpl.IMessage;
import net.minecraftforge.fml.common.network.simpleimpl.IMessageHandler;
import net.minecraftforge.fml.common.network.simpleimpl.MessageContext;
import net.minecraft.world.WorldServer;
import studio.ERM.strategic.ExploredMapData;
import studio.ERM.war.map.client.ClientFogCache;

/**
 * FOG OF WAR sync. FULL chart on map open (C2S request -> S2C full=TRUE replaces the client set);
 * live deltas ride the 2s civil-plan feed as the recent ring. Chunks travel as packed ChunkPos longs.
 */
public class S2CFogSync implements IMessage {

    private boolean full;
    private long[] chunks = new long[0];

    public S2CFogSync() {}

    public S2CFogSync(boolean full, long[] chunks) {
        this.full = full;
        this.chunks = chunks != null ? chunks : new long[0];
    }

    @Override
    public void fromBytes(ByteBuf buf) {
        full = buf.readBoolean();
        int n = buf.readInt();
        chunks = new long[Math.max(0, Math.min(n, 262144))];
        for (int i = 0; i < chunks.length; i++) chunks[i] = buf.readLong();
    }

    @Override
    public void toBytes(ByteBuf buf) {
        buf.writeBoolean(full);
        buf.writeInt(chunks.length);
        for (long c : chunks) buf.writeLong(c);
    }

    public static class Handler implements IMessageHandler<S2CFogSync, IMessage> {
        @Override
        public IMessage onMessage(S2CFogSync msg, MessageContext ctx) {
            Minecraft.getMinecraft().addScheduledTask(() -> {
                if (msg.full) ClientFogCache.replaceAll(msg.chunks);
                else ClientFogCache.merge(msg.chunks);
            });
            return null;
        }
    }

    /** C2S: the map opened — ship me the full chart. */
    public static class Request implements IMessage {
        public Request() {}
        @Override public void fromBytes(ByteBuf buf) {}
        @Override public void toBytes(ByteBuf buf) {}

        public static class Handler implements IMessageHandler<Request, IMessage> {
            @Override
            public IMessage onMessage(Request msg, MessageContext ctx) {
                EntityPlayerMP player = ctx.getServerHandler().player;
                ((WorldServer) player.world).addScheduledTask(() -> TacticalWarMapNetwork.sendTo(
                        new S2CFogSync(true, ExploredMapData.get(player.world).snapshot()), player));
                return null;
            }
        }
    }
}
