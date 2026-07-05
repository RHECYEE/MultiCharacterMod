package studio.ERM.war.rival;

import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.nbt.NBTTagList;
import net.minecraft.util.EnumFacing;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.ChunkPos;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.World;
import net.minecraftforge.common.util.Constants;
import studio.ERM.EpochRunnerMod;
import studio.ERM.war.world.WarWorldData;

import java.util.*;

/**
 * RIVAL EXPANSION MANAGER
 * 
 * Implements "Directional Branch Growth" (Root Seeker) algorithm:
 * - Expands TOWARD the player (not randomly)
 * - Creates organic branching (lightning-like, not straight lines)
 * - Hardens into military borders when near player territory
 * - Backfills core with density upgrades
 * 
 * The resulting map shows:
 * - Soft civilian sprawl behind the lines
 * - Hardened military shell near the player
 * - Natural encirclement if player ignores expansion
 */
public class RivalExpansionManager {
    
    private static final Random rand = new Random();
    
    // ===== EXPANSION CONFIGURATION =====
    public static int EXPANSION_DISTANCE = 48;           // Distance per expansion step
    public static int BRANCH_ANGLE_JITTER = 35;          // Degrees of randomness
    public static int THREAT_SCAN_RADIUS = 200;          // How far to check for player
    public static int MILITARY_TRIGGER_DISTANCE = 150;   // Distance to player for military
    public static int CANDIDATES_PER_NODE = 3;           // Branch candidates to evaluate
    
    // ===== NODE TYPES =====
    public enum NodeState {
        ACTIVE,     // Can grow further
        TERMINAL,   // Military border, no further growth
        CORE,       // Capital/central node
        UPGRADED    // Backfilled with industry
    }
    
    public enum ZoneType {
        CORE(0, 500),
        INDUSTRIAL_INNER(500, 1500),
        SUBURBAN(1500, 3000),
        FRONTIER(3000, Integer.MAX_VALUE);
        
        public final int minDistance;
        public final int maxDistance;
        
        ZoneType(int min, int max) {
            this.minDistance = min;
            this.maxDistance = max;
        }
        
        public static ZoneType fromDistance(int distance) {
            for (ZoneType zone : values()) {
                if (distance >= zone.minDistance && distance < zone.maxDistance) {
                    return zone;
                }
            }
            return FRONTIER;
        }
    }
    
    // ===== EXPANSION NODE =====
    public static class ExpansionNode {
        public final String id;
        public final BlockPos position;
        public NodeState state;
        public ZoneType zone;
        public String structureType;
        public EnumFacing facing;
        public long createdTick;
        
        public ExpansionNode(BlockPos pos) {
            this.id = "node_" + pos.getX() + "_" + pos.getZ();
            this.position = pos;
            this.state = NodeState.ACTIVE;
            this.zone = ZoneType.FRONTIER;
            this.facing = EnumFacing.NORTH;
        }
        
        /**
         * Returns the chunk position for this node
         */
        public ChunkPos getChunk() {
            return new ChunkPos(position);
        }
        
        /**
         * Returns the structure type string
         */
        public String getStructureType() {
            return structureType != null ? structureType : "";
        }
        
        /**
         * Returns the facing direction
         */
        public EnumFacing getFacing() {
            return facing != null ? facing : EnumFacing.NORTH;
        }
        
        public NBTTagCompound toNBT() {
            NBTTagCompound nbt = new NBTTagCompound();
            nbt.setString("id", id);
            nbt.setInteger("x", position.getX());
            nbt.setInteger("y", position.getY());
            nbt.setInteger("z", position.getZ());
            nbt.setString("state", state.name());
            nbt.setString("zone", zone.name());
            nbt.setString("structure", structureType != null ? structureType : "");
            nbt.setInteger("facing", facing.getHorizontalIndex());
            nbt.setLong("created", createdTick);
            return nbt;
        }
        
        public static ExpansionNode fromNBT(NBTTagCompound nbt) {
            BlockPos pos = new BlockPos(
                nbt.getInteger("x"),
                nbt.getInteger("y"),
                nbt.getInteger("z")
            );
            ExpansionNode node = new ExpansionNode(pos);
            try {
                node.state = NodeState.valueOf(nbt.getString("state"));
                node.zone = ZoneType.valueOf(nbt.getString("zone"));
            } catch (Exception e) {
                node.state = NodeState.ACTIVE;
                node.zone = ZoneType.FRONTIER;
            }
            node.structureType = nbt.getString("structure");
            node.facing = EnumFacing.byHorizontalIndex(nbt.getInteger("facing"));
            node.createdTick = nbt.getLong("created");
            return node;
        }
    }
    
    // ===== STATE =====
    private final String factionId;
    private BlockPos capitalPos;
    private BlockPos playerTargetPos; // Cached player position to grow toward
    
    private final Map<String, ExpansionNode> allNodes = new HashMap<>();
    private final List<ExpansionNode> activeNodes = new ArrayList<>();
    private final Set<ChunkPos> claimedChunks = new HashSet<>();
    
    // ===== CONSTRUCTOR =====
    
    public RivalExpansionManager(String factionId) {
        this.factionId = factionId;
    }
    
    /**
     * Initialize with capital position
     */
    public void initialize(World world, BlockPos capital) {
        this.capitalPos = capital;
        
        // Create core node
        ExpansionNode coreNode = new ExpansionNode(capital);
        coreNode.state = NodeState.CORE;
        coreNode.zone = ZoneType.CORE;
        coreNode.structureType = "CAPITAL";
        coreNode.createdTick = world.getTotalWorldTime();
        
        allNodes.put(coreNode.id, coreNode);
        activeNodes.add(coreNode);
        claimedChunks.add(new ChunkPos(capital));
        
        // Find nearest player for initial direction
        updatePlayerTarget(world);
        
        EpochRunnerMod.logger.info("[EXPANSION] Initialized " + factionId + " at " + capital);
    }
    
    /**
     * Update cached player target position
     */
    public void updatePlayerTarget(World world) {
        EntityPlayer nearest = world.getClosestPlayer(
            capitalPos.getX(), capitalPos.getY(), capitalPos.getZ(), 
            10000, false
        );
        
        if (nearest != null) {
            playerTargetPos = nearest.getPosition();
        } else {
            // Default to world spawn
            playerTargetPos = world.getSpawnPoint();
        }
    }
    
    /**
     * Main tick method - called every world tick
     * Handles periodic expansion logic
     */
    public void tick(World world) {
        if (!isInitialized()) return;
        
        // Only expand periodically (every 5 seconds)
        long tick = world.getTotalWorldTime();
        if (tick % 100 != 0) return;
        
        // Get faction stats for this world
        RivalFactionStats stats = studio.ERM.war.WarSystemIntegration.getFactionStats(world);
        if (stats == null) return;
        
        // Attempt expansion
        expand(world, stats);
    }
    
    // ===== EXPANSION ALGORITHM (ROOT SEEKER) =====
    
    /**
     * Main expansion tick - call this to grow the faction
     * @return List of new nodes created
     */
    public List<ExpansionNode> expand(World world, RivalFactionStats stats) {
        List<ExpansionNode> newNodes = new ArrayList<>();
        
        // Update player target periodically
        if (world.getTotalWorldTime() % 200 == 0) {
            updatePlayerTarget(world);
        }
        
        // Get active nodes that can grow
        List<ExpansionNode> growable = new ArrayList<>();
        for (ExpansionNode node : activeNodes) {
            if (node.state == NodeState.ACTIVE || node.state == NodeState.CORE) {
                growable.add(node);
            }
        }
        
        if (growable.isEmpty()) {
            EpochRunnerMod.logger.info("[EXPANSION] No growable nodes for " + factionId);
            return newNodes;
        }
        
        // Try to expand from each active node
        for (ExpansionNode node : growable) {
            List<BlockPos> candidates = generateCandidates(world, node);
            BlockPos bestCandidate = selectBestCandidate(world, candidates);
            
            if (bestCandidate != null) {
                ExpansionNode newNode = createNode(world, bestCandidate, node, stats);
                if (newNode != null) {
                    newNodes.add(newNode);
                }
            }
        }
        
        // Backfill density in core zones
        backfillDensity(world, stats);
        
        return newNodes;
    }
    
    /**
     * Generate candidate positions for expansion
     * Uses directional bias toward player with angular jitter
     */
    private List<BlockPos> generateCandidates(World world, ExpansionNode source) {
        List<BlockPos> candidates = new ArrayList<>();
        
        if (playerTargetPos == null) return candidates;
        
        // Calculate base vector toward player
        Vec3d toPlayer = new Vec3d(
            playerTargetPos.getX() - source.position.getX(),
            0,
            playerTargetPos.getZ() - source.position.getZ()
        ).normalize();
        
        double baseAngle = Math.atan2(toPlayer.z, toPlayer.x);
        
        // Generate candidates with angular jitter
        for (int i = 0; i < CANDIDATES_PER_NODE; i++) {
            // Add random angle deviation
            double jitter = Math.toRadians((rand.nextDouble() - 0.5) * 2 * BRANCH_ANGLE_JITTER);
            double angle = baseAngle + jitter;
            
            // Add some random distance variation
            int distance = EXPANSION_DISTANCE + rand.nextInt(20) - 10;
            
            int newX = source.position.getX() + (int)(Math.cos(angle) * distance);
            int newZ = source.position.getZ() + (int)(Math.sin(angle) * distance);
            
            // Find ground level
            BlockPos candidate = world.getTopSolidOrLiquidBlock(new BlockPos(newX, 0, newZ));
            
            // Check if valid
            if (isValidExpansionPos(world, candidate)) {
                candidates.add(candidate);
            }
        }
        
        return candidates;
    }
    
    /**
     * Select the best candidate based on:
     * 1. Lowest terrain cost
     * 2. Highest forward progress toward player
     */
    private BlockPos selectBestCandidate(World world, List<BlockPos> candidates) {
        if (candidates.isEmpty()) return null;
        
        BlockPos best = null;
        double bestScore = Double.MIN_VALUE;
        
        for (BlockPos candidate : candidates) {
            double score = evaluateCandidate(world, candidate);
            if (score > bestScore) {
                bestScore = score;
                best = candidate;
            }
        }
        
        return best;
    }
    
    /**
     * Evaluate a candidate position
     */
    private double evaluateCandidate(World world, BlockPos candidate) {
        if (playerTargetPos == null) return 0;
        
        double score = 0;
        
        // Forward progress toward player (higher = better)
        double distFromCapital = capitalPos.getDistance(candidate.getX(), candidate.getY(), candidate.getZ());
        double distToPlayer = playerTargetPos.getDistance(candidate.getX(), candidate.getY(), candidate.getZ());
        double capitalToPlayer = capitalPos.getDistance(playerTargetPos.getX(), playerTargetPos.getY(), playerTargetPos.getZ());
        
        // Reward positions closer to player than capital is
        if (capitalToPlayer > 0) {
            score += (capitalToPlayer - distToPlayer) / capitalToPlayer * 50;
        }
        
        // Terrain cost (water/lava = bad)
        if (world.getBlockState(candidate).getMaterial().isLiquid()) {
            score -= 100;
        }
        
        // Prefer flat terrain
        int heightVariance = 0;
        for (int dx = -2; dx <= 2; dx++) {
            for (int dz = -2; dz <= 2; dz++) {
                BlockPos check = world.getTopSolidOrLiquidBlock(candidate.add(dx, 0, dz));
                heightVariance += Math.abs(check.getY() - candidate.getY());
            }
        }
        score -= heightVariance * 0.5;
        
        // Avoid placing too close to existing nodes
        for (ExpansionNode existing : allNodes.values()) {
            double dist = existing.position.getDistance(candidate.getX(), candidate.getY(), candidate.getZ());
            if (dist < 20) {
                score -= (20 - dist) * 2;
            }
        }
        
        return score;
    }
    
    /**
     * Check if a position is valid for expansion
     */
    private boolean isValidExpansionPos(World world, BlockPos pos) {
        // Not in water/lava
        if (world.getBlockState(pos).getMaterial().isLiquid()) {
            return false;
        }
        
        // Not already claimed
        ChunkPos chunk = new ChunkPos(pos);
        if (claimedChunks.contains(chunk)) {
            return false;
        }
        
        // Not too close to existing node
        for (ExpansionNode node : allNodes.values()) {
            if (node.position.getDistance(pos.getX(), pos.getY(), pos.getZ()) < 30) {
                return false;
            }
        }
        
        return true;
    }
    
    // ===== NODE CREATION & THREAT DETECTION =====
    
    /**
     * Create a new expansion node with threat detection
     */
    private ExpansionNode createNode(World world, BlockPos pos, ExpansionNode parent, RivalFactionStats stats) {
        // Threat scan
        ThreatLevel threat = scanThreat(world, pos);
        
        ExpansionNode node = new ExpansionNode(pos);
        node.createdTick = world.getTotalWorldTime();
        
        // Calculate zone based on distance from capital
        int distFromCapital = (int)capitalPos.getDistance(pos.getX(), pos.getY(), pos.getZ());
        node.zone = ZoneType.fromDistance(distFromCapital);
        
        // Determine structure type and state based on threat
        if (threat == ThreatLevel.HIGH) {
            // Military border - terminal node
            node.state = NodeState.TERMINAL;
            node.structureType = selectMilitaryStructure(stats.getLevel());
            node.facing = calculateFacingToPlayer(pos);
            
            EpochRunnerMod.logger.info("[EXPANSION] Terminal military node at " + pos + 
                " - " + node.structureType);
        } else {
            // Civilian expansion - active node
            node.state = NodeState.ACTIVE;
            node.structureType = selectCivilianStructure(node.zone, stats.getLevel());
            node.facing = EnumFacing.byHorizontalIndex(rand.nextInt(4));
        }
        
        // Register node
        allNodes.put(node.id, node);
        if (node.state == NodeState.ACTIVE) {
            activeNodes.add(node);
        }
        
        // Claim chunk
        ChunkPos chunk = new ChunkPos(pos);
        claimedChunks.add(chunk);
        
        // Update faction stats
        RivalFactionStats.StructureType structType = mapToStructureType(node.structureType);
        if (structType != null) {
            stats.onStructureBuilt(structType);
        }
        
        return node;
    }
    
    /**
     * Threat levels for border detection
     */
    public enum ThreatLevel {
        LOW,      // Safe civilian area
        MEDIUM,   // Some caution
        HIGH      // Military border needed
    }
    
    /**
     * Scan for threats at a position
     */
    private ThreatLevel scanThreat(World world, BlockPos pos) {
        // Check distance to player
        if (playerTargetPos != null) {
            double distToPlayer = pos.getDistance(
                playerTargetPos.getX(), playerTargetPos.getY(), playerTargetPos.getZ()
            );
            
            if (distToPlayer < MILITARY_TRIGGER_DISTANCE) {
                return ThreatLevel.HIGH;
            }
            
            if (distToPlayer < MILITARY_TRIGGER_DISTANCE * 1.5) {
                return ThreatLevel.MEDIUM;
            }
        }
        
        // Check for player claims nearby
        WarWorldData data = WarWorldData.get(world);
        ChunkPos nodeChunk = new ChunkPos(pos);
        
        for (int dx = -3; dx <= 3; dx++) {
            for (int dz = -3; dz <= 3; dz++) {
                ChunkPos check = new ChunkPos(nodeChunk.x + dx, nodeChunk.z + dz);
                String owner = data.getOwner(check);
                if ("PLAYER".equals(owner)) {
                    return ThreatLevel.HIGH;
                }
            }
        }
        
        return ThreatLevel.LOW;
    }
    
    /**
     * Calculate facing toward player for military structures
     */
    private EnumFacing calculateFacingToPlayer(BlockPos pos) {
        if (playerTargetPos == null) return EnumFacing.NORTH;
        
        int dx = playerTargetPos.getX() - pos.getX();
        int dz = playerTargetPos.getZ() - pos.getZ();
        
        if (Math.abs(dx) > Math.abs(dz)) {
            return dx > 0 ? EnumFacing.EAST : EnumFacing.WEST;
        } else {
            return dz > 0 ? EnumFacing.SOUTH : EnumFacing.NORTH;
        }
    }
    
    // ===== STRUCTURE SELECTION =====
    
    private static final String[] MILITARY_STRUCTURES = {
        "FORT", "WATCHTOWER", "BUNKER", "TRENCH", "BARRACKS", "ARTILLERY"
    };
    
    private static final String[][] CIVILIAN_BY_ZONE = {
        // CORE
        {"SKYSCRAPER", "FACTORY", "COOLING_TOWER", "CENTRAL_HUB"},
        // INDUSTRIAL_INNER
        {"SILO", "STORAGE_TANK", "RAIL_DEPOT", "FOUNDRY", "FACTORY"},
        // SUBURBAN
        {"HOUSING", "FARM", "MARKET", "VILLAGE"},
        // FRONTIER
        {"ANTENNA", "RADAR", "OUTPOST", "WATCHTOWER"}
    };
    
    private String selectMilitaryStructure(int level) {
        // Higher levels get better military structures
        int maxIdx = Math.min(MILITARY_STRUCTURES.length - 1, level / 2);
        return MILITARY_STRUCTURES[rand.nextInt(maxIdx + 1)];
    }
    
    private String selectCivilianStructure(ZoneType zone, int level) {
        String[] pool = CIVILIAN_BY_ZONE[zone.ordinal()];
        return pool[rand.nextInt(pool.length)];
    }
    
    private RivalFactionStats.StructureType mapToStructureType(String structure) {
        if (structure == null) return null;
        
        switch (structure.toUpperCase()) {
            case "SKYSCRAPER": return RivalFactionStats.StructureType.SKYSCRAPER;
            case "COOLING_TOWER": return RivalFactionStats.StructureType.COOLING_TOWER;
            case "FACTORY": return RivalFactionStats.StructureType.FACTORY;
            case "FOUNDRY": return RivalFactionStats.StructureType.FACTORY;
            case "SILO": return RivalFactionStats.StructureType.SILO_STORAGE;
            case "STORAGE_TANK": return RivalFactionStats.StructureType.SILO_STORAGE;
            case "ANTENNA": return RivalFactionStats.StructureType.ANTENNA_RADAR;
            case "RADAR": return RivalFactionStats.StructureType.ANTENNA_RADAR;
            case "HOUSING": return RivalFactionStats.StructureType.HOUSING;
            case "VILLAGE": return RivalFactionStats.StructureType.HOUSING;
            case "FARM": return RivalFactionStats.StructureType.FARM;
            case "MARKET": return RivalFactionStats.StructureType.HOUSING;
            case "FORT": return RivalFactionStats.StructureType.MILITARY_FORT;
            case "BUNKER": return RivalFactionStats.StructureType.MILITARY_FORT;
            case "BARRACKS": return RivalFactionStats.StructureType.BARRACKS;
            case "WATCHTOWER": return RivalFactionStats.StructureType.WATCHTOWER;
            default: return null;
        }
    }
    
    // ===== DENSITY BACKFILL =====
    
    /**
     * Backfill density in core zones
     * Called after each expansion to upgrade inner areas
     */
    public void backfillDensity(World world, RivalFactionStats stats) {
        // Only backfill every few expansions
        if (rand.nextFloat() > 0.3f) return;
        
        // Find nodes in core/industrial zones that could be upgraded
        for (ExpansionNode node : allNodes.values()) {
            if (node.state == NodeState.ACTIVE && 
                (node.zone == ZoneType.CORE || node.zone == ZoneType.INDUSTRIAL_INNER)) {
                
                // Check if should upgrade
                if (shouldUpgrade(node, stats.getLevel())) {
                    upgradeNode(node, stats);
                }
            }
        }
    }
    
    private boolean shouldUpgrade(ExpansionNode node, int factionLevel) {
        // Higher chance to upgrade core zones at higher levels
        float chance = 0.1f + (factionLevel * 0.05f);
        if (node.zone == ZoneType.CORE) chance += 0.15f;
        
        return rand.nextFloat() < chance;
    }
    
    private void upgradeNode(ExpansionNode node, RivalFactionStats stats) {
        String oldStructure = node.structureType;
        
        // Upgrade path: Village -> Factory, Factory -> Skyscraper, etc.
        switch (node.structureType) {
            case "HOUSING":
            case "VILLAGE":
                node.structureType = "FACTORY";
                break;
            case "FACTORY":
                node.structureType = "SKYSCRAPER";
                break;
            case "FARM":
                node.structureType = "SILO";
                break;
            case "OUTPOST":
                node.structureType = "COOLING_TOWER";
                break;
        }
        
        if (!oldStructure.equals(node.structureType)) {
            node.state = NodeState.UPGRADED;
            
            // Update stats
            RivalFactionStats.StructureType oldType = mapToStructureType(oldStructure);
            RivalFactionStats.StructureType newType = mapToStructureType(node.structureType);
            
            if (oldType != null) {
                // Don't call destroyed, just swap the contribution
            }
            if (newType != null) {
                stats.onStructureBuilt(newType);
            }
            
            EpochRunnerMod.logger.info("[EXPANSION] Upgraded " + oldStructure + " -> " + 
                node.structureType + " at " + node.position);
        }
    }
    
    // ===== STRUCTURE DESTRUCTION =====
    
    /**
     * Called when player destroys a structure
     */
    public void onStructureDestroyed(BlockPos pos, RivalFactionStats stats) {
        // Find the node at this position
        for (ExpansionNode node : allNodes.values()) {
            if (node.position.getDistance(pos.getX(), pos.getY(), pos.getZ()) < 20) {
                RivalFactionStats.StructureType type = mapToStructureType(node.structureType);
                if (type != null) {
                    stats.onStructureDestroyed(type);
                }
                
                // Remove from active if it was active
                activeNodes.remove(node);
                
                EpochRunnerMod.logger.info("[EXPANSION] Structure destroyed: " + 
                    node.structureType + " at " + pos);
                break;
            }
        }
    }
    
    // ===== QUERIES =====
    
    public BlockPos getCapitalPos() { return capitalPos; }
    public BlockPos getCapitalPosition() { return capitalPos; } // alias
    public int getNodeCount() { return allNodes.size(); }
    public int getActiveNodeCount() { return activeNodes.size(); }
    public Set<ChunkPos> getClaimedChunks() { return claimedChunks; }
    public int getClaimedChunkCount() { return claimedChunks.size(); }
    
    public List<ExpansionNode> getActiveNodes() {
        return new ArrayList<>(activeNodes);
    }
    
    public ExpansionNode getNodeAt(BlockPos pos) {
        for (ExpansionNode node : allNodes.values()) {
            if (node.position.getDistance(pos.getX(), pos.getY(), pos.getZ()) < 10) {
                return node;
            }
        }
        return null;
    }
    
    public List<ExpansionNode> getNodesInZone(ZoneType zone) {
        List<ExpansionNode> result = new ArrayList<>();
        for (ExpansionNode node : allNodes.values()) {
            if (node.zone == zone) {
                result.add(node);
            }
        }
        return result;
    }
    
    public List<ExpansionNode> getMilitaryNodes() {
        List<ExpansionNode> result = new ArrayList<>();
        for (ExpansionNode node : allNodes.values()) {
            if (node.state == NodeState.TERMINAL) {
                result.add(node);
            }
        }
        return result;
    }
    
    // ===== NBT =====
    
    public NBTTagCompound writeToNBT(NBTTagCompound nbt) {
        nbt.setString("factionId", factionId);
        
        if (capitalPos != null) {
            nbt.setInteger("capitalX", capitalPos.getX());
            nbt.setInteger("capitalY", capitalPos.getY());
            nbt.setInteger("capitalZ", capitalPos.getZ());
        }
        
        if (playerTargetPos != null) {
            nbt.setInteger("targetX", playerTargetPos.getX());
            nbt.setInteger("targetY", playerTargetPos.getY());
            nbt.setInteger("targetZ", playerTargetPos.getZ());
        }
        
        // Save nodes
        NBTTagList nodeList = new NBTTagList();
        for (ExpansionNode node : allNodes.values()) {
            nodeList.appendTag(node.toNBT());
        }
        nbt.setTag("nodes", nodeList);
        
        // Save claimed chunks
        int[] chunkData = new int[claimedChunks.size() * 2];
        int i = 0;
        for (ChunkPos chunk : claimedChunks) {
            chunkData[i++] = chunk.x;
            chunkData[i++] = chunk.z;
        }
        nbt.setIntArray("chunks", chunkData);
        
        return nbt;
    }
    
    public void readFromNBT(NBTTagCompound nbt) {
        if (nbt.hasKey("capitalX")) {
            capitalPos = new BlockPos(
                nbt.getInteger("capitalX"),
                nbt.getInteger("capitalY"),
                nbt.getInteger("capitalZ")
            );
        }
        
        if (nbt.hasKey("targetX")) {
            playerTargetPos = new BlockPos(
                nbt.getInteger("targetX"),
                nbt.getInteger("targetY"),
                nbt.getInteger("targetZ")
            );
        }
        
        // Load nodes
        allNodes.clear();
        activeNodes.clear();
        if (nbt.hasKey("nodes")) {
            NBTTagList nodeList = nbt.getTagList("nodes", Constants.NBT.TAG_COMPOUND);
            for (int i = 0; i < nodeList.tagCount(); i++) {
                ExpansionNode node = ExpansionNode.fromNBT(nodeList.getCompoundTagAt(i));
                allNodes.put(node.id, node);
                if (node.state == NodeState.ACTIVE || node.state == NodeState.CORE) {
                    activeNodes.add(node);
                }
            }
        }
        
        // Load claimed chunks
        claimedChunks.clear();
        if (nbt.hasKey("chunks")) {
            int[] chunkData = nbt.getIntArray("chunks");
            for (int i = 0; i < chunkData.length - 1; i += 2) {
                claimedChunks.add(new ChunkPos(chunkData[i], chunkData[i + 1]));
            }
        }
    }
    
    // ===== QUERY METHODS =====
    
    /**
     * Check if the manager is initialized
     */
    public boolean isInitialized() {
        return capitalPos != null;
    }
    
    /**
     * Handle node destruction
     */
    public void onNodeDestroyed(BlockPos pos) {
        String nodeId = "node_" + pos.getX() + "_" + pos.getZ();
        ExpansionNode node = allNodes.get(nodeId);
        if (node != null) {
            node.state = NodeState.TERMINAL; // Mark as destroyed/terminal
            activeNodes.remove(node);
            EpochRunnerMod.logger.info("[EXPANSION] Node destroyed at " + pos);
        }
    }
    
    /**
     * Get all nodes (alias for iteration)
     */
    public Collection<ExpansionNode> getAllNodesCollection() {
        return allNodes.values();
    }
}
