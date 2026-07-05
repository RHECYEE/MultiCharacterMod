package studio.ERM.war.map.net;

import io.netty.buffer.ByteBuf;
import net.minecraft.client.Minecraft;
import net.minecraftforge.fml.common.network.ByteBufUtils;
import net.minecraftforge.fml.common.network.simpleimpl.IMessage;
import net.minecraftforge.fml.common.network.simpleimpl.IMessageHandler;
import net.minecraftforge.fml.common.network.simpleimpl.MessageContext;
import studio.ERM.war.map.client.ClientTradeCache;

import java.util.ArrayList;
import java.util.List;

/** Server -> client snapshot backing the Trade Depot GUI (catalogue rows + agreements + player CB). */
public class S2CTradeSync implements IMessage {

    public static class Row {
        public String id, category, demand;
        public float price;
        public int stored, minLevel;
        public boolean available;
    }

    public static class Agreement {
        public String name, status, imports, exports;
    }

    public int commandBucks, rivalLevel;
    public List<Row> rows = new ArrayList<>();
    public List<Agreement> agreements = new ArrayList<>();

    public S2CTradeSync() {}

    @Override
    public void fromBytes(ByteBuf buf) {
        commandBucks = buf.readInt();
        rivalLevel = buf.readInt();
        int n = buf.readInt();
        for (int i = 0; i < n; i++) {
            Row r = new Row();
            r.id = ByteBufUtils.readUTF8String(buf);
            r.category = ByteBufUtils.readUTF8String(buf);
            r.demand = ByteBufUtils.readUTF8String(buf);
            r.price = buf.readFloat();
            r.stored = buf.readInt();
            r.minLevel = buf.readInt();
            r.available = buf.readBoolean();
            rows.add(r);
        }
        int a = buf.readInt();
        for (int i = 0; i < a; i++) {
            Agreement ag = new Agreement();
            ag.name = ByteBufUtils.readUTF8String(buf);
            ag.status = ByteBufUtils.readUTF8String(buf);
            ag.imports = ByteBufUtils.readUTF8String(buf);
            ag.exports = ByteBufUtils.readUTF8String(buf);
            agreements.add(ag);
        }
    }

    @Override
    public void toBytes(ByteBuf buf) {
        buf.writeInt(commandBucks);
        buf.writeInt(rivalLevel);
        buf.writeInt(rows.size());
        for (Row r : rows) {
            ByteBufUtils.writeUTF8String(buf, r.id == null ? "" : r.id);
            ByteBufUtils.writeUTF8String(buf, r.category == null ? "" : r.category);
            ByteBufUtils.writeUTF8String(buf, r.demand == null ? "" : r.demand);
            buf.writeFloat(r.price);
            buf.writeInt(r.stored);
            buf.writeInt(r.minLevel);
            buf.writeBoolean(r.available);
        }
        buf.writeInt(agreements.size());
        for (Agreement ag : agreements) {
            ByteBufUtils.writeUTF8String(buf, ag.name == null ? "" : ag.name);
            ByteBufUtils.writeUTF8String(buf, ag.status == null ? "" : ag.status);
            ByteBufUtils.writeUTF8String(buf, ag.imports == null ? "" : ag.imports);
            ByteBufUtils.writeUTF8String(buf, ag.exports == null ? "" : ag.exports);
        }
    }

    public static class Handler implements IMessageHandler<S2CTradeSync, IMessage> {
        @Override
        public IMessage onMessage(S2CTradeSync message, MessageContext ctx) {
            Minecraft.getMinecraft().addScheduledTask(() -> ClientTradeCache.update(message));
            return null;
        }
    }
}
