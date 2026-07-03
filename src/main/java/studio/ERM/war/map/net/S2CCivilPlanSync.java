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

/**
 * Full civilian-infrastructure snapshot (roads + districts) for the map's Civilian tab, plus a
 * settlement-stats header (labor + housing) drawn under the sidebar Legend. Each marker also
 * carries its live assigned/desired worker counts for the inspect panel.
 */
public class S2CCivilPlanSync implements IMessage {

    private List<CivilMarker> markers = new ArrayList<>();
    private int availWorkers, totalWorkers, availBeds, totalBeds;

    public S2CCivilPlanSync() {}

    public S2CCivilPlanSync(List<CivilMarker> markers) {
        this.markers = new ArrayList<>(markers);
    }

    public S2CCivilPlanSync(List<CivilMarker> markers, int availWorkers, int totalWorkers,
                            int availBeds, int totalBeds) {
        this.markers = new ArrayList<>(markers);
        this.availWorkers = availWorkers;
        this.totalWorkers = totalWorkers;
        this.availBeds = availBeds;
        this.totalBeds = totalBeds;
    }

    @Override
    public void fromBytes(ByteBuf buf) {
        markers.clear();
        int n = buf.readShort();
        for (int i = 0; i < n; i++) markers.add(CivilMarker.fromBytes(buf));
        availWorkers = buf.readInt();
        totalWorkers = buf.readInt();
        availBeds = buf.readInt();
        totalBeds = buf.readInt();
    }

    @Override
    public void toBytes(ByteBuf buf) {
        buf.writeShort(markers.size());
        for (CivilMarker m : markers) m.toBytes(buf);
        buf.writeInt(availWorkers);
        buf.writeInt(totalWorkers);
        buf.writeInt(availBeds);
        buf.writeInt(totalBeds);
    }

    public static class Handler implements IMessageHandler<S2CCivilPlanSync, IMessage> {
        @Override
        public IMessage onMessage(S2CCivilPlanSync msg, MessageContext ctx) {
            Minecraft.getMinecraft().addScheduledTask(() -> {
                ClientCivilPlanCache.update(msg.markers);
                ClientCivilPlanCache.updateStats(
                        msg.availWorkers, msg.totalWorkers, msg.availBeds, msg.totalBeds);
            });
            return null;
        }
    }
}
