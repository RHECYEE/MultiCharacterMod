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

    /** One live courier job / shipment, reduced to what the map draws: a src->dst line + label. */
    public static class JobLine {
        public int sx, sz, dx, dz;
        public int state; // CourierJob.STATE_* ; 3 = trade SHIPMENT (teal, real progress)
        public String label = "";
        /** Real leg progress 0-100, or -1 = decorative loop animation (courier jobs). */
        public byte progress = -1;
    }

    /** One PLAYER-KNOWN strategic resource deposit for the map: a clickable camp-site icon. */
    public static class Deposit {
        public int x, z;
        public int type;   // ResourceNodeData type index (names client-side via TYPE_NAMES)
        public int state;  // 0 charted, 1 camp pending, 2 your camp, 3 rival camp, 4 exhausted
        public String name = "";
    }

    private List<CivilMarker> markers = new ArrayList<>();
    private int availWorkers, totalWorkers, availBeds, totalBeds;
    private List<JobLine> jobs = new ArrayList<>();
    private List<Deposit> deposits = new ArrayList<>();

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

    public S2CCivilPlanSync withJobs(List<JobLine> jobLines) {
        this.jobs = jobLines != null ? jobLines : new ArrayList<>();
        return this;
    }

    public S2CCivilPlanSync withDeposits(List<Deposit> deps) {
        this.deposits = deps != null ? deps : new ArrayList<>();
        return this;
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
        jobs.clear();
        int jn = buf.readShort();
        for (int i = 0; i < jn; i++) {
            JobLine j = new JobLine();
            j.sx = buf.readInt(); j.sz = buf.readInt();
            j.dx = buf.readInt(); j.dz = buf.readInt();
            j.state = buf.readByte();
            j.progress = buf.readByte();
            j.label = net.minecraftforge.fml.common.network.ByteBufUtils.readUTF8String(buf);
            jobs.add(j);
        }
        deposits.clear();
        int dn = buf.readShort();
        for (int i = 0; i < dn; i++) {
            Deposit d = new Deposit();
            d.x = buf.readInt(); d.z = buf.readInt();
            d.type = buf.readByte();
            d.state = buf.readByte();
            d.name = net.minecraftforge.fml.common.network.ByteBufUtils.readUTF8String(buf);
            deposits.add(d);
        }
    }

    @Override
    public void toBytes(ByteBuf buf) {
        buf.writeShort(markers.size());
        for (CivilMarker m : markers) m.toBytes(buf);
        buf.writeInt(availWorkers);
        buf.writeInt(totalWorkers);
        buf.writeInt(availBeds);
        buf.writeInt(totalBeds);
        buf.writeShort(jobs.size());
        for (JobLine j : jobs) {
            buf.writeInt(j.sx); buf.writeInt(j.sz);
            buf.writeInt(j.dx); buf.writeInt(j.dz);
            buf.writeByte(j.state);
            buf.writeByte(j.progress);
            net.minecraftforge.fml.common.network.ByteBufUtils.writeUTF8String(
                    buf, j.label == null ? "" : j.label);
        }
        buf.writeShort(deposits.size());
        for (Deposit d : deposits) {
            buf.writeInt(d.x); buf.writeInt(d.z);
            buf.writeByte(d.type);
            buf.writeByte(d.state);
            net.minecraftforge.fml.common.network.ByteBufUtils.writeUTF8String(
                    buf, d.name == null ? "" : d.name);
        }
    }

    public static class Handler implements IMessageHandler<S2CCivilPlanSync, IMessage> {
        @Override
        public IMessage onMessage(S2CCivilPlanSync msg, MessageContext ctx) {
            Minecraft.getMinecraft().addScheduledTask(() -> {
                ClientCivilPlanCache.update(msg.markers);
                ClientCivilPlanCache.updateStats(
                        msg.availWorkers, msg.totalWorkers, msg.availBeds, msg.totalBeds);
                ClientCivilPlanCache.updateJobs(msg.jobs);
                ClientCivilPlanCache.updateDeposits(msg.deposits);
            });
            return null;
        }
    }
}
