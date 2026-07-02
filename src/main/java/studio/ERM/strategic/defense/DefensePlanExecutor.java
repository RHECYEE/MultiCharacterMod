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
            driveTo(world, best, slot);
        }

        // LEFTOVERS: reserves wait at the reserve areas during a siege; in peacetime they walk patrols.
        if (!pool.isEmpty()) {
            if (siege) {
                List<BlockPos> reserves = pointsOf(plan, DefenseMarker.RESERVE);
                if (reserves.isEmpty()) reserves = pointsOf(plan, DefenseMarker.RALLY);
                if (!reserves.isEmpty()) {
                    int i = 0;
                    for (EntityCreature d : pool) driveTo(world, d, reserves.get(i++ % reserves.size()));
                }
            } else {
                List<DefenseMarker> patrols = new ArrayList<>();
                for (DefenseMarker m : plan.markers) {
                    if (m.type == DefenseMarker.PATROL_ROUTE && m.points.size() >= 2) patrols.add(m);
                }
                int i = 0;
                for (EntityCreature d : pool) {
                    if (patrols.isEmpty()) break;
                    tickPatrol(world, d, patrols.get(i++ % patrols.size()));
                }
            }
        }
    }

    /** The player's AW2 combat NPCs near the plan (excluding strategic-map-owned entities). */
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
            ResourceLocation id = EntityList.getKey(c);
            String reg = (id != null) ? id.toString() : "";
            if (reg.contains("ancientwarfare") && reg.contains("combat")) out.add(c);
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
    private static void tickPatrol(WorldServer world, EntityCreature d, DefenseMarker route) {
        int idx = d.getEntityData().getInteger("erm_patrol_idx") % route.points.size();
        BlockPos wp = route.points.get(idx);
        double dd = d.getDistanceSq(wp.getX() + 0.5, d.posY, wp.getZ() + 0.5);
        if (dd < 16.0) {
            idx = (idx + 1) % route.points.size();
            d.getEntityData().setInteger("erm_patrol_idx", idx);
            wp = route.points.get(idx);
        }
        driveTo(world, d, wp);
    }

    /** Send a defender to a slot (surface-resolved); only re-path when it has strayed. */
    private static void driveTo(WorldServer world, EntityCreature d, BlockPos slot) {
        if (d == null || slot == null) return;
        try {
            BlockPos g = world.getTopSolidOrLiquidBlock(new BlockPos(slot.getX(), 64, slot.getZ()));
            double dd = d.getDistanceSq(slot.getX() + 0.5, g.getY(), slot.getZ() + 0.5);
            if (dd <= 6.25) return; // holding its position
            d.getNavigator().tryMoveToXYZ(slot.getX() + 0.5, g.getY(), slot.getZ() + 0.5, 1.05D);
        } catch (Throwable ignored) {}
    }
}
