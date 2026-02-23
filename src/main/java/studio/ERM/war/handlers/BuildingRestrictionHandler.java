package studio.ERM.war.handlers;

import net.minecraft.block.Block;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.ChunkPos;
import net.minecraft.util.text.TextComponentString;
import net.minecraft.world.World;
import net.minecraftforge.event.entity.player.PlayerInteractEvent;
import net.minecraftforge.event.world.BlockEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.common.eventhandler.EventPriority;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import studio.ERM.EpochRunnerMod;
import studio.ERM.war.world.WarWorldData;

/**
 * Building Restriction Handler
 * 
 * Enforces territory-based building restrictions:
 * - Players can only build in claimed chunks
 * - Only MINER class can mine underground in unclaimed land
 * - Only LUMBERJACK class can chop trees in unclaimed land
 * - Everything else requires being in your borders
 * 
 * Integrates with MultiCharacter mod for class system.
 */
@Mod.EventBusSubscriber(modid = EpochRunnerMod.MODID)
public class BuildingRestrictionHandler {
    
    // Configuration
    public static boolean enabled = true;
    public static boolean allowBreakingInUnclaimed = false; // If true, anyone can break blocks in unclaimed
    public static int undergroundLevel = 50; // Y level considered "underground"
    
    // Block categories for lumberjack
    private static final String[] LOG_BLOCKS = {
        "log", "log2", "wood", "leaves", "leaves2", "sapling"
    };
    
    // Block categories for miner
    private static final String[] ORE_BLOCKS = {
        "ore", "stone", "cobblestone", "gravel", "dirt", "sand", "sandstone",
        "granite", "diorite", "andesite", "obsidian"
    };
    
    @SubscribeEvent(priority = EventPriority.HIGH)
    public static void onBlockPlace(BlockEvent.PlaceEvent event) {
        if (!enabled) return;
        if (event.getWorld().isRemote) return;
        
        EntityPlayer player = event.getPlayer();
        if (player == null) return;
        
        // Ops/creative can build anywhere
        if (player.capabilities.isCreativeMode || isOperator(player)) return;
        
        BlockPos pos = event.getPos();
        World world = event.getWorld();
        
        if (!canBuildAt(player, world, pos)) {
            event.setCanceled(true);
            player.sendStatusMessage(new TextComponentString(
                "§c[Territory] §7You can only build in claimed territory!"), true);
        }
    }
    
    @SubscribeEvent(priority = EventPriority.HIGH)
    public static void onBlockBreak(BlockEvent.BreakEvent event) {
        if (!enabled) return;
        if (event.getWorld().isRemote) return;
        
        EntityPlayer player = event.getPlayer();
        if (player == null) return;
        
        // Ops/creative can break anywhere
        if (player.capabilities.isCreativeMode || isOperator(player)) return;
        
        BlockPos pos = event.getPos();
        World world = event.getWorld();
        Block block = event.getState().getBlock();
        
        if (!canBreakAt(player, world, pos, block)) {
            event.setCanceled(true);
            
            String message = getRestrictionMessage(player, pos, block);
            player.sendStatusMessage(new TextComponentString(message), true);
        }
    }
    
    @SubscribeEvent(priority = EventPriority.HIGH)
    public static void onRightClickBlock(PlayerInteractEvent.RightClickBlock event) {
        if (!enabled) return;
        if (event.getWorld().isRemote) return;
        
        EntityPlayer player = event.getEntityPlayer();
        if (player == null) return;
        
        // Ops/creative can interact anywhere
        if (player.capabilities.isCreativeMode || isOperator(player)) return;
        
        // Only restrict building-related interactions (like placing)
        if (event.getItemStack().isEmpty()) return;
        
        BlockPos pos = event.getPos();
        World world = event.getWorld();
        
        // Check if this is a building action (placing blocks, etc.)
        if (event.getItemStack().getItem() instanceof net.minecraft.item.ItemBlock) {
            if (!canBuildAt(player, world, pos.offset(event.getFace()))) {
                event.setCanceled(true);
                player.sendStatusMessage(new TextComponentString(
                    "§c[Territory] §7You can only build in claimed territory!"), true);
            }
        }
    }
    
    /**
     * Checks if a player can build at a position.
     */
    public static boolean canBuildAt(EntityPlayer player, World world, BlockPos pos) {
        ChunkPos chunkPos = new ChunkPos(pos);
        WarWorldData data = WarWorldData.get(world);
        String owner = data.getOwner(chunkPos);
        
        // Can always build in own territory (owner is player's UUID)
        String playerUUID = player.getUniqueID().toString();
        if (playerUUID.equals(owner)) return true;
        
        // Also allow if marked as generic "PLAYER"
        if ("PLAYER".equals(owner)) return true;
        
        // Can build in neutral IF it's the starting area (first few chunks)
        if ("NEUTRAL".equals(owner) && isStartingArea(player, pos)) return true;
        
        // Cannot build in rival territory or unclaimed land
        return false;
    }
    
    /**
     * Checks if a player can break a block at a position.
     */
    public static boolean canBreakAt(EntityPlayer player, World world, BlockPos pos, Block block) {
        ChunkPos chunkPos = new ChunkPos(pos);
        WarWorldData data = WarWorldData.get(world);
        String owner = data.getOwner(chunkPos);
        
        // Can always break in own territory (owner is player's UUID)
        String playerUUID = player.getUniqueID().toString();
        if (playerUUID.equals(owner)) return true;
        
        // Also allow if marked as generic "PLAYER"
        if ("PLAYER".equals(owner)) return true;
        
        // Cannot break in rival territory at all
        if ("RIVAL".equals(owner)) return false;
        
        // In unclaimed territory, check class restrictions
        if ("NEUTRAL".equals(owner)) {
            return canBreakInUnclaimed(player, pos, block);
        }
        
        return allowBreakingInUnclaimed;
    }
    
    /**
     * Checks class-based restrictions for breaking blocks in unclaimed land.
     */
    private static boolean canBreakInUnclaimed(EntityPlayer player, BlockPos pos, Block block) {
        String playerClass = getPlayerClass(player);
        String blockName = block.getRegistryName().toString().toLowerCase();
        
        // NOMAD can break ANY block anywhere (mining fatigue 3 naturally limits to soft blocks)
        if ("NOMAD".equalsIgnoreCase(playerClass) || "nomad".equalsIgnoreCase(playerClass)) {
            return true;
        }
        
        // Check if it's a log/tree block
        if (isLogBlock(blockName)) {
            return "LUMBERJACK".equals(playerClass) || "lumberjack".equals(playerClass);
        }
        
        // Check if it's an ore/underground block AND we're underground
        if (isOreBlock(blockName) && pos.getY() < undergroundLevel) {
            return "MINER".equals(playerClass) || "miner".equals(playerClass);
        }
        
        // Surface non-tree blocks can be broken by anyone
        if (pos.getY() >= undergroundLevel && !isLogBlock(blockName)) {
            return true;
        }
        
        return false;
    }
    
    /**
     * Gets the player's class from MultiCharacter mod.
     */
    private static String getPlayerClass(EntityPlayer player) {
        // Try to get class from MultiCharacter
        try {
            // Check capability/NBT for role
            if (player.getEntityData().hasKey("multicharacter_role")) {
                return player.getEntityData().getString("multicharacter_role");
            }
            
            // Direct access to MultiCharacter mod
            co.runed.multicharacter.character.Character activeChar = 
                co.runed.multicharacter.MultiCharacterMod.getCharacterManager().getActiveCharacter(player);
            if (activeChar != null) {
                java.util.List<String> roles = activeChar.getRoles();
                if (roles != null && !roles.isEmpty()) {
                    return roles.get(0); // Return primary role
                }
            }
        } catch (Exception e) {
            // MultiCharacter not installed or error
        }
        
        // Default - no class restrictions
        return "NONE";
    }
    
    private static boolean isLogBlock(String blockName) {
        for (String logType : LOG_BLOCKS) {
            if (blockName.contains(logType)) return true;
        }
        return false;
    }
    
    private static boolean isOreBlock(String blockName) {
        for (String oreType : ORE_BLOCKS) {
            if (blockName.contains(oreType)) return true;
        }
        return false;
    }
    
    private static boolean isOperator(EntityPlayer player) {
        return player.getServer() != null && 
               player.getServer().getPlayerList().getOppedPlayers().getEntry(player.getGameProfile()) != null;
    }
    
    private static boolean isStartingArea(EntityPlayer player, BlockPos pos) {
        // Allow building within 64 blocks of world spawn initially
        BlockPos spawn = player.world.getSpawnPoint();
        double distance = pos.getDistance(spawn.getX(), spawn.getY(), spawn.getZ());
        return distance < 64;
    }
    
    private static String getRestrictionMessage(EntityPlayer player, BlockPos pos, Block block) {
        String blockName = block.getRegistryName().toString().toLowerCase();
        
        if (isLogBlock(blockName)) {
            return "§c[Territory] §7Only Lumberjacks can harvest trees in unclaimed land!";
        }
        
        if (isOreBlock(blockName) && pos.getY() < undergroundLevel) {
            return "§c[Territory] §7Only Miners can mine underground in unclaimed land!";
        }
        
        return "§c[Territory] §7You cannot break blocks outside your borders!";
    }
    
    /**
     * Checks if a chunk is player-owned (by any player).
     */
    public static boolean isPlayerTerritory(World world, BlockPos pos) {
        ChunkPos chunkPos = new ChunkPos(pos);
        WarWorldData data = WarWorldData.get(world);
        String owner = data.getOwner(chunkPos);
        // Player territory is anything that's not NEUTRAL or RIVAL
        return owner != null && !owner.equals("NEUTRAL") && !owner.equals("RIVAL");
    }
    
    /**
     * Checks if a chunk is rival-owned.
     */
    public static boolean isRivalTerritory(World world, BlockPos pos) {
        ChunkPos chunkPos = new ChunkPos(pos);
        WarWorldData data = WarWorldData.get(world);
        return "RIVAL".equals(data.getOwner(chunkPos));
    }
}
