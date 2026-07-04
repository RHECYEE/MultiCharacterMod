package studio.ERM.war.map.net;

import io.netty.buffer.ByteBuf;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.world.WorldServer;
import net.minecraftforge.fml.common.network.simpleimpl.IMessage;
import net.minecraftforge.fml.common.network.simpleimpl.IMessageHandler;
import net.minecraftforge.fml.common.network.simpleimpl.MessageContext;
import studio.ERM.strategic.civil.evac.EvacuationManager;

/** Toggle the civilian evacuation from the war map's Civilian-tab button. */
public class C2SEvacuationToggle implements IMessage {

    public C2SEvacuationToggle() {}

    @Override public void fromBytes(ByteBuf buf) {}
    @Override public void toBytes(ByteBuf buf) {}

    public static class Handler implements IMessageHandler<C2SEvacuationToggle, IMessage> {
        @Override
        public IMessage onMessage(C2SEvacuationToggle msg, MessageContext ctx) {
            EntityPlayerMP player = ctx.getServerHandler().player;
            ((WorldServer) player.world).addScheduledTask(() -> {
                WorldServer world = (WorldServer) player.world;
                EvacuationManager.setEvacuating(world, !EvacuationManager.isEvacuating(world));
            });
            return null;
        }
    }
}
