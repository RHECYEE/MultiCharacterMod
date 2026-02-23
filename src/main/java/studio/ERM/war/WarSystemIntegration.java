package studio.ERM.war;

import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.ChunkPos;
import net.minecraft.util.text.TextComponentString;
import net.minecraft.util.text.TextFormatting;
import net.minecraft.world.World;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import net.minecraftforge.fml.common.gameevent.TickEvent;
import studio.ERM.EpochRunnerMod;
import studio.ERM.war.battle.WarBattleSystem;
import studio.ERM.war.faction.ProceduralBuildingGenerator;
import studio.ERM.war.faction.RivalExpansionManager;
import studio.ERM.war.faction.RivalFactionStats;
import studio.ERM.war.raid.SmartRaidSystem;

import java.util.*;

/**
 * WAR SYSTEM INTEGRATION
 * 
 * Central coordinator for the new war systems:
 * - RivalFactionStats (three-stat economy)
 * - RivalExpansionManager (directional growth)
 * - ProceduralBuildingGenerator (buildings)
 * - SmartRaidSystem (tier-based raids)
 * - WarBattleSystem (control point battles)
 * 
 * This class bridges the new systems with existing code and handles:
 * - Tick updates for all systems
 * - Event coordination
 * - NBT persistence
 * - Legacy compatibility
 */
@Mod.EventBusSubscriber(modid = EpochRunnerMod.MODID)
public class WarSystemIntegration {
    
    // ===== SYSTEM INSTANCES (per world) =====
    private static final Map<Integer, RivalFactionStats> factionStats = new HashMap<>();
    private static final Map<Integer, RivalExpansionManager> expansionManagers = new HashMap<>();
    private static final Map<Integer, SmartRaidSystem> raidSystems = new HashMap<>();
    
    // Global battle system (one battle at a time)
    private static WarBattleSystem battleSystem = null;
    
    // Tick counter
    private static long worldTick = 0;
    
    // ===== INITIALIZATION =====
    
    /**
     * Initialize systems for a world
     */
    public static void initializeForWorld(World world) {
        int dim = world.provider.getDimension();
        
        if (!factionStats.containsKey(dim)) {
            RivalFactionStats stats = new RivalFactionStats("rival_faction_" + dim);
            factionStats.put(dim, stats);
            EpochRunnerMod.logger.info("[WAR-INT] Initialized faction stats for dimension " + dim);
        }
        
        if (!expansionManagers.containsKey(dim)) {
            RivalExpansionManager manager = new RivalExpansionManager();
            expansionManagers.put(dim, manager);
            EpochRunnerMod.logger.info("[WAR-INT] Initialized expansion manager for dimension " + dim);
        }
        
        if (!raidSystems.containsKey(dim)) {
            SmartRaidSystem raid = new SmartRaidSystem();
            raidSystems.put(dim, raid);
            EpochRunnerMod.logger.info("[WAR-INT] Initialized raid system for dimension " + dim);
        }
        
        if (battleSystem == null) {
            battleSystem = new WarBattleSystem();
            EpochRunnerMod.logger.info("[WAR-INT] Initialized global battle system");
        }
    }
    
    /**
     * Get faction stats for a dimension
     */
    public static RivalFactionStats getFactionStats(World world) {
        initializeForWorld(world);
        return factionStats.get(world.provider.getDimension());
    }
    
    /**
     * Get expansion manager for a dimension
     */
    public static RivalExpansionManager getExpansionManager(World world) {
        initializeForWorld(world);
        return expansionManagers.get(world.provider.getDimension());
    }
    
    /**
     * Get raid system for a dimension
     */
    public static SmartRaidSystem getRaidSystem(World world) {
        initializeForWorld(world);
        return raidSystems.get(world.provider.getDimension());
    }
    
    /**
     * Get the global battle system
     */
    public static WarBattleSystem getBattleSystem() {
        if (battleSystem == null) {
            battleSystem = new WarBattleSystem();
        }
        return battleSystem;
    }
    
    // ===== TICK HANDLER =====
    
    @SubscribeEvent
    public static void onWorldTick(TickEvent.WorldTickEvent event) {
        if (event.phase != TickEvent.Phase.END) return;
        if (event.world.isRemote) return;
        
        World world = event.world;
        worldTick = world.getTotalWorldTime();
        
        // Only tick systems in overworld (dimension 0) by default
        if (world.provider.getDimension() != 0) return;
        
        // Update faction stats recovery (every 60 ticks = 3 seconds)
        if (worldTick % 60 == 0) {
            RivalFactionStats stats = getFactionStats(world);
            if (stats != null) {
                stats.tickRecovery(worldTick);
            }
        }
        
        // Update expansion manager (every 200 ticks = 10 seconds)
        if (worldTick % 200 == 0) {
            RivalExpansionManager manager = getExpansionManager(world);
            if (manager != null && manager.isInitialized()) {
                // Update player target for directional growth
                EntityPlayer nearestPlayer = world.getClosestPlayer(
                    manager.getCapitalPosition().getX(),
                    manager.getCapitalPosition().getY(),
                    manager.getCapitalPosition().getZ(),
                    10000, false
                );
                if (nearestPlayer != null) {
                    manager.updatePlayerTarget(nearestPlayer.getPosition());
                }
            }
        }
        
        // Update raid system (every tick when active)
        SmartRaidSystem raid = getRaidSystem(world);
        if (raid != null) {
            raid.tick(world, worldTick);
        }
        
        // Update battle system (every tick when active)
        if (battleSystem != null) {
            battleSystem.tick(world, worldTick);
        }
    }
    
    // ===== CITY INTEGRATION =====
    
    /**
     * Initialize rival faction when city spawns
     * Called from RivalCityManager.initializeRivalCity()
     */
    public static void onRivalCitySpawned(World world, BlockPos center, int level) {
        RivalFactionStats stats = getFactionStats(world);
        stats.initializeForLevel(level);
        
        RivalExpansionManager manager = getExpansionManager(world);
        manager.initialize(center, level);
        
        EpochRunnerMod.logger.info("[WAR-INT] Rival city spawned at " + center + " level " + level);
    }
    
    /**
     * Handle city growth
     * Called from RivalCityManager.growCity()
     */
    public static void onRivalCityGrown(World world, int newLevel) {
        RivalFactionStats stats = getFactionStats(world);
        stats.levelUp();
        
        RivalExpansionManager manager = getExpansionManager(world);
        manager.setLevel(newLevel);
        
        // Trigger expansion toward player
        EntityPlayer nearestPlayer = findNearestPlayer(world, manager.getCapitalPosition());
        if (nearestPlayer != null) {
            manager.updatePlayerTarget(nearestPlayer.getPosition());
            
            // Perform expansion steps
            int expansionSteps = 2 + newLevel / 2;
            for (int i = 0; i < expansionSteps; i++) {
                RivalExpansionManager.ExpansionResult result = manager.performExpansion(world, stats);
                if (result != null && result.node != null) {
                    // Generate building at new node
                    generateBuildingAtNode(world, result.node, newLevel);
                }
            }
            
            // Trigger density backfill
            manager.performDensityBackfill(world, stats);
        }
        
        EpochRunnerMod.logger.info("[WAR-INT] Rival city grown to level " + newLevel);
    }
    
    /**
     * Generate a procedural building at an expansion node
     */
    private static void generateBuildingAtNode(World world, RivalExpansionManager.ExpansionNode node, int level) {
        if (node.structureType == null) return;
        
        BlockPos pos = node.position;
        
        // Map structure type to building generator
        switch (node.structureType) {
            case "SKYSCRAPER":
                ProceduralBuildingGenerator.generateSkyscraper(world, pos, level);
                break;
            case "COOLING_TOWER":
                ProceduralBuildingGenerator.generateCoolingTower(world, pos, level);
                break;
            case "FACTORY":
                ProceduralBuildingGenerator.generateFactory(world, pos, level);
                break;
            case "SILO":
            case "STORAGE":
                ProceduralBuildingGenerator.generateSiloCluster(world, pos, level);
                break;
            case "ANTENNA":
            case "RADAR":
                ProceduralBuildingGenerator.generateAntennaTower(world, pos, level);
                break;
            case "FORT":
            case "BUNKER":
                ProceduralBuildingGenerator.generateFort(world, pos, level, node.facing);
                break;
            case "WATCHTOWER":
                ProceduralBuildingGenerator.generateWatchtower(world, pos, level);
                break;
            case "BARRACKS":
                // Use battle system barracks
                WarBattleSystem.placeBarracks(world, pos, level);
                break;
        }
        
        // Update faction stats with new structure
        RivalFactionStats stats = getFactionStats(world);
        RivalFactionStats.StructureType statType = mapToStatStructureType(node.structureType);
        if (statType != null) {
            stats.onStructureBuilt(statType);
        }
    }
    
    /**
     * Map expansion node structure type to faction stat structure type
     */
    private static RivalFactionStats.StructureType mapToStatStructureType(String nodeType) {
        if (nodeType == null) return null;
        switch (nodeType) {
            case "SKYSCRAPER": return RivalFactionStats.StructureType.SKYSCRAPER;
            case "COOLING_TOWER": return RivalFactionStats.StructureType.COOLING_TOWER;
            case "FACTORY": return RivalFactionStats.StructureType.FACTORY;
            case "SILO":
            case "STORAGE": return RivalFactionStats.StructureType.SILO_STORAGE;
            case "ANTENNA":
            case "RADAR": return RivalFactionStats.StructureType.ANTENNA_RADAR;
            case "HOUSING": return RivalFactionStats.StructureType.HOUSING;
            case "FARM": return RivalFactionStats.StructureType.FARM;
            case "FORT":
            case "BUNKER": return RivalFactionStats.StructureType.MILITARY_FORT;
            case "BARRACKS": return RivalFactionStats.StructureType.BARRACKS;
            case "WATCHTOWER": return RivalFactionStats.StructureType.WATCHTOWER;
            default: return null;
        }
    }
    
    // ===== STRUCTURE DESTRUCTION =====
    
    /**
     * Called when a rival structure is destroyed
     */
    public static void onRivalStructureDestroyed(World world, BlockPos pos, String structureType) {
        RivalFactionStats stats = getFactionStats(world);
        RivalFactionStats.StructureType type = mapToStatStructureType(structureType);
        
        if (type != null) {
            stats.onStructureDestroyed(type);
            
            // Notify expansion manager
            RivalExpansionManager manager = getExpansionManager(world);
            manager.onNodeDestroyed(pos);
            
            EpochRunnerMod.logger.info("[WAR-INT] Structure destroyed: " + structureType + " at " + pos);
        }
    }
    
    // ===== RAID INTEGRATION =====
    
    /**
     * Start a smart raid against a player
     */
    public static SmartRaidSystem.ActiveRaid startSmartRaid(World world, EntityPlayer target, int forcedTier) {
        SmartRaidSystem raid = getRaidSystem(world);
        RivalFactionStats stats = getFactionStats(world);
        
        // Check if faction can raid
        if (!stats.canInitiateBattle()) {
            target.sendMessage(new TextComponentString(
                TextFormatting.YELLOW + "The rival faction is too weak to raid right now."
            ));
            return null;
        }
        
        // Calculate EMC value of player's base (simplified - use chunk claims)
        int playerEMC = estimatePlayerEMC(world, target);
        
        // Start raid
        SmartRaidSystem.ActiveRaid activeRaid = raid.startRaid(world, target, playerEMC, forcedTier, stats);
        
        if (activeRaid != null) {
            // Announce raid
            String tierName = activeRaid.tier.displayName;
            target.sendMessage(new TextComponentString(
                TextFormatting.RED + "⚠ " + tierName + TextFormatting.RED + " RAID INCOMING!"
            ));
            
            EpochRunnerMod.logger.info("[WAR-INT] Started " + tierName + " raid on " + target.getName());
        }
        
        return activeRaid;
    }
    
    /**
     * Estimate player's EMC value for raid tier calculation
     */
    private static int estimatePlayerEMC(World world, EntityPlayer player) {
        WarWorldData data = WarWorldData.get(world);
        String playerId = player.getUniqueID().toString();
        
        // Count claimed chunks
        int claimedChunks = 0;
        // This would need integration with your claim system
        // For now, estimate based on era/level
        
        WarWorldData.FactionStats stats = data.getStats(playerId);
        int era = stats.era;
        
        // Base EMC scales exponentially with era
        int baseEMC = (int) Math.pow(10, era);
        
        // Add for command points (represents investment)
        baseEMC += stats.commandPoints * 100;
        
        return baseEMC;
    }
    
    // ===== BATTLE INTEGRATION =====
    
    /**
     * Create a battle site at a contested border
     */
    public static WarBattleSystem.BattleSite createBattleSite(World world, ChunkPos chunk, String name, int level) {
        RivalFactionStats stats = getFactionStats(world);
        
        // Check if faction can battle
        if (!stats.canInitiateBattle()) {
            return null;
        }
        
        return getBattleSystem().createBattleSite(world, chunk, name, level);
    }
    
    /**
     * Start a battle at an existing site
     */
    public static boolean startBattle(World world, ChunkPos chunk, EntityPlayer initiator) {
        WarBattleSystem.BattleSite site = getBattleSystem().getBattleSite(chunk);
        if (site == null) return false;
        
        RivalFactionStats stats = getFactionStats(world);
        return getBattleSystem().startBattle(world, site, stats, initiator);
    }
    
    /**
     * Called when a battle ends
     */
    public static void onBattleResolved(World world, boolean playerWon, int casualties, int vehiclesLost) {
        RivalFactionStats stats = getFactionStats(world);
        stats.consumeForBattle(casualties, vehiclesLost, !playerWon);
        
        // Update war state authority
        WarStateAuthority.get().endEvent(world, playerWon);
        
        // Check if faction should seek peace
        if (stats.shouldSeekPeace()) {
            EpochRunnerMod.logger.info("[WAR-INT] Rival faction morale broken - seeking peace!");
            // Could trigger peace event here
        }
    }
    
    // ===== HELPER METHODS =====
    
    private static EntityPlayer findNearestPlayer(World world, BlockPos pos) {
        return world.getClosestPlayer(pos.getX(), pos.getY(), pos.getZ(), 10000, false);
    }
    
    // ===== NBT PERSISTENCE =====
    
    /**
     * Save all systems to NBT
     */
    public static NBTTagCompound writeToNBT(World world) {
        NBTTagCompound nbt = new NBTTagCompound();
        int dim = world.provider.getDimension();
        
        // Save faction stats
        RivalFactionStats stats = factionStats.get(dim);
        if (stats != null) {
            nbt.setTag("factionStats", stats.writeToNBT(new NBTTagCompound()));
        }
        
        // Save expansion manager
        RivalExpansionManager manager = expansionManagers.get(dim);
        if (manager != null) {
            nbt.setTag("expansionManager", manager.writeToNBT(new NBTTagCompound()));
        }
        
        // Battle system state is transient by design
        
        return nbt;
    }
    
    /**
     * Load all systems from NBT
     */
    public static void readFromNBT(World world, NBTTagCompound nbt) {
        int dim = world.provider.getDimension();
        
        // Load faction stats
        if (nbt.hasKey("factionStats")) {
            RivalFactionStats stats = new RivalFactionStats("rival_faction_" + dim);
            stats.readFromNBT(nbt.getCompoundTag("factionStats"));
            factionStats.put(dim, stats);
        }
        
        // Load expansion manager
        if (nbt.hasKey("expansionManager")) {
            RivalExpansionManager manager = new RivalExpansionManager();
            manager.readFromNBT(nbt.getCompoundTag("expansionManager"));
            expansionManagers.put(dim, manager);
        }
    }
    
    /**
     * Reset all systems for a dimension
     */
    public static void reset(World world) {
        int dim = world.provider.getDimension();
        factionStats.remove(dim);
        expansionManagers.remove(dim);
        raidSystems.remove(dim);
        
        if (battleSystem != null) {
            battleSystem.reset();
        }
        
        EpochRunnerMod.logger.info("[WAR-INT] Reset all systems for dimension " + dim);
    }
    
    // ===== STATUS DISPLAY =====
    
    /**
     * Get status lines for display
     */
    public static String[] getStatusLines(World world) {
        List<String> lines = new ArrayList<>();
        
        RivalFactionStats stats = getFactionStats(world);
        if (stats != null) {
            lines.add("§c=== RIVAL FACTION ===");
            lines.add("§7Level: §e" + stats.getLevel());
            lines.add("§cPop: §f" + stats.getPopulation() + "/" + stats.getMaxPopulation());
            lines.add("§6Ind: §f" + stats.getIndustry() + "/" + stats.getMaxIndustry());
            lines.add("§aMorale: §f" + stats.getMorale() + "%");
            lines.add("§7Vehicle Tier: §f" + stats.getVehicleTier());
        }
        
        RivalExpansionManager manager = getExpansionManager(world);
        if (manager != null && manager.isInitialized()) {
            lines.add("");
            lines.add("§e=== EXPANSION ===");
            lines.add("§7Nodes: §f" + manager.getNodeCount());
            lines.add("§7Territory: §f" + manager.getClaimedChunkCount() + " chunks");
        }
        
        if (battleSystem != null && battleSystem.hasActiveBattle()) {
            lines.add("");
            lines.add("§4=== ACTIVE BATTLE ===");
            WarBattleSystem.BattleSite site = battleSystem.getActiveBattleSite();
            if (site != null) {
                lines.add("§7Site: §f" + site.name);
                lines.add("§7State: §f" + site.state.name());
            }
        }
        
        SmartRaidSystem raid = getRaidSystem(world);
        if (raid != null && raid.hasActiveRaid()) {
            lines.add("");
            lines.add("§c=== ACTIVE RAID ===");
            SmartRaidSystem.ActiveRaid active = raid.getActiveRaid();
            if (active != null) {
                lines.add("§7Tier: " + active.tier.displayName);
                lines.add("§7Phase: §f" + active.phase.displayName);
                lines.add("§7Enemies: §f" + active.getAliveCount() + "/" + active.totalSpawned);
            }
        }
        
        return lines.toArray(new String[0]);
    }
}
