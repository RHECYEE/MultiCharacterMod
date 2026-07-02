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

    private StrategicSimulator() {}

    /** Materialize range = the player's actual RENDER DISTANCE (server view distance in blocks). */
    private static double materializeRange(WorldServer world) {
        try {
            return Math.max(64.0, world.getMinecraftServer().getPlayerList().getViewDistance() * 16.0);
        } catch (Throwable t) {
            return 160.0;
        }
    }

    @SubscribeEvent
    public static void onWorldTick(TickEvent.WorldTickEvent e) {
        if (e.phase != TickEvent.Phase.END) return;
        if (e.world == null || e.world.isRemote || !(e.world instanceof WorldServer)) return;
        WorldServer world = (WorldServer) e.world;
        StrategicMapData data = StrategicMapData.get(world);

        // MATERIALIZED objects drive EVERY tick (they're the few near the player): smooth cart towing,
        // waypoint bookkeeping, march-order refresh. Their own nav calls throttle internally.
        if (world.getTotalWorldTime() % 20 != 0) {
            for (StrategicObject o : new ArrayList<>(data.objects.values())) {
                if (!o.materialized) continue;
                try { o.driveLoaded(world); } catch (Throwable ignored) {}
            }
            return;
        }

        // ── 1/second STRATEGIC PASS ─────────────────────────────────────────────────────────────

        // TRAFFIC GENERATION: keep each nearby rival city's level-scaled quota of patrols/traders topped
        // up (gently, one per type per pass). Runs on its own slower cadence.
        int interval = Math.max(10, studio.ERM.war.config.WarLevelsConfig.trafficIntervalSeconds());
        if (world.getTotalWorldTime() % (interval * 20L) == 0) {
            try { StrategicTrafficManager.ensure(world); }
            catch (Throwable t) { EpochRunnerMod.logger.warn("[Strategic] traffic ensure failed: " + t); }
        }

        // MAP SYNC: push the strategic snapshot to every player's tactical map every 2s (tiny packet;
        // sent even when empty so stale client markers clear).
        if (world.getTotalWorldTime() % 40 == 0) {
            try { syncToPlayers(world, data); } catch (Throwable ignored) {}
        }

        if (data.objects.isEmpty()) return;

        double matRange = materializeRange(world);
        double dematRange = matRange + 32.0; // hysteresis just past the render horizon

        boolean dirty = false;
        for (StrategicObject o : new ArrayList<>(data.objects.values())) {
            try {
                double playerDist = nearestPlayerHorizDist(world, o.x, o.z);
                if (!o.materialized) {
                    o.tickUnloaded(1.0);
                    if (playerDist <= matRange) {
                        o.materialize(world);
                        EpochRunnerMod.logger.info("[Strategic] MATERIALIZE " + o.label() + " @ "
                                + (int) o.x + "," + (int) o.z);
                        debugChat(world, o, TextFormatting.DARK_AQUA + "[Strategic] " + o.label()
                                + " materialized at " + (int) o.x + ", " + (int) o.z);
                    }
                } else {
                    if (playerDist > dematRange) {
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

    /** Build + send the strategic snapshot to every player in this world: traffic objects, LIVE unit
     *  dots (friendly = the player's AW2 army; red = the besiegers), and the siege-camp alert. */
    private static void syncToPlayers(WorldServer world, StrategicMapData data) {
        java.util.List<studio.ERM.war.map.net.S2CStrategicSync.Data> snap = new ArrayList<>();
        for (StrategicObject o : data.objects.values()) {
            studio.ERM.war.map.net.S2CStrategicSync.Data d = new studio.ERM.war.map.net.S2CStrategicSync.Data();
            d.type = o.typeId();
            d.label = o.label();
            d.x = (int) o.x;
            d.z = (int) o.z;
            d.live = o.materialized;
            d.strength = o.strength;
            snap.add(d);
        }

        // LIVE UNIT DOTS around each player (capped; later narrowed to KNOWN/SEEN enemies).
        java.util.List<Integer> fd = new ArrayList<>(), ed = new ArrayList<>();
        java.util.Set<Integer> seen = new java.util.HashSet<>();
        for (EntityPlayer p : world.playerEntities) {
            if (p == null || p.isDead) continue;
            net.minecraft.util.math.AxisAlignedBB box = p.getEntityBoundingBox().grow(220, 128, 220);
            for (net.minecraft.entity.EntityLivingBase e
                    : world.getEntitiesWithinAABB(net.minecraft.entity.EntityLivingBase.class, box)) {
                if (e == null || e.isDead || e instanceof EntityPlayer) continue;
                if (!seen.add(e.getEntityId())) continue;
                if (fd.size() + ed.size() >= 700) break;
                if (studio.ERM.strategic.defense.Aw2Npc.isPlayerOwnedCombat(e)) {
                    fd.add((int) e.posX); fd.add((int) e.posZ);
                } else if (isSiegeHostile(e)) {
                    ed.add((int) e.posX); ed.add((int) e.posZ);
                }
            }
        }

        // SIEGE ALERT: the active battle's site ("enemy camp gathering here").
        boolean siege = false; int sx = 0, sz = 0;
        try {
            studio.ERM.war.BattleManagers.core.BattleEngine engine =
                    studio.ERM.war.BattleManagers.core.BattleEngine.get(world);
            net.minecraft.util.math.BlockPos site = (engine != null) ? engine.getActiveSite() : null;
            if (site != null) { siege = true; sx = site.getX(); sz = site.getZ(); }
        } catch (Throwable ignored) {}

        studio.ERM.war.map.net.S2CStrategicSync pkt = new studio.ERM.war.map.net.S2CStrategicSync(
                snap, toIntArray(fd), toIntArray(ed), siege, sx, sz);
        for (EntityPlayer p : world.playerEntities) {
            if (p instanceof net.minecraft.entity.player.EntityPlayerMP) {
                studio.ERM.war.map.net.TacticalWarMapNetwork.sendTo(pkt,
                        (net.minecraft.entity.player.EntityPlayerMP) p);
            }
        }
    }

    /** A besieging-army unit for the red map dots: empire-team soldiers/pilots + hostile faction npcs. */
    private static boolean isSiegeHostile(net.minecraft.entity.EntityLivingBase e) {
        if (e instanceof EntitySoldier) {
            try { return "empire".equalsIgnoreCase(((EntitySoldier) e).getTeam_()); } catch (Throwable t) { return false; }
        }
        if (e instanceof studio.ERM.war.vehicle.EntityAIPilot) {
            try { return "empire".equalsIgnoreCase(((studio.ERM.war.vehicle.EntityAIPilot) e).getMcmTeam()); }
            catch (Throwable t) { return false; }
        }
        if (e instanceof studio.ERM.war.air.EntityGhostAircraft) return true;
        // Hostile AW2 faction npcs (the empire faction is the invader in this pack).
        String fac = studio.ERM.strategic.defense.Aw2Npc.faction(e);
        return !fac.isEmpty();
    }

    private static int[] toIntArray(java.util.List<Integer> list) {
        int[] a = new int[list.size()];
        for (int i = 0; i < a.length; i++) a[i] = list.get(i);
        return a;
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
