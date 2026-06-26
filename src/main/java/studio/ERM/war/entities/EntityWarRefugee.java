package studio.ERM.war.entities;

import net.minecraft.entity.EntityAgeable;
import net.minecraft.entity.SharedMonsterAttributes;
import net.minecraft.entity.ai.*;
import net.minecraft.entity.passive.EntityVillager;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.init.Blocks;
import net.minecraft.init.Items;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.network.datasync.DataParameter;
import net.minecraft.network.datasync.DataSerializers;
import net.minecraft.network.datasync.EntityDataManager;
import net.minecraft.util.EnumHand;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;
import net.minecraft.util.text.TextComponentString;
import net.minecraft.util.text.TextFormatting;
import net.minecraft.world.World;
import studio.ERM.EpochRunnerMod;

import java.util.UUID;

/**
 * WAR REFUGEE ENTITY - COMPLETE IMPLEMENTATION
 * 
 * A refugee that spawns near player-set camps and:
 * - Wanders around the camp area
 * - Checks for beds and food
 * - Expresses unhappiness if resources missing
 * - Breaks minor blocks when very unhappy
 * - Can be accepted (becomes AW2 NPC) or rejected (despawns after 5 min)
 * 
 * Behavior Flow:
 * 1. Spawns near camp
 * 2. Wanders and waits
 * 3. If no resources: complains and breaks blocks
 * 4. Player right-clicks: offer decision
 * 5. Accept: convert to AW2 NPC (farmer/soldier/courier)
 * 6. Reject: wait 5 minutes then despawn
 */
public class EntityWarRefugee extends EntityVillager {
    
    // Data parameters for client sync
    private static final DataParameter<Boolean> HAS_RESOURCES = 
        EntityDataManager.createKey(EntityWarRefugee.class, DataSerializers.BOOLEAN);
    private static final DataParameter<Boolean> OFFERED = 
        EntityDataManager.createKey(EntityWarRefugee.class, DataSerializers.BOOLEAN);
    private static final DataParameter<Boolean> REJECTED = 
        EntityDataManager.createKey(EntityWarRefugee.class, DataSerializers.BOOLEAN);
    
    // Camp and owner data
    private BlockPos campPosition;
    private UUID ownerID;
    
    // State tracking
    private boolean hasResources = false;
    private boolean offered = false;
    private boolean rejected = false;
    
    // Timing
    private int ticksSinceSpawn = 0;
    private int ticksSinceLastComplaint = 0;
    private long despawnTime = -1;
    
    // Constants
    private static final int COMPLAINT_INTERVAL = 200; // 10 seconds
    private static final int BLOCK_BREAK_CHANCE = 30; // 3% per complaint when unhappy
    private static final int MAX_WAIT_TIME = 6000; // 5 minutes
    
    public EntityWarRefugee(World worldIn) {
        super(worldIn);
        this.setSize(0.6F, 1.95F);
    }
    
    @Override
    protected void entityInit() {
        super.entityInit();
        this.dataManager.register(HAS_RESOURCES, false);
        this.dataManager.register(OFFERED, false);
        this.dataManager.register(REJECTED, false);
    }
    
    @Override
    protected void initEntityAI() {
        // Clear default villager AI
        this.tasks.taskEntries.clear();
        this.targetTasks.taskEntries.clear();
        
        // Add refugee-specific AI
        this.tasks.addTask(0, new EntityAISwimming(this));
        this.tasks.addTask(1, new EntityAIPanic(this, 1.25D));
        this.tasks.addTask(2, new EntityAIWanderRefugeeCamp(this, 0.8D));
        this.tasks.addTask(3, new EntityAIWatchClosest(this, EntityPlayer.class, 8.0F));
        this.tasks.addTask(4, new EntityAILookIdle(this));
    }
    
    @Override
    protected void applyEntityAttributes() {
        super.applyEntityAttributes();
        this.getEntityAttribute(SharedMonsterAttributes.MAX_HEALTH).setBaseValue(20.0D);
        this.getEntityAttribute(SharedMonsterAttributes.MOVEMENT_SPEED).setBaseValue(0.5D);
    }
    
    // ===== SETTERS & GETTERS =====
    
    public void setCampPosition(BlockPos pos) {
        this.campPosition = pos;
    }
    
    public BlockPos getCampPosition() {
        return this.campPosition;
    }
    
    public void setOwnerID(UUID id) {
        this.ownerID = id;
    }
    
    public UUID getOwnerID() {
        return this.ownerID;
    }
    
    public void setHasResources(boolean has) {
        this.hasResources = has;
        this.dataManager.set(HAS_RESOURCES, has);
    }
    
    public boolean getHasResources() {
        return this.dataManager.get(HAS_RESOURCES);
    }
    
    public void setOffered(boolean offered) {
        this.offered = offered;
        this.dataManager.set(OFFERED, offered);
    }
    
    public boolean isOffered() {
        return this.dataManager.get(OFFERED);
    }
    
    public void setRejected(boolean rejected) {
        this.rejected = rejected;
        this.dataManager.set(REJECTED, rejected);
    }
    
    public boolean isRejected() {
        return this.dataManager.get(REJECTED);
    }
    
    public void setDespawnTime(long time) {
        this.despawnTime = time;
    }
    
    // ===== UPDATE LOGIC =====
    
    @Override
    public void onUpdate() {
        super.onUpdate();
        
        if (world.isRemote) return; // Server only
        
        ticksSinceSpawn++;
        
        // Handle rejection despawn
        if (rejected && despawnTime > 0) {
            if (world.getTotalWorldTime() >= despawnTime) {
                // Time to leave
                this.setDead();
                
                // Notify owner if nearby
                EntityPlayer owner = world.getPlayerEntityByUUID(ownerID);
                if (owner != null && owner.getDistance(this) < 32) {
                    owner.sendMessage(new TextComponentString(TextFormatting.GRAY + 
                        "A rejected refugee has left your camp."));
                }
            }
            return;
        }
        
        // Don't do anything if already offered a decision
        if (offered) return;
        
        // Check unhappiness
        ticksSinceLastComplaint++;
        if (!hasResources && ticksSinceLastComplaint >= COMPLAINT_INTERVAL) {
            expressUnhappiness(world);
            ticksSinceLastComplaint = 0;
            
            // Random chance to break a block
            if (rand.nextInt(100) < BLOCK_BREAK_CHANCE) {
                breakNearbyBlock(world);
            }
        }
        
        // Auto-despawn if owner doesn't interact for too long
        if (ticksSinceSpawn > MAX_WAIT_TIME * 2) { // 10 minutes
            this.setDead();
            EpochRunnerMod.logger.info("Refugee auto-despawned after timeout");
        }
    }
    
    // ===== UNHAPPINESS BEHAVIOR =====
    
    /**
     * Express unhappiness - say something and show particles
     */
    public void expressUnhappiness(World world) {
        if (world.isRemote) return;
        
        // Pick a random complaint
        String[] complaints = {
            "I'm hungry...",
            "Is there any food here?",
            "I need a place to sleep...",
            "Where are the beds?",
            "This camp has no supplies!",
            "How can we survive here?",
            "I see no food chests...",
            "Do you have any bread?"
        };
        
        String complaint = complaints[rand.nextInt(complaints.length)];
        
        // Show message to nearby players
        for (EntityPlayer player : world.getEntitiesWithinAABB(EntityPlayer.class, 
            getEntityBoundingBox().grow(16, 4, 16))) {
            player.sendMessage(new TextComponentString(TextFormatting.GRAY + 
                "[Refugee] " + TextFormatting.WHITE + complaint));
        }
        
        // Angry particles
        world.setEntityState(this, (byte)13); // Heart break particles
    }
    
    /**
     * Break a nearby block (minor griefing when unhappy)
     */
    public void breakNearbyBlock(World world) {
        if (world.isRemote) return;
        
        // Find a breakable block nearby
        for (int attempt = 0; attempt < 10; attempt++) {
            int offsetX = rand.nextInt(6) - 3;
            int offsetY = rand.nextInt(3);
            int offsetZ = rand.nextInt(6) - 3;
            
            BlockPos pos = getPosition().add(offsetX, offsetY, offsetZ);
            net.minecraft.block.Block block = world.getBlockState(pos).getBlock();
            
            // Only break minor blocks (crops, flowers, etc.)
            if (isMinorBlock(block)) {
                world.destroyBlock(pos, true); // Drop items
                
                // Show message
                for (EntityPlayer player : world.getEntitiesWithinAABB(EntityPlayer.class, 
                    getEntityBoundingBox().grow(16, 4, 16))) {
                    player.sendMessage(new TextComponentString(TextFormatting.RED + 
                        "[Refugee] I'm so frustrated! *breaks something*"));
                }
                
                break;
            }
        }
    }
    
    /**
     * Check if block is minor (safe to break)
     */
    private boolean isMinorBlock(net.minecraft.block.Block block) {
        return block == Blocks.TALLGRASS ||
               block == Blocks.YELLOW_FLOWER ||
               block == Blocks.RED_FLOWER ||
               block == Blocks.WHEAT ||
               block == Blocks.CARROTS ||
               block == Blocks.POTATOES ||
               block == Blocks.DEADBUSH ||
               block == Blocks.SAPLING ||
               block == Blocks.BROWN_MUSHROOM ||
               block == Blocks.RED_MUSHROOM;
    }
    
    // ===== PLAYER INTERACTION =====
    
    @Override
    public boolean processInteract(EntityPlayer player, EnumHand hand) {
        if (world.isRemote) return true; // Client just opens GUI
        
        // Only owner can interact
        if (!player.getPersistentID().equals(ownerID)) {
            player.sendMessage(new TextComponentString(TextFormatting.RED + 
                "This is not your refugee."));
            return true;
        }
        
        // Already made an offer
        if (offered) {
            player.sendMessage(new TextComponentString(TextFormatting.YELLOW + 
                "You've already made a decision about this refugee."));
            return true;
        }
        
        // Don't interrupt if the refugee manager will handle this
        // (It shows the decision GUI)
        return false; // Let the event system handle it
    }
    
    // ===== AI CLASSES =====
    
    /**
     * Custom AI to wander near camp
     */
    public static class EntityAIWanderRefugeeCamp extends EntityAIWander {
        private final EntityWarRefugee refugee;

        public EntityAIWanderRefugeeCamp(EntityWarRefugee refugee, double speedIn) {
            super(refugee, speedIn);
            this.refugee = refugee;
        }

        @Override
        public boolean shouldExecute() {
            // Don't wander if rejected (just stand and wait)
            if (refugee.isRejected()) {
                return false;
            }

            return super.shouldExecute();
        }
    }
    
    // ===== NBT =====
    
    @Override
    public void writeEntityToNBT(NBTTagCompound compound) {
        super.writeEntityToNBT(compound);
        
        if (campPosition != null) {
            compound.setInteger("campX", campPosition.getX());
            compound.setInteger("campY", campPosition.getY());
            compound.setInteger("campZ", campPosition.getZ());
        }
        
        if (ownerID != null) {
            compound.setString("ownerID", ownerID.toString());
        }
        
        compound.setBoolean("hasResources", hasResources);
        compound.setBoolean("offered", offered);
        compound.setBoolean("rejected", rejected);
        compound.setInteger("ticksSinceSpawn", ticksSinceSpawn);
        compound.setLong("despawnTime", despawnTime);
    }
    
    @Override
    public void readEntityFromNBT(NBTTagCompound compound) {
        super.readEntityFromNBT(compound);
        
        if (compound.hasKey("campX")) {
            campPosition = new BlockPos(
                compound.getInteger("campX"),
                compound.getInteger("campY"),
                compound.getInteger("campZ")
            );
        }
        
        if (compound.hasKey("ownerID")) {
            ownerID = UUID.fromString(compound.getString("ownerID"));
        }
        
        hasResources = compound.getBoolean("hasResources");
        setHasResources(hasResources);
        
        offered = compound.getBoolean("offered");
        setOffered(offered);
        
        rejected = compound.getBoolean("rejected");
        setRejected(rejected);
        
        ticksSinceSpawn = compound.getInteger("ticksSinceSpawn");
        despawnTime = compound.getLong("despawnTime");
    }
    
    // ===== BREEDING (Disabled) =====
    
    @Override
    public EntityVillager createChild(EntityAgeable ageable) {
        return null; // Refugees don't breed
    }
    
    @Override
    protected boolean canDespawn() {
        return false; // Controlled despawn only
    }
    
    // ===== CUSTOM NAME =====
    
    @Override
    public String getName() {
        if (this.hasCustomName()) {
            return this.getCustomNameTag();
        }
        return "Refugee";
    }
}
