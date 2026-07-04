package studio.ERM.war.map.net;

import io.netty.buffer.ByteBuf;
import net.minecraft.client.Minecraft;
import net.minecraftforge.fml.common.network.ByteBufUtils;
import net.minecraftforge.fml.common.network.simpleimpl.IMessage;
import net.minecraftforge.fml.common.network.simpleimpl.IMessageHandler;
import net.minecraftforge.fml.common.network.simpleimpl.MessageContext;
import studio.ERM.war.map.client.ClientResearchCache;

import java.util.ArrayList;
import java.util.List;

/** Server -> client snapshot of the research tree for one player: nodes, layout, status, progress. */
public class S2CResearchSync implements IMessage {

    public static final int LOCKED = 0, AVAILABLE = 1, RESEARCHING = 2, COMPLETE = 3;

    public static class Node {
        public String id, name;
        public int column, row, status, cbCost;
        public List<String> deps = new ArrayList<>();
    }

    public int commandBucks;
    public boolean aw2Present;
    public String currentId = "";
    public float currentPct;
    public List<Node> nodes = new ArrayList<>();

    public S2CResearchSync() {}

    @Override
    public void fromBytes(ByteBuf buf) {
        commandBucks = buf.readInt();
        aw2Present = buf.readBoolean();
        currentId = ByteBufUtils.readUTF8String(buf);
        currentPct = buf.readFloat();
        int n = buf.readInt();
        for (int i = 0; i < n; i++) {
            Node nd = new Node();
            nd.id = ByteBufUtils.readUTF8String(buf);
            nd.name = ByteBufUtils.readUTF8String(buf);
            nd.column = buf.readShort();
            nd.row = buf.readShort();
            nd.status = buf.readByte();
            nd.cbCost = buf.readInt();
            int d = buf.readShort();
            for (int j = 0; j < d; j++) nd.deps.add(ByteBufUtils.readUTF8String(buf));
            nodes.add(nd);
        }
    }

    @Override
    public void toBytes(ByteBuf buf) {
        buf.writeInt(commandBucks);
        buf.writeBoolean(aw2Present);
        ByteBufUtils.writeUTF8String(buf, currentId == null ? "" : currentId);
        buf.writeFloat(currentPct);
        buf.writeInt(nodes.size());
        for (Node nd : nodes) {
            ByteBufUtils.writeUTF8String(buf, nd.id);
            ByteBufUtils.writeUTF8String(buf, nd.name);
            buf.writeShort(nd.column);
            buf.writeShort(nd.row);
            buf.writeByte(nd.status);
            buf.writeInt(nd.cbCost);
            buf.writeShort(nd.deps.size());
            for (String d : nd.deps) ByteBufUtils.writeUTF8String(buf, d);
        }
    }

    public static class Handler implements IMessageHandler<S2CResearchSync, IMessage> {
        @Override
        public IMessage onMessage(S2CResearchSync message, MessageContext ctx) {
            Minecraft.getMinecraft().addScheduledTask(() -> ClientResearchCache.update(message));
            return null;
        }
    }
}
