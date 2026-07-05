package studio.ERM.war.entities;

import net.minecraft.entity.EntityCreature;
import net.minecraft.entity.SharedMonsterAttributes;
import net.minecraft.entity.ai.EntityAIHurtByTarget;
import net.minecraft.entity.ai.EntityAILookIdle;
import net.minecraft.entity.ai.EntityAISwimming;
import net.minecraft.entity.ai.EntityAIWanderAvoidWater;
import net.minecraft.entity.ai.EntityAIWatchClosest;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.init.SoundEvents;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.network.datasync.DataParameter;
import net.minecraft.network.datasync.DataSerializers;
import net.minecraft.network.datasync.EntityDataManager;
import net.minecraft.util.DamageSource;
import net.minecraft.util.EnumHand;
import net.minecraft.util.ResourceLocation;
import net.minecraft.util.SoundCategory;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.text.TextComponentString;
import net.minecraft.util.text.TextFormatting;
import net.minecraft.world.World;

import studio.ERM.EpochRunnerMod;
import studio.ERM.war.WarEventState;
import studio.ERM.war.districts.DistrictType;
import studio.ERM.war.entities.ai.EntityAIFindClaimBed;
import studio.ERM.war.entities.ai.EntityAISleepInClaimedBed;
import studio.ERM.war.entities.ai.EntityAIWorkDistrict;
import studio.ERM.war.skins.ISkinnable;
import studio.ERM.war.world.BedClaimData;

import javax.annotation.Nullable;

/**
 * Modern citizen — the player-spawned worker NPC.
 *
 * Was previously a bare stub (fields + getters only), which is why citizens stood frozen,
 * rendered as a missing texture, and ignored every right-click. This now wires up:
 *   - AW2 skin pack support via {@link ISkinnable} using a synced {@link DataParameter}
 *     (same proven pattern as EntitySoldier) so the renderer's SkinTextureCache resolves a
 *     real texture on every client. A skin is rolled from the "soldiers" pool on spawn.
 *   - Real AI: bed claim/sleep ({@link EntityAIFindClaimBed}/{@link EntityAISleepInClaimedBed})
 *     and daytime district work loiter ({@link EntityAIWorkDistrict}), plus vanilla
 *     swim/wander/watch/look.
 *   - Right-click interactions: hand a job tool (hammer/multimeter/gold_wrench/blueprint/
 *     command_buck) to assign a district job; empty hand reports status.
 *   - Attributes + NBT persistence for job, work site, bed, retreat flag and skin key.
 */
public class EntityModernCitizen extends EntityCreature implements ISkinnable {

    /** DataWatcher key — auto-synced to all clients so the renderer can resolve the skin. */
    private static final DataParameter<String> DW_SKIN_KEY =
            EntityDataManager.createKey(EntityModernCitizen.class, DataSerializers.STRING);

    private BlockPos assignedBed = null;
    private BlockPos workSite = null;
    private DistrictType currentJob = DistrictType.NONE;
    private boolean retreating = false;

    public EntityModernCitizen(World worldIn) {
        super(worldIn);
        this.setSize(0.6F, 1.8F);
        this.enablePersistence();
    }

    @Override
    protected void entityInit() {
        super.entityInit();
        this.dataManager.register(DW_SKIN_KEY, "");
    }

    // ===== AI =====

    @Override
    protected void initEntityAI() {
        this.tasks.addTask(0, new EntityAISwimming(this));
        // Sleep takes priority at night / during retreat (mutex 1 movement).
        this.tasks.addTask(1, new EntityAISleepInClaimedBed(this));
        // Claim a nearby bed if we don't have one yet.
        this.tasks.addTask(2, new EntityAIFindClaimBed(this, 24));
        // Daytime: loiter around the assigned work district.
        this.tasks.addTask(3, new EntityAIWorkDistrict(this));
        // Ambient fallbacks.
        this.tasks.addTask(7, new EntityAIWanderAvoidWater(this, 0.6D));
        this.tasks.addTask(8, new EntityAIWatchClosest(this, EntityPlayer.class, 8.0F));
        this.tasks.addTask(9, new EntityAILookIdle(this));

        this.targetTasks.addTask(1, new EntityAIHurtByTarget(this, true));
    }

    @Override
    protected void applyEntityAttributes() {
        super.applyEntityAttributes();
        this.getEntityAttribute(SharedMonsterAttributes.MAX_HEALTH).setBaseValue(20.0D);
        this.getEntityAttribute(SharedMonsterAttributes.MOVEMENT_SPEED).setBaseValue(0.5D);
        this.getEntityAttribute(SharedMonsterAttributes.FOLLOW_RANGE).setBaseValue(16.0D);
    }

    // ===== Interaction =====

    @Override
    public boolean processInteract(EntityPlayer player, EnumHand hand) {
        if (player == null) return super.processInteract(player, hand);

        // Predict success on the client so the arm swings; all real work is server-side.
        if (this.world.isRemote) return true;

        ItemStack held = player.getHeldItem(hand);
        if (!held.isEmpty()) {
            DistrictType job = jobForItem(held.getItem());
            if (job != null) {
                assignJob(player, job);
                return true;
            }
        }

        reportStatus(player);
        return true;
    }

    /** Maps a job tool item to the district job it assigns, or null if not a job tool. */
    @Nullable
    private static DistrictType jobForItem(Item item) {
        if (item == null) return null;
        if (item == EpochRunnerMod.hammer)       return DistrictType.RESOURCE;     // builder
        if (item == EpochRunnerMod.multimeter)   return DistrictType.POWER;        // electrician
        if (item == EpochRunnerMod.gold_wrench)  return DistrictType.INDUSTRY;     // mechanic
        if (item == EpochRunnerMod.blueprint)    return DistrictType.AGRICULTURE;  // planner
        if (item == EpochRunnerMod.command_buck) return DistrictType.DEFENSE;      // soldier
        return null;
    }

    private void assignJob(EntityPlayer player, DistrictType job) {
        setCurrentJob(job);
        // Anchor the work loiter where the player assigned the job (near a district marker).
        setWorkSite(this.getPosition());
        this.world.playSound(null, this.getPosition(),
                SoundEvents.ENTITY_VILLAGER_YES, SoundCategory.NEUTRAL, 0.9F, 1.0F);
        player.sendMessage(new TextComponentString(
                TextFormatting.GREEN + "Citizen assigned to " + jobLabel(job) + " duty."));
    }

    private void reportStatus(EntityPlayer player) {
        String jobStr = (currentJob == null || currentJob == DistrictType.NONE)
                ? "no assignment" : (jobLabel(currentJob) + " duty");
        String bedStr = hasBed() ? "has a bed" : "needs a bed";
        player.sendMessage(new TextComponentString(
                TextFormatting.AQUA + "Citizen: " + jobStr + ", " + bedStr + "."));
        this.world.playSound(null, this.getPosition(),
                SoundEvents.ENTITY_VILLAGER_AMBIENT, SoundCategory.NEUTRAL, 0.7F, 1.0F);
    }

    private static String jobLabel(DistrictType job) {
        switch (job.getCanonical()) {
            case POWER:       return "Power";
            case AGRICULTURE: return "Agriculture";
            case RESOURCE:    return "Resource";
            case DEFENSE:     return "Defense";
            case INDUSTRY:    return "Industry";
            default:          return "Civilian";
        }
    }

    // ===== Tick / lifecycle =====

    @Override
    public void onUpdate() {
        super.onUpdate();
        if (!this.world.isRemote) {
            // Drive the retreat flag off the global war event state so the sleep/retreat AI engages.
            boolean shouldRetreat = false;
            try { shouldRetreat = WarEventState.isEventActive(); } catch (Throwable ignored) {}
            if (shouldRetreat != this.retreating) {
                this.retreating = shouldRetreat;
            }
        }
    }

    @Override
    public void onDeath(DamageSource cause) {
        if (!this.world.isRemote && assignedBed != null) {
            try {
                BedClaimData.get(this.world).unclaimBed(this.world, assignedBed, this.getUniqueID());
            } catch (Throwable ignored) {}
        }
        super.onDeath(cause);
    }

    // ===== State accessors (used by AI + district reward counting) =====

    public boolean hasBed() {
        return assignedBed != null;
    }

    @Nullable
    public BlockPos getAssignedBed() {
        return assignedBed;
    }

    public void setAssignedBed(@Nullable BlockPos bed) {
        this.assignedBed = bed;
    }

    public boolean hasWorkSite() {
        return workSite != null;
    }

    @Nullable
    public BlockPos getWorkSite() {
        return workSite;
    }

    public void setWorkSite(@Nullable BlockPos site) {
        this.workSite = site;
    }

    public DistrictType getCurrentJob() {
        return currentJob;
    }

    public void setCurrentJob(DistrictType job) {
        this.currentJob = job != null ? job : DistrictType.NONE;
    }

    public boolean isRetreating() {
        return retreating;
    }

    public void setRetreating(boolean retreating) {
        this.retreating = retreating;
    }

    // ===== ISkinnable (synced via DataParameter) =====

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
        // Split by role at the world's rival level: military citizens get the era SOLDIER look,
        // everyone else the era WORKER look (WWI L6 / WWII L7 / modern L8+).
        int level = 1;
        try { level = studio.ERM.strategic.civil.DistrictRegistry.rivalLevel(world); } catch (Throwable ignored) {}
        boolean military = currentJob == studio.ERM.war.districts.DistrictType.DEFENSE
                || currentJob == studio.ERM.war.districts.DistrictType.MILITARY;
        return studio.ERM.war.skins.SkinPoolManager.eraPoolFor(level, military);
    }

    @Override
    public ResourceLocation getFallbackTexture() {
        return new ResourceLocation("minecraft", "textures/entity/steve.png");
    }

    // ===== NBT =====

    @Override
    public void writeEntityToNBT(NBTTagCompound tag) {
        super.writeEntityToNBT(tag);
        if (assignedBed != null) tag.setTag("erm_bed", writePos(assignedBed));
        if (workSite != null) tag.setTag("erm_work", writePos(workSite));
        tag.setString("erm_job", currentJob != null ? currentJob.name() : DistrictType.NONE.name());
        tag.setBoolean("erm_retreat", retreating);
        tag.setString("ermSkinKey", this.dataManager.get(DW_SKIN_KEY));
    }

    @Override
    public void readEntityFromNBT(NBTTagCompound tag) {
        super.readEntityFromNBT(tag);
        assignedBed = tag.hasKey("erm_bed") ? readPos(tag.getCompoundTag("erm_bed")) : null;
        workSite = tag.hasKey("erm_work") ? readPos(tag.getCompoundTag("erm_work")) : null;
        currentJob = parseJob(tag.getString("erm_job"));
        retreating = tag.getBoolean("erm_retreat");
        this.dataManager.set(DW_SKIN_KEY, tag.getString("ermSkinKey"));
    }

    private static DistrictType parseJob(String name) {
        if (name == null || name.isEmpty()) return DistrictType.NONE;
        try {
            return DistrictType.valueOf(name);
        } catch (IllegalArgumentException ex) {
            return DistrictType.NONE;
        }
    }

    private static NBTTagCompound writePos(BlockPos pos) {
        NBTTagCompound c = new NBTTagCompound();
        c.setInteger("x", pos.getX());
        c.setInteger("y", pos.getY());
        c.setInteger("z", pos.getZ());
        return c;
    }

    @Nullable
    private static BlockPos readPos(NBTTagCompound c) {
        if (c == null || !c.hasKey("x")) return null;
        return new BlockPos(c.getInteger("x"), c.getInteger("y"), c.getInteger("z"));
    }
}
