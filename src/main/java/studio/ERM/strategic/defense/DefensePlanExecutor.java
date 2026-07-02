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

    private DefensePlanExecutor() {}

    @SubscribeEvent
    public static void onWorldTick(TickEvent.WorldTickEvent e) {
        if (e.phase != TickEvent.Phase.END) return;
        if (e.world == null || e.world.isRemote || !(e.world instanceof WorldServer)) return;
        WorldServer world = (WorldServer) e.world;
        if (world.getTotalWorldTime() % 60 != 0) return; // 3s cadence

        DefensePlanData plan = DefensePlanData.get(world);
        if (plan.markers.isEmpty()) return;

        boolean siege = false;
        try {
            BattleEngine engine = BattleEngine.get(world);
            siege = engine != null && engine.hasActiveBattle();
        } catch (Throwable ignored) {}

        List<EntityCreature> defenders = gatherDefenders(world, plan);
        if (defenders.isEmpty()) return;

        // Build the slot list in PRIORITY ORDER: strongpoints first, then the active lines (war only),
        // so an under-staffed plan fills what matters most.
        List<BlockPos> slots = new ArrayList<>();
        for (DefenseMarker m : plan.markers) {
            if (m.type == DefenseMarker.STRONGPOINT && !m.points.isEmpty()) slots.add(m.points.get(0));
        }
        if (siege) {
            int lineType = plan.fallbackActive ? DefenseMarker.FALLBACK_LINE : DefenseMarker.LINE;
            for (DefenseMarker m : plan.markers) {
                if (m.type == lineType) addLineSlots(m, slots);
            }
        }

        // GREEDY ASSIGNMENT: each slot takes the nearest unassigned defender.
        List<EntityCreature> pool = new ArrayList<>(defenders);
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

        // LEFTOVERS: reserves wait at the reserve areas during a siege; in peacetime they walk patrols.
        if (!pool.isEmpty()) {
            if (siege) {
                List<BlockPos> reserves = pointsOf(plan, DefenseMarker.RESERVE);
                if (reserves.isEmpty()) reserves = pointsOf(plan, DefenseMarker.RALLY);
                if (!reserves.isEmpty()) {
                    int i = 0;
                    for (EntityCreature d : pool) driveTo(world, d, reserves.get(i++ % reserves.size()), plan);
                }
            } else {
                List<DefenseMarker> patrols = new ArrayList<>();
                for (DefenseMarker m : plan.markers) {
                    if (m.type == DefenseMarker.PATROL_ROUTE && m.points.size() >= 2) patrols.add(m);
                }
                int i = 0;
                for (EntityCreature d : pool) {
                    if (patrols.isEmpty()) break;
                    tickPatrol(world, d, patrols.get(i++ % patrols.size()), plan);
                }
            }
        }
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

    /** Spread infantry slots along a polyline every ~{@link #LINE_SPACING} blocks. */
    private static void addLineSlots(DefenseMarker m, List<BlockPos> slots) {
        for (int i = 0; i + 1 < m.points.size(); i++) {
            BlockPos a = m.points.get(i), b = m.points.get(i + 1);
            double dx = b.getX() - a.getX(), dz = b.getZ() - a.getZ();
            double len = Math.sqrt(dx * dx + dz * dz);
            int n = Math.max(1, (int) (len / LINE_SPACING));
            for (int s = 0; s <= n; s++) {
                double f = (double) s / n;
                slots.add(new BlockPos((int) Math.round(a.getX() + dx * f), 0,
                        (int) Math.round(a.getZ() + dz * f)));
            }
        }
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
     * FINAL SAY movement. The Military AI owns what every defender is doing at all times — this is the
     * player's means of controlling their army. A defender may finish an ADJACENT fight (target within
     * 8 blocks); a distant chase is CANCELLED and the plan's order reasserted. A defender that cannot
     * close on its slot across several passes (unpathable line / destroyed position) REGROUPS at the
     * rally point instead of wandering, and gets reassigned from there.
     */
    private static void driveTo(WorldServer world, EntityCreature d, BlockPos slot, DefensePlanData plan) {
        if (d == null || slot == null) return;
        try {
            // Combat override: allow the close fight, cancel the distant chase.
            net.minecraft.entity.EntityLivingBase tgt = d.getAttackTarget();
            if (tgt != null && !tgt.isDead) {
                if (d.getDistanceSq(tgt) < 64.0) return; // finish the adjacent fight
                d.setAttackTarget(null);                  // the plan overrides the chase
                d.getNavigator().clearPath();
            }

            BlockPos g = world.getTopSolidOrLiquidBlock(new BlockPos(slot.getX(), 64, slot.getZ()));
            double dd = d.getDistanceSq(slot.getX() + 0.5, g.getY(), slot.getZ() + 0.5);
            if (dd <= 6.25) {
                d.getEntityData().setInteger("erm_def_stuck", 0); // on station
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
                    d.getNavigator().tryMoveToXYZ(rally.getX() + 0.5, rg.getY(), rally.getZ() + 0.5, 1.05D);
                    // Reaching the regroup point clears the counter so reassignment can retry the line.
                    if (d.getDistanceSq(rally.getX() + 0.5, rg.getY(), rally.getZ() + 0.5) < 25.0) {
                        d.getEntityData().setInteger("erm_def_stuck", 0);
                        d.getEntityData().setDouble("erm_def_lastd", 0);
                    }
                    return;
                }
            }

            d.getNavigator().tryMoveToXYZ(slot.getX() + 0.5, g.getY(), slot.getZ() + 0.5, 1.05D);
        } catch (Throwable ignored) {}
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
