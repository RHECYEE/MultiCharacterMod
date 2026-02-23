package studio.ERM.war.map.net;

import io.netty.buffer.ByteBuf;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.util.text.TextComponentString;
import net.minecraft.util.text.TextFormatting;
import net.minecraftforge.fml.common.network.ByteBufUtils;
import net.minecraftforge.fml.common.network.simpleimpl.IMessage;
import net.minecraftforge.fml.common.network.simpleimpl.IMessageHandler;
import net.minecraftforge.fml.common.network.simpleimpl.MessageContext;
import studio.ERM.war.BattleManagers.deployed.DeployedBattleManager;

/**
 * Packet sent from client to server when player clicks to deploy a battle from the map.
 */
public class PacketDeployBattle implements IMessage {

    private String directorId;
    private int worldX;
    private int worldZ;

    public PacketDeployBattle() {
        // Required for forge
    }

    public PacketDeployBattle(String directorId, int worldX, int worldZ) {
        this.directorId = directorId;
        this.worldX = worldX;
        this.worldZ = worldZ;
    }

    @Override
    public void fromBytes(ByteBuf buf) {
        this.directorId = ByteBufUtils.readUTF8String(buf);
        this.worldX = buf.readInt();
        this.worldZ = buf.readInt();
    }

    @Override
    public void toBytes(ByteBuf buf) {
        ByteBufUtils.writeUTF8String(buf, directorId);
        buf.writeInt(worldX);
        buf.writeInt(worldZ);
    }

    public static class Handler implements IMessageHandler<PacketDeployBattle, IMessage> {

        @Override
        public IMessage onMessage(PacketDeployBattle message, MessageContext ctx) {
            EntityPlayerMP player = ctx.getServerHandler().player;
            
            // Schedule on main thread
            player.getServerWorld().addScheduledTask(() -> {
                DeployedBattleManager manager = DeployedBattleManager.get(player.world);
                if (manager == null) {
                    player.sendMessage(new TextComponentString(
                        TextFormatting.RED + "Failed to deploy battle: Manager unavailable"
                    ));
                    return;
                }

                DeployedBattleManager.DeployResult result = manager.deployBattle(
                    player.world,
                    player,
                    message.directorId,
                    message.worldX,
                    message.worldZ
                );

                if (!result.isSuccess()) {
                    player.sendMessage(new TextComponentString(
                        TextFormatting.RED + result.getMessage()
                    ));
                }
                // Success message is sent by the manager
            });

            return null;
        }
    }
}
