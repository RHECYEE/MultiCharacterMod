package studio.ERM.strategic.civil;

import net.minecraft.block.material.Material;
import net.minecraft.entity.EntityCreature;
import net.minecraft.item.ItemStack;
import net.minecraft.util.math.AxisAlignedBB;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.WorldServer;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import net.minecraftforge.fml.common.gameevent.TickEvent;
import net.minecraftforge.items.ItemHandlerHelper;
import studio.ERM.EpochRunnerMod;
import studio.ERM.strategic.defense.Aw2Npc;
import studio.ERM.war.config.DistrictOutputConfig;
import studio.ERM.war.districts.TileEntityDistrictMarker;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * THE DISTRICT LABOR BRAIN — the civilian mirror of DefensePlanExecutor. The strategic map's
 * drawn districts HIJACK the player's AW2 worker NPCs and reorder their tasks, exactly like the
 * military tab hijacks combat NPCs: a periodic pass assigns replaceable labor to persistent
 * districts, injects one order-following AI into each worker, and the districts' depots collect
 * the yields. Kill a worker and another simply takes the slot — the district survives.
 *
 * Assignment rules per pass (every ~3s per dimension, day only):
 *   - a district WANTS workers when: polygon has a BOUND, LOADED depot; its kind has a producing
 *     DistrictOutputConfig table; staffed < depot.desiredWorkers.
 *   - candidates: PLAYER-OWNED AW2 npcs of type "worker" within GATHER_RANGE of the district
 *     centroid, not already assigned elsewhere. Nearest first.
 *   - work loop (EntityAIDistrictWork): walk to a kind-scored spot INSIDE the polygon, work it,
 *     roll the output table (rival-level gated x rateCoefficient), carry yields, deposit at the
 *     depot every few rolls. Natural movement, not fixed routes: spots re-roll every cycle.
 *
 * All state here is transient by design (same doctrine as the defense ORDERS map): the PERSISTENT
 * parts of a district are the polygon + depot block. A restart just re-hijacks.
 */
public final class DistrictWorkExecutor {

    private static final double GATHER_RANGE = 192.0;
    private static final int PASS_INTERVAL = 60; // ticks between assignment passes
    public static boolean VERBOSE = false;

    private DistrictWorkExecutor() {}

    /** worker uuid -> live assignment. Transient. */
    private static final Map<UUID, Assignment> ASSIGNMENTS = new ConcurrentHashMap<>();
    /** worker uuid -> yields carried since the last depot run. Transient (in-transit only). */
    private static final Map<UUID, List<ItemStack>> CARRIED = new ConcurrentHashMap<>();
    private static final Random RNG = new Random();
    private static int passCounter = 0;

    public static class Assignment {
        public final int districtUid;
        public final int kind;
        public final BlockPos depot;
        public BlockPos workSpot;
        /** true = heading to the depot to unload (set by the AI when carrying enough). */
        public boolean depositRun;

        Assignment(int districtUid, int kind, BlockPos depot, BlockPos workSpot) {
            this.districtUid = districtUid;
            this.kind = kind;
            this.depot = depot;
            this.workSpot = workSpot;
        }
    }

    public static Assignment assignmentFor(EntityCreature npc) {
        return ASSIGNMENTS.get(npc.getUniqueID());
    }

    public static int assignedCount() {
        return ASSIGNMENTS.size();
    }

    public static void resetTransients() {
        ASSIGNMENTS.clear();
        CARRIED.clear();
    }

    // ==================================================================
    // THE PASS
    // ==================================================================

    @SubscribeEvent
    public static void onWorldTick(TickEvent.WorldTickEvent e) {
        if (e.phase != TickEvent.Phase.END || e.side.isClient() || !(e.world instanceof WorldServer)) return;
        if (++passCounter % PASS_INTERVAL != 0) return;
        WorldServer world = (WorldServer) e.world;

        // NIGHT: drop all orders in this dimension — workers fall back to AW2's own AI (beds).
        if (!world.isDaytime()) {
            boolean any = false;
            for (Map.Entry<UUID, Assignment> en : ASSIGNMENTS.entrySet()) {
                if (dimensionOwns(world, en.getValue())) { ASSIGNMENTS.remove(en.getKey()); any = true; }
            }
            if (any && VERBOSE) EpochRunnerMod.logger.info("[DistrictAI] night — released workers");
            return;
        }

        CivilPlanData plan = CivilPlanData.get(world);
        List<CivilMarker> districts = new ArrayList<>();
        for (CivilMarker m : plan.markers) {
            if (!m.isRoad() && m.hasDepot() && DistrictOutputConfig.produces(m.configKey())) {
                districts.add(m);
            }
        }
        if (districts.isEmpty()) return;

        // Current staffing + drop assignments whose worker or district evaporated.
        Map<Integer, Integer> staffed = new HashMap<>();
        Set<UUID> alive = new HashSet<>();
        for (net.minecraft.entity.Entity ent : world.loadedEntityList) {
            if (ent instanceof EntityCreature && ASSIGNMENTS.containsKey(ent.getUniqueID()) && !ent.isDead) {
                alive.add(ent.getUniqueID());
            }
        }
        for (Map.Entry<UUID, Assignment> en : new ArrayList<>(ASSIGNMENTS.entrySet())) {
            Assignment a = en.getValue();
            if (!dimensionOwns(world, a)) continue; // another dimension's worker
            CivilMarker district = byUid(districts, a.districtUid);
            TileEntityDistrictMarker depot = district != null ? DistrictRegistry.depotOf(world, district) : null;
            if (!alive.contains(en.getKey()) || district == null || depot == null) {
                ASSIGNMENTS.remove(en.getKey());
                CARRIED.remove(en.getKey()); // in-transit yields die with the worker (or unload)
                continue;
            }
            staffed.merge(a.districtUid, 1, Integer::sum);
        }

        // Staff each understaffed district from the nearby unassigned worker pool.
        for (CivilMarker district : districts) {
            TileEntityDistrictMarker depot = DistrictRegistry.depotOf(world, district);
            if (depot == null) continue;
            int want = depot.getDesiredWorkers() - staffed.getOrDefault(district.uid, 0);
            if (want <= 0) {
                releaseOverstaff(world, district, depot, staffed.getOrDefault(district.uid, 0));
                continue;
            }

            BlockPos c = district.center();
            BlockPos centroid = world.getTopSolidOrLiquidBlock(new BlockPos(c.getX(), 64, c.getZ()));
            AxisAlignedBB box = new AxisAlignedBB(centroid).grow(GATHER_RANGE, 96, GATHER_RANGE);
            List<EntityCreature> pool = new ArrayList<>();
            for (EntityCreature cand : world.getEntitiesWithinAABB(EntityCreature.class, box)) {
                if (cand.isDead || ASSIGNMENTS.containsKey(cand.getUniqueID())) continue;
                if (Aw2Npc.allegiance(cand) != Aw2Npc.Allegiance.PLAYER_OWNED) continue;
                if (!"worker".equalsIgnoreCase(Aw2Npc.type(cand))) continue;
                pool.add(cand);
            }
            pool.sort((x, y) -> Double.compare(
                    x.getDistanceSq(centroid.getX(), centroid.getY(), centroid.getZ()),
                    y.getDistanceSq(centroid.getX(), centroid.getY(), centroid.getZ())));

            for (EntityCreature worker : pool) {
                if (want-- <= 0) break;
                BlockPos spot = pickWorkSpot(world, district);
                if (spot == null) spot = centroid;
                ASSIGNMENTS.put(worker.getUniqueID(),
                        new Assignment(district.uid, district.kind, district.depotPos, spot));
                ensureWorkTask(worker);
                staffed.merge(district.uid, 1, Integer::sum);
                EpochRunnerMod.logger.info("[DistrictAI] hired " + worker.getName() + " ("
                        + Aw2Npc.fullType(worker) + ") -> " + CivilMarker.nameOf(district.kind)
                        + " district #" + (district.uid & 0xFFFF));
            }
        }
    }

    /** desiredWorkers was lowered: release the newest extras back to AW2. */
    private static void releaseOverstaff(WorldServer world, CivilMarker district,
                                         TileEntityDistrictMarker depot, int have) {
        int extra = have - depot.getDesiredWorkers();
        if (extra <= 0) return;
        for (Map.Entry<UUID, Assignment> en : ASSIGNMENTS.entrySet()) {
            if (extra <= 0) break;
            if (en.getValue().districtUid == district.uid) {
                ASSIGNMENTS.remove(en.getKey());
                extra--;
            }
        }
    }

    private static boolean dimensionOwns(WorldServer world, Assignment a) {
        // Assignments key off polygon uids, which are per-world saved data — check the plan.
        return DistrictRegistry.byUid(world, a.districtUid) != null || a.depot != null
                && world.isBlockLoaded(a.depot, false);
    }

    private static CivilMarker byUid(List<CivilMarker> list, int uid) {
        for (CivilMarker m : list) if (m.uid == uid) return m;
        return null;
    }

    /** Inject the order-following work AI ONCE (scanned, not flagged — survives entity reloads).
     *  Priority 0 like the military hijack: it must OUTRANK AW2's own movement tasks; workers
     *  still flee when hurt because the task itself yields while under attack. */
    private static void ensureWorkTask(EntityCreature worker) {
        for (net.minecraft.entity.ai.EntityAITasks.EntityAITaskEntry e : worker.tasks.taskEntries) {
            if (e.action instanceof EntityAIDistrictWork) return;
        }
        worker.tasks.addTask(0, new EntityAIDistrictWork(worker));
    }

    // ==================================================================
    // WORK SPOTS — natural movement: react to features inside the polygon
    // ==================================================================

    /**
     * Sample candidate points inside the polygon and keep the best for the district's kind:
     * fishermen want shoreline, lumberjacks logs, farmers crops, miners exposed stone, hunters
     * open grass. Returns a surface position, or null when sampling found nothing inside.
     */
    public static BlockPos pickWorkSpot(WorldServer world, CivilMarker district) {
        int minX = Integer.MAX_VALUE, maxX = Integer.MIN_VALUE;
        int minZ = Integer.MAX_VALUE, maxZ = Integer.MIN_VALUE;
        for (BlockPos p : district.points) {
            minX = Math.min(minX, p.getX()); maxX = Math.max(maxX, p.getX());
            minZ = Math.min(minZ, p.getZ()); maxZ = Math.max(maxZ, p.getZ());
        }
        if (minX > maxX) return null;

        BlockPos best = null;
        int bestScore = -1;
        for (int i = 0; i < 12; i++) {
            int x = minX + RNG.nextInt(Math.max(1, maxX - minX + 1));
            int z = minZ + RNG.nextInt(Math.max(1, maxZ - minZ + 1));
            if (!district.contains(x, z)) continue;
            if (!world.isBlockLoaded(new BlockPos(x, 64, z), false)) continue;
            BlockPos surface = world.getTopSolidOrLiquidBlock(new BlockPos(x, 64, z));
            // Never order INTO liquid — stand beside it.
            if (world.getBlockState(surface).getMaterial().isLiquid()
                    || world.getBlockState(surface.down()).getMaterial().isLiquid()) {
                BlockPos dry = driftToDry(world, surface);
                if (dry == null) continue;
                surface = dry;
            }
            int score = scoreSpot(world, surface, district.kind);
            if (score > bestScore) { bestScore = score; best = surface; }
        }
        return best;
    }

    /** Walk outward a few blocks to the nearest non-liquid column (shoreline stance). */
    private static BlockPos driftToDry(WorldServer world, BlockPos wet) {
        for (int r = 1; r <= 6; r++) {
            for (net.minecraft.util.EnumFacing f : net.minecraft.util.EnumFacing.HORIZONTALS) {
                BlockPos p = world.getTopSolidOrLiquidBlock(wet.offset(f, r));
                if (!world.getBlockState(p).getMaterial().isLiquid()
                        && !world.getBlockState(p.down()).getMaterial().isLiquid()) return p;
            }
        }
        return null;
    }

    private static int scoreSpot(WorldServer world, BlockPos surface, int kind) {
        int score = RNG.nextInt(3); // tiebreak jitter so workers spread out
        for (int dx = -3; dx <= 3; dx++) {
            for (int dz = -3; dz <= 3; dz++) {
                for (int dy = -2; dy <= 2; dy++) {
                    BlockPos p = surface.add(dx, dy, dz);
                    net.minecraft.block.state.IBlockState st = world.getBlockState(p);
                    Material mat = st.getMaterial();
                    switch (kind) {
                        case CivilMarker.FISHING:
                            if (mat == Material.WATER) score += 2;
                            break;
                        case CivilMarker.LUMBER:
                            if (mat == Material.WOOD) score += 2;
                            if (mat == Material.LEAVES) score += 1;
                            break;
                        case CivilMarker.FARM:
                            if (st.getBlock() instanceof net.minecraft.block.BlockCrops
                                    || st.getBlock() == net.minecraft.init.Blocks.FARMLAND) score += 2;
                            if (mat == Material.PLANTS) score += 1;
                            break;
                        case CivilMarker.MINING:
                            if (mat == Material.ROCK) score += 1;
                            if (st.getBlock() instanceof net.minecraft.block.BlockOre) score += 4;
                            break;
                        case CivilMarker.HUNTING:
                            if (mat == Material.GRASS) score += 1;
                            break;
                        default:
                            break;
                    }
                }
            }
        }
        return score;
    }

    // ==================================================================
    // YIELDS — rolled by the AI at the end of each work cycle
    // ==================================================================

    /** One completed work cycle: maybe roll a yield into the worker's carried list. */
    public static void onWorkCycleComplete(WorldServer world, EntityCreature worker, Assignment a) {
        CivilMarker district = DistrictRegistry.byUid(world, a.districtUid);
        if (district == null) return;
        String key = district.configKey();
        if (RNG.nextDouble() >= DistrictOutputConfig.rateCoefficient(key)) return;

        ItemStack yield = DistrictOutputConfig.rollOutput(key, DistrictRegistry.rivalLevel(world));
        if (yield.isEmpty()) return;
        CARRIED.computeIfAbsent(worker.getUniqueID(), u -> new ArrayList<>()).add(yield);
        if (VERBOSE) EpochRunnerMod.logger.info("[DistrictAI] " + worker.getName() + " produced "
                + yield.getCount() + "x " + yield.getDisplayName());
    }

    public static int carriedCount(EntityCreature worker) {
        List<ItemStack> list = CARRIED.get(worker.getUniqueID());
        return list == null ? 0 : list.size();
    }

    /** Arrived at the depot: insert everything carried; anything that doesn't fit drops. */
    public static void depositCarried(WorldServer world, EntityCreature worker, Assignment a) {
        List<ItemStack> list = CARRIED.remove(worker.getUniqueID());
        if (list == null || list.isEmpty()) return;
        CivilMarker district = DistrictRegistry.byUid(world, a.districtUid);
        TileEntityDistrictMarker depot = district != null ? DistrictRegistry.depotOf(world, district) : null;
        int deposited = 0;
        for (ItemStack s : list) {
            ItemStack left = depot != null ? ItemHandlerHelper.insertItemStacked(depot.depot, s, false) : s;
            if (!left.isEmpty()) {
                net.minecraft.inventory.InventoryHelper.spawnItemStack(world,
                        worker.posX, worker.posY, worker.posZ, left);
            } else {
                deposited++;
            }
        }
        if (deposited > 0 && VERBOSE) {
            EpochRunnerMod.logger.info("[DistrictAI] " + worker.getName() + " deposited "
                    + deposited + " yields at " + a.depot);
        }
    }
}
