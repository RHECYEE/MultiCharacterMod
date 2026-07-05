package studio.ERM.war.map.net;

import io.netty.buffer.ByteBuf;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraftforge.fml.common.network.ByteBufUtils;
import net.minecraftforge.fml.common.network.simpleimpl.IMessage;
import net.minecraftforge.fml.common.network.simpleimpl.IMessageHandler;
import net.minecraftforge.fml.common.network.simpleimpl.MessageContext;
import net.minecraft.world.WorldServer;
import studio.ERM.strategic.civil.research.ErmResearchData;
import studio.ERM.strategic.civil.research.ResearchManager;
import studio.ERM.strategic.civil.research.ResearchTree;
import studio.ERM.war.config.ResearchConfig;
import studio.ERM.war.world.WarWorldData;

/** Research GUI actions: QUEUE a node / REQUEST_SYNC. Each op replies with a fresh S2CResearchSync. */
public class C2SResearchAction implements IMessage {

    public static final int QUEUE = 0, REQUEST_SYNC = 1;

    private int op;
    private String id = "";

    public C2SResearchAction() {}

    public C2SResearchAction(int op, String id) { this.op = op; this.id = id == null ? "" : id; }

    @Override
    public void fromBytes(ByteBuf buf) {
        op = buf.readByte();
        id = ByteBufUtils.readUTF8String(buf);
    }

    @Override
    public void toBytes(ByteBuf buf) {
        buf.writeByte(op);
        ByteBufUtils.writeUTF8String(buf, id);
    }

    public static class Handler implements IMessageHandler<C2SResearchAction, IMessage> {
        @Override
        public IMessage onMessage(C2SResearchAction msg, MessageContext ctx) {
            EntityPlayerMP player = ctx.getServerHandler().player;
            ((WorldServer) player.world).addScheduledTask(() -> {
                WorldServer world = (WorldServer) player.world;
                if (msg.op == QUEUE) ResearchManager.queue(world, player, msg.id);
                TacticalWarMapNetwork.sendTo(buildSync(world, player), player);
            });
            return null;
        }

        public static S2CResearchSync buildSync(WorldServer world, EntityPlayerMP player) {
            S2CResearchSync sync = new S2CResearchSync();
            sync.aw2Present = ResearchTree.available();
            sync.commandBucks = WarWorldData.get(world)
                    .getStats(player.getUniqueID().toString()).commandPoints;
            ErmResearchData.Progress prog = ErmResearchData.get(world).forPlayer(player.getName());
            sync.currentId = prog.current == null ? "" : prog.current;
            ResearchTree.Node cur = sync.currentId.isEmpty() ? null : ResearchTree.byId(sync.currentId);
            if (cur != null) {
                int need = ResearchConfig.employeeSeconds(cur.id, cur.aw2Time);
                sync.currentPct = need > 0 ? (float) Math.min(1.0, prog.points / need) : 0f;
            }
            String name = player.getName();
            for (ResearchTree.Node n : ResearchTree.nodes()) {
                S2CResearchSync.Node r = new S2CResearchSync.Node();
                r.id = n.id;
                r.name = n.name;
                r.column = n.column;
                r.row = n.row;
                r.cbCost = ResearchConfig.cbCost(n.id, n.aw2Time);
                r.deps.addAll(n.deps);
                if (ResearchTree.hasCompleted(world, name, n.id)) r.status = S2CResearchSync.COMPLETE;
                else if (n.id.equals(sync.currentId)) r.status = S2CResearchSync.RESEARCHING;
                else if (ResearchTree.depsMet(world, name, n)) r.status = S2CResearchSync.AVAILABLE;
                else r.status = S2CResearchSync.LOCKED;
                sync.nodes.add(r);
            }
            return sync;
        }
    }
}
