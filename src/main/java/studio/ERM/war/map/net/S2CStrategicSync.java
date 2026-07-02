package studio.ERM.war.map.net;

import io.netty.buffer.ByteBuf;
import net.minecraft.client.Minecraft;
import net.minecraftforge.fml.common.network.ByteBufUtils;
import net.minecraftforge.fml.common.network.simpleimpl.IMessage;
import net.minecraftforge.fml.common.network.simpleimpl.IMessageHandler;
import net.minecraftforge.fml.common.network.simpleimpl.MessageContext;
import studio.ERM.war.map.client.ClientStrategicCache;

import java.util.ArrayList;
import java.util.List;

/**
 * PHASE 2 — server -> client snapshot of the STRATEGIC MAP (patrols, traders, convoys...) for the
 * tactical war map's traffic overlay. Mirrors PacketDeployedBattlesSync: a small periodic full
 * snapshot; the client just replaces its cache.
 */
public class S2CStrategicSync implements IMessage {

    public static class Data {
        public String type;    // "patrol" / "trader" / future archetypes
        public String label;   // human label for hover/log
        public int x, z;       // strategic position
        public boolean live;   // materialized right now?
        public int strength;   // members remaining
    }

    private List<Data> objects = new ArrayList<>();

    public S2CStrategicSync() {}

    public S2CStrategicSync(List<Data> objects) {
        this.objects = objects != null ? objects : new ArrayList<>();
    }

    @Override
    public void fromBytes(ByteBuf buf) {
        objects.clear();
        int n = buf.readInt();
        for (int i = 0; i < n; i++) {
            Data d = new Data();
            d.type = ByteBufUtils.readUTF8String(buf);
            d.label = ByteBufUtils.readUTF8String(buf);
            d.x = buf.readInt();
            d.z = buf.readInt();
            d.live = buf.readBoolean();
            d.strength = buf.readInt();
            objects.add(d);
        }
    }

    @Override
    public void toBytes(ByteBuf buf) {
        buf.writeInt(objects.size());
        for (Data d : objects) {
            ByteBufUtils.writeUTF8String(buf, d.type == null ? "" : d.type);
            ByteBufUtils.writeUTF8String(buf, d.label == null ? "" : d.label);
            buf.writeInt(d.x);
            buf.writeInt(d.z);
            buf.writeBoolean(d.live);
            buf.writeInt(d.strength);
        }
    }

    public static class Handler implements IMessageHandler<S2CStrategicSync, IMessage> {
        @Override
        public IMessage onMessage(S2CStrategicSync message, MessageContext ctx) {
            Minecraft.getMinecraft().addScheduledTask(() -> ClientStrategicCache.update(message.objects));
            return null;
        }
    }
}
