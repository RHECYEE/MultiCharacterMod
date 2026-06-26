package studio.ERM.war.map.net;

import io.netty.buffer.ByteBuf;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraftforge.fml.common.network.simpleimpl.IMessage;
import net.minecraftforge.fml.common.network.simpleimpl.IMessageHandler;
import net.minecraftforge.fml.common.network.simpleimpl.MessageContext;
import studio.ERM.war.WarClaimHandler;

/**
 * Client -> Server: Request a full territory sync (sent when map opens).
 */
public class C2SRequestTerritorySync implements IMessage {

    public C2SRequestTerritorySync() {}

    @Override
    public void fromBytes(ByteBuf buf) {}

    @Override
    public void toBytes(ByteBuf buf) {}

    public static class Handler implements IMessageHandler<C2SRequestTerritorySync, IMessage> {
        @Override
        public IMessage onMessage(C2SRequestTerritorySync message, MessageContext ctx) {
            EntityPlayerMP player = ctx.getServerHandler().player;
            // Real-logger trace: proves the open-map sync request actually arrived server-side.
            studio.ERM.EpochRunnerMod.logger.info("[ERM-Map] SERVER: C2SRequestTerritorySync received from "
                    + player.getName());
            player.getServerWorld().addScheduledTask(() -> {
                WarClaimHandler.syncTerritoryToPlayer(player);
            });
            return null;
        }
    }
}
