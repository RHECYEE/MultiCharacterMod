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

    /** Workers currently assigned to one district (for the map panel's "Workers X/Y"). */
    public static int assignedTo(int districtUid) {
        int n = 0;
        for (Assignment a : ASSIGNMENTS.values()) if (a.districtUid == districtUid) n++;
        return n;
    }

    /** Is this AW2 npc an unassigned, player-owned civilian worker? (available-labor pool test) */
    public static boolean isFreeWorker(EntityCreature c) {
        return !c.isDead
                && Aw2Npc.allegiance(c) == Aw2Npc.Allegiance.PLAYER_OWNED
                && "worker".equalsIgnoreCase(Aw2Npc.type(c))
                && !ASSIGNMENTS.containsKey(c.getUniqueID());
    }

    public static boolean isAnyWorker(EntityCreature c) {
        return !c.isDead
                && Aw2Npc.allegiance(c) == Aw2Npc.Allegiance.PLAYER_OWNED
                && "worker".equalsIgnoreCase(Aw2Npc.type(c));
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

        // ARMORY: arm empty-handed player-owned soldiers from a nearby armory depot (runs day+night).
        equipFromArmory(world);

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
            // Quarry mines, Warehouse organizes, Research generates points — all staff without a table.
            boolean workable = DistrictOutputConfig.produces(m.configKey())
                    || m.kind == CivilMarker.QUARRY || m.kind == CivilMarker.WAREHOUSE
                    || m.kind == CivilMarker.RESEARCH;
            if (!m.isRoad() && m.hasDepot() && workable) {
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
                if (alive.contains(en.getKey())) clearJobItem(world, en.getKey()); // district gone -> badge off
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
                if (want <= 0) break;
                BlockPos spot = pickWorkSpot(world, district, null);
                // No workable feature anywhere sampled -> don't hire into empty ground this pass
                // (and don't spend a want slot on the miss).
                if (spot == null) continue;
                ASSIGNMENTS.put(worker.getUniqueID(),
                        new Assignment(district.uid, district.kind, district.depotPos, spot));
                ensureWorkTask(worker);
                equipJobItem(worker, district.kind); // rod/axe/hoe/pick/bow in hand = the job reads at a glance
                staffed.merge(district.uid, 1, Integer::sum);
                want--;
                EpochRunnerMod.logger.info("[DistrictAI] hired " + worker.getName() + " ("
                        + Aw2Npc.fullType(worker) + ") -> " + CivilMarker.nameOf(district.kind)
                        + " district #" + (district.uid & 0xFFFF));
            }
        }
    }

    private static final net.minecraft.inventory.EntityEquipmentSlot[] LOADOUT_EQUIP = {
            net.minecraft.inventory.EntityEquipmentSlot.MAINHAND,
            net.minecraft.inventory.EntityEquipmentSlot.OFFHAND,
            net.minecraft.inventory.EntityEquipmentSlot.HEAD,
            net.minecraft.inventory.EntityEquipmentSlot.CHEST,
            net.minecraft.inventory.EntityEquipmentSlot.LEGS,
            net.minecraft.inventory.EntityEquipmentSlot.FEET };

    /**
     * ARMORY SUPPLY — the main kit hub. Each armory defines up to 6 LOADOUTS (patterns) with a soldier
     * COUNT each; nearby player-owned combat NPCs are assigned to loadouts up to their counts (tagged so
     * the assignment sticks), and equipped by ISSUING the loadout's items from the depot STOCK (couriers
     * keep the stock filled). A slot with no stock is left empty — under-supplied until a courier arrives.
     */
    private static void equipFromArmory(WorldServer world) {
        List<CivilMarker> armories = new ArrayList<>();
        for (CivilMarker m : CivilPlanData.get(world).markers) {
            if (m.kind == CivilMarker.ARMORY && m.hasDepot()) armories.add(m);
        }
        if (armories.isEmpty()) return;

        for (CivilMarker ar : armories) {
            TileEntityDistrictMarker depot = DistrictRegistry.depotOf(world, ar);
            if (depot == null) continue;
            AxisAlignedBB box = new AxisAlignedBB(ar.depotPos).grow(48, 32, 48);

            // Tally current assignments to THIS armory + collect the unassigned nearby soldiers.
            int[] assigned = new int[TileEntityDistrictMarker.LOADOUTS];
            List<EntityCreature> unassigned = new ArrayList<>();
            for (EntityCreature npc : world.getEntitiesWithinAABB(EntityCreature.class, box)) {
                if (npc.isDead || !Aw2Npc.isPlayerOwnedCombat(npc)) continue;
                int tag = npc.getEntityData().getInteger("erm_loadout") - 1; // stored +1; 0 => none
                int tagArm = npc.getEntityData().getInteger("erm_loadout_arm");
                if (tag >= 0 && tag < TileEntityDistrictMarker.LOADOUTS && tagArm == ar.uid) {
                    assigned[tag]++;
                    if (npc.getHeldItemMainhand().isEmpty()) equipLoadout(depot, npc, tag); // re-kit after death
                } else {
                    unassigned.add(npc);
                }
            }

            // Fill open loadout slots (lowest row first) from the unassigned pool.
            for (EntityCreature npc : unassigned) {
                int row = -1;
                for (int i = 0; i < TileEntityDistrictMarker.LOADOUTS; i++) {
                    if (assigned[i] < depot.getLoadoutCount(i)) { row = i; break; }
                }
                if (row < 0) break; // all loadouts full at this armory
                npc.getEntityData().setInteger("erm_loadout", row + 1);
                npc.getEntityData().setInteger("erm_loadout_arm", ar.uid);
                assigned[row]++;
                equipLoadout(depot, npc, row);
                EpochRunnerMod.logger.info("[Armory] assigned " + npc.getName() + " to loadout " + (row + 1));
            }
        }
    }

    /** Issue loadout {@code row}'s items to a soldier, pulling each from the depot stock (skip if absent). */
    private static void equipLoadout(TileEntityDistrictMarker depot, EntityCreature npc, int row) {
        for (int slot = 0; slot < TileEntityDistrictMarker.LOADOUT_SLOTS; slot++) {
            ItemStack want = depot.loadouts.getStackInSlot(row * TileEntityDistrictMarker.LOADOUT_SLOTS + slot);
            if (want.isEmpty()) continue;
            net.minecraft.inventory.EntityEquipmentSlot eq = LOADOUT_EQUIP[slot];
            if (!npc.getItemStackFromSlot(eq).isEmpty()) continue; // already wearing something there
            int found = findStock(depot, want);
            if (found < 0) continue; // not in stock — courier will bring it
            ItemStack issue = depot.depot.extractItem(found, 1, false);
            if (issue.isEmpty()) continue;
            npc.setItemStackToSlot(eq, issue);
            npc.setDropChance(eq, 0f); // issued gear never drops (no dupe)
        }
    }

    /** First depot slot holding an item matching {@code want} (item + meta), or -1. */
    private static int findStock(TileEntityDistrictMarker depot, ItemStack want) {
        for (int i = 0; i < depot.depot.getSlots(); i++) {
            ItemStack s = depot.depot.getStackInSlot(i);
            if (!s.isEmpty() && s.getItem() == want.getItem() && s.getMetadata() == want.getMetadata()) return i;
        }
        return -1;
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
                clearJobItem(world, en.getKey());
                extra--;
            }
        }
    }

    /**
     * The visible JOB BADGE: a hired worker holds the tool of its trade (fishing rod / axe / hoe /
     * pickaxe / bow), so what a citizen does is readable at a glance. Cleared when the worker is
     * explicitly released (overstaff / district deleted); kept overnight -- they're still employed,
     * just walking home. Zero drop chance so the tool never becomes loot.
     */
    private static void equipJobItem(EntityCreature worker, int kind) {
        try {
            net.minecraft.item.ItemStack tool = jobItemFor(kind);
            if (tool.isEmpty()) return;
            worker.setHeldItem(net.minecraft.util.EnumHand.MAIN_HAND, tool);
            worker.setDropChance(net.minecraft.inventory.EntityEquipmentSlot.MAINHAND, 0f);
        } catch (Throwable ignored) {}
    }

    private static void clearJobItem(WorldServer world, UUID workerId) {
        try {
            net.minecraft.entity.Entity e = world.getEntityFromUuid(workerId);
            if (e instanceof EntityCreature)
                ((EntityCreature) e).setHeldItem(net.minecraft.util.EnumHand.MAIN_HAND,
                        net.minecraft.item.ItemStack.EMPTY);
        } catch (Throwable ignored) {}
    }

    private static net.minecraft.item.ItemStack jobItemFor(int kind) {
        switch (kind) {
            case CivilMarker.FISHING: return new net.minecraft.item.ItemStack(net.minecraft.init.Items.FISHING_ROD);
            case CivilMarker.LUMBER:  return new net.minecraft.item.ItemStack(net.minecraft.init.Items.IRON_AXE);
            case CivilMarker.FARM:    return new net.minecraft.item.ItemStack(net.minecraft.init.Items.IRON_HOE);
            case CivilMarker.MINING:  return new net.minecraft.item.ItemStack(net.minecraft.init.Items.IRON_PICKAXE);
            case CivilMarker.HUNTING: return new net.minecraft.item.ItemStack(net.minecraft.init.Items.BOW);
            default:                  return net.minecraft.item.ItemStack.EMPTY;
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
        // Priority 1: still outranks AW2's own movement tasks (~4+) but SITS BELOW the citizen-life
        // task at 0, so night bed-seeking and hunger override the work loop.
        worker.tasks.addTask(1, new EntityAIDistrictWork(worker));
    }

    /** Inject the citizen-life task ONCE (priority 0) for any player-owned worker, so it sleeps at
     *  night and visits the kitchen when hungry even when not hired to a district. */
    public static void ensureLifeTask(EntityCreature worker) {
        for (net.minecraft.entity.ai.EntityAITasks.EntityAITaskEntry e : worker.tasks.taskEntries) {
            if (e.action instanceof EntityAICitizenLife) return;
        }
        worker.tasks.addTask(0, new EntityAICitizenLife(worker));
    }

    // ==================================================================
    // WORK SPOTS — natural movement: react to features inside the polygon
    // ==================================================================

    /**
     * Sample candidate points inside the polygon and keep a GOOD one for the district's kind:
     * fishermen want shoreline, lumberjacks logs, farmers crops, miners exposed stone, hunters
     * open grass. SPREAD RULES (fix for everyone stacking on the single best-scoring spot):
     * candidates within 8 blocks of ANOTHER worker's current spot are rejected, re-rolls avoid
     * the caller's own current spot, and the pick is random among the top three scorers instead
     * of deterministic-best. Returns a surface position, or null when sampling found nothing.
     */
    public static BlockPos pickWorkSpot(WorldServer world, CivilMarker district, BlockPos avoidOwn) {
        if (district == null) return null;
        int minX = Integer.MAX_VALUE, maxX = Integer.MIN_VALUE;
        int minZ = Integer.MAX_VALUE, maxZ = Integer.MIN_VALUE;
        for (BlockPos p : district.points) {
            minX = Math.min(minX, p.getX()); maxX = Math.max(maxX, p.getX());
            minZ = Math.min(minZ, p.getZ()); maxZ = Math.max(maxZ, p.getZ());
        }
        if (minX > maxX) return null;

        // Other workers' claimed spots in this district (spread exclusion zones).
        List<BlockPos> taken = new ArrayList<>();
        for (Assignment other : ASSIGNMENTS.values()) {
            if (other.districtUid == district.uid && other.workSpot != null
                    && !other.workSpot.equals(avoidOwn)) {
                taken.add(other.workSpot);
            }
        }

        List<BlockPos> spots = new ArrayList<>();
        List<Integer> scores = new ArrayList<>();
        for (int i = 0; i < 16; i++) {
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
            if (tooClose(surface, taken, 8.0) || (avoidOwn != null && tooClose(surface, avoidOwn, 6.0))) {
                continue;
            }
            // NO FEATURE, NO SPOT: a fishing spot needs water, a farm tilled soil, etc. Spots with
            // nothing to work are rejected outright so workers don't wander to empty ground.
            int fs = pickFeatureScore(world, surface, district.kind);
            if (fs <= 0) continue;
            spots.add(surface);
            scores.add(fs);
        }
        if (spots.isEmpty()) return null;

        // Random pick among the top three scorers — variety over a single magnet spot.
        List<Integer> order = new ArrayList<>();
        for (int i = 0; i < spots.size(); i++) order.add(i);
        order.sort((a, b) -> scores.get(b) - scores.get(a));
        int topN = Math.min(3, order.size());
        return spots.get(order.get(RNG.nextInt(topN)));
    }

    private static boolean tooClose(BlockPos p, List<BlockPos> others, double dist) {
        for (BlockPos o : others) if (tooClose(p, o, dist)) return true;
        return false;
    }

    private static boolean tooClose(BlockPos p, BlockPos o, double dist) {
        double dx = p.getX() - o.getX(), dz = p.getZ() - o.getZ();
        return dx * dx + dz * dz < dist * dist;
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

    /**
     * How many WORK-FEATURE blocks sit around this surface spot, for the district's kind — the
     * "is there anything to work here" score used to accept/reject and rank spots. ZERO means
     * empty ground: no water to fish, no logs to fell, no tilled soil to tend, no rock to mine.
     * HUNTING scores by grass (hunters roam grassland); the actual kill is gated on live animals
     * in {@link #featurePresent}. Non-natural kinds return 1 (never feature-gated).
     */
    private static int pickFeatureScore(WorldServer world, BlockPos surface, int kind) {
        if (kind < CivilMarker.FISHING) return 1; // config-driven districts aren't terrain-gated
        int hits = 0;
        for (int dx = -3; dx <= 3; dx++) {
            for (int dz = -3; dz <= 3; dz++) {
                for (int dy = -2; dy <= 2; dy++) {
                    BlockPos p = surface.add(dx, dy, dz);
                    if (!world.isBlockLoaded(p, false)) continue;
                    net.minecraft.block.state.IBlockState st = world.getBlockState(p);
                    Material mat = st.getMaterial();
                    switch (kind) {
                        case CivilMarker.FISHING:
                            if (mat == Material.WATER) hits += 2;
                            break;
                        case CivilMarker.LUMBER:
                            // A tree farm works standing wood/leaves AND bare ground it can plant on,
                            // so a fresh (treeless) district can still be forested from saplings.
                            if (mat == Material.WOOD) hits += 2;
                            else if (mat == Material.LEAVES) hits += 1;
                            else if ((mat == Material.GRASS || mat == Material.GROUND)
                                    && world.isAirBlock(p.up())) hits += 1;
                            break;
                        case CivilMarker.FARM:
                            // Tilled soil or crops ONLY — wild grass/flowers are not farm work.
                            if (st.getBlock() instanceof net.minecraft.block.BlockCrops
                                    || st.getBlock() == net.minecraft.init.Blocks.FARMLAND) hits += 2;
                            break;
                        case CivilMarker.MINING:
                            if (st.getBlock() instanceof net.minecraft.block.BlockOre) hits += 4;
                            else if (mat == Material.ROCK) hits += 1;
                            break;
                        case CivilMarker.HUNTING:
                            if (mat == Material.GRASS) hits += 1;
                            break;
                        default:
                            break;
                    }
                }
            }
        }
        return hits;
    }

    /**
     * THE YIELD GATE — is the required resource actually present at the worker's current position
     * RIGHT NOW? You can't fish from nothing. Checked at production time (not just spot-pick time)
     * because terrain/animals change: water needs water, farm needs tilled soil/crops, lumber
     * logs, mining exposed rock/ore, hunting a LIVE animal within range. Non-natural districts
     * (config tables on RESIDENTIAL/WAREHOUSE/etc.) are never gated.
     */
    public static boolean featurePresent(WorldServer world, BlockPos pos, int kind) {
        switch (kind) {
            case CivilMarker.FISHING:
            case CivilMarker.LUMBER:
            case CivilMarker.FARM:
            case CivilMarker.MINING:
                return pickFeatureScore(world, pos, kind) > 0;
            case CivilMarker.HUNTING:
                return !world.getEntitiesWithinAABB(net.minecraft.entity.passive.EntityAnimal.class,
                        new AxisAlignedBB(pos).grow(10.0)).isEmpty();
            default:
                return true;
        }
    }

    // ==================================================================
    // YIELDS — rolled by the AI at the end of each work cycle
    // ==================================================================

    /** One completed work cycle: maybe roll a yield into the worker's carried list. */
    public static void onWorkCycleComplete(WorldServer world, EntityCreature worker, Assignment a) {
        CivilMarker district = DistrictRegistry.byUid(world, a.districtUid);
        if (district == null) return;

        // NO FEATURE, NO WORK — gate on what's actually under the worker right now. A fishing
        // district over dry land, a farm with no tilled soil, a mine with no exposed rock: no yield.
        if (!featurePresent(world, worker.getPosition(), district.kind)) return;

        // FARM VISUAL: physically harvest a mature crop (reset to age 0) and, using SEEDS FROM THE
        // DEPOT, plant bare tilled soil. The deposited YIELD still comes from the output table (the
        // "set item per district"); this just makes the field a living patchwork of growth stages.
        if (district.kind == CivilMarker.FARM) {
            TileEntityDistrictMarker fd = DistrictRegistry.depotOf(world, district);
            workFarmVisual(world, worker.getPosition(), fd);
        }

        // QUARRY: no output table — physically MINE one block per cycle (BuildCraft-style pit) and
        // carry the REAL drops. Wears a pickaxe stocked in the depot. Returns after mining.
        if (district.kind == CivilMarker.QUARRY) {
            workQuarry(world, worker, DistrictRegistry.depotOf(world, district));
            return;
        }

        // WAREHOUSE: no output table — the worker "organizes" by pulling loose items from nearby
        // chests into the carried pile, which the deposit run moves into the warehouse depot.
        if (district.kind == CivilMarker.WAREHOUSE) {
            workWarehouse(world, worker, district);
            return;
        }

        // RESEARCH: no item output — the scientists just work the room; their COUNT drives research
        // points in ResearchManager. Nothing to deposit.
        if (district.kind == CivilMarker.RESEARCH) {
            return;
        }

        // LUMBER: a managed TREE FARM or FRUIT FARM (depot sub-mode toggle). Both plant saplings from
        // the depot to forest the interior; Tree Farm fells mature logs for the "lumber" table, Fruit
        // Farm leaves the trees standing and harvests the "lumber_fruit" table.
        if (district.kind == CivilMarker.LUMBER) {
            TileEntityDistrictMarker ld = DistrictRegistry.depotOf(world, district);
            workLumber(world, worker, district, ld, ld != null && ld.getSubMode() == 1);
            return;
        }

        produceFromTable(world, worker, district, district.configKey());
    }

    /**
     * Rate-gated roll of a district's output table into the worker's carried pile, with the depot's
     * best configured tool lifting the effective rival level (and wearing a little). Shared by the
     * generic districts and the mode-specific lumber path.
     */
    private static void produceFromTable(WorldServer world, EntityCreature worker,
                                         CivilMarker district, String key) {
        if (RNG.nextDouble() >= DistrictOutputConfig.rateCoefficient(key)) return;
        int level = DistrictRegistry.rivalLevel(world);
        TileEntityDistrictMarker depot = DistrictRegistry.depotOf(world, district);
        int[] tool = depot != null ? DistrictOutputConfig.findBestTool(key, depot.depot) : null;
        int effectiveLevel = level + (tool != null ? tool[1] : 0);
        ItemStack yield = DistrictOutputConfig.rollOutput(key, effectiveLevel);
        if (yield.isEmpty()) return;
        if (tool != null && depot != null) DistrictOutputConfig.wearTool(depot.depot, tool[0]);
        CARRIED.computeIfAbsent(worker.getUniqueID(), u -> new ArrayList<>()).add(yield);
        if (VERBOSE) EpochRunnerMod.logger.info("[DistrictAI] " + worker.getName() + " produced "
                + yield.getCount() + "x " + yield.getDisplayName()
                + (tool != null ? " (tool +" + tool[1] + ")" : ""));
    }

    /**
     * The managed-forest loop. Always tries to PLANT a sapling from the depot on bare ground near the
     * worker (foresting the interior). Then, only when a real tree is present:
     *   TREE FARM  — fell one mature log (visible break) and roll the "lumber" table (logs).
     *   FRUIT FARM — leave the tree standing and roll the "lumber_fruit" table (fruit).
     * No tree nearby yet -> it just planted, no yield.
     */
    private static void workLumber(WorldServer world, EntityCreature worker, CivilMarker district,
                                   TileEntityDistrictMarker depot, boolean fruit) {
        BlockPos feet = worker.getPosition();
        workLumberReplant(world, feet, depot); // forest the interior from depot saplings

        BlockPos log = null;
        boolean canopy = false;
        for (int dx = -3; dx <= 3 && log == null; dx++) {
            for (int dz = -3; dz <= 3; dz++) {
                for (int dy = -1; dy <= 4; dy++) {
                    BlockPos p = feet.add(dx, dy, dz);
                    if (!world.isBlockLoaded(p, false)) continue;
                    net.minecraft.block.material.Material m = world.getBlockState(p).getMaterial();
                    if (m == net.minecraft.block.material.Material.WOOD) { log = p; break; }
                    if (m == net.minecraft.block.material.Material.LEAVES) canopy = true;
                }
                if (log != null) break;
            }
        }
        if (log == null && !canopy) return; // nothing grown yet — only planted this cycle

        if (!fruit && log != null) {
            // Tree farm fells a log (a sapling was just planted to replace it).
            world.playEvent(2001, log, net.minecraft.block.Block.getStateId(world.getBlockState(log)));
            world.setBlockState(log, net.minecraft.init.Blocks.AIR.getDefaultState(), 3);
        }
        produceFromTable(world, worker, district, fruit ? "lumber_fruit" : "lumber");
    }

    /**
     * The visible farm loop, run once per work cycle at the worker's feet:
     *   1) if a MATURE crop sits within reach, "harvest" it by resetting it to age 0 (the field
     *      cycles through growth stages naturally as time passes);
     *   2) else if bare tilled soil sits within reach, PLANT it from any seed in the depot.
     * Purely cosmetic — the actual produced item is the district's output table. No-op when nothing
     * is workable so a farmer standing on stone does nothing.
     */
    private static void workFarmVisual(WorldServer world, BlockPos feet, TileEntityDistrictMarker depot) {
        BlockPos mature = null, bare = null;
        for (int dx = -3; dx <= 3 && mature == null; dx++) {
            for (int dz = -3; dz <= 3 && mature == null; dz++) {
                for (int dy = -1; dy <= 1; dy++) {
                    BlockPos p = feet.add(dx, dy, dz);
                    if (!world.isBlockLoaded(p, false)) continue;
                    net.minecraft.block.state.IBlockState st = world.getBlockState(p);
                    if (st.getBlock() instanceof net.minecraft.block.BlockCrops) {
                        net.minecraft.block.BlockCrops crop = (net.minecraft.block.BlockCrops) st.getBlock();
                        if (crop.isMaxAge(st)) { mature = p; break; }
                    } else if (st.getBlock() == net.minecraft.init.Blocks.FARMLAND
                            && world.isAirBlock(p.up())) {
                        if (bare == null) bare = p.up();
                    }
                }
            }
        }
        if (mature != null) {
            net.minecraft.block.BlockCrops crop =
                    (net.minecraft.block.BlockCrops) world.getBlockState(mature).getBlock();
            world.setBlockState(mature, crop.withAge(0), 2); // harvest + replant, one visible motion
            return;
        }
        if (bare != null && depot != null) {
            for (int slot = 0; slot < depot.depot.getSlots(); slot++) {
                ItemStack s = depot.depot.getStackInSlot(slot);
                if (s.isEmpty() || !(s.getItem() instanceof net.minecraft.item.ItemSeeds)) continue;
                try {
                    net.minecraft.block.state.IBlockState plant =
                            ((net.minecraft.item.ItemSeeds) s.getItem()).getPlant(world, bare);
                    world.setBlockState(bare, plant, 2);
                    s.shrink(1);
                    depot.depot.setStackInSlot(slot, s.isEmpty() ? ItemStack.EMPTY : s);
                } catch (Throwable ignored) {}
                return;
            }
        }
    }

    /**
     * QUARRY dig — a BuildCraft-style descending pit worked one block per cycle, with SUPPLIED TOOLS:
     *   - requires a pickaxe stocked in the depot (no pickaxe = no mining; the tool wears + can break);
     *   - clears TOP-DOWN: mines the highest solid block in the worker's 3x3 column so the terrain
     *     flattens then sinks into a clean pit, but jumps to any ORE in range first;
     *   - a stone pickaxe won't touch obsidian/diamond-hardness (needs iron+), matching vanilla tiers;
     *   - carries the block's REAL drops to the depot. Bedrock/liquids skipped.
     */
    private static void workQuarry(WorldServer world, EntityCreature worker, TileEntityDistrictMarker depot) {
        // TOOLS SUPPLIED: find the best pickaxe in the depot. Without one, the quarry can't work.
        int pickSlot = bestPickaxeSlot(depot);
        if (pickSlot < 0) return;
        net.minecraft.item.ItemStack pick = depot.depot.getStackInSlot(pickSlot);
        int harvestLevel;
        try { harvestLevel = pick.getItem().getHarvestLevel(pick, "pickaxe", null, null); }
        catch (Throwable t) { harvestLevel = 0; }

        BlockPos feet = worker.getPosition();
        BlockPos ore = null, topRock = null;
        int bestY = Integer.MIN_VALUE;
        for (int dx = -1; dx <= 1 && ore == null; dx++) {
            for (int dz = -1; dz <= 1; dz++) {
                for (int dy = 3; dy >= -4; dy--) { // topmost-first per column
                    BlockPos p = feet.add(dx, dy, dz);
                    if (!world.isBlockLoaded(p, false) || world.isAirBlock(p)) continue;
                    net.minecraft.block.state.IBlockState st = world.getBlockState(p);
                    net.minecraft.block.material.Material mat = st.getMaterial();
                    if (st.getBlock() == net.minecraft.init.Blocks.BEDROCK || mat.isLiquid()) continue;
                    if (st.getBlockHardness(world, p) < 0) continue; // unbreakable
                    if (!canHarvest(world, p, st, harvestLevel)) continue; // wrong tool tier
                    if (st.getBlock() instanceof net.minecraft.block.BlockOre) { ore = p; break; }
                    if (mat == Material.ROCK) { if (p.getY() > bestY) { bestY = p.getY(); topRock = p; } break; }
                }
                if (ore != null) break;
            }
        }
        BlockPos target = ore != null ? ore : topRock;
        if (target == null) return;

        net.minecraft.block.state.IBlockState st = world.getBlockState(target);
        net.minecraft.util.NonNullList<ItemStack> drops = net.minecraft.util.NonNullList.create();
        try { st.getBlock().getDrops(drops, world, target, st, 0); } catch (Throwable ignored) {}
        world.setBlockState(target, net.minecraft.init.Blocks.AIR.getDefaultState(), 3);
        world.playEvent(2001, target, net.minecraft.block.Block.getStateId(st)); // break FX
        List<ItemStack> carried = CARRIED.computeIfAbsent(worker.getUniqueID(), u -> new ArrayList<>());
        for (ItemStack d : drops) if (!d.isEmpty()) carried.add(d);
        wearPickaxe(depot, pickSlot);
        if (VERBOSE) EpochRunnerMod.logger.info("[DistrictAI] " + worker.getName()
                + " quarried " + st.getBlock().getLocalizedName());
    }

    /** Best (highest harvest level) pickaxe slot in the depot, or -1. */
    private static int bestPickaxeSlot(TileEntityDistrictMarker depot) {
        if (depot == null) return -1;
        int best = -1, bestLvl = -1;
        for (int slot = 0; slot < depot.depot.getSlots(); slot++) {
            ItemStack s = depot.depot.getStackInSlot(slot);
            if (s.isEmpty() || !(s.getItem() instanceof net.minecraft.item.ItemPickaxe)) continue;
            int lvl;
            try { lvl = s.getItem().getHarvestLevel(s, "pickaxe", null, null); } catch (Throwable t) { lvl = 0; }
            if (lvl > bestLvl) { bestLvl = lvl; best = slot; }
        }
        return best;
    }

    private static boolean canHarvest(WorldServer world, BlockPos p,
                                      net.minecraft.block.state.IBlockState st, int harvestLevel) {
        try {
            if (!st.getBlock().getDefaultState().getMaterial().isToolNotRequired()
                    && st.getBlock().getHarvestLevel(st) > harvestLevel) return false;
        } catch (Throwable ignored) {}
        return true;
    }

    private static void wearPickaxe(TileEntityDistrictMarker depot, int slot) {
        ItemStack s = depot.depot.getStackInSlot(slot);
        if (s.isEmpty() || !s.getItem().isDamageable()) return;
        ItemStack worn = s.copy();
        worn.setItemDamage(worn.getItemDamage() + 1);
        depot.depot.setStackInSlot(slot, worn.getItemDamage() > worn.getMaxDamage() ? ItemStack.EMPTY : worn);
    }

    /**
     * WAREHOUSE organize loop: find a nearby container (NOT the warehouse depot) inside the district,
     * "open" it, and pull ONE stack into the worker's carried pile — the deposit run then consolidates
     * it into the warehouse depot. This is the visible "opening chests and moving stuff" when couriers
     * aren't out on deliveries. No-op when there's nothing loose to gather.
     */
    private static void workWarehouse(WorldServer world, EntityCreature worker, CivilMarker district) {
        BlockPos feet = worker.getPosition();
        for (int dx = -4; dx <= 4; dx++) {
            for (int dz = -4; dz <= 4; dz++) {
                for (int dy = -2; dy <= 2; dy++) {
                    BlockPos p = feet.add(dx, dy, dz);
                    if (p.equals(district.depotPos) || !world.isBlockLoaded(p, false)) continue;
                    net.minecraft.tileentity.TileEntity te = world.getTileEntity(p);
                    net.minecraftforge.items.IItemHandler inv = itemHandlerOf(te);
                    if (inv == null) continue;
                    for (int slot = 0; slot < inv.getSlots(); slot++) {
                        ItemStack ex = inv.extractItem(slot, 64, false);
                        if (!ex.isEmpty()) {
                            CARRIED.computeIfAbsent(worker.getUniqueID(), u -> new ArrayList<>()).add(ex);
                            // Chest open/close animation for the pickup (best-effort).
                            if (te instanceof net.minecraft.tileentity.TileEntityChest) {
                                world.addBlockEvent(p, te.getBlockType(), 1, 1);
                            }
                            return;
                        }
                    }
                }
            }
        }
    }

    /**
     * Tree-farm replant: if bare grass/dirt sits near the worker and the depot holds a SAPLING, plant
     * one (consuming it) so the wood district reseeds itself instead of clear-cutting to nothing.
     * Purely for sustainability + the look of a managed forest; the yield is still the output table.
     */
    private static void workLumberReplant(WorldServer world, BlockPos feet, TileEntityDistrictMarker depot) {
        if (depot == null) return;
        BlockPos bare = null;
        for (int dx = -3; dx <= 3 && bare == null; dx++) {
            for (int dz = -3; dz <= 3 && bare == null; dz++) {
                for (int dy = -1; dy <= 1; dy++) {
                    BlockPos g = feet.add(dx, dy, dz);
                    if (!world.isBlockLoaded(g, false)) continue;
                    net.minecraft.block.material.Material m = world.getBlockState(g).getMaterial();
                    if ((m == net.minecraft.block.material.Material.GRASS
                            || m == net.minecraft.block.material.Material.GROUND)
                            && world.isAirBlock(g.up()) && world.getLight(g.up()) >= 8) {
                        bare = g.up(); break;
                    }
                }
            }
        }
        if (bare == null) return;
        for (int slot = 0; slot < depot.depot.getSlots(); slot++) {
            ItemStack s = depot.depot.getStackInSlot(slot);
            if (s.isEmpty()) continue;
            net.minecraft.block.Block b = net.minecraft.block.Block.getBlockFromItem(s.getItem());
            if (!(b instanceof net.minecraft.block.BlockSapling)) continue;
            world.setBlockState(bare, b.getStateFromMeta(s.getMetadata()), 2);
            s.shrink(1);
            depot.depot.setStackInSlot(slot, s.isEmpty() ? ItemStack.EMPTY : s);
            return;
        }
    }

    /** IItemHandler view of a tile entity (capability first, then IInventory), or null. */
    private static net.minecraftforge.items.IItemHandler itemHandlerOf(net.minecraft.tileentity.TileEntity te) {
        if (te == null) return null;
        if (te instanceof TileEntityDistrictMarker) return null; // depots are managed, not scavenged
        if (te.hasCapability(net.minecraftforge.items.CapabilityItemHandler.ITEM_HANDLER_CAPABILITY, null)) {
            return te.getCapability(net.minecraftforge.items.CapabilityItemHandler.ITEM_HANDLER_CAPABILITY, null);
        }
        if (te instanceof net.minecraft.inventory.IInventory) {
            return new net.minecraftforge.items.wrapper.InvWrapper((net.minecraft.inventory.IInventory) te);
        }
        return null;
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
