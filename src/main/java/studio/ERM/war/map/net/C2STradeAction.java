package studio.ERM.war.map.net;

import io.netty.buffer.ByteBuf;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.util.math.BlockPos;
import net.minecraftforge.fml.common.network.ByteBufUtils;
import net.minecraftforge.fml.common.network.simpleimpl.IMessage;
import net.minecraftforge.fml.common.network.simpleimpl.IMessageHandler;
import net.minecraftforge.fml.common.network.simpleimpl.MessageContext;
import net.minecraft.world.WorldServer;
import studio.ERM.strategic.civil.DistrictRegistry;
import studio.ERM.strategic.civil.trade.TradeMarketData;
import studio.ERM.strategic.civil.trade.TradeShipmentManager;
import studio.ERM.war.config.TradePriceConfig;
import studio.ERM.war.world.WarWorldData;

/** Trade Depot actions: BUY / SELL / REQUEST_SYNC. Every op replies with a fresh {@link S2CTradeSync}. */
public class C2STradeAction implements IMessage {

    public static final int BUY = 0, SELL = 1, REQUEST_SYNC = 2, ORDER = 3;

    private int op, count, x, y, z;
    private String id = "";
    // ORDER payload: the whole confirmed shopping list in one packet -> one merchant cart.
    private java.util.List<String> orderIds = new java.util.ArrayList<>();
    private java.util.List<Integer> orderCounts = new java.util.ArrayList<>();

    public C2STradeAction() {}

    public C2STradeAction(int op, String id, int count, BlockPos depot) {
        this.op = op; this.id = id == null ? "" : id; this.count = count;
        this.x = depot.getX(); this.y = depot.getY(); this.z = depot.getZ();
    }

    /** A confirmed multi-line order. */
    public static C2STradeAction order(java.util.Map<String, Integer> order, BlockPos depot) {
        C2STradeAction p = new C2STradeAction(ORDER, "", 0, depot);
        for (java.util.Map.Entry<String, Integer> e : order.entrySet()) {
            p.orderIds.add(e.getKey());
            p.orderCounts.add(e.getValue());
        }
        return p;
    }

    @Override
    public void fromBytes(ByteBuf buf) {
        op = buf.readByte();
        count = buf.readInt();
        x = buf.readInt(); y = buf.readInt(); z = buf.readInt();
        id = ByteBufUtils.readUTF8String(buf);
        orderIds.clear(); orderCounts.clear();
        int n = buf.readShort();
        for (int i = 0; i < n; i++) {
            orderIds.add(ByteBufUtils.readUTF8String(buf));
            orderCounts.add(buf.readInt());
        }
    }

    @Override
    public void toBytes(ByteBuf buf) {
        buf.writeByte(op);
        buf.writeInt(count);
        buf.writeInt(x); buf.writeInt(y); buf.writeInt(z);
        ByteBufUtils.writeUTF8String(buf, id);
        buf.writeShort(orderIds.size());
        for (int i = 0; i < orderIds.size(); i++) {
            ByteBufUtils.writeUTF8String(buf, orderIds.get(i));
            buf.writeInt(orderCounts.get(i));
        }
    }

    public static class Handler implements IMessageHandler<C2STradeAction, IMessage> {
        @Override
        public IMessage onMessage(C2STradeAction msg, MessageContext ctx) {
            EntityPlayerMP player = ctx.getServerHandler().player;
            ((WorldServer) player.world).addScheduledTask(() -> {
                WorldServer world = (WorldServer) player.world;
                BlockPos depot = new BlockPos(msg.x, msg.y, msg.z);
                if (msg.op == BUY) TradeShipmentManager.buy(world, player, msg.id, msg.count, depot);
                else if (msg.op == SELL) TradeShipmentManager.sell(world, player, msg.id, msg.count, depot);
                else if (msg.op == ORDER) TradeShipmentManager.buyOrder(world, player, msg.orderIds, msg.orderCounts, depot);
                TacticalWarMapNetwork.sendTo(buildSync(world, player), player);
            });
            return null;
        }

        /** Snapshot the whole catalogue for this player: live price, demand, stored, availability. */
        public static S2CTradeSync buildSync(WorldServer world, EntityPlayerMP player) {
            S2CTradeSync sync = new S2CTradeSync();
            sync.rivalLevel = DistrictRegistry.rivalLevel(world);
            sync.commandBucks = WarWorldData.get(world)
                    .getStats(player.getUniqueID().toString()).commandPoints;
            TradeMarketData market = TradeMarketData.get(world);
            for (TradePriceConfig.Category c : TradePriceConfig.categories()) {
                for (TradePriceConfig.ItemPrice ip : c.items) {
                    if (TradePriceConfig.resolve(ip.id).isEmpty()) continue; // missing mod -> hide
                    S2CTradeSync.Row r = new S2CTradeSync.Row();
                    r.id = ip.id;
                    r.category = c.name;
                    r.price = (float) market.price(world, ip.id);
                    r.demand = market.demand(world, ip.id);
                    r.stored = TradeShipmentManager.storedCount(world, ip.id);
                    r.minLevel = ip.minLevel;
                    r.available = sync.rivalLevel >= ip.minLevel;
                    sync.rows.add(r);
                }
            }
            // Trade agreements — a lightweight stub until the diplomacy module lands. The open market is
            // always available; a formal kingdom agreement appears as the world advances (rival level).
            S2CTradeSync.Agreement open = new S2CTradeSync.Agreement();
            open.name = "Open Market"; open.status = "Neutral";
            open.imports = "All (config)"; open.exports = "All surplus";
            sync.agreements.add(open);
            if (sync.rivalLevel >= 3) {
                S2CTradeSync.Agreement k = new S2CTradeSync.Agreement();
                k.name = "Kingdom of Red Hills"; k.status = "Friendly";
                k.imports = "Steel, Coal"; k.exports = "Food, Leather";
                sync.agreements.add(k);
            }
            return sync;
        }
    }
}
