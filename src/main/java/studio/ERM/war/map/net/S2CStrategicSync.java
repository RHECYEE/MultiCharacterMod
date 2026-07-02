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
    // LIVE UNIT DOTS: loaded soldiers as map dots -- friendly (the player's army) and enemy (the
    // besiegers; later narrowed to KNOWN/SEEN enemies). Flat x,z pairs, capped server-side.
    private int[] friendlyDots = new int[0];
    private int[] enemyDots = new int[0];
    // SIEGE ALERT: "enemy camp gathering here" banner position while a battle is active.
    private boolean siegeActive = false;
    private int siegeX, siegeZ;

    public S2CStrategicSync() {}

    public S2CStrategicSync(List<Data> objects, int[] friendlyDots, int[] enemyDots,
                            boolean siegeActive, int siegeX, int siegeZ) {
        this.objects = objects != null ? objects : new ArrayList<>();
        this.friendlyDots = friendlyDots != null ? friendlyDots : new int[0];
        this.enemyDots = enemyDots != null ? enemyDots : new int[0];
        this.siegeActive = siegeActive;
        this.siegeX = siegeX;
        this.siegeZ = siegeZ;
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
        friendlyDots = readIntArray(buf);
        enemyDots = readIntArray(buf);
        siegeActive = buf.readBoolean();
        siegeX = buf.readInt();
        siegeZ = buf.readInt();
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
        writeIntArray(buf, friendlyDots);
        writeIntArray(buf, enemyDots);
        buf.writeBoolean(siegeActive);
        buf.writeInt(siegeX);
        buf.writeInt(siegeZ);
    }

    private static void writeIntArray(ByteBuf buf, int[] a) {
        buf.writeInt(a.length);
        for (int v : a) buf.writeInt(v);
    }

    private static int[] readIntArray(ByteBuf buf) {
        int n = Math.max(0, Math.min(4096, buf.readInt()));
        int[] a = new int[n];
        for (int i = 0; i < n; i++) a[i] = buf.readInt();
        return a;
    }

    public static class Handler implements IMessageHandler<S2CStrategicSync, IMessage> {
        @Override
        public IMessage onMessage(S2CStrategicSync message, MessageContext ctx) {
            Minecraft.getMinecraft().addScheduledTask(() -> ClientStrategicCache.update(
                    message.objects, message.friendlyDots, message.enemyDots,
                    message.siegeActive, message.siegeX, message.siegeZ));
            return null;
        }
    }
}
