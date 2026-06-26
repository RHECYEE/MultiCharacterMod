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
        CAS_LOITER
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
                this.setSize(8.0F, 3.0F);
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

    // ===== UPDATE =====
    @Override
    public void onUpdate() {
        super.onUpdate();

        // SERVER: mission + movement only
        if (!this.world.isRemote) {
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
            default:
                break;
        }
    }

    private void executeBombingRun() {
        if (attackTarget == null) return;
        if (bombsRemaining <= 0) return;

        double horizDist = getHorizontalDistanceTo(attackTarget);
        if (horizDist < 20 && posY > attackTarget.getY() + 10) {
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
    }

    // ===== WEAPONS =====

    private void dropBomb() {
        if (world.isRemote) return;

        BlockPos groundPos = world.getTopSolidOrLiquidBlock(getPosition());
        float bombDmg = this.dataManager.get(BOMB_DMG);

        world.newExplosion(this,
                groundPos.getX(), groundPos.getY(), groundPos.getZ(),
                bombDmg, true, true);

        EpochRunnerMod.logger.info("[AIR] Bomb dropped at " + groundPos);
    }

    /**
     * Single strafe implementation (uses ACCURACY + doctrine for shot count)
     */
    private void fireStrafe() {
        if (world.isRemote) return;

        AirDoctrine.AircraftProfile p = AirDoctrine.getProfile(getAircraftType());
        float accuracy = this.dataManager.get(ACCURACY); // higher = more spread, per your comment

        double yawRad = Math.toRadians(rotationYaw + 90);
        double forwardX = Math.cos(yawRad);
        double forwardZ = Math.sin(yawRad);

        int shots = (p.attackPattern == AirDoctrine.AttackPattern.GUN_RUN) ? 12 : 6;
        float radius = (p.attackPattern == AirDoctrine.AttackPattern.GUN_RUN) ? 0.9f : 0.6f;

        for (int i = 0; i < shots; i++) {
            double spread = (rand.nextDouble() - 0.5) * 10.0 * accuracy;
            double range = 55 + rand.nextInt(20);

            double targetX = posX + forwardX * range + spread;
            double targetZ = posZ + forwardZ * range + spread;

            BlockPos groundPos = world.getTopSolidOrLiquidBlock(new BlockPos(targetX, 0, targetZ));

            world.newExplosion(null,
                    groundPos.getX() + 0.5, groundPos.getY(), groundPos.getZ() + 0.5,
                    radius, false, true);
        }
    }

    private void fireMissile(Entity target) {
        if (world.isRemote) return;

        target.attackEntityFrom(DamageSource.causeExplosionDamage(this), 30.0f);
        world.newExplosion(this, target.posX, target.posY, target.posZ, 2.0f, true, true);
    }

    // ===== MOVEMENT =====

    private void moveTowardTarget() {
        if (targetPosition == null) return;

        double dx = targetPosition.x - posX;
        double dy = targetPosition.y - posY;
        double dz = targetPosition.z - posZ;
        double dist = Math.sqrt(dx * dx + dy * dy + dz * dz);

        if (dist > 0.1) {
            double moveX = (dx / dist) * speed;
            double moveY = (dy / dist) * speed * 0.5;
            double moveZ = (dz / dist) * speed;

            double horizDist = Math.sqrt(moveX * moveX + moveZ * moveZ);
            if (horizDist > 0.01) {
                lastMoveDirection = new Vec3d(moveX / horizDist, 0, moveZ / horizDist);
            }
            lastMoveY = moveY;

            setPosition(posX + moveX, posY + moveY, posZ + moveZ);

            float targetYaw = (float) (MathHelper.atan2(dz, dx) * (180D / Math.PI)) - 90.0F;
            this.rotationYaw = targetYaw;
            this.rotationYawHead = targetYaw;

            this.rotationPitch = (float) Math.toDegrees(Math.atan2(-moveY, horizDist));
        }
    }

    private double getDistanceToTarget() {
        if (targetPosition == null) return Double.MAX_VALUE;
        return new Vec3d(posX, posY, posZ).distanceTo(targetPosition);
    }

    private double getHorizontalDistanceTo(BlockPos pos) {
        double dx = pos.getX() - posX;
        double dz = pos.getZ() - posZ;
        return Math.sqrt(dx * dx + dz * dz);
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
        float yawDelta = MathHelper.wrapDegrees(rotationYaw - prevRotationYaw);
        bankAngle = MathHelper.clamp(yawDelta * 2, -45, 45);

        double horizSpeed = Math.sqrt(lastMoveDirection.x * lastMoveDirection.x +
                lastMoveDirection.z * lastMoveDirection.z) * speed;

        if (horizSpeed > 0.01) {
            pitchAngle = (float) Math.toDegrees(Math.atan2(-lastMoveY, horizSpeed));
            pitchAngle = MathHelper.clamp(pitchAngle, -30, 30);
        }
    }

    public float getBankAngle() { return bankAngle; }
    public float getPitchAngle() { return pitchAngle; }

    // ===== DAMAGE =====

    @Override
    public boolean attackEntityFrom(DamageSource source, float amount) {
        if (source.getImmediateSource() != null) {
            String sourceName = source.getImmediateSource().getName().toLowerCase();
            if (sourceName.contains("aa") || sourceName.contains("flak") || sourceName.contains("bofors")) {
                amount *= 2.0f;
            }
        }

        boolean result = super.attackEntityFrom(source, amount);

        if (getHealth() <= 0) {
            onAircraftDestroyed();
        }

        return result;
    }

    private void onAircraftDestroyed() {
        world.newExplosion(this, posX, posY, posZ, 3.0f, true, true);
        EpochRunnerMod.logger.info("[AIR] Aircraft destroyed: " + getAircraftType());
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
        this.altitude = (float) profile.altitude;
        this.speed = Math.max(0.4f, (float) profile.speed);
        this.setMission(profile.mission);
    }
}
