package studio.ERM.strategic.civil;

import net.minecraft.block.Block;
import net.minecraft.block.state.IBlockState;
import net.minecraft.entity.EntityCreature;
import net.minecraft.init.Blocks;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.util.math.AxisAlignedBB;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.WorldServer;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import net.minecraftforge.fml.common.gameevent.TickEvent;
import net.minecraftforge.items.ItemStackHandler;
import studio.ERM.EpochRunnerMod;
import studio.ERM.war.districts.TileEntityDistrictMarker;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * THE ROAD BRAIN — roads are infrastructure, and this manager keeps them REAL:
 *
 *  MAJORITY-BLOCK DETECTION: every ~10s a few loaded roads are re-surveyed. Each segment samples
 *  the surface blocks beneath its line; the most common valid block becomes that segment's
 *  MATERIAL (mostly gravel -> a gravel road) — players pave with any palette they like.
 *
 *  CONDITION: the fraction of samples matching the material grades the segment
 *  Excellent/Good/Fair/Poor/Destroyed. Explosions and combat lower condition simply by
 *  destroying surface blocks — no damage hook needed, the survey sees the craters.
 *
 *  AUTO-REPAIR: damaged samples queue as repair ops. When a road drops below GOOD and a
 *  WAREHOUSE depot stocks the material item, a free AW2 worker is hired as a road builder
 *  (same hijack doctrine as districts): he walks the damaged stretch and fixes it one visible
 *  block at a time, consuming materials from that depot. Released when the queue drains.
 *
 * All hire state is transient (restart re-hijacks); material/condition persist on the markers.
 */
public final class RoadNetworkManager {

    private static final int PASS_INTERVAL = 200;      // survey cadence (ticks)
    private static final int ROADS_PER_PASS = 3;       // round-robin bound
    private static final int MAX_SAMPLES_PER_SEG = 32;
    private static final int MAX_QUEUE_PER_ROAD = 48;
    private static final int MAX_BUILDERS = 2;         // per world, across all roads
    public static boolean VERBOSE = false;

    private RoadNetworkManager() {}

    /** One pending fix: put {@code material} at {@code pos}. */
    public static class RepairOp {
        public final BlockPos pos;
        public final String material;
        RepairOp(BlockPos pos, String material) { this.pos = pos; this.material = material; }
    }

    /** road uid -> pending repairs (transient; resurveyed continuously). */
    private static final Map<Integer, Deque<RepairOp>> REPAIRS = new ConcurrentHashMap<>();
    /** builder uuid -> road uid being repaired. Transient. */
    private static final Map<UUID, Integer> BUILDERS = new ConcurrentHashMap<>();
    private static int tickCounter = 0;
    private static int roadCursor = 0;

    /** Grant Speed I to any player within {@link #ROAD_SPEED_RADIUS} of a road centre-line. */
    private static void applyRoadSwiftness(WorldServer world) {
        CivilPlanData plan = CivilPlanData.get(world);
        List<CivilMarker> roads = new ArrayList<>();
        for (CivilMarker m : plan.markers) if (m.isRoad() && m.points.size() >= 2) roads.add(m);
        if (roads.isEmpty()) return;
        double r2 = ROAD_SPEED_RADIUS * ROAD_SPEED_RADIUS;
        for (net.minecraft.entity.player.EntityPlayer p : world.playerEntities) {
            if (p == null || p.isDead || p.isSpectator()) continue;
            boolean onRoad = false;
            for (CivilMarker road : roads) {
                for (int i = 0; i + 1 < road.points.size() && !onRoad; i++) {
                    BlockPos a = road.points.get(i), b = road.points.get(i + 1);
                    if (segDistSq2D(p.posX, p.posZ, a.getX() + 0.5, a.getZ() + 0.5,
                            b.getX() + 0.5, b.getZ() + 0.5) <= r2) onRoad = true;
                }
                if (onRoad) break;
            }
            if (onRoad) {
                // 3s duration, refreshed each second: no flicker at the edges, particles off.
                p.addPotionEffect(new net.minecraft.potion.PotionEffect(
                        net.minecraft.init.MobEffects.SPEED, 60, 0, true, false));
            }
        }
    }

    /** Squared distance from (px,pz) to the segment (ax,az)-(bx,bz), on the X/Z plane. */
    private static double segDistSq2D(double px, double pz, double ax, double az, double bx, double bz) {
        double dx = bx - ax, dz = bz - az;
        double len2 = dx * dx + dz * dz;
        double t = len2 <= 1e-9 ? 0 : ((px - ax) * dx + (pz - az) * dz) / len2;
        t = Math.max(0, Math.min(1, t));
        double cx = ax + t * dx, cz = az + t * dz;
        double ex = px - cx, ez = pz - cz;
        return ex * ex + ez * ez;
    }

    public static Integer builderRoad(EntityCreature npc) {
        return BUILDERS.get(npc.getUniqueID());
    }

    public static boolean isBuilder(EntityCreature npc) {
        return BUILDERS.containsKey(npc.getUniqueID());
    }

    public static RepairOp peekRepair(int roadUid) {
        Deque<RepairOp> q = REPAIRS.get(roadUid);
        return q == null ? null : q.peekFirst();
    }

    public static void resetTransients() {
        REPAIRS.clear();
        BUILDERS.clear();
    }

    /** Total queued repair ops (map sidebar diagnostics). */
    public static int queuedRepairs() {
        int n = 0;
        for (Deque<RepairOp> q : REPAIRS.values()) n += q.size();
        return n;
    }

    // ==================================================================
    // THE PASS
    // ==================================================================

    /** Horizontal radius from a road centre-line that grants a traveller Swiftness. */
    private static final double ROAD_SPEED_RADIUS = 4.0;

    @SubscribeEvent
    public static void onWorldTick(TickEvent.WorldTickEvent e) {
        if (e.phase != TickEvent.Phase.END || e.side.isClient() || !(e.world instanceof WorldServer)) return;
        WorldServer world = (WorldServer) e.world;

        // ROAD SWIFTNESS — refreshed every second, independent of the (slow) survey cadence: any player
        // travelling within a few blocks of a road gets Speed, so roads are worth building + using.
        if (world.getTotalWorldTime() % 20 == 0) applyRoadSwiftness(world);

        if (++tickCounter % PASS_INTERVAL != 0) return;

        CivilPlanData plan = CivilPlanData.get(world);
        List<CivilMarker> roads = new ArrayList<>();
        for (CivilMarker m : plan.markers) if (m.isRoad() && m.points.size() >= 2) roads.add(m);
        if (roads.isEmpty()) { REPAIRS.clear(); releaseAllBuilders(world); return; }

        // Survey a few roads per pass, round-robin so long networks still get coverage.
        boolean dirty = false;
        for (int k = 0; k < Math.min(ROADS_PER_PASS, roads.size()); k++) {
            CivilMarker road = roads.get((roadCursor + k) % roads.size());
            if (surveyRoad(world, road)) dirty = true;
        }
        roadCursor += ROADS_PER_PASS;
        if (dirty) plan.markDirty();

        // Drop queues for deleted roads + assignments whose builder/road evaporated.
        REPAIRS.keySet().removeIf(uid -> byUid(roads, uid) == null);
        for (Map.Entry<UUID, Integer> en : new ArrayList<>(BUILDERS.entrySet())) {
            net.minecraft.entity.Entity ent = world.getEntityFromUuid(en.getKey());
            Deque<RepairOp> q = REPAIRS.get(en.getValue());
            if (ent == null || ent.isDead || q == null || q.isEmpty()
                    || findMaterialDepot(world, plan, q.peekFirst().material) == null) {
                BUILDERS.remove(en.getKey());
                if (ent instanceof EntityCreature) clearBadge((EntityCreature) ent);
            }
        }

        // NIGHT: builders go home like every other civilian.
        if (!world.isDaytime()) { releaseAllBuilders(world); return; }

        // Hire builders for damaged roads that have materials in a warehouse.
        if (BUILDERS.size() >= MAX_BUILDERS) return;
        for (CivilMarker road : roads) {
            if (BUILDERS.size() >= MAX_BUILDERS) break;
            Deque<RepairOp> q = REPAIRS.get(road.uid);
            if (q == null || q.isEmpty() || BUILDERS.containsValue(road.uid)) continue;
            RepairOp op = q.peekFirst();
            if (findMaterialDepot(world, plan, op.material) == null) continue; // no materials, no crew

            EntityCreature builder = findFreeWorkerNear(world, op.pos);
            if (builder == null) continue;
            BUILDERS.put(builder.getUniqueID(), road.uid);
            ensureRepairTask(builder);
            equipBadge(builder);
            EpochRunnerMod.logger.info("[Roads] hired " + builder.getName() + " to repair road #"
                    + (road.uid & 0xFFFF) + " (" + q.size() + " ops queued)");
        }
    }

    // ==================================================================
    // SURVEY — majority block + condition + damage queue per segment
    // ==================================================================

    /** Re-sample every loaded segment of one road. Returns true when anything changed. */
    private static boolean surveyRoad(WorldServer world, CivilMarker road) {
        road.ensureSegArrays();
        boolean changed = false;
        Deque<RepairOp> queue = REPAIRS.computeIfAbsent(road.uid, u -> new ArrayDeque<>());

        for (int i = 0; i < road.segmentCount(); i++) {
            BlockPos a = road.points.get(i), b = road.points.get(i + 1);
            double dx = b.getX() - a.getX(), dz = b.getZ() - a.getZ();
            double len = Math.sqrt(dx * dx + dz * dz);
            int samples = Math.max(2, Math.min(MAX_SAMPLES_PER_SEG, (int) (len / 2.0)));

            Map<String, Integer> tally = new HashMap<>();
            List<BlockPos> surfaces = new ArrayList<>();
            List<String> names = new ArrayList<>();
            long ySum = 0;
            int loaded = 0;

            for (int s = 0; s <= samples; s++) {
                int wx = a.getX() + (int) Math.round(dx * s / samples);
                int wz = a.getZ() + (int) Math.round(dz * s / samples);
                BlockPos probe = new BlockPos(wx, 64, wz);
                if (!world.isBlockLoaded(probe, false)) continue;
                BlockPos stand = world.getTopSolidOrLiquidBlock(probe);
                BlockPos surf = stand.down();
                IBlockState st = world.getBlockState(surf);
                loaded++;
                ySum += surf.getY();
                surfaces.add(surf);
                if (validRoadSurface(st)) {
                    String name = String.valueOf(Block.REGISTRY.getNameForObject(st.getBlock()));
                    names.add(name);
                    tally.merge(name, 1, Integer::sum);
                } else {
                    names.add(null); // liquid / non-solid = damage
                }
            }
            if (loaded < 4) continue; // segment (mostly) unloaded: keep persisted state

            // Majority valid block = the segment's material.
            String majority = null;
            int best = 0;
            for (Map.Entry<String, Integer> en : tally.entrySet()) {
                if (en.getValue() > best) { best = en.getValue(); majority = en.getKey(); }
            }
            int cond;
            if (majority == null) {
                cond = CivilMarker.COND_DESTROYED;
            } else {
                double match = best / (double) loaded;
                cond = match >= 0.9 ? CivilMarker.COND_EXCELLENT
                        : match >= 0.75 ? CivilMarker.COND_GOOD
                        : match >= 0.5 ? CivilMarker.COND_FAIR
                        : match >= 0.25 ? CivilMarker.COND_POOR
                        : CivilMarker.COND_DESTROYED;
            }

            if (majority != null && (!majority.equals(road.segMaterial[i]) || road.segCondition[i] != cond)) {
                changed = true;
            }
            if (majority != null) {
                road.segMaterial[i] = majority;
                Block blk = Block.REGISTRY.getObject(new net.minecraft.util.ResourceLocation(majority));
                try {
                    road.segColor[i] = 0xFF000000
                            | blk.getDefaultState().getMapColor(world, surfaces.get(0)).colorValue;
                } catch (Throwable t) {
                    road.segColor[i] = 0;
                }
                road.segLabel[i] = materialLabel(blk);
            }
            road.segCondition[i] = (byte) cond;

            // DAMAGE QUEUE: samples not matching the material become repair ops. Depressions
            // (craters) queue a fill one layer above their floor so holes visibly refill.
            if (majority != null && cond >= CivilMarker.COND_GOOD + 1) {
                int medianY = (int) (ySum / loaded);
                for (int s = 0; s < surfaces.size() && queue.size() < MAX_QUEUE_PER_ROAD; s++) {
                    BlockPos surf = surfaces.get(s);
                    boolean matches = majority.equals(names.get(s));
                    if (matches && surf.getY() >= medianY - 2) continue;
                    BlockPos target = surf.getY() < medianY - 2 ? surf.up() : surf;
                    if (!containsPos(queue, target)) queue.addLast(new RepairOp(target, majority));
                }
            }
        }
        return changed;
    }

    /** A block that can BE a road surface: any full solid cube, plus grass path. */
    private static boolean validRoadSurface(IBlockState st) {
        if (st.getMaterial().isLiquid()) return false;
        return st.isFullCube() || st.getBlock() == Blocks.GRASS_PATH;
    }

    private static String materialLabel(Block blk) {
        try {
            ItemStack asItem = new ItemStack(Item.getItemFromBlock(blk));
            if (!asItem.isEmpty()) return asItem.getDisplayName();
        } catch (Throwable ignored) {}
        try {
            return blk.getLocalizedName();
        } catch (Throwable t) {
            return "Road";
        }
    }

    private static boolean containsPos(Deque<RepairOp> q, BlockPos p) {
        for (RepairOp op : q) if (op.pos.equals(p)) return true;
        return false;
    }

    private static CivilMarker byUid(List<CivilMarker> list, int uid) {
        for (CivilMarker m : list) if (m.uid == uid) return m;
        return null;
    }

    // ==================================================================
    // REPAIR EXECUTION — called by EntityAIRoadRepair at the work site
    // ==================================================================

    /**
     * The builder finished swinging at the front op: place the material block, consume one
     * material item from the warehouse depot that stocks it. Returns false when the op could not
     * be executed (materials ran out / road gone) — the AI then idles and the pass releases him.
     */
    public static boolean completeRepair(WorldServer world, EntityCreature builder, int roadUid) {
        Deque<RepairOp> q = REPAIRS.get(roadUid);
        if (q == null || q.isEmpty()) return false;
        RepairOp op = q.peekFirst();

        CivilPlanData plan = CivilPlanData.get(world);
        ItemStackHandler depot = findMaterialDepot(world, plan, op.material);
        if (depot == null) return false;

        Block blk = Block.REGISTRY.getObject(new net.minecraft.util.ResourceLocation(op.material));
        if (blk == Blocks.AIR) { q.pollFirst(); return true; }

        // Consume 1 material item, then place. (Builder fetch trips are a later polish pass —
        // the visible part is the man on the road swinging block by block.)
        Item want = Item.getItemFromBlock(blk);
        if (!extractOne(depot, want)) return false;
        world.setBlockState(op.pos, blk.getDefaultState(), 3);
        q.pollFirst();
        if (VERBOSE) EpochRunnerMod.logger.info("[Roads] " + builder.getName() + " repaired "
                + op.material + " @ " + op.pos);
        return true;
    }

    /** The warehouse-district depot (any bound depot on a WAREHOUSE polygon) stocking this material. */
    private static ItemStackHandler findMaterialDepot(WorldServer world, CivilPlanData plan, String material) {
        Block blk = Block.REGISTRY.getObject(new net.minecraft.util.ResourceLocation(material));
        Item want = Item.getItemFromBlock(blk);
        if (want == null) return null;
        for (CivilMarker m : plan.markers) {
            if (m.isRoad() || m.kind != CivilMarker.WAREHOUSE || !m.hasDepot()) continue;
            TileEntityDistrictMarker depot = DistrictRegistry.depotOf(world, m);
            if (depot == null) continue;
            for (int i = 0; i < depot.depot.getSlots(); i++) {
                if (depot.depot.getStackInSlot(i).getItem() == want) return depot.depot;
            }
        }
        return null;
    }

    private static boolean extractOne(ItemStackHandler handler, Item want) {
        for (int i = 0; i < handler.getSlots(); i++) {
            ItemStack s = handler.getStackInSlot(i);
            if (!s.isEmpty() && s.getItem() == want) {
                handler.extractItem(i, 1, false);
                return true;
            }
        }
        return false;
    }

    // ==================================================================
    // HIRING plumbing (mirrors DistrictWorkExecutor)
    // ==================================================================

    private static EntityCreature findFreeWorkerNear(WorldServer world, BlockPos pos) {
        AxisAlignedBB box = new AxisAlignedBB(pos).grow(160.0, 96.0, 160.0);
        EntityCreature best = null;
        double bd = Double.MAX_VALUE;
        for (EntityCreature c : world.getEntitiesWithinAABB(EntityCreature.class, box)) {
            if (!DistrictWorkExecutor.isFreeWorker(c) || BUILDERS.containsKey(c.getUniqueID())) continue;
            if (studio.ERM.strategic.civil.logistics.LogisticsManager.isCourier(c)) continue;
            double d = c.getDistanceSq(pos.getX(), pos.getY(), pos.getZ());
            if (d < bd) { bd = d; best = c; }
        }
        return best;
    }

    private static void ensureRepairTask(EntityCreature builder) {
        for (net.minecraft.entity.ai.EntityAITasks.EntityAITaskEntry e : builder.tasks.taskEntries) {
            if (e.action instanceof EntityAIRoadRepair) return;
        }
        builder.tasks.addTask(0, new EntityAIRoadRepair(builder));
    }

    /** Visible job badge: road crews carry a shovel. */
    private static void equipBadge(EntityCreature builder) {
        try {
            builder.setHeldItem(net.minecraft.util.EnumHand.MAIN_HAND,
                    new ItemStack(net.minecraft.init.Items.IRON_SHOVEL));
            builder.setDropChance(net.minecraft.inventory.EntityEquipmentSlot.MAINHAND, 0f);
        } catch (Throwable ignored) {}
    }

    private static void clearBadge(EntityCreature builder) {
        try {
            builder.setHeldItem(net.minecraft.util.EnumHand.MAIN_HAND, ItemStack.EMPTY);
        } catch (Throwable ignored) {}
    }

    private static void releaseAllBuilders(WorldServer world) {
        for (UUID id : new ArrayList<>(BUILDERS.keySet())) {
            BUILDERS.remove(id);
            net.minecraft.entity.Entity ent = world.getEntityFromUuid(id);
            if (ent instanceof EntityCreature) clearBadge((EntityCreature) ent);
        }
    }
}
