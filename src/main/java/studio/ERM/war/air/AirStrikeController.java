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
        double approachDist = 150 + level * 20;

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

        // If a specific aircraftType is requested, force a single aircraft and no escorts.
        int aircraftCount = (forcedAircraftType != null) ? 1 : config.aircraftCount;
        int escortCount = (forcedAircraftType != null) ? 0 : config.escortCount;

        // Spawn aircraft
        for (int i = 0; i < aircraftCount; i++) {
            String aircraftType;
            if (forcedAircraftType != null) {
                aircraftType = forcedAircraftType;
            } else {
                aircraftType = normalizeFlansVehicleId(config.aircraftTypes[rand.nextInt(config.aircraftTypes.length)]);
            }

            EntityGhostAircraft aircraft = new EntityGhostAircraft(world, aircraftType, team);

            WarAirstrikeHelper.StrikeProfile prof = WarAirstrikeHelper.getProfile(aircraftType);
            aircraft.applyStrikeProfile(prof, target);

            if (focusPlayer != null) {
                aircraft.setTargetPlayer(focusPlayer);
            }

            int runY = Math.max(30, (int) prof.altitude);
            BlockPos startPos = new BlockPos(baseStartPos.getX(), runY, baseStartPos.getZ());
            BlockPos endPos = new BlockPos(baseEndPos.getX(), runY, baseEndPos.getZ());

            double offsetX = (i - aircraftCount / 2.0) * 10;
            double offsetZ = rand.nextDouble() * 5 - 2.5;

            aircraft.setPosition(
                    startPos.getX() + offsetX,
                    startPos.getY() + rand.nextInt(20) - 10,
                    startPos.getZ() + offsetZ
            );

            switch (prof.mission) {
                case BOMBING_RUN:
                    aircraft.setBombingRun(target, startPos, endPos);
                    break;

                case STRAFING:
                    aircraft.setStrafingRun(target, new Vec3d(-Math.cos(angle), 0, -Math.sin(angle)));
                    break;

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
            String escortType = normalizeFlansVehicleId(config.aircraftTypes[0]);
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
