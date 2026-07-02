package studio.ERM.strategic;

import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.util.text.TextComponentString;
import net.minecraft.util.text.TextFormatting;
import net.minecraft.world.WorldServer;
import net.minecraftforge.event.entity.EntityJoinWorldEvent;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import net.minecraftforge.fml.common.gameevent.TickEvent;
import studio.ERM.EpochRunnerMod;
import studio.ERM.war.BattleManagers.entities.EntitySoldier;

import java.util.ArrayList;
import java.util.UUID;

/**
 * PHASE 2 — the strategic simulation TICK LOOP (Base module).
 *
 * Once a second, for every strategic object in the world:
 *   UNLOADED  -> advance it along its route by pure math (no chunks touched); if a player comes
 *                within {@link #MATERIALIZE_RANGE}, materialize it mid-task, facing its travel
 *                direction.
 *   LOADED    -> steer its live entities toward the current waypoint, recapture position/strength
 *                (real casualties flow back to the map); when the nearest player leaves
 *                {@link #DEMATERIALIZE_RANGE}, capture state and despawn. Hysteresis between the two
 *                ranges stops thrashing at the boundary, and demat range < chunk-unload distance so
 *                entities are still reachable when we capture them.
 *
 * Also runs the ORPHAN SWEEP: any strategic-tagged soldier whose object no longer claims it (stale
 * chunk-save after a crash/reload, or a wiped object) is silently removed on chunk load — the map is
 * the single source of truth, so the object re-materializes fresh instead of duplicating.
 *
 * Registered on the Forge event bus in EpochRunnerMod.init() (static handlers -> register the CLASS).
 */
public final class StrategicSimulator {

    /** Debug chat to the nearest player on every materialize/dematerialize/destroyed event. */
    public static boolean DEBUG = true;

    private static final double MATERIALIZE_RANGE = 100.0;
    private static final double DEMATERIALIZE_RANGE = 140.0;

    private StrategicSimulator() {}

    @SubscribeEvent
    public static void onWorldTick(TickEvent.WorldTickEvent e) {
        if (e.phase != TickEvent.Phase.END) return;
        if (e.world == null || e.world.isRemote || !(e.world instanceof WorldServer)) return;
        WorldServer world = (WorldServer) e.world;
        if (world.getTotalWorldTime() % 20 != 0) return; // strategic tick = 1/second

        // TRAFFIC GENERATION: keep each nearby rival city's level-scaled quota of patrols/traders topped
        // up (gently, one per type per pass). Runs on its own slower cadence.
        int interval = Math.max(10, studio.ERM.war.config.WarLevelsConfig.trafficIntervalSeconds());
        if (world.getTotalWorldTime() % (interval * 20L) == 0) {
            try { StrategicTrafficManager.ensure(world); }
            catch (Throwable t) { EpochRunnerMod.logger.warn("[Strategic] traffic ensure failed: " + t); }
        }

        StrategicMapData data = StrategicMapData.get(world);
        if (data.objects.isEmpty()) return;

        boolean dirty = false;
        for (StrategicObject o : new ArrayList<>(data.objects.values())) {
            try {
                double playerDist = nearestPlayerHorizDist(world, o.x, o.z);
                if (!o.materialized) {
                    o.tickUnloaded(1.0);
                    if (playerDist <= MATERIALIZE_RANGE) {
                        o.materialize(world);
                        EpochRunnerMod.logger.info("[Strategic] MATERIALIZE " + o.label() + " @ "
                                + (int) o.x + "," + (int) o.z);
                        debugChat(world, o, TextFormatting.DARK_AQUA + "[Strategic] " + o.label()
                                + " materialized at " + (int) o.x + ", " + (int) o.z);
                    }
                } else {
                    if (playerDist > DEMATERIALIZE_RANGE) {
                        o.dematerialize(world);
                        EpochRunnerMod.logger.info("[Strategic] DEMATERIALIZE " + o.label() + " @ "
                                + (int) o.x + "," + (int) o.z + " (back to map)");
                        debugChat(world, o, TextFormatting.DARK_GRAY + "[Strategic] " + o.label()
                                + " dematerialized -> map");
                    } else {
                        o.driveLoaded(world);
                        if (o.strength <= 0) {
                            data.remove(o.id);
                            EpochRunnerMod.logger.info("[Strategic] DESTROYED " + o.label());
                            debugChat(world, o, TextFormatting.RED + "[Strategic] " + o.label()
                                    + " wiped out — removed from the map");
                        }
                    }
                }
                dirty = true;
            } catch (Throwable t) {
                EpochRunnerMod.logger.warn("[Strategic] tick failed for " + o.typeId() + ": " + t);
            }
        }
        if (dirty) data.markDirty();
    }

    /**
     * ORPHAN SWEEP. A strategic-tagged soldier joining the world (chunk load) that its object does not
     * currently claim is a stale duplicate (chunk-saved before a reload, or its object was destroyed).
     * Remove it silently — the strategic map is the single source of truth.
     */
    @SubscribeEvent
    public static void onEntityJoin(EntityJoinWorldEvent e) {
        if (e.getWorld() == null || e.getWorld().isRemote) return;
        // ANY strategic-tagged living entity (soldier, trader, cart mule, AW2 npc) is subject to the
        // sweep -- the map is the single source of truth for all of them.
        if (!(e.getEntity() instanceof net.minecraft.entity.EntityLiving)) return;
        net.minecraft.entity.EntityLiving s = (net.minecraft.entity.EntityLiving) e.getEntity();
        String tag = s.getEntityData().getString("erm_strategic");
        if (tag == null || tag.isEmpty()) return;
        try {
            UUID oid = UUID.fromString(tag);
            StrategicMapData data = StrategicMapData.get(e.getWorld());
            StrategicObject o = data.objects.get(oid);
            boolean claimed = o != null && o.materialized && o.entityIds.contains(s.getUniqueID());
            if (!claimed) {
                s.setDead();
            }
        } catch (Throwable ignored) {}
    }

    private static double nearestPlayerHorizDist(WorldServer world, double x, double z) {
        double best = Double.MAX_VALUE;
        for (EntityPlayer p : world.playerEntities) {
            if (p == null || p.isDead || p.isSpectator()) continue;
            double dx = p.posX - x, dz = p.posZ - z;
            double d = dx * dx + dz * dz;
            if (d < best) best = d;
        }
        return best == Double.MAX_VALUE ? Double.MAX_VALUE : Math.sqrt(best);
    }

    private static void debugChat(WorldServer world, StrategicObject o, String msg) {
        if (!DEBUG) return;
        EntityPlayer p = null;
        double best = Double.MAX_VALUE;
        for (EntityPlayer q : world.playerEntities) {
            if (q == null || q.isDead) continue;
            double dx = q.posX - o.x, dz = q.posZ - o.z;
            double d = dx * dx + dz * dz;
            if (d < best) { best = d; p = q; }
        }
        if (p != null && best < 200 * 200) p.sendMessage(new TextComponentString(msg));
    }
}
