package studio.ERM.strategic.civil.logistics;

import net.minecraft.entity.EntityCreature;
import net.minecraft.item.ItemStack;
import net.minecraft.util.math.AxisAlignedBB;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.WorldServer;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import net.minecraftforge.fml.common.gameevent.TickEvent;
import net.minecraftforge.items.ItemHandlerHelper;
import net.minecraftforge.items.ItemStackHandler;
import studio.ERM.EpochRunnerMod;
import studio.ERM.strategic.civil.CivilMarker;
import studio.ERM.strategic.civil.CivilPlanData;
import studio.ERM.strategic.civil.DistrictRegistry;
import studio.ERM.strategic.civil.DistrictWorkExecutor;
import studio.ERM.strategic.civil.RoadNetworkManager;
import studio.ERM.war.districts.TileEntityDistrictMarker;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * THE GLOBAL LOGISTICS MANAGER — Phase 3. The player never creates transport routes; every depot
 * describes what it needs (IN templates: keep stocked) and what it produces (OUT templates:
 * export when available), and this settlement-wide pass continuously satisfies those requests:
 *
 *   detect shortages -> detect surpluses -> match suppliers to consumers -> create jobs -> hire.
 *
 * No depot owns couriers. Free AW2 workers are hired per job (same hijack doctrine as districts
 * and road crews) and released after delivery. THE WAREHOUSE IS THE HUB: when no depot requests
 * an exported item it flows to the Warehouse by default, and when no surplus supplier exists a
 * demanded item is pulled from the Warehouse by default.
 *
 * Couriers physically walk pickup -> carry -> deposit while loaded (chest in hand = the job reads
 * at a glance, roads preferred via RoadRouter); when their chunks unload the job keeps moving as
 * a strategic-layer timer and completes against the depots directly. Facade over perfection.
 */
public final class LogisticsManager {

    private static final int PASS_INTERVAL = 100;      // 5s planning cadence
    private static final int IN_TARGET = 16;           // "kept stocked" = this many per template
    private static final int CARRY_CAP = 32;           // per trip
    private static final int MAX_JOBS = 12;
    private static final int MAX_NEW_PER_PASS = 4;
    private static final int MAX_COURIERS = 4;
    private static final double VIRTUAL_SPEED = 0.11;  // blocks/tick while unloaded
    public static boolean VERBOSE = false;

    private LogisticsManager() {}

    /** courier uuid -> job uid. Transient (restart re-hires). */
    private static final Map<UUID, Integer> COURIERS = new ConcurrentHashMap<>();
    private static int tickCounter = 0;

    public static boolean isCourier(EntityCreature c) {
        return COURIERS.containsKey(c.getUniqueID());
    }

    public static CourierJob jobFor(WorldServer world, EntityCreature npc) {
        Integer uid = COURIERS.get(npc.getUniqueID());
        return uid == null ? null : LogisticsData.get(world).byUid(uid);
    }

    public static int activeJobCount(WorldServer world) {
        int n = 0;
        for (CourierJob j : LogisticsData.get(world).jobs) if (!j.done) n++;
        return n;
    }

    public static void resetTransients() {
        COURIERS.clear();
    }

    // ==================================================================
    // THE PASS
    // ==================================================================

    @SubscribeEvent
    public static void onWorldTick(TickEvent.WorldTickEvent e) {
        if (e.phase != TickEvent.Phase.END || e.side.isClient() || !(e.world instanceof WorldServer)) return;
        if (++tickCounter % PASS_INTERVAL != 0) return;
        WorldServer world = (WorldServer) e.world;

        LogisticsData data = LogisticsData.get(world);
        CivilPlanData plan = CivilPlanData.get(world);

        // ---- collect the loaded, bound depots ----
        List<CivilMarker> depots = new ArrayList<>();
        for (CivilMarker m : plan.markers) {
            if (!m.isRoad() && m.hasDepot() && DistrictRegistry.depotOf(world, m) != null) depots.add(m);
        }

        // ---- job upkeep: finish/kill stale jobs, advance unloaded ones ----
        boolean dirty = false;
        for (CourierJob job : new ArrayList<>(data.jobs)) {
            if (job.done) { data.jobs.remove(job); dirty = true; continue; }
            // A depot half of the pair vanished (broken block / deleted polygon): drop the job.
            if (depotHandlerAt(world, job.src) == null && world.isBlockLoaded(job.src, false)
                    || depotHandlerAt(world, job.dst) == null && world.isBlockLoaded(job.dst, false)) {
                releaseCourier(world, job);
                data.jobs.remove(job);
                dirty = true;
                continue;
            }
            if (job.assigned()) {
                net.minecraft.entity.Entity ent = world.getEntityFromUuid(job.courier);
                if (ent == null) {
                    // UNLOADED: the job continues on the strategic layer.
                    dirty |= advanceVirtually(world, job);
                } else if (ent.isDead) {
                    // Courier died: cargo is lost with him; the shortage re-detects next pass.
                    COURIERS.remove(job.courier);
                    job.courier = null;
                    job.carried = ItemStack.EMPTY;
                    job.state = CourierJob.STATE_PENDING;
                    job.virtualProgress = 0;
                    dirty = true;
                }
            }
        }
        // Drop courier links whose job evaporated.
        COURIERS.entrySet().removeIf(en -> {
            CourierJob j = data.byUid(en.getValue());
            return j == null || j.done;
        });

        // ---- NIGHT: couriers go home; jobs wait for morning ----
        if (!world.isDaytime()) {
            for (CourierJob job : data.jobs) {
                if (job.assigned() && world.getEntityFromUuid(job.courier) != null) {
                    releaseCourier(world, job);
                    dirty = true;
                }
            }
            if (dirty) data.markDirty();
            return;
        }

        // ---- detect shortages + surpluses and create jobs ----
        if (!depots.isEmpty()) {
            int created = 0;
            // SHORTAGES first (they outrank exports for the same stock).
            for (CivilMarker m : depots) {
                if (created >= MAX_NEW_PER_PASS || data.jobs.size() >= MAX_JOBS) break;
                TileEntityDistrictMarker te = DistrictRegistry.depotOf(world, m);
                // Player IN templates PLUS auto-tool demands (couriers bring tools without any setup).
                List<ItemStack> demands = new ArrayList<>(distinctTemplates(te.inTemplates));
                for (ItemStack auto : autoToolDemands(m, te)) {
                    if (demands.stream().noneMatch(d -> d.getItem() == auto.getItem()
                            && d.getMetadata() == auto.getMetadata())) demands.add(auto);
                }
                // SALE ORDERS: the Trade Depot auto-demands whatever the player listed for sale,
                // so couriers stock it ahead of the once-daily trader departure.
                if (m.kind == CivilMarker.TRADE_DEPOT) {
                    studio.ERM.strategic.civil.trade.TradeMarketData market =
                            studio.ERM.strategic.civil.trade.TradeMarketData.get(world);
                    for (java.util.Map.Entry<String, Integer> so : market.saleOrders.entrySet()) {
                        if (so.getValue() <= 0) continue;
                        ItemStack tpl = studio.ERM.war.config.TradePriceConfig.resolve(so.getKey());
                        if (tpl.isEmpty()) continue;
                        if (demands.stream().noneMatch(d -> d.getItem() == tpl.getItem()
                                && d.getMetadata() == tpl.getMetadata())) demands.add(tpl);
                    }
                }
                for (ItemStack tpl : demands) {
                    if (created >= MAX_NEW_PER_PASS || data.jobs.size() >= MAX_JOBS) break;
                    int have = countItem(te.depot, tpl);
                    if (have >= IN_TARGET) continue;
                    if (hasActiveJob(data, null, m.depotPos, tpl)) continue;

                    // Source: a depot EXPORTING this item, else the Warehouse (default supplier).
                    CivilMarker src = findSurplusSource(world, depots, m, tpl);
                    if (src == null) src = findWarehouseWith(world, depots, m, tpl);
                    if (src == null) continue;

                    CourierJob job = new CourierJob();
                    job.uid = data.takeUid();
                    job.type = CourierJob.TYPE_RESTOCK;
                    job.src = src.depotPos;
                    job.dst = m.depotPos;
                    job.item = ItemHandlerHelper.copyStackWithSize(tpl, 1);
                    job.count = Math.min(CARRY_CAP, IN_TARGET - have);
                    job.priority = demandPriority(m.kind);
                    data.jobs.add(job);
                    created++;
                    dirty = true;
                    if (VERBOSE) EpochRunnerMod.logger.info("[Logistics] RESTOCK "
                            + tpl.getDisplayName() + " x" + job.count + " "
                            + CivilMarker.nameOf(src.kind) + " -> " + CivilMarker.nameOf(m.kind));
                }
            }
            // EXPORTS: unrequested OUT surpluses drain to the Warehouse hub.
            for (CivilMarker m : depots) {
                if (created >= MAX_NEW_PER_PASS || data.jobs.size() >= MAX_JOBS) break;
                if (m.kind == CivilMarker.WAREHOUSE) continue; // the hub doesn't export to itself
                TileEntityDistrictMarker te = DistrictRegistry.depotOf(world, m);
                for (ItemStack tpl : distinctTemplates(te.outTemplates)) {
                    if (created >= MAX_NEW_PER_PASS || data.jobs.size() >= MAX_JOBS) break;
                    if (countItem(te.depot, tpl) <= 0) continue;
                    if (hasActiveJob(data, m.depotPos, null, tpl)) continue;
                    CivilMarker wh = nearestWarehouse(depots, m);
                    if (wh == null) continue;

                    CourierJob job = new CourierJob();
                    job.uid = data.takeUid();
                    job.type = CourierJob.TYPE_EXPORT;
                    job.src = m.depotPos;
                    job.dst = wh.depotPos;
                    job.item = ItemHandlerHelper.copyStackWithSize(tpl, 1);
                    job.count = CARRY_CAP;
                    job.priority = 30;
                    data.jobs.add(job);
                    created++;
                    dirty = true;
                    if (VERBOSE) EpochRunnerMod.logger.info("[Logistics] EXPORT "
                            + tpl.getDisplayName() + " " + CivilMarker.nameOf(m.kind) + " -> Warehouse");
                }
            }
        }

        // ---- hire couriers for pending jobs, highest priority first ----
        List<CourierJob> pending = new ArrayList<>();
        for (CourierJob j : data.jobs) if (!j.done && !j.assigned()) pending.add(j);
        pending.sort((a, b) -> b.priority - a.priority);
        for (CourierJob job : pending) {
            if (COURIERS.size() >= MAX_COURIERS) break;
            EntityCreature courier = findFreeWorkerNear(world, job.src);
            if (courier == null) continue;
            COURIERS.put(courier.getUniqueID(), job.uid);
            job.courier = courier.getUniqueID();
            job.state = job.carried.isEmpty() ? CourierJob.STATE_TO_SOURCE : CourierJob.STATE_TO_DEST;
            job.virtualProgress = 0;
            ensureCourierTask(courier);
            dirty = true;
            EpochRunnerMod.logger.info("[Logistics] hired " + courier.getName() + " for "
                    + CourierJob.TYPE_NAMES[job.type] + " of " + job.item.getDisplayName()
                    + " (job #" + job.uid + ")");
        }

        if (dirty) data.markDirty();
    }

    // ==================================================================
    // ARRIVALS — called by EntityAICourierWork
    // ==================================================================

    /** Courier reached the SOURCE depot: load up. */
    public static void arrivedAtSource(WorldServer world, EntityCreature courier, CourierJob job) {
        ItemStackHandler src = depotHandlerAt(world, job.src);
        int taken = 0;
        if (src != null) {
            for (int i = 0; i < src.getSlots() && taken < job.count; i++) {
                ItemStack s = src.getStackInSlot(i);
                if (!s.isEmpty() && sameItem(s, job.item)) {
                    ItemStack got = src.extractItem(i, job.count - taken, false);
                    taken += got.getCount();
                }
            }
        }
        if (taken <= 0) {
            job.done = true; // nothing to move any more — the pass will re-plan
            releaseCourier(world, job);
        } else {
            job.carried = ItemHandlerHelper.copyStackWithSize(job.item, taken);
            job.state = CourierJob.STATE_TO_DEST;
            job.virtualProgress = 0;
            // The cargo badge: a courier mid-delivery carries a chest.
            try {
                courier.setHeldItem(net.minecraft.util.EnumHand.MAIN_HAND,
                        new ItemStack(net.minecraft.init.Blocks.CHEST));
                courier.setDropChance(net.minecraft.inventory.EntityEquipmentSlot.MAINHAND, 0f);
            } catch (Throwable ignored) {}
        }
        LogisticsData.get(world).markDirty();
    }

    /** Courier reached the DESTINATION depot: unload; overflow spills visibly at the depot. */
    public static void arrivedAtDest(WorldServer world, EntityCreature courier, CourierJob job) {
        deliver(world, job);
        releaseCourier(world, job);
        LogisticsData.get(world).markDirty();
    }

    private static void deliver(WorldServer world, CourierJob job) {
        if (!job.carried.isEmpty()) {
            ItemStackHandler dst = depotHandlerAt(world, job.dst);
            ItemStack left = dst != null
                    ? ItemHandlerHelper.insertItemStacked(dst, job.carried, false) : job.carried;
            if (!left.isEmpty()) {
                net.minecraft.inventory.InventoryHelper.spawnItemStack(world,
                        job.dst.getX() + 0.5, job.dst.getY() + 1, job.dst.getZ() + 0.5, left);
            }
            job.carried = ItemStack.EMPTY;
        }
        job.done = true;
    }

    /** Unloaded couriers keep walking on the strategic layer; legs complete against loaded depots. */
    private static boolean advanceVirtually(WorldServer world, CourierJob job) {
        if (job.state == CourierJob.STATE_PENDING) return false;
        job.virtualProgress += VIRTUAL_SPEED * PASS_INTERVAL;
        double leg = job.state == CourierJob.STATE_TO_DEST
                ? Math.max(24, Math.sqrt(job.src.distanceSq(job.dst)))
                : 96; // approach leg: courier position unknown while unloaded
        if (job.virtualProgress < leg) return true;

        if (job.state == CourierJob.STATE_TO_SOURCE) {
            ItemStackHandler src = depotHandlerAt(world, job.src);
            if (src == null) return true; // wait until the depot chunk loads
            int taken = 0;
            for (int i = 0; i < src.getSlots() && taken < job.count; i++) {
                ItemStack s = src.getStackInSlot(i);
                if (!s.isEmpty() && sameItem(s, job.item)) {
                    taken += src.extractItem(i, job.count - taken, false).getCount();
                }
            }
            if (taken <= 0) { job.done = true; return true; }
            job.carried = ItemHandlerHelper.copyStackWithSize(job.item, taken);
            job.state = CourierJob.STATE_TO_DEST;
            job.virtualProgress = 0;
        } else {
            if (depotHandlerAt(world, job.dst) == null) return true; // wait for the chunk
            deliver(world, job);
        }
        return true;
    }

    private static void releaseCourier(WorldServer world, CourierJob job) {
        if (job.courier == null) return;
        COURIERS.remove(job.courier);
        net.minecraft.entity.Entity ent = world.getEntityFromUuid(job.courier);
        if (ent instanceof EntityCreature) {
            try {
                ((EntityCreature) ent).setHeldItem(net.minecraft.util.EnumHand.MAIN_HAND, ItemStack.EMPTY);
            } catch (Throwable ignored) {}
        }
        job.courier = null;
        if (!job.done && job.carried.isEmpty()) job.state = CourierJob.STATE_PENDING;
    }

    // ==================================================================
    // Matching helpers
    // ==================================================================

    /**
     * Tools a district CONSUMES that couriers should keep stocked automatically, even with no IN
     * template set: quarries need pickaxes, farms seeds, tree farms saplings; an armory needs every
     * item its loadouts issue (so soldiers are actually supplied). Matched against stock like any
     * other IN request. The player can still add explicit IN templates on top.
     */
    private static List<ItemStack> autoToolDemands(CivilMarker m, TileEntityDistrictMarker te) {
        List<ItemStack> out = new ArrayList<>();
        switch (m.kind) {
            case CivilMarker.QUARRY:
                out.add(new ItemStack(net.minecraft.init.Items.IRON_PICKAXE));
                break;
            case CivilMarker.FARM:
                out.add(new ItemStack(net.minecraft.init.Items.WHEAT_SEEDS));
                break;
            case CivilMarker.LUMBER:
                out.add(new ItemStack(net.minecraft.item.Item.getItemFromBlock(net.minecraft.init.Blocks.SAPLING)));
                break;
            case CivilMarker.ARMORY:
                for (int i = 0; i < te.loadouts.getSlots(); i++) {
                    ItemStack l = te.loadouts.getStackInSlot(i);
                    if (!l.isEmpty()) out.add(ItemHandlerHelper.copyStackWithSize(l, 1));
                }
                break;
            default: break;
        }
        return out;
    }

    private static int demandPriority(int kind) {
        switch (kind) {
            case CivilMarker.HOSPITAL: return 90;  // "Priority Resupply"
            case CivilMarker.KITCHEN:  return 80;
            case CivilMarker.ARMORY:
            case CivilMarker.BARRACKS: return 70;
            default:                   return 50;
        }
    }

    /** A depot whose OUT templates export this item and whose depot actually stocks it. Nearest. */
    private static CivilMarker findSurplusSource(WorldServer world, List<CivilMarker> depots,
                                                 CivilMarker demander, ItemStack tpl) {
        CivilMarker best = null;
        double bd = Double.MAX_VALUE;
        for (CivilMarker m : depots) {
            if (m == demander) continue;
            TileEntityDistrictMarker te = DistrictRegistry.depotOf(world, m);
            if (te == null || !templatesContain(te.outTemplates, tpl) || countItem(te.depot, tpl) <= 0) continue;
            double d = m.depotPos.distanceSq(demander.depotPos);
            if (d < bd) { bd = d; best = m; }
        }
        return best;
    }

    /** The default supplier: any Warehouse depot that stocks the item (even without OUT template). */
    private static CivilMarker findWarehouseWith(WorldServer world, List<CivilMarker> depots,
                                                 CivilMarker demander, ItemStack tpl) {
        CivilMarker best = null;
        double bd = Double.MAX_VALUE;
        for (CivilMarker m : depots) {
            if (m == demander || m.kind != CivilMarker.WAREHOUSE) continue;
            TileEntityDistrictMarker te = DistrictRegistry.depotOf(world, m);
            if (te == null || countItem(te.depot, tpl) <= 0) continue;
            double d = m.depotPos.distanceSq(demander.depotPos);
            if (d < bd) { bd = d; best = m; }
        }
        return best;
    }

    private static CivilMarker nearestWarehouse(List<CivilMarker> depots, CivilMarker from) {
        CivilMarker best = null;
        double bd = Double.MAX_VALUE;
        for (CivilMarker m : depots) {
            if (m == from || m.kind != CivilMarker.WAREHOUSE) continue;
            double d = m.depotPos.distanceSq(from.depotPos);
            if (d < bd) { bd = d; best = m; }
        }
        return best;
    }

    private static boolean hasActiveJob(LogisticsData data, BlockPos src, BlockPos dst, ItemStack tpl) {
        for (CourierJob j : data.jobs) {
            if (j.done || !sameItem(j.item, tpl)) continue;
            if (src != null && src.equals(j.src)) return true;
            if (dst != null && dst.equals(j.dst)) return true;
        }
        return false;
    }

    private static List<ItemStack> distinctTemplates(ItemStackHandler templates) {
        List<ItemStack> out = new ArrayList<>();
        for (int i = 0; i < templates.getSlots(); i++) {
            ItemStack s = templates.getStackInSlot(i);
            if (s.isEmpty()) continue;
            boolean seen = false;
            for (ItemStack o : out) if (sameItem(o, s)) { seen = true; break; }
            if (!seen) out.add(s);
        }
        return out;
    }

    private static int countItem(ItemStackHandler handler, ItemStack tpl) {
        int n = 0;
        for (int i = 0; i < handler.getSlots(); i++) {
            ItemStack s = handler.getStackInSlot(i);
            if (!s.isEmpty() && sameItem(s, tpl)) n += s.getCount();
        }
        return n;
    }

    private static boolean templatesContain(ItemStackHandler templates, ItemStack tpl) {
        for (int i = 0; i < templates.getSlots(); i++) {
            ItemStack s = templates.getStackInSlot(i);
            if (!s.isEmpty() && sameItem(s, tpl)) return true;
        }
        return false;
    }

    /** Item + meta match (meta ignored for damageable items, same as the district tool logic). */
    private static boolean sameItem(ItemStack a, ItemStack b) {
        if (a.getItem() != b.getItem()) return false;
        return a.getItem().isDamageable() || a.getMetadata() == b.getMetadata();
    }

    private static ItemStackHandler depotHandlerAt(WorldServer world, BlockPos pos) {
        if (pos == null || !world.isBlockLoaded(pos, false)) return null;
        net.minecraft.tileentity.TileEntity te = world.getTileEntity(pos);
        return te instanceof TileEntityDistrictMarker ? ((TileEntityDistrictMarker) te).depot : null;
    }

    private static EntityCreature findFreeWorkerNear(WorldServer world, BlockPos pos) {
        if (!world.isBlockLoaded(pos, false)) return null;
        AxisAlignedBB box = new AxisAlignedBB(pos).grow(160.0, 96.0, 160.0);
        EntityCreature best = null;
        double bd = Double.MAX_VALUE;
        for (EntityCreature c : world.getEntitiesWithinAABB(EntityCreature.class, box)) {
            if (!DistrictWorkExecutor.isFreeWorker(c)) continue;
            if (COURIERS.containsKey(c.getUniqueID()) || RoadNetworkManager.isBuilder(c)) continue;
            double d = c.getDistanceSq(pos.getX(), pos.getY(), pos.getZ());
            if (d < bd) { bd = d; best = c; }
        }
        return best;
    }

    private static void ensureCourierTask(EntityCreature courier) {
        for (net.minecraft.entity.ai.EntityAITasks.EntityAITaskEntry e : courier.tasks.taskEntries) {
            if (e.action instanceof EntityAICourierWork) return;
        }
        courier.tasks.addTask(0, new EntityAICourierWork(courier));
    }
}
