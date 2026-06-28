package studio.ERM.war.air;

import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.World;
import net.minecraft.world.WorldServer;
import studio.ERM.EpochRunnerMod;
import studio.ERM.war.WarStateAuthority;
import studio.ERM.war.config.WarLevelsConfig;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Random;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * HARD RULE:
 *  - ONLY launchFriendlyStrike() spawns FRIENDLY ("PLAYER") aircraft.
 *  - ALL OTHER entrypoints spawn HOSTILE ("RIVAL") aircraft, even if called by a player/command.
 *
 * This guarantees: the ItemAirTargetDesignator is the ONLY way to call friendly ghost aircraft.
 */
public class AirStrikeController {

    private static final List<AirOperation> activeOperations = new CopyOnWriteArrayList<>();
    private static final Random rand = new Random();

    // CAS tracking (Phase 3)
    private static final java.util.Map<BlockPos, Long> casCooldowns = new java.util.HashMap<>();
    private static final long CAS_COOLDOWN_TICKS = 600; // 30 seconds between CAS dispatches per site

    // Surge tracking (Phase 4)
    private static final java.util.Map<BlockPos, Long> surgeCooldowns = new java.util.HashMap<>();
    private static final long SURGE_COOLDOWN_TICKS = 2400; // 2 minutes between surges per site

    /**
     * Used by HUD / status overlays.
     */
    public static int getActiveStrikeCount() {
        return activeOperations.size();
    }

    /**
     * Normalize Flan's vehicle IDs.
     * Accepts short ids like "bf109" and returns "flansmod:bf109".
     * Leaves full ids like "flansmod:bf109" unchanged.
     */
    public static String normalizeFlansVehicleId(String idOrShort) {
        if (idOrShort == null) return "flansmod:bf109";
        String s = idOrShort.trim();
        if (s.isEmpty()) return "flansmod:bf109";
        if (s.contains(":")) return s.toLowerCase(Locale.ROOT);
        return ("flansmod:" + s).toLowerCase(Locale.ROOT);
    }

    /**
     * Level-appropriate fallback pool of REAL Flan's plane ShortNames, used when the air
     * package config carries no explicit aircraftTypes list (which is the default).
     *
     * Every entry is verified against the installed content packs (WW2 + Modern Warfare).
     * Made-up/legacy names with NO matching ShortName -- "apache" (it is ApacheAH64), "tiger"
     * (it is EC665), "huey", "biplane" -- are deliberately excluded, because an unknown
     * ShortName makes the client fall back to an invisible placeholder box.
     */
    private static String[] defaultAircraftPool(int level) {
        // Era-scaled by rival level -- real Flan ShortNames (planes AND Flan helis, which are PlaneType).
        // Higher levels ADD heavier assets, escalating the air war per the faction doctrine.
        if (level <= 3) {
            return new String[] { "Camel", "Fokker" };                                  // recon scouts
        } else if (level == 4) {
            return new String[] { "BF109", "Spitfire", "zero", "yak9" };                // WW2 fighters
        } else if (level == 5) {
            return new String[] { "BF109", "Spitfire", "Mustang", "Lancaster" };        // + bomber
        } else if (level == 6) {
            return new String[] { "Spitfire", "Mustang", "Lancaster", "LittleBird", "cobra" }; // + light heli
        } else if (level == 7) {
            return new String[] { "Mustang", "LittleBird", "cobra", "hind", "BlackHawk" };     // + assault/transport heli
        } else if (level == 8) {
            return new String[] { "A10", "SU25", "ApacheAH64", "EC665", "cobra", "hind" };     // modern attack
        } else if (level == 9) {
            return new String[] { "A10", "SU25", "ApacheAH64", "EC665", "hind", "chinook" };   // + heavy lift
        }
        return new String[] { "B52", "f22", "tornado", "A10", "ApacheAH64", "EC665" };  // L10 escalation
    }

    // ============================================================
    // LEGACY API (BACK-COMPAT)
    // ============================================================

    /**
     * Legacy call used by older systems (WarTensionManager etc).
     * IMPORTANT: Per your rule, ALL non-designator strikes are HOSTILE.
     *
     * @param teamParam ignored for safety. Any team passed here becomes HOSTILE.
     */
    public static boolean launchStrike(World world, BlockPos target, String teamParam, int level) {
        // Force hostile regardless of what the caller requested.
        return launchEnemyAirStrike(world, target, level);
    }

    /**
     * Legacy call with explicit aircraft type.
     * Still forced HOSTILE (designator is the only friendly path).
     */
    public static boolean launchStrike(World world, BlockPos target, String teamParam, int level, String aircraftType) {
        return launchEnemyAirStrike(world, target, level, aircraftType);
    }

    // ============================================================
    // FRIENDLY ENTRYPOINT (DESIGNATOR ONLY)
    // ============================================================

    /**
     * This is the ONLY method that can spawn friendly ghost aircraft.
     * ItemAirTargetDesignator MUST call this.
     */
    public static boolean launchFriendlyStrike(World world, EntityPlayer caller, BlockPos target, String aircraftType, int level) {
        level = Math.max(1, Math.min(10, level));
        String forcedType = (aircraftType == null || aircraftType.trim().isEmpty()) ? null : normalizeFlansVehicleId(aircraftType);
        return launchAirStrike(world, "PLAYER", target, level, caller, forcedType);
    }

    /**
     * Back-compat overload.
     * Still friendly by definition; do not use from anywhere except designator.
     */
    public static boolean launchFriendlyStrike(World world, BlockPos target, String aircraftType, int level) {
        return launchFriendlyStrike(world, null, target, aircraftType, level);
    }

    // ============================================================
    // HOSTILE ENTRYPOINTS (EVERYTHING ELSE)
    // ============================================================

    /**
     * War/AI/raid hostile strike. Primary hostile API.
     */
    public static boolean launchEnemyAirStrike(World world, BlockPos target, int level) {
        level = Math.max(1, Math.min(10, level));
        return launchAirStrike(world, "RIVAL", target, level, null, (String) null);
    }

    /**
     * Hostile strike forcing a specific Flan vehicle id/short name.
     * Useful for war events / raid scripts.
     */
    public static boolean launchEnemyAirStrike(World world, BlockPos target, int level, String aircraftType) {
        level = Math.max(1, Math.min(10, level));
        String forcedType = (aircraftType == null || aircraftType.trim().isEmpty()) ? null : normalizeFlansVehicleId(aircraftType);
        return launchAirStrike(world, "RIVAL", target, level, null, forcedType);
    }

    /**
     * Compatibility: Previously used by commands for "player airstrike".
     * Per your rule, this MUST be HOSTILE now. The designator is the only friendly path.
     */
    public static boolean launchPlayerAirStrike(World world, EntityPlayer player, BlockPos target, int level) {
        WarStateAuthority authority = WarStateAuthority.get();
        if (!authority.shouldDistrictsOperate()) {
            return false;
        }
        level = Math.max(1, Math.min(10, level));
        return launchAirStrike(world, "RIVAL", target, level, null, (String) null);
    }

    /**
     * Compatibility: Previously custom player strike.
     * Per your rule, MUST be HOSTILE now.
     */
    public static boolean launchCustomPlayerAirStrike(World world, EntityPlayer player, BlockPos target, int level, String flansShortOrId) {
        WarStateAuthority authority = WarStateAuthority.get();
        if (!authority.shouldDistrictsOperate()) {
            return false;
        }
        level = Math.max(1, Math.min(10, level));
        String forcedType = normalizeFlansVehicleId(flansShortOrId);
        return launchAirStrike(world, "RIVAL", target, level, null, forcedType);
    }

    // ============================================================
    // PHASE 3: CAS AUTO-DISPATCH
    // ============================================================

    /**
     * Request CAS (Close Air Support) at a battle site.
     * Dispatches a CAS aircraft to loiter over the area, engaging ground targets.
     * Respects a per-site cooldown to prevent spam.
     *
     * @param team "PLAYER" for friendly CAS, "RIVAL" for hostile
     * @param level war level (1-10), affects aircraft quality
     * @return true if CAS was dispatched
     */
    public static boolean requestCAS(World world, BlockPos target, String team, int level) {
        if (world == null || world.isRemote) return false;
        level = Math.max(1, Math.min(10, level));

        // Check cooldown
        long worldTime = world.getTotalWorldTime();
        Long lastCAS = casCooldowns.get(target);
        if (lastCAS != null && (worldTime - lastCAS) < CAS_COOLDOWN_TICKS) {
            return false;
        }

        // Pick a CAS-capable aircraft based on level.
        // These MUST be real Flan's ShortNames or the client renders a placeholder box.
        // Verified against the installed Modern Warfare pack: ApacheAH64 (NOT "apache"),
        // hind, cobra, a10, su25, LittleBird. "apache"/"huey" do not exist as ShortNames.
        String aircraftType;
        if (level >= 8) {
            aircraftType = rand.nextBoolean() ? "flansmod:ApacheAH64" : "flansmod:hind";
        } else if (level >= 5) {
            String[] casAircraft = {"flansmod:a10", "flansmod:su25", "flansmod:cobra"};
            aircraftType = casAircraft[rand.nextInt(casAircraft.length)];
        } else {
            String[] lightCas = {"flansmod:LittleBird", "flansmod:cobra"};
            aircraftType = lightCas[rand.nextInt(lightCas.length)];
        }

        // Launch as CAS loiter mission
        boolean result = launchAirStrike(world, team, target, level, null, aircraftType);
        if (result) {
            casCooldowns.put(target, worldTime);
            EpochRunnerMod.logger.info("[AIR] CAS dispatched to " + target + " team=" + team + " level=" + level);
        }
        return result;
    }

    /**
     * Convenience: request friendly CAS for player defense.
     */
    public static boolean requestFriendlyCAS(World world, EntityPlayer player, BlockPos target, int level) {
        return requestCAS(world, target, "PLAYER", level);
    }

    /**
     * Convenience: request hostile CAS (enemy air support during siege/raid).
     */
    public static boolean requestHostileCAS(World world, BlockPos target, int level) {
        return requestCAS(world, target, "RIVAL", level);
    }

    /**
     * Dispatch a single transport helicopter to fast-rope a KSK troop payload onto a drop point.
     * Always HOSTILE (RIVAL). The per-type WarAirstrikeHelper profile resolves to INSERTION, so the
     * launchAirStrike switch routes it to setInsertion(...). focusPlayer is auto-resolved to the
     * nearest player to target inside launchAirStrike.
     *
     * @param type Flan transport short id: "LittleBird" (6) / "BlackHawk" (10) / "chinook" (20)
     */
    public static boolean launchInsertion(World world, BlockPos target, int level, String type) {
        if (world == null || world.isRemote) return false;
        level = Math.max(1, Math.min(10, level));
        String forced = normalizeFlansVehicleId((type == null || type.trim().isEmpty()) ? "LittleBird" : type);
        boolean r = launchAirStrike(world, "RIVAL", target, level, null, forced);
        if (r) EpochRunnerMod.logger.info("[AIR] INSERTION dispatched " + forced + " -> " + target + " level=" + level);
        return r;
    }

    // ============================================================
    // SIEGE BOMBARDMENT: JET + BOMBER DISPATCH (cooldown-free)
    // ============================================================
    //
    // These spawn HOSTILE jets/bombers that run REAL bombing/strafing missions over a target.
    // They intentionally do NOT use the casCooldowns/surgeCooldowns maps: the SiegeDirector owns
    // the cadence (its own per-instance cooldown), so these are single-aircraft, fire-and-forget.
    // Each forced ShortName routes through WarAirstrikeHelper.getProfile -> BOMBING_RUN (bombers,
    // 10 bombs) or STRAFING (jets), so the aircraft actually attack the ground and are visible.

    private static final String[] JETS_L8  = { "A10", "SU25" };
    private static final String[] JETS_L9  = { "A10", "SU25", "tornado" };
    private static final String[] JETS_L10 = { "A10", "SU25", "tornado", "f22" };

    /** One hostile JET strike (strafing/gun-rocket pass) on target. Level-scaled jet pool, no cooldown. */
    public static boolean launchHostileJetStrike(World world, BlockPos target, int level) {
        if (world == null || world.isRemote || target == null) return false;
        level = Math.max(1, Math.min(10, level));
        String[] pool = (level >= 10) ? JETS_L10 : (level >= 9) ? JETS_L9 : JETS_L8;
        String type = normalizeFlansVehicleId(pool[rand.nextInt(pool.length)]);
        boolean ok = launchAirStrike(world, "RIVAL", target, level, null, type);
        if (ok) EpochRunnerMod.logger.info("[AIR] Siege JET strike " + type + " -> " + target + " L" + level);
        return ok;
    }

    /** One hostile BOMBER carpet run (BOMBING_RUN, ~10 bombs) on target. L9=Lancaster, L10=B52. No cooldown. */
    public static boolean launchHostileBombingRun(World world, BlockPos target, int level) {
        if (world == null || world.isRemote || target == null) return false;
        level = Math.max(1, Math.min(10, level));
        String type = normalizeFlansVehicleId(level >= 10 ? "B52" : "Lancaster");
        boolean ok = launchAirStrike(world, "RIVAL", target, level, null, type);
        if (ok) EpochRunnerMod.logger.info("[AIR] Siege BOMBER run " + type + " -> " + target + " L" + level);
        return ok;
    }

    /**
     * Dispatch a level-scaled bombardment air event on the given target.
     *  - L8     : one jet strike
     *  - L9     : jet strike + a chance of a Lancaster carpet run
     *  - L10    : jet strike + a B52 carpet run
     * Below L8 this does nothing (the SiegeDirector keeps its existing CAS heli pass for low levels).
     * Cooldown-free: the caller (SiegeDirector) gates the cadence.
     *
     * @return true if at least one aircraft was launched.
     */
    public static boolean dispatchBombers(World world, BlockPos target, int level) {
        if (world == null || world.isRemote || target == null) return false;
        level = Math.max(1, Math.min(10, level));
        if (level < 8) return false;
        boolean any = false;
        // Always a jet pass at L8+.
        any |= launchHostileJetStrike(world, target, level);
        // Bombers at L9-10: guaranteed at L10, ~50% at L9 (so it stays mixed, not every cadence).
        if (level >= 10 || (level == 9 && rand.nextBoolean())) {
            any |= launchHostileBombingRun(world, target, level);
        }
        return any;
    }

    // ============================================================
    // PHASE 4: SURGE AIRSTRIKES
    // ============================================================

    /**
     * Launch a surge airstrike: multiple waves of aircraft in quick succession.
     * Triggered during critical battle moments (siege Phase 3 breach, etc).
     *
     * @param waveCount number of waves to launch (staggered by ~15 seconds each)
     * @return true if at least one wave was launched
     */
    public static boolean launchSurgeAirStrike(World world, BlockPos target, String team, int level, int waveCount) {
        if (world == null || world.isRemote) return false;
        level = Math.max(1, Math.min(10, level));
        waveCount = Math.max(1, Math.min(5, waveCount));

        // Check surge cooldown
        long worldTime = world.getTotalWorldTime();
        Long lastSurge = surgeCooldowns.get(target);
        if (lastSurge != null && (worldTime - lastSurge) < SURGE_COOLDOWN_TICKS) {
            return false;
        }

        boolean anyLaunched = false;

        // First wave launches immediately
        if (launchAirStrike(world, team, target, level, null, (String) null)) {
            anyLaunched = true;
        }

        // Schedule subsequent waves as delayed operations
        if (waveCount > 1) {
            SurgeWaveOperation surge = new SurgeWaveOperation(target, team, level, waveCount - 1, worldTime);
            activeOperations.add(surge);
        }

        if (anyLaunched) {
            surgeCooldowns.put(target, worldTime);
            EpochRunnerMod.logger.info("[AIR] SURGE AIRSTRIKE launched at " + target
                    + " team=" + team + " level=" + level + " waves=" + waveCount);
        }
        return anyLaunched;
    }

    /**
     * Convenience: hostile surge (used by SiegeDirector Phase 3).
     */
    public static boolean launchHostileSurge(World world, BlockPos target, int level, int waveCount) {
        return launchSurgeAirStrike(world, target, "RIVAL", level, waveCount);
    }

    // ============================================================
    // TICK
    // ============================================================

    public static void tick(WorldServer world) {
        if (world == null) return;

        for (AirOperation op : activeOperations) {
            try {
                op.tick(world);
                if (op.isFinished(world)) {
                    activeOperations.remove(op);
                }
            } catch (Throwable t) {
                EpochRunnerMod.logger.warn("[AIR] Air operation tick failed: " + t.getMessage());
                activeOperations.remove(op);
            }
        }
    }

    /**
     * Server-tick driver for air operations. Registered on the Forge bus by EpochRunnerMod.init().
     * WITHOUT this, AirStrikeController.tick() was never called, so the whole air-operations system --
     * surge waves, aircraft loiter/cleanup -- was DEAD and aircraft never properly ran their missions.
     */
    @net.minecraftforge.fml.common.eventhandler.SubscribeEvent
    public static void onWorldTick(net.minecraftforge.fml.common.gameevent.TickEvent.WorldTickEvent e) {
        if (e.phase != net.minecraftforge.fml.common.gameevent.TickEvent.Phase.END) return;
        if (e.world == null || e.world.isRemote || !(e.world instanceof WorldServer)) return;
        try { tick((WorldServer) e.world); }
        catch (Throwable t) { EpochRunnerMod.logger.warn("[AIR] tick handler failed: " + t.getMessage()); }
    }

    // ============================================================
    // CORE LAUNCH
    // ============================================================

    /**
     * BACK-COMPAT overload (5 args).
     */
    private static boolean launchAirStrike(World world, String team, BlockPos target, int level, EntityPlayer caller) {
        return launchAirStrike(world, team, target, level, caller, (String) null);
    }

    /**
     * New core launch with optional forced aircraft type.
     * forcedAircraftType == null => use tier package random selection
     * forcedAircraftType != null => force a single aircraft, no escorts
     */
    private static boolean launchAirStrike(World world, String team, BlockPos target, int level, EntityPlayer caller, String forcedAircraftType) {
        level = Math.max(1, Math.min(10, level));
        WarLevelsConfig.AirPackageConfig config = WarLevelsConfig.getAirPackageConfig(level);

        String opId = "airstrike_" + System.currentTimeMillis();
        AirOperation op = new AirOperation(opId, team, level, target);
        op.startTick = world.getTotalWorldTime();

        double angle = rand.nextDouble() * Math.PI * 2;
        // Spawn CLOSE so the aircraft actually appear over the battle instead of doing one brief pass from
        // 350 blocks out (which is why "I never saw a single plane"). They run in, strike, and leave.
        double approachDist = 55 + level * 5;

        // Focus player:
        // - Friendly (designator): orbit/deploy anchors to caller when present
        // - Hostile: orbit/deploy anchors to nearest player to target
        EntityPlayer focusPlayer = null;
        if ("PLAYER".equals(team)) {
            focusPlayer = caller;
        }
        if (focusPlayer == null) {
            try {
                focusPlayer = world.getClosestPlayer(
                        target.getX() + 0.5, target.getY() + 0.5, target.getZ() + 0.5,
                        256, false
                );
            } catch (Throwable ignored) {
            }
        }

        int baseY = 100 + level * 10;
        final BlockPos baseStartPos = new BlockPos(
                target.getX() + Math.cos(angle) * approachDist,
                baseY,
                target.getZ() + Math.sin(angle) * approachDist
        );

        final BlockPos baseEndPos = new BlockPos(
                target.getX() - Math.cos(angle) * approachDist,
                baseY,
                target.getZ() - Math.sin(angle) * approachDist
        );

        // Aircraft type pool. config.aircraftTypes is currently ALWAYS empty (the JSON
        // AirPackageLevel carries no type list), which made the old code do
        // rand.nextInt(0) and throw -- aborting the entire strike before anything spawned.
        // Fall back to a level-appropriate pool of REAL Flan's ShortNames so the strike both
        // spawns and renders an actual plane model.
        String[] typePool = (config.aircraftTypes != null && config.aircraftTypes.length > 0)
                ? config.aircraftTypes
                : defaultAircraftPool(level);
        if (typePool.length == 0) typePool = new String[] { "bf109" };

        // If a specific aircraftType is requested, force a single aircraft and no escorts.
        int aircraftCount = (forcedAircraftType != null) ? 1 : config.aircraftCount;
        int escortCount = (forcedAircraftType != null) ? 0 : config.escortCount;

        // Spawn aircraft
        for (int i = 0; i < aircraftCount; i++) {
            String aircraftType;
            if (forcedAircraftType != null) {
                aircraftType = forcedAircraftType;
            } else {
                aircraftType = normalizeFlansVehicleId(typePool[rand.nextInt(typePool.length)]);
            }

            EntityGhostAircraft aircraft = new EntityGhostAircraft(world, aircraftType, team);

            WarAirstrikeHelper.StrikeProfile prof = WarAirstrikeHelper.getProfile(aircraftType);
            aircraft.applyStrikeProfile(prof, target);

            if (focusPlayer != null) {
                aircraft.setTargetPlayer(focusPlayer);
            }

            // Fly ABOVE the target's structures, not at an absolute low Y. prof.altitude is absolute
            // (~80), so over a tall desert base (towers near y100) the run flew INTO the buildings: the
            // aircraft collided, was destroyed, and was NEVER visible. Scan the whole run for the tallest
            // column and clear it by ~16 -- low enough to read as a dramatic pass, high enough to survive.
            // Sample the WHOLE flight line DENSELY (every ~4 blocks, +/- a little width) for the tallest
            // column -- a sparse scan missed thin towers/peaks between samples, so planes clipped them.
            // Clear the tallest by 28 so they fly safely OVER buildings and mountains, not into them.
            int maxSurf = target.getY();
            double fdx = baseEndPos.getX() - baseStartPos.getX(), fdz = baseEndPos.getZ() - baseStartPos.getZ();
            int fsteps = (int) Math.max(8, Math.hypot(fdx, fdz) / 4.0);
            double perpx = -fdz, perpz = fdx;
            double plen = Math.max(0.001, Math.hypot(perpx, perpz));
            perpx /= plen; perpz /= plen;
            for (int s = 0; s <= fsteps; s++) {
                double tt = (double) s / fsteps;
                for (int w = -4; w <= 4; w += 4) {
                    int sx = (int) Math.round(baseStartPos.getX() + fdx * tt + perpx * w);
                    int sz = (int) Math.round(baseStartPos.getZ() + fdz * tt + perpz * w);
                    try {
                        int top = world.getTopSolidOrLiquidBlock(new BlockPos(sx, 64, sz)).getY();
                        if (top > maxSurf) maxSurf = top;
                    } catch (Throwable ignored) {}
                }
            }
            int runY = Math.min(240, Math.max((int) prof.altitude, maxSurf + 28));
            BlockPos startPos = new BlockPos(baseStartPos.getX(), runY, baseStartPos.getZ());
            BlockPos endPos = new BlockPos(baseEndPos.getX(), runY, baseEndPos.getZ());

            double offsetX = (i - aircraftCount / 2.0) * 10;
            double offsetZ = rand.nextDouble() * 5 - 2.5;

            // Spawn EXACTLY at cruising altitude (no random +/-10 Y), so the plane doesn't appear high
            // and visibly "fall ~20 blocks" to its run height on spawn-in.
            aircraft.setPosition(
                    startPos.getX() + offsetX,
                    startPos.getY(),
                    startPos.getZ() + offsetZ
            );
            // Face the run direction IMMEDIATELY (matching the entity's own yaw convention in
            // moveTowardTarget: atan2(dz,dx)*180/PI - 90), so it doesn't spawn pointing east and then
            // snap 90 degrees on its first movement tick.
            double spawnDX = target.getX() - aircraft.posX, spawnDZ = target.getZ() - aircraft.posZ;
            float spawnYaw = (float) (net.minecraft.util.math.MathHelper.atan2(spawnDZ, spawnDX) * (180D / Math.PI)) - 90.0F;
            aircraft.rotationYaw = spawnYaw;
            aircraft.prevRotationYaw = spawnYaw;
            aircraft.rotationYawHead = spawnYaw;

            // HELICOPTERS SPLIT UP: each heli takes its OWN sector around the objective and runs an
            // independent gunship pattern, instead of all stacking on one hover point. Planes
            // (bombing/strafing) still converge on the breach. Sector = an even slice of the circle by
            // aircraft index, so two helis end up on opposite sides providing support separately.
            BlockPos missionTarget = target;
            if (prof.mission == EntityGhostAircraft.MissionType.HOVER_STRIKE
                    || prof.mission == EntityGhostAircraft.MissionType.ORBIT_ATTACK
                    || prof.mission == EntityGhostAircraft.MissionType.CAS_LOITER
                    || prof.mission == EntityGhostAircraft.MissionType.INSERTION) {
                double secAng = angle + (Math.PI * 2.0 * i) / Math.max(1, aircraftCount) + rand.nextDouble() * 0.4;
                double secR = 22 + rand.nextInt(20);
                missionTarget = target.add(
                        (int) Math.round(Math.cos(secAng) * secR), 0,
                        (int) Math.round(Math.sin(secAng) * secR));
            }

            switch (prof.mission) {
                case BOMBING_RUN:
                    aircraft.setBombingRun(target, startPos, endPos);
                    break;

                case STRAFING:
                    aircraft.setStrafingRun(target, new Vec3d(-Math.cos(angle), 0, -Math.sin(angle)));
                    break;

                case HOVER_STRIKE:
                    aircraft.setHoverStrike(missionTarget, startPos);
                    break;

                case ORBIT_ATTACK:
                    aircraft.setOrbitAttack(missionTarget, startPos, 40 + rand.nextInt(20));
                    break;

                case CAS_LOITER:
                    aircraft.setCASLoiter(missionTarget, startPos);
                    break;

                case INSERTION: {
                    int troops = EntityGhostAircraft.insertionPayloadFor(aircraftType);
                    java.util.UUID tgt = (focusPlayer != null) ? focusPlayer.getUniqueID() : null;
                    aircraft.setInsertion(missionTarget, startPos, troops, tgt, level);
                    break;
                }

                case INTERCEPTION:
                case FLYOVER:
                default:
                    List<BlockPos> path = new ArrayList<>();
                    path.add(startPos);
                    path.add(target);
                    path.add(endPos);
                    aircraft.setFlightPath(path);
                    break;
            }

            safeSpawnEntity(world, aircraft, op);
        }

        // Escorts
        for (int i = 0; i < escortCount; i++) {
            String escortType = normalizeFlansVehicleId(typePool[0]);
            EntityGhostAircraft escort = new EntityGhostAircraft(world, escortType, team);

            if (focusPlayer != null) {
                escort.setTargetPlayer(focusPlayer);
            }

            try {
                WarAirstrikeHelper.StrikeProfile escortProf = WarAirstrikeHelper.getProfile(escortType);
                escort.applyStrikeProfile(escortProf, target);
            } catch (Throwable ignored) {
            }

            escort.setPosition(
                    baseStartPos.getX() + (rand.nextDouble() - 0.5) * 50,
                    baseStartPos.getY() + 20,
                    baseStartPos.getZ() + (rand.nextDouble() - 0.5) * 50
            );

            List<BlockPos> path = new ArrayList<>();
            path.add(baseStartPos);
            path.add(target);
            path.add(baseEndPos);
            escort.setFlightPath(path);

            safeSpawnEntity(world, escort, op);
        }

        activeOperations.add(op);

        EpochRunnerMod.logger.info("[AIR] Launched " + team + " air strike level " + level + " with " + op.aircraft.size() + " aircraft");
        return true;
    }

    private static void safeSpawnEntity(World world, EntityGhostAircraft aircraft, AirOperation op) {
        if (world == null || world.isRemote) return;
        try {
            world.spawnEntity(aircraft);
            op.aircraft.add(aircraft);
        } catch (Throwable t) {
            EpochRunnerMod.logger.warn("[AIR] Failed to spawn ghost aircraft: " + t.getMessage());
        }
    }

    /**
     * Surge wave operation: spawns additional strike waves at timed intervals.
     * Each wave is a full launchAirStrike call, staggered by WAVE_DELAY ticks.
     */
    private static class SurgeWaveOperation extends AirOperation {
        private static final int WAVE_DELAY = 300; // 15 seconds between waves
        private final String team;
        private final int level;
        private int wavesRemaining;
        private long lastWaveTick;

        SurgeWaveOperation(BlockPos target, String team, int level, int wavesRemaining, long startTick) {
            super("surge_" + System.currentTimeMillis(), team, level, target);
            this.team = team;
            this.level = level;
            this.wavesRemaining = wavesRemaining;
            this.lastWaveTick = startTick;
            this.startTick = startTick;
        }

        @Override
        void tick(WorldServer world) {
            super.tick(world);
            if (wavesRemaining <= 0) return;

            long worldTime = world.getTotalWorldTime();
            if ((worldTime - lastWaveTick) >= WAVE_DELAY) {
                launchAirStrike(world, team, target, level, null, (String) null);
                lastWaveTick = worldTime;
                wavesRemaining--;
                EpochRunnerMod.logger.info("[AIR] Surge wave launched, " + wavesRemaining + " remaining");
            }
        }

        @Override
        boolean isFinished(WorldServer world) {
            return wavesRemaining <= 0 && super.isFinished(world);
        }
    }

    private static class AirOperation {
        final String opId;
        final String team;
        final int level;
        final BlockPos target;
        long startTick = 0;

        final List<EntityGhostAircraft> aircraft = new ArrayList<>();

        AirOperation(String opId, String team, int level, BlockPos target) {
            this.opId = opId;
            this.team = team;
            this.level = level;
            this.target = target;
        }

        void tick(WorldServer world) {
            aircraft.removeIf(a -> a == null || a.isDead);
        }

        boolean isFinished(WorldServer world) {
            if (aircraft.isEmpty()) return true;
            return (world.getTotalWorldTime() - startTick) > 3600;
        }
    }
}
