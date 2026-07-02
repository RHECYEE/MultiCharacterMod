package studio.ERM.war.map.net;

import io.netty.buffer.ByteBuf;
import net.minecraft.client.Minecraft;
import net.minecraftforge.fml.common.network.simpleimpl.IMessage;
import net.minecraftforge.fml.common.network.simpleimpl.IMessageHandler;
import net.minecraftforge.fml.common.network.simpleimpl.MessageContext;
import studio.ERM.strategic.defense.DefenseMarker;
import studio.ERM.war.map.client.ClientDefensePlanCache;

import java.util.ArrayList;
import java.util.List;

/** PHASE 2 — full defensive-plan snapshot (markers + fall-back state) for the map's Military overlay. */
public class S2CDefensePlanSync implements IMessage {

    private List<DefenseMarker> markers = new ArrayList<>();
    private boolean fallbackActive;

    public S2CDefensePlanSync() {}

    public S2CDefensePlanSync(List<DefenseMarker> markers, boolean fallbackActive) {
        this.markers = new ArrayList<>(markers);
        this.fallbackActive = fallbackActive;
    }

    @Override
    public void fromBytes(ByteBuf buf) {
        markers.clear();
        fallbackActive = buf.readBoolean();
        int n = buf.readShort();
        for (int i = 0; i < n; i++) markers.add(DefenseMarker.fromBytes(buf));
    }

    @Override
    public void toBytes(ByteBuf buf) {
        buf.writeBoolean(fallbackActive);
        buf.writeShort(markers.size());
        for (DefenseMarker m : markers) m.toBytes(buf);
    }

    public static class Handler implements IMessageHandler<S2CDefensePlanSync, IMessage> {
        @Override
        public IMessage onMessage(S2CDefensePlanSync msg, MessageContext ctx) {
            Minecraft.getMinecraft().addScheduledTask(
                    () -> ClientDefensePlanCache.update(msg.markers, msg.fallbackActive));
            return null;
        }
    }
}
