package studio.ERM.war.air;

import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityLiving;
import net.minecraft.entity.SharedMonsterAttributes;
import net.minecraft.entity.EntityList;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.network.datasync.DataParameter;
import net.minecraft.network.datasync.DataSerializers;
import net.minecraft.network.datasync.EntityDataManager;
import net.minecraft.util.DamageSource;
import net.minecraft.util.ResourceLocation;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.World;
import studio.ERM.EpochRunnerMod;
import studio.ERM.war.config.WarMasterConfig;

import java.util.ArrayList;
import java.util.List;

public class EntityGhostAircraft extends EntityLiving {

    // Base sync params
    private static final DataParameter<String> AIRCRAFT_TYPE =
            EntityDataManager.createKey(EntityGhostAircraft.class, DataSerializers.STRING);
    private static final DataParameter<String> TEAM =
            EntityDataManager.createKey(EntityGhostAircraft.class, DataSerializers.STRING);
    private static final DataParameter<Integer> MISSION_TYPE =
            EntityDataManager.createKey(EntityGhostAircraft.class, DataSerializers.VARINT);

    // Tuning knobs (sync)
    private static final DataParameter<Integer> BOMBS =
            EntityDataManager.createKey(EntityGhostAircraft.class, DataSerializers.VARINT);
    private static final DataParameter<Float> BOMB_DMG =
            EntityDataManager.createKey(EntityGhostAircraft.class, DataSerializers.FLOAT);
    private static final DataParameter<Integer> MAX_MISSILES =
            EntityDataManager.createKey(EntityGhostAircraft.class, DataSerializers.VARINT);
    private static final DataParameter<Float> ACCURACY =
            EntityDataManager.createKey(EntityGhostAircraft.class, DataSerializers.FLOAT);

    // Flight path
    private final List<Vec3d> waypoints = new ArrayList<>();
    private int currentWaypointIndex = 0;
    private Vec3d targetPosition = null;

    // Flight parameters
    private float speed = 2.0f;
    private float altitude = 80.0f;
    private float bankAngle = 0;
    private float pitchAngle = 0;

    // ── PILOT AI (LAYER 2 + 3): smoothed flight, terrain-following, forward-raycast avoidance ──
    // The aircraft turns toward its desired heading at a capped rate each tick instead of instantly pointing
    // at the target -- this is what produces banking + wide sweeping turns rather than "point and drift". It
    // also holds an altitude BAND above the terrain directly below it (rising over hills/towers, descending
    // over valleys) and raycasts forward to climb/steer away from anything it would otherwise smash into.
    private float headingYaw = Float.NaN;    // current smoothed heading; NaN until the first move
    private int avoidanceActive = 0;         // >0 while a forward-collision avoidance maneuver is in progress
    // Per-aircraft variation generated ONCE so each flight looks different but stays stable (no per-tick
    // randomness). Seeds the altitude lane (deconfliction) and a small approach offset.
    private int flightLane = -1;
    public static boolean DEBUG_FLIGHT = true; // emit flight-state particles + logs (toggle off to silence)

    // Movement tracking
    private Vec3d lastMoveDirection = new Vec3d(1, 0, 0);
    private double lastMoveY = 0;

    // Mission
    private MissionType mission = MissionType.FLYOVER;
    private BlockPos attackTarget = null;
    private int bombsRemaining = 3;
    private int missilesFired = 0;

    // State
    private boolean isOnMission = false;
    private boolean isReturning = false;
    private int ticksOnMission = 0;
    private int ticksReturning = 0;
    private int strafeCooldown = 0;

    // Helicopter orbit state
    private double orbitAngle = 0;
    private double orbitRadius = 40;
    private int hoverEngageTicks = 0;
    private int rocketsFired = 0;
    private int casLoiterTicks = 0;
    private static final int CAS_LOITER_DURATION = 1200; // 60 seconds

    // Troop insertion state
    private int insertTroops = 0;            // total payload to drop
    private int insertTroopsRemaining = 0;   // left to drop
    private int insertWarLevel = 1;          // KSK loadout scaling
    private int insertHoverTicks = 0;        // ticks spent over the drop point
    private java.util.UUID insertTargetUuid = null; // defender to aggro the troops onto
    private static final int INSERT_DROP_INTERVAL = 12;   // 0.6s between fast-ropes
    private static final int INSERT_HOVER_TIMEOUT = 1200; // safety: bail after 60s on station
    private int insertPhase = 0;                 // 0 = approach/drop, 1 = post-drop orbit-fire (light helis)
    private boolean insertJeepDropped = false;   // chinook unloads one jeep
    private static final int INSERT_ORBIT_TICKS = 600;  // light heli orbits firing ~30s after dropping
    private static final int CHINOOK_LAND_TICKS = 600;  // chinook sits on the ground ~30s

    // Flans puppet
    private Entity flansVehiclePuppet = null;
    private boolean puppetSpawned = false;
    private int puppetSyncTicks = 0;
    private static final int PUPPET_SYNC_INTERVAL = 1; // every tick

    public enum MissionType {
        FLYOVER,
        BOMBING_RUN,
        STRAFING,
        ESCORT,
        INTERCEPTION,
        HOVER_STRIKE,
        ORBIT_ATTACK,
        CAS_LOITER,
        INSERTION
    }

    public EntityGhostAircraft(World worldIn) {
        super(worldIn);
        this.setSize(4.0F, 2.0F);
        this.noClip = true;
        this.setNoGravity(true);
    }

    public EntityGhostAircraft(World worldIn, String type, String team) {
        this(worldIn);
        this.setAircraftType(type);
        this.setMcmTeam(team);
    }

    @Override
    protected void entityInit() {
        super.entityInit();

        // Base identity
        this.dataManager.register(AIRCRAFT_TYPE, "bf109");
        this.dataManager.register(TEAM, "ENEMY");
        this.dataManager.register(MISSION_TYPE, 0);

        // Tuning knobs defaults
        this.dataManager.register(BOMBS, 3);
        this.dataManager.register(BOMB_DMG, 4.0f);
        this.dataManager.register(MAX_MISSILES, 2);
        this.dataManager.register(ACCURACY, 1.0f);
    }

    @Override
    protected void applyEntityAttributes() {
        super.applyEntityAttributes();
        this.getEntityAttribute(SharedMonsterAttributes.MAX_HEALTH).setBaseValue(100.0D);
        this.getEntityAttribute(SharedMonsterAttributes.MOVEMENT_SPEED).setBaseValue(2.0D);
    }

    // ===== TYPE & TEAM =====

    public void setAircraftType(String type) {
        this.dataManager.set(AIRCRAFT_TYPE, type);
        applyTypeStats(type);
    }

    public String getAircraftType() {
        return this.dataManager.get(AIRCRAFT_TYPE);
    }

    public void setMcmTeam(String team) {
        this.dataManager.set(TEAM, team);
    }

    public String getMcmTeam() {
        return this.dataManager.get(TEAM);
    }

    /**
     * IMPORTANT: must be public (CommandWar calls it)
     */
    public void setMission(MissionType newMission) {
        this.mission = newMission;
        this.dataManager.set(MISSION_TYPE, newMission.ordinal());
    }

    public MissionType getMission() {
        return MissionType.values()[this.dataManager.get(MISSION_TYPE)];
    }

    /**
     * Unified type stats:
     * Doctrine gives baseline behavior, WarMasterConfig overrides (if present).
     */
    private void applyTypeStats(String type) {
        // Baseline from doctrine
        AirDoctrine.AircraftProfile p = AirDoctrine.getProfile(type);

        // Altitude & speed from doctrine
        this.altitude = p.minAltitude + rand.nextInt(Math.max(1, (p.maxAltitude - p.minAltitude) + 1));
        this.speed = Math.max(0.4f, 2.0f * p.speedMultiplier);

        // Reasonable defaults from doctrine
        int defaultBombs = p.usesBombs ? 2 : 0;
        int defaultMaxMissiles = p.usesMissiles ? 2 : 0;
        double defaultHp = 40.0 + (p.threatLevel * 12.0);

        // Size & payload by attack pattern
        switch (p.attackPattern) {
            case CARPET_BOMB:
                defaultBombs = 10;
                defaultMaxMissiles = 0;
                // Big bombers (B52/Lancaster) get a MASSIVE hitbox so they're actually shootable from the
                // ground (MC AABBs are square-footprint, so this is "huge" rather than truly long+skinny).
                this.setSize(12.0F, 3.0F);
                break;
            case GUN_RUN:
            case ROCKET_STRAFE:
                defaultBombs = 0;
                defaultMaxMissiles = p.usesMissiles ? 4 : 0;
                this.setSize(5.0F, 2.2F);
                break;
            case ORBIT_ATTACK:
            case HOVER_STRIKE:
                defaultBombs = 0;
                defaultMaxMissiles = p.usesMissiles ? 6 : 0;
                this.setSize(4.5F, 2.5F);
                break;
            default:
                this.setSize(4.0F, 2.0F);
                break;
        }

// Optional override from config
        WarMasterConfig.AircraftStats cfg =
                (WarMasterConfig.data != null && WarMasterConfig.data.aircraftSettings != null)
                        ? WarMasterConfig.data.aircraftSettings.getOrDefault(type, new WarMasterConfig.AircraftStats())
                        : new WarMasterConfig.AircraftStats();

// Priority: Config Map > Doctrine defaults > existing dataparam defaults
        int bombs = (cfg.bombsRemaining != 0) ? cfg.bombsRemaining : defaultBombs;
        int maxMissiles = (cfg.maxMissiles != 0) ? cfg.maxMissiles : defaultMaxMissiles;
        float bombDmg = (cfg.bombDamage > 0.0f) ? cfg.bombDamage : this.dataManager.get(BOMB_DMG);
        float accuracy = (cfg.aimAccuracy > 0.0f) ? cfg.aimAccuracy : this.dataManager.get(ACCURACY);
        double hp = (cfg.health > 0) ? cfg.health : defaultHp;

        this.bombsRemaining = bombs;
        this.missilesFired = 0;

        this.dataManager.set(BOMBS, bombs);
        this.dataManager.set(MAX_MISSILES, maxMissiles);
        this.dataManager.set(BOMB_DMG, bombDmg);
        this.dataManager.set(ACCURACY, accuracy);

        this.getEntityAttribute(SharedMonsterAttributes.MAX_HEALTH).setBaseValue(hp);
        this.setHealth((float) hp);
    }

    // ===== MISSION SETUP =====

    public void setFlightPath(List<BlockPos> path) {
        waypoints.clear();
        for (BlockPos pos : path) {
            waypoints.add(new Vec3d(pos.getX() + 0.5, altitude, pos.getZ() + 0.5));
        }
        currentWaypointIndex = 0;
        targetPosition = waypoints.isEmpty() ? null : waypoints.get(0);
        isOnMission = true;
    }

    public void setBombingRun(BlockPos target, BlockPos startPos, BlockPos endPos) {
        setMission(MissionType.BOMBING_RUN);
        this.attackTarget = target;

        waypoints.clear();
        waypoints.add(new Vec3d(startPos.getX(), altitude, startPos.getZ()));
        waypoints.add(new Vec3d(target.getX(), altitude, target.getZ()));
        waypoints.add(new Vec3d(endPos.getX(), altitude, endPos.getZ()));

        currentWaypointIndex = 0;
        targetPosition = waypoints.get(0);
        isOnMission = true;

        lastMoveDirection = new Vec3d(
                target.getX() - startPos.getX(),
                0,
                target.getZ() - startPos.getZ()
        ).normalize();

        EpochRunnerMod.logger.info("[AIR] Bombing run set: " + target);
    }

    public void setStrafingRun(BlockPos target, Vec3d approachVector) {
        setMission(MissionType.STRAFING);
        this.attackTarget = target;

        waypoints.clear();
        Vec3d approach = approachVector.normalize().scale(100);
        waypoints.add(new Vec3d(target.getX() - approach.x, altitude, target.getZ() - approach.z));
        waypoints.add(new Vec3d(target.getX(), altitude, target.getZ()));
        waypoints.add(new Vec3d(target.getX() + approach.x * 2, altitude, target.getZ() + approach.z * 2));

        currentWaypointIndex = 0;
        targetPosition = waypoints.get(0);
        isOnMission = true;

        lastMoveDirection = approachVector.normalize();
    }

    /**
     * Setup a helicopter hover strike mission.
     * Aircraft approaches target then hovers nearby, engaging with rockets and guns.
     */
    public void setHoverStrike(BlockPos target, BlockPos startPos) {
        setMission(MissionType.HOVER_STRIKE);
        this.attackTarget = target;

        waypoints.clear();
        waypoints.add(new Vec3d(startPos.getX(), altitude, startPos.getZ()));
        waypoints.add(new Vec3d(target.getX(), altitude, target.getZ()));

        currentWaypointIndex = 0;
        targetPosition = waypoints.get(0);
        isOnMission = true;
        hoverEngageTicks = 0;
        rocketsFired = 0;

        lastMoveDirection = new Vec3d(
                target.getX() - startPos.getX(), 0, target.getZ() - startPos.getZ()
        ).normalize();

        EpochRunnerMod.logger.info("[AIR] Hover strike set: " + target);
    }

    /**
     * Setup a helicopter orbit attack mission.
     * Aircraft orbits the target area at specified radius, firing weapons.
     */
    public void setOrbitAttack(BlockPos target, BlockPos startPos, double radius) {
        setMission(MissionType.ORBIT_ATTACK);
        this.attackTarget = target;
        this.orbitRadius = radius;
        this.orbitAngle = 0;
        this.rocketsFired = 0;

        waypoints.clear();
        waypoints.add(new Vec3d(startPos.getX(), altitude, startPos.getZ()));
        waypoints.add(new Vec3d(target.getX() + radius, altitude, target.getZ()));

        currentWaypointIndex = 0;
        targetPosition = waypoints.get(0);
        isOnMission = true;

        lastMoveDirection = new Vec3d(
                target.getX() - startPos.getX(), 0, target.getZ() - startPos.getZ()
        ).normalize();

        EpochRunnerMod.logger.info("[AIR] Orbit attack set: " + target + " radius=" + radius);
    }

    /**
     * Setup a CAS loiter mission.
     * Aircraft patrols an area for extended duration, engaging targets of opportunity.
     */
    public void setCASLoiter(BlockPos target, BlockPos startPos) {
        setMission(MissionType.CAS_LOITER);
        this.attackTarget = target;
        this.casLoiterTicks = 0;
        this.rocketsFired = 0;

        waypoints.clear();
        waypoints.add(new Vec3d(startPos.getX(), altitude, startPos.getZ()));
        waypoints.add(new Vec3d(target.getX(), altitude, target.getZ()));

        currentWaypointIndex = 0;
        targetPosition = waypoints.get(0);
        isOnMission = true;

        lastMoveDirection = new Vec3d(
                target.getX() - startPos.getX(), 0, target.getZ() - startPos.getZ()
        ).normalize();

        EpochRunnerMod.logger.info("[AIR] CAS loiter set: " + target);
    }

    /**
     * Setup a troop INSERTION mission.
     * The helicopter approaches the drop point, hovers over it, and fast-ropes its troop payload
     * (real EntitySoldiers) one at a time onto the ground below, then departs. Aircraft are
     * client-rendered ghosts and cannot carry live Flan passengers, so the troops are independently
     * spawned via SpawnHelper.
     *
     * @param drop         the landing-zone / drop point
     * @param startPos     approach origin
     * @param troopCount   number of EntitySoldiers to drop (LittleBird 6 / BlackHawk 10 / Chinook 20)
     * @param targetPlayer defender UUID the dropped troops aggro onto (nullable)
     * @param warLevel     rival level 1-10 for KSK loadout scaling
     */
    public void setInsertion(BlockPos drop, BlockPos startPos, int troopCount,
                             java.util.UUID targetPlayer, int warLevel) {
        setMission(MissionType.INSERTION);
        this.attackTarget = drop;
        this.insertTroops = Math.max(1, troopCount);
        this.insertTroopsRemaining = this.insertTroops;
        this.insertTargetUuid = targetPlayer;
        this.insertWarLevel = Math.max(1, Math.min(10, warLevel));
        this.insertHoverTicks = 0;
        this.insertPhase = 0;
        this.insertJeepDropped = false;

        waypoints.clear();
        waypoints.add(new Vec3d(startPos.getX(), altitude, startPos.getZ()));
        waypoints.add(new Vec3d(drop.getX(), altitude, drop.getZ()));

        currentWaypointIndex = 0;
        targetPosition = waypoints.get(0);
        isOnMission = true;

        lastMoveDirection = new Vec3d(
                drop.getX() - startPos.getX(), 0, drop.getZ() - startPos.getZ()
        ).normalize();

        EpochRunnerMod.logger.info("[AIR] INSERTION set: " + drop + " troops=" + this.insertTroops
                + " level=" + this.insertWarLevel);
    }

    // ===== UPDATE =====
    @Override
    public void onUpdate() {
        super.onUpdate();

        // SERVER: mission + movement only
        if (!this.world.isRemote) {
            if (crashing) { tickCrash(); return; } // shot down -> spin down to the ground and detonate
            // Ghost aircraft are POSITION-driven (setPosition each tick), never motion-driven. Pin motion
            // to zero and keep gravity off EVERY tick so they never "fall and tumble" on spawn before the
            // first mission move kicks in (the spawn-fall the player saw).
            this.motionX = 0; this.motionY = 0; this.motionZ = 0;
            this.setNoGravity(true);
            if (isOnMission) {
                ticksOnMission++;

                if (targetPosition != null) moveTowardTarget();
                if (targetPosition != null && getDistanceToTarget() < speed * 2) onWaypointReached();

                executeMission();

                if (strafeCooldown > 0) strafeCooldown--;

                if (ticksOnMission > 2400) startReturning();

                handleDespawn();
            }

            // Spawn + drag the REAL Flan plane so airstrikes show an ACTUAL aircraft model. This is the
            // old, CME-free approach the user described: a normally-spawned Flan entity (Flan renders
            // it itself) dragged along this ghost's flight path. The ghost itself renders nothing. The
            // build/sync was fully implemented but never called -- which is why no planes ever appeared.
            if (!puppetSpawned) spawnFlansPuppet();
            syncPuppetPosition();
        }

        // Always update angles (pure math)
        updateVisualAngles();
    }



    private void executeMission() {
        switch (mission) {
            case BOMBING_RUN:
                executeBombingRun();
                break;
            case STRAFING:
                executeStrafingRun();
                break;
            case INTERCEPTION:
                executeInterception();
                break;
            case HOVER_STRIKE:
                executeHoverStrike();
                break;
            case ORBIT_ATTACK:
                executeOrbitAttack();
                break;
            case CAS_LOITER:
                executeCASLoiter();
                break;
            case INSERTION:
                executeInsertion();
                break;
            default:
                break;
        }
    }

    private void executeBombingRun() {
        if (attackTarget == null) return;
        if (bombsRemaining <= 0) return;

        double horizDist = getHorizontalDistanceTo(attackTarget);
        // Release the STICK across the whole pass -- one bomb every few ticks within a WIDER window -- so the
        // bombs walk across the target with spread instead of the entire load dumping on one block the first
        // tick we enter range.
        if (horizDist < 34 && posY > attackTarget.getY() + 8 && ticksOnMission % 4 == 0) {
            dropBomb();

            bombsRemaining--;
            this.dataManager.set(BOMBS, bombsRemaining);

            if (bombsRemaining <= 0) {
                setMission(MissionType.FLYOVER);
                startReturning();
            }
        }
    }

    private void executeStrafingRun() {
        if (attackTarget == null) return;
        if (strafeCooldown > 0) return;

        double horizDist = getHorizontalDistanceTo(attackTarget);
        if (horizDist < 50 && horizDist > 10) {
            fireStrafe();
            strafeCooldown = 5;
        }
    }

    private void executeInterception() {
        List<EntityGhostAircraft> aircraft = world.getEntitiesWithinAABB(
                EntityGhostAircraft.class,
                getEntityBoundingBox().grow(100)
        );

        EntityGhostAircraft target = null;
        double nearestDist = Double.MAX_VALUE;

        for (EntityGhostAircraft a : aircraft) {
            if (a == this) continue;
            if (a.getMcmTeam().equals(this.getMcmTeam())) continue;

            double dist = getDistance(a);
            if (dist < nearestDist) {
                nearestDist = dist;
                target = a;
            }
        }

        if (target != null) {
            targetPosition = new Vec3d(target.posX, target.posY, target.posZ);

            int maxMissiles = this.dataManager.get(MAX_MISSILES);
            if (nearestDist < 30 && strafeCooldown <= 0 && missilesFired < maxMissiles) {
                fireMissile(target);
                missilesFired++;
                strafeCooldown = 40;
            }
        }
    }

    // ===== HELICOPTER MISSIONS =====

    /**
     * Hover Strike: helicopter holds position near target, fires rockets/guns downward.
     * Used by ATTACK_HELI (Apache, Cobra, Tiger) with HOVER_STRIKE pattern.
     */
    private void executeHoverStrike() {
        if (attackTarget == null) return;

        double horizDist = getHorizontalDistanceTo(attackTarget);

        // Approach phase: fly toward target until within engage range
        if (horizDist > 50) {
            targetPosition = new Vec3d(attackTarget.getX(), altitude, attackTarget.getZ());
            return;
        }

        // Hover phase: hold position near target, slight drift
        hoverEngageTicks++;
        double hoverX = attackTarget.getX() + Math.sin(hoverEngageTicks * 0.02) * 8;
        double hoverZ = attackTarget.getZ() + Math.cos(hoverEngageTicks * 0.02) * 8;
        targetPosition = new Vec3d(hoverX, Math.max(altitude, attackTarget.getY() + 30), hoverZ);

        // Slow down for hover
        this.speed = Math.max(0.3f, speed * 0.95f);

        // Fire rockets at intervals
        int maxMissiles = this.dataManager.get(MAX_MISSILES);
        if (hoverEngageTicks % 30 == 0 && rocketsFired < maxMissiles) {
            fireRocketAtGround(attackTarget);
            rocketsFired++;
        }

        // Strafe between rockets
        if (strafeCooldown <= 0 && horizDist < 60) {
            fireStrafe();
            strafeCooldown = 15;
        }

        // Disengage after expending ordnance or timeout
        if (rocketsFired >= maxMissiles || hoverEngageTicks > 600) {
            setMission(MissionType.FLYOVER);
            startReturning();
        }
    }

    /**
     * Orbit Attack: helicopter circles target area at radius, firing continuously.
     * Used by GUNSHIP (Hind) with ORBIT_ATTACK pattern.
     */
    private void executeOrbitAttack() {
        if (attackTarget == null) return;

        double horizDist = getHorizontalDistanceTo(attackTarget);

        // Approach phase
        if (horizDist > orbitRadius + 30) {
            targetPosition = new Vec3d(attackTarget.getX(), altitude, attackTarget.getZ());
            return;
        }

        // Orbit phase: circle around target
        orbitAngle += 0.03; // ~3.4 degrees per tick, full orbit ~6 seconds
        double orbitX = attackTarget.getX() + Math.cos(orbitAngle) * orbitRadius;
        double orbitZ = attackTarget.getZ() + Math.sin(orbitAngle) * orbitRadius;
        targetPosition = new Vec3d(orbitX, Math.max(altitude, attackTarget.getY() + 35), orbitZ);

        // Yaw follows movement direction (tangent to orbit), not inward
        // moveTowardTarget() handles yaw naturally — no override needed

        // Continuous strafing fire toward center
        if (strafeCooldown <= 0) {
            fireStrafe();
            strafeCooldown = 10;
        }

        // Fire rockets periodically
        int maxMissiles = this.dataManager.get(MAX_MISSILES);
        if (ticksOnMission % 40 == 0 && rocketsFired < maxMissiles) {
            fireRocketAtGround(attackTarget);
            rocketsFired++;
        }

        // Drop bombs if available (Hind carries bombs)
        if (bombsRemaining > 0 && ticksOnMission % 80 == 0) {
            dropBomb();
            bombsRemaining--;
            this.dataManager.set(BOMBS, bombsRemaining);
        }

        // Disengage after full orbits or ammo depleted
        if (ticksOnMission > 800 || (rocketsFired >= maxMissiles && bombsRemaining <= 0)) {
            setMission(MissionType.FLYOVER);
            startReturning();
        }
    }

    /**
     * CAS Loiter: helicopter patrols an area for extended duration, engaging targets of opportunity.
     * Used when CAS auto-dispatch sends helicopters to support ground forces.
     */
    private void executeCASLoiter() {
        if (attackTarget == null) return;

        casLoiterTicks++;

        // Figure-8 patrol pattern around target
        double patrolPhase = casLoiterTicks * 0.015;
        double patrolX = attackTarget.getX() + Math.sin(patrolPhase) * orbitRadius;
        double patrolZ = attackTarget.getZ() + Math.sin(patrolPhase * 2) * (orbitRadius * 0.5);
        targetPosition = new Vec3d(patrolX, Math.max(altitude, attackTarget.getY() + 40), patrolZ);

        // Scan for nearby hostile entities and engage
        List<Entity> nearbyEntities = world.getEntitiesWithinAABBExcludingEntity(this,
                getEntityBoundingBox().grow(60));

        Entity closestHostile = null;
        double closestDist = Double.MAX_VALUE;
        for (Entity e : nearbyEntities) {
            if (e instanceof EntityGhostAircraft) {
                EntityGhostAircraft other = (EntityGhostAircraft) e;
                if (!other.getMcmTeam().equals(this.getMcmTeam())) {
                    double d = getDistance(e);
                    if (d < closestDist) { closestDist = d; closestHostile = e; }
                }
            } else if (e instanceof EntityLiving && !(e instanceof net.minecraft.entity.player.EntityPlayer)) {
                double d = getDistance(e);
                if (d < closestDist) { closestDist = d; closestHostile = e; }
            }
        }

        // Engage closest hostile
        if (closestHostile != null && closestDist < 50) {
            if (strafeCooldown <= 0) {
                fireStrafe();
                strafeCooldown = 12;
            }
            int maxMissiles = this.dataManager.get(MAX_MISSILES);
            if (closestDist < 30 && rocketsFired < maxMissiles && casLoiterTicks % 60 == 0) {
                fireRocketAtGround(new BlockPos(closestHostile));
                rocketsFired++;
            }
        }

        // Depart after loiter duration expires
        if (casLoiterTicks >= CAS_LOITER_DURATION) {
            setMission(MissionType.FLYOVER);
            startReturning();
        }
    }

    /**
     * INSERTION: helicopter approaches the drop point, hovers, and fast-ropes its troop payload
     * (real EntitySoldiers) one at a time onto the ground below, then departs. Used by transport
     * helis (LittleBird/BlackHawk/Chinook).
     */
    private void executeInsertion() {
        if (attackTarget == null) { startReturning(); return; }
        if (world.isRemote) return;

        String t = (getAircraftType() == null) ? "" : getAircraftType().toLowerCase();
        boolean chinook = t.contains("chinook");
        // getTopSolidOrLiquidBlock returns the TOP block (roof/tree included), so fast-roping here drops
        // troops onto HIGH GROUND when the objective sits under a roof/canopy.
        int groundY = world.getTopSolidOrLiquidBlock(attackTarget).getY();
        double horizDist = getHorizontalDistanceTo(attackTarget);

        // PHASE 0: approach until directly over the drop point.
        if (insertPhase == 0 && horizDist > 6.0) {
            targetPosition = new Vec3d(attackTarget.getX() + 0.5,
                    Math.max(altitude, groundY + (chinook ? 30 : 16)), attackTarget.getZ() + 0.5);
            return;
        }

        insertHoverTicks++;

        if (chinook) {
            // CHINOOK: descend and LAND on the drop point, sit ~30s unloading troops + a jeep, then lift off.
            // Y is pinned directly (noGravity) so it sets down cleanly with NO ground-bounce.
            double landY = groundY + 1.2;
            targetPosition = new Vec3d(attackTarget.getX() + 0.5, landY, attackTarget.getZ() + 0.5);
            this.speed = Math.max(0.2f, speed * 0.85f);
            // Only unload once actually LANDED -- never dump troops mid-descent.
            boolean landed = Math.abs(posY - landY) < 2.5 && getHorizontalDistanceTo(attackTarget) < 4.0;
            if (landed && insertTroopsRemaining > 0 && insertHoverTicks % INSERT_DROP_INTERVAL == 0) {
                dropOneTrooper(); insertTroopsRemaining--;
            }
            if (landed && !insertJeepDropped && insertHoverTicks >= 40) { dropJeep(groundY); insertJeepDropped = true; }
            if (insertHoverTicks > CHINOOK_LAND_TICKS && insertTroopsRemaining <= 0) {
                setMission(MissionType.FLYOVER); startReturning();
            }
            return;
        }

        // LITTLEBIRD / BLACKHAWK -- PHASE 0: hover ~12 above the drop point at ONE spot and fast-rope the
        // WHOLE payload (troops spawn on the ground below = no fall damage), then switch to orbit-fire.
        if (insertPhase == 0) {
            double hoverY = groundY + 12;
            targetPosition = new Vec3d(attackTarget.getX() + 0.5, hoverY, attackTarget.getZ() + 0.5);
            this.speed = Math.max(0.25f, speed * 0.9f);
            // Only fast-rope once actually ON STATION at the hover point -- never while still arriving.
            boolean onStation = Math.abs(posY - hoverY) < 3.0 && getHorizontalDistanceTo(attackTarget) < 6.0;
            if (onStation && insertTroopsRemaining > 0 && insertHoverTicks % INSERT_DROP_INTERVAL == 0) {
                dropOneTrooper(); insertTroopsRemaining--;
            }
            // Switch to orbit-fire once the payload is delivered, or after a generous safety window.
            if (insertTroopsRemaining <= 0 || insertHoverTicks > 600) {
                insertPhase = 1; insertHoverTicks = 0; rocketsFired = 0; orbitAngle = 0;
            }
            return;
        }

        // PHASE 1: orbit the drop point firing MG + rockets for a fixed window, then depart.
        orbitAngle += 0.05;
        double ox = attackTarget.getX() + 0.5 + Math.cos(orbitAngle) * 30.0;
        double oz = attackTarget.getZ() + 0.5 + Math.sin(orbitAngle) * 30.0;
        targetPosition = new Vec3d(ox, groundY + 18, oz);
        this.speed = Math.max(0.6f, speed);
        if (strafeCooldown <= 0) { fireStrafe(); strafeCooldown = 12; }
        int maxMissiles = this.dataManager.get(MAX_MISSILES);
        if (insertHoverTicks % 40 == 0 && rocketsFired < maxMissiles) { fireRocketAtGround(attackTarget); rocketsFired++; }
        if (insertHoverTicks > INSERT_ORBIT_TICKS) { setMission(MissionType.FLYOVER); startReturning(); }
    }

    /** Chinook extra payload: unload a crewed Flan jeep beside the heli (rival team, engages like other armour). */
    private void dropJeep(int groundY) {
        try {
            int jx = (int) Math.floor(posX) + 2;
            int jz = (int) Math.floor(posZ);
            BlockPos at = world.getTopSolidOrLiquidBlock(new BlockPos(jx, 0, jz));
            studio.ERM.war.vehicle.EntityAIPilot pilot = new studio.ERM.war.vehicle.EntityAIPilot(world);
            pilot.setPosition(at.getX() + 0.5, at.getY() + 1.0, at.getZ() + 0.5);
            pilot.setVehicleType("Jeep");
            pilot.setMcmTeam("empire");
            world.spawnEntity(pilot);
            EpochRunnerMod.logger.info("[AIR] Chinook unloaded a jeep at " + at);
        } catch (Throwable th) {
            EpochRunnerMod.logger.warn("[AIR] jeep unload failed: " + th.getMessage());
        }
    }

    /** Spawn one real EntitySoldier (KSK / SPECIAL loadout), aggroed on the defender. Chinook troops run
     *  out the REAR onto the ground; LittleBird/BlackHawk troops fast-rope BESIDE the heli at heli height
     *  and fall out the side (war soldiers are FALL-immune, see WarFriendlyFireHandler). */
    private void dropOneTrooper() {
        try {
            String t = (getAircraftType() == null) ? "" : getAircraftType().toLowerCase();
            double yawRad = Math.toRadians(rotationYaw + 90);
            double fx = Math.cos(yawRad), fz = Math.sin(yawRad);          // heli forward vector
            BlockPos drop;
            if (t.contains("chinook")) {
                double bx = posX - fx * 3.0, bz = posZ - fz * 3.0;        // behind the rear ramp, on the ground
                BlockPos g = world.getTopSolidOrLiquidBlock(new BlockPos((int) Math.floor(bx), 0, (int) Math.floor(bz)));
                drop = new BlockPos(g.getX(), g.getY(), g.getZ());
            } else {
                double side = (rand.nextBoolean() ? 1.6 : -1.6);          // out the side, AT heli height
                double sx = posX + (-fz) * side, sz = posZ + (fx) * side;
                drop = new BlockPos((int) Math.floor(sx), (int) Math.floor(posY), (int) Math.floor(sz));
            }
            net.minecraft.entity.Entity e = studio.ERM.war.BattleManagers.core.SpawnHelper.spawnPayload(
                    world, drop, "soldier:special", insertTargetUuid,
                    attackTarget /* battle site / home */, insertWarLevel, "SPECIAL", "");
            if (e != null) {
                e.fallDistance = 0.0F;
                EpochRunnerMod.logger.debug("[AIR] inserted trooper at " + drop
                        + " (" + insertTroopsRemaining + " left)");
            }
        } catch (Throwable th) {
            EpochRunnerMod.logger.warn("[AIR] insertion drop failed: " + th.getMessage());
        }
    }

    /**
     * Per-type troop payload: LittleBird 6 / BlackHawk 10 / Chinook 20.
     * Accepts short ids or full "flansmod:" ids, case-insensitive.
     */
    public static int insertionPayloadFor(String type) {
        if (type == null) return 8;
        String t = type.toLowerCase();
        if (t.contains("chinook")) return 20;
        if (t.contains("blackhawk")) return 10;
        if (t.contains("littlebird")) return 6;
        if (t.contains("huey")) return 8;
        return 8;
    }

    /**
     * Fire a rocket/missile at a ground position. Smaller explosion than bombs.
     */
    private void fireRocketAtGround(BlockPos target) {
        if (world.isRemote) return;

        BlockPos groundPos = world.getTopSolidOrLiquidBlock(target);
        float accuracy = this.dataManager.get(ACCURACY);
        double offsetX = (rand.nextDouble() - 0.5) * 6.0 * accuracy;
        double offsetZ = (rand.nextDouble() - 0.5) * 6.0 * accuracy;

        world.newExplosion(this,
                groundPos.getX() + 0.5 + offsetX,
                groundPos.getY(),
                groundPos.getZ() + 0.5 + offsetZ,
                2.5f, true, true);
        // Visible rocket streak from the aircraft to the impact (orange).
        emitTracer(groundPos.getX() + 0.5 + offsetX, groundPos.getY() + 0.5, groundPos.getZ() + 0.5 + offsetZ,
                1.0f, 0.4f, 0.1f);
        // SOUNDBOARD: rocket pair off the rails at the heli + the sharp blast/debris at the impact.
        studio.ERM.war.sound.WarSoundboard.heliRocketFire(world, posX, posY, posZ);
        studio.ERM.war.sound.WarSoundboard.rocketImpact(world,
                groundPos.getX() + 0.5 + offsetX, groundPos.getY(), groundPos.getZ() + 0.5 + offsetZ);
    }

    // ===== WEAPONS =====

    private void dropBomb() {
        if (world.isRemote) return;
        float bombDmg = this.dataManager.get(BOMB_DMG);
        // VISIBLE bomb: drop a primed TNT from the aircraft so the player SEES the bomb fall and detonate,
        // instead of an instant ground explosion appearing from nowhere. The igniter is THIS ghost (RIVAL),
        // so the friendly-fire guard spares the siege's own troops/tanks/aircraft from the blast while the
        // player + the base still take it. Falls back to the old instant blast if TNT can't spawn.
        // SPREAD: scatter each bomb around the aircraft's track (forward along the run + lateral) so a stick
        // of bombs WALKS ACROSS the target instead of every one detonating on the same block ("bombers
        // hitting the same place over and over"). Variation is per-bomb, not per-tick-fixed.
        double sideX = -lastMoveDirection.z, sideZ = lastMoveDirection.x; // perpendicular to the run
        double along = (rand.nextDouble() - 0.2) * 9.0;                   // forward bias along the track
        double lat = (rand.nextDouble() - 0.5) * 11.0;                    // lateral scatter
        double bx = posX + lastMoveDirection.x * along + sideX * lat;
        double bz = posZ + lastMoveDirection.z * along + sideZ * lat;
        try {
            int groundY = world.getTopSolidOrLiquidBlock(new BlockPos((int) Math.floor(bx), 0, (int) Math.floor(bz))).getY();
            net.minecraft.entity.item.EntityTNTPrimed tnt =
                    new net.minecraft.entity.item.EntityTNTPrimed(world, bx, posY - 1.0, bz, this);
            // Fuse must outlast the FALL from high altitude, or the bomb detonates in mid-air ("the TNT
            // despawns before hitting the ground"). Match it to the drop height (with headroom) up to 200t.
            int fuse = (int) Math.max(30, Math.min(200, (posY - groundY) / 1.6 + 12));
            tnt.setFuse(fuse);
            tnt.motionX = lastMoveDirection.x * 0.4 + (rand.nextDouble() - 0.5) * 0.25; // forward throw + jitter
            tnt.motionZ = lastMoveDirection.z * 0.4 + (rand.nextDouble() - 0.5) * 0.25;
            tnt.motionY = -1.0;                       // strong downward kick so it actually reaches the ground
            world.spawnEntity(tnt);
            EpochRunnerMod.logger.info("[AIR] Bomb away (fuse " + fuse + ")");
        } catch (Throwable t) {
            BlockPos g = world.getTopSolidOrLiquidBlock(new BlockPos((int) Math.floor(bx), (int) posY, (int) Math.floor(bz)));
            world.newExplosion(this, g.getX(), g.getY(), g.getZ(), bombDmg, true, true);
        }
    }

    /**
     * Single strafe implementation (uses ACCURACY + doctrine for shot count)
     */
    private void fireStrafe() {
        if (world.isRemote) return;

        // A STRAFING RUN: a straight LINE of explosion PARTICLES walked along the aircraft's heading, with
        // ENTITY damage at each point and ZERO block damage (no world.newExplosion -> the run scars nothing,
        // it just shreds whatever is standing in the line). Fixes "never hits, no effective damage, craters".
        double yawRad = Math.toRadians(rotationYaw + 90);
        double fx = Math.cos(yawRad), fz = Math.sin(yawRad);
        final int points = 14;      // explosions in the line
        final double step = 3.0;    // spacing between them
        final double start = 12.0;  // begins just ahead of the nose (so it walks across the ground below/ahead)
        final float dmg = 6.0f;     // per-hit ENTITY damage
        final double hitR = 2.75;   // entity hit radius per point
        net.minecraft.world.WorldServer ws = (world instanceof net.minecraft.world.WorldServer)
                ? (net.minecraft.world.WorldServer) world : null;

        // One tracer from the aircraft to the middle of the line, so it visibly opens fire.
        double midD = start + points * step * 0.5;
        double mx = posX + fx * midD, mz = posZ + fz * midD;
        BlockPos midG = world.getTopSolidOrLiquidBlock(new BlockPos(mx, 0, mz));
        emitTracer(mx, midG.getY() + 0.5, mz, 1.0f, 0.85f, 0.2f);

        // THE SOUNDBOARD BRRRT: airframe swell at the plane, then the ground-ripping ram-hit ticks walk
        // the strafe line (2 per tick), a near-miss thud every 4th, and the payoff blast at the end --
        // an A10 is heard as the WORLD BEING HIT, never as a gunshot.
        studio.ERM.war.sound.WarSoundboard.gau8Approach(world, posX, posY, posZ);

        for (int i = 0; i < points; i++) {
            double d = start + i * step;
            double cx = posX + fx * d, cz = posZ + fz * d;
            BlockPos g = world.getTopSolidOrLiquidBlock(new BlockPos(cx, 0, cz));
            double gy = g.getY() + 1.0;
            if (ws != null) {
                ws.spawnParticle(net.minecraft.util.EnumParticleTypes.EXPLOSION_LARGE, cx, gy, cz, 1, 0.0, 0.0, 0.0, 0.0);
                ws.spawnParticle(net.minecraft.util.EnumParticleTypes.EXPLOSION_NORMAL, cx, gy, cz, 5, 0.7, 0.35, 0.7, 0.02);
            }
            studio.ERM.war.sound.WarSoundboard.gau8Impact(world, cx, gy, cz, i, i == points - 1);
            // ENTITY DAMAGE ONLY: hit hostiles (the defenders / player) near this point; never the siege's own
            // army (skipped here + the friendly-fire handler cancels same-side damage as a backstop).
            net.minecraft.util.math.AxisAlignedBB box = new net.minecraft.util.math.AxisAlignedBB(
                    cx - hitR, gy - hitR, cz - hitR, cx + hitR, gy + hitR, cz + hitR);
            for (net.minecraft.entity.EntityLivingBase e
                    : world.getEntitiesWithinAABB(net.minecraft.entity.EntityLivingBase.class, box)) {
                if (e == null || e.isDead || isStrafeFriendly(e)) continue;
                e.attackEntityFrom(DamageSource.causeExplosionDamage(this), dmg);
            }
        }
    }

    /** True if the entity is the siege's OWN side -- never strafe your own army. */
    private static boolean isStrafeFriendly(Entity e) {
        return e instanceof EntityGhostAircraft
                || e instanceof studio.ERM.war.BattleManagers.entities.EntitySoldier
                || e instanceof studio.ERM.war.BattleManagers.entities.EntityFormationCarrier
                || e instanceof studio.ERM.war.vehicle.EntityAIPilot;
    }

    /** Draw a coloured TRACER line from the aircraft to an impact point so its fire is visible. */
    private void emitTracer(double tx, double ty, double tz, float r, float g, float b) {
        if (!(world instanceof net.minecraft.world.WorldServer)) return;
        net.minecraft.world.WorldServer ws = (net.minecraft.world.WorldServer) world;
        double dx = tx - posX, dy = ty - posY, dz = tz - posZ;
        double dist = Math.sqrt(dx * dx + dy * dy + dz * dz);
        int n = (int) Math.min(48, Math.max(6, dist / 2));
        for (int i = 1; i <= n; i++) {
            double f = (double) i / n;
            ws.spawnParticle(net.minecraft.util.EnumParticleTypes.REDSTONE,
                    posX + dx * f, posY + dy * f, posZ + dz * f, 0, r, g, b, 1.0D);
        }
    }

    private void fireMissile(Entity target) {
        if (world.isRemote) return;

        target.attackEntityFrom(DamageSource.causeExplosionDamage(this), 30.0f);
        world.newExplosion(this, target.posX, target.posY, target.posZ, 2.0f, true, true);
    }

    // ===== MOVEMENT =====

    /**
     * PILOT AI movement. NOT a ground-mob pathfind -- a flight model built for visual believability:
     *   LAYER 2  smoothed heading: turn toward the target heading at a capped rate (banking + wide turns),
     *            then move FORWARD along that heading (the aircraft can't sidestep to the target).
     *   ALTITUDE terrain-following band: hold desiredY = max(missionY, terrainBelow + clearanceBand), so it
     *            rises over hills/towers and never sinks into the ground; vertical movement is rate-limited.
     *   LAYER 3  forward avoidance: raycast ahead; if blocked, steer toward the clearer side and climb.
     * Insertion near its drop point is exempt (it descends to the ground deliberately).
     */
    private void moveTowardTarget() {
        if (targetPosition == null) return;
        if (flightLane < 0) flightLane = (getEntityId() % 4); // stable per-aircraft altitude lane

        double dx = targetPosition.x - posX;
        double dz = targetPosition.z - posZ;
        double horiz = Math.sqrt(dx * dx + dz * dz);

        boolean heli = isHeli();
        // Insertion intentionally drops to/near the ground -- only exempt terrain-following/avoidance once it
        // is actually CLOSE to the drop (still protect the long approach so it doesn't clip en route).
        boolean lowMission = (mission == MissionType.INSERTION) && horiz < 30;

        // ---- LAYER 2: smoothed heading ----
        float desiredYaw = (horiz > 0.5)
                ? (float) (MathHelper.atan2(dz, dx) * (180D / Math.PI)) - 90.0F
                : this.rotationYaw;
        if (Float.isNaN(headingYaw)) headingYaw = desiredYaw;
        float turn = MathHelper.wrapDegrees(desiredYaw - headingYaw);
        float maxTurn = maxTurnRate();

        // ---- LAYER 3: forward collision avoidance ----
        boolean avoiding = false;
        double avoidClimb = 0;
        if (!lowMission) {
            int look = heli ? 12 : 18;
            if (blockedAhead(headingYaw, look, 2)) {
                boolean leftClear  = !blockedAhead(headingYaw + 40, look, 2);
                boolean rightClear = !blockedAhead(headingYaw - 40, look, 2);
                float steer = leftClear ? 40 : rightClear ? -40 : 25;
                turn = MathHelper.clamp(turn + steer, -maxTurn * 2.2f, maxTurn * 2.2f);
                avoidClimb = 1.0;
                avoidanceActive = 14;
                avoiding = true;
            }
        }

        // While avoiding, grant extra turn authority so the emergency turn is genuinely snappier -- the
        // *2.2 boost on the steer above is REALIZED here instead of being truncated back to the normal max.
        float applyMax = avoiding ? maxTurn * 2.2f : maxTurn;
        headingYaw = MathHelper.wrapDegrees(headingYaw + MathHelper.clamp(turn, -applyMax, applyMax));

        // ---- forward motion along the smoothed heading ----
        double hyaw = Math.toRadians(headingYaw + 90.0);
        double fx = Math.cos(hyaw), fz = Math.sin(hyaw);
        // A plane cannot stop; a heli slows as it closes on its station (no overshoot / no jitter).
        double step = heli ? Math.min(speed, Math.max(0.15, horiz)) : speed;
        double moveX = fx * step;
        double moveZ = fz * step;

        // ---- ALTITUDE: terrain-following band ----
        double terrainY = terrainYBelow();
        double desiredY = targetPosition.y;
        if (!lowMission) {
            double floorY = terrainY + clearanceBand() + flightLane * 5.0; // lane offset deconflicts stacking
            desiredY = Math.max(desiredY, floorY);
        }
        if (avoiding) desiredY += 10.0; // climb over the obstacle
        double dY = desiredY - posY;
        double vRate = (heli ? speed * 0.7 : speed * 0.5) + (avoidanceActive > 0 ? 1.0 : 0.0);
        double moveY = MathHelper.clamp(dY, -vRate, vRate);

        lastMoveDirection = new Vec3d(fx, 0, fz);
        lastMoveY = moveY;
        if (avoidanceActive > 0) avoidanceActive--;

        setPosition(posX + moveX, posY + moveY, posZ + moveZ);

        this.rotationYaw = headingYaw;
        this.rotationYawHead = headingYaw;
        this.rotationPitch = (float) Math.toDegrees(Math.atan2(-moveY, Math.max(0.05, step)));

        debugFlight(terrainY, desiredY, avoiding);
    }

    private double getDistanceToTarget() {
        if (targetPosition == null) return Double.MAX_VALUE;
        // HORIZONTAL distance only: terrain-following deliberately holds a different Y than the waypoint
        // (which is set at an absolute altitude), so a 3D distance would never shrink below the threshold
        // over high ground and the aircraft would stall on a waypoint forever.
        double dx = targetPosition.x - posX;
        double dz = targetPosition.z - posZ;
        return Math.sqrt(dx * dx + dz * dz);
    }

    private double getHorizontalDistanceTo(BlockPos pos) {
        double dx = pos.getX() - posX;
        double dz = pos.getZ() - posZ;
        return Math.sqrt(dx * dx + dz * dz);
    }

    // ── PILOT AI helpers ───────────────────────────────────────────────────────────────────────

    /** Helicopters (incl. gunships/transport/special-ops) turn fast + fly low; planes turn slow + fly high. */
    private boolean isHeli() {
        try {
            AirDoctrine.AircraftRole r = AirDoctrine.getProfile(getAircraftType()).role;
            if (r == AirDoctrine.AircraftRole.ATTACK_HELI || r == AirDoctrine.AircraftRole.GUNSHIP
                    || r == AirDoctrine.AircraftRole.TRANSPORT || r == AirDoctrine.AircraftRole.SPECIAL_OPS) return true;
        } catch (Throwable ignored) {}
        return mission == MissionType.HOVER_STRIKE || mission == MissionType.ORBIT_ATTACK
                || mission == MissionType.CAS_LOITER || mission == MissionType.INSERTION;
    }

    /** Max heading change per tick (degrees). Helis turn fast; planes need wide sweeping turns. */
    private float maxTurnRate() {
        return isHeli() ? 7.0f : 2.6f;
    }

    /** Minimum blocks to hold above the terrain/buildings directly below (the "altitude band" floor). */
    private float clearanceBand() {
        switch (mission) {
            case HOVER_STRIKE: return 22f;
            case ORBIT_ATTACK: return 26f;
            case CAS_LOITER:   return 28f;
            case INSERTION:    return 14f; // only used on the long approach (close-in is exempt)
            default: break;
        }
        return isHeli() ? 20f : 26f; // planes/jets/bombers ride a higher floor over terrain + towers
    }

    /** Highest solid/liquid block in the aircraft's column = the thing it must clear (roofs/trees/towers). */
    private double terrainYBelow() {
        try {
            return world.getTopSolidOrLiquidBlock(
                    new BlockPos(MathHelper.floor(posX), 0, MathHelper.floor(posZ))).getY();
        } catch (Throwable ignored) {}
        return 64;
    }

    /** Forward raycast: is there solid terrain/building within {@code dist} blocks along {@code yaw}? */
    private boolean blockedAhead(float yaw, int dist, int vSpread) {
        double hy = Math.toRadians(yaw + 90.0);
        double fx = Math.cos(hy), fz = Math.sin(hy);
        for (int d = 4; d <= dist; d += 2) {
            for (int vy = -vSpread; vy <= vSpread; vy++) {
                int x = MathHelper.floor(posX + fx * d);
                int y = MathHelper.floor(posY + vy);
                int z = MathHelper.floor(posZ + fz * d);
                try {
                    if (world.getBlockState(new BlockPos(x, y, z)).getMaterial().isSolid()) return true;
                } catch (Throwable ignored) {}
            }
        }
        return false;
    }

    /** Visible flight-state debug: a green particle on the active waypoint, red on an avoidance maneuver,
     *  and a periodic state log (aircraft / state / altitudes / heading / avoidance). Toggle DEBUG_FLIGHT. */
    private void debugFlight(double terrainY, double desiredY, boolean avoiding) {
        if (!DEBUG_FLIGHT || !(world instanceof net.minecraft.world.WorldServer)) return;
        net.minecraft.world.WorldServer ws = (net.minecraft.world.WorldServer) world;
        try {
            if (targetPosition != null) {
                ws.spawnParticle(net.minecraft.util.EnumParticleTypes.VILLAGER_HAPPY,
                        targetPosition.x, targetPosition.y, targetPosition.z, 1, 0.2, 0.2, 0.2, 0.0);
            }
            if (avoiding) {
                ws.spawnParticle(net.minecraft.util.EnumParticleTypes.FLAME,
                        posX + lastMoveDirection.x * 6, posY, posZ + lastMoveDirection.z * 6, 5, 0.4, 0.4, 0.4, 0.0);
            }
            if (ticksOnMission % 40 == 0) {
                String name = getAircraftType();
                if (name != null && name.contains(":")) name = name.substring(name.indexOf(':') + 1);
                EpochRunnerMod.logger.info("[AIRDBG] " + name + "#" + getEntityId() + " STATE=" + mission
                        + " y=" + (int) posY + " terrainY=" + (int) terrainY + " targetY=" + (int) desiredY
                        + " hdg=" + (int) headingYaw + " avoid=" + (avoiding ? "YES" : "no"));
            }
        } catch (Throwable ignored) {}
    }

    private void onWaypointReached() {
        currentWaypointIndex++;
        if (currentWaypointIndex < waypoints.size()) {
            targetPosition = waypoints.get(currentWaypointIndex);
        } else {
            startReturning();
        }
    }

    private void startReturning() {
        if (isReturning) return;

        isReturning = true;
        ticksReturning = 0;
        setMission(MissionType.FLYOVER);

        targetPosition = new Vec3d(
                posX + lastMoveDirection.x * 500,
                altitude + 20,
                posZ + lastMoveDirection.z * 500
        );
    }

    // ===== VISUALS =====

    private void updateVisualAngles() {
        // Roll INTO turns -- the smoothed heading now changes gradually, so the per-tick yaw delta is the
        // turn rate. Bank harder (and ease back toward level) so sweeping turns read clearly from the ground.
        float yawDelta = MathHelper.wrapDegrees(rotationYaw - prevRotationYaw);
        float targetBank = MathHelper.clamp(yawDelta * 6.0f, -55, 55);
        bankAngle += (targetBank - bankAngle) * 0.25f; // smooth the roll so it doesn't snap

        double horizSpeed = Math.sqrt(lastMoveDirection.x * lastMoveDirection.x +
                lastMoveDirection.z * lastMoveDirection.z) * speed;

        if (horizSpeed > 0.01) {
            float targetPitch = MathHelper.clamp(
                    (float) Math.toDegrees(Math.atan2(-lastMoveY, horizSpeed)), -30, 30);
            pitchAngle += (targetPitch - pitchAngle) * 0.25f; // ease pitch too (climb/dive nose attitude)
        }
    }

    public float getBankAngle() { return bankAngle; }
    public float getPitchAngle() { return pitchAngle; }

    // ===== DAMAGE =====

    @Override
    public boolean attackEntityFrom(DamageSource source, float amount) {
        if (crashing) return false; // already going down -- ignore further damage so the crash plays out
        // FRIENDLY-FIRE GUARD. Ghost aircraft are the BESIEGER's air; they must only be downed by the PLAYER
        // (a direct hit) or by AA fire -- NEVER by their own side: the troops they fast-rope in, another
        // friendly aircraft's bombs/strafe, or the siege's own catapult (an anonymous explosion with no
        // source). Previously only other ghost aircraft were spared, so trooper bullets + the catapult +
        // strafe blasts were shooting the planes down ("planes friendly-fired by their own team").
        Entity src = source.getImmediateSource();
        Entity trueSrc = source.getTrueSource();
        // BLOCKLIST (safer than an allowlist -- never accidentally makes the aircraft unkillable by the
        // player's own gun, whatever DamageSource Flan uses). Block our OWN side only:
        //   - other ghost aircraft (bombs/strafe/death blast)
        //   - the troops we fast-rope in + friendly ground vehicles (EntitySoldier / EntityAIPilot)
        //   - the siege's own catapult / any anonymous AoE: an explosion with no PLAYER behind it
        // Everything else (the player's gun/explosives, AA, vanilla) still downs us.
        boolean friendlyUnit = src instanceof EntityGhostAircraft || trueSrc instanceof EntityGhostAircraft
                || isFriendlyGroundUnit(src) || isFriendlyGroundUnit(trueSrc);
        boolean anonExplosion = source.isExplosion() && !(trueSrc instanceof net.minecraft.entity.player.EntityPlayer);
        if (friendlyUnit || anonExplosion) return false;

        if (src != null && aaSource(src.getName())) amount *= 2.0f; // AA hits harder

        boolean result = super.attackEntityFrom(source, amount);

        // ONE-SHOT: attackEntityFrom fires several times once health hits 0 (the death explosion + any
        // other hits the same tick), which printed "You shot down ..." 4x per plane. Guard it.
        if (getHealth() <= 0 && !aircraftDestroyed) {
            aircraftDestroyed = true;
            onAircraftDestroyed(trueSrc != null ? trueSrc : src);
        }

        return result;
    }

    /** True if the entity is one of the siege's OWN ground units (its troops or AI-crewed vehicles). */
    private static boolean isFriendlyGroundUnit(Entity e) {
        return e instanceof studio.ERM.war.BattleManagers.entities.EntitySoldier
                || e instanceof studio.ERM.war.vehicle.EntityAIPilot;
    }

    /** True if a damage source NAME looks like anti-aircraft fire (the one non-player thing allowed to down us). */
    private static boolean aaSource(String n) {
        if (n == null) return false;
        n = n.toLowerCase();
        return n.contains("aa") || n.contains("flak") || n.contains("bofors") || n.contains("ack") || n.contains("sam");
    }

    private boolean aircraftDestroyed = false;
    // Death-crash state: when shot down, the aircraft spins around Y and falls to the ground, then
    // detonates and leaves an iron-bar/rubble wreck (instead of vanishing in a mid-air puff).
    private boolean crashing = false;
    private int crashTicks = 0;
    private boolean crashIsHeli = false; // helis SPIN down; planes nose-dive forward
    private double crashFallSpeed = 0.0;  // plane descent accelerates as it dives

    private void onAircraftDestroyed(Entity killer) {
        EpochRunnerMod.logger.info("[AIR] Aircraft destroyed: " + getAircraftType());
        boolean heli = false;
        try {
            AirDoctrine.AircraftRole role = AirDoctrine.getProfile(getAircraftType()).role;
            heli = role == AirDoctrine.AircraftRole.ATTACK_HELI || role == AirDoctrine.AircraftRole.GUNSHIP
                    || role == AirDoctrine.AircraftRole.TRANSPORT || role == AirDoctrine.AircraftRole.SPECIAL_OPS;
        } catch (Throwable ignored) {}

        // Begin the death CRASH: keep the entity alive (health 1) so onUpdate can fly it down to the
        // ground, where crashLand() detonates it. Helicopters SPIN down; planes nose-dive forward.
        crashing = true;
        crashTicks = 0;
        crashIsHeli = heli;
        crashFallSpeed = 0.0;
        isOnMission = false;
        this.setHealth(1.0F);
        this.setNoGravity(true);

        // Death message to the player who shot it down (planes + helis get their own, like the tank kill msg).
        if (!world.isRemote && killer instanceof net.minecraft.entity.player.EntityPlayer) {
            String name = getAircraftType();
            if (name != null && name.contains(":")) name = name.substring(name.indexOf(':') + 1);
            String kind = heli ? "helicopter" : "aircraft";
            ((net.minecraft.entity.player.EntityPlayer) killer).sendMessage(
                    new net.minecraft.util.text.TextComponentString(
                            net.minecraft.util.text.TextFormatting.AQUA + "✈ You shot down the enemy "
                                    + kind + " (" + name + ")"));
        }
    }

    /** Death fall. Helicopters SPIN around Y and drop straight down; planes keep flying FORWARD along the
     *  last heading and nose-dive, accelerating downward. Either way -> crashLand() on ground contact. */
    private void tickCrash() {
        crashTicks++;
        int groundY = world.getTopSolidOrLiquidBlock(getPosition()).getY();
        if (crashIsHeli) {
            rotationYaw += 28.0F;            // dramatic flat spin (rotationYaw syncs to clients)
            rotationYawHead = rotationYaw;
            rotationPitch = Math.min(45.0F, rotationPitch + 2.0F);
            double newY = Math.max(groundY + 1.0, posY - 0.9);
            setPosition(posX, newY, posZ);
        } else {
            // PLANE: no spin -- continue forward along the last heading and accelerate the descent.
            crashFallSpeed += 0.06;
            double fwd = Math.max(1.2, speed);
            double nx = posX + lastMoveDirection.x * fwd;
            double nz = posZ + lastMoveDirection.z * fwd;
            double newY = Math.max(groundY + 1.0, posY - crashFallSpeed);
            rotationPitch = Math.min(70.0F, rotationPitch + 3.0F); // nose drops into the dive
            setPosition(nx, newY, nz);
        }
        if (posY <= groundY + 1.2 || crashTicks > 120) crashLand(groundY);
    }

    /** Impact. Helicopters detonate harder and leave an iron-bar + rubble wreck; planes make a SMALL
     *  explosion (they hit fast + forward, less wreckage). Then die. */
    private void crashLand(int groundY) {
        try {
            float power = crashIsHeli ? 3.5F : 2.0F;
            world.newExplosion(this, posX, groundY + 1, posZ, power, true, true);
            int debris = crashIsHeli ? 6 : 2;
            for (int i = 0; i < debris; i++) {
                int rx = (int) Math.floor(posX) + rand.nextInt(5) - 2;
                int rz = (int) Math.floor(posZ) + rand.nextInt(5) - 2;
                BlockPos p = world.getTopSolidOrLiquidBlock(new BlockPos(rx, 0, rz));
                if (world.isAirBlock(p)) {
                    world.setBlockState(p, (rand.nextBoolean()
                            ? net.minecraft.init.Blocks.IRON_BARS
                            : net.minecraft.init.Blocks.COBBLESTONE).getDefaultState(), 2);
                }
            }
        } catch (Throwable ignored) {}
        setDead();
    }

    // ===== NBT =====

    @Override
    public void writeEntityToNBT(NBTTagCompound compound) {
        super.writeEntityToNBT(compound);
        compound.setString("aircraftType", getAircraftType());
        compound.setString("team", getMcmTeam());
        compound.setInteger("mission", mission.ordinal());
        compound.setInteger("bombs", bombsRemaining);
        compound.setFloat("altitude", altitude);
        compound.setFloat("speed", speed);
        compound.setBoolean("onMission", isOnMission);
        compound.setBoolean("returning", isReturning);
        compound.setDouble("lastMoveX", lastMoveDirection.x);
        compound.setDouble("lastMoveZ", lastMoveDirection.z);
    }

    @Override
    public void readEntityFromNBT(NBTTagCompound compound) {
        super.readEntityFromNBT(compound);

        String type = compound.getString("aircraftType");
        String team = compound.getString("team");

        // init data first
        this.dataManager.set(AIRCRAFT_TYPE, (type == null || type.isEmpty()) ? "bf109" : type);
        this.dataManager.set(TEAM, (team == null || team.isEmpty()) ? "ENEMY" : team);

        applyTypeStats(this.dataManager.get(AIRCRAFT_TYPE));

        mission = MissionType.values()[compound.getInteger("mission")];
        this.dataManager.set(MISSION_TYPE, mission.ordinal());

        bombsRemaining = compound.getInteger("bombs");
        this.dataManager.set(BOMBS, bombsRemaining);

        altitude = compound.getFloat("altitude");
        speed = compound.getFloat("speed");
        isOnMission = compound.getBoolean("onMission");
        isReturning = compound.getBoolean("returning");

        if (compound.hasKey("lastMoveX")) {
            lastMoveDirection = new Vec3d(compound.getDouble("lastMoveX"), 0, compound.getDouble("lastMoveZ"));
        }
    }

    // ===== COLLISIONS / DESPAWN =====

    @Override
    protected boolean canDespawn() { return false; }

    @Override
    public boolean canBePushed() { return false; }

    @Override
    protected void collideWithEntity(Entity entityIn) { }

    @Override
    public boolean canBeCollidedWith() { return true; }

    private void handleDespawn() {
        // Snapshot player list to avoid CME if it changes during iteration
        List<Entity> players = new ArrayList<>(world.playerEntities);

        // Hard timeout: any aircraft alive too long gets killed regardless of state
        if (ticksOnMission > 3000) {
            setDead();
            return;
        }

        // If no players in world, despawn quickly
        if (players.isEmpty()) {
            if (ticksOnMission > 100) setDead();
            return;
        }

        // Find nearest player
        double nearestPlayer = Double.MAX_VALUE;
        for (Entity e : players) {
            if (e == null || e.isDead) continue;
            nearestPlayer = Math.min(nearestPlayer, getDistance(e));
        }

        // Far from all players — despawn immediately (avoids floating in unloaded chunks)
        if (nearestPlayer > 180) {
            setDead();
            return;
        }

        // Returning aircraft: shorter timeout
        if (isReturning) {
            ticksReturning++;
            if (ticksReturning > 400) setDead();
            if (nearestPlayer > 120) setDead();
        }
    }

    // ===== FLANS PUPPET =====

    private void spawnFlansPuppet() {
        // DISABLED -- spawning a live Flan EntityPlane CRASHES the server. A real Flan plane (and the
        // seat + wheel entities its constructor auto-spawns) runs Flan's full flight physics every
        // tick, which NPEs without a real player pilot (EntityPlane.onUpdate -> "Ticking entity"
        // crash). noClip/noGravity/position-dragging does NOT bypass that tick. Airstrike aircraft are
        // therefore drawn by the entity RENDERER and no live driveable is ever constructed. Kept as a
        // no-op so the onUpdate call site stays valid and flansVehiclePuppet stays null.
        puppetSpawned = true;
    }

    /** Resolve a Flan PlaneType from a ShortName, with the same fallbacks RenderGhostAircraft uses. */
    private com.flansmod.common.driveables.PlaneType resolvePlaneType(String shortName) {
        try {
            com.flansmod.common.driveables.PlaneType t =
                    com.flansmod.common.driveables.PlaneType.getPlane(shortName);
            if (t != null) return t;
        } catch (Throwable ignored) {}
        try {
            for (com.flansmod.common.driveables.PlaneType t : com.flansmod.common.driveables.PlaneType.types) {
                if (t != null && t.shortName != null && t.shortName.equalsIgnoreCase(shortName)) return t;
            }
        } catch (Throwable ignored) {}
        return null;
    }

    private void syncPuppetPosition() {
        if (flansVehiclePuppet == null || flansVehiclePuppet.isDead) {
            flansVehiclePuppet = null;
            return;
        }

        puppetSyncTicks++;
        if (puppetSyncTicks < PUPPET_SYNC_INTERVAL) return;
        puppetSyncTicks = 0;

        flansVehiclePuppet.setPosition(posX, posY, posZ);
        flansVehiclePuppet.rotationYaw = rotationYaw;
        flansVehiclePuppet.rotationPitch = pitchAngle;
        flansVehiclePuppet.prevRotationYaw = prevRotationYaw;
        flansVehiclePuppet.prevRotationPitch = pitchAngle;

        flansVehiclePuppet.motionX = motionX;
        flansVehiclePuppet.motionY = motionY;
        flansVehiclePuppet.motionZ = motionZ;

        flansVehiclePuppet.noClip = true;
        flansVehiclePuppet.setNoGravity(true);
    }

    @Override
    public void setDead() {
        super.setDead();
        // Take the dragged Flan plane (and its seats) with us so airstrikes don't leave wrecks behind.
        if (flansVehiclePuppet != null && !flansVehiclePuppet.isDead) {
            try { flansVehiclePuppet.setDead(); } catch (Throwable ignored) {}
        }
        flansVehiclePuppet = null;
    }

    public Entity getFlansPuppet() {
        return flansVehiclePuppet;
    }

    /**
     * Sets the player this aircraft should focus on (for orbit/deploy anchoring).
     */
    public void setTargetPlayer(net.minecraft.entity.player.EntityPlayer player) {
        // Used by AirStrikeController for orbit/deploy anchoring
        if (player != null) {
            this.setAttackTarget(player);
        }
    }

    /**
     * Apply a strike profile from WarAirstrikeHelper to this aircraft.
     */
    public void applyStrikeProfile(WarAirstrikeHelper.StrikeProfile profile, net.minecraft.util.math.BlockPos target) {
        if (profile == null) return;
        this.altitude = (float) profile.altitude + 30f; // +30 cruise: clears towers, stops ~90% of building collisions
        this.speed = Math.max(0.4f, (float) profile.speed);
        this.setMission(profile.mission);
    }
}
