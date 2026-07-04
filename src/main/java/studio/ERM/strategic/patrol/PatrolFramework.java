package studio.ERM.strategic.patrol;

import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityLiving;
import net.minecraft.entity.EntityList;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.util.ResourceLocation;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.WorldServer;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import net.minecraftforge.fml.common.gameevent.TickEvent;
import studio.ERM.EpochRunnerMod;
import studio.ERM.strategic.civil.DistrictRegistry;
import studio.ERM.strategic.patrol.PatrolConfig.PatrolDef;

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
 * THE GENERIC PATROL ENGINE — one shared spawner/driver for every roaming group in the world:
 * wild pre-civilization threats (RIVAL level 0), nation-state patrols, rival patrols, convoy
 * escorts, scout parties, AW2 medieval formations, late-game Flan's infantry. Data lives in
 * {@link PatrolConfig}; this class owns the lifecycle:
 *
 *   SPAWN    — per-def attempt timer; ring around a random player (never closer than min, never
 *              past max); land/water/air spot validation; biome/dimension/rival-level gates;
 *              per-def + global active caps. Unresolvable entity ids skip the def (log once).
 *   WANDER   — each patrol shares a travel WAYPOINT ~80-160 blocks out; loaded land members are
 *              nudged along it (clamped legs); reaching it rolls the next. Water/air members keep
 *              their own AI (they roam naturally).
 *   CLEANUP  — dead members drop off; empty patrols close; members beyond despawnDistanceFactor x
 *              maxPlayerDist from every player despawn; patrols unseen for ~20 min are forgotten.
 *
 * All state is transient (a restart clears live patrols — the world re-rolls). Members carry the
 * "erm_patrol" entity tag + patrol uid in their NBT so other systems can recognize them.
 */
public final class PatrolFramework {

    private static final int PASS_INTERVAL = 100;       // 5s lifecycle cadence
    private static final long STALE_TICKS = 24000;      // forget patrols unseen this long
    public static boolean VERBOSE = false;

    private PatrolFramework() {}

    private static final Random RNG = new Random();
    private static final Map<Integer, Patrol> ACTIVE = new ConcurrentHashMap<>();
    private static final Map<String, Long> NEXT_TRY = new HashMap<>();
    private static final Set<String> BAD_DEFS = new HashSet<>(); // unresolvable entities, logged once
    private static int nextUid = 1;
    private static int tickCounter = 0;

    private static final class Patrol {
        final int uid, dim;
        final PatrolDef def;
        final List<UUID> members = new ArrayList<>();
        BlockPos waypoint;
        long lastSeen;
        Patrol(int uid, int dim, PatrolDef def) { this.uid = uid; this.dim = dim; this.def = def; }
    }

    /** Is this entity a member of any live patrol? (other systems: targeting, intel, missions) */
    public static boolean isPatrolMember(Entity e) {
        return e != null && e.getTags().contains("erm_patrol");
    }

    public static int activeCount() {
        return ACTIVE.size();
    }

    public static void resetTransients() {
        ACTIVE.clear();
        NEXT_TRY.clear();
    }

    /**
     * Spawn one patrol of a named def anchored to a POSITION instead of a player — how nation
     * states, camps and missions dispatch from their own settlements. Honors the def's per-def
     * cap; returns false when the def is unknown/capped/no valid spot.
     */
    public static boolean spawnAt(WorldServer world, String defName, BlockPos anchor) {
        PatrolDef def = null;
        for (PatrolDef d : PatrolConfig.data.patrols) if (d.name.equals(defName)) { def = d; break; }
        if (def == null || BAD_DEFS.contains(def.name)) return false;
        int dim = world.provider.getDimension();
        int active = 0;
        for (Patrol p : ACTIVE.values()) if (p.dim == dim && p.def == def) active++;
        if (active >= def.maxActive) return false;
        BlockPos spot = findSpawnSpotNear(world, def, anchor, 16, 48);
        if (spot == null) return false;
        spawnPatrol(world, dim, def, spot);
        return true;
    }

    // ==================================================================
    // THE PASS
    // ==================================================================

    @SubscribeEvent
    public static void onWorldTick(TickEvent.WorldTickEvent e) {
        if (e.phase != TickEvent.Phase.END || e.side.isClient() || !(e.world instanceof WorldServer)) return;
        if (++tickCounter % PASS_INTERVAL != 0) return;
        WorldServer world = (WorldServer) e.world;
        int dim = world.provider.getDimension();

        maintain(world, dim);
        trySpawns(world, dim);
    }

    // ==================================================================
    // LIFECYCLE — wander, despawn, stale cleanup
    // ==================================================================

    private static void maintain(WorldServer world, int dim) {
        long now = world.getTotalWorldTime();
        double despawnDist = 0;
        for (Patrol p : new ArrayList<>(ACTIVE.values())) {
            if (p.dim != dim) continue;
            despawnDist = p.def.maxPlayerDist * PatrolConfig.data.despawnDistanceFactor;

            List<Entity> loaded = new ArrayList<>();
            for (UUID id : new ArrayList<>(p.members)) {
                Entity ent = world.getEntityFromUuid(id);
                if (ent != null && ent.isDead) { p.members.remove(id); continue; }
                if (ent != null) loaded.add(ent);
            }
            if (p.members.isEmpty()) { ACTIVE.remove(p.uid); continue; }
            if (!loaded.isEmpty()) p.lastSeen = now;
            else if (now - p.lastSeen > STALE_TICKS) { ACTIVE.remove(p.uid); continue; } // unloaded too long

            // DESPAWN: the whole visible group is far beyond every player -> fold the patrol.
            if (!loaded.isEmpty() && !world.playerEntities.isEmpty()) {
                boolean anyNear = false;
                for (Entity ent : loaded) {
                    for (EntityPlayer pl : world.playerEntities) {
                        if (ent.getDistanceSq(pl) < despawnDist * despawnDist) { anyNear = true; break; }
                    }
                    if (anyNear) break;
                }
                if (!anyNear) {
                    for (Entity ent : loaded) ent.setDead();
                    ACTIVE.remove(p.uid);
                    if (VERBOSE) EpochRunnerMod.logger.info("[Patrol] " + p.def.name + " #" + p.uid + " despawned (far)");
                    continue;
                }
            }

            // WANDER: shared waypoint; land members get nudged along, water/air roam on their own AI.
            if (!"land".equalsIgnoreCase(p.def.type) || loaded.isEmpty()) continue;
            Entity lead = loaded.get(0);
            if (p.waypoint == null
                    || lead.getDistanceSq(p.waypoint.getX(), lead.posY, p.waypoint.getZ()) < 64) {
                double ang = RNG.nextDouble() * Math.PI * 2;
                int dist = 80 + RNG.nextInt(81);
                BlockPos probe = new BlockPos(lead.posX + Math.cos(ang) * dist, 64,
                        lead.posZ + Math.sin(ang) * dist);
                p.waypoint = world.isBlockLoaded(probe, false)
                        ? world.getTopSolidOrLiquidBlock(probe) : probe;
            }
            for (Entity ent : loaded) {
                if (!(ent instanceof EntityLiving)) continue;
                EntityLiving liv = (EntityLiving) ent;
                if (liv.getAttackTarget() != null || !liv.getNavigator().noPath()) continue;
                double ox = (RNG.nextDouble() - 0.5) * 8, oz = (RNG.nextDouble() - 0.5) * 8;
                // Clamped legs, same discipline as every other far-order mover.
                double dx = p.waypoint.getX() + ox - liv.posX, dz = p.waypoint.getZ() + oz - liv.posZ;
                double dH = Math.sqrt(dx * dx + dz * dz);
                if (dH > 14.0) {
                    liv.getNavigator().tryMoveToXYZ(liv.posX + dx / dH * 12.0, liv.posY,
                            liv.posZ + dz / dH * 12.0, 0.85D);
                } else {
                    liv.getNavigator().tryMoveToXYZ(p.waypoint.getX() + ox, p.waypoint.getY(),
                            p.waypoint.getZ() + oz, 0.85D);
                }
            }
        }
    }

    // ==================================================================
    // SPAWNING
    // ==================================================================

    private static void trySpawns(WorldServer world, int dim) {
        if (world.playerEntities.isEmpty()) return;
        long now = world.getTotalWorldTime();
        int rivalLevel = DistrictRegistry.rivalLevel(world);

        int worldActive = 0;
        for (Patrol p : ACTIVE.values()) if (p.dim == dim) worldActive++;
        if (worldActive >= PatrolConfig.data.globalMaxActive) return;

        for (PatrolDef def : PatrolConfig.data.patrols) {
            if (BAD_DEFS.contains(def.name)) continue;
            if (rivalLevel < def.rivalLevelMin || rivalLevel > def.rivalLevelMax) continue;
            int wantDim = def.dimensions.isEmpty() ? 0 : -999;
            if (def.dimensions.isEmpty() ? dim != wantDim : !def.dimensions.contains(dim)) continue;

            Long next = NEXT_TRY.get(def.name);
            if (next != null && now < next) continue;
            NEXT_TRY.put(def.name, now + def.spawnIntervalTicks);

            int active = 0;
            for (Patrol p : ACTIVE.values()) if (p.dim == dim && p.def == def) active++;
            if (active >= def.maxActive) continue;
            if (RNG.nextInt(Math.max(1, 4 - Math.min(3, def.weight))) != 0 && def.weight < 4) {
                // weight softly scales the hit chance (1 = 25%, 2 = 33%, 3 = 50%, 4+ = always)
                continue;
            }

            BlockPos spot = findSpawnSpot(world, def);
            if (spot == null) continue;
            spawnPatrol(world, dim, def, spot);
            if (++worldActive >= PatrolConfig.data.globalMaxActive) return;
        }
    }

    /** A valid spot on the ring around a random player, honoring type + biome filters. */
    private static BlockPos findSpawnSpot(WorldServer world, PatrolDef def) {
        EntityPlayer anchor = world.playerEntities.get(RNG.nextInt(world.playerEntities.size()));
        return findSpawnSpotNear(world, def, anchor.getPosition(), def.minPlayerDist, def.maxPlayerDist);
    }

    /** Ring search around an arbitrary anchor (players for wild defs, settlements for nations). */
    private static BlockPos findSpawnSpotNear(WorldServer world, PatrolDef def, BlockPos center,
                                              int minDist, int maxDist) {
        for (int attempt = 0; attempt < 8; attempt++) {
            double ang = RNG.nextDouble() * Math.PI * 2;
            double dist = minDist + RNG.nextDouble() * Math.max(1, maxDist - minDist);
            BlockPos probe = new BlockPos(center.getX() + Math.cos(ang) * dist, 64,
                    center.getZ() + Math.sin(ang) * dist);
            if (!world.isBlockLoaded(probe, false)) continue;

            if (!def.biomeContains.isEmpty()) {
                String biome;
                try { biome = world.getBiome(probe).getBiomeName().toLowerCase(); }
                catch (Throwable t) { biome = ""; }
                boolean ok = false;
                for (String b : def.biomeContains) if (biome.contains(b.toLowerCase())) { ok = true; break; }
                if (!ok) continue;
            }

            BlockPos stand = world.getTopSolidOrLiquidBlock(probe);
            boolean surfaceLiquid = world.getBlockState(stand.down()).getMaterial().isLiquid();
            switch (def.type.toLowerCase()) {
                case "water":
                    if (surfaceLiquid) return stand.down(2);
                    break;
                case "air":
                    if (world.isAirBlock(stand.up(10))) return stand.up(10);
                    break;
                default: // land: solid dry footing + headroom
                    if (!surfaceLiquid && world.isAirBlock(stand) && world.isAirBlock(stand.up())) return stand;
                    break;
            }
        }
        return null;
    }

    private static void spawnPatrol(WorldServer world, int dim, PatrolDef def, BlockPos spot) {
        int size = def.groupMin + RNG.nextInt(def.groupMax - def.groupMin + 1);
        Patrol patrol = new Patrol(nextUid++, dim, def);
        patrol.lastSeen = world.getTotalWorldTime();

        int spawned = 0;
        for (int i = 0; i < size; i++) {
            String id = def.entities.get(RNG.nextInt(def.entities.size()));
            Entity ent;
            try {
                ent = EntityList.createEntityByIDFromName(new ResourceLocation(id), world);
            } catch (Throwable t) {
                ent = null;
            }
            if (ent == null) {
                // Unresolvable id (mod absent / typo): retire the def for this session, log ONCE.
                BAD_DEFS.add(def.name);
                EpochRunnerMod.logger.warn("[Patrol] def '" + def.name + "' skipped — entity '"
                        + id + "' does not resolve (edit patrols.json).");
                for (UUID u : patrol.members) {
                    Entity m = world.getEntityFromUuid(u);
                    if (m != null) m.setDead();
                }
                return;
            }
            double ox = (RNG.nextDouble() - 0.5) * 6, oz = (RNG.nextDouble() - 0.5) * 6;
            ent.setPosition(spot.getX() + 0.5 + ox, spot.getY() + 0.1, spot.getZ() + 0.5 + oz);
            if (ent instanceof EntityLiving) {
                try {
                    ((EntityLiving) ent).onInitialSpawn(
                            world.getDifficultyForLocation(spot), null);
                    ((EntityLiving) ent).enablePersistence(); // the FRAMEWORK owns despawning
                } catch (Throwable ignored) {}
            }
            ent.addTag("erm_patrol");
            ent.getEntityData().setInteger("erm_patrol_uid", patrol.uid);
            ent.getEntityData().setString("erm_patrol_def", def.name);
            // ERA SKINS: patrol members dress for the world's tech level (tribal -> medieval ->
            // renaissance -> modern pools), so formations visibly grow with the technology.
            try {
                studio.ERM.war.skins.SkinPoolManager.applySkinForRivalLevel(
                        ent, DistrictRegistry.rivalLevel(world), RNG);
            } catch (Throwable ignored) {}
            if (!world.spawnEntity(ent)) continue;
            patrol.members.add(ent.getUniqueID());
            spawned++;
        }
        if (spawned == 0) return;
        ACTIVE.put(patrol.uid, patrol);
        EpochRunnerMod.logger.info("[Patrol] spawned '" + def.name + "' #" + patrol.uid + " ("
                + spawned + " members, " + def.type + ") @ " + spot.getX() + "," + spot.getZ());
    }
}
