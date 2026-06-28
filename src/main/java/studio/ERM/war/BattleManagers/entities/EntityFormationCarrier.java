package studio.ERM.war.BattleManagers.entities;

import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityCreature;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.entity.SharedMonsterAttributes;
import net.minecraft.entity.projectile.EntityTippedArrow;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.pathfinding.PathNodeType;
import net.minecraft.util.DamageSource;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.RayTraceResult;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.World;
import studio.ERM.EpochRunnerMod;
import studio.ERM.war.BattleManagers.cards.SlotPayload;
import studio.ERM.war.BattleManagers.cards.UnitCard;
import studio.ERM.war.BattleManagers.core.SpawnHelper;
import studio.ERM.war.skins.SkinPoolManager;

import java.util.UUID;
import studio.ERM.war.BattleManagers.core.UnitCompositionResolver;

import java.util.ArrayList;
import java.util.List;

/**
 * v8 — Formation carrier that releases EntitySoldier instances on dismount.
 *
 * Changes from v7:
 *   - tickRelease() now passes warLevel, card-derived role, and skinKey to SpawnHelper
 *   - SpawnHelper creates EntitySoldier with proper loadout instead of broken AW2 reflection
 *   - Puppets' skin keys are transferred to soldiers for visual continuity
 */
public class EntityFormationCarrier extends EntityCreature {

    // Ranges
    private int activationRange = 120;
    private int releaseRange = 30;

    // Release pacing
    private int releasePerSecond = 4;

    // CONTACT SLICE (director-managed formations only). Instead of dumping the whole squad into real
    // combat-AI soldiers the instant the line touches the objective -- which spawned 100-300 soldiers in
    // a second and devolved the siege into "minecraft madness" -- a director formation keeps its puppets
    // in formation and feeds only a small, capped slice of real fighters into contact, replenishing them
    // as they fall. The bulk of the unit stays a controlled, tight, advancing puppet block (Total War).
    private final List<Integer> contactIds = new ArrayList<>();
    private int contactCooldown = 0;
    private int contactSliceCap = 3;

    // Rout
    private float routHealthThreshold = 0.25F;

    // Orbit debug mode
    private boolean orbitEnabled = false;
    private boolean orbitLockPuppets = false;
    private BlockPos orbitCenter = BlockPos.ORIGIN;
    private double orbitRadius = 10.0D;
    private double orbitAngularSpeed = 0.04D;
    private double orbitAngle = 0.0D;

    // Suppression / volley
    private BlockPos suppressionTarget = null;
    private int volleyCooldownTicks = 40;
    private int volleyBurstCount = 6;
    private int volleyCooldown = 0;

    // Card + slots
    private String cardName = "";
    private int warLevel = 1;
    private boolean supportsVolley = false;

    private final List<SlotPayload> slotPayloads = new ArrayList<>();
    private final List<Integer> puppetEntityIds = new ArrayList<>();
    private int initialPuppetCount = 0;
    private final List<Vec3d> slotOffsets = new ArrayList<>();

    // Battle context (server-side)
    private UUID battleTargetPlayerUuid = null;
    private BlockPos battleSite = null;

    // When true, this carrier belongs to a director-managed FORMATION (e.g. a siege battle line):
    // it advances and holds under explicit director orders, commits its troops only once it actually
    // reaches the objective (battleSite / the breach), and NEVER auto-routs into an every-soldier-
    // for-himself chase of the player. This is what keeps a siege reading as cohesive army lines
    // instead of scattered packets that dismount and sprint at the defender.
    private boolean directorManaged = false;

    // When true this carrier is an ENGINEER construction crew: it NEVER releases combat soldiers and
    // never fights, no matter how close it gets to the objective. Its puppets are just a visual crew;
    // all its real work (bridging/laddering/breaching) is driven block-by-block by the SiegeDirector.
    // Without this, an engineer reaching the wall (inside releaseRange of the base centre) tripped the
    // contact-slice release and "turned into a dumb hostile mob chasing the player" instead of working.
    private boolean engineerMode = false;

    public EntityFormationCarrier(World worldIn) {
        super(worldIn);
        this.setSize(1.2F, 1.9F);
        this.enablePersistence();
        this.setPathPriority(PathNodeType.WATER, -1.0F);
        this.experienceValue = 0;
        // The carrier is only the FORMATION ANCHOR -- the visible bodies are its armoured puppets, which
        // surround it. Rendering the anchor too drew a default-skin, un-armoured "Steve" standing in the
        // centre of every formation (the player's report). Hide it; the puppets are the squad.
        this.setInvisible(true);
    }

    @Override
    protected void initEntityAI() {
        // Carrier uses navigation only; no combat AI.
    }

    @Override
    protected void applyEntityAttributes() {
        super.applyEntityAttributes();
        this.getEntityAttribute(SharedMonsterAttributes.MAX_HEALTH).setBaseValue(40.0D);
        this.getEntityAttribute(SharedMonsterAttributes.MOVEMENT_SPEED).setBaseValue(0.22D);
        this.getEntityAttribute(SharedMonsterAttributes.FOLLOW_RANGE).setBaseValue(64.0D);
    }

    public void configureFromCard(UnitCard card, int warLevel) {
        if (card == null) return;

        this.cardName = card.getName();
        this.warLevel = Math.max(1, warLevel);
        this.supportsVolley = card.supportsVolley();

        this.slotOffsets.clear();
        this.slotOffsets.addAll(card.getSpacingProfile().buildOffsets(card.getSlotCount()));

        this.slotPayloads.clear();
        this.slotPayloads.addAll(UnitCompositionResolver.buildSlotPayloads(card, this.warLevel));

        if (!world.isRemote) {
            spawnPuppets();
        }
    }

    public void setSuppressionTarget(BlockPos pos) {
        this.suppressionTarget = pos;
    }

    /** Mark this carrier as part of a director-managed formation (see {@link #directorManaged}). */
    public void setDirectorManaged(boolean managed) {
        this.directorManaged = managed;
    }

    /** Mark this carrier as an ENGINEER construction crew that never releases combat soldiers. */
    public void setEngineerMode(boolean engineer) {
        this.engineerMode = engineer;
    }

    public boolean isEngineerMode() { return engineerMode; }

    /** Size of the simultaneous contact slice this formation commits (e.g. a cavalry CHARGE wants more). */
    public void setContactSliceCap(int cap) {
        this.contactSliceCap = Math.max(1, cap);
    }

    /**
     * Director-driven commit: release up to {@code count} of this carrier's puppets as real soldiers
     * RIGHT NOW, regardless of player proximity. Lets the SiegeDirector promote only a small "contact
     * slice" of an otherwise-visual formation when the line reaches the wall, instead of every carrier
     * dumping its whole squad. Returns the number actually released.
     */
    public int releaseContactSlice(int count) {
        if (world.isRemote || count <= 0) return 0;
        return releasePuppets(count);
    }

    /**
     * Bind this carrier to a battle so that released units immediately acquire a target and engage.
     */
    public void setBattleContext(EntityPlayer player, BlockPos battleSite) {
        if (world.isRemote) return;
        this.battleTargetPlayerUuid = (player == null) ? null : player.getUniqueID();
        this.battleSite = battleSite;
    }

    public void setMoveTarget(BlockPos target, double speed) {
        if (target == null || world.isRemote) return;

        // An explicit move order cancels any holding orbit. Without this, tickOrbit() would
        // override tryMoveToXYZ every tick and the carrier could never actually advance —
        // a phased director's "release the staging force" order would silently do nothing.
        this.orbitEnabled = false;
        this.orbitLockPuppets = false;

        this.getNavigator().tryMoveToXYZ(
            target.getX() + 0.5D,
            target.getY(),
            target.getZ() + 0.5D,
            speed > 0 ? speed / 0.22D : 1.0D
        );
    }

    public void setOrbitMode(BlockPos center, double radius, double angularSpeed) {
        setOrbitMode(center, radius, angularSpeed, false);
    }

    public void setOrbitMode(BlockPos center, double radius, double angularSpeed, boolean lockPuppets) {
        this.orbitEnabled = true;
        this.orbitLockPuppets = lockPuppets;
        this.orbitCenter = center == null ? BlockPos.ORIGIN : center;
        this.orbitRadius = Math.max(2.0D, radius);

        double s = angularSpeed;
        if (Math.abs(s) < 0.001D) {
            s = (s < 0.0D) ? -0.001D : 0.001D;
        }
        this.orbitAngularSpeed = s;
    }

    public Vec3d rotateOffset(Vec3d offset) {
        if (offset == null) return Vec3d.ZERO;
        double yawRad = Math.toRadians(-this.rotationYaw);
        double cos = Math.cos(yawRad);
        double sin = Math.sin(yawRad);
        double x = offset.x * cos - offset.z * sin;
        double z = offset.x * sin + offset.z * cos;
        return new Vec3d(x, offset.y, z);
    }

    @Override
    public void onUpdate() {
        super.onUpdate();

        if (world.isRemote) return;

        pruneDeadPuppets();

        Entity nearestPlayer = world.getClosestPlayerToEntity(this, activationRange);
        if (nearestPlayer == null) {
            if (orbitEnabled) tickOrbit();
            return;
        }

        if (orbitEnabled) tickOrbit();

        // In locked orbit mode, do NOT release and do NOT auto-rout.
        if (orbitLockPuppets) return;

        // Volley outside release range
        if (supportsVolley && suppressionTarget != null) {
            double d = this.getDistance(nearestPlayer);
            if (d > releaseRange) {
                tickVolley(nearestPlayer);
            }
        }

        // Release troops only once the carrier is within melee releaseRange of a player -- i.e. when
        // the advancing formation actually REACHES the fight. The director (now that BattleEngine
        // actually ticks again) drives carriers inward each phase via pressToward()/chasePlayer(), so
        // a staging army on the 80-block ring visibly marches in and deploys when it arrives. (An
        // earlier "release as soon as a battle is active" trigger dumped soldiers straight onto the
        // outer ring -- spawning them inside mountainsides where they instantly suffocated, and making
        // troops pop out of thin air instead of an approaching army. Reverted.) Until then keep the
        // visible puppet squad sized to the health bar so it doesn't thin out as the carrier is hit.
        // Director-managed formations (siege battle lines) ignore player proximity entirely: they
        // hold visual strength and commit troops ONLY when the line reaches its objective (the
        // breach / battleSite), driven there by the director. They never auto-rout into a chase --
        // that is what made sieges dissolve into a mob sprinting at the defender.
        if (directorManaged) {
            // ENGINEER crews never fight -- they are construction units driven by the director. Hold the
            // visual puppet squad sized to health and NEVER release combat soldiers (no contact slice).
            if (engineerMode) { syncPuppetCountToHealth(); return; }
            boolean atObjective = battleSite != null
                    && this.getDistanceSq(battleSite.getX() + 0.5, battleSite.getY(), battleSite.getZ() + 0.5)
                       <= (double) (releaseRange * releaseRange);
            if (atObjective) {
                tickContactSlice(); // controlled trickle of fighters; the rest hold formation
            } else {
                syncPuppetCountToHealth();
            }
            return;
        }

        double dist = this.getDistance(nearestPlayer);
        if (dist <= releaseRange) {
            tickRelease();
        } else {
            syncPuppetCountToHealth();
        }

        // Rout: convert the survivors of a broken formation into real soldiers for a
        // last stand instead of deleting the whole squad outright.
        if (this.getHealth() / this.getMaxHealth() < routHealthThreshold) {
            releaseSurvivors();
            setDead();
        }
    }

    private void tickOrbit() {
        orbitAngle += orbitAngularSpeed;
        if (orbitAngle > Math.PI * 2) orbitAngle -= Math.PI * 2;
        if (orbitAngle < 0) orbitAngle += Math.PI * 2;

        double tx = orbitCenter.getX() + 0.5D + Math.cos(orbitAngle) * orbitRadius;
        double tz = orbitCenter.getZ() + 0.5D + Math.sin(orbitAngle) * orbitRadius;

        this.getNavigator().tryMoveToXYZ(tx, orbitCenter.getY(), tz, 1.0D);

        double dx = -Math.sin(orbitAngle);
        double dz = Math.cos(orbitAngle);
        this.rotationYaw = (float) (Math.toDegrees(Math.atan2(dz, dx)) - 90.0D);
    }

    private void tickVolley(Entity nearestPlayer) {
        if (volleyCooldown > 0) {
            volleyCooldown--;
            return;
        }

        Vec3d from = new Vec3d(this.posX, this.posY + this.getEyeHeight(), this.posZ);
        Vec3d to = new Vec3d(suppressionTarget.getX() + 0.5, suppressionTarget.getY() + 0.5, suppressionTarget.getZ() + 0.5);

        RayTraceResult hit = world.rayTraceBlocks(from, to, false, true, false);
        if (hit != null && hit.typeOfHit == RayTraceResult.Type.BLOCK) {
            volleyCooldown = 10;
            return;
        }

        for (int i = 0; i < volleyBurstCount; i++) {
            double scatter = 2.5D;
            double sx = (rand.nextDouble() - 0.5D) * scatter;
            double sz = (rand.nextDouble() - 0.5D) * scatter;
            double sy = (rand.nextDouble() - 0.5D) * 0.6D;

            Vec3d tgt = to.add(sx, sy, sz);

            EntityTippedArrow arrow = new EntityTippedArrow(world);
            arrow.setPosition(from.x, from.y, from.z);

            double vx = tgt.x - from.x;
            double vy = tgt.y - from.y;
            double vz = tgt.z - from.z;

            arrow.shoot(vx, vy, vz, 2.2F, 12.0F);
            arrow.pickupStatus = net.minecraft.entity.projectile.EntityArrow.PickupStatus.DISALLOWED;

            world.spawnEntity(arrow);
        }

        volleyCooldown = volleyCooldownTicks;
    }

    /**
     * v8 — Release puppets as EntitySoldier instances with full loadout and skin.
     *
     * Key changes:
     *   - Derives role from cardName via SoldierLoadout.roleFromCardName()
     *   - Passes warLevel and skinKey to SpawnHelper
     *   - SpawnHelper now creates EntitySoldier (not broken AW2 reflection)
     */
    private void tickRelease() {
        releasePuppets(Math.max(1, releasePerSecond / 20));
    }

    /** Release up to {@code max} puppets as real EntitySoldiers. Returns the count actually released. */
    private int releasePuppets(int max) {
        int toRelease = Math.max(0, max);
        int releasedThisTick = 0;

        // Determine the role from the card name
        String loadoutRole = SoldierLoadout.roleFromCardName(this.cardName);

        for (int i = 0; i < slotPayloads.size() && releasedThisTick < toRelease; i++) {
            if (i >= puppetEntityIds.size()) break;

            int puppetId = puppetEntityIds.get(i);
            Entity p = world.getEntityByID(puppetId);
            if (!(p instanceof EntitySoldierPuppet)) continue;

            EntitySoldierPuppet puppet = (EntitySoldierPuppet) p;
            SlotPayload payload = slotPayloads.get(i);
            BlockPos spawnPos = new BlockPos(p.posX, p.posY, p.posZ);

            // Transfer skin from puppet to soldier
            String puppetSkin = puppet.getSkinKey();

            try {
                // Use the extended SpawnHelper that creates EntitySoldier
                SpawnHelper.spawnPayload(
                    world, spawnPos,
                    payload.getId(),
                    battleTargetPlayerUuid, battleSite,
                    this.warLevel, loadoutRole, puppetSkin
                );
            } catch (Throwable t) {
                EpochRunnerMod.logger.error("[BattleManagers] Release spawn error for payload {}: {}", payload, t.getMessage());
            }

            // Remove puppet
            p.setDead();
            releasedThisTick++;
        }

        pruneDeadPuppets();

        // If all puppets are gone, carrier is done.
        if (puppetEntityIds.isEmpty()) {
            setDead();
        }
        return releasedThisTick;
    }

    /**
     * Controlled commitment for a director formation: keep at most {@link #contactSliceCap} real
     * fighters in the fight at once, replenishing one at a time (on a cooldown) as they fall. The rest
     * of the unit stays a tight puppet block that thins with the carrier's health. This is what turns a
     * siege line into an advancing Total-War formation instead of a one-second flood of loose soldiers.
     */
    private void tickContactSlice() {
        for (int i = contactIds.size() - 1; i >= 0; i--) {       // forget fallen fighters
            Entity e = world.getEntityByID(contactIds.get(i));
            if (e == null || e.isDead) contactIds.remove(i);
        }
        if (contactCooldown > 0) contactCooldown--;
        if (contactCooldown <= 0 && contactIds.size() < contactSliceCap && !puppetEntityIds.isEmpty()) {
            Entity s = releaseOnePuppet();
            if (s != null) { contactIds.add(s.getEntityId()); contactCooldown = 25; }
        }
        // The unreleased remainder still visibly thins as the formation takes damage.
        syncPuppetCountToHealth();
    }

    /** Release exactly ONE front-slot puppet as a real soldier and return it (for contact tracking). */
    private Entity releaseOnePuppet() {
        String loadoutRole = SoldierLoadout.roleFromCardName(this.cardName);
        Entity spawned = null;
        for (int i = 0; i < slotPayloads.size() && i < puppetEntityIds.size(); i++) {
            int puppetId = puppetEntityIds.get(i);
            Entity p = world.getEntityByID(puppetId);
            if (!(p instanceof EntitySoldierPuppet)) continue;
            EntitySoldierPuppet puppet = (EntitySoldierPuppet) p;
            SlotPayload payload = slotPayloads.get(i);
            BlockPos spawnPos = new BlockPos(p.posX, p.posY, p.posZ);
            try {
                spawned = SpawnHelper.spawnPayload(world, spawnPos, payload.getId(),
                        battleTargetPlayerUuid, battleSite, this.warLevel, loadoutRole, puppet.getSkinKey());
            } catch (Throwable t) {
                EpochRunnerMod.logger.error("[BattleManagers] Contact release error: {}", t.getMessage());
            }
            p.setDead();
            break; // exactly one
        }
        pruneDeadPuppets();
        if (puppetEntityIds.isEmpty()) setDead();
        return spawned;
    }

    private void spawnPuppets() {
        despawnAllPuppets();

        int carrierId = this.getEntityId();
        java.util.Random rng = new java.util.Random(carrierId * 31L + warLevel);

        for (int i = 0; i < slotOffsets.size() && i < slotPayloads.size(); i++) {
            Vec3d off = slotOffsets.get(i);

            EntitySoldierPuppet puppet = new EntitySoldierPuppet(world);
            puppet.bindToCarrier(carrierId, i, off);

            // Apply skin from AW2 skin pool
            try {
                SkinPoolManager.applySkinForRivalLevel(puppet, warLevel, rng);
            } catch (Throwable t) {
                puppet.setTexturePathNoExt("");
            }

            // Armour: the puppets are most of the visible formation and had NONE -- give them the kit too.
            try { SoldierLoadout.applyKskArmor(puppet); } catch (Throwable ignored) {}

            Vec3d rotated = rotateOffset(off);
            puppet.setPosition(this.posX + rotated.x, this.posY, this.posZ + rotated.z);

            world.spawnEntity(puppet);
            puppetEntityIds.add(puppet.getEntityId());
        }

        this.initialPuppetCount = puppetEntityIds.size();
    }

    private void pruneDeadPuppets() {
        if (puppetEntityIds.isEmpty()) return;

        for (int i = puppetEntityIds.size() - 1; i >= 0; i--) {
            int id = puppetEntityIds.get(i);
            Entity e = world.getEntityByID(id);
            if (!(e instanceof EntitySoldierPuppet) || e.isDead) {
                puppetEntityIds.remove(i);
            }
        }
    }

    private void despawnAllPuppets() {
        for (int id : new ArrayList<>(puppetEntityIds)) {
            Entity e = world.getEntityByID(id);
            if (e != null && !e.isDead) e.setDead();
        }
        puppetEntityIds.clear();
    }

    /**
     * LOD ranged phase: keep the number of visible puppets proportional to the carrier's
     * remaining health, so the squad visibly thins as it is damaged instead of members
     * dying at random. Only culls puppets; never spawns.
     */
    private void syncPuppetCountToHealth() {
        pruneDeadPuppets();
        if (initialPuppetCount <= 0 || puppetEntityIds.isEmpty()) return;

        float ratio = this.getHealth() / this.getMaxHealth();
        if (ratio < 0F) ratio = 0F;
        if (ratio > 1F) ratio = 1F;

        int desired = (int) Math.ceil(ratio * initialPuppetCount);

        while (puppetEntityIds.size() > desired) {
            int last = puppetEntityIds.size() - 1;
            Entity e = world.getEntityByID(puppetEntityIds.get(last));
            if (e != null && !e.isDead) e.setDead();
            puppetEntityIds.remove(last);
        }
    }

    /**
     * Convert any surviving puppets into real EntitySoldiers (last stand) so a broken or
     * destroyed formation never simply blinks out of existence.
     */
    private void releaseSurvivors() {
        String loadoutRole = SoldierLoadout.roleFromCardName(this.cardName);

        int count = Math.min(puppetEntityIds.size(), slotPayloads.size());
        for (int i = 0; i < count; i++) {
            Entity p = world.getEntityByID(puppetEntityIds.get(i));
            if (!(p instanceof EntitySoldierPuppet)) continue;

            EntitySoldierPuppet puppet = (EntitySoldierPuppet) p;
            SlotPayload payload = slotPayloads.get(i);
            BlockPos spawnPos = new BlockPos(p.posX, p.posY, p.posZ);

            try {
                SpawnHelper.spawnPayload(
                    world, spawnPos,
                    payload.getId(),
                    battleTargetPlayerUuid, battleSite,
                    this.warLevel, loadoutRole, puppet.getSkinKey()
                );
            } catch (Throwable t) {
                EpochRunnerMod.logger.error("[BattleManagers] Rout release error for payload {}: {}", payload, t.getMessage());
            }
        }

        despawnAllPuppets();
    }

    @Override
    public void onDeath(DamageSource cause) {
        // If the carrier is killed outright (rather than routing), still convert any
        // survivors to real soldiers so the squad doesn't simply disappear.
        if (!world.isRemote && !puppetEntityIds.isEmpty()) {
            releaseSurvivors();
        }
        super.onDeath(cause);
    }

    @Override
    public void readEntityFromNBT(NBTTagCompound compound) {
        activationRange = compound.getInteger("bm_activationRange");
        releaseRange = compound.getInteger("bm_releaseRange");
        releasePerSecond = compound.getInteger("bm_releasePerSecond");
        routHealthThreshold = compound.getFloat("bm_routThreshold");

        orbitEnabled = compound.getBoolean("bm_orbitEnabled");
        orbitCenter = new BlockPos(compound.getInteger("bm_orbitX"), compound.getInteger("bm_orbitY"), compound.getInteger("bm_orbitZ"));
        orbitRadius = compound.getDouble("bm_orbitRadius");
        orbitAngularSpeed = compound.getDouble("bm_orbitSpeed");
        orbitAngle = compound.getDouble("bm_orbitAngle");

        if (compound.hasKey("bm_supX")) {
            suppressionTarget = new BlockPos(compound.getInteger("bm_supX"), compound.getInteger("bm_supY"), compound.getInteger("bm_supZ"));
        } else {
            suppressionTarget = null;
        }

        volleyCooldownTicks = compound.getInteger("bm_volleyCd");
        volleyBurstCount = compound.getInteger("bm_volleyBurst");
        volleyCooldown = compound.getInteger("bm_volleyCur");

        cardName = compound.getString("bm_cardName");
        warLevel = compound.getInteger("bm_warLevel");
        supportsVolley = compound.getBoolean("bm_supportsVolley");

        slotPayloads.clear();
        slotOffsets.clear();
        puppetEntityIds.clear();
    }

    @Override
    public void writeEntityToNBT(NBTTagCompound compound) {
        compound.setInteger("bm_activationRange", activationRange);
        compound.setInteger("bm_releaseRange", releaseRange);
        compound.setInteger("bm_releasePerSecond", releasePerSecond);
        compound.setFloat("bm_routThreshold", routHealthThreshold);

        compound.setBoolean("bm_orbitEnabled", orbitEnabled);
        compound.setInteger("bm_orbitX", orbitCenter.getX());
        compound.setInteger("bm_orbitY", orbitCenter.getY());
        compound.setInteger("bm_orbitZ", orbitCenter.getZ());
        compound.setDouble("bm_orbitRadius", orbitRadius);
        compound.setDouble("bm_orbitSpeed", orbitAngularSpeed);
        compound.setDouble("bm_orbitAngle", orbitAngle);

        if (suppressionTarget != null) {
            compound.setInteger("bm_supX", suppressionTarget.getX());
            compound.setInteger("bm_supY", suppressionTarget.getY());
            compound.setInteger("bm_supZ", suppressionTarget.getZ());
        }

        compound.setInteger("bm_volleyCd", volleyCooldownTicks);
        compound.setInteger("bm_volleyBurst", volleyBurstCount);
        compound.setInteger("bm_volleyCur", volleyCooldown);

        compound.setString("bm_cardName", cardName == null ? "" : cardName);
        compound.setInteger("bm_warLevel", warLevel);
        compound.setBoolean("bm_supportsVolley", supportsVolley);
    }
}
