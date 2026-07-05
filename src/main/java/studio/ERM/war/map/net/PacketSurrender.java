package studio.ERM.war.map.net;

import io.netty.buffer.ByteBuf;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.util.text.TextComponentString;
import net.minecraft.util.text.TextFormatting;
import net.minecraft.world.World;
import net.minecraftforge.fml.common.network.simpleimpl.IMessage;
import net.minecraftforge.fml.common.network.simpleimpl.IMessageHandler;
import net.minecraftforge.fml.common.network.simpleimpl.MessageContext;
import studio.ERM.war.BattleManagers.core.BattleEngine;

/**
 * Client -> Server: Player requests surrender during active battle.
 */
public final class PacketSurrender implements IMessage {

    public PacketSurrender() {
    }

    @Override
    public void fromBytes(ByteBuf buf) {
        // no payload
    }

    @Override
    public void toBytes(ByteBuf buf) {
        // no payload
    }

    public static final class Handler implements IMessageHandler<PacketSurrender, IMessage> {

        @Override
        public IMessage onMessage(PacketSurrender message, MessageContext ctx) {
            if (ctx == null || ctx.getServerHandler() == null) {
                return null;
            }

            EntityPlayerMP player = ctx.getServerHandler().player;
            if (player == null) {
                return null;
            }

            World world = player.getServerWorld();
            if (world == null) {
                return null;
            }

            try {
                BattleEngine engine = BattleEngine.get(world);
                if (engine != null && engine.hasActiveBattle()) {
                    engine.surrender(player, player.getPosition());
                    player.sendMessage(new TextComponentString(TextFormatting.RED + "You have surrendered the battle."));
                } else {
                    player.sendMessage(new TextComponentString(TextFormatting.GRAY + "No active battle to surrender."));
                }
            } catch (Throwable t) {
                player.sendMessage(new TextComponentString(TextFormatting.RED + "Surrender failed: " + t.getMessage()));
            }

            return null;
        }
    }
}
