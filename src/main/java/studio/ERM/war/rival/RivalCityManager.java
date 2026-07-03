package studio.ERM.war.rival;

import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.util.EnumFacing;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.ChunkPos;
import net.minecraft.util.text.TextComponentString;
import net.minecraft.util.text.TextFormatting;
import net.minecraft.world.World;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import net.minecraftforge.fml.common.gameevent.TickEvent;
import studio.ERM.EpochRunnerMod;
// External config uses FQN: studio.ERM.config.RivalCityConfig
// Unqualified RivalCityConfig refers to studio.ERM.war.rival.RivalCityConfig
import studio.ERM.war.WarMapOverlay;
import studio.ERM.war.world.WarWorldData;
import studio.ERM.war.rival.ProceduralBuildingGenerator;
import studio.ERM.war.rival.RivalExpansionManager;
import studio.ERM.war.rival.RivalFactionStats;

import java.util.*;

/**
 * RivalCityManager - Main coordinator for the rival city system.
 * 
 * This class has been refactored from a monolithic 3000+ line file into:
 * - RivalCityManager (this file) - Event handlers, public API, initialization
 * - RivalCityState - State/data classes and NBT serialization
 * - RivalCityConfig - Configuration constants
 * - RivalCityGenerator - City generation logic
 * - RivalCitySpawner - Entity spawning logic
 */
@Mod.EventBusSubscriber(modid = EpochRunnerMod.MODID)
public class RivalCityManager {

    // =====================================================================
    // GLOBAL STATE
    // =====================================================================

    // Legacy static fields for backwards compatibility
    private static BlockPos rivalCityCenter = null;
    // TESTING DEFAULT: start at the max war level (10) so sieges immediately field modern troops
    // (Flan-gun infantry at L8-10 per WarWeaponsConfig), hostile CAS, and the heavier waves. An
    // actual rival city still overrides this with its real level once one is generated/loaded.
    private static int rivalCityLevel = 9; // default siege level for tuning (L10 config is locked in; now tuning L9)
    private static int rivalCitySize = 48;
    private static boolean isInitialized = false;
    private static boolean isGenerating = false;
    private static int currentRingRadius = 0;

    // Multi-city support
    private static final Map<Integer, Map<String, RivalCityState>> rivalCitiesByDim = new HashMap<>();

    private static final Random rand = new Random();

    // =====================================================================
    // CITY ACCESS METHODS
    // =====================================================================

    public static Map<String, RivalCityState> getDimensionCities(World world) {
        return rivalCitiesByDim.computeIfAbsent(world.provider.getDimension(), d -> new HashMap<>());
    }

    public static RivalCityState getCity(World world, String ownerKey) {
        return getDimensionCities(world).get(ownerKey);
    }

    public static RivalCityState getOrCreateCity(World world, String ownerKey) {
        Map<String, RivalCityState> map = getDimensionCities(world);
        RivalCityState state = map.get(ownerKey);
        if (state == null) {
            state = new RivalCityState(ownerKey);
            map.put(ownerKey, state);
        }
        return state;
    }

    public static RivalCityState getAnyCity(World world) {
        Map<String, RivalCityState> map = getDimensionCities(world);
        if (map.isEmpty()) return null;
        return map.values().iterator().next();
    }

    public static RivalCityState getNearestCity(World world, BlockPos pos) {
        Map<String, RivalCityState> map = getDimensionCities(world);
        RivalCityState best = null;
        double bestDist = Double.MAX_VALUE;
        for (RivalCityState state : map.values()) {
            if (state.center == null) continue;
            double d = state.center.distanceSq(pos);
            if (d < bestDist) {
                bestDist = d;
                best = state;
            }
        }
        return best;
    }

    public static String getOwnerKey(EntityPlayer player) {
        return player.getUniqueID().toString();
    }

    // =====================================================================
    // EVENT HANDLERS
    // =====================================================================

    @SubscribeEvent
    public static void onBlockBreak(net.minecraftforge.event.world.BlockEvent.BreakEvent e) {
        if (e.getWorld().isRemote) return;
        World world = (World) e.getWorld();
        BlockPos pos = e.getPos();
        handleStructureDamageAt(world, pos);
    }

    @SubscribeEvent
    public static void onExplosion(net.minecraftforge.event.world.ExplosionEvent.Detonate e) {
        if (e.getWorld().isRemote) return;
        World world = e.getWorld();
        for (BlockPos p : e.getAffectedBlocks()) {
            handleStructureDamageAt(world, p);
        }
    }

    private static void handleStructureDamageAt(World world, BlockPos hitPos) {
        Map<String, RivalCityState> map = getDimensionCities(world);
        if (map.isEmpty()) return;

        long now = world.getTotalWorldTime();

        for (RivalCityState state : map.values()) {
            if (state.center == null) continue;

            if (state.center.distanceSq(hitPos) > (state.size * state.size * 4.0)) continue;

            for (Map.Entry<BlockPos, RivalCityState.TemplateFootprint> e : state.templateFootprints.entrySet()) {
                BlockPos anchor = e.getKey();
                RivalCityState.TemplateFootprint fp = e.getValue();
                if (fp == null || fp.destroyed) continue;

                if (now - fp.lastCheckTick < 40) continue;

                int ax = anchor.getX();
                int az = anchor.getZ();
                int hx = hitPos.getX();
                int hz = hitPos.getZ();

                if (hx < ax || hx >= ax + fp.width) continue;
                if (hz < az || hz >= az + fp.depth) continue;

                fp.lastCheckTick = now;

                BlockPos base = world.getTopSolidOrLiquidBlock(anchor);
                int[] cur = RivalCityGenerator.sampleFootprint(world, base, fp.width, fp.depth, fp.height);

                if (fp.baseFilled > 0 && cur[1] < (int) (fp.baseFilled * RivalCityConfig.DESTROYED_THRESHOLD)) {
                    fp.destroyed = true;

                    state.destroyedPlots.putIfAbsent(anchor.toLong(), now);
                    RivalFactionStats.StructureType st = mapFootprintType(fp.structureType);
                    if (st != null) {
                        state.stats.onStructureDestroyed(st);
                    }
                }
            }
        }
    }

    private static RivalFactionStats.StructureType mapFootprintType(String s) {
        if (s == null) return null;
        try {
            return RivalFactionStats.StructureType.valueOf(s.toUpperCase());
        } catch (Throwable ignored) {
            return null;
        }
    }

    @SubscribeEvent
    public static void onWorldTick(TickEvent.WorldTickEvent event) {
        if (event.phase != TickEvent.Phase.END) return;
        if (event.world.isRemote) return;

        World world = event.world;
        Map<String, RivalCityState> map = getDimensionCities(world);
        if (map.isEmpty()) return;

        long time = world.getTotalWorldTime();

        for (RivalCityState state : map.values()) {
            if (state.center == null) continue;
            if (!world.isBlockLoaded(state.center, false)) continue;

            state.stats.updateRecovery(time);

            if (time - state.lastNPCSpawnTick >= RivalCityConfig.npcSpawnIntervalTicks) {
                state.lastNPCSpawnTick = time;

                if (state.stats.getPopulation() > 0) {
                    RivalCitySpawner.spawnRivalNPCs(world, state);
                }

                processRedevelopmentQueue(world, state, time);

                if (studio.ERM.config.RivalCityConfig.enableFlansVehicles
                        && state.stats.willCommitVehicles()
                        && state.level >= studio.ERM.config.RivalCityConfig.flansVehiclesStartLevel) {
                    try {
                        int vehicleCount = Math.min(
                                studio.ERM.config.RivalCityConfig.flansVehiclesPassive,
                                state.stats.getVehicleTier()
                        );
                        RivalCitySpawner.spawnRivalFlansVehicles(world, state,
                                RivalCitySpawner.getBoostedFlansVehicleCountForLevel(state.level, vehicleCount));
                    } catch (Throwable t) {
                        EpochRunnerMod.logger.error("[RIVAL] Passive Flan spawn error.", t);
                    }
                }
            }
        }
    }

    // =====================================================================
    // INITIALIZATION
    // =====================================================================

    public static void initializeRivalCity(EntityPlayer player) {
        if (player == null) return;
        World world = player.world;
        if (world.isRemote) return;
        if (isGenerating) return;

        isGenerating = true;
        try {
            String ownerKey = getOwnerKey(player);
            RivalCityState state = getOrCreateCity(world, ownerKey);

            double angle = rand.nextDouble() * Math.PI * 2.0;
            int distance = RivalCityConfig.minSpawnDistance + 
                    rand.nextInt(Math.max(1, RivalCityConfig.maxSpawnDistance - RivalCityConfig.minSpawnDistance));

            int x = (int) (player.posX + Math.cos(angle) * distance);
            int z = (int) (player.posZ + Math.sin(angle) * distance);
            int y = world.getTopSolidOrLiquidBlock(new BlockPos(x, 0, z)).getY();

            state.center = new BlockPos(x, y, z);
            state.level = 1;
            state.size = 64;
            state.currentRingRadius = 0;
            state.clearStructures();

            state.stats.initializeForLevel(1);
            state.expansionManager.initialize(world, state.center);

            updateLegacyFields(state);

            RivalCityGenerator.generateCityLevel(world, state, 1);
            claimChunksForRival(world, state);

            WarMapOverlay.setRivalCityMarker(state.center, state.level);

            player.sendMessage(new TextComponentString(
                    TextFormatting.RED + "☠ A rival settlement has been spotted " +
                            TextFormatting.YELLOW + distance + " blocks " +
                            TextFormatting.RED + "away!"
            ));
        } finally {
            isGenerating = false;
        }
    }

    /**
     * Seed a rival city at a randomized distance from the player (Rival City module).
     *
     * Fixes two long-standing /war rival city bugs:
     *   1. It USED to drop the city on the player's head (center = player position), so the
     *      "spawned where I was standing" complaint. We now offset by min/maxSpawnDistance.
     *   2. It never registered chunk ownership, so rival land never appeared on the tactical
     *      war map. We now call claimChunksForRival() which sets each chunk's owner to RIVAL,
     *      so getAllChunkOwners() (and the map overlay) actually see the territory.
     *
     * Returns the chosen center, or null on failure.
     */
    public static BlockPos seedCityAtDistance(World world, EntityPlayer player, int level) {
        if (world == null || player == null || world.isRemote) return null;
        if (isGenerating) return null;

        isGenerating = true;
        try {
            // Command path tracks the city under the shared "RIVAL" key, matching the
            // pre-existing /war rival city / status lookups (getNearestCity, etc.).
            RivalCityState state = getOrCreateCity(world, "RIVAL");

            double angle = rand.nextDouble() * Math.PI * 2.0;
            int span = Math.max(1, RivalCityConfig.maxSpawnDistance - RivalCityConfig.minSpawnDistance);
            int distance = RivalCityConfig.minSpawnDistance + rand.nextInt(span);
            int x = (int) (player.posX + Math.cos(angle) * distance);
            int z = (int) (player.posZ + Math.sin(angle) * distance);
            int y = world.getTopSolidOrLiquidBlock(new BlockPos(x, 0, z)).getY();

            state.center = new BlockPos(x, y, z);
            state.level = Math.max(1, level);
            state.size = Math.max(64, state.size);
            state.currentRingRadius = 0;
            state.clearStructures();

            state.stats.initializeForLevel(state.level);
            state.expansionManager.initialize(world, state.center);
            updateLegacyFields(state);

            RivalCityGenerator.generateCityLevel(world, state, state.level);
            claimChunksForRival(world, state);   // registers RIVAL chunk ownership -> shows on map
            RivalCitySpawner.spawnRivalNPCs(world, state);
            WarMapOverlay.setRivalCityMarker(state.center, state.level);

            return state.center;
        } finally {
            isGenerating = false;
        }
    }

    // =====================================================================
    // GROW / LEVEL COMMANDS
    // =====================================================================

    /**
     * Grow city by expanding outward (keeps current level).
     */
    public static void growCity(World world, EntityPlayer player) {
        if (world == null || player == null) return;
        if (world.isRemote) return;
        if (isGenerating) return;

        String ownerKey = getOwnerKey(player);
        RivalCityState state = getCity(world, ownerKey);
        if (state == null || state.center == null) return;

        isGenerating = true;
        try {
            // Root Seeker expansion
            if (!state.expansionManager.isInitialized()) {
                state.expansionManager.initialize(world, state.center);
            }

            int steps = Math.max(1, 1 + (state.level / 3));
            int builtNodes = 0;

            for (int i = 0; i < steps; i++) {
                List<RivalExpansionManager.ExpansionNode> newNodes =
                        state.expansionManager.expand(world, state.stats);

                if (newNodes == null || newNodes.isEmpty()) continue;

                for (RivalExpansionManager.ExpansionNode node : newNodes) {
                    if (node == null || node.position == null) continue;

                    ChunkPos cp = new ChunkPos(node.position);
                    WarWorldData data = WarWorldData.get(world);
                    data.setOwner(cp, "RIVAL");
                    data.markDirty();
                    WarMapOverlay.updateChunkTerritory(cp, "RIVAL");

                    RivalCitySpawner.generateBuildingAtExpansionNode(world, state, node);
                    builtNodes++;
                }
            }

            try {
                state.expansionManager.backfillDensity(world, state.stats);
            } catch (Throwable ignored) {}

            RivalCityGenerator.generateCityLevel(world, state, state.level);
            claimChunksForRival(world, state);

            if (studio.ERM.config.RivalCityConfig.enableFlansVehicles && state.level >= studio.ERM.config.RivalCityConfig.flansVehiclesStartLevel) {
                try {
                    int count = Math.min(studio.ERM.config.RivalCityConfig.flansVehiclesPerGrowth, Math.max(1, state.stats.getVehicleTier() + 1));
                    RivalCitySpawner.spawnRivalFlansVehicles(world, state, 
                            RivalCitySpawner.getBoostedFlansVehicleCountForLevel(state.level, count));
                } catch (Throwable t) {
                    EpochRunnerMod.logger.error("[RIVAL] Flans vehicle spawn error.", t);
                }
            }

            updateLegacyFields(state);
            WarMapOverlay.setRivalCityMarker(state.center, state.level);

            player.sendMessage(new TextComponentString(
                    TextFormatting.YELLOW + "Rival grew (Level " + state.level + "): " +
                            TextFormatting.RED + builtNodes + TextFormatting.YELLOW + " node(s) + outward growth."
            ));
            showStatsToPlayer(player, state);

        } finally {
            isGenerating = false;
        }
    }

    /**
     * Set city to a specific level.
     */
    public static void setLevel(World world, EntityPlayer player, int targetLevel) {
        if (world == null || player == null) return;
        if (world.isRemote) return;
        if (isGenerating) return;

        String ownerKey = getOwnerKey(player);
        RivalCityState state = getCity(world, ownerKey);
        if (state == null || state.center == null) {
            player.sendMessage(new TextComponentString(
                    TextFormatting.RED + "No rival city exists. Use /war rival spawn first."));
            return;
        }

        targetLevel = Math.max(1, Math.min(RivalCityConfig.maxRivalLevel, targetLevel));

        isGenerating = true;
        try {
            int oldLevel = state.level;

            if (targetLevel > oldLevel) {
                player.sendMessage(new TextComponentString(
                        TextFormatting.YELLOW + "Growing city from level " + oldLevel + " to " + targetLevel + "..."));

                while (state.level < targetLevel) {
                    state.level++;
                    state.size += 48;
                    state.stats.levelUp();
                    RivalCityConfig.applyDensityByLevel(state);
                    RivalCityGenerator.generateCityLevel(world, state, state.level);
                }
            } else if (targetLevel < oldLevel) {
                player.sendMessage(new TextComponentString(
                        TextFormatting.YELLOW + "Reducing city level from " + oldLevel + " to " + targetLevel));

                state.level = targetLevel;
                state.size = 64 + (targetLevel - 1) * 48;
                state.stats.initializeForLevel(targetLevel);
            }

            updateLegacyFields(state);
            claimChunksForRival(world, state);
            WarMapOverlay.setRivalCityMarker(state.center, state.level);

            player.sendMessage(new TextComponentString(
                    TextFormatting.GREEN + "Rival city is now level " +
                            TextFormatting.YELLOW + state.level));

            showStatsToPlayer(player, state);

        } finally {
            isGenerating = false;
        }
    }

    /**
     * Backwards-compatible API.
     */
    public static void setRivalLevel(World world, EntityPlayer player, int targetLevel) {
        setLevel(world, player, targetLevel);
    }

    // =====================================================================
    // STATUS DISPLAY
    // =====================================================================

    /**
     * Print rival status to player.
     */
    public static void printRivalStatus(World world, EntityPlayer player) {
        if (world == null || player == null) return;
        String ownerKey = getOwnerKey(player);
        RivalCityState state = getCity(world, ownerKey);
        if (state == null || state.center == null) {
            player.sendMessage(new TextComponentString(TextFormatting.RED + "No rival city exists. Use /war rival spawn first."));
            return;
        }

        player.sendMessage(new TextComponentString(TextFormatting.GOLD + "=== Rival City ==="));
        player.sendMessage(new TextComponentString(TextFormatting.GRAY + "Center: " + TextFormatting.WHITE + 
                state.center.getX() + ", " + state.center.getY() + ", " + state.center.getZ()));
        player.sendMessage(new TextComponentString(TextFormatting.GRAY + "Level: " + TextFormatting.YELLOW + state.level));
        player.sendMessage(new TextComponentString(TextFormatting.GRAY + "Density: " + TextFormatting.WHITE + 
                "plot=" + state.gridPlotSize + " road=" + state.gridRoadWidth + " spacing=" + state.gridSpacing));
        
        try {
            String[] lines = state.stats.getStatusLines();
            for (String s : lines) {
                player.sendMessage(new TextComponentString(s));
            }
        } catch (Throwable t) {
            player.sendMessage(new TextComponentString(TextFormatting.RED + "Stats unavailable: " + t.getClass().getSimpleName()));
        }

        if (state.lastKnownPlayerPos != null) {
            player.sendMessage(new TextComponentString(TextFormatting.GRAY + "Frontier target: " + TextFormatting.WHITE +
                    state.lastKnownPlayerPos.getX() + ", " + state.lastKnownPlayerPos.getY() + ", " + state.lastKnownPlayerPos.getZ()));
        }

        ensureGarrisonAnchors(world, state);
        if (!state.garrisonAnchors.isEmpty()) {
            player.sendMessage(new TextComponentString(TextFormatting.GRAY + "Garrisons: " + TextFormatting.WHITE + state.garrisonAnchors.size()));
            for (int i = 0; i < Math.min(3, state.garrisonAnchors.size()); i++) {
                BlockPos a = state.garrisonAnchors.get(i).toApproxWorldPos(state);
                player.sendMessage(new TextComponentString(TextFormatting.DARK_RED + " - " + a.getX() + ", " + a.getY() + ", " + a.getZ()));
            }
        }
    }

    private static void showStatsToPlayer(EntityPlayer player, RivalCityState state) {
        String[] statusLines = state.stats.getStatusLines();
        for (String line : statusLines) {
            player.sendMessage(new TextComponentString(line));
        }
    }

    // =====================================================================
    // RESET
    // =====================================================================

    public static void reset() {
        rivalCitiesByDim.clear();
        rivalCityCenter = null;
        rivalCityLevel = 9; // testing default (see field declaration)
        rivalCitySize = 48;
        currentRingRadius = 0;
        isInitialized = false;
        isGenerating = false;
        WarMapOverlay.removeMarker("rival_city");
    }

    private static void updateLegacyFields(RivalCityState state) {
        rivalCityCenter = state.center;
        rivalCityLevel = state.level;
        rivalCitySize = state.size;
        currentRingRadius = state.currentRingRadius;
        isInitialized = true;
    }

    // =====================================================================
    // GARRISON ANCHORS
    // =====================================================================

    private static void ensureGarrisonAnchors(World world, RivalCityState state) {
        if (world == null || state == null || state.center == null) return;

        long now = world.getTotalWorldTime();
        if (now - state.lastAnchorRefreshTick < 100) return;
        state.lastAnchorRefreshTick = now;

        int desired = getDesiredGarrisonAnchorCountForLevel(state.level);

        if (state.occupiedGridCells == null || state.occupiedGridCells.isEmpty()) {
            state.garrisonAnchors.clear();
            return;
        }

        double dxp = 1.0;
        double dzp = 0.0;
        if (state.lastKnownPlayerPos != null) {
            dxp = state.lastKnownPlayerPos.getX() - state.center.getX();
            dzp = state.lastKnownPlayerPos.getZ() - state.center.getZ();
            double mag = Math.sqrt(dxp * dxp + dzp * dzp);
            if (mag > 0.0001) {
                dxp /= mag;
                dzp /= mag;
            } else {
                dxp = 1.0;
                dzp = 0.0;
            }
        }

        List<Long> candidates = new ArrayList<>(state.occupiedGridCells);
        int maxSample = Math.min(candidates.size(), 600);
        if (candidates.size() > maxSample) {
            Collections.shuffle(candidates, rand);
            candidates = candidates.subList(0, maxSample);
        }

        class ScoredAnchor {
            final RivalCityState.GarrisonAnchor a;
            final double score;
            ScoredAnchor(RivalCityState.GarrisonAnchor a, double score) { this.a = a; this.score = score; }
        }

        final double fdxp = dxp;
        final double fdzp = dzp;
        
        List<ScoredAnchor> scored = new ArrayList<>();
        for (Long packed : candidates) {
            if (packed == null) continue;
            int gx = RivalCityGenerator.unpackGridX(packed);
            int gz = RivalCityGenerator.unpackGridZ(packed);

            if (gx == 0 && gz == 0) continue;

            int spacing = Math.max(8, state.gridSpacing);
            double wx = state.center.getX() + (gx * (double) spacing);
            double wz = state.center.getZ() + (gz * (double) spacing);

            double dx = wx - state.center.getX();
            double dz = wz - state.center.getZ();
            double mag = Math.sqrt(dx * dx + dz * dz);
            if (mag < 6.0) continue;

            double nx = dx / mag;
            double nz = dz / mag;

            double dot = nx * fdxp + nz * fdzp;
            double distScore = Math.min(1.0, mag / Math.max(32.0, state.size * 0.75));
            double score = (dot * 1.35) + (distScore * 0.75);

            if (state.destroyedPlots != null && !state.destroyedPlots.isEmpty()) {
                long k = (((long) ((int) wx)) << 32) ^ (((int) wz) & 0xFFFFFFFFL);
                if (state.destroyedPlots.containsKey(k)) score -= 0.25;
            }

            scored.add(new ScoredAnchor(new RivalCityState.GarrisonAnchor(gx, gz), score));
        }

        if (scored.isEmpty()) {
            state.garrisonAnchors.clear();
            return;
        }

        scored.sort((a, b) -> Double.compare(b.score, a.score));

        List<RivalCityState.GarrisonAnchor> next = new ArrayList<>();
        int minGridSeparation = Math.max(2, 2 + (state.level / 4));

        for (ScoredAnchor sa : scored) {
            if (next.size() >= desired) break;

            boolean tooClose = false;
            for (RivalCityState.GarrisonAnchor existing : next) {
                int dx = existing.gx - sa.a.gx;
                int dz = existing.gz - sa.a.gz;
                if ((dx * dx + dz * dz) < (minGridSeparation * minGridSeparation)) {
                    tooClose = true;
                    break;
                }
            }
            if (tooClose) continue;

            next.add(sa.a);
        }

        int i = 0;
        while (next.size() < desired && i < scored.size()) {
            RivalCityState.GarrisonAnchor a = scored.get(i).a;
            boolean exists = false;
            for (RivalCityState.GarrisonAnchor e : next) {
                if (e.gx == a.gx && e.gz == a.gz) { exists = true; break; }
            }
            if (!exists) next.add(a);
            i++;
        }

        state.garrisonAnchors.clear();
        state.garrisonAnchors.addAll(next);
    }

    private static int getDesiredGarrisonAnchorCountForLevel(int level) {
        int lvl = Math.max(1, Math.min(10, level));
        try {
            studio.ERM.war.config.WarLevelsConfig.RivalCityConfig cfg = 
                    studio.ERM.war.config.WarLevelsConfig.getRivalCityConfig(lvl);
            if (cfg != null) {
                try {
                    java.lang.reflect.Field f = cfg.getClass().getField("garrisonAnchorCount");
                    Object v = f.get(cfg);
                    if (v instanceof Integer) {
                        int n = (Integer) v;
                        if (n > 0) return Math.min(16, n);
                    }
                } catch (Throwable ignored) {}
                int guards = cfg.guardCount;
                int derived = Math.max(1, Math.min(16, 1 + (guards / 5)));
                return derived;
            }
        } catch (Throwable ignored) {}

        return Math.max(1, Math.min(10, 1 + (lvl / 3)));
    }

    // =====================================================================
    // REDEVELOPMENT QUEUE
    // =====================================================================

    private static void processRedevelopmentQueue(World world, RivalCityState state, long time) {
        if (state == null) return;
        if (state.destroyedPlots == null || state.destroyedPlots.isEmpty()) return;

        int budget = 1;
        if (state.level >= 6) budget = 2;
        if (state.level >= 9) budget = 3;

        Iterator<Map.Entry<Long, Long>> it = state.destroyedPlots.entrySet().iterator();
        while (it.hasNext() && budget > 0) {
            Map.Entry<Long, Long> e = it.next();
            long anchorLong = e.getKey();
            long firstSeen = e.getValue() != null ? e.getValue() : 0L;

            if (time - firstSeen < RivalCityConfig.REDEVELOP_COOLDOWN_TICKS) continue;

            BlockPos anchor = BlockPos.fromLong(anchorLong);
            if (!world.isBlockLoaded(anchor, false)) continue;

            RivalCityState.TemplateFootprint old = state.templateFootprints.get(anchor);
            if (old == null) {
                it.remove();
                continue;
            }

            try {
                BlockPos ground = world.getTopSolidOrLiquidBlock(anchor);
                if (ground == null) ground = anchor;

                RivalCityGenerator.evaluateAndPrepareSite(world, ground, old.width, old.depth);

                EnumFacing facing = EnumFacing.Plane.HORIZONTAL.random(world.rand);

                String structType = (old.structureType != null && !old.structureType.trim().isEmpty())
                        ? old.structureType
                        : "HOUSING";

                int pW = Math.max(6, old.width);
                int pD = Math.max(6, old.depth);
                int pH = Math.max(8, old.height);

                ProceduralBuildingGenerator.generateStructure(world, ground, structType, state.level, facing);

                int[] sample = RivalCityGenerator.sampleFootprint(world, ground, pW, pD, pH);
                RivalCityState.TemplateFootprint fp = new RivalCityState.TemplateFootprint(pW, pD, pH, sample[0], sample[1], structType);
                state.templateFootprints.put(anchor, fp);

                budget--;
                it.remove();
            } catch (Throwable ignored) {}
        }
    }

    // =====================================================================
    // TERRITORY
    // =====================================================================

    private static void claimChunksForRival(World world, RivalCityState state) {
        if (state.center == null) return;

        WarWorldData data = WarWorldData.get(world);
        ChunkPos centerChunk = new ChunkPos(state.center);
        int chunkRadius = state.size / 16 + 2;

        for (int cx = -chunkRadius; cx <= chunkRadius; cx++) {
            for (int cz = -chunkRadius; cz <= chunkRadius; cz++) {
                ChunkPos pos = new ChunkPos(centerChunk.x + cx, centerChunk.z + cz);
                if ("NEUTRAL".equals(data.getOwner(pos))) {
                    data.setOwner(pos, RivalCityState.RIVAL_FACTION_NAME);
                    WarMapOverlay.updateChunkTerritory(pos, RivalCityState.RIVAL_FACTION_NAME);
                }
            }
        }
    }

    // =====================================================================
    // PUBLIC API
    // =====================================================================

    public static int getRivalCitySize() { return rivalCitySize; }
    public static BlockPos getRivalCityCenter() { return rivalCityCenter; }
    public static int getRivalCityLevel() { return rivalCityLevel; }
    public static boolean isInitialized() { return isInitialized; }

    /**
     * Get stats for the player's rival city.
     */
    public static RivalFactionStats getStats(World world, EntityPlayer player) {
        RivalCityState state = getCity(world, getOwnerKey(player));
        return state != null ? state.stats : null;
    }

    /**
     * Get expansion manager for the player's rival city.
     */
    public static RivalExpansionManager getExpansionManager(World world, EntityPlayer player) {
        RivalCityState state = getCity(world, getOwnerKey(player));
        return state != null ? state.expansionManager : null;
    }

    public static void triggerRaid(World world, EntityPlayer player) {
        if (world == null || world.isRemote) return;

        RivalCityState state = null;
        if (player != null) {
            state = getCity(world, getOwnerKey(player));
            if (state == null && player.getPosition() != null) {
                state = getNearestCity(world, player.getPosition());
            }
        }
        if (state == null) state = getAnyCity(world);
        if (state == null || state.center == null) return;

        try {
            if (!state.stats.canInitiateBattle()) {
                if (player != null) {
                    player.sendMessage(new TextComponentString(
                            TextFormatting.YELLOW + "The rival city is too weakened to raid..."));
                }
                return;
            }

            int old = RivalCityConfig.npcsPerLevel;
            RivalCityConfig.npcsPerLevel = Math.max(RivalCityConfig.npcsPerLevel, 6);
            for (int i = 0; i < 3; i++) RivalCitySpawner.spawnRivalNPCs(world, state);
            RivalCityConfig.npcsPerLevel = old;

            if (studio.ERM.config.RivalCityConfig.enableFlansVehicles && state.stats.willCommitVehicles() &&
                    state.level >= studio.ERM.config.RivalCityConfig.flansVehiclesStartLevel) {
                RivalCitySpawner.spawnRivalFlansVehicles(world, state,
                        RivalCitySpawner.getBoostedFlansVehicleCountForLevel(state.level,
                                Math.max(2, studio.ERM.config.RivalCityConfig.flansVehiclesPerGrowth)));
            }

            state.stats.consumeForRaid(state.stats.getReinforcementWaveSize(), 0);

            if (player != null) {
                player.sendMessage(new TextComponentString(
                        TextFormatting.DARK_RED + "☠ The rival city launches a raid party..."));
            }
        } catch (Throwable t) {
            EpochRunnerMod.logger.error("[RIVAL] triggerRaid failed.", t);
        }
    }

    public static void attemptChunkCapture(World world) {
        if (world == null || world.isRemote) return;

        Map<String, RivalCityState> map = getDimensionCities(world);
        if (map.isEmpty()) return;

        try {
            for (RivalCityState state : map.values()) {
                if (state.center == null) continue;
                state.size = Math.min(state.size + 16, 1024);
                claimChunksForRival(world, state);
            }

            RivalCityState primary = getAnyCity(world);
            if (primary != null) {
                updateLegacyFields(primary);
            }
        } catch (Throwable t) {
            EpochRunnerMod.logger.error("[RIVAL] attemptChunkCapture failed.", t);
        }
    }
}
