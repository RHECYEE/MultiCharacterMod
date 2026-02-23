package studio.ERM.war.map.net;

import io.netty.buffer.ByteBuf;
import net.minecraft.client.Minecraft;
import net.minecraftforge.fml.common.network.ByteBufUtils;
import net.minecraftforge.fml.common.network.simpleimpl.IMessage;
import net.minecraftforge.fml.common.network.simpleimpl.IMessageHandler;
import net.minecraftforge.fml.common.network.simpleimpl.MessageContext;
import studio.ERM.war.map.client.ClientDeployedBattleCache;

import java.util.ArrayList;
import java.util.List;

/**
 * Packet sent from server to client containing deployed battle locations for map display.
 */
public class PacketDeployedBattlesSync implements IMessage {

    private List<DeployedBattleData> battles = new ArrayList<>();

    public PacketDeployedBattlesSync() {
        // Required for forge
    }

    public PacketDeployedBattlesSync(List<DeployedBattleData> battles) {
        this.battles = battles != null ? battles : new ArrayList<>();
    }

    @Override
    public void fromBytes(ByteBuf buf) {
        battles.clear();
        int count = buf.readInt();
        for (int i = 0; i < count; i++) {
            DeployedBattleData data = new DeployedBattleData();
            data.id = ByteBufUtils.readUTF8String(buf);
            data.directorId = ByteBufUtils.readUTF8String(buf);
            data.displayName = ByteBufUtils.readUTF8String(buf);
            data.ownerName = ByteBufUtils.readUTF8String(buf);
            data.worldX = buf.readInt();
            data.worldZ = buf.readInt();
            data.triggered = buf.readBoolean();
            data.isOwner = buf.readBoolean();
            battles.add(data);
        }
    }

    @Override
    public void toBytes(ByteBuf buf) {
        buf.writeInt(battles.size());
        for (DeployedBattleData data : battles) {
            ByteBufUtils.writeUTF8String(buf, data.id);
            ByteBufUtils.writeUTF8String(buf, data.directorId);
            ByteBufUtils.writeUTF8String(buf, data.displayName);
            ByteBufUtils.writeUTF8String(buf, data.ownerName);
            buf.writeInt(data.worldX);
            buf.writeInt(data.worldZ);
            buf.writeBoolean(data.triggered);
            buf.writeBoolean(data.isOwner);
        }
    }

    /**
     * Simplified data for client display
     */
    public static class DeployedBattleData {
        public String id;
        public String directorId;
        public String displayName;
        public String ownerName;
        public int worldX;
        public int worldZ;
        public boolean triggered;
        public boolean isOwner; // True if the receiving player owns this battle
    }

    public static class Handler implements IMessageHandler<PacketDeployedBattlesSync, IMessage> {

        @Override
        public IMessage onMessage(PacketDeployedBattlesSync message, MessageContext ctx) {
            Minecraft.getMinecraft().addScheduledTask(() -> {
                ClientDeployedBattleCache.updateFromServer(message.battles);
            });
            return null;
        }
    }
}
