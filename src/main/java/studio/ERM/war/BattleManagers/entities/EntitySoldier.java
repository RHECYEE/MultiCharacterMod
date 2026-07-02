package studio.ERM.war.BattleManagers.entities;

import net.minecraft.enchantment.EnchantmentHelper;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityCreature;
import net.minecraft.entity.EntityLivingBase;
import net.minecraft.entity.SharedMonsterAttributes;
import net.minecraft.entity.ai.*;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.entity.projectile.EntityArrow;
import net.minecraft.entity.projectile.EntityTippedArrow;
import net.minecraft.init.Items;
import net.minecraft.init.SoundEvents;
import net.minecraft.inventory.EntityEquipmentSlot;
import net.minecraft.item.Item;
import net.minecraft.item.ItemBow;
import net.minecraft.item.ItemStack;
import net.minecraft.item.ItemSword;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.network.datasync.DataParameter;
import net.minecraft.network.datasync.DataSerializers;
import net.minecraft.network.datasync.EntityDataManager;
import net.minecraft.util.DamageSource;
import net.minecraft.util.EnumHand;
import net.minecraft.util.ResourceLocation;
import net.minecraft.util.SoundEvent;
import net.minecraft.util.math.MathHelper;
import net.minecraft.world.DifficultyInstance;
import net.minecraft.world.World;

import studio.ERM.war.skins.ISkinnable;
import studio.ERM.war.skins.SkinPoolManager;

/**
 * EntitySoldier — The real combat entity that puppets convert to when a formation dismounts.
 *
 * Features:
 *   - Lightweight pathfinding AI (melee chase + ranged standoff)
 *   - Configurable equipment per level and role via SoldierLoadout
 *   - AW2 skin pack integration (ISkinnable) — skins carry over from puppet
 *   - Team "empire" — won't attack other empire-team entities
 *   - Bow AI with unlimited arrows (hostile mob, no inventory depletion)
 *   - Flans weapon holding support (item in mainhand)
 *   - Health/speed/damage scale with warLevel
 *   - Drops nothing (enemy combatants, not loot piñatas)
 *
 * Registration: EpochRunnerMod registers this as "erm:soldier"
 */
public class EntitySoldier extends EntityCreature implements ISkinnable {

    // DataWatcher key — synced automatically to all clients.
    private static final DataParameter<String> DW_SKIN_KEY =
            EntityDataManager.createKey(EntitySoldier.class, DataSerializers.STRING);

    // ── Team / Faction ──
    private String team = "empire";

    // ── War context ──
    private int warLevel = 1;
    private String unitRole = "MELEE";  // MELEE, RANGED, HEAVY, SPECIAL

    // ── Ranged combat ──
    private int rangedCooldown = 0;
    private static final int RANGED_COOLDOWN_TICKS = 30; // 1.5 sec between shots
    private static final double RANGED_ENGAGE_DIST = 20.0;
    private static final double MELEE_ENGAGE_DIST = 2.5;

    // ── Despawn ──
    private int idleTicks = 0;
    private static final int MAX_IDLE_TICKS = 6000; // 5 minutes with no target → despawn

    // ── Friendly-fire shield ──
    // Set true transiently while an allied blast detonates nearby so friendly tanks/airstrikes
    // don't mow down their own infantry. Player-caused explosives are unaffected.
    private boolean explosionShield = false;

    // ── DIRECTOR CONTROL ──
    // When the SiegeDirector sets a march objective this soldier is a DIRECTED siege unit: it walks to its
    // objective and only engages an enemy that is right on top of it -- it does NOT free-hunt the player
    // across the map like a vanilla mob (the "everyone just chases me like a dumb minecraft mob" complaint).
    // The director refreshes this each tick; clearing it returns the soldier to normal autonomous behaviour.
    private net.minecraft.util.math.BlockPos marchObjective = null;
    private static final double DIRECTOR_ENGAGE_SQ = 36.0; // only fight a player within 6 blocks while marching

    // ── STRATEGIC MANAGEMENT (Phase 2) ──
    // True when this soldier is a live representation of a StrategicObject (a materialized patrol member).
    // The strategic map owns its lifecycle: it must NEVER idle-despawn (the simulator dematerializes it
    // back onto the map instead), and the orphan sweep removes stale chunk-saved copies.
    private boolean strategicManaged = false;

    public EntitySoldier(World worldIn) {
        super(worldIn);
        this.setSize(0.6F, 1.8F);
        this.enablePersistence();
        this.experienceValue = 3;
    }

    @Override
    protected void entityInit() {
        super.entityInit();
        this.dataManager.register(DW_SKIN_KEY, "");
    }

    // ══════════════════════════════════════
    //  AI
    // ══════════════════════════════════════

    @Override
    protected void initEntityAI() {
        // Movement
        this.tasks.addTask(0, new EntityAISwimming(this));
        this.tasks.addTask(2, new EntityAIAttackMelee(this, 1.1D, false));
        this.tasks.addTask(5, new EntityAIMoveTowardsRestriction(this, 1.0D));
        this.tasks.addTask(7, new EntityAIWanderAvoidWater(this, 0.8D));
        this.tasks.addTask(8, new EntityAIWatchClosest(this, EntityPlayer.class, 12.0F));
        this.tasks.addTask(9, new EntityAILookIdle(this));

        // Targeting — attack players and non-empire living entities. callForHelp=false: a stray friendly
        // hit must not rally the whole squad onto an ally (that was the friendly-fire cascade).
        this.targetTasks.addTask(1, new EntityAIHurtByTarget(this, false));
        this.targetTasks.addTask(2, new EntityAINearestAttackableTarget<>(this, EntityPlayer.class, true));
        this.targetTasks.addTask(3, new EntityAINearestAttackableTarget<>(this, EntityLivingBase.class, 10, true, false, this::shouldAttackEntity));
    }

    /**
     * Target filter: attack anything that is NOT on our team.
     * This prevents empire soldiers from attacking empire patrols, garrisons, and other soldiers.
     */
    private boolean shouldAttackEntity(EntityLivingBase target) {
        if (target == null || !target.isEntityAlive()) return false;
        if (target instanceof EntityPlayer) return true; // always attack players

        // Don't attack other EntitySoldiers on same team
        if (target instanceof EntitySoldier) {
            return !((EntitySoldier) target).getTeam_().equals(this.team);
        }

        // Don't attack EntityAIPilot on same team
        if (target instanceof studio.ERM.war.vehicle.EntityAIPilot) {
            try {
                String otherTeam = ((studio.ERM.war.vehicle.EntityAIPilot) target).getMcmTeam();
                return !this.team.equalsIgnoreCase(otherTeam);
            } catch (Throwable ignored) {}
        }

        // Don't attack EntitySoldierPuppet or EntityFormationCarrier (friendly formation units)
        if (target instanceof EntitySoldierPuppet || target instanceof EntityFormationCarrier) return false;

        // Never shoot a ghost aircraft -- including the very transport heli that just fast-roped us in
        // (the "they shot their own LittleBird down" bug). Air support is not a ground-troop target.
        if (target instanceof studio.ERM.war.air.EntityGhostAircraft) return false;

        // Don't attack AW2 empire faction NPCs
        if (isAW2EmpireNPC(target)) return false;

        // Don't attack passive animals (cows, sheep, horses, etc.)
        if (target instanceof net.minecraft.entity.passive.EntityAnimal) return false;

        // Attack everything else: hostile mobs, non-empire NPCs, other entities
        return true;
    }

    /**
     * Check if an entity is an AW2 NPC belonging to empire faction.
     */
    private static boolean isAW2EmpireNPC(Entity entity) {
        if (entity == null) return false;
        String className = entity.getClass().getName();
        if (!className.contains("shadowmage") && !className.contains("ancientwarfare")) return false;

        try {
            // Try NpcFaction.getFaction()
            java.lang.reflect.Method getFaction = entity.getClass().getMethod("getFaction");
            Object fac = getFaction.invoke(entity);
            if (fac != null && fac.toString().toLowerCase().contains("empire")) return true;
        } catch (Throwable ignored) {}

        try {
            // Try field access
            java.lang.reflect.Field factionField = entity.getClass().getDeclaredField("factionName");
            factionField.setAccessible(true);
            Object fac = factionField.get(entity);
            if (fac != null && fac.toString().toLowerCase().contains("empire")) return true;
        } catch (Throwable ignored) {}

        return false;
    }

    // ══════════════════════════════════════
    //  ATTRIBUTES (scaled by warLevel)
    // ══════════════════════════════════════

    @Override
    protected void applyEntityAttributes() {
        super.applyEntityAttributes();
        this.getEntityAttribute(SharedMonsterAttributes.MAX_HEALTH).setBaseValue(20.0D);
        this.getEntityAttribute(SharedMonsterAttributes.MOVEMENT_SPEED).setBaseValue(0.28D);
        this.getEntityAttribute(SharedMonsterAttributes.FOLLOW_RANGE).setBaseValue(32.0D);
        this.getAttributeMap().registerAttribute(SharedMonsterAttributes.ATTACK_DAMAGE).setBaseValue(3.0D);
    }

    /**
     * Scale stats based on warLevel. Called after spawn.
     */
    public void applyLevelScaling() {
        int lv = Math.max(1, Math.min(10, this.warLevel));
        double health = 16.0 + (lv * 4.0);  // 20 at L1, 56 at L10
        double speed  = 0.26 + (lv * 0.005); // slightly faster at high levels
        double damage = 2.0 + (lv * 1.0);    // 3 at L1, 12 at L10
        double follow = 28.0 + (lv * 2.0);

        this.getEntityAttribute(SharedMonsterAttributes.MAX_HEALTH).setBaseValue(health);
        // Current health is set by the caller (full on spawn, preserved on reload).
        this.getEntityAttribute(SharedMonsterAttributes.MOVEMENT_SPEED).setBaseValue(speed);
        this.getEntityAttribute(SharedMonsterAttributes.ATTACK_DAMAGE).setBaseValue(damage);
        this.getEntityAttribute(SharedMonsterAttributes.FOLLOW_RANGE).setBaseValue(follow);
    }

    // ══════════════════════════════════════
    //  UPDATE TICK
    // ══════════════════════════════════════

    @Override
    public void onUpdate() {
        super.onUpdate();
        if (world.isRemote) return;

        // Ranged combat logic
        if (rangedCooldown > 0) rangedCooldown--;
        if (isRangedUnit() && getAttackTarget() != null) {
            EntityLivingBase target = getAttackTarget();
            double dist = getDistance(target);
            if (dist > MELEE_ENGAGE_DIST && dist < RANGED_ENGAGE_DIST && rangedCooldown <= 0 && canEntityBeSeen(target)) {
                fireRangedAttack(target);
                rangedCooldown = RANGED_COOLDOWN_TICKS;
            }
        }

        // Idle despawn — if no attack target and no player nearby for 5 minutes. Strategic-managed
        // soldiers are EXEMPT: the strategic map owns their lifecycle (it dematerializes them back
        // onto the map instead of letting them evaporate mid-route).
        if (!strategicManaged) {
            if (getAttackTarget() == null) {
                Entity nearest = world.getClosestPlayerToEntity(this, 64.0);
                if (nearest == null) {
                    idleTicks++;
                    if (idleTicks > MAX_IDLE_TICKS) {
                        setDead();
                        return;
                    }
                } else {
                    idleTicks = 0;
                }
            } else {
                idleTicks = 0;
            }
        }

        // DIRECTOR MARCH: walk to the assigned objective unless we're in an adjacent fight. This is what
        // makes a released siege soldier SEEK ITS OBJECTIVE (room-to-room) instead of standing around or
        // chasing the player -- the director directs it.
        if (marchObjective != null) {
            EntityLivingBase mtgt = getAttackTarget();
            boolean adjacentFight = mtgt != null && !mtgt.isDead && this.getDistanceSq(mtgt) < 9.0;
            if (!adjacentFight) {
                double dObj = this.getDistanceSq(marchObjective.getX() + 0.5, marchObjective.getY(),
                        marchObjective.getZ() + 0.5);
                if (dObj > 6.25 && (this.getNavigator().noPath() || this.ticksExisted % 15 == 0)) {
                    // March speed scales with the config walk multiplier (WarLevelsConfig siege.walkSpeedMultiplier),
                    // capped so the navigator doesn't overshoot. Default 3x -> ~2.7x march.
                    double marchMult = Math.max(1.0, Math.min(3.2,
                            0.9 * studio.ERM.war.config.WarLevelsConfig.walkSpeedMultiplier()));
                    this.getNavigator().tryMoveToXYZ(marchObjective.getX() + 0.5, marchObjective.getY(),
                            marchObjective.getZ() + 0.5, marchMult);
                }
                // LADDER CLIMB: the 1.12 ground navigator walks INTO a ladder column but never climbs it.
                // While marching to an objective ABOVE us, actively climb any ladder we're standing in --
                // this is what carries assault troops up the engineer-built ladder columns rung by rung
                // instead of bumping forever at the base.
                if (marchObjective.getY() > this.posY + 1.5 && this.isOnLadder()) {
                    this.motionY = Math.max(this.motionY, 0.22);
                }
            }
        }
    }

    public void setExplosionShield(boolean shielded) {
        this.explosionShield = shielded;
    }

    /** Director order: march to this objective and stop free-hunting the player (see {@link #marchObjective}). */
    public void setMarchObjective(net.minecraft.util.math.BlockPos pos) {
        this.marchObjective = pos;
        // CLEAR an existing distant-player target. setAttackTarget's guard only blocks ACQUIRING a new
        // distant player; a soldier that locked onto the player BEFORE being ordered would otherwise keep
        // chasing forever (the "everyone just chases me like a dumb mob" bug). Break it off so it marches.
        if (pos != null) {
            EntityLivingBase cur = getAttackTarget();
            if (cur instanceof EntityPlayer && this.getDistanceSq(cur) > DIRECTOR_ENGAGE_SQ) {
                super.setAttackTarget(null);
                this.getNavigator().clearPath();
            }
        }
    }

    /** Mark this soldier as a live representation of a StrategicObject (see {@link #strategicManaged}). */
    public void setStrategicManaged(boolean managed) {
        this.strategicManaged = managed;
    }

    @Override
    public void setAttackTarget(EntityLivingBase target) {
        // DIRECTOR CONTROL: a marching siege soldier does not break off to chase a distant player -- the
        // director sends it to an objective; it only engages a player that is right on top of it.
        if (marchObjective != null && target instanceof EntityPlayer
                && this.getDistanceSq(target) > DIRECTOR_ENGAGE_SQ) {
            return;
        }
        super.setAttackTarget(target);
    }

    @Override
    public boolean attackEntityFrom(DamageSource source, float amount) {
        // Ignore friendly blast splash while shielded (allied tank/airstrike detonations).
        if (explosionShield && source.isExplosion()) return false;
        return super.attackEntityFrom(source, amount);
    }

    // ══════════════════════════════════════
    //  RANGED COMBAT
    // ══════════════════════════════════════

    private boolean isRangedUnit() {
        ItemStack main = getHeldItemMainhand();
        if (main.isEmpty()) return false;
        Item item = main.getItem();
        // Bow
        if (item instanceof ItemBow) return true;
        // Flans weapon — check class name
        String cls = item.getClass().getName().toLowerCase();
        return cls.contains("itemgun") || cls.contains("flansmod");
    }

    private void fireRangedAttack(EntityLivingBase target) {
        ItemStack main = getHeldItemMainhand();
        if (main.isEmpty()) return;

        Item item = main.getItem();

        if (item instanceof ItemBow) {
            // Spawn arrow
            EntityTippedArrow arrow = new EntityTippedArrow(world, this);
            double dx = target.posX - this.posX;
            double dy = target.getEntityBoundingBox().minY + (target.height / 3.0) - arrow.posY;
            double dz = target.posZ - this.posZ;
            double dist = MathHelper.sqrt(dx * dx + dz * dz);
            arrow.shoot(dx, dy + dist * 0.2, dz, 1.6F, (float)(14 - world.getDifficulty().getId() * 4));

            // Level-based damage bonus
            int dmgBonus = Math.max(0, warLevel / 3);
            arrow.setDamage(arrow.getDamage() + dmgBonus + 0.5);
            arrow.pickupStatus = EntityArrow.PickupStatus.DISALLOWED;

            this.playSound(SoundEvents.ENTITY_SKELETON_SHOOT, 1.0F, 1.0F / (rand.nextFloat() * 0.4F + 0.8F));
            world.spawnEntity(arrow);
        } else {
            // Flan GUN: hitscan + a bright TRACER streak. Soldiers were literally firing ARROWS out of their
            // uzis (an EntityTippedArrow proxy). Now the round is a hitscan with a yellow tracer line, so it
            // reads as a bullet, not an arrow.
            fireGunTracer(target);
        }

        // Face target
        this.faceEntity(target, 30.0F, 30.0F);
    }

    /** Gun fire: apply hitscan damage and draw a yellow TRACER line muzzle->target (no arrow entity). */
    private void fireGunTracer(EntityLivingBase target) {
        double fx = posX, fy = posY + getEyeHeight(), fz = posZ;
        double spread = 0.7;
        double tx = target.posX + (rand.nextDouble() - 0.5) * spread;
        double ty = target.posY + target.height * 0.5 + (rand.nextDouble() - 0.5) * spread * 0.5;
        double tz = target.posZ + (rand.nextDouble() - 0.5) * spread;

        target.attackEntityFrom(DamageSource.causeMobDamage(this), (float) (2.0 + warLevel));
        this.playSound(SoundEvents.ENTITY_BLAZE_HURT, 0.7F, 1.7F); // sharp report

        if (world instanceof net.minecraft.world.WorldServer) {
            net.minecraft.world.WorldServer ws = (net.minecraft.world.WorldServer) world;
            double dx = tx - fx, dy = ty - fy, dz = tz - fz;
            double dist = Math.sqrt(dx * dx + dy * dy + dz * dz);
            int n = (int) Math.min(24, Math.max(4, dist));
            for (int i = 1; i <= n; i++) {
                double f = (double) i / n;
                // REDSTONE colour trick (count=0, offsets = R,G,B): a yellow dust line = a tracer.
                ws.spawnParticle(net.minecraft.util.EnumParticleTypes.REDSTONE,
                        fx + dx * f, fy + dy * f, fz + dz * f, 0, 1.0D, 0.85D, 0.2D, 1.0D);
            }
        }
    }

    // ══════════════════════════════════════
    //  MELEE
    // ══════════════════════════════════════

    @Override
    public boolean attackEntityAsMob(Entity target) {
        float damage = (float) this.getEntityAttribute(SharedMonsterAttributes.ATTACK_DAMAGE).getAttributeValue();

        // Weapon bonus
        ItemStack main = getHeldItemMainhand();
        if (!main.isEmpty()) {
            // ItemSword already adds damage via attribute modifiers, but let's ensure minimum
            damage = Math.max(damage, 2.0F + warLevel);
        }

        boolean hit = target.attackEntityFrom(DamageSource.causeMobDamage(this), damage);

        if (hit) {
            // Knockback
            int kb = EnchantmentHelper.getKnockbackModifier(this);
            if (kb > 0 && target instanceof EntityLivingBase) {
                ((EntityLivingBase) target).knockBack(this, kb * 0.5F,
                    MathHelper.sin(this.rotationYaw * 0.017453292F),
                    -MathHelper.cos(this.rotationYaw * 0.017453292F));
            }
        }
        return hit;
    }

    // ══════════════════════════════════════
    //  CONFIGURATION
    // ══════════════════════════════════════

    public void configure(int warLevel, String unitRole, String skinKey) {
        this.warLevel = Math.max(1, Math.min(10, warLevel));
        this.unitRole = unitRole != null ? unitRole : "MELEE";
        this.dataManager.set(DW_SKIN_KEY, skinKey != null ? skinKey : "");

        applyLevelScaling();
        this.setHealth(this.getMaxHealth());
        SoldierLoadout.equip(this, this.warLevel, this.unitRole);
    }

    public void setTeam_(String team) { this.team = team != null ? team : "empire"; }
    public String getTeam_() { return team; }

    public int getWarLevel() { return warLevel; }
    public String getUnitRole() { return unitRole; }

    // ══════════════════════════════════════
    //  SOUNDS
    // ══════════════════════════════════════

    @Override
    protected SoundEvent getHurtSound(DamageSource source) { return SoundEvents.ENTITY_PLAYER_HURT; }

    @Override
    protected SoundEvent getDeathSound() { return SoundEvents.ENTITY_PLAYER_DEATH; }

    // ══════════════════════════════════════
    //  DROPS
    // ══════════════════════════════════════

    @Override
    protected void dropLoot(boolean wasRecentlyHit, int lootingModifier, DamageSource source) {
        // No drops — enemy combatants don't drop equipment
    }

    @Override
    protected boolean canDropLoot() { return false; }

    // ══════════════════════════════════════
    //  NBT
    // ══════════════════════════════════════

    @Override
    public void readEntityFromNBT(NBTTagCompound tag) {
        super.readEntityFromNBT(tag);
        this.team = tag.getString("erm_team");
        if (this.team.isEmpty()) this.team = "empire";
        this.warLevel = tag.getInteger("erm_warLevel");
        if (this.warLevel < 1) this.warLevel = 1;
        this.unitRole = tag.getString("erm_unitRole");
        if (this.unitRole.isEmpty()) this.unitRole = "MELEE";
        this.dataManager.set(DW_SKIN_KEY, tag.getString("ermSkinKey"));
        this.idleTicks = tag.getInteger("erm_idle");
        this.strategicManaged = tag.getBoolean("erm_strategic_managed");

        applyLevelScaling();

        // Restore the saved current health (clamped to the level-scaled max) so reloading
        // a chunk doesn't silently heal damaged soldiers back to full.
        float savedHealth = tag.getFloat("Health");
        if (savedHealth > 0.0F) {
            this.setHealth(Math.min(savedHealth, this.getMaxHealth()));
        }
    }

    @Override
    public void writeEntityToNBT(NBTTagCompound tag) {
        super.writeEntityToNBT(tag);
        tag.setString("erm_team", team);
        tag.setInteger("erm_warLevel", warLevel);
        tag.setString("erm_unitRole", unitRole);
        tag.setString("ermSkinKey", this.dataManager.get(DW_SKIN_KEY));
        tag.setInteger("erm_idle", idleTicks);
        tag.setBoolean("erm_strategic_managed", strategicManaged);
    }

    // ══════════════════════════════════════
    //  ISkinnable
    // ══════════════════════════════════════

    @Override
    public String getSkinKey() { return this.dataManager.get(DW_SKIN_KEY); }

    @Override
    public void setSkinKey(String key) {
        this.dataManager.set(DW_SKIN_KEY, key != null ? key : "");
    }

    @Override
    public String getDefaultPoolName() { return "soldiers"; }

    @Override
    public ResourceLocation getFallbackTexture() {
        return new ResourceLocation("minecraft", "textures/entity/steve.png");
    }

    // ══════════════════════════════════════
    //  MISC
    // ══════════════════════════════════════

    /** Never retaliate against an ally: a friendly's stray bullet / blast must not make us target them.
     *  This (with team-filtered proactive targeting) is what stops attacking forces friendly-firing. */
    @Override
    public void setRevengeTarget(EntityLivingBase entity) {
        if (entity != null && isOnSameTeam(entity)) return;
        super.setRevengeTarget(entity);
    }

    /** Report the kill so director-controlled soldiers register like vehicles do ("You killed an enemy
     *  soldier"). Also the capture hook for the future Tree of Remembrance enemies branch. */
    @Override
    public void onDeath(net.minecraft.util.DamageSource cause) {
        if (!this.world.isRemote && cause.getTrueSource() instanceof EntityPlayer) {
            ((EntityPlayer) cause.getTrueSource()).sendMessage(new net.minecraft.util.text.TextComponentString(
                    net.minecraft.util.text.TextFormatting.RED + "You killed an enemy soldier"));
        }
        super.onDeath(cause);
    }

    @Override
    public boolean isOnSameTeam(Entity other) {
        if (other instanceof EntitySoldier) return ((EntitySoldier) other).team.equals(this.team);
        if (other instanceof studio.ERM.war.vehicle.EntityAIPilot) {
            try {
                return ((studio.ERM.war.vehicle.EntityAIPilot) other).getMcmTeam().equalsIgnoreCase(this.team);
            } catch (Throwable ignored) {}
        }
        return super.isOnSameTeam(other);
    }

    @Override
    protected boolean canDespawn() {
        return false; // Managed by idle timer instead
    }

    @Override
    public boolean getCanSpawnHere() {
        return true; // We control spawning programmatically
    }
}
