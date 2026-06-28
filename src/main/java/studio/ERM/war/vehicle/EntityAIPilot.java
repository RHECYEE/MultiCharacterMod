package studio.ERM.war.vehicle;

import co.runed.multicharacter.compat.EntityAIFlansGunAttack;
import co.runed.multicharacter.ModConfig;
import co.runed.multicharacter.combat.CombatFeatures;
import com.flansmod.common.FlansMod;
import com.flansmod.common.RotatedAxes;
import com.flansmod.common.driveables.*;
import com.flansmod.common.guns.*;
import com.flansmod.common.network.PacketSeatUpdates;
import com.flansmod.common.network.PacketVehicleControl;
import com.flansmod.common.network.PacketPlaySound;
import com.flansmod.common.parts.EnumPartCategory;
import com.flansmod.common.parts.PartType;
import com.flansmod.common.types.InfoType;
import com.flansmod.common.vector.Vector3f;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityCreature;
import net.minecraft.entity.EntityLiving;
import net.minecraft.entity.EntityLivingBase;
import net.minecraft.entity.SharedMonsterAttributes;
import net.minecraft.entity.ai.*;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.inventory.EntityEquipmentSlot;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.network.datasync.DataParameter;
import net.minecraft.network.datasync.DataSerializers;
import net.minecraft.network.datasync.EntityDataManager;
import net.minecraft.util.DamageSource;
import net.minecraft.util.ResourceLocation;
import net.minecraft.util.SoundCategory;
import net.minecraft.util.SoundEvent;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;
import net.minecraft.util.text.TextComponentString;
import net.minecraft.util.text.TextFormatting;
import net.minecraft.world.World;
import net.minecraftforge.fml.common.Loader;

import studio.ERM.war.skins.ISkinnable;
import studio.ERM.war.skins.SkinPoolManager;


import java.lang.reflect.Field;
import java.util.*;

public class EntityAIPilot extends EntityCreature implements ISkinnable {

    /**
     * DataWatcher-backed skin key — auto-synced to every tracking client so the renderer's
     * SkinTextureCache can resolve a real AW2 skin-pack texture instead of falling back to a
     * default/missing-texture biped. Same proven pattern as EntitySoldier/EntityModernCitizen.
     */
    private static final DataParameter<String> DW_SKIN_KEY =
            EntityDataManager.createKey(EntityAIPilot.class, DataSerializers.STRING);

    private String vehicleToSummon = "";
    private boolean hasSpawnedVehicle = false;
    private boolean crewSpawned = false;
    public boolean isPassenger = false;

    // Director-set staging point: a rallied vehicle drives HERE and holds, instead of charging the player.
    // Only scouts/transports/boats the SiegeDirector rallies get one; tanks leave it null (unchanged).
    private net.minecraft.util.math.BlockPos rallyPoint = null;
    public void setRallyPoint(net.minecraft.util.math.BlockPos p) { this.rallyPoint = p; }
    private static Field seatsField;
    private int initialCrewSize = 0;

    private final Map<Integer, Integer> seatFireCooldown = new HashMap<>();
    private boolean isDying = false;
    private int mountDelay = 0;

    public EntityVehicle parentVehicle;
    private Entity lockedTarget;
    private int lockedTargetTicks = 0;
    // Teaming / targeting doctrine
    private static final String NBT_TEAM = "mcmTeam";
    private static final String NBT_ALLOW_VEHICLE_TARGETS = "mcmAllowVehicleTargets";

    private String mcmTeam = "ENEMY";
    private boolean allowVehicleTargets = false;


    private boolean hasOrderedDismount = false;
    private int dismountWaitTime = 0;

    private List<AuxiliaryGunBurst> auxiliaryGunBursts = new ArrayList<>();
    private List<SecondaryMGBurst> secondaryMGBursts = new ArrayList<>();

    // === EMPIRE VEHICLE AMMO (UNLIMITED) ===
    // Flan vehicles require valid ammo stacks in DriveableData inventories for EntityDriveable#shoot() to fire.
    // Empire/Bot vehicles should always have ammo without requiring any player logistics.
    private static final int EMPIRE_AMMO_REFILL_INTERVAL_TICKS = 20;
    private int empireAmmoRefillTicker = 0;


    /**
     * Empire (enemy) vehicles get unlimited ammo.
     *
     * Implementation strategy (fork-safe):
     * - Every EMPIRE_AMMO_REFILL_INTERVAL_TICKS, refill DriveableData.ammo[] stacks to max.
     * - If an ammo slot is empty, attempt to infer a valid ShootableType from the vehicle's seat gun(s)
     *   and seed the slot with a full stack of that item.
     *
     * This intentionally does NOT interact with DriveableData "inventory" slots, because different Flan forks
     * expose different inventory APIs. Weapon firing in Flan's uses DriveableData.ammo[] for the gun ammo slots.
     */
    private void ensureEmpireVehicleAmmo(EntityDriveable vehicle) {
        if (vehicle == null || vehicle.isDead) return;

        // Throttle work to reduce per-tick overhead.
        empireAmmoRefillTicker++;
        if (empireAmmoRefillTicker < EMPIRE_AMMO_REFILL_INTERVAL_TICKS) return;
        empireAmmoRefillTicker = 0;

        DriveableData data = vehicle.getDriveableData();
        if (data == null || data.ammo == null || data.ammo.length == 0) return;

        // Build candidate ammo stacks from mounted guns.
        List<ItemStack> candidates = new ArrayList<>();

        List<EntitySeat> seats = getSeatsFromVehicle(vehicle);
        if (seats != null) {
            for (EntitySeat seat : seats) {
                if (seat == null || seat.seatInfo == null) continue;

                GunType gunType = null;
                if (seat.seatInfo.gunType != null) {
                    gunType = seat.seatInfo.gunType;
                } else if (seat.seatInfo.gunName != null && !seat.seatInfo.gunName.isEmpty()) {
                    InfoType resolved = InfoType.getType(seat.seatInfo.gunName);
                    if (resolved instanceof GunType) {
                        gunType = (GunType) resolved;
                    }
                }

                if (gunType == null || gunType.ammo == null || gunType.ammo.isEmpty()) continue;

                ShootableType st = gunType.ammo.get(0);
                ItemStack ammoStack = createFullAmmoStack(st);
                if (ammoStack != null && !ammoStack.isEmpty()) {
                    candidates.add(ammoStack);
                }
            }
        }

        // If we couldn't infer any candidates, fall back to whatever is already loaded.
        if (candidates.isEmpty()) {
            for (ItemStack s : data.ammo) {
                if (s != null && !s.isEmpty()) {
                    ItemStack full = s.copy();
                    full.setCount(full.getMaxStackSize());
                    candidates.add(full);
                }
            }
        }

        // Refill / seed ammo slots.
        for (int i = 0; i < data.ammo.length; i++) {
            ItemStack slot = data.ammo[i];

            if (slot != null && !slot.isEmpty()) {
                int max = slot.getMaxStackSize();
                if (slot.getCount() < max) {
                    slot.setCount(max);
                    data.ammo[i] = slot;
                }
                continue;
            }

            if (!candidates.isEmpty()) {
                ItemStack seed = candidates.get(i % candidates.size()).copy();
                if (seed.getCount() <= 0) seed.setCount(seed.getMaxStackSize());
                data.ammo[i] = seed;
            }
        }
    }

    /**
     * Create a full ammo ItemStack for a ShootableType.
     *
     * Flan's types typically inherit from InfoType, which provides an 'item' field.
     * Some forks differ; we defensively check the field presence and type.
     */
    private ItemStack createFullAmmoStack(ShootableType st) {
        if (st == null) return ItemStack.EMPTY;

        try {
            // Most Flan's forks: InfoType#item is the Item instance for this type.
            Item item = st.item;
            if (item == null) return ItemStack.EMPTY;

            ItemStack stack = new ItemStack(item, 1, 0);
            int max = stack.getMaxStackSize();
            if (max <= 0) max = 64;
            stack.setCount(max);
            return stack;
        } catch (Throwable t) {
            // If the fork doesn't expose st.item, we can't safely construct ammo here.
            return ItemStack.EMPTY;
        }
    }


    private enum VehicleCategory { TANK, BOAT, TRANSPORT, STATIC }

    // === PHASE 2: per-vehicle MOVEMENT STYLE (ShortName-derived) =========================
    // Finer than VehicleCategory: distinguishes scouts / light / mid / heavy so each class
    // moves to its own doctrine. DEFAULT falls back to the legacy controlGround behaviour so
    // any unmatched vehicle keeps working exactly as before.
    private enum MovementStyle { SCOUT, LIGHT_TANK, MID_TANK, HEAVY_TANK, DEFAULT }

    // Light scout / utility: fast loose wide-radius scouting, circle the perimeter, drive-by + relocate.
    private static final List<String> STYLE_SCOUT = Arrays.asList(
            "bmwr75", "bmw", "jeep", "kubel", "sasjeep", "sdkfz2", "humvee", "motorcycle", "bike");
    // Early / light tanks + armoured cars: probe, support infantry, flank, fire at medium range.
    private static final List<String> STYLE_LIGHT = Arrays.asList(
            "b1", "chiha", "chi-ha", "crusader", "greyhound", "panzeriil", "uc2pdr", "2pdr",
            "chaffee", "puma", "ba-64", "sdkfz222");
    // Mid tanks / carriers: main assault support, advance-by-bounds, stop at assault range.
    private static final List<String> STYLE_MID = Arrays.asList(
            "churchill", "cromwell", "m3halftrack", "panzer", "sdkfz251", "sherman");
    // Late heavy / TD: slow deliberate, hold standoff, never chase into interiors.
    private static final List<String> STYLE_HEAVY = Arrays.asList(
            "tiger", "tigerii", "tiger131", "is2", "kv1", "kv2", "stug", "m10", "hellcat",
            "fury", "t34", "pershing", "jumbo");

    // Standoff / engagement radii in BLOCKS, squared inline where compared to getDistanceSq().
    private static final double SCOUT_ORBIT_RADIUS = 45.0;  // circle the base this far out
    private static final double LIGHT_STANDOFF     = 35.0;  // medium range fire
    private static final double MID_ASSAULT_RANGE  = 22.0;  // close support stop line
    private static final double HEAVY_STANDOFF     = 50.0;  // long deliberate standoff
    private static final float  SCOUT_RETREAT_HP   = 0.4F;  // retreat below 40% hull HP

    // Advance-by-bounds phase counter for MID_TANK (move ~2s, halt ~1s so the gun fires from a stop).
    private int boundPhaseTicks = 0;

    private static final List<String> SOFT_VEHICLES = Arrays.asList("bike", "motorcycle", "quad", "atv");
    private static final List<String> LIGHT_TRANSPORTS = Arrays.asList("jeep", "kubel", "truck", "halftrack", "m3", "humvee", "transport", "bmw", "sasjeep", "sdkfz");
    private static final List<String> ARMORED_CARS = Arrays.asList("greyhound", "puma", "BA-64", "sdkfz222");
    private static final List<String> MEDIUM_TANKS = Arrays.asList("sherman", "panzer", "t34", "cromwell", "chi-nu", "type97", "stug", "crusader", "m10", "fury");
    private static final List<String> HEAVY_TANKS = Arrays.asList("tiger", "is2", "kv1", "churchill", "pershing", "jumbo", "kv2");
    private static final List<String> MODERN_MBT = Arrays.asList("abrams", "t90", "leo2", "chally", "merkara", "type99", "t72", "t80");
    private static final List<String> AA_GUNS = Arrays.asList("bofors", "flakvierling", "type96", "zsu", "shilka", "tunguska", "m45", "quad");
    private static final List<String> HEAVY_ARTILLERY = Arrays.asList("flak88", "pak40", "155mm", "howitzer", "long tom");
    private static final List<String> STATIC_WEAPONS = Arrays.asList("bofors", "flak88", "flakvierling", "m157mm", "m45quad", "pak40", "uc2pdr", "2pdr", "mim23", "quad");
    private static final List<String> NAVAL_VESSELS = Arrays.asList("s100", "ptboat", "destroyer", "battleship");
    private static final List<String> AUXILIARY_GUN_VEHICLES = Arrays.asList("m45quad", "bofors", "flakvierling", "flak38", "flak42", "flak88", "shilka", "zsu", "jeep", "humvee", "kubel", "m3halftrack", "sdkfz251", "m10", "hellcat", "sherman", "t34", "panzer", "tiger", "abrams", "leo2", "chally", "t90");

    public EntityAIPilot(World worldIn) {
        super(worldIn);
        this.setSize(0.6F, 1.8F);
        this.setInvisible(false);
        this.enablePersistence();

        this.tasks.addTask(1, new EntityAISwimming(this));
        this.tasks.addTask(2, new EntityAIRemountVehicle(this));
        this.tasks.addTask(3, new EntityAIFlansGunAttack(this, 1.0D, 20, 60.0F));
        this.tasks.addTask(4, new EntityAIWanderAvoidWater(this, 1.0D));
        this.tasks.addTask(5, new EntityAIWatchClosest(this, EntityPlayer.class, 8.0F));
        this.tasks.addTask(6, new EntityAILookIdle(this));

        this.targetTasks.addTask(1, new EntityAIHurtByTarget(this, true));

        // Target players - enemy pilots attack all players
        this.targetTasks.addTask(2, new EntityAINearestAttackableTarget<EntityPlayer>(this, EntityPlayer.class, 10, true, false, (player) -> {
            return player != null && player.isEntityAlive() && isHostileToEntity(player);
        }));

        // Target AW2 NPCs - attack player-owned NPCs (non-Empire faction)
        this.targetTasks.addTask(3, new EntityAINearestAttackableTarget<EntityLiving>(this, EntityLiving.class, 10, true, false, (entity) -> {
            if (entity == null || !entity.isEntityAlive()) return false;
            if (entity instanceof EntityAIPilot) return false; // Don't target pilots here

            // Check if this is an AW2 NPC we should attack
            return shouldTargetAW2NPC(entity);
        }));

        // Target enemy pilots (different team)
        this.targetTasks.addTask(4, new EntityAINearestAttackableTarget<EntityAIPilot>(this, EntityAIPilot.class, 10, true, false, (entity) -> {
            if (entity == null || !entity.isEntityAlive() || entity == this) return false;
            if (this.parentVehicle != null && entity.getUniqueID().equals(this.parentVehicle.getUniqueID())) return false;
            // Only target pilots from different teams
            return !entity.getMcmTeam().equals(this.getMcmTeam());
        }));

        // Target player's citizens (EntityModernCitizen)
        this.targetTasks.addTask(5, new EntityAINearestAttackableTarget<EntityLiving>(this, EntityLiving.class, 10, true, false, (entity) -> {
            if (entity == null || !entity.isEntityAlive()) return false;
            // Check if it's a player's modern citizen
            String className = entity.getClass().getSimpleName();
            return className.contains("ModernCitizen") || className.contains("Citizen");
        }));
    }

    @Override
    protected void entityInit() {
        super.entityInit();
        // Register the synced skin key so clients receive it via the entity's DataManager.
        this.dataManager.register(DW_SKIN_KEY, "");
    }

    /**
     * Check if we should target an AW2 NPC based on faction
     * Empire pilots should attack player-owned NPCs (not Empire faction)
     */
    private boolean shouldTargetAW2NPC(Entity entity) {
        if (entity == null) return false;

        String className = entity.getClass().getName();

        // Check if it's an AW2 NPC
        if (!className.contains("shadowmage") && !className.contains("ancientwarfare")) {
            return false;
        }

        // Try to get faction from AW2 NPC
        try {
            // For NpcFaction entities, check the faction name
            if (className.contains("NpcFaction")) {
                // Get faction name via reflection
                java.lang.reflect.Method getFactionName = entity.getClass().getMethod("getFaction");
                Object factionObj = getFactionName.invoke(entity);
                if (factionObj != null) {
                    String factionName = factionObj.toString().toLowerCase();
                    // Don't attack Empire NPCs (our faction)
                    if (factionName.contains("empire")) {
                        return false;
                    }
                    // Attack all other factions (player's NPCs, brigands, etc.)
                    return true;
                }
            }

            // For player-owned NPCs (NpcPlayerOwned), always attack them
            if (className.contains("NpcPlayerOwned") || className.contains("PlayerOwned")) {
                return true;
            }

            // For faction NPCs, check if they're hostile to Empire
            if (className.contains("Npc")) {
                // Try to determine ownership
                try {
                    java.lang.reflect.Method getOwner = entity.getClass().getMethod("getOwner");
                    Object owner = getOwner.invoke(entity);
                    // If it has a player owner, target it
                    if (owner != null) {
                        return true;
                    }
                } catch (Exception ignored) {}

                // Default: attack non-Empire faction NPCs
                return true;
            }
        } catch (Exception e) {
            // Reflection failed, use fallback logic
        }

        return false;
    }

    /**
     * Check if this pilot is hostile to a given entity
     */
    /**
     * Determines if the pilot should attack a given entity, accounting for FTB Alliances.
     */
    private boolean isHostileToEntity(Entity entity) {
        if (entity == null) return false;

        String myTeam = this.getMcmTeam();

        // "ENEMY" team pilots remain hostile to everyone not on their exact team
        if ("ENEMY".equals(myTeam)) {
            String entityTeam = resolveEntityTeam(entity);
            return !myTeam.equals(entityTeam);
        }

        String theirTeam = resolveEntityTeam(entity);

        // 1. Same team members are friendly
        if (myTeam.equals(theirTeam)) return false;

        // 2. Check for FTB Alliance via reflection (Safely handles 1.12.2 API)
        if (isFtbAlly(myTeam, theirTeam)) return false;

        // 3. If not on the same team and not allied, target is hostile
        return true;
    }

    /**
     * Safely queries the FTB Utilities/FTBLib 1.12.2 API to check for an alliance.
     */
    private boolean isFtbAlly(String teamA, String teamB) {
        String idA = teamA.replace("FTB:", "");
        String idB = teamB.replace("FTB:", "");

        try {
            if (!net.minecraftforge.fml.common.Loader.isModLoaded("ftblib") &&
                    !net.minecraftforge.fml.common.Loader.isModLoaded("ftbutilities")) return false;

            Class<?> universeClass = Class.forName("com.feed_the_beast.ftblib.lib.data.Universe");
            java.lang.reflect.Method getUniverse = universeClass.getMethod("get");
            Object universe = getUniverse.invoke(null);

            if (universe == null) return false;

            java.lang.reflect.Method getTeam = universe.getClass().getMethod("getTeam", String.class);
            Object teamObjA = getTeam.invoke(universe, idA);
            Object teamObjB = getTeam.invoke(universe, idB);

            if (teamObjA != null && teamObjB != null) {
                // FTB Teams in 1.12.2 have an isAlly(Team other) method
                java.lang.reflect.Method isAllyMethod = teamObjA.getClass().getMethod("isAlly", teamObjA.getClass());
                return (boolean) isAllyMethod.invoke(teamObjA, teamObjB);
            }
        } catch (Exception ignored) {}
        return false;
    }

    private String resolveEntityTeam(Entity entity) {
        String tag = getTeamTag(entity, this);
        return (tag == null || tag.trim().isEmpty()) ? "UNKNOWN" : tag;
    }

    private static String getTeamTag(Entity e, EntityAIPilot context) {
        if (e == null) return null;

        // Priority 1: Custom NBT tags (for Vehicles and NPCs)
        net.minecraft.nbt.NBTTagCompound nbt = e.getEntityData();
        if (nbt != null && nbt.hasKey("mcmTeam")) {
            String t = nbt.getString("mcmTeam");
            if (t != null && !t.isEmpty()) return t;
        }

        // Priority 2: Player-specific team data (FTB/Scoreboard)
        if (e instanceof net.minecraft.entity.player.EntityPlayer) {
            net.minecraft.entity.player.EntityPlayer p = (net.minecraft.entity.player.EntityPlayer) e;

            // Note: Ensure resolveFtbTeamId remains in your class as it was previously defined
            String ftb = resolveFtbTeamId(p);
            if (ftb != null && !ftb.isEmpty()) return ftb;

            if (p.getTeam() != null) return "SCOREBOARD:" + p.getTeam().getName();
            return "UUID:" + p.getUniqueID().toString();
        }

        // Priority 3: Inherit from active Pilot instance
        if (e instanceof EntityAIPilot) {
            return ((EntityAIPilot) e).getMcmTeam();
        }

        return "UUID:" + e.getUniqueID().toString();
    }

    private static class EntityAIRemountVehicle extends EntityAIBase {
        private final EntityAIPilot pilot;
        private int remountCooldown = 0;

        public EntityAIRemountVehicle(EntityAIPilot pilot) {
            this.pilot = pilot;
            this.setMutexBits(7);
        }

        @Override
        public boolean shouldExecute() {
            if (pilot.parentVehicle == null || pilot.parentVehicle.isDead) return false;
            if (pilot.getRidingEntity() != null) return false;
            if (pilot.mountDelay > 0) return false;
            if (remountCooldown > 0) {
                remountCooldown--;
                return false;
            }

            double dist = pilot.getDistance(pilot.parentVehicle);
            return dist > 3.0D && dist < 50.0D;
        }

        @Override
        public void startExecuting() {
            pilot.getNavigator().tryMoveToEntityLiving(pilot.parentVehicle, 1.2D);
        }

        @Override
        public void updateTask() {
            double dist = pilot.getDistance(pilot.parentVehicle);
            if (dist < 2.0D) {
                EntitySeat[] seats = pilot.getSeatsReflected(pilot.parentVehicle);
                if (seats != null) {
                    for (EntitySeat s : seats) {
                        if (s != null && s.getControllingPassenger() == null) {
                            pilot.startRiding(s, true);
                            pilot.mountDelay = 100;
                            return;
                        }
                    }
                }
                remountCooldown = 100;
            }
        }
    }

    @Override
    protected void applyEntityAttributes() {
        super.applyEntityAttributes();
        this.getEntityAttribute(SharedMonsterAttributes.MAX_HEALTH).setBaseValue(40.0D);
        this.getEntityAttribute(SharedMonsterAttributes.FOLLOW_RANGE).setBaseValue(64.0D);
        this.getEntityAttribute(SharedMonsterAttributes.MOVEMENT_SPEED).setBaseValue(0.3D);
    }

    public void setVehicleType(String shortName) {
        this.vehicleToSummon = shortName;
    }

    private void applyTieredHealth(String name, VehicleCategory cat) {
        float hp = 100.0F;
        name = name.toLowerCase();

        if (cat == VehicleCategory.TRANSPORT) {
            if (matches(name, SOFT_VEHICLES)) hp = 30.0F;
            else if (matches(name, LIGHT_TRANSPORTS)) hp = 60.0F;
            else if (matches(name, ARMORED_CARS)) hp = 180.0F;
            else hp = 50.0F;
            this.setSize(1.5F, 2.0F);
        } else if (cat == VehicleCategory.TANK) {
            if (matches(name, MODERN_MBT)) hp = 800.0F;
            else if (matches(name, HEAVY_TANKS)) hp = 550.0F;
            else hp = 350.0F;
            this.setSize(9.0F, 5.0F); // bigger hitbox so the tank is actually hittable (was 6x4)
        } else if (cat == VehicleCategory.STATIC) {
            if (matches(name, HEAVY_ARTILLERY)) hp = 400.0F;
            else if (matches(name, AA_GUNS)) hp = 300.0F;
            else hp = 200.0F;
            this.setSize(3.0F, 2.0F);
        }

        this.getEntityAttribute(SharedMonsterAttributes.MAX_HEALTH).setBaseValue(hp);
        this.setHealth(hp);
    }

    private boolean matches(String name, List<String> list) {
        for (String s : list) if (name.contains(s)) return true;
        return false;
    }

    private ItemStack getRandomWeaponItem(boolean isPassenger) {
        String configString = isPassenger ? ModConfig.weapons.passengerWeapons : ModConfig.weapons.pilotWeapons;
        if (configString == null || configString.isEmpty()) return ItemStack.EMPTY;

        String[] parts = configString.split(",");
        if (parts.length == 0) return ItemStack.EMPTY;

        String pick = parts[this.rand.nextInt(parts.length)].trim();
        Item item = Item.getByNameOrId(pick);
        return item != null ? new ItemStack(item) : ItemStack.EMPTY;
    }

    // Transient flag: true only while a friendly blast is detonating nearby, so a vehicle's
    // own main gun never damages its crew and friendly tanks don't friendly-fire each other.
    private boolean explosionShield = false;

    public void setExplosionShield(boolean shielded) {
        this.explosionShield = shielded;
    }

    @Override
    public boolean attackEntityFrom(DamageSource source, float amount) {
        if (explosionShield && source.isExplosion()) return false;
        if (this.isDying || this.isEntityInvulnerable(source) || this.isDead || this.getHealth() <= 0) return false;

        Entity attacker = source.getTrueSource();
        boolean hitPassenger = false;

        if (attacker != null && this.isRiding() && this.getRidingEntity() instanceof EntitySeat) {
            EntityDriveable d = ((EntitySeat)this.getRidingEntity()).driveable;
            EntitySeat[] seats = getSeatsReflected(d);
            VehicleCategory cat = getVehicleCategory(d);

            if (seats != null && cat == VehicleCategory.TRANSPORT) {
                for (EntitySeat seat : seats) {
                    if (seat == null || seat.getControllingPassenger() == null) continue;
                    if (seat.getControllingPassenger() == this) continue;

                    if (this.rand.nextBoolean()) {
                        seat.getControllingPassenger().attackEntityFrom(source, amount);
                        hitPassenger = true;
                        break;
                    }
                }
            }
        }

        if (!hitPassenger) {
            float newHealth = this.getHealth() - amount;
            this.setHealth(newHealth);
            this.performHurtAnimation();
            this.playHurtSound(source);
        }

        if (this.getHealth() <= 0.0F) {
            this.isDying = true;

            if (!this.world.isRemote && source.getTrueSource() instanceof EntityPlayer) {
                EntityPlayer player = (EntityPlayer) source.getTrueSource();
                String veh = (this.vehicleToSummon != null) ? this.vehicleToSummon.trim() : "";
                boolean inVehicle = this.isRiding() && this.getRidingEntity() instanceof EntitySeat
                        && ((EntitySeat) this.getRidingEntity()).driveable != null;
                if (inVehicle && !veh.isEmpty()) {
                    player.sendMessage(new TextComponentString(TextFormatting.GOLD + "You destroyed the enemy " + veh));
                } else {
                    player.sendMessage(new TextComponentString(TextFormatting.RED + "You killed an enemy soldier"));
                }
            } else if (!this.world.isRemote
                    && source.getTrueSource() instanceof studio.ERM.war.air.EntityGhostAircraft
                    && "PLAYER".equalsIgnoreCase(((studio.ERM.war.air.EntityGhostAircraft) source.getTrueSource()).getMcmTeam())) {
                // The player's AIRSTRIKE (designator-called friendly aircraft) destroyed this vehicle --
                // give them the kill feedback too, not just direct hits ("the message should still play").
                EntityPlayer p = this.world.getClosestPlayerToEntity(this, 160.0D);
                if (p != null) {
                    String veh = (this.vehicleToSummon != null) ? this.vehicleToSummon.trim() : "";
                    p.sendMessage(new TextComponentString(TextFormatting.GOLD + "💥 Good impact — "
                            + (veh.isEmpty() ? "enemy vehicle" : veh) + " destroyed!"));
                }
            }

            this.onDeath(source);
            if (this.isRiding() && this.getRidingEntity() instanceof EntitySeat) {
                EntityDriveable d = ((EntitySeat)this.getRidingEntity()).driveable;
                if (d != null) {
                    d.setDead();
                    emergencyEject(d);
                }
            }
            this.setDead();
        }

        return true;
    }

    @Override
    public void onLivingUpdate() {
        super.onLivingUpdate();

        // Roll an AW2 skin the first server tick we lack one. Doing it here (rather than only in a
        // spawn-egg hook) means every spawn path — egg, SpawnHelper, RivalCitySpawner, raids, crew —
        // gets a real soldier texture. setSkinKey() is DataParameter-backed, so it syncs to clients.
        if (!this.world.isRemote && this.getSkinKey().isEmpty()) {
            try { SkinPoolManager.applySkinFromPool(this, getDefaultPoolName(), this.world.rand); }
            catch (Throwable ignored) {}
        }

        if (!this.world.isRemote && !this.isPassenger) {
            // NERF "the tank wrecks the player the instant any pixel is visible": acquire targets SLOWER
            // and at shorter range, and periodically DROP the lock so it re-acquires (gives the player a
            // window to break contact / makes it switch targets) instead of being relentlessly glued on.
            if (this.ticksExisted % 140 == 0 && this.rand.nextBoolean()) {
                this.setAttackTarget(null);
                this.lockedTarget = null;
            }
            if (this.ticksExisted % 90 == 0 && this.getAttackTarget() == null) {
                EntityPlayer nearest = this.world.getClosestPlayerToEntity(this, 55.0D);
                if (nearest != null) {
                    this.setAttackTarget(nearest);
                    this.lockedTarget = nearest;
                }
            }
        }

        // Note: getRevengeTarget() is an EntityLivingBase. Flan's driveables are not living entities,
        // so they can't legally be checked/cast here (and Java will error on instanceof).
        // Vehicle-vs-vehicle escalations should be prevented by our explicit target selection filters.

        // Optional vehicle targeting (kept for future player-bots). Disabled by default.
        if (!this.world.isRemote && this.allowVehicleTargets && this.ticksExisted % 20 == 0 && this.lockedTarget == null) {
            EntityDriveable myVehicle = getControllingDriveable();
            List<EntityDriveable> vehicles = this.world.getEntitiesWithinAABB(EntityDriveable.class, this.getEntityBoundingBox().grow(64));
            for (EntityDriveable v : vehicles) {
                if (v == null || v.isDead) continue;
                if (myVehicle != null && v.getUniqueID().equals(myVehicle.getUniqueID())) continue;
                if (isFriendlyVehicle(v)) continue;
                this.lockedTarget = v;
                break;
            }
        }

        if (!this.world.isRemote && this.ticksExisted % 20 == 0) {
            NBTTagCompound nbt = this.getEntityData();
            if (nbt.getInteger("Infantry_CurrentAmmo") <= 0) {
                nbt.setInteger("Infantry_CurrentAmmo", 30);
                if (this.getHeldItemOffhand().isEmpty()) {
                    this.setItemStackToSlot(EntityEquipmentSlot.OFFHAND, new ItemStack(Item.getItemById(262)));
                }
            }
        }

        if (this.world.isRemote) return;

        if (this.isDead || this.getHealth() <= 0) {
            this.setDead();
            return;
        }

        if (this.isPassenger) {
            if (this.parentVehicle == null || this.parentVehicle.isDead) {
                this.isPassenger = false;
                this.setSize(0.6F, 1.8F);
                this.setItemStackToSlot(EntityEquipmentSlot.MAINHAND, getRandomWeaponItem(true));
            }
            return;
        }

        if (this.isRiding() && this.ticksExisted % 5 == 0) {
            EntityDriveable d = ((EntitySeat)this.getRidingEntity()).driveable;
            if (d != null) {
                EntitySeat[] seats = getSeatsReflected(d);
                if (seats != null) {
                    for (EntitySeat s : seats) {
                        if (s.getControllingPassenger() != null) {
                            s.getControllingPassenger().setInvisible(false);
                        }
                    }
                }
            }
        }

        if (this.isRiding() && this.getRidingEntity() instanceof EntitySeat) {
            EntityDriveable d = ((EntitySeat)this.getRidingEntity()).driveable;
            if (d == null || d.isDead || d.driveableData == null) {
                this.attackEntityFrom(DamageSource.OUT_OF_WORLD, 1000F);
                return;
            }
        }

        if (this.ticksExisted < 60) return;
        if (this.isRiding()) hasSpawnedVehicle = true;

        if (!hasSpawnedVehicle && !vehicleToSummon.isEmpty()) {
            spawnAndMountVehicle();
            hasSpawnedVehicle = true;
            mountDelay = 40;
            return;
        }

        if (mountDelay > 0) {
            mountDelay--;
            if (mountDelay == 0) findAndMountVehicle();
            return;
        }

        if (hasSpawnedVehicle && this.getRidingEntity() == null) {
            return;
        }

        if (this.getRidingEntity() instanceof EntitySeat && !crewSpawned) {
            EntityDriveable vehicle = ((EntitySeat)this.getRidingEntity()).driveable;
            if(vehicle != null) {
                spawnEmpireCrew(vehicle);
                crewSpawned = true;
            }
        }

        if (this.getRidingEntity() instanceof EntitySeat) {
            EntitySeat seat = (EntitySeat) this.getRidingEntity();
            if (seat.driveable != null) {
                try {
                    runControlLogic(seat);
                } catch (Exception e) {
                    e.printStackTrace();
                }
            }
        }
    }

    private void runControlLogic(EntitySeat seat) {
        EntityDriveable driving = seat.driveable;
        if (driving == null || driving.driveableData == null) return;

        Entity target = this.getAttackTarget();
        if (target == null) target = this.lockedTarget;
        if (target == null) return;

        if (lockedTarget != target) {
            lockedTarget = target;
            lockedTargetTicks = 0;
        }
        lockedTargetTicks++;

        updateVehicleControl(driving);
        VehicleCategory cat = getVehicleCategory(driving);

        if (rallyPoint != null && driving instanceof EntityVehicle) {
            // RALLIED (director-set staging): drive the hull to the staging point and HOLD there instead of
            // charging the player. Unchanged from phase 1 -- the director owns this path for the units it rallies.
            driveHullToward((EntityVehicle) driving, rallyPoint.getX() + 0.5, rallyPoint.getZ() + 0.5);
        } else if (cat == VehicleCategory.BOAT) {
            controlBoat(driving, target);
        } else if ((cat == VehicleCategory.TANK || cat == VehicleCategory.TRANSPORT)
                && driving instanceof EntityVehicle) {
            // PHASE 2: per-vehicle movement style. DEFAULT falls back to the legacy controlGround so
            // any unmatched vehicle behaves exactly as before (tanks stay functional).
            MovementStyle style = getMovementStyle(driving);
            if (style == MovementStyle.DEFAULT) {
                controlGround(driving, target, cat == VehicleCategory.TRANSPORT);
            } else {
                applyMovementStyle((EntityVehicle) driving, target, style, cat == VehicleCategory.TRANSPORT);
            }
        } else if (cat == VehicleCategory.TRANSPORT) {
            controlGround(driving, target, true);
        } else if (cat == VehicleCategory.TANK || cat == VehicleCategory.STATIC) {
            controlGround(driving, target, false);
        }

        handleAllTurrets(driving, target);
    }

    private void handleAllTurrets(EntityDriveable vehicle, Entity target) {
        // Ensure Empire vehicles always have valid ammo loaded (unlimited ammo).
        ensureEmpireVehicleAmmo(vehicle);

        if (target == null || target.isDead) {
            return;
        }

        List<EntitySeat> seatList = getSeatsFromVehicle(vehicle);
        if (seatList == null || seatList.isEmpty()) return;

        VehicleCategory cat = categorizeVehicle(vehicle);
        double distToTarget = this.getDistanceSq(target);

        updateAuxiliaryGunBursts();
        updateSecondaryMGBursts();

        for (int seatIndex = 0; seatIndex < seatList.size(); seatIndex++) {
            EntitySeat seat = seatList.get(seatIndex);
            if (seat == null || seat.seatInfo == null) continue;

            boolean isDriver = (seatIndex == 0);
            boolean hasGun = (seat.seatInfo.gunType != null);
            boolean isTransportDriver = isDriver && (cat == VehicleCategory.TRANSPORT);

            if (isTransportDriver) continue;
            if (isDriver && !hasGun && !(vehicle instanceof EntityVehicle)) continue;

            boolean isMainGun = isDriver && !hasGun && (cat == VehicleCategory.TANK || cat == VehicleCategory.STATIC);

            if (distToTarget > 8100.0D) continue;
            if (!canSeeTarget(seat, target)) continue;

            boolean isAuxiliaryGunVehicle = shouldHaveAuxiliaryGun(vehicle);
            boolean isPureAuxiliaryVehicle = isAuxiliaryGunVehicle && !isMainGun && (cat == VehicleCategory.TRANSPORT || matches(vehicle.getDriveableType().shortName.toLowerCase(), AA_GUNS));

            if (isPureAuxiliaryVehicle && isDriver) {
                handleAuxiliaryGunFire(vehicle, seat, target);
                continue;
            }

            tryFireSeat(vehicle, seat, target, seatIndex, isMainGun, distToTarget);
        }
    }

    private void tryFireSeat(EntityDriveable vehicle, EntitySeat seat, Entity target,
                             int seatIndex, boolean isMainGun, double distToTarget) {

        int cooldown = seatFireCooldown.getOrDefault(seatIndex, 0);
        if (cooldown > 0) {
            seatFireCooldown.put(seatIndex, cooldown - 1);
            return;
        }

        // AIM/LOCK-ON TIME: hold fire for the first ~1.5s after acquiring a target, so the player gets a
        // beat to react / take cover instead of being hit the instant the turret swings on.
        if (lockedTargetTicks < 30) return;

        double dist = Math.sqrt(distToTarget);
        double targetHeight = target instanceof EntityLivingBase ? ((EntityLivingBase)target).getEyeHeight() * 0.7 : target.height * 0.5;
        double dx = target.posX - seat.posX;
        double dy = (target.posY + targetHeight) - (seat.posY + seat.getEyeHeight());
        double dz = target.posZ - seat.posZ;

        double inaccuracyMultiplier = 1.0;
        if (dist > 30) inaccuracyMultiplier = 3.0;
        else if (dist > 20) inaccuracyMultiplier = 2.0;

        if (vehicle.throttle > 0.1 || vehicle.throttle < -0.1) {
            inaccuracyMultiplier *= 1.5;
        }

        inaccuracyMultiplier *= CombatFeatures.getAimInaccuracyMultiplier(this);

        double yawInaccuracy = (this.rand.nextDouble() - 0.5) * 2.0 * inaccuracyMultiplier;
        double pitchInaccuracy = (this.rand.nextDouble() - 0.5) * 1.5 * inaccuracyMultiplier;

        dx += yawInaccuracy;
        dy += pitchInaccuracy;

        double hdist = Math.sqrt(dx * dx + dz * dz);
        float targetYaw = (float) (Math.atan2(dz, dx) * 180.0 / Math.PI) - 90.0F;
        float targetPitch = (float) -(Math.atan2(dy, hdist) * 180.0 / Math.PI);

        float currentYaw = seat.looking.getYaw();
        float currentPitch = seat.looking.getPitch();

        float deltaYaw = MathHelper.wrapDegrees(targetYaw - currentYaw);
        float deltaPitch = MathHelper.wrapDegrees(targetPitch - currentPitch);

        // Slower turret traverse -> the gun takes longer to line up on a moving player (reaction time).
        float smoothing = 0.05f;
        if (dist < 10) smoothing = 0.08f;
        if (dist < 5) smoothing = 0.11f;

        float newYaw = currentYaw + deltaYaw * smoothing;
        float newPitch = currentPitch + deltaPitch * smoothing;

        seat.looking = new RotatedAxes(newYaw, newPitch, 0);

        float yawTolerance = 15.0f;
        float pitchTolerance = 12.0f;

        if (dist < 10) {
            yawTolerance = 20.0f;
            pitchTolerance = 15.0f;
        }

        if (Math.abs(deltaYaw) <= yawTolerance && Math.abs(deltaPitch) <= pitchTolerance) {
            // Flan's EntityDriveable.shoot() can NPE for an AI-crewed vehicle (it reaches for a player
            // driver's ammo inventory that AI seats don't have). Fire each barrel in its OWN guard so a
            // failing secondary never blocks the main gun, and DON'T spam the log with a stack trace
            // every tick -- the tank just skips the shot it couldn't take.
            VehicleCategory vcat = categorizeVehicle(vehicle);
            try { vehicle.shoot(false); } catch (Throwable ignored) {}
            if (vcat == VehicleCategory.TANK || vcat == VehicleCategory.STATIC) {
                try { vehicle.shoot(true); } catch (Throwable ignored) {}
            }

            if (isMainGun) {
                spawnCustomExplosion(vehicle, target, seat, dist);

                if (!this.world.isRemote) {
                    // Flan's vehicle firing path (EntityDriveable#shoot) already broadcasts the correct Flan
                    // weapon sound via PacketPlaySound / Flan networking. Playing a vanilla explosion sound here
                    // causes doubled / incorrect audio.
                    spawnMuzzleFlash(seat);

                    if (shouldHaveSecondaryMG(vehicle)) {
                        scheduleSecondaryMGBurst(vehicle, target, seat);
                    }
                }
            }

            int fireRate = getFireRate(vehicle, seat);
            seatFireCooldown.put(seatIndex, fireRate);
        }
    }

    private static class SecondaryMGBurst {
        EntityDriveable vehicle;
        Entity target;
        EntitySeat seat;
        int ticksRemaining;
        int shotsLeft;

        SecondaryMGBurst(EntityDriveable v, Entity t, EntitySeat s) {
            this.vehicle = v;
            this.target = t;
            this.seat = s;
            this.ticksRemaining = 5 + new Random().nextInt(10);
            this.shotsLeft = 8 + new Random().nextInt(12);
        }
    }

    private boolean shouldHaveSecondaryMG(EntityDriveable vehicle) {
        if (vehicle == null || vehicle.getDriveableType() == null) return false;
        String name = vehicle.getDriveableType().shortName.toLowerCase();
        return matches(name, MEDIUM_TANKS) || matches(name, HEAVY_TANKS) || matches(name, MODERN_MBT);
    }

    private void scheduleSecondaryMGBurst(EntityDriveable vehicle, Entity target, EntitySeat seat) {
        secondaryMGBursts.add(new SecondaryMGBurst(vehicle, target, seat));
    }

    private void updateSecondaryMGBursts() {
        Iterator<SecondaryMGBurst> iterator = secondaryMGBursts.iterator();
        while (iterator.hasNext()) {
            SecondaryMGBurst burst = iterator.next();

            if (burst.target == null || burst.target.isDead) {
                iterator.remove();
                continue;
            }

            burst.ticksRemaining--;

            if (burst.ticksRemaining <= 0 && burst.shotsLeft > 0) {
                fireSecondaryMGShot(burst.seat, burst.target);
                burst.shotsLeft--;
                burst.ticksRemaining = 2 + this.rand.nextInt(3);

                if (burst.shotsLeft <= 0) {
                    iterator.remove();
                }
            }
        }
    }

    private void fireSecondaryMGShot(EntitySeat seat, Entity target) {
        if (this.world.isRemote) return;

        double targetHeight = target instanceof EntityLivingBase ? ((EntityLivingBase)target).height * 0.6 : target.height * 0.5;
        double dx = target.posX - seat.posX + (this.rand.nextDouble() - 0.5) * 6.0;
        double dy = (target.posY + targetHeight) - (seat.posY + 1.5) + (this.rand.nextDouble() - 0.5) * 4.0;
        double dz = target.posZ - seat.posZ + (this.rand.nextDouble() - 0.5) * 6.0;

        Vec3d origin = new Vec3d(seat.posX, seat.posY + 1.5, seat.posZ);
        Vec3d direction = new Vec3d(dx, dy, dz).normalize();

        GunType gunType = getGenericMGGunType();
        if (gunType == null) return;

        FireableGun fireable = new FireableGun(gunType, 2.0f, 3.0f, 8.0f, null);
        BulletType bulletInfo = getGenericBulletType();

        if (bulletInfo != null) {
            FiredShot firedShot = new FiredShot(fireable, bulletInfo, this);
            EntityBullet bullet = new EntityBullet(this.world, firedShot, origin, direction);

            if (seat.driveable != null) {
                NBTTagCompound bulletNBT = bullet.getEntityData();
                bulletNBT.setString("ParentVehicleUUID", seat.driveable.getUniqueID().toString());
            }

            float bulletSpeed = 3.0f;
            bullet.motionX = direction.x * bulletSpeed;
            bullet.motionY = direction.y * bulletSpeed;
            bullet.motionZ = direction.z * bulletSpeed;
            this.world.spawnEntity(bullet);

            // Play the Flan gun's configured shoot sound so AI "fake fire" matches content-pack audio.
            if (gunType.shootSound != null && !gunType.shootSound.isEmpty()) {
                try {
                    PacketPlaySound.sendSoundPacket(
                            seat.posX, seat.posY, seat.posZ,
                            FlansMod.soundRange,
                            seat.world.provider.getDimension(),
                            gunType.shootSound,
                            gunType.distortSound,
                            false
                    );
                } catch (Throwable ignored) {
                    // Sound is non-critical; ignore failures for mod compatibility.
                }
            }

        }
    }

    private static class AuxiliaryGunBurst {
        EntityDriveable vehicle;
        EntitySeat seat;
        Entity target;
        int cooldownTicks;
        boolean burstActive;
        int burstTicksRemaining;
        int shotsThisTick;

        AuxiliaryGunBurst(EntityDriveable v, EntitySeat s, Entity t) {
            this.vehicle = v;
            this.seat = s;
            this.target = t;
            this.cooldownTicks = 40;
            this.burstActive = false;
            this.burstTicksRemaining = 0;
            this.shotsThisTick = 3;
        }
    }

    private boolean shouldHaveAuxiliaryGun(EntityDriveable vehicle) {
        if (vehicle == null || vehicle.getDriveableType() == null) return false;
        String name = vehicle.getDriveableType().shortName.toLowerCase();
        return matches(name, AUXILIARY_GUN_VEHICLES);
    }

    private void handleAuxiliaryGunFire(EntityDriveable vehicle, EntitySeat seat, Entity target) {
        AuxiliaryGunBurst existingBurst = null;
        for (AuxiliaryGunBurst burst : auxiliaryGunBursts) {
            if (burst.seat == seat) {
                existingBurst = burst;
                break;
            }
        }

        if (existingBurst != null && existingBurst.burstActive) {
            return;
        }

        if (existingBurst == null) {
            existingBurst = new AuxiliaryGunBurst(vehicle, seat, target);
            auxiliaryGunBursts.add(existingBurst);
        } else {
            existingBurst.target = target;
            existingBurst.cooldownTicks = 40 + rand.nextInt(20);
            existingBurst.burstActive = false;
        }
    }

    private void updateAuxiliaryGunBursts() {
        Iterator<AuxiliaryGunBurst> iterator = auxiliaryGunBursts.iterator();
        while (iterator.hasNext()) {
            AuxiliaryGunBurst burst = iterator.next();

            if (burst.target == null || burst.target.isDead) {
                iterator.remove();
                continue;
            }

            if (!burst.burstActive) {
                burst.cooldownTicks--;
                if (burst.cooldownTicks <= 0) {
                    burst.burstActive = true;
                    burst.burstTicksRemaining = 20;
                    burst.shotsThisTick = 3 + rand.nextInt(2);
                }
            } else {
                burst.burstTicksRemaining--;

                for (int i = 0; i < burst.shotsThisTick; i++) {
                    fireAuxiliaryBullet(burst.seat, burst.target);
                }

                if (burst.burstTicksRemaining <= 0) {
                    burst.burstActive = false;
                    burst.cooldownTicks = 40 + rand.nextInt(20);
                }
            }
        }
    }

    private void fireAuxiliaryBullet(EntitySeat seat, Entity target) {
        if (this.world.isRemote) return;

        double targetHeight = target instanceof EntityLivingBase ? ((EntityLivingBase)target).height * 0.6 : target.height * 0.5;
        double dx = target.posX - seat.posX + (this.rand.nextDouble() - 0.5) * 6.0;
        double dy = (target.posY + targetHeight) - (seat.posY + 1.5) + (this.rand.nextDouble() - 0.5) * 4.0;
        double dz = target.posZ - seat.posZ + (this.rand.nextDouble() - 0.5) * 6.0;

        Vec3d origin = new Vec3d(seat.posX, seat.posY + 1.5, seat.posZ);
        Vec3d direction = new Vec3d(dx, dy, dz).normalize();

        GunType gunType = getGenericMGGunType();
        if (gunType == null) return;

        FireableGun fireable = new FireableGun(gunType, 3.0f, 3.0f, 8.0f, null);
        BulletType bulletInfo = getGenericBulletType();

        if (bulletInfo != null) {
            FiredShot firedShot = new FiredShot(fireable, bulletInfo, this);
            EntityBullet bullet = new EntityBullet(this.world, firedShot, origin, direction);

            if (seat.driveable != null) {
                NBTTagCompound bulletNBT = bullet.getEntityData();
                bulletNBT.setString("ParentVehicleUUID", seat.driveable.getUniqueID().toString());
            }

            float bulletSpeed = 3.0f;
            bullet.motionX = direction.x * bulletSpeed;
            bullet.motionY = direction.y * bulletSpeed;
            bullet.motionZ = direction.z * bulletSpeed;
            this.world.spawnEntity(bullet);

            // Use the Flan gun's configured shoot sound (like Magistu) so audio matches the bullet type.
            // This also properly replicates to nearby clients via Flan's packet handler.
            if (gunType.shootSound != null && !gunType.shootSound.isEmpty()) {
                try {
                    PacketPlaySound.sendSoundPacket(
                            seat.posX, seat.posY, seat.posZ,
                            FlansMod.soundRange,
                            seat.world.provider.getDimension(),
                            gunType.shootSound,
                            gunType.distortSound,
                            false
                    );
                } catch (Throwable ignored) {
                    // Sound is non-critical; ignore failures for mod compatibility.
                }
            }
        }
    }

    private GunType getGenericMGGunType() {
        for (InfoType type : InfoType.infoTypes.values()) {
            if (type instanceof GunType) {
                return (GunType) type;
            }
        }
        return null;
    }

    private BulletType getGenericBulletType() {
        GunType gunType = getGenericMGGunType();
        if (gunType != null && gunType.ammo != null && !gunType.ammo.isEmpty()) {
            ShootableType st = gunType.ammo.get(0);
            if (st instanceof BulletType) return (BulletType) st;
        }
        return null;
    }

    private int getFireRate(EntityDriveable vehicle, EntitySeat seat) {
        String gunName = "";
        if (seat.seatInfo.gunType != null) gunName = seat.seatInfo.gunType.shortName.toLowerCase();
        else if (seat.seatInfo.gunName != null) gunName = seat.seatInfo.gunName.toLowerCase();

        VehicleCategory cat = getVehicleCategory(vehicle);

        boolean isMachineGun = gunName.contains("mg") || gunName.contains("machine") || gunName.contains("browning") || gunName.contains("m2");

        if (isMachineGun) {
            return ModConfig.vehicleCombat.machineGunFireRate;
        }

        if (gunName.contains("flak") || gunName.contains("bofors") || gunName.contains("aa")) {
            return 6;
        }

        if (gunName.contains("37mm") || gunName.contains("20mm")) {
            return 50;
        }

        if (gunName.contains("missile") || gunName.contains("rocket")) {
            return 80;
        }

        return 60;
    }

    private boolean canSeeTarget(EntitySeat seat, Entity target) {
        double targetHeight = target instanceof EntityLivingBase ? ((EntityLivingBase)target).getEyeHeight() : target.height * 0.5;
        Vec3d start = new Vec3d(seat.posX, seat.posY + seat.getEyeHeight(), seat.posZ);
        Vec3d end = new Vec3d(target.posX, target.posY + targetHeight, target.posZ);

        net.minecraft.util.math.RayTraceResult result = this.world.rayTraceBlocks(start, end, false, true, false);

        if (result != null && result.typeOfHit == net.minecraft.util.math.RayTraceResult.Type.BLOCK) {
            return false;
        }

        return true;
    }

    private void spawnCustomExplosion(EntityDriveable vehicle, Entity target, EntitySeat seat, double dist) {
        double targetHeight = target instanceof EntityLivingBase ? ((EntityLivingBase)target).getEyeHeight() * 0.7 : target.height * 0.5;
        double dx = target.posX - seat.posX;
        double dy = (target.posY + targetHeight) - (seat.posY + seat.getEyeHeight());
        double dz = target.posZ - seat.posZ;

        double scatter = Math.min(6.0, dist / 8.0); // wider miss radius so shots visibly land off-target
        double explosionX = target.posX + (this.rand.nextDouble() - 0.5) * scatter;
        double explosionY = target.posY + (this.rand.nextDouble() - 0.5) * (scatter / 2.0);
        double explosionZ = target.posZ + (this.rand.nextDouble() - 0.5) * scatter;

        float explosionPower = getExplosionPowerForVehicle(vehicle);

        // Shield friendly crew/infantry inside the blast so the main gun never damages its own
        // vehicle's crew or nearby allied troops. The vehicle (exploder) is already excluded by
        // vanilla; this covers everyone riding it and friendly units standing beside it.
        java.util.List<Entity> shielded = shieldFriendliesForBlast(explosionX, explosionY, explosionZ, explosionPower);
        try {
            this.world.newExplosion(vehicle, explosionX, explosionY, explosionZ, explosionPower, false, explosionPower > 2.0F);
        } finally {
            clearExplosionShields(shielded);
        }

        spawnImpactParticles(explosionX, explosionY, explosionZ);

        this.world.playSound(null, explosionX, explosionY, explosionZ,
                net.minecraft.init.SoundEvents.ENTITY_GENERIC_EXPLODE,
                net.minecraft.util.SoundCategory.HOSTILE, 4.0F, 0.8F + rand.nextFloat() * 0.4F);

        if (dist < 70.0D) {
            // ACCURACY NERF: the main gun is NOT a guaranteed hit. Before this it ALWAYS applied full
            // damage to any target within 70 blocks regardless of aim -- that is why it deleted the player
            // the instant any pixel was visible. Now the direct hit chance falls off hard with range (the
            // scatter explosion above can still clip the player on a "miss"), and each hit is softer.
            double hitChance = (dist < 12) ? 0.65 : (dist < 25) ? 0.42 : (dist < 45) ? 0.26 : 0.14;
            if (this.rand.nextDouble() < hitChance) {
                float baseDamage = ModConfig.vehicleCombat.mainGunDamage;
                float damage = (baseDamage - (float)(dist / 2.5)) * 0.6f;
                if (damage > 0) {
                    target.attackEntityFrom(DamageSource.causeExplosionDamage(this), damage);
                }
            }
        }
    }

    /**
     * Flag every friendly (same-team) crew member and infantry inside an upcoming blast so the
     * synchronous {@link World#newExplosion} call cannot damage them. Returns the shielded list
     * so the caller can clear the flags immediately afterwards.
     */
    private java.util.List<Entity> shieldFriendliesForBlast(double x, double y, double z, float power) {
        String myTeam = this.getMcmTeam();
        double r = power * 2.0 + 2.0;
        net.minecraft.util.math.AxisAlignedBB box =
                new net.minecraft.util.math.AxisAlignedBB(x - r, y - r, z - r, x + r, y + r, z + r);

        java.util.List<Entity> shielded = new java.util.ArrayList<>();
        for (Entity e : world.getEntitiesWithinAABB(Entity.class, box)) {
            if (e == null || e.isDead) continue;

            if (e instanceof EntityAIPilot) {
                if (sameTeam(myTeam, ((EntityAIPilot) e).getMcmTeam())) {
                    ((EntityAIPilot) e).setExplosionShield(true);
                    shielded.add(e);
                }
            } else if (e instanceof studio.ERM.war.BattleManagers.entities.EntitySoldier) {
                if (sameTeam(myTeam, ((studio.ERM.war.BattleManagers.entities.EntitySoldier) e).getTeam_())) {
                    ((studio.ERM.war.BattleManagers.entities.EntitySoldier) e).setExplosionShield(true);
                    shielded.add(e);
                }
            }
        }
        return shielded;
    }

    private void clearExplosionShields(java.util.List<Entity> shielded) {
        if (shielded == null) return;
        for (Entity e : shielded) {
            if (e instanceof EntityAIPilot) {
                ((EntityAIPilot) e).setExplosionShield(false);
            } else if (e instanceof studio.ERM.war.BattleManagers.entities.EntitySoldier) {
                ((studio.ERM.war.BattleManagers.entities.EntitySoldier) e).setExplosionShield(false);
            }
        }
    }

    private static boolean sameTeam(String a, String b) {
        return a != null && b != null && a.equalsIgnoreCase(b);
    }

    private float getExplosionPowerForVehicle(EntityDriveable vehicle) {
        String name = vehicle.getDriveableType().shortName.toLowerCase();

        if (matches(name, MODERN_MBT)) return ModConfig.vehicleCombat.modernTankExplosion;
        if (matches(name, HEAVY_TANKS)) return ModConfig.vehicleCombat.heavyTankExplosion;
        if (matches(name, MEDIUM_TANKS)) return ModConfig.vehicleCombat.mediumTankExplosion;
        if (matches(name, ARMORED_CARS) || matches(name, LIGHT_TRANSPORTS)) return ModConfig.vehicleCombat.lightVehicleExplosion;
        if (matches(name, AA_GUNS) || matches(name, HEAVY_ARTILLERY)) return ModConfig.vehicleCombat.artilleryExplosion;

        return 2.0F;
    }

    private void spawnImpactParticles(double x, double y, double z) {
        for (int i = 0; i < 20; i++) {
            double offsetX = (this.rand.nextDouble() - 0.5) * 2.0;
            double offsetY = this.rand.nextDouble() * 2.0;
            double offsetZ = (this.rand.nextDouble() - 0.5) * 2.0;

            this.world.spawnParticle(net.minecraft.util.EnumParticleTypes.SMOKE_LARGE,
                    x + offsetX, y + offsetY, z + offsetZ,
                    offsetX * 0.2, offsetY * 0.3, offsetZ * 0.2);
        }

        for (int i = 0; i < 15; i++) {
            double offsetX = (this.rand.nextDouble() - 0.5) * 1.5;
            double offsetY = this.rand.nextDouble() * 1.5;
            double offsetZ = (this.rand.nextDouble() - 0.5) * 1.5;

            this.world.spawnParticle(net.minecraft.util.EnumParticleTypes.EXPLOSION_LARGE,
                    x + offsetX, y + offsetY, z + offsetZ, 0.0, 0.0, 0.0);
        }

        for (int i = 0; i < 10; i++) {
            double offsetX = (this.rand.nextDouble() - 0.5) * 1.0;
            double offsetY = this.rand.nextDouble() * 1.0;
            double offsetZ = (this.rand.nextDouble() - 0.5) * 1.0;

            this.world.spawnParticle(net.minecraft.util.EnumParticleTypes.FLAME,
                    x + offsetX, y + offsetY, z + offsetZ,
                    offsetX * 0.1, offsetY * 0.2, offsetZ * 0.1);
        }
    }

    private void spawnMuzzleFlash(EntitySeat seat) {
        double yaw = Math.toRadians(seat.looking.getYaw());
        double pitch = Math.toRadians(seat.looking.getPitch());

        double distance = 3.0;
        double muzzleX = seat.posX + distance * Math.cos(pitch) * Math.cos(yaw);
        double muzzleY = seat.posY + seat.getEyeHeight() - distance * Math.sin(pitch);
        double muzzleZ = seat.posZ + distance * Math.cos(pitch) * Math.sin(yaw);

        for (int i = 0; i < 15; i++) {
            double offsetX = (this.rand.nextDouble() - 0.5) * 1.0;
            double offsetY = (this.rand.nextDouble() - 0.5) * 1.0;
            double offsetZ = (this.rand.nextDouble() - 0.5) * 1.0;

            this.world.spawnParticle(net.minecraft.util.EnumParticleTypes.SMOKE_LARGE,
                    muzzleX + offsetX, muzzleY + offsetY, muzzleZ + offsetZ,
                    Math.cos(pitch) * Math.cos(yaw) * 0.5,
                    -Math.sin(pitch) * 0.5,
                    Math.cos(pitch) * Math.sin(yaw) * 0.5);
        }

        for (int i = 0; i < 8; i++) {
            this.world.spawnParticle(net.minecraft.util.EnumParticleTypes.EXPLOSION_NORMAL,
                    muzzleX, muzzleY, muzzleZ, 0.0, 0.0, 0.0);
        }

        for (int i = 0; i < 5; i++) {
            this.world.spawnParticle(net.minecraft.util.EnumParticleTypes.FLAME,
                    muzzleX, muzzleY, muzzleZ, 0.0, 0.0, 0.0);
        }
    }

    private List<EntitySeat> getSeatsFromVehicle(EntityDriveable vehicle) {
        if (seatsField == null) {
            try {
                seatsField = EntityDriveable.class.getDeclaredField("seats");
                seatsField.setAccessible(true);
            } catch (Exception e) {
                e.printStackTrace();
                return null;
            }
        }
        try {
            Object obj = seatsField.get(vehicle);
            if (obj instanceof EntitySeat[]) {
                return Arrays.asList((EntitySeat[]) obj);
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
        return null;
    }

    private VehicleCategory categorizeVehicle(EntityDriveable vehicle) {
        String name = vehicle.getDriveableType().shortName.toLowerCase();

        if (matches(name, NAVAL_VESSELS)) return VehicleCategory.BOAT;
        if (matches(name, STATIC_WEAPONS)) return VehicleCategory.STATIC;
        if (matches(name, LIGHT_TRANSPORTS) || matches(name, SOFT_VEHICLES)) return VehicleCategory.TRANSPORT;

        return VehicleCategory.TANK;
    }

    private void updateVehicleControl(EntityDriveable driving) {
        if (driving == null) return;
        if (driving.axes == null) driving.axes = new RotatedAxes();
        if (driving.angularVelocity == null) driving.angularVelocity = new Vector3f(0F, 0F, 0F);

        driving.serverPosX = (long)driving.posX;
        driving.serverPosY = (long)driving.posY;
        driving.serverPosZ = (long)driving.posZ;
        driving.serverYaw = driving.axes.getYaw();

        if (driving.driveableData != null) {
            FlansMod.getPacketHandler().sendToAllAround(new PacketVehicleControl(driving),
                    driving.posX, driving.posY, driving.posZ,
                    FlansMod.driveableUpdateRange, driving.dimension);
        }

        if (driving instanceof EntityVehicle) {
            EntityVehicle v = (EntityVehicle) driving;
            driving.setPositionRotationAndMotion(driving.posX, driving.posY, driving.posZ,
                    driving.axes.getYaw(), driving.axes.getPitch(), driving.axes.getRoll(),
                    driving.motionX, driving.motionY, driving.motionZ,
                    driving.angularVelocity.x, driving.angularVelocity.y, driving.angularVelocity.z,
                    driving.throttle, v.wheelsYaw);
        }
    }

    /**
     * Drive the hull toward a world (x,z) and HOLD within ~5 blocks. Same steering/throttle math as
     * controlGround's approach, but aimed at a fixed point instead of an entity -- used for the staging
     * rally so scouts/transports push up to the breach and stop, rather than charging the player.
     */
    private void driveHullToward(EntityVehicle tank, double tx, double tz) {
        if (tank.axes == null) return;
        double dx = tx - tank.posX;
        double dz = tz - tank.posZ;
        if (dx * dx + dz * dz <= 25.0D) { tank.throttle = 0.0F; tank.wheelsYaw = 0.0F; return; } // arrived -> hold

        Vector3f forward = tank.axes.getXAxis();
        double cross = (forward.x * dz) - (forward.z * dx);
        double dot = (forward.x * dx) + (forward.z * dz);

        float turnPower = 0.0F;
        if (cross > 0.5) turnPower = 1.0F;
        else if (cross < -0.5) turnPower = -1.0F;
        if (dot < 0) turnPower = (cross > 0) ? 1.0F : -1.0F;

        tank.wheelsYaw = turnPower * 25.0F;
        tank.throttle = 0.7F;
        float power = 0.4F * Math.signum(tank.throttle);
        tank.motionX += forward.x * power;
        tank.motionZ += forward.z * power;
    }

    /**
     * Classify the per-vehicle MOVEMENT style from the Flan ShortName. Order matters: LIGHT (armoured
     * cars) is checked before SCOUT so "sdkfz222" does not get swallowed by the "sdkfz2" scout
     * substring; HEAVY before MID so heavies win shared tokens. Returns DEFAULT when nothing matches
     * so the caller uses the legacy controlGround path.
     */
    private MovementStyle getMovementStyle(EntityDriveable vehicle) {
        if (vehicle == null || vehicle.getDriveableType() == null
                || vehicle.getDriveableType().shortName == null) return MovementStyle.DEFAULT;
        String name = vehicle.getDriveableType().shortName.toLowerCase();

        if (matches(name, STYLE_LIGHT))  return MovementStyle.LIGHT_TANK; // armoured cars first (sdkfz222 vs sdkfz2)
        if (matches(name, STYLE_SCOUT))  return MovementStyle.SCOUT;
        if (matches(name, STYLE_HEAVY) || matches(name, MODERN_MBT)) return MovementStyle.HEAVY_TANK;
        if (matches(name, STYLE_MID))    return MovementStyle.MID_TANK;
        if (matches(name, MEDIUM_TANKS)) return MovementStyle.MID_TANK;  // catch any other mediums
        if (matches(name, HEAVY_TANKS))  return MovementStyle.HEAVY_TANK; // catch any other heavies
        return MovementStyle.DEFAULT;
    }

    /**
     * Hull-movement dispatcher for the phase-2 styles. Turret aiming/firing is NOT touched here --
     * handleAllTurrets() still runs every tick in runControlLogic, so a vehicle that holds at
     * standoff still tracks and fires. Distances use getDistanceSq() (pilot<->target) like controlGround.
     */
    private void applyMovementStyle(EntityVehicle tank, Entity target, MovementStyle style, boolean isTransport) {
        if (tank.axes == null || target == null) return;

        switch (style) {
            case SCOUT:      moveScout(tank, target); break;
            case LIGHT_TANK: moveStandoff(tank, target, LIGHT_STANDOFF); break;
            case HEAVY_TANK: moveStandoff(tank, target, HEAVY_STANDOFF); break;
            case MID_TANK:   moveAdvanceByBounds(tank, target, MID_ASSAULT_RANGE); break;
            default:
                // Safety net: behave like a transport/tank legacy hull if we somehow get here.
                controlGround(tank, target, isTransport);
        }
    }

    /**
     * SCOUT: circle the target at SCOUT_ORBIT_RADIUS (never closes to gate range), strafing past and
     * relocating. Retreats outward when hull HP drops below SCOUT_RETREAT_HP. Reuses driveHullToward.
     */
    private void moveScout(EntityVehicle tank, Entity target) {
        float hpRatio = this.getMaxHealth() > 0 ? this.getHealth() / this.getMaxHealth() : 1.0F;
        double dx = target.posX - tank.posX;
        double dz = target.posZ - tank.posZ;
        double dist = Math.sqrt(dx * dx + dz * dz);
        if (dist < 0.001) dist = 0.001;
        double nx = dx / dist, nz = dz / dist;

        if (hpRatio < SCOUT_RETREAT_HP) {
            // RETREAT: drive directly away from the target.
            driveHullToward(tank, tank.posX - nx * 30.0, tank.posZ - nz * 30.0);
            return;
        }

        // Nearest point on the orbit ring toward us, then lead 16 blocks tangentially -> drive-by + relocate.
        // tangent of (nx,nz) is (-nz,nx).
        double cx = target.posX - nx * SCOUT_ORBIT_RADIUS;
        double cz = target.posZ - nz * SCOUT_ORBIT_RADIUS;
        double tx = cx + (-nz) * 16.0;
        double tz = cz + (nx) * 16.0;
        driveHullTowardHolding(tank, tx, tz, 3.0);
    }

    /**
     * STANDOFF (LIGHT/HEAVY): advance until within `standoff` blocks of the target, then HOLD and let
     * the turret fire. Never reverses to chase and never closes past standoff -> heavies don't get
     * dragged into courtyards.
     */
    private void moveStandoff(EntityVehicle tank, Entity target, double standoff) {
        double d0 = this.getDistanceSq(target);
        if (d0 > standoff * standoff) {
            driveHullToward(tank, target.posX, target.posZ); // close to the standoff line
        } else {
            tank.throttle = 0.0F; // hold; turret does the work
            tank.wheelsYaw = 0.0F;
        }
    }

    /**
     * MID advance-by-bounds: alternate a ~40-tick MOVE phase and a ~20-tick HALT phase so the gun
     * fires from a stop, until within `assaultRange` blocks; then hold at the assault line.
     */
    private void moveAdvanceByBounds(EntityVehicle tank, Entity target, double assaultRange) {
        double d0 = this.getDistanceSq(target);
        if (d0 <= assaultRange * assaultRange) {
            tank.throttle = 0.0F; tank.wheelsYaw = 0.0F; // reached the assault line -> hold + fire
            return;
        }
        boundPhaseTicks = (boundPhaseTicks + 1) % 60;
        if (boundPhaseTicks < 40) {
            driveHullToward(tank, target.posX, target.posZ); // MOVE bound
        } else {
            tank.throttle = 0.0F; tank.wheelsYaw = 0.0F;     // HALT bound: fire from a stop
        }
    }

    /**
     * Like driveHullToward but with a configurable HOLD radius (in blocks). driveHullToward hardcodes
     * a 5-block hold; scouts want a tighter hold so they keep relocating around the ring.
     */
    private void driveHullTowardHolding(EntityVehicle tank, double tx, double tz, double holdBlocks) {
        if (tank.axes == null) return;
        double dx = tx - tank.posX;
        double dz = tz - tank.posZ;
        if (dx * dx + dz * dz <= holdBlocks * holdBlocks) { tank.throttle = 0.0F; tank.wheelsYaw = 0.0F; return; }

        Vector3f forward = tank.axes.getXAxis();
        double cross = (forward.x * dz) - (forward.z * dx);
        double dot = (forward.x * dx) + (forward.z * dz);

        float turnPower = 0.0F;
        if (cross > 0.5) turnPower = 1.0F;
        else if (cross < -0.5) turnPower = -1.0F;
        if (dot < 0) turnPower = (cross > 0) ? 1.0F : -1.0F;

        tank.wheelsYaw = turnPower * 25.0F;
        tank.throttle = 0.7F;
        float power = 0.4F * Math.signum(tank.throttle);
        tank.motionX += forward.x * power;
        tank.motionZ += forward.z * power;
    }

    private void controlGround(EntityDriveable driveable, Entity target, boolean isTransport) {
        if (!(driveable instanceof EntityVehicle)) return;
        EntityVehicle tank = (EntityVehicle) driveable;
        if (tank.axes == null) return;

        double d0 = this.getDistanceSq(target);
        double maxEngagementRangeSq = isTransport ? 1600.0D : 6400.0D;

        if (d0 > maxEngagementRangeSq) {
            double dx = target.posX - tank.posX;
            double dz = target.posZ - tank.posZ;
            Vector3f forward = tank.axes.getXAxis();

            double cross = (forward.x * dz) - (forward.z * dx);
            double dot = (forward.x * dx) + (forward.z * dz);

            float turnPower = 0.0F;
            if (cross > 0.5) turnPower = 1.0F;
            else if (cross < -0.5) turnPower = -1.0F;
            if (dot < 0) turnPower = (cross > 0) ? 1.0F : -1.0F;

            tank.wheelsYaw = turnPower * 25.0F;
            tank.throttle = 0.7F;

            if (Math.abs(tank.throttle) > 0.05F) {
                float power = 0.4F * Math.signum(tank.throttle);
                tank.motionX += forward.x * power;
                tank.motionZ += forward.z * power;
            }
            return;
        }

        double dx = target.posX - tank.posX;
        double dz = target.posZ - tank.posZ;
        Vector3f forward = tank.axes.getXAxis();

        double cross = (forward.x * dz) - (forward.z * dx);
        double dot = (forward.x * dx) + (forward.z * dz);

        // Movement Logic
        float currentThrottle = 0.0F;
        if (d0 > 900) currentThrottle = 0.6F; // 30 blocks
        else if (d0 < 100) currentThrottle = -0.3F; // 10 blocks

        tank.throttle = currentThrottle;

        // --- HULL TURNING LOGIC ---
        // Hull only turns when actively moving
        if (Math.abs(tank.throttle) > 0.05F) {
            float turnPower = (cross > 0.5) ? 1.0F : (cross < -0.5 ? -1.0F : 0.0F);
            if (dot < 0) turnPower = (cross > 0) ? 1.0F : -1.0F;

            tank.wheelsYaw = turnPower * 25.0F;

            float power = 0.35F * Math.signum(tank.throttle);
            tank.motionX += forward.x * power;
            tank.motionZ += forward.z * power;
        } else {
            // Static position: hull stays stationary, turret tracks independently
            tank.wheelsYaw = 0.0F;
        }

        if (isTransport) {
            // Jeep dismount buffer at 35 blocks
            if (d0 < 1225) {
                tank.throttle = 0.0F;
                tank.wheelsYaw = 0.0F;

                if (!hasOrderedDismount) {
                    dismountAllSeats(tank);
                    hasOrderedDismount = true;
                    dismountWaitTime = 60;
                }

                if (dismountWaitTime > 0) {
                    dismountWaitTime--;
                    return;
                }
            }
            // Remount snap at 45 blocks
            else if (d0 >= 2025) {
                hasOrderedDismount = false;
                dismountWaitTime = 0;
                teleportCrewToVehicle(tank);
            }
        }
    }

    private void teleportCrewToVehicle(EntityDriveable vehicle) {
        EntitySeat[] seats = getSeatsReflected(vehicle);
        if (seats == null) return;

        for (EntityAIPilot pilot : this.world.getEntitiesWithinAABB(EntityAIPilot.class, vehicle.getEntityBoundingBox().grow(50))) {
            if (pilot.isPassenger && pilot.parentVehicle == vehicle && !pilot.isRiding()) {
                for (EntitySeat s : seats) {
                    if (s != null && s.getControllingPassenger() == null) {
                        pilot.startRiding(s, true);
                        break;
                    }
                }
            }
        }
    }

    private void controlBoat(EntityDriveable boat, Entity target) {
        if(!boat.isInWater()) return;
        if (boat instanceof EntityVehicle) controlGround(boat, target, false);
        boat.motionY = 0;
    }

    private void emergencyEject(EntityDriveable vehicle) {
        EntitySeat[] seats = getSeatsReflected(vehicle);
        if (seats == null) return;
        for (EntitySeat s : seats) {
            if (s == null) continue;
            for (Entity p : s.getPassengers()) {
                p.dismountRidingEntity();
                if (p instanceof EntityAIPilot) {
                    EntityAIPilot clone = (EntityAIPilot) p;
                    clone.isPassenger = true;
                    if (vehicle instanceof EntityVehicle) clone.parentVehicle = (EntityVehicle) vehicle;
                    clone.setSize(0.6F, 1.8F);
                    clone.setAttackTarget(this.lockedTarget instanceof EntityLivingBase ? (EntityLivingBase)this.lockedTarget : null);
                    clone.setItemStackToSlot(EntityEquipmentSlot.MAINHAND, getRandomWeaponItem(false));
                }
            }
        }
    }

    private void dismountAllSeats(EntityDriveable vehicle) {
        EntitySeat[] seats = getSeatsReflected(vehicle);
        if (seats == null) return;
        for (EntitySeat s : seats) {
            if (s == null || s.seatInfo.id == 0) continue;
            for (Entity p : s.getPassengers()) {
                p.dismountRidingEntity();
                if (p instanceof EntityAIPilot) {
                    EntityAIPilot clone = (EntityAIPilot) p;
                    clone.isPassenger = true;
                    if (vehicle instanceof EntityVehicle) clone.parentVehicle = (EntityVehicle) vehicle;
                    clone.setSize(0.6F, 1.8F);
                    clone.setAttackTarget(this.lockedTarget instanceof EntityLivingBase ? (EntityLivingBase)this.lockedTarget : null);
                    clone.setItemStackToSlot(EntityEquipmentSlot.MAINHAND, getRandomWeaponItem(true));
                }
            }
        }
    }

    private double getFireRangeSq(EntityDriveable vehicle) {
        VehicleCategory vcat = getVehicleCategory(vehicle);
        if (vcat == VehicleCategory.STATIC) return 120*120;
        if (vcat == VehicleCategory.TANK) return 90*90;
        if (vcat == VehicleCategory.TRANSPORT) return 40*40;
        return 70*70;
    }

    private VehicleCategory getVehicleCategory(EntityDriveable vehicle) {
        if (vehicle == null) return VehicleCategory.TRANSPORT;
        if (vehicle instanceof EntityPlane) return VehicleCategory.STATIC;

        DriveableType dt = vehicle.getDriveableType();
        if (dt == null || dt.shortName == null) return VehicleCategory.TRANSPORT;

        String name = dt.shortName.toLowerCase();

        if (matches(name, LIGHT_TRANSPORTS) || matches(name, SOFT_VEHICLES) || matches(name, ARMORED_CARS)) return VehicleCategory.TRANSPORT;
        if (matches(name, STATIC_WEAPONS) || matches(name, HEAVY_ARTILLERY) || matches(name, AA_GUNS)) return VehicleCategory.STATIC;
        if (matches(name, NAVAL_VESSELS) || name.contains("boat")) return VehicleCategory.BOAT;

        return VehicleCategory.TANK;
    }

    private void spawnAndMountVehicle() {
        try {
            InfoType type = InfoType.getType(vehicleToSummon);

            if (type == null && InfoType.infoTypes != null) {
                String lower = vehicleToSummon.toLowerCase().replace("flansmod:", "");
                for (InfoType t : InfoType.infoTypes.values()) {
                    if (t.shortName.toLowerCase().equals(lower)) {
                        type = t;
                        break;
                    }
                }
            }

            if (type instanceof DriveableType) {
                if (type instanceof PlaneType) {
                    this.setDead();
                    return;
                }

                DriveableType dtype = (DriveableType) type;
                NBTTagCompound tags = new NBTTagCompound();
                tags.setString("Type", dtype.shortName);
                tags.setString("Engine", "V8");

                if(PartType.parts != null) {
                    for(PartType p : PartType.parts) {
                        if(p.category == EnumPartCategory.ENGINE) {
                            tags.setString("Engine", p.shortName);
                            break;
                        }
                    }
                }

                DriveableData data = new DriveableData(tags, dtype.numCargoSlots);
                data.fuelInTank = dtype.fuelTankSize;

                for(EnumDriveablePart part : EnumDriveablePart.values()) {
                    CollisionBox box = dtype.health.get(part);
                    if (box != null) {
                        DriveablePart dp = new DriveablePart(part, box);
                        dp.health = dp.maxHealth;
                        dp.dead = false;
                        data.parts.put(part, dp);
                    }
                }

                double spawnY = this.posY + 2.5D;
                EntityVehicle v = new EntityVehicle(this.world, this.posX, spawnY, this.posZ, (VehicleType)dtype, data);
                // Team tag for friendly-fire filtering (used by our AI, not Flans itself)
                v.getEntityData().setString(NBT_TEAM, this.getMcmTeam());
                v.getEntityData().setBoolean(NBT_ALLOW_VEHICLE_TARGETS, this.isVehicleTargetingEnabled());


                this.world.spawnEntity(v);

                EntitySeat[] seats = getSeatsReflected(v);
                if (seats != null && seats.length > 0 && seats[0] != null) {
                    this.startRiding(seats[0]);
                    this.parentVehicle = v;
                }

                VehicleCategory vcat = getVehicleCategory(v);
                applyTieredHealth(dtype.shortName, vcat);
                broadcastError(TextFormatting.GREEN + "AI: Deployed " + dtype.shortName);

                this.setItemStackToSlot(EntityEquipmentSlot.MAINHAND, getRandomWeaponItem(false));
            }
        } catch (Exception e) { e.printStackTrace(); }
    }

    private void findAndMountVehicle() {
        for(Entity e : this.world.getEntitiesWithinAABB(EntityDriveable.class, this.getEntityBoundingBox().grow(10))) {
            EntityDriveable driveable = (EntityDriveable) e;
            if(!driveable.isDead) {
                EntitySeat[] seats = getSeatsReflected(driveable);
                if(seats != null && seats.length > 0 && seats[0].getControllingPassenger() == null) {
                    this.startRiding(seats[0]);
                    if (driveable instanceof EntityVehicle) this.parentVehicle = (EntityVehicle) driveable;
                    return;
                }
            }
        }
    }

    private void spawnEmpireCrew(EntityDriveable vehicle) {
        EntitySeat[] seats = getSeatsReflected(vehicle);
        if (seats == null) return;

        initialCrewSize = 1;

        for (int i = 1; i < seats.length; i++) {
            EntitySeat seat = seats[i];
            if (seat != null && seat.getControllingPassenger() == null) {
                EntityAIPilot passenger = new EntityAIPilot(this.world);
                passenger.setPosition(seat.posX, seat.posY, seat.posZ);
                passenger.isPassenger = true;
                if (vehicle instanceof EntityVehicle) passenger.parentVehicle = (EntityVehicle) vehicle;
                passenger.setMcmTeam(this.getMcmTeam());
                passenger.setVehicleTargetingEnabled(this.isVehicleTargetingEnabled());


                passenger.setItemStackToSlot(EntityEquipmentSlot.MAINHAND, getRandomWeaponItem(true));

                this.world.spawnEntity(passenger);
                passenger.startRiding(seat);
                initialCrewSize++;
            }
        }
    }

    // ---------------------------------------------------------------------
    // Team tags (prevents friendly vehicle-vs-vehicle fights)
    // ---------------------------------------------------------------------

    public String getMcmTeam() {
        NBTTagCompound nbt = this.getEntityData();
        if (nbt != null && nbt.hasKey(NBT_TEAM)) {
            String t = nbt.getString(NBT_TEAM);
            if (t != null && !t.isEmpty()) return t;
        }
        return mcmTeam;
    }

    public void setMcmTeam(String team) {
        if (team == null || team.trim().isEmpty()) team = "NONE";
        this.mcmTeam = team;
        this.getEntityData().setString(NBT_TEAM, team);
    }
    /**
     * Convenience: copy the spawning players team (FTB Teams / scoreboard / UUID) into this pilot
     * so spawned vehicles and crew inherit it and won't fight friendlies.
     */
    public void adoptTeamFromPlayer(EntityPlayer player) {
        if (player == null) return;
        setMcmTeam(getTeamTag(player, this));
    }


    public boolean isVehicleTargetingEnabled() {
        NBTTagCompound nbt = this.getEntityData();
        if (nbt != null && nbt.hasKey(NBT_ALLOW_VEHICLE_TARGETS)) {
            return nbt.getBoolean(NBT_ALLOW_VEHICLE_TARGETS);
        }
        return allowVehicleTargets;
    }

    public void setVehicleTargetingEnabled(boolean enabled) {
        this.allowVehicleTargets = enabled;
        this.getEntityData().setBoolean(NBT_ALLOW_VEHICLE_TARGETS, enabled);
    }

    private EntityDriveable getControllingDriveable() {
        if (this.isRiding() && this.getRidingEntity() instanceof EntitySeat) {
            EntityDriveable d = ((EntitySeat) this.getRidingEntity()).driveable;
            if (d != null && !d.isDead) return d;
        }
        if (this.parentVehicle != null && !this.parentVehicle.isDead) return this.parentVehicle;
        return null;
    }

    private boolean isFriendlyVehicle(EntityDriveable v) {
        String myTeam = getTeamTag(getControllingDriveable(), this);
        String theirTeam = getTeamTag(v, null);
        return myTeam != null && myTeam.equals(theirTeam);
    }

    private static String resolveFtbTeamId(EntityPlayer player) {
        try {
            if (!net.minecraftforge.fml.common.Loader.isModLoaded("ftbteams")
                    && !net.minecraftforge.fml.common.Loader.isModLoaded("ftblib")
                    && !net.minecraftforge.fml.common.Loader.isModLoaded("ftbutilities")) {
                return null;
            }
        } catch (Throwable ignored) { }

        String[] candidateClasses = new String[] {
                "com.feed_the_beast.ftblib.lib.data.ForgeTeam",
                "com.feed_the_beast.ftblib.lib.data.Team",
                "dev.ftb.mods.ftbteams.api.FTBTeamsAPI",
                "dev.ftb.mods.ftbteams.FTBTeamsAPI"
        };

        for (String cn : candidateClasses) {
            String id = tryResolveTeamFromClass(cn, player);
            if (id != null && !id.isEmpty()) return "FTB:" + id;
        }

        return null;
    }

    private static String tryResolveTeamFromClass(String className, EntityPlayer player) {
        try {
            Class<?> c = Class.forName(className);

            for (java.lang.reflect.Method m : c.getMethods()) {
                if ((m.getName().toLowerCase().contains("team") || m.getName().toLowerCase().contains("player"))
                        && java.lang.reflect.Modifier.isStatic(m.getModifiers())
                        && m.getParameterTypes().length == 1
                        && m.getReturnType() != Void.TYPE) {

                    Class<?> p0 = m.getParameterTypes()[0];
                    Object teamObj = null;
                    if (p0.isAssignableFrom(EntityPlayer.class)) {
                        teamObj = m.invoke(null, player);
                    } else if (p0.isAssignableFrom(java.util.UUID.class)) {
                        teamObj = m.invoke(null, player.getUniqueID());
                    }

                    String id = extractTeamId(teamObj);
                    if (id != null && !id.isEmpty()) return id;
                }
            }

            for (java.lang.reflect.Method m : c.getMethods()) {
                if (java.lang.reflect.Modifier.isStatic(m.getModifiers())
                        && m.getParameterTypes().length == 0
                        && m.getReturnType() != Void.TYPE
                        && (m.getName().equalsIgnoreCase("instance") || m.getName().equalsIgnoreCase("getInstance"))) {

                    Object api = m.invoke(null);
                    if (api == null) continue;

                    for (java.lang.reflect.Method m2 : api.getClass().getMethods()) {
                        if ((m2.getName().toLowerCase().contains("team"))
                                && m2.getParameterTypes().length == 1
                                && m2.getReturnType() != Void.TYPE) {

                            Class<?> p0 = m2.getParameterTypes()[0];
                            Object teamObj = null;
                            if (p0.isAssignableFrom(EntityPlayer.class)) {
                                teamObj = m2.invoke(api, player);
                            } else if (p0.isAssignableFrom(java.util.UUID.class)) {
                                teamObj = m2.invoke(api, player.getUniqueID());
                            }

                            String id = extractTeamId(teamObj);
                            if (id != null && !id.isEmpty()) return id;
                        }
                    }
                }
            }
        } catch (Throwable ignored) { }
        return null;
    }

    private static String extractTeamId(Object teamObj) {
        if (teamObj == null) return null;

        String[] getters = new String[] {"getId", "getUID", "getUid", "getName", "getDisplayName"};
        for (String g : getters) {
            try {
                java.lang.reflect.Method m = teamObj.getClass().getMethod(g);
                Object v = m.invoke(teamObj);
                if (v != null) return v.toString();
            } catch (Throwable ignored) { }
        }

        String[] fields = new String[] {"id", "uid", "name"};
        for (String f : fields) {
            try {
                java.lang.reflect.Field fld = teamObj.getClass().getDeclaredField(f);
                fld.setAccessible(true);
                Object v = fld.get(teamObj);
                if (v != null) return v.toString();
            } catch (Throwable ignored) { }
        }

        return teamObj.toString();
    }

    private EntitySeat[] getSeatsReflected(EntityDriveable vehicle) {
        try {
            if (seatsField == null) {
                seatsField = EntityDriveable.class.getDeclaredField("seats");
                seatsField.setAccessible(true);
            }
            return (EntitySeat[]) seatsField.get(vehicle);
        } catch (Exception e) { return null; }
    }

    private void broadcastError(String msg) {
        for(EntityPlayer p : this.world.playerEntities) {
            if(p.getDistance(this) < 20) {
                p.sendMessage(new TextComponentString(msg));
            }
        }
    }

    @Override
    public void writeEntityToNBT(NBTTagCompound compound) {
        super.writeEntityToNBT(compound);
        compound.setString("Vehicle", vehicleToSummon);
        compound.setBoolean("Spawned", hasSpawnedVehicle);
        compound.setBoolean("IsPassenger", isPassenger);
        compound.setString("mcmTeam", getMcmTeam());
        compound.setBoolean("mcmAllowVehicleTargets", isVehicleTargetingEnabled());
        compound.setString("ermSkinKey", this.dataManager.get(DW_SKIN_KEY));

    }


    @Override
    public void readEntityFromNBT(NBTTagCompound compound) {
        super.readEntityFromNBT(compound);
        this.vehicleToSummon = compound.getString("Vehicle");
        this.hasSpawnedVehicle = compound.getBoolean("Spawned");
        this.isPassenger = compound.getBoolean("IsPassenger");
        if (compound.hasKey("mcmTeam")) setMcmTeam(compound.getString("mcmTeam"));
        if (compound.hasKey("mcmAllowVehicleTargets")) setVehicleTargetingEnabled(compound.getBoolean("mcmAllowVehicleTargets"));
        if (compound.hasKey("ermSkinKey")) this.dataManager.set(DW_SKIN_KEY, compound.getString("ermSkinKey"));

    }


    // ---------------------------------------------------------------------
    // ISkinnable — synced via DataParameter so the client renderer (RenderSkinnable +
    // SkinTextureCache) resolves a real AW2 skin-pack texture for this Flan's vehicle pilot.
    // ---------------------------------------------------------------------
    @Override
    public String getSkinKey() {
        return this.dataManager.get(DW_SKIN_KEY);
    }

    @Override
    public void setSkinKey(String key) {
        this.dataManager.set(DW_SKIN_KEY, key != null ? key : "");
    }

    @Override
    public String getDefaultPoolName() {
        return "soldiers";
    }

    @Override
    public ResourceLocation getFallbackTexture() {
        return new ResourceLocation("minecraft", "textures/entity/steve.png");
    }

    // ---------------------------------------------------------------------
    // Display name fix:
    // Prevents death messages like "was shot by entity.aipilot.name" when no lang entry exists.
    // ---------------------------------------------------------------------
    @Override
    public String getName() {
        if (this.hasCustomName()) {
            return this.getCustomNameTag();
        }
        return "Empire Vehicle";
    }

}