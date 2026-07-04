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

    private static final class Shipment {
        final int dim; final boolean importing; final String id; final int count;
        final double bucks; final BlockPos depot; final long completeTick; final UUID player;
        Shipment(int dim, boolean importing, String id, int count, double bucks, BlockPos depot,
                 long completeTick, UUID player) {
            this.dim = dim; this.importing = importing; this.id = id; this.count = count;
            this.bucks = bucks; this.depot = depot; this.completeTick = completeTick; this.player = player;
        }
    }

    // ---- API called from the packet handler ----

    /** True if the current rival level unlocks buying this item. */
    public static boolean available(WorldServer world, String id) {
        return DistrictRegistry.rivalLevel(world) >= TradePriceConfig.minLevel(id);
    }

    public static void buy(WorldServer world, EntityPlayerMP player, String id, int count, BlockPos depot) {
        if (count <= 0) return;
        if (!available(world, id)) {
            msg(player, TextFormatting.RED + "That item isn't available at your current rival level.");
            return;
        }
        TradeMarketData market = TradeMarketData.get(world);
        double unit = market.price(world, id);
        double cost = unit * count;
        WarWorldData data = WarWorldData.get(world);
        WarWorldData.FactionStats stats = data.getStats(player.getUniqueID().toString());
        if (!player.isCreative() && stats.commandPoints < cost) {
            msg(player, TextFormatting.RED + "Not enough Command Bucks (need " + (int) Math.ceil(cost)
                    + ", have " + stats.commandPoints + ").");
            return;
        }
        if (!player.isCreative()) { stats.commandPoints -= (int) Math.ceil(cost); data.markDirty(); }
        market.recordBuy(id, count);
        schedule(world, true, id, count, cost, depot, player.getUniqueID());
        msg(player, TextFormatting.GOLD + "Import ordered: " + count + "x " + displayName(id)
                + " for " + (int) Math.ceil(cost) + " CB. Merchant arriving in ~"
                + TradePriceConfig.data.shipmentSeconds + "s.");
    }

    public static void sell(WorldServer world, EntityPlayerMP player, String id, int count, BlockPos depot) {
        if (count <= 0) return;
        int pulled = pullFromStorage(world, id, count);
        if (pulled <= 0) {
            msg(player, TextFormatting.RED + "No " + displayName(id) + " in connected warehouses.");
            return;
        }
        TradeMarketData market = TradeMarketData.get(world);
        double unit = market.price(world, id);
        double payout = unit * pulled;
        market.recordSell(id, pulled);
        schedule(world, false, id, pulled, payout, depot, player.getUniqueID());
        msg(player, TextFormatting.GOLD + "Export dispatched: " + pulled + "x " + displayName(id)
                + ". " + (int) Math.floor(payout) + " CB paid when the shipment leaves (~"
                + TradePriceConfig.data.shipmentSeconds + "s).");
    }

    private static void schedule(WorldServer world, boolean importing, String id, int count,
                                 double bucks, BlockPos depot, UUID player) {
        long done = world.getTotalWorldTime() + TradePriceConfig.data.shipmentSeconds * 20L;
        SHIPMENTS.add(new Shipment(world.provider.getDimension(), importing, id, count, bucks, depot, done, player));
    }

    // ---- tick ----

    @SubscribeEvent
    public static void onWorldTick(TickEvent.WorldTickEvent e) {
        if (e.phase != TickEvent.Phase.END || e.side.isClient() || !(e.world instanceof WorldServer)) return;
        WorldServer world = (WorldServer) e.world;
        if (world.getTotalWorldTime() % 40 == 0) TradeMarketData.get(world).decay(world);
        if (SHIPMENTS.isEmpty()) return;
        long now = world.getTotalWorldTime();
        for (Shipment s : new ArrayList<>(SHIPMENTS)) {
            if (s.dim != world.provider.getDimension() || now < s.completeTick) continue;
            SHIPMENTS.remove(s);
            complete(world, s);
        }
    }

    private static void complete(WorldServer world, Shipment s) {
        EntityPlayer p = world.getPlayerEntityByUUID(s.player);
        if (s.importing) {
            ItemStack stack = TradePriceConfig.resolve(s.id);
            if (stack.isEmpty()) return;
            int remaining = s.count;
            BlockPos target = s.depot;
            TileEntityDistrictMarker depot = depotAt(world, target);
            // Deliver to the trade depot, spilling overflow to any warehouse, then the ground.
            while (remaining > 0) {
                ItemStack unit = stack.copy();
                unit.setCount(Math.min(stack.getMaxStackSize(), remaining));
                remaining -= unit.getCount();
                ItemStack left = depot != null ? ItemHandlerHelper.insertItemStacked(depot.depot, unit, false) : unit;
                if (!left.isEmpty()) left = insertIntoWarehouses(world, left);
                if (!left.isEmpty() && p != null) {
                    net.minecraft.inventory.InventoryHelper.spawnItemStack(world, p.posX, p.posY, p.posZ, left);
                }
            }
            if (p != null) msg(p, TextFormatting.GREEN + "Import delivered: " + s.count + "x " + displayName(s.id) + ".");
        } else {
            WarWorldData data = WarWorldData.get(world);
            data.getStats(s.player.toString()).commandPoints += (int) Math.floor(s.bucks);
            data.markDirty();
            if (p != null) msg(p, TextFormatting.GREEN + "Export sold: +" + (int) Math.floor(s.bucks)
                    + " CB for " + s.count + "x " + displayName(s.id) + ".");
        }
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

    /** Total count of an item across connected storage (for the Sell menu). */
    public static int storedCount(WorldServer world, String id) {
        ItemStack want = TradePriceConfig.resolve(id);
        if (want.isEmpty()) return 0;
        int total = 0;
        for (TileEntityDistrictMarker d : connectedStorage(world)) {
            for (int slot = 0; slot < d.depot.getSlots(); slot++) {
                ItemStack s = d.depot.getStackInSlot(slot);
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
        for (TileEntityDistrictMarker d : connectedStorage(world)) {
            for (int slot = 0; slot < d.depot.getSlots() && pulled < count; slot++) {
                ItemStack s = d.depot.getStackInSlot(slot);
                if (s.isEmpty() || s.getItem() != want.getItem() || s.getMetadata() != want.getMetadata()) continue;
                int take = Math.min(s.getCount(), count - pulled);
                d.depot.extractItem(slot, take, false);
                pulled += take;
            }
        }
        return pulled;
    }

    private static ItemStack insertIntoWarehouses(WorldServer world, ItemStack stack) {
        for (TileEntityDistrictMarker d : connectedStorage(world)) {
            stack = ItemHandlerHelper.insertItemStacked(d.depot, stack, false);
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
