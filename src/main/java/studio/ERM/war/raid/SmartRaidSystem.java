package studio.ERM.war.raid;

import co.runed.multicharacter.vehicle.EntityAIPilot;
import net.minecraft.block.Block;
import net.minecraft.block.state.IBlockState;
import net.minecraft.entity.EntityLiving;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.init.Blocks;
import net.minecraft.init.Items;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.tileentity.TileEntityChest;
import net.minecraft.util.EnumFacing;
import net.minecraft.util.EnumHand;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.text.TextComponentString;
import net.minecraft.util.text.TextFormatting;
import net.minecraft.world.World;
import net.minecraftforge.items.CapabilityItemHandler;
import net.minecraftforge.items.IItemHandler;
import studio.ERM.EpochRunnerMod;
import studio.ERM.war.WarStateAuthority;
import studio.ERM.war.faction.RivalFactionStats;

import java.util.*;

/**
 * SMART RAID SYSTEM
 * 
 * Every raid has:
 * - Primary Objective: Steal highest EMC/value asset
 * - Operational Goals: Tier-specific sabotage
 * 
 * Tier Doctrine:
 * 1 (10-1k EMC): Bandits - steal food, smash doors, light arson
 * 2 (1k-50k EMC): Raiders - steal valuables, wreck storage
 * 3 (50k-500k EMC): Assault - breach walls, destroy power first
 * 4 (500k-5M EMC): Commando - disable comms/AD, plant charges
 * 5 (5M+ EMC): State Actor - multi-phase, missile strike, full assault
 * 
 * Raid Phases:
 * 1. Recon/Approach
 * 2. Breach
 * 3. Clear + Loot
 * 4. Exfil + Rearguard
 */
public class SmartRaidSystem {
    
    private static final Random rand = new Random();
    
    // ===== RAID TIERS =====
    public enum RaidTier {
        BANDITS(1, 0, 1000, "§7Bandits", 200, 500),
        RAIDERS(2, 1000, 50000, "§eRaiders", 1000, 3000),
        ASSAULT(3, 50000, 500000, "§6Assault", 5000, 15000),
        COMMANDO(4, 500000, 5000000, "§cCommando", 20000, 50000),
        STATE_ACTOR(5, 5000000, Integer.MAX_VALUE, "§4State Actor", 0, Integer.MAX_VALUE);
        
        public final int tier;
        public final int minEMC;
        public final int maxEMC;
        public final String displayName;
        public final int minDestructionBudget;
        public final int maxDestructionBudget;
        
        RaidTier(int tier, int minEMC, int maxEMC, String name, int minDest, int maxDest) {
            this.tier = tier;
            this.minEMC = minEMC;
            this.maxEMC = maxEMC;
            this.displayName = name;
            this.minDestructionBudget = minDest;
            this.maxDestructionBudget = maxDest;
        }
        
        public static RaidTier fromEMC(int emc) {
            for (RaidTier tier : values()) {
                if (emc >= tier.minEMC && emc < tier.maxEMC) {
                    return tier;
                }
            }
            return STATE_ACTOR;
        }
    }
    
    // ===== RAID PHASES =====
    public enum RaidPhase {
        APPROACH("Approaching", 30, 90),       // 1.5-4.5 seconds
        BREACH("Breaching", 20, 40),           // 1-2 seconds
        CLEAR_LOOT("Engaging", 120, 300),      // 6-15 seconds
        EXFIL("Extracting", 40, 80);           // 2-4 seconds
        
        public final String displayName;
        public final int minTicks;
        public final int maxTicks;
        
        RaidPhase(String name, int minTicks, int maxTicks) {
            this.displayName = name;
            this.minTicks = minTicks;
            this.maxTicks = maxTicks;
        }
        
        public int getDuration() {
            return minTicks + rand.nextInt(maxTicks - minTicks + 1);
        }
    }
    
    // ===== OPERATIONAL GOALS =====
    public enum OperationalGoal {
        STEAL_VALUABLES,      // Always present
        DESTROY_DOORS,        // Tier 1+
        LIGHT_ARSON,          // Tier 1
        KILL_LIVESTOCK,       // Tier 2
        WRECK_STORAGE,        // Tier 2+
        BREAK_FARMS,          // Tier 2
        DESTROY_POWER,        // Tier 3+
        BREACH_WALLS,         // Tier 3+
        DISABLE_COMMS,        // Tier 4+
        DISABLE_AD,           // Tier 4+
        PLANT_CHARGES,        // Tier 4+
        DECOY_STRIKE,         // Tier 5
        MISSILE_STRIKE,       // Tier 5
        LEAVE_NOTHING         // Tier 5
    }
    
    // ===== ACTIVE RAID STATE =====
    public static class ActiveRaid {
        public final String id;
        public final RaidTier tier;
        public final BlockPos targetCenter;
        public final int targetEMC;
        
        // Objectives
        public BlockPos primaryObjective;      // Highest value target
        public BlockPos secondaryObjective;    // Infrastructure target
        public BlockPos tertiaryObjective;     // Recovery prevention
        public final List<OperationalGoal> goals = new ArrayList<>();
        
        // Units
        public final List<EntityLiving> raiders = new ArrayList<>();
        public final List<EntityLiving> lootCarriers = new ArrayList<>();
        public int totalSpawned = 0;
        public int totalKilled = 0;
        
        // Phase tracking
        public RaidPhase phase = RaidPhase.APPROACH;
        public long phaseStartTick;
        public long phaseDurationTicks;
        
        // Destruction budget
        public int destructionBudget;
        public int blocksDestroyed = 0;
        
        // Loot tracking
        public final List<ItemStack> stolenLoot = new ArrayList<>();
        public int lootValue = 0;
        
        // Player activity tracking
        public BlockPos newestBuildSite;
        public long startTick;
        
        public ActiveRaid(String id, RaidTier tier, BlockPos center, int emc) {
            this.id = id;
            this.tier = tier;
            this.targetCenter = center;
            this.targetEMC = emc;
            this.destructionBudget = tier.minDestructionBudget + 
                rand.nextInt(Math.max(1, tier.maxDestructionBudget - tier.minDestructionBudget));
        }
        
        /**
         * Count alive raiders
         */
        public int getAliveCount() {
            int alive = 0;
            for (EntityLiving raider : raiders) {
                if (!raider.isDead) alive++;
            }
            for (EntityLiving carrier : lootCarriers) {
                if (!carrier.isDead) alive++;
            }
            return alive;
        }
    }
    
    // ===== ACTIVE RAIDS =====
    private static final Map<String, ActiveRaid> activeRaids = new HashMap<>();
    
    // ===== HIGH VALUE BLOCKS (for targeting) =====
    private static final Set<Block> HIGH_VALUE_BLOCKS = new HashSet<>();
    private static final Set<Block> POWER_BLOCKS = new HashSet<>();
    private static final Set<Block> DOOR_BLOCKS = new HashSet<>();
    private static final Set<Block> STORAGE_BLOCKS = new HashSet<>();
    
    static {
        // High value containers
        HIGH_VALUE_BLOCKS.add(Blocks.CHEST);
        HIGH_VALUE_BLOCKS.add(Blocks.TRAPPED_CHEST);
        HIGH_VALUE_BLOCKS.add(Blocks.ENDER_CHEST);
        HIGH_VALUE_BLOCKS.add(Blocks.DIAMOND_BLOCK);
        HIGH_VALUE_BLOCKS.add(Blocks.EMERALD_BLOCK);
        HIGH_VALUE_BLOCKS.add(Blocks.GOLD_BLOCK);
        HIGH_VALUE_BLOCKS.add(Blocks.IRON_BLOCK);
        HIGH_VALUE_BLOCKS.add(Blocks.BEACON);
        
        // Power blocks
        POWER_BLOCKS.add(Blocks.REDSTONE_BLOCK);
        POWER_BLOCKS.add(Blocks.DAYLIGHT_DETECTOR);
        POWER_BLOCKS.add(Blocks.REDSTONE_LAMP);
        POWER_BLOCKS.add(Blocks.OBSERVER);
        
        // Doors
        DOOR_BLOCKS.add(Blocks.OAK_DOOR);
        DOOR_BLOCKS.add(Blocks.IRON_DOOR);
        DOOR_BLOCKS.add(Blocks.SPRUCE_DOOR);
        DOOR_BLOCKS.add(Blocks.BIRCH_DOOR);
        DOOR_BLOCKS.add(Blocks.JUNGLE_DOOR);
        DOOR_BLOCKS.add(Blocks.ACACIA_DOOR);
        DOOR_BLOCKS.add(Blocks.DARK_OAK_DOOR);
        DOOR_BLOCKS.add(Blocks.OAK_FENCE_GATE);
        
        // Storage
        STORAGE_BLOCKS.add(Blocks.CHEST);
        STORAGE_BLOCKS.add(Blocks.TRAPPED_CHEST);
        STORAGE_BLOCKS.add(Blocks.FURNACE);
        STORAGE_BLOCKS.add(Blocks.LIT_FURNACE);
        STORAGE_BLOCKS.add(Blocks.HOPPER);
        STORAGE_BLOCKS.add(Blocks.DROPPER);
        STORAGE_BLOCKS.add(Blocks.DISPENSER);
    }
    
    // ===== RAID INITIATION =====
    
    /**
     * Start a smart raid
     * @param world The world
     * @param player Target player
     * @param playerEMC Estimated player wealth (EMC value)
     * @param factionStats Attacking faction stats
     * @return The active raid, or null if failed
     */
    public static ActiveRaid startRaid(World world, EntityPlayer player, int playerEMC, 
                                       RivalFactionStats factionStats) {
        // Check with war authority
        WarStateAuthority authority = WarStateAuthority.get();
        RaidTier tier = RaidTier.fromEMC(playerEMC);
        
        // Faction level affects tier
        int effectiveTier = Math.min(tier.tier, (factionStats.getLevel() + 1) / 2);
        tier = RaidTier.values()[Math.max(0, effectiveTier - 1)];
        
        if (!authority.startRaid(world, player.getPosition(), tier.tier)) {
            return null;
        }
        
        String raidId = "raid_" + System.currentTimeMillis();
        ActiveRaid raid = new ActiveRaid(raidId, tier, player.getPosition(), playerEMC);
        raid.startTick = world.getTotalWorldTime();
        
        // Scan for objectives
        scanObjectives(world, raid, player);
        
        // Assign operational goals based on tier
        assignGoals(raid);
        
        // Start first phase
        startPhase(raid, RaidPhase.APPROACH);
        
        // Spawn raiders
        spawnRaiders(world, raid, player, factionStats);
        
        activeRaids.put(raidId, raid);
        
        // Announce to player
        announceRaid(player, raid);
        
        EpochRunnerMod.logger.info("[RAID] Started " + tier.displayName + " raid on " + 
            player.getName() + " - EMC: " + playerEMC);
        
        return raid;
    }
    
    /**
     * Scan for raid objectives
     */
    private static void scanObjectives(World world, ActiveRaid raid, EntityPlayer player) {
        int scanRadius = 50 + (raid.tier.tier * 10);
        BlockPos center = player.getPosition();
        
        BlockPos highestValue = null;
        int highestValueScore = 0;
        
        BlockPos powerTarget = null;
        BlockPos storageTarget = null;
        
        // Scan area for targets
        for (int x = -scanRadius; x <= scanRadius; x++) {
            for (int y = -20; y <= 20; y++) {
                for (int z = -scanRadius; z <= scanRadius; z++) {
                    BlockPos pos = center.add(x, y, z);
                    if (!world.isBlockLoaded(pos)) continue;
                    
                    IBlockState state = world.getBlockState(pos);
                    Block block = state.getBlock();
                    
                    // Check for high value
                    if (HIGH_VALUE_BLOCKS.contains(block)) {
                        int value = evaluateTarget(world, pos);
                        if (value > highestValueScore) {
                            highestValueScore = value;
                            highestValue = pos;
                        }
                    }
                    
                    // Check for power
                    if (POWER_BLOCKS.contains(block) && powerTarget == null) {
                        powerTarget = pos;
                    }
                    
                    // Check for storage
                    if (STORAGE_BLOCKS.contains(block) && storageTarget == null) {
                        storageTarget = pos;
                    }
                }
            }
        }
        
        raid.primaryObjective = highestValue != null ? highestValue : center;
        raid.secondaryObjective = powerTarget;
        raid.tertiaryObjective = storageTarget;
        
        // Track newest construction (psychological targeting)
        raid.newestBuildSite = findNewestConstruction(world, center, scanRadius);
    }
    
    /**
     * Evaluate a target's value
     */
    private static int evaluateTarget(World world, BlockPos pos) {
        int value = 100; // Base value
        
        TileEntity te = world.getTileEntity(pos);
        if (te != null) {
            // Check for item handler (chest, etc)
            IItemHandler handler = te.getCapability(CapabilityItemHandler.ITEM_HANDLER_CAPABILITY, null);
            if (handler != null) {
                for (int i = 0; i < handler.getSlots(); i++) {
                    ItemStack stack = handler.getStackInSlot(i);
                    if (!stack.isEmpty()) {
                        value += getItemValue(stack);
                    }
                }
            }
        }
        
        Block block = world.getBlockState(pos).getBlock();
        if (block == Blocks.DIAMOND_BLOCK) value += 5000;
        if (block == Blocks.EMERALD_BLOCK) value += 4000;
        if (block == Blocks.GOLD_BLOCK) value += 2000;
        if (block == Blocks.IRON_BLOCK) value += 500;
        if (block == Blocks.BEACON) value += 10000;
        
        return value;
    }
    
    /**
     * Get item EMC/value estimate
     */
    private static int getItemValue(ItemStack stack) {
        Item item = stack.getItem();
        int count = stack.getCount();
        
        // Basic vanilla value estimates
        if (item == Items.DIAMOND) return 100 * count;
        if (item == Items.EMERALD) return 80 * count;
        if (item == Items.GOLD_INGOT) return 40 * count;
        if (item == Items.IRON_INGOT) return 10 * count;
        if (item == Items.NETHER_STAR) return 5000 * count;
        
        // Modded items - base on rarity
        int maxDamage = stack.getMaxDamage();
        if (maxDamage > 1000) return 200 * count;
        if (maxDamage > 500) return 100 * count;
        
        return 5 * count; // Default
    }
    
    /**
     * Find the player's newest construction
     */
    private static BlockPos findNewestConstruction(World world, BlockPos center, int radius) {
        // This would ideally track placed blocks, but for now we'll use a heuristic
        // Look for scaffolding, partial builds, or fresh construction patterns
        
        for (int x = -radius; x <= radius; x += 5) {
            for (int z = -radius; z <= radius; z += 5) {
                BlockPos checkPos = world.getTopSolidOrLiquidBlock(center.add(x, 0, z));
                
                // Check for construction patterns (scaffolding, ladders, etc)
                IBlockState state = world.getBlockState(checkPos);
                if (state.getBlock() == Blocks.SCAFFOLDING || 
                    state.getBlock() == Blocks.LADDER ||
                    state.getBlock() == Blocks.COBBLESTONE) {
                    return checkPos;
                }
            }
        }
        
        return null;
    }
    
    // ===== GOAL ASSIGNMENT =====
    
    /**
     * Assign operational goals based on tier
     */
    private static void assignGoals(ActiveRaid raid) {
        raid.goals.clear();
        raid.goals.add(OperationalGoal.STEAL_VALUABLES); // Always
        
        switch (raid.tier) {
            case BANDITS:
                raid.goals.add(OperationalGoal.DESTROY_DOORS);
                raid.goals.add(OperationalGoal.LIGHT_ARSON);
                break;
                
            case RAIDERS:
                raid.goals.add(OperationalGoal.DESTROY_DOORS);
                raid.goals.add(OperationalGoal.WRECK_STORAGE);
                raid.goals.add(OperationalGoal.KILL_LIVESTOCK);
                raid.goals.add(OperationalGoal.BREAK_FARMS);
                break;
                
            case ASSAULT:
                raid.goals.add(OperationalGoal.BREACH_WALLS);
                raid.goals.add(OperationalGoal.DESTROY_POWER);
                raid.goals.add(OperationalGoal.WRECK_STORAGE);
                break;
                
            case COMMANDO:
                raid.goals.add(OperationalGoal.DISABLE_COMMS);
                raid.goals.add(OperationalGoal.DISABLE_AD);
                raid.goals.add(OperationalGoal.PLANT_CHARGES);
                raid.goals.add(OperationalGoal.DESTROY_POWER);
                break;
                
            case STATE_ACTOR:
                raid.goals.add(OperationalGoal.DECOY_STRIKE);
                raid.goals.add(OperationalGoal.MISSILE_STRIKE);
                raid.goals.add(OperationalGoal.DISABLE_AD);
                raid.goals.add(OperationalGoal.PLANT_CHARGES);
                raid.goals.add(OperationalGoal.LEAVE_NOTHING);
                break;
        }
    }
    
    // ===== RAIDER SPAWNING =====
    
    /**
     * Spawn raiders for the raid
     */
    private static void spawnRaiders(World world, ActiveRaid raid, EntityPlayer target,
                                     RivalFactionStats factionStats) {
        // Calculate counts based on tier
        int infantry = getInfantryCount(raid.tier);
        int ranged = getRangedCount(raid.tier);
        int carriers = 1 + raid.tier.tier / 2; // Loot carriers
        
        // Spawn position (edge of awareness)
        double angle = rand.nextDouble() * Math.PI * 2;
        int distance = 40 + rand.nextInt(20);
        
        BlockPos spawnBase = target.getPosition().add(
            (int)(Math.cos(angle) * distance),
            0,
            (int)(Math.sin(angle) * distance)
        );
        spawnBase = world.getTopSolidOrLiquidBlock(spawnBase);
        
        // Spawn infantry
        for (int i = 0; i < infantry; i++) {
            BlockPos spawnPos = spawnBase.add(rand.nextInt(10) - 5, 0, rand.nextInt(10) - 5);
            spawnPos = world.getTopSolidOrLiquidBlock(spawnPos);
            
            EntityLiving raider = spawnRaider(world, spawnPos, raid, factionStats, false);
            if (raider != null) {
                raid.raiders.add(raider);
                raid.totalSpawned++;
            }
        }
        
        // Spawn ranged
        for (int i = 0; i < ranged; i++) {
            BlockPos spawnPos = spawnBase.add(rand.nextInt(10) - 5, 0, rand.nextInt(10) - 5);
            spawnPos = world.getTopSolidOrLiquidBlock(spawnPos);
            
            EntityLiving raider = spawnRaider(world, spawnPos, raid, factionStats, true);
            if (raider != null) {
                raid.raiders.add(raider);
                raid.totalSpawned++;
            }
        }
        
        // Spawn loot carriers (labeled differently)
        for (int i = 0; i < carriers; i++) {
            BlockPos spawnPos = spawnBase.add(rand.nextInt(6) - 3, 0, rand.nextInt(6) - 3);
            spawnPos = world.getTopSolidOrLiquidBlock(spawnPos);
            
            EntityLiving carrier = spawnRaider(world, spawnPos, raid, factionStats, false);
            if (carrier != null) {
                carrier.setCustomNameTag("§eLoot Carrier");
                raid.lootCarriers.add(carrier);
                raid.totalSpawned++;
            }
        }
    }
    
    private static int getInfantryCount(RaidTier tier) {
        switch (tier) {
            case BANDITS: return 5 + rand.nextInt(3);
            case RAIDERS: return 10 + rand.nextInt(5);
            case ASSAULT: return 20 + rand.nextInt(10);
            case COMMANDO: return 15 + rand.nextInt(5);
            case STATE_ACTOR: return 40 + rand.nextInt(20);
            default: return 5;
        }
    }
    
    private static int getRangedCount(RaidTier tier) {
        switch (tier) {
            case BANDITS: return rand.nextInt(3);
            case RAIDERS: return 3 + rand.nextInt(3);
            case ASSAULT: return 8 + rand.nextInt(5);
            case COMMANDO: return 10 + rand.nextInt(5);
            case STATE_ACTOR: return 15 + rand.nextInt(10);
            default: return 0;
        }
    }
    
    private static EntityLiving spawnRaider(World world, BlockPos pos, ActiveRaid raid,
                                            RivalFactionStats factionStats, boolean ranged) {
        EntityAIPilot raider = new EntityAIPilot(world);
        raider.setPosition(pos.getX() + 0.5, pos.getY(), pos.getZ() + 0.5);
        raider.setMcmTeam("RIVAL");
        
        // Weapon based on tier
        ItemStack weapon = getWeaponForTier(raid.tier, ranged);
        raider.setHeldItem(EnumHand.MAIN_HAND, weapon);
        
        // Name based on tier
        String name = raid.tier.displayName + " " + (ranged ? "Archer" : "Raider");
        raider.setCustomNameTag(name);
        
        world.spawnEntity(raider);
        return raider;
    }
    
    private static ItemStack getWeaponForTier(RaidTier tier, boolean ranged) {
        if (ranged) {
            return new ItemStack(Items.BOW);
        }
        
        switch (tier) {
            case BANDITS:
                return rand.nextBoolean() ? 
                    new ItemStack(Items.WOODEN_SWORD) : new ItemStack(Items.WOODEN_AXE);
            case RAIDERS:
                return rand.nextBoolean() ?
                    new ItemStack(Items.STONE_SWORD) : new ItemStack(Items.IRON_AXE);
            case ASSAULT:
                return new ItemStack(Items.IRON_SWORD);
            case COMMANDO:
            case STATE_ACTOR:
                return new ItemStack(Items.DIAMOND_SWORD);
            default:
                return new ItemStack(Items.WOODEN_SWORD);
        }
    }
    
    // ===== PHASE MANAGEMENT =====
    
    private static void startPhase(ActiveRaid raid, RaidPhase phase) {
        raid.phase = phase;
        raid.phaseDurationTicks = phase.getDuration();
        raid.phaseStartTick = 0; // Will be set on first tick
    }
    
    // ===== TICK UPDATE =====
    
    /**
     * Main tick update for all raids
     */
    public static void tick(World world) {
        if (world.isRemote) return;
        
        long currentTick = world.getTotalWorldTime();
        
        Iterator<Map.Entry<String, ActiveRaid>> iter = activeRaids.entrySet().iterator();
        while (iter.hasNext()) {
            ActiveRaid raid = iter.next().getValue();
            
            // Initialize phase start tick
            if (raid.phaseStartTick == 0) {
                raid.phaseStartTick = currentTick;
            }
            
            // Update phase
            long phaseElapsed = currentTick - raid.phaseStartTick;
            if (phaseElapsed >= raid.phaseDurationTicks) {
                advancePhase(world, raid);
            }
            
            // Execute phase actions
            executePhaseActions(world, raid);
            
            // Track casualties
            updateCasualties(raid);
            
            // Check end conditions
            if (shouldEndRaid(raid)) {
                endRaid(world, raid);
                iter.remove();
            }
        }
    }
    
    private static void advancePhase(World world, ActiveRaid raid) {
        switch (raid.phase) {
            case APPROACH:
                startPhase(raid, RaidPhase.BREACH);
                break;
            case BREACH:
                startPhase(raid, RaidPhase.CLEAR_LOOT);
                break;
            case CLEAR_LOOT:
                startPhase(raid, RaidPhase.EXFIL);
                break;
            case EXFIL:
                // Raid complete
                break;
        }
        raid.phaseStartTick = world.getTotalWorldTime();
    }
    
    private static void executePhaseActions(World world, ActiveRaid raid) {
        switch (raid.phase) {
            case APPROACH:
                // Raiders move toward target, may sabotage alarms
                break;
                
            case BREACH:
                // Execute breach - destroy doors/walls
                if (raid.goals.contains(OperationalGoal.DESTROY_DOORS)) {
                    destroyDoors(world, raid);
                }
                if (raid.goals.contains(OperationalGoal.BREACH_WALLS)) {
                    breachWalls(world, raid);
                }
                break;
                
            case CLEAR_LOOT:
                // Main raid phase - loot and destroy
                executeLootAndDestroy(world, raid);
                break;
                
            case EXFIL:
                // Raiders retreat with loot
                executeExfil(world, raid);
                break;
        }
    }
    
    // ===== RAID ACTIONS =====
    
    private static void destroyDoors(World world, ActiveRaid raid) {
        if (raid.blocksDestroyed >= raid.destructionBudget) return;
        
        int searchRadius = 20;
        BlockPos center = raid.targetCenter;
        
        for (int x = -searchRadius; x <= searchRadius; x++) {
            for (int y = -5; y <= 10; y++) {
                for (int z = -searchRadius; z <= searchRadius; z++) {
                    BlockPos pos = center.add(x, y, z);
                    if (!world.isBlockLoaded(pos)) continue;
                    
                    Block block = world.getBlockState(pos).getBlock();
                    if (DOOR_BLOCKS.contains(block)) {
                        world.destroyBlock(pos, false);
                        raid.blocksDestroyed++;
                        
                        if (raid.blocksDestroyed >= raid.destructionBudget) return;
                    }
                }
            }
        }
    }
    
    private static void breachWalls(World world, ActiveRaid raid) {
        if (raid.blocksDestroyed >= raid.destructionBudget) return;
        
        // Find weakest wall segment and breach
        BlockPos target = raid.primaryObjective != null ? raid.primaryObjective : raid.targetCenter;
        
        // Simple breach: destroy blocks in a path
        int breachSize = 3 + rand.nextInt(3);
        for (int i = 0; i < breachSize && raid.blocksDestroyed < raid.destructionBudget; i++) {
            BlockPos breachPos = target.add(rand.nextInt(5) - 2, rand.nextInt(3), rand.nextInt(5) - 2);
            if (world.isBlockLoaded(breachPos) && !world.isAirBlock(breachPos)) {
                world.destroyBlock(breachPos, false);
                raid.blocksDestroyed++;
            }
        }
    }
    
    private static void executeLootAndDestroy(World world, ActiveRaid raid) {
        // Loot primary objective
        if (raid.primaryObjective != null && world.isBlockLoaded(raid.primaryObjective)) {
            lootPosition(world, raid, raid.primaryObjective);
        }
        
        // Execute destruction goals
        if (raid.goals.contains(OperationalGoal.DESTROY_POWER) && raid.secondaryObjective != null) {
            destroyPower(world, raid);
        }
        
        if (raid.goals.contains(OperationalGoal.WRECK_STORAGE)) {
            wreckStorage(world, raid);
        }
        
        if (raid.goals.contains(OperationalGoal.LIGHT_ARSON)) {
            lightArson(world, raid);
        }
        
        // Psychological targeting - hit newest build
        if (raid.newestBuildSite != null && raid.tier.tier >= 3) {
            if (world.isBlockLoaded(raid.newestBuildSite)) {
                int radius = 5;
                for (int x = -radius; x <= radius && raid.blocksDestroyed < raid.destructionBudget; x++) {
                    for (int z = -radius; z <= radius; z++) {
                        BlockPos pos = raid.newestBuildSite.add(x, 0, z);
                        if (!world.isAirBlock(pos)) {
                            world.destroyBlock(pos, false);
                            raid.blocksDestroyed++;
                        }
                    }
                }
            }
        }
    }
    
    private static void lootPosition(World world, ActiveRaid raid, BlockPos pos) {
        TileEntity te = world.getTileEntity(pos);
        if (te == null) return;
        
        IItemHandler handler = te.getCapability(CapabilityItemHandler.ITEM_HANDLER_CAPABILITY, null);
        if (handler == null) return;
        
        // Steal items
        for (int i = 0; i < handler.getSlots(); i++) {
            ItemStack stack = handler.extractItem(i, 64, false);
            if (!stack.isEmpty()) {
                raid.stolenLoot.add(stack.copy());
                raid.lootValue += getItemValue(stack);
            }
        }
    }
    
    private static void destroyPower(World world, ActiveRaid raid) {
        if (raid.secondaryObjective == null) return;
        if (raid.blocksDestroyed >= raid.destructionBudget) return;
        
        int radius = 10;
        BlockPos center = raid.secondaryObjective;
        
        for (int x = -radius; x <= radius && raid.blocksDestroyed < raid.destructionBudget; x++) {
            for (int y = -3; y <= 3; y++) {
                for (int z = -radius; z <= radius; z++) {
                    BlockPos pos = center.add(x, y, z);
                    if (!world.isBlockLoaded(pos)) continue;
                    
                    Block block = world.getBlockState(pos).getBlock();
                    if (POWER_BLOCKS.contains(block)) {
                        world.destroyBlock(pos, false);
                        raid.blocksDestroyed++;
                    }
                }
            }
        }
    }
    
    private static void wreckStorage(World world, ActiveRaid raid) {
        if (raid.blocksDestroyed >= raid.destructionBudget) return;
        
        int radius = 30;
        BlockPos center = raid.targetCenter;
        
        for (int x = -radius; x <= radius && raid.blocksDestroyed < raid.destructionBudget; x++) {
            for (int y = -5; y <= 10; y++) {
                for (int z = -radius; z <= radius; z++) {
                    BlockPos pos = center.add(x, y, z);
                    if (!world.isBlockLoaded(pos)) continue;
                    
                    Block block = world.getBlockState(pos).getBlock();
                    if (STORAGE_BLOCKS.contains(block) && rand.nextFloat() < 0.3f) {
                        // Loot first, then destroy
                        lootPosition(world, raid, pos);
                        world.destroyBlock(pos, false);
                        raid.blocksDestroyed++;
                    }
                }
            }
        }
    }
    
    private static void lightArson(World world, ActiveRaid raid) {
        if (raid.blocksDestroyed >= raid.destructionBudget) return;
        
        int radius = 20;
        BlockPos center = raid.targetCenter;
        int fires = 0;
        int maxFires = 5;
        
        for (int x = -radius; x <= radius && fires < maxFires; x++) {
            for (int z = -radius; z <= radius; z++) {
                BlockPos pos = world.getTopSolidOrLiquidBlock(center.add(x, 0, z));
                
                // Set fire on flammable blocks
                Block below = world.getBlockState(pos.down()).getBlock();
                if (below.getFlammability(world, pos.down(), EnumFacing.UP) > 0) {
                    if (world.isAirBlock(pos)) {
                        world.setBlockState(pos, Blocks.FIRE.getDefaultState());
                        fires++;
                        raid.blocksDestroyed++;
                    }
                }
            }
        }
    }
    
    private static void executeExfil(World world, ActiveRaid raid) {
        // Move raiders away from target
        // In practice this would update their AI goals
        // For now, just mark them for despawn
    }
    
    // ===== CASUALTY TRACKING =====
    
    private static void updateCasualties(ActiveRaid raid) {
        raid.raiders.removeIf(entity -> {
            if (entity.isDead) {
                raid.totalKilled++;
                return true;
            }
            return false;
        });
        
        raid.lootCarriers.removeIf(entity -> {
            if (entity.isDead) {
                raid.totalKilled++;
                // Loot carriers drop stolen items when killed
                // (handled by entity death)
                return true;
            }
            return false;
        });
    }
    
    // ===== END CONDITIONS =====
    
    private static boolean shouldEndRaid(ActiveRaid raid) {
        // All raiders dead
        if (raid.raiders.isEmpty() && raid.lootCarriers.isEmpty()) {
            return true;
        }
        
        // Exfil phase complete
        if (raid.phase == RaidPhase.EXFIL) {
            return true;
        }
        
        // Time limit (prevent infinite raids)
        // Handled by phase durations
        
        return false;
    }
    
    private static void endRaid(World world, ActiveRaid raid) {
        boolean playerWon = raid.totalKilled > raid.totalSpawned / 2;
        
        // Kill remaining raiders
        for (EntityLiving raider : raid.raiders) {
            if (!raider.isDead) raider.setDead();
        }
        for (EntityLiving carrier : raid.lootCarriers) {
            if (!carrier.isDead) carrier.setDead();
        }
        
        // Report to war authority
        WarStateAuthority.get().endEvent(world, playerWon);
        
        // Log results
        EpochRunnerMod.logger.info("[RAID] Ended " + raid.tier.displayName + 
            " - Loot: " + raid.lootValue + 
            " - Destroyed: " + raid.blocksDestroyed + " blocks" +
            " - Casualties: " + raid.totalKilled + "/" + raid.totalSpawned +
            " - Result: " + (playerWon ? "DEFENDED" : "RAIDED"));
    }
    
    // ===== ANNOUNCEMENTS =====
    
    private static void announceRaid(EntityPlayer player, ActiveRaid raid) {
        player.sendMessage(new TextComponentString(
            TextFormatting.DARK_RED + "═══════════════════════════════════"
        ));
        player.sendMessage(new TextComponentString(
            TextFormatting.RED + "⚠ " + raid.tier.displayName + " RAID INCOMING!"
        ));
        player.sendMessage(new TextComponentString(
            TextFormatting.GRAY + "Intel suggests " + raid.totalSpawned + " hostiles"
        ));
        
        // Tier-specific warnings
        if (raid.tier.tier >= 3) {
            player.sendMessage(new TextComponentString(
                TextFormatting.YELLOW + "⚡ They're targeting your power systems!"
            ));
        }
        if (raid.tier.tier >= 4) {
            player.sendMessage(new TextComponentString(
                TextFormatting.RED + "☠ Elite forces detected - expect sabotage"
            ));
        }
        if (raid.tier.tier >= 5) {
            player.sendMessage(new TextComponentString(
                TextFormatting.DARK_RED + "☢ STATE-LEVEL ASSAULT - ALL DEFENSES ACTIVE"
            ));
        }
        
        player.sendMessage(new TextComponentString(
            TextFormatting.DARK_RED + "═══════════════════════════════════"
        ));
    }
    
    // ===== QUERIES =====
    
    public static boolean hasActiveRaid() {
        return !activeRaids.isEmpty();
    }
    
    public static ActiveRaid getRaid(String id) {
        return activeRaids.get(id);
    }
    
    public static ActiveRaid getFirstActiveRaid() {
        if (activeRaids.isEmpty()) return null;
        return activeRaids.values().iterator().next();
    }
    
    public static void endAllRaids(World world) {
        for (ActiveRaid raid : activeRaids.values()) {
            for (EntityLiving raider : raid.raiders) {
                if (!raider.isDead) raider.setDead();
            }
            for (EntityLiving carrier : raid.lootCarriers) {
                if (!carrier.isDead) carrier.setDead();
            }
        }
        activeRaids.clear();
        WarStateAuthority.get().forceEndEvent(world);
    }
    
    // ===== INSTANCE METHODS (for WarSystemIntegration) =====
    
    /**
     * Instance method for starting a raid (wraps static method)
     */
    public ActiveRaid startRaid(World world, EntityPlayer player, int playerEMC, 
                                int forcedTier, RivalFactionStats factionStats) {
        // If forcedTier > 0, use it
        if (forcedTier > 0) {
            RaidTier tier = RaidTier.values()[Math.min(forcedTier - 1, RaidTier.values().length - 1)];
            return startRaidWithTier(world, player, playerEMC, tier, factionStats);
        }
        return startRaid(world, player, playerEMC, factionStats);
    }
    
    /**
     * Start a raid with a specific tier (bypasses EMC calculation)
     */
    public static ActiveRaid startRaidWithTier(World world, EntityPlayer player, int playerEMC,
                                               RaidTier tier, RivalFactionStats factionStats) {
        // Check with war authority
        WarStateAuthority authority = WarStateAuthority.get();
        
        if (!authority.startRaid(world, player.getPosition(), tier.tier)) {
            return null;
        }
        
        String raidId = "raid_" + System.currentTimeMillis();
        ActiveRaid raid = new ActiveRaid(raidId, tier, player.getPosition(), playerEMC);
        raid.startTick = world.getTotalWorldTime();
        
        // Scan for objectives
        scanObjectives(world, raid, player);
        
        // Assign operational goals based on tier
        assignGoals(raid);
        
        // Spawn raiders
        spawnRaiders(world, raid, player, factionStats);
        
        // Store raid
        activeRaids.put(raidId, raid);
        
        // Announce
        announceRaid(player, raid);
        
        EpochRunnerMod.logger.info("[RAID] Started " + tier.displayName + " raid with " + 
            raid.totalSpawned + " units");
        
        return raid;
    }
    
    /**
     * Instance tick method
     */
    public void tick(World world, long worldTick) {
        tickRaids(world, worldTick);
    }
    
    /**
     * Instance query for active raid
     */
    public ActiveRaid getActiveRaid() {
        return getFirstActiveRaid();
    }
    
    /**
     * Get alive count for active raid
     */
    public int getActiveCount() {
        ActiveRaid raid = getFirstActiveRaid();
        if (raid == null) return 0;
        return raid.getAliveCount();
    }
}
