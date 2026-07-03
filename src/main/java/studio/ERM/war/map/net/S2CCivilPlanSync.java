package studio.ERM.war.map.net;

import io.netty.buffer.ByteBuf;
import net.minecraft.client.Minecraft;
import net.minecraftforge.fml.common.network.simpleimpl.IMessage;
import net.minecraftforge.fml.common.network.simpleimpl.IMessageHandler;
import net.minecraftforge.fml.common.network.simpleimpl.MessageContext;
import studio.ERM.strategic.civil.CivilMarker;
import studio.ERM.war.map.client.ClientCivilPlanCache;

import java.util.ArrayList;
import java.util.List;

/** Full civilian-infrastructure snapshot (roads + districts) for the map's Civilian tab. */
public class S2CCivilPlanSync implements IMessage {

    private List<CivilMarker> markers = new ArrayList<>();

    public S2CCivilPlanSync() {}

    public S2CCivilPlanSync(List<CivilMarker> markers) {
        this.markers = new ArrayList<>(markers);
    }

    @Override
    public void fromBytes(ByteBuf buf) {
        markers.clear();
        int n = buf.readShort();
        for (int i = 0; i < n; i++) markers.add(CivilMarker.fromBytes(buf));
    }

    @Override
    public void toBytes(ByteBuf buf) {
        buf.writeShort(markers.size());
        for (CivilMarker m : markers) m.toBytes(buf);
    }

    public static class Handler implements IMessageHandler<S2CCivilPlanSync, IMessage> {
        @Override
        public IMessage onMessage(S2CCivilPlanSync msg, MessageContext ctx) {
            Minecraft.getMinecraft().addScheduledTask(
                    () -> ClientCivilPlanCache.update(msg.markers));
            return null;
        }
    }
}
