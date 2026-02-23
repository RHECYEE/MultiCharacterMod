package studio.ERM.war.entities;

import net.minecraft.block.BlockBed;
import net.minecraft.block.state.IBlockState;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityCreature;
import net.minecraft.entity.SharedMonsterAttributes;
import net.minecraft.entity.ai.EntityAIHurtByTarget;
import net.minecraft.entity.ai.EntityAILookIdle;
import net.minecraft.entity.ai.EntityAISwimming;
import net.minecraft.entity.ai.EntityAIWanderAvoidWater;
import net.minecraft.entity.ai.EntityAIWatchClosest;
import net.minecraft.entity.monster.EntityMob;
import net.minecraft.entity.monster.IMob;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.init.SoundEvents;
import net.minecraft.inventory.EntityEquipmentSlot;
import net.minecraft.item.Item;
import net.minecraft.item.ItemArmor;
import net.minecraft.item.ItemFood;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.pathfinding.PathNodeType;
import net.minecraft.util.DamageSource;
import net.minecraft.util.EnumFacing;
import net.minecraft.util.EnumHand;
import net.minecraft.util.SoundCategory;
import net.minecraft.util.math.AxisAlignedBB;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.text.TextComponentString;
import net.minecraft.world.World;
import net.minecraftforge.common.util.Constants;
import net.minecraftforge.items.CapabilityItemHandler;
import net.minecraftforge.items.ItemStackHandler;

import studio.ERM.war.WarEventState;
import studio.ERM.war.entities.ai.EntityAIBanquetHallVisit;
import studio.ERM.war.entities.ai.EntityAIHarassForNeeds;
import studio.ERM.war.entities.ai.EntityAIItemDuctTransfer;
import studio.ERM.war.entities.ai.EntityAIPatrol;
import studio.ERM.war.entities.ai.EntityAIRetreatOnWar;
import studio.ERM.war.entities.ai.EntityAISleepAndBedClaim;
import studio.ERM.war.world.BedClaimData;

import javax.annotation.Nullable;
import java.util.UUID;

/**
 * Modular base citizen NPC:
 * - Full internal inventory (36 slots) via ItemStackHandler capability
 * - Armor + mainhand equip by right-click with item (swap)
 * - Claims beds via WorldSavedData registry (clears on death)
 * - Harasses (follows) player when needs bed/food and speaks on interact
 * - Visits Banquet Hall once/day in job window to pull food
 * - Retreat behavior on war state (go to bed/indoors + flee from hostiles)
 * - Optional 2-point patrol (via patrol stick)
 *
 * NOTE: This class must exist at: src/main/java/studio/ERM/war/entities/EntityModularCitizen.java
 */
public class EntityModularCitizen extends EntityCreature {

    // NBT keys
    public static final String NBT_JOB_ID = "erm_job_id";
    public static final String NBT_JOB_VISIT_TICK = "erm_job_visitTick";
    public static final String NBT_LAST_VISIT_DAY = "erm_job_lastVisitDay";

    public static final String NBT_BED_POS = "erm_bed_pos";
    public static final String NBT_BANQUET_POS = "erm_banquet_pos";

    public static final String NBT_PATROL_A = "erm_patrol_a";
    public static final String NBT_PATROL_B = "erm_patrol_b";
    public static final String NBT_HAS_PATROL = "erm_patrol_enabled";
    public static final String NBT_PATROL_TO_A = "erm_patrol_to_a";

    public static final String NBT_ITEMDUCT_SRC = "erm_itemduct_src";
    public static final String NBT_ITEMDUCT_DST = "erm_itemduct_dst";
    public static final String NBT_ITEMDUCT_SRC_FACE = "erm_itemduct_src_face";
    public static final String NBT_ITEMDUCT_DST_FACE = "erm_itemduct_dst_face";

    public static final String NBT_MCM_TEAM = "erm_mcm_team";

    // Tuning
    private static final int BASE_INV_SLOTS = 36;
    private static final int FOOD_MIN_STACKS_TARGET = 4;
    private static final int BANQUET_PULL_LIMIT = 8;
    private static final int BED_SEARCH_RADIUS = 24;
    private static final int BANQUET_SEARCH_RADIUS = 48;

    private final ItemStackHandler internalInv = new ItemStackHandler(BASE_INV_SLOTS) {
        @Override
        protected void onContentsChanged(int slot) {
            // Just mark for save; Forge will call writeEntityToNBT on chunk save.
            markDirtyData();
        }
    };

    private boolean dirtyData = false;

    // Skin key/path (resolved client-side by your renderer / SkinHandler)
    private String texturePathNoExt = "";

    private boolean retreating = false;

    public EntityModularCitizen(World worldIn) {
        super(worldIn);
        this.setSize(0.6F, 1.8F);
        this.setPathPriority(PathNodeType.WATER, -1.0F);
        this.enablePersistence();
    }

    @Override
    protected void initEntityAI() {
        this.tasks.addTask(0, new EntityAISwimming(this));

        // War retreat has high priority and will override movement when active
        this.tasks.addTask(1, new EntityAIRetreatOnWar(this));

        // Bed claim + sleep routine
        this.tasks.addTask(2, new EntityAISleepAndBedClaim(this, BED_SEARCH_RADIUS));

        // Follow player if needs bed/food (talk on interact)
        this.tasks.addTask(3, new EntityAIHarassForNeeds(this));

        // Human item duct job
        this.tasks.addTask(4, new EntityAIItemDuctTransfer(this));

        // Daily food pickup
        this.tasks.addTask(5, new EntityAIBanquetHallVisit(this, BANQUET_SEARCH_RADIUS, BANQUET_PULL_LIMIT));

        // Optional patrol
        this.tasks.addTask(6, new EntityAIPatrol(this));

        // Ambient
        this.tasks.addTask(7, new EntityAIWanderAvoidWater(this, 0.6D));
        this.tasks.addTask(8, new EntityAIWatchClosest(this, EntityPlayer.class, 8.0F));
        this.tasks.addTask(9, new EntityAILookIdle(this));

        this.targetTasks.addTask(1, new EntityAIHurtByTarget(this, true));
    }

    @Override
    protected void applyEntityAttributes() {
        super.applyEntityAttributes();
        this.getEntityAttribute(SharedMonsterAttributes.MAX_HEALTH).setBaseValue(20.0D);
        this.getEntityAttribute(SharedMonsterAttributes.MOVEMENT_SPEED).setBaseValue(0.28D);
        this.getEntityAttribute(SharedMonsterAttributes.FOLLOW_RANGE).setBaseValue(24.0D);
    }

    // --------------------
    // Interaction / equip swapping
    // --------------------

    @Override
    public boolean processInteract(EntityPlayer player, EnumHand hand) {
        if (player == null) return false;

        ItemStack held = player.getHeldItem(hand);
        if (!this.world.isRemote) {

            if (needsBed()) {
                player.sendMessage(new TextComponentString("I need a bed."));
                this.world.playSound(null, this.getPosition(), SoundEvents.ENTITY_VILLAGER_NO, SoundCategory.NEUTRAL, 0.8F, 1.0F);
                return true;
            }

            if (needsFood()) {
                player.sendMessage(new TextComponentString("I need food."));
                this.world.playSound(null, this.getPosition(), SoundEvents.ENTITY_VILLAGER_NO, SoundCategory.NEUTRAL, 0.8F, 1.0F);
                return true;
            }

            if (!held.isEmpty()) {
                return tryEquipOrSwap(player, hand, held);
            }
        }

        return super.processInteract(player, hand);
    }

    private boolean tryEquipOrSwap(EntityPlayer player, EnumHand hand, ItemStack held) {
        Item item = held.getItem();
        EntityEquipmentSlot targetSlot = EntityEquipmentSlot.MAINHAND;

        if (item instanceof ItemArmor) {
            targetSlot = ((ItemArmor) item).armorType;
        }

        ItemStack current = this.getItemStackFromSlot(targetSlot);

        // Equip 1 item into that slot
        ItemStack toEquip = held.copy();
        toEquip.setCount(1);

        if (current.isEmpty()) {
            this.setItemStackToSlot(targetSlot, toEquip);
            held.shrink(1);
            player.setHeldItem(hand, held);
            markDirtyData();
            return true;
        }

        // Swap: must be able to return current to player or drop it
        boolean added = player.inventory.addItemStackToInventory(current.copy());
        if (!added) {
            // Drop at player's feet as fallback
            player.dropItem(current.copy(), false);
        }

        this.setItemStackToSlot(targetSlot, toEquip);
        held.shrink(1);
        player.setHeldItem(hand, held);

        markDirtyData();
        return true;
    }

    // --------------------
    // Needs
    // --------------------

    public boolean needsBed() {
        return getClaimedBedPos() == null;
    }

    public boolean needsFood() {
        int foodStacks = 0;
        for (int i = 0; i < internalInv.getSlots(); i++) {
            ItemStack s = internalInv.getStackInSlot(i);
            if (!s.isEmpty() && s.getItem() instanceof ItemFood) {
                foodStacks++;
            }
        }
        return foodStacks < FOOD_MIN_STACKS_TARGET;
    }

    // --------------------
    // Bed claim
    // --------------------

    @Nullable
    public BlockPos getClaimedBedPos() {
        return readPosFromNBT(NBT_BED_POS);
    }

    public void setClaimedBedPos(@Nullable BlockPos pos) {
        writePosToNBT(NBT_BED_POS, pos);
    }

    public boolean tryClaimBed(BlockPos bedPos) {
        if (this.world.isRemote) return false;
        if (bedPos == null) return false;
        UUID id = this.getUniqueID();
        BedClaimData data = BedClaimData.get(this.world);
        boolean ok = data.claim(bedPos, id);
        if (ok) {
            setClaimedBedPos(bedPos);
        }
        return ok;
    }

    public void clearBedClaim() {
        if (this.world.isRemote) return;
        BlockPos bed = getClaimedBedPos();
        if (bed != null) {
            BedClaimData.get(this.world).unclaim(bed, this.getUniqueID());
        }
        setClaimedBedPos(null);
    }

    // --------------------
    // Banquet hall assignment
    // --------------------

    @Nullable
    public BlockPos getBanquetHallPos() {
        return readPosFromNBT(NBT_BANQUET_POS);
    }

    public void setBanquetHallPos(@Nullable BlockPos pos) {
        writePosToNBT(NBT_BANQUET_POS, pos);
    }

    // --------------------
    // Job schedule
    // --------------------

    public String getJobId() {
        String v = this.getEntityData().getString(NBT_JOB_ID);
        return v == null || v.trim().isEmpty() ? "citizen" : v;
    }

    public void setJobId(String jobId) {
        this.getEntityData().setString(NBT_JOB_ID, jobId == null ? "citizen" : jobId);
        this.getEntityData().removeTag(NBT_JOB_VISIT_TICK);
        this.getEntityData().removeTag(NBT_LAST_VISIT_DAY);
        markDirtyData();
    }

    public int getJobVisitTickOrInit(int windowStartTick, int windowEndTick) {
        if (this.getEntityData().hasKey(NBT_JOB_VISIT_TICK, Constants.NBT.TAG_INT)) {
            return this.getEntityData().getInteger(NBT_JOB_VISIT_TICK);
        }
        int start = Math.max(0, windowStartTick);
        int end = Math.min(23999, windowEndTick);
        int range = Math.max(1, end - start);
        int pick = start + this.rand.nextInt(range);
        this.getEntityData().setInteger(NBT_JOB_VISIT_TICK, pick);
        markDirtyData();
        return pick;
    }

    public long getLastVisitDay() {
        if (!this.getEntityData().hasKey(NBT_LAST_VISIT_DAY, Constants.NBT.TAG_LONG)) return -1L;
        return this.getEntityData().getLong(NBT_LAST_VISIT_DAY);
    }

    public void setLastVisitDay(long day) {
        this.getEntityData().setLong(NBT_LAST_VISIT_DAY, day);
        markDirtyData();
    }

    // --------------------
    // Patrol compatibility API
    // --------------------

    public boolean isPatrolEnabled() {
        return this.getEntityData().getBoolean(NBT_HAS_PATROL);
    }

    public void setPatrolEnabled(boolean enabled) {
        this.getEntityData().setBoolean(NBT_HAS_PATROL, enabled);
        markDirtyData();
    }

    public boolean isPatrolling() {
        return isPatrolEnabled();
    }

    @Nullable
    public BlockPos getPatrolA() {
        return readPosFromNBT(NBT_PATROL_A);
    }

    @Nullable
    public BlockPos getPatrolB() {
        return readPosFromNBT(NBT_PATROL_B);
    }

    public void setPatrolA(@Nullable BlockPos pos) {
        writePosToNBT(NBT_PATROL_A, pos);
    }

    public void setPatrolB(@Nullable BlockPos pos) {
        writePosToNBT(NBT_PATROL_B, pos);
    }

    @Nullable
    public BlockPos getPatrolPoint1() {
        return getPatrolA();
    }

    @Nullable
    public BlockPos getPatrolPoint2() {
        return getPatrolB();
    }

    public boolean isMovingToPoint1() {
        return this.getEntityData().getBoolean(NBT_PATROL_TO_A);
    }

    public void setMovingToPoint1(boolean val) {
        this.getEntityData().setBoolean(NBT_PATROL_TO_A, val);
        markDirtyData();
    }

    public void setPatrolRoute(BlockPos a, BlockPos b) {
        setPatrolA(a);
        setPatrolB(b);
    }

    public void startPatrolling() {
        setPatrolEnabled(true);
        setMovingToPoint1(true);
    }

    public void stopPatrolling() {
        setPatrolEnabled(false);
    }

    // Legacy hook used by older patrol stick; no longer supported here
    @Nullable
    public Entity findNearbyFlansVehicle() {
        return null;
    }

    // --------------------
    // Item duct endpoints
    // --------------------

    @Nullable
    public BlockPos getItemDuctSource() {
        return readPosFromNBT(NBT_ITEMDUCT_SRC);
    }

    @Nullable
    public BlockPos getItemDuctDest() {
        return readPosFromNBT(NBT_ITEMDUCT_DST);
    }

    public void setItemDuctSource(@Nullable BlockPos pos) {
        writePosToNBT(NBT_ITEMDUCT_SRC, pos);
    }

    public void setItemDuctDest(@Nullable BlockPos pos) {
        writePosToNBT(NBT_ITEMDUCT_DST, pos);
    }

    public EnumFacing getItemDuctSourceFace() {
        int idx = this.getEntityData().getInteger(NBT_ITEMDUCT_SRC_FACE);
        return idx >= 0 && idx < EnumFacing.values().length ? EnumFacing.values()[idx] : EnumFacing.UP;
    }

    public EnumFacing getItemDuctDestFace() {
        int idx = this.getEntityData().getInteger(NBT_ITEMDUCT_DST_FACE);
        return idx >= 0 && idx < EnumFacing.values().length ? EnumFacing.values()[idx] : EnumFacing.UP;
    }

    public void setItemDuctSourceFace(EnumFacing face) {
        this.getEntityData().setInteger(NBT_ITEMDUCT_SRC_FACE, face.getIndex());
        markDirtyData();
    }

    public void setItemDuctDestFace(EnumFacing face) {
        this.getEntityData().setInteger(NBT_ITEMDUCT_DST_FACE, face.getIndex());
        markDirtyData();
    }

    // --------------------
    // Retreat state
    // --------------------

    public boolean isRetreating() {
        return retreating;
    }

    public void setRetreating(boolean retreating) {
        this.retreating = retreating;
    }

    // --------------------
    // MCM team compatibility
    // --------------------

    public int getMcmTeam() {
        return this.getEntityData().getInteger(NBT_MCM_TEAM);
    }

    public void setMcmTeam(int team) {
        this.getEntityData().setInteger(NBT_MCM_TEAM, team);
        markDirtyData();
    }

    // --------------------
    // Inventory / capabilities
    // --------------------

    public ItemStackHandler getInternalInv() {
        return internalInv;
    }

    @Override
    public boolean hasCapability(net.minecraftforge.common.capabilities.Capability<?> capability, @Nullable EnumFacing facing) {
        if (capability == CapabilityItemHandler.ITEM_HANDLER_CAPABILITY) return true;
        return super.hasCapability(capability, facing);
    }

    @Override
    @SuppressWarnings("unchecked")
    public <T> T getCapability(net.minecraftforge.common.capabilities.Capability<T> capability, @Nullable EnumFacing facing) {
        if (capability == CapabilityItemHandler.ITEM_HANDLER_CAPABILITY) {
            return (T) internalInv;
        }
        return super.getCapability(capability, facing);
    }

    // --------------------
    // Tick / persistence
    // --------------------

    @Override
    public void onUpdate() {
        super.onUpdate();

        if (!this.world.isRemote) {
            // Sync retreating from WarEventState
            boolean shouldRetreat = WarEventState.isEventActive();
            if (shouldRetreat != this.retreating) {
                this.retreating = shouldRetreat;
                markDirtyData();
            }

            // If bed was broken, clear claim
            BlockPos bed = getClaimedBedPos();
            if (bed != null) {
                IBlockState state = this.world.getBlockState(bed);
                if (!(state.getBlock() instanceof BlockBed)) {
                    clearBedClaim();
                }
            }

            if (dirtyData) {
                dirtyData = false;
            }
        }
    }

    @Override
    public void onDeath(DamageSource cause) {
        if (!this.world.isRemote) {
            clearBedClaim();
        }
        super.onDeath(cause);
    }

    @Override
    public void writeEntityToNBT(NBTTagCompound compound) {
        super.writeEntityToNBT(compound);
        compound.setTag("erm_internalInv", internalInv.serializeNBT());
        compound.setString("erm_texturePathNoExt", texturePathNoExt == null ? "" : texturePathNoExt);
        compound.setBoolean("erm_retreating", retreating);
    }

    @Override
    public void readEntityFromNBT(NBTTagCompound compound) {
        super.readEntityFromNBT(compound);
        if (compound.hasKey("erm_internalInv", Constants.NBT.TAG_COMPOUND)) {
            internalInv.deserializeNBT(compound.getCompoundTag("erm_internalInv"));
        }
        texturePathNoExt = compound.getString("erm_texturePathNoExt");
        retreating = compound.getBoolean("erm_retreating");
    }

    private void markDirtyData() {
        this.dirtyData = true;
    }

    // --------------------
    // Skin helpers
    // --------------------

    public void setTexturePathNoExt(String domainAndPathNoExt) {
        this.texturePathNoExt = domainAndPathNoExt == null ? "" : domainAndPathNoExt;
        markDirtyData();
    }

    public String getTexturePathNoExt() {
        return texturePathNoExt;
    }

    // --------------------
    // NBT pos helpers
    // --------------------

    @Nullable
    private BlockPos readPosFromNBT(String key) {
        if (!this.getEntityData().hasKey(key, Constants.NBT.TAG_COMPOUND)) return null;
        NBTTagCompound c = this.getEntityData().getCompoundTag(key);
        if (!c.hasKey("x", Constants.NBT.TAG_INT)) return null;
        return new BlockPos(c.getInteger("x"), c.getInteger("y"), c.getInteger("z"));
    }

    private void writePosToNBT(String key, @Nullable BlockPos pos) {
        if (pos == null) {
            this.getEntityData().removeTag(key);
        } else {
            NBTTagCompound c = new NBTTagCompound();
            c.setInteger("x", pos.getX());
            c.setInteger("y", pos.getY());
            c.setInteger("z", pos.getZ());
            this.getEntityData().setTag(key, c);
        }
        markDirtyData();
    }
}
