package studio.ERM.strategic.defense;

import net.minecraft.entity.EntityCreature;
import net.minecraft.entity.EntityList;
import net.minecraft.util.ResourceLocation;
import net.minecraft.util.math.AxisAlignedBB;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.WorldServer;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import net.minecraftforge.fml.common.gameevent.TickEvent;
import studio.ERM.war.BattleManagers.core.BattleEngine;

import java.util.ArrayList;
import java.util.List;

/**
 * PHASE 2 — THE DEFENSIVE MILITARY AI. Executes the player's standing plan with whatever forces are
 * actually available; the player commands DOCTRINE, never individuals.
 *
 * Staffing rules (spec):
 *   - STRONGPOINTS are always occupied (highest priority, peace or war).
 *   - During a SIEGE, infantry spreads along the ACTIVE lines — the defensive lines normally, the
 *     FALLBACK lines once the player hits FALL BACK — with leftover troops waiting at RESERVE areas.
 *   - In PEACETIME, spare troops walk the PATROL ROUTES (each defender keeps its own circuit progress).
 *   - Under-staffed plans fill the most important positions first (strongpoints -> lines -> reserve).
 *
 * Defenders (v1): the player's AW2 combat NPCs (the crafted guard soldiers) near the plan. Vehicles /
 * AA / radar crews staff their positions in a later pass. Runs every 3s; navigation re-issued only
 * when a defender has strayed from its slot.
 */
public final class DefensePlanExecutor {

    private static final double GATHER_RANGE = 200.0; // how far from a marker we recruit defenders
    private static final double LINE_SPACING = 3.0;   // blocks between infantry slots on a line

    // The live ORDER BOOK: entity UUID -> the surface-resolved position it must hold. Written by the
    // executor every pass; read EVERY TICK by the injected EntityAIDefendPlanOrder task inside each
    // npc's own AI list (the actual hijack -- see that class). Transient by design.
    private static final java.util.Map<java.util.UUID, BlockPos> ORDERS =
            new java.util.concurrent.ConcurrentHashMap<>();

    // Debug-locked orders (the /war strat test forced-movement probe): never cleared or overwritten by
    // the plan pass, so the minimal test is isolated from assignment/cleanup bugs (protocol check J).
    private static final java.util.Set<java.util.UUID> DEBUG_LOCK =
            java.util.Collections.newSetFromMap(new java.util.concurrent.ConcurrentHashMap<>());

    private static int passCounter = 0;

    private DefensePlanExecutor() {}

    /** The standing order for an npc, or null when the plan has nothing for it (read by the AI task). */
    public static BlockPos orderFor(EntityCreature npc) {
        return npc == null ? null : ORDERS.get(npc.getUniqueID());
    }

    /** MINIMAL FORCED-MOVEMENT TEST: inject the task + write a locked order, bypassing the whole plan
     *  pipeline. If the npc walks, injection/task/uuid/mutex/navigator are fine and the bug is in
     *  slots/assignment/cleanup; if not, the core hijack is broken. */
    public static void debugOrder(WorldServer world, EntityCreature npc, BlockPos pos) {
        if (npc == null || pos == null) return;
        ensureOrderTask(npc);
        BlockPos g = sanitizeSlot(world, pos);
        ORDERS.put(npc.getUniqueID(), g);
        DEBUG_LOCK.add(npc.getUniqueID());
        studio.ERM.EpochRunnerMod.logger.info("[DefenseAI] DEBUG ORDER entity=" + npc.getUniqueID()
                + " -> " + g.getX() + " " + g.getY() + " " + g.getZ()
                + " dim(order)=" + world.provider.getDimension()
                + " dim(entity)=" + npc.world.provider.getDimension()
                + " | " + Aw2Npc.describe(npc));
    }

    public static int clearDebugOrders() {
        int n = DEBUG_LOCK.size();
        for (java.util.UUID u : DEBUG_LOCK) ORDERS.remove(u);
        DEBUG_LOCK.clear();
        return n;
    }

    /** Clear plan-issued orders but never the debug-locked probes. */
    private static void clearUnlocked() {
        ORDERS.keySet().removeIf(u -> !DEBUG_LOCK.contains(u));
    }

    /**
     * Slot sanitizer (protocol check H): a map-drawn slot can be inside a wall / on a fence / in liquid.
     * Resolve the surface, require a solid floor + 2 air blocks, and spiral-search up to 4 blocks for
     * the nearest valid stand if the exact slot is bad.
     */
    private static BlockPos sanitizeSlot(WorldServer world, BlockPos slot) {
        BlockPos best = null;
        outer:
        for (int r = 0; r <= 4; r++) {
            for (int dx = -r; dx <= r; dx++) {
                for (int dz = -r; dz <= r; dz++) {
                    if (Math.max(Math.abs(dx), Math.abs(dz)) != r) continue; // ring only
                    int x = slot.getX() + dx, z = slot.getZ() + dz;
                    BlockPos g = world.getTopSolidOrLiquidBlock(new BlockPos(x, 64, z));
                    try {
                        if (world.getBlockState(g.down()).getMaterial().isLiquid()) continue;
                        if (!world.getBlockState(g.down()).getMaterial().isSolid()) continue;
                        if (world.getBlockState(g).getMaterial().isSolid()) continue;
                        if (world.getBlockState(g.up()).getMaterial().isSolid()) continue;
                        best = new BlockPos(x, g.getY(), z);
                        break outer;
                    } catch (Throwable ignored) {}
                }
            }
        }
        if (best != null) return best;
        BlockPos g = world.getTopSolidOrLiquidBlock(new BlockPos(slot.getX(), 64, slot.getZ()));
        return new BlockPos(slot.getX(), g.getY(), slot.getZ());
    }

    @SubscribeEvent
    public static void onWorldTick(TickEvent.WorldTickEvent e) {
        if (e.phase != TickEvent.Phase.END) return;
        if (e.world == null || e.world.isRemote || !(e.world instanceof WorldServer)) return;
        WorldServer world = (WorldServer) e.world;
        if (world.getTotalWorldTime() % 60 != 0) return; // 3s cadence

        passCounter++;
        DefensePlanData plan = DefensePlanData.get(world);
        if (plan.markers.isEmpty()) {
            clearUnlocked();
            // Log this state UNCONDITIONALLY (throttled): "zero logs" must be impossible. If this line
            // prints while the player HAS drawn markers, the GUI -> C2SDefensePlanEdit -> DefensePlanData
            // pipeline is the broken stage, not the AI.
            if (passCounter % 10 == 0) {
                studio.ERM.EpochRunnerMod.logger.info("[Defense] executor alive; NO plan markers "
                        + "server-side (dim " + world.provider.getDimension() + ")"
                        + (DEBUG_LOCK.isEmpty() ? "" : "; " + DEBUG_LOCK.size() + " debug order(s) live"));
            }
            return;
        }

        boolean siege = false;
        try {
            BattleEngine engine = BattleEngine.get(world);
            siege = engine != null && engine.hasActiveBattle();
        } catch (Throwable ignored) {}

        List<EntityCreature> defenders = gatherDefenders(world, plan);
        if (defenders.isEmpty()) {
            clearUnlocked();
            if (passCounter % 10 == 0) logGatherDiagnostics(world, plan); // WHY is nobody conscripted?
            return;
        }

        // Fresh order book each pass (debug-locked probes excepted): dead/ungathered npcs drop off.
        clearUnlocked();

        // Build the slot list in PRIORITY ORDER with each marker contributing exactly its player-set
        // ASSIGNED count: strongpoints (clustered around the point), then the ACTIVE line family (spread
        // evenly along the polyline; the FALL BACK toggle picks primary vs fallback). Lines staff in
        // peacetime too (standing garrison).
        List<BlockPos> slots = new ArrayList<>();
        for (DefenseMarker m : plan.markers) {
            if (m.type == DefenseMarker.STRONGPOINT && !m.points.isEmpty()) addPointSlots(m, slots);
        }
        int lineType = plan.fallbackActive ? DefenseMarker.FALLBACK_LINE : DefenseMarker.LINE;
        for (DefenseMarker m : plan.markers) {
            if (m.type == lineType) addLineSlots(m, slots);
        }

        // GREEDY ASSIGNMENT: each slot takes the nearest unassigned defender (debug-locked npcs skipped).
        List<EntityCreature> pool = new ArrayList<>(defenders);
        pool.removeIf(d -> DEBUG_LOCK.contains(d.getUniqueID()));
        for (BlockPos slot : slots) {
            if (pool.isEmpty()) break;
            EntityCreature best = null;
            double bd = Double.MAX_VALUE;
            for (EntityCreature d : pool) {
                double dd = d.getDistanceSq(slot.getX() + 0.5, d.posY, slot.getZ() + 0.5);
                if (dd < bd) { bd = dd; best = d; }
            }
            pool.remove(best);
            driveTo(world, best, slot, plan);
        }

        // PATROLS: dedicated crews of exactly the assigned size (no longer just leftovers), each walking
        // its own circuit. Nearest defenders are drafted per route.
        for (DefenseMarker m : plan.markers) {
            if (m.type != DefenseMarker.PATROL_ROUTE || m.points.size() < 2) continue;
            for (int k = 0; k < m.assigned && !pool.isEmpty(); k++) {
                BlockPos anchor = m.points.get(0);
                EntityCreature best = null;
                double bd = Double.MAX_VALUE;
                for (EntityCreature d : pool) {
                    double dd = d.getDistanceSq(anchor.getX() + 0.5, d.posY, anchor.getZ() + 0.5);
                    if (dd < bd) { bd = dd; best = d; }
                }
                pool.remove(best);
                tickPatrol(world, best, m, plan);
            }
        }

        // LEFTOVERS: during a siege they wait at RESERVE (else RALLY) areas; in peacetime they are left
        // to their normal AW2 lives (no standing order) -- the plan only commands what it was given.
        if (!pool.isEmpty() && siege) {
            List<BlockPos> reserves = pointsOf(plan, DefenseMarker.RESERVE);
            if (reserves.isEmpty()) reserves = pointsOf(plan, DefenseMarker.RALLY);
            if (!reserves.isEmpty()) {
                int i = 0;
                for (EntityCreature d : pool) driveTo(world, d, reserves.get(i++ % reserves.size()), plan);
            }
        }

        // Heartbeat (every ~15s) so "the plan is/isn't commanding anyone" is visible in the log.
        if (passCounter % 5 == 0) {
            studio.ERM.EpochRunnerMod.logger.info("[Defense] pass: " + defenders.size() + " defender(s), "
                    + slots.size() + " priority slot(s), orders=" + ORDERS.size()
                    + (siege ? (plan.fallbackActive ? " [SIEGE/FALLBACK]" : " [SIEGE]") : " [peace]"));
        }
    }

    /**
     * WHY-EMPTY diagnostics (runs when a plan exists but ZERO defenders were conscripted): counts what
     * the sweep actually saw and prints a full classifier breakdown for up to 3 AW2 npcs, so the failed
     * stage (classifier resolution / instanceof / type string / range) is provable from the log alone.
     */
    private static void logGatherDiagnostics(WorldServer world, DefensePlanData plan) {
        int minX = Integer.MAX_VALUE, minZ = Integer.MAX_VALUE, maxX = Integer.MIN_VALUE, maxZ = Integer.MIN_VALUE;
        for (DefenseMarker m : plan.markers) {
            for (BlockPos p : m.points) {
                minX = Math.min(minX, p.getX()); maxX = Math.max(maxX, p.getX());
                minZ = Math.min(minZ, p.getZ()); maxZ = Math.max(maxZ, p.getZ());
            }
        }
        AxisAlignedBB box = new AxisAlignedBB(minX - GATHER_RANGE, 0, minZ - GATHER_RANGE,
                maxX + GATHER_RANGE, 255, maxZ + GATHER_RANGE);
        List<EntityCreature> all = world.getEntitiesWithinAABB(EntityCreature.class, box);
        int aw2 = 0, owned = 0, samples = 0;
        StringBuilder sb = new StringBuilder();
        for (EntityCreature c : all) {
            if (c == null || c.isDead) continue;
            boolean isAw2 = Aw2Npc.isAw2Npc(c)
                    || String.valueOf(EntityList.getKey(c)).contains("ancientwarfare");
            if (!isAw2) continue;
            aw2++;
            if (Aw2Npc.isPlayerOwnedCombat(c)) owned++;
            if (samples < 3) {
                samples++;
                sb.append("\n[Defense]   sample: ").append(Aw2Npc.describe(c));
            }
        }
        studio.ERM.EpochRunnerMod.logger.info("[Defense] 0 defenders conscripted: creaturesInBox="
                + all.size() + " aw2Npcs=" + aw2 + " playerOwnedCombat=" + owned
                + " markers=" + plan.markers.size() + sb);
    }

    /**
     * The player's ARMY near the plan: PLAYER-OWNED AW2 combat NPCs, classified by RUNTIME ENTITY STATE
     * ({@link Aw2Npc} — instanceof NpcPlayerOwned + getNpcType() "combat", which includes the bow
     * "archer" subtype since that's the same NpcCombat class). Never by spawner items or registry-name
     * strings; the registry heuristic remains only as a fallback when AW2's classes can't be resolved.
     * Faction NPCs (a rival's soldiers wandering past) are NEVER conscripted.
     */
    private static List<EntityCreature> gatherDefenders(WorldServer world, DefensePlanData plan) {
        // One bounding sweep around the plan's overall extent.
        int minX = Integer.MAX_VALUE, minZ = Integer.MAX_VALUE, maxX = Integer.MIN_VALUE, maxZ = Integer.MIN_VALUE;
        for (DefenseMarker m : plan.markers) {
            for (BlockPos p : m.points) {
                minX = Math.min(minX, p.getX()); maxX = Math.max(maxX, p.getX());
                minZ = Math.min(minZ, p.getZ()); maxZ = Math.max(maxZ, p.getZ());
            }
        }
        AxisAlignedBB box = new AxisAlignedBB(minX - GATHER_RANGE, 0, minZ - GATHER_RANGE,
                maxX + GATHER_RANGE, 255, maxZ + GATHER_RANGE);
        List<EntityCreature> out = new ArrayList<>();
        for (EntityCreature c : world.getEntitiesWithinAABB(EntityCreature.class, box)) {
            if (c == null || c.isDead) continue;
            if (c.getEntityData().hasKey("erm_strategic")) continue; // strategic-map units are not ours
            if (Aw2Npc.isPlayerOwnedCombat(c)) { out.add(c); continue; }
            // Fallback only when the classifier can't see AW2 at all (reflection failed): the old
            // registry-name heuristic, explicitly excluding anything the classifier DID identify.
            if (Aw2Npc.allegiance(c) == Aw2Npc.Allegiance.NONE && !Aw2Npc.isAw2Npc(c)) {
                ResourceLocation id = EntityList.getKey(c);
                String reg = (id != null) ? id.toString() : "";
                if (reg.contains("ancientwarfare") && reg.contains("combat")) out.add(c);
            }
        }
        return out;
    }

    /** Spread exactly the marker's ASSIGNED count of slots evenly along the whole polyline. */
    private static void addLineSlots(DefenseMarker m, List<BlockPos> slots) {
        if (m.points.size() < 2) return;
        // Total polyline length, then place `assigned` slots at even fractions along it.
        double total = 0;
        double[] seg = new double[m.points.size() - 1];
        for (int i = 0; i + 1 < m.points.size(); i++) {
            BlockPos a = m.points.get(i), b = m.points.get(i + 1);
            seg[i] = Math.hypot(b.getX() - a.getX(), b.getZ() - a.getZ());
            total += seg[i];
        }
        if (total < 1) { slots.add(m.points.get(0)); return; }
        int n = Math.max(1, Math.min(255, m.assigned));
        for (int s = 0; s < n; s++) {
            double f = (n == 1) ? 0.5 : (double) s / (n - 1);
            double target = f * total, walked = 0;
            for (int i = 0; i < seg.length; i++) {
                if (walked + seg[i] >= target || i == seg.length - 1) {
                    double lf = seg[i] <= 0 ? 0 : (target - walked) / seg[i];
                    BlockPos a = m.points.get(i), b = m.points.get(i + 1);
                    slots.add(new BlockPos(
                            (int) Math.round(a.getX() + (b.getX() - a.getX()) * lf), 0,
                            (int) Math.round(a.getZ() + (b.getZ() - a.getZ()) * lf)));
                    break;
                }
                walked += seg[i];
            }
        }
    }

    /** The marker's ASSIGNED count of slots clustered around a point marker (centre + expanding ring). */
    private static void addPointSlots(DefenseMarker m, List<BlockPos> slots) {
        BlockPos c = m.points.get(0);
        int n = Math.max(1, Math.min(255, m.assigned));
        slots.add(c);
        for (int k = 1; k < n; k++) {
            double a = k * 2.399963; // golden-angle spiral: even cluster, no overlaps
            double r = 1.5 + 0.9 * Math.sqrt(k);
            slots.add(new BlockPos((int) Math.round(c.getX() + Math.cos(a) * r), 0,
                    (int) Math.round(c.getZ() + Math.sin(a) * r)));
        }
    }

    /** Count the conscriptable defenders near the plan (used by the map's assign-all + X/Y readout). */
    public static int countDefenders(WorldServer world, DefensePlanData plan) {
        if (plan.markers.isEmpty()) return 0;
        return gatherDefenders(world, plan).size();
    }

    /** True if an entity holds a ranged weapon (bow or a Flan gun) -- ranged defenders engage from
     *  their station; melee may pursue a good bit further. */
    private static boolean isRanged(EntityCreature d) {
        try {
            net.minecraft.item.ItemStack main = d.getHeldItemMainhand();
            if (main.isEmpty()) return false;
            if (main.getItem() instanceof net.minecraft.item.ItemBow) return true;
            String cls = main.getItem().getClass().getName().toLowerCase();
            return cls.contains("itemgun") || cls.contains("flansmod");
        } catch (Throwable t) { return false; }
    }

    /** True if the entity stands inside any ENGAGEMENT ZONE (centre = point 0, radius = dist to point 1).
     *  Targets inside a zone are ALWAYS engageable at any range -- the "open up together" trigger. */
    private static boolean insideEngagementZone(DefensePlanData plan, net.minecraft.entity.Entity e) {
        for (DefenseMarker m : plan.markers) {
            if (m.type != DefenseMarker.ENGAGEMENT_ZONE || m.points.size() < 2) continue;
            BlockPos c = m.points.get(0);
            double r = Math.max(4.0, Math.min(64.0,
                    Math.hypot(m.points.get(1).getX() - c.getX(), m.points.get(1).getZ() - c.getZ())));
            double dx = e.posX - (c.getX() + 0.5), dz = e.posZ - (c.getZ() + 0.5);
            if (dx * dx + dz * dz <= r * r) return true;
        }
        return false;
    }

    private static List<BlockPos> pointsOf(DefensePlanData plan, int type) {
        List<BlockPos> out = new ArrayList<>();
        for (DefenseMarker m : plan.markers) if (m.type == type && !m.points.isEmpty()) out.add(m.points.get(0));
        return out;
    }

    /** Walk a defender one leg of its patrol circuit, keeping per-entity progress in its NBT. */
    private static void tickPatrol(WorldServer world, EntityCreature d, DefenseMarker route, DefensePlanData plan) {
        int idx = d.getEntityData().getInteger("erm_patrol_idx") % route.points.size();
        BlockPos wp = route.points.get(idx);
        double dd = d.getDistanceSq(wp.getX() + 0.5, d.posY, wp.getZ() + 0.5);
        if (dd < 16.0) {
            idx = (idx + 1) % route.points.size();
            d.getEntityData().setInteger("erm_patrol_idx", idx);
            wp = route.points.get(idx);
        }
        driveTo(world, d, wp, plan);
    }

    /**
     * FINAL SAY orders. The Military AI owns what every defender is doing at all times — this is the
     * player's means of controlling their army. This does NOT push a one-shot navigator call (AW2's own
     * AI overwrote that within a tick — the "they don't care about orders" failure); instead it (1)
     * injects our {@link EntityAIDefendPlanOrder} task INTO the npc's AI list once, where the vanilla
     * mutex system suspends AW2's movement tasks, and (2) writes the standing order the task reads
     * every tick. A defender may finish an ADJACENT fight (8 blocks); a distant chase is CANCELLED. A
     * defender making no progress across ~4 passes (unpathable line / destroyed position) is re-ordered
     * to the RALLY point to regroup, then reassigned.
     */
    private static void driveTo(WorldServer world, EntityCreature d, BlockPos slot, DefensePlanData plan) {
        if (d == null || slot == null) return;
        try {
            ensureOrderTask(d);

            // Combat override: engage while adhering to navigation. RANGED defenders may hold and shoot
            // anything within ~28; MELEE may pursue a good bit (~18) before being recalled. A target
            // standing inside an ENGAGEMENT ZONE is always fair game at any range ("open up together").
            net.minecraft.entity.EntityLivingBase tgt = d.getAttackTarget();
            if (tgt != null && !tgt.isDead) {
                double lim = isRanged(d) ? 28.0 * 28.0 : 18.0 * 18.0;
                if (d.getDistanceSq(tgt) >= lim && !insideEngagementZone(plan, tgt)) {
                    d.setAttackTarget(null);
                    d.getNavigator().clearPath();
                }
            }

            BlockPos resolved = sanitizeSlot(world, slot); // check H: never order into a wall/fence/liquid
            double dd = d.getDistanceSq(resolved.getX() + 0.5, resolved.getY(), resolved.getZ() + 0.5);
            if (dd <= 6.25) {
                d.getEntityData().setInteger("erm_def_stuck", 0); // on station; the task holds it here
                ORDERS.put(d.getUniqueID(), resolved);
                return;
            }

            // STUCK -> REGROUP: no meaningful progress toward the slot across ~4 passes (12s).
            double last = d.getEntityData().getDouble("erm_def_lastd");
            int stuck = d.getEntityData().getInteger("erm_def_stuck");
            stuck = (last > 0 && dd > last - 4.0) ? stuck + 1 : 0;
            d.getEntityData().setDouble("erm_def_lastd", dd);
            d.getEntityData().setInteger("erm_def_stuck", stuck);
            if (stuck >= 4) {
                BlockPos rally = regroupPoint(plan);
                if (rally != null) {
                    BlockPos rg = world.getTopSolidOrLiquidBlock(new BlockPos(rally.getX(), 64, rally.getZ()));
                    BlockPos rr = new BlockPos(rally.getX(), rg.getY(), rally.getZ());
                    ORDERS.put(d.getUniqueID(), rr); // regroup order instead of grinding the wall
                    if (d.getDistanceSq(rr.getX() + 0.5, rr.getY(), rr.getZ() + 0.5) < 25.0) {
                        d.getEntityData().setInteger("erm_def_stuck", 0);
                        d.getEntityData().setDouble("erm_def_lastd", 0);
                    }
                    return;
                }
            }

            ORDERS.put(d.getUniqueID(), resolved);
        } catch (Throwable ignored) {}
    }

    /** Inject our order-following AI task ONCE into the npc's own task list (priority 0, movement
     *  mutex) -- the actual hijack point. Scanned (not flagged) so it survives entity reloads. */
    private static void ensureOrderTask(EntityCreature d) {
        for (net.minecraft.entity.ai.EntityAITasks.EntityAITaskEntry e : d.tasks.taskEntries) {
            if (e.action instanceof EntityAIDefendPlanOrder) return;
        }
        d.tasks.addTask(0, new EntityAIDefendPlanOrder(d));
        studio.ERM.EpochRunnerMod.logger.info("[Defense] took command of " + d.getName()
                + " (" + Aw2Npc.fullType(d) + ")");
    }

    /** Where broken/unpathable units regroup: the first RALLY marker, else the plan's centroid. */
    private static BlockPos regroupPoint(DefensePlanData plan) {
        for (DefenseMarker m : plan.markers) {
            if (m.type == DefenseMarker.RALLY && !m.points.isEmpty()) return m.points.get(0);
        }
        long sx = 0, sz = 0; int n = 0;
        for (DefenseMarker m : plan.markers) {
            for (BlockPos p : m.points) { sx += p.getX(); sz += p.getZ(); n++; }
        }
        return n == 0 ? null : new BlockPos((int) (sx / n), 0, (int) (sz / n));
    }
}
