package studio.ERM.strategic.civil.trade;

import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.item.ItemStack;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.text.TextComponentString;
import net.minecraft.util.text.TextFormatting;
import net.minecraft.world.WorldServer;
import net.minecraftforge.event.world.WorldEvent;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import net.minecraftforge.fml.common.gameevent.TickEvent;
import net.minecraftforge.items.ItemHandlerHelper;
import studio.ERM.EpochRunnerMod;
import studio.ERM.strategic.civil.CivilMarker;
import studio.ERM.strategic.civil.CivilPlanData;
import studio.ERM.strategic.civil.DistrictRegistry;
import studio.ERM.war.config.TradePriceConfig;
import studio.ERM.war.districts.TileEntityDistrictMarker;
import studio.ERM.war.world.WarWorldData;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * TRADE SHIPMENTS — the physical delay between an order and its goods, per the spec:
 *   BUY  -> Command Bucks charged now; a merchant DELIVERS the items to the trade depot ~N seconds
 *           later ("Purchasing creates an import shipment. Merchants physically deliver later.").
 *   SELL -> the goods leave connected storage now; Command Bucks are awarded ONCE THE SHIPMENT LEAVES
 *           (~N seconds later) ("awarded once the shipment successfully leaves the settlement").
 * Also ticks the market's saturation decay. Transient (a restart cancels in-flight shipments).
 */
public final class TradeShipmentManager {

    private TradeShipmentManager() {}

    private static final List<Shipment> SHIPMENTS = new ArrayList<>();
    /** Set-dressing chest carts parked at the depot after arrival/departure: {entity, expiryTick}. */
    private static final List<Object[]> DECOR_CARTS = new ArrayList<>();

    private static final class Shipment {
        final int dim; final boolean importing;
        final List<String> ids; final List<Integer> counts;
        final double bucks; final BlockPos depot; final long startTick, completeTick; final UUID player;
        /** Fixed strategic-map origin the cart travels from (imports) / toward (exports). */
        final int ox, oz;
        Shipment(WorldServer world, boolean importing, List<String> ids, List<Integer> counts,
                 double bucks, BlockPos depot, long startTick, long completeTick, UUID player) {
            this.dim = world.provider.getDimension(); this.importing = importing;
            this.ids = ids; this.counts = counts; this.bucks = bucks; this.depot = depot;
            this.startTick = startTick; this.completeTick = completeTick; this.player = player;
            double ang = Math.random() * Math.PI * 2;
            this.ox = depot.getX() + (int) (Math.cos(ang) * 280);
            this.oz = depot.getZ() + (int) (Math.sin(ang) * 280);
        }
        int totalCount() { int n = 0; for (int c : counts) n += c; return n; }
    }

    // ---- API called from the packet handler ----

    /** True if the current rival level unlocks buying this item. */
    public static boolean available(WorldServer world, String id) {
        return DistrictRegistry.rivalLevel(world) >= TradePriceConfig.minLevel(id);
    }

    public static void buy(WorldServer world, EntityPlayerMP player, String id, int count, BlockPos depot) {
        List<String> ids = new ArrayList<>(); ids.add(id);
        List<Integer> counts = new ArrayList<>(); counts.add(count);
        buyOrder(world, player, ids, counts, depot);
    }

    /** CONFIRMED ORDER: charge the whole list once, then ONE merchant cart brings everything in
     *  from the strategic map (visible as a moving icon) and unloads into your warehouse. */
    public static void buyOrder(WorldServer world, EntityPlayerMP player,
                                List<String> ids, List<Integer> counts, BlockPos depot) {
        TradeMarketData market = TradeMarketData.get(world);
        List<String> okIds = new ArrayList<>();
        List<Integer> okCounts = new ArrayList<>();
        double cost = 0;
        for (int i = 0; i < ids.size() && i < counts.size(); i++) {
            if (counts.get(i) <= 0) continue;
            if (!available(world, ids.get(i))) {
                msg(player, TextFormatting.RED + displayName(ids.get(i))
                        + " isn't available at your current rival level — skipped.");
                continue;
            }
            okIds.add(ids.get(i));
            okCounts.add(counts.get(i));
            cost += market.price(world, ids.get(i)) * counts.get(i);
        }
        if (okIds.isEmpty()) return;
        WarWorldData data = WarWorldData.get(world);
        WarWorldData.FactionStats stats = data.getStats(player.getUniqueID().toString());
        // ALWAYS charge — creative mode does not print free Command Bucks (the "buying doesn't
        // reduce CB" bug was a creative-mode bypass here).
        if (stats.commandPoints < cost) {
            msg(player, TextFormatting.RED + "Not enough Command Bucks (need " + (int) Math.ceil(cost)
                    + ", have " + stats.commandPoints + ").");
            return;
        }
        stats.commandPoints -= (int) Math.ceil(cost);
        data.markDirty();
        int total = 0;
        for (int i = 0; i < okIds.size(); i++) { market.recordBuy(okIds.get(i), okCounts.get(i)); total += okCounts.get(i); }
        long now = world.getTotalWorldTime();
        SHIPMENTS.add(new Shipment(world, true, okIds, okCounts, cost, depot,
                now, now + TradePriceConfig.data.shipmentSeconds * 20L, player.getUniqueID()));
        msg(player, TextFormatting.GOLD + "Order confirmed: " + total + " item(s) for "
                + (int) Math.ceil(cost) + " CB. A merchant cart is on its way (~"
                + TradePriceConfig.data.shipmentSeconds + "s) — watch the Civilian map.");
    }

    /** SELLING is a STANDING ORDER now: couriers stock the Trade Depot with the listed items and
     *  a trader departs ONCE PER DAY with whatever is actually stocked (paid on departure). */
    public static void sell(WorldServer world, EntityPlayerMP player, String id, int count, BlockPos depot) {
        if (count <= 0) return;
        TradeMarketData market = TradeMarketData.get(world);
        market.saleOrders.merge(id, count, Integer::sum);
        market.markDirty();
        msg(player, TextFormatting.GOLD + "Sale order placed: " + market.saleOrders.get(id) + "x "
                + displayName(id) + ". Couriers will stock the Trade Depot; the trader departs once a day.");
    }

    // ---- tick ----

    @SubscribeEvent
    public static void onWorldTick(TickEvent.WorldTickEvent e) {
        if (e.phase != TickEvent.Phase.END || e.side.isClient() || !(e.world instanceof WorldServer)) return;
        WorldServer world = (WorldServer) e.world;
        long now = world.getTotalWorldTime();
        if (now % 40 == 0) TradeMarketData.get(world).decay(world);
        if (now % 100 == 0) dailyTraderDeparture(world);
        // Expire the set-dressing carts.
        for (Object[] d : new ArrayList<>(DECOR_CARTS)) {
            net.minecraft.entity.Entity cart = (net.minecraft.entity.Entity) d[0];
            if (cart.isDead || now >= (Long) d[1]) { cart.setDead(); DECOR_CARTS.remove(d); }
        }
        if (SHIPMENTS.isEmpty()) return;
        for (Shipment s : new ArrayList<>(SHIPMENTS)) {
            if (s.dim != world.provider.getDimension() || now < s.completeTick) continue;
            SHIPMENTS.remove(s);
            complete(world, s);
        }
    }

    /**
     * ONCE PER DAY the trader departs: whatever sale-ordered stock the couriers managed to pile
     * into the TRADE DEPOT is loaded up and driven off; payout lands when the cart leaves (a
     * shipment). The daily EXPORT CURVE applies: full price up to the cap (64 base, raised per
     * rival level by the config multiplier), diminishing beyond it. The counter resets each day.
     */
    private static void dailyTraderDeparture(WorldServer world) {
        TradeMarketData market = TradeMarketData.get(world);
        long day = world.getTotalWorldTime() / 24000L;
        if (day <= market.lastTradeDay) return;
        market.lastTradeDay = day;
        market.exportedToday = 0; // fresh cap every morning
        market.markDirty();
        if (market.saleOrders.isEmpty()) return;

        // Pull sale-ordered stock from TRADE DEPOT depots only (that's what couriers stocked).
        List<String> ids = new ArrayList<>();
        List<Integer> counts = new ArrayList<>();
        double payout = 0;
        UUID payee = null;
        BlockPos tradeDepot = null;
        for (CivilMarker m : CivilPlanData.get(world).markers) {
            if (m.isRoad() || m.kind != CivilMarker.TRADE_DEPOT || !m.hasDepot()) continue;
            TileEntityDistrictMarker te = DistrictRegistry.depotOf(world, m);
            if (te == null) continue;
            tradeDepot = m.depotPos;
            for (java.util.Map.Entry<String, Integer> order : new java.util.HashMap<>(market.saleOrders).entrySet()) {
                if (order.getValue() <= 0) { market.saleOrders.remove(order.getKey()); continue; }
                ItemStack want = TradePriceConfig.resolve(order.getKey());
                if (want.isEmpty()) continue;
                int pulled = 0;
                for (int slot = 0; slot < te.depot.getSlots() && pulled < order.getValue(); slot++) {
                    ItemStack s = te.depot.getStackInSlot(slot);
                    if (s.isEmpty() || s.getItem() != want.getItem() || s.getMetadata() != want.getMetadata()) continue;
                    pulled += te.depot.extractItem(slot, order.getValue() - pulled, false).getCount();
                }
                if (pulled <= 0) continue;
                double unit = market.price(world, order.getKey());
                double factor = market.exportPayoutFactor(world, market.exportedToday + pulled);
                payout += unit * pulled * factor;
                market.exportedToday += pulled;
                market.recordSell(order.getKey(), pulled);
                int rem = order.getValue() - pulled;
                if (rem <= 0) market.saleOrders.remove(order.getKey());
                else market.saleOrders.put(order.getKey(), rem);
                ids.add(order.getKey());
                counts.add(pulled);
            }
        }
        if (ids.isEmpty() || tradeDepot == null) return;
        market.markDirty();
        if (!world.playerEntities.isEmpty()) payee = world.playerEntities.get(0).getUniqueID();
        if (payee == null) return;
        long now = world.getTotalWorldTime();
        SHIPMENTS.add(new Shipment(world, false, ids, counts, payout, tradeDepot,
                now, now + TradePriceConfig.data.shipmentSeconds * 20L, payee));
        spawnDecorCart(world, tradeDepot);
        EntityPlayer p = world.getPlayerEntityByUUID(payee);
        int total = 0; for (int c : counts) total += c;
        if (p != null) msg(p, TextFormatting.GOLD + "The daily trader departed with " + total
                + " item(s) — payment on the way out (cap today: " + TradeMarketData.dailyExportCap(world) + ").");
    }

    private static void complete(WorldServer world, Shipment s) {
        EntityPlayer p = world.getPlayerEntityByUUID(s.player);
        if (s.importing) {
            // "Goods put into your warehouse": warehouses first, trade depot as spill, then ground.
            for (int i = 0; i < s.ids.size(); i++) {
                ItemStack stack = TradePriceConfig.resolve(s.ids.get(i));
                if (stack.isEmpty()) continue;
                int remaining = s.counts.get(i);
                while (remaining > 0) {
                    ItemStack unit = stack.copy();
                    unit.setCount(Math.min(stack.getMaxStackSize(), remaining));
                    remaining -= unit.getCount();
                    ItemStack left = insertIntoWarehouses(world, unit);
                    if (!left.isEmpty() && p != null) {
                        net.minecraft.inventory.InventoryHelper.spawnItemStack(world, p.posX, p.posY, p.posZ, left);
                    }
                }
            }
            spawnDecorCart(world, s.depot);
            if (p != null) msg(p, TextFormatting.GREEN + "Merchant cart arrived: " + s.totalCount()
                    + " item(s) unloaded into your warehouse.");
        } else {
            WarWorldData data = WarWorldData.get(world);
            data.getStats(s.player.toString()).commandPoints += (int) Math.floor(s.bucks);
            data.markDirty();
            if (p != null) msg(p, TextFormatting.GREEN + "Export sold: +" + (int) Math.floor(s.bucks)
                    + " CB for " + s.totalCount() + " item(s).");
        }
    }

    /** The physical chest cart "dragged in" at the depot — pure set dressing, despawns after ~60s. */
    private static void spawnDecorCart(WorldServer world, BlockPos depot) {
        try {
            if (depot == null || !world.isBlockLoaded(depot, false)) return;
            net.minecraft.entity.Entity cart = studio.ERM.war.BattleManagers.core.ChestCartHelper.createChestCart(world);
            if (cart == null) return;
            BlockPos stand = world.getTopSolidOrLiquidBlock(depot.add(3, 0, 2));
            cart.setPosition(stand.getX() + 0.5, stand.getY(), stand.getZ() + 0.5);
            studio.ERM.war.BattleManagers.core.ChestCartHelper.makeInvulnerable(cart);
            world.spawnEntity(cart);
            DECOR_CARTS.add(new Object[]{cart, world.getTotalWorldTime() + 1200L});
        } catch (Throwable ignored) {} // AW2 absent -> no cart, everything else still works
    }

    /** Live shipments for the Civilian map: src->dst lines with REAL progress (0-100). */
    public static List<studio.ERM.war.map.net.S2CCivilPlanSync.JobLine> shipmentLines(WorldServer world) {
        List<studio.ERM.war.map.net.S2CCivilPlanSync.JobLine> out = new ArrayList<>();
        long now = world.getTotalWorldTime();
        for (Shipment s : SHIPMENTS) {
            if (s.dim != world.provider.getDimension()) continue;
            studio.ERM.war.map.net.S2CCivilPlanSync.JobLine l = new studio.ERM.war.map.net.S2CCivilPlanSync.JobLine();
            if (s.importing) { l.sx = s.ox; l.sz = s.oz; l.dx = s.depot.getX(); l.dz = s.depot.getZ(); }
            else { l.sx = s.depot.getX(); l.sz = s.depot.getZ(); l.dx = s.ox; l.dz = s.oz; }
            l.state = 3; // SHIPMENT (map draws these teal with real progress)
            long span = Math.max(1, s.completeTick - s.startTick);
            l.progress = (byte) Math.max(0, Math.min(100, (now - s.startTick) * 100 / span));
            l.label = (s.importing ? "Merchant: " : "Trader: ") + s.totalCount() + " goods";
            out.add(l);
        }
        return out;
    }

    // ---- connected storage: trade depot + all warehouse depots ----

    public static List<TileEntityDistrictMarker> connectedStorage(WorldServer world) {
        List<TileEntityDistrictMarker> out = new ArrayList<>();
        for (CivilMarker m : CivilPlanData.get(world).markers) {
            if (m.isRoad() || !m.hasDepot()) continue;
            if (m.kind == CivilMarker.WAREHOUSE || m.kind == CivilMarker.TRADE_DEPOT) {
                TileEntityDistrictMarker d = DistrictRegistry.depotOf(world, m);
                if (d != null) out.add(d);
            }
        }
        return out;
    }

    /** EVERY handler the settlement's trade can touch: warehouse + trade depots PLUS every
     *  container block inside Warehouse polygons ("the warehouse reads all inventories"). */
    private static List<net.minecraftforge.items.IItemHandler> allStorageHandlers(WorldServer world) {
        List<net.minecraftforge.items.IItemHandler> out = new ArrayList<>();
        for (CivilMarker m : CivilPlanData.get(world).markers) {
            if (m.isRoad() || !m.hasDepot()) continue;
            if (m.kind != CivilMarker.WAREHOUSE && m.kind != CivilMarker.TRADE_DEPOT) continue;
            TileEntityDistrictMarker d = DistrictRegistry.depotOf(world, m);
            if (d != null) out.add(d.depot);
            if (m.kind == CivilMarker.WAREHOUSE) {
                out.addAll(DistrictRegistry.districtInventories(world, m));
            }
        }
        return out;
    }

    /** Total count of an item across connected storage (for the Sell menu). */
    public static int storedCount(WorldServer world, String id) {
        ItemStack want = TradePriceConfig.resolve(id);
        if (want.isEmpty()) return 0;
        int total = 0;
        for (net.minecraftforge.items.IItemHandler inv : allStorageHandlers(world)) {
            for (int slot = 0; slot < inv.getSlots(); slot++) {
                ItemStack s = inv.getStackInSlot(slot);
                if (!s.isEmpty() && s.getItem() == want.getItem() && s.getMetadata() == want.getMetadata()) {
                    total += s.getCount();
                }
            }
        }
        return total;
    }

    private static int pullFromStorage(WorldServer world, String id, int count) {
        ItemStack want = TradePriceConfig.resolve(id);
        if (want.isEmpty()) return 0;
        int pulled = 0;
        for (net.minecraftforge.items.IItemHandler inv : allStorageHandlers(world)) {
            for (int slot = 0; slot < inv.getSlots() && pulled < count; slot++) {
                ItemStack s = inv.getStackInSlot(slot);
                if (s.isEmpty() || s.getItem() != want.getItem() || s.getMetadata() != want.getMetadata()) continue;
                pulled += inv.extractItem(slot, count - pulled, false).getCount();
            }
        }
        return pulled;
    }

    private static ItemStack insertIntoWarehouses(WorldServer world, ItemStack stack) {
        for (net.minecraftforge.items.IItemHandler inv : allStorageHandlers(world)) {
            stack = ItemHandlerHelper.insertItemStacked(inv, stack, false);
            if (stack.isEmpty()) break;
        }
        return stack;
    }

    private static TileEntityDistrictMarker depotAt(WorldServer world, BlockPos pos) {
        if (pos == null) return null;
        net.minecraft.tileentity.TileEntity te = world.getTileEntity(pos);
        return te instanceof TileEntityDistrictMarker ? (TileEntityDistrictMarker) te : null;
    }

    private static String displayName(String id) {
        ItemStack s = TradePriceConfig.resolve(id);
        return s.isEmpty() ? id : s.getDisplayName();
    }

    private static void msg(EntityPlayer p, String s) {
        if (p != null) p.sendMessage(new TextComponentString(s));
    }

    /** Clear in-flight shipments on world unload so they don't leak across saves. */
    @SubscribeEvent
    public static void onWorldUnload(WorldEvent.Unload e) {
        if (e.getWorld() instanceof WorldServer) {
            int dim = ((WorldServer) e.getWorld()).provider.getDimension();
            SHIPMENTS.removeIf(s -> s.dim == dim);
        }
    }
}
