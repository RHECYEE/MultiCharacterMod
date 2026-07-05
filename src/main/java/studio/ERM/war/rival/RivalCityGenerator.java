package studio.ERM.war.rival;

import net.minecraft.block.Block;
import net.minecraft.block.state.IBlockState;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.init.Blocks;
import net.minecraft.util.EnumFacing;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;
import studio.ERM.EpochRunnerMod;
import studio.ERM.war.rival.ProceduralBuildingGenerator;
import studio.ERM.war.rival.RivalFactionStats;

import java.util.*;

/**
 * RivalCityGenerator - Handles all city generation logic.
 * 
 * Includes:
 * - AW2 template generation
 * - Procedural building generation
 * - Road network generation
 * - Grid layout and plot management
 * - Terrain preparation and backfill
 */
public class RivalCityGenerator {

    // =====================================================================
    // AW2 TEMPLATE POOLS (MohawkyPack V72)
    // =====================================================================

    public static final String[] CORE_STRUCTURES = {
            "EmpireVillageAndDukesCastle", "EmpireVillageAndCountsCastle", "EmpireVillageAndBaronsCastle",
            "EmpireVillageAndLordsCastle", "EmpireVillageAndMarquisCastle", "EmpireWalledCity"
    };

    public static final String[] RING1_PRIMITIVE = {
            "EmpireTownGenTravellingKnightTent", "EmpireTownGenRoadsideShrine",
            "EmpireTownGenWell", "EmpireTownGenWatchStation", "EmpireCoachingInn"
    };

    public static final String[] RING2_FARMS = {
            "EmpireTownGenFarmWheatLarge", "EmpireTownGenFarmSugarCane", "EmpireTownGenSugarCaneField",
            "EmpireTownGenFarmSheepLarge", "EmpireTownGenFarmPigLarge", "EmpireTownGenStockBarn",
            "EmpireTownGenMixedFieldLarge", "EmpireTownGenWheatFieldLarge", "EmpireTownGenMelonField"
    };

    public static final String[] RING3_CIVIC = {
            "EmpireTownGenTownHall", "EmpireTownGenTavernTravellersRest", "EmpireTownGenTavernSlaughteredLamb",
            "EmpireTownGenRandyRabbitTavern", "EmpireTownGenLibrary", "EmpireTownGenMarketSquare",
            "EmpireMerchantHouse1", "EmpireMerchantHouse2", "EmpireParishChurch", "EmpireBishopsChurch"
    };

    public static final String[] RING4_DEFENSE = {
            "EmpireTownGenWatchTower", "EmpireTownGenTollGateTower", "EmpireTownGenWatchPrison",
            "EmpireCityWallCornerTower", "EmpireCityWallOrdinaryWallTower", "EmpireBorderTower",
            "EmpireBallistaeTower", "EmpireMountainBeaconTower"
    };

    public static final String[] RING5_INDUSTRY = {
            "EmpireTownGenMillTraditional", "EmpireTownGenMillNewFangled", "EmpireTownGenMine",
            "EmpireTownGenStoneMason", "EmpireTownGenLumberjack", "EmpireTownGenGamekeepersHut",
            "EmpireTownGenBlacksmithLarge", "EmpireTownGenBlacksmithSmall", "EmpireBlacksmithLarge"
    };

    public static final String[] RING6_AIRSHIPS = {
            "Airship1Small", "Airship2Small", "Airship3Small",
            "BalloonSmallMulticoloured", "BalloonLargeRed", "BalloonStationLarge"
    };

    public static final String[] HOUSING = {
            "EmpireTownGenHouse1", "EmpireTownGenHouse2", "EmpireTownGenHouse3",
            "EmpireTownGenHouse4", "EmpireTownGenHouse5", "EmpireTownGenHouse6", "EmpireTownGenHouse7",
            "EmpireCityGenArtisansHouse1", "EmpireCityGenArtisansHouse2", "EmpireCityGenArtisansHouse3",
            "EmpireCityGenPoorHouse1", "EmpireCityGenPoorHouse2", "EmpireCityGenPoorHouse3", "EmpireCityGenPoorHouse4"
    };

    private static final Random rand = new Random();

    // =====================================================================
    // GRID CELL HELPER CLASS
    // =====================================================================

    public static class GridCell {
        public final int gx;
        public final int gz;
        public GridCell(int gx, int gz) { this.gx = gx; this.gz = gz; }
    }

    // =====================================================================
    // MAIN GENERATION ENTRY POINT
    // =====================================================================

    /**
     * Generate structures for a specific city level.
     *
     * LEVEL 1 IS NOT A CITY: it's the rival's TRIBAL CAMP — tents/teepees from the catalog's
     * "Level 0 Tribal / Tents" pool, nothing else. The core town structure (castle/walled centre),
     * plots, backfill and the road web all begin at level 2 ("it shouldve been teepees or tents
     * not a city, city is next").
     */
    public static void generateCityLevel(World world, RivalCityState state, int level) {
        if (state.center == null) return;
        if (!world.isBlockLoaded(state.center, false)) return;

        if (level <= 1) {
            generateTribalCamp(world, state);
            return;
        }

        // Determine mix of AW2 templates vs procedural buildings
        float proceduralRatio = Math.min(0.7f, (level - 3) * 0.15f);
        proceduralRatio = Math.max(0f, proceduralRatio);

        boolean aw2Available = tryGenerateAW2City(world, state, level, proceduralRatio);

        if (!aw2Available) {
            generateProceduralCity(world, state, level);
        }

        // Density backfill
        performDensityBackfill(world, state, level);

        // Road network
        generateRoadNetwork(world, state);
    }

    // Keyword fallback for the level-1 camp, matched against everything AW2 has loaded (the same
    // doctrine the nation-state camps use) so a thin catalog still yields real tents.
    private static final String[] TRIBAL_CAMP_KEYWORDS =
            {"teepee", "tepee", "tipi", "tent", "tribal", "camp", "hut", "yurt", "wigwam"};

    /**
     * The rival's LEVEL 1 settlement: a ring of tents/teepees around a bare centre. Placed once
     * (re-runs only top up tents that failed to place); never drops the castle core or paves roads.
     */
    private static void generateTribalCamp(World world, RivalCityState state) {
        int want = 7;
        int placed = 0;
        // Count what's already standing so /war rival city re-runs don't double the camp.
        int existing = state.placedStructures.size();
        for (int i = 0; existing + placed < want && i < want * 2; i++) {
            double ang = rand.nextDouble() * Math.PI * 2;
            int r = (i == 0 && existing == 0) ? 0 : 10 + rand.nextInt(22);
            BlockPos probe = state.center.add((int) (Math.cos(ang) * r), 0, (int) (Math.sin(ang) * r));
            if (!world.isBlockLoaded(probe, false)) continue;
            BlockPos at = world.getTopSolidOrLiquidBlock(probe);
            if (world.getBlockState(at.down()).getMaterial().isLiquid()) continue;

            String tmpl = studio.ERM.strategic.Aw2Structures.pick(
                    studio.ERM.war.config.SchematicCatalog.TRIBAL, null, TRIBAL_CAMP_KEYWORDS);
            if (tmpl == null) break; // no tent templates loaded at all — nothing to place
            if (placeAW2TemplateSafe(world, tmpl, at, EnumFacing.HORIZONTALS[rand.nextInt(4)], state)) {
                state.placedStructures.add(at);
                placed++;
            }
        }
        state.currentRingRadius = Math.max(state.currentRingRadius, 34);
        EpochRunnerMod.logger.info("[RivalCity] L1 tribal camp: " + placed + " tent(s) raised ("
                + (existing + placed) + " standing)");
    }

    // =====================================================================
    // AW2 TEMPLATE GENERATION
    // =====================================================================

    /**
     * AW2 template generation with procedural building integration.
     */
    public static boolean tryGenerateAW2City(World world, RivalCityState state, int level, float proceduralRatio) {
        try {
            Class.forName("net.shadowmage.ancientwarfare.structure.template.StructureTemplateManager");
            Set<String> templates = net.shadowmage.ancientwarfare.structure.template.StructureTemplateManager.getTemplates();
            if (templates == null || templates.isEmpty()) return false;

            // Core structure: placed exactly ONCE, the first time the settlement generates at
            // level 2+ (level 1 is the tent camp and never reaches this method anymore).
            if (level >= 2 && !state.coreStructurePlaced) {
                String coreName = findTemplate(templates, CORE_STRUCTURES);
                if (coreName != null) {
                    placeAW2TemplateSafe(world, coreName, state.center, EnumFacing.NORTH, state);
                    state.placedStructures.add(state.center);
                    state.majorIntersections.add(state.center);
                    state.currentRingRadius = 45;
                    state.stats.onStructureBuilt(RivalFactionStats.StructureType.HOUSING);
                    state.stats.onStructureBuilt(RivalFactionStats.StructureType.MILITARY_FORT);
                    state.coreStructurePlaced = true;
                }
            }

            // Apply density rules
            RivalCityConfig.applyDensityByLevel(state);

            int plotsToPlace = Math.min(RivalCityConfig.maxPlotsPerGrowth,
                    6 + (level * 2) + Math.max(0, state.stats.getIndustryTier() - 1));

            // Bias expansion toward the nearest player
            EntityPlayer targetPlayer = null;
            try {
                targetPlayer = world.getClosestPlayer(state.center.getX() + 0.5, state.center.getY() + 0.5, 
                        state.center.getZ() + 0.5, 2048, false);
                if (targetPlayer == null && world.playerEntities != null && !world.playerEntities.isEmpty()) {
                    targetPlayer = world.playerEntities.get(0);
                }
            } catch (Throwable ignored) {}

            // Maintain frontier garrison anchors
            ensureFrontierGarrisonAnchors(world, state, targetPlayer);

            // Ensure center plot is occupied
            state.occupiedGridCells.add(packGridCell(0, 0));
            state.currentGridRadius = Math.max(state.currentGridRadius, 1);

            for (int i = 0; i < plotsToPlace; i++) {
                GridCell cell = pickNextGridCell(world, state, targetPlayer);
                if (cell == null) break;

                BlockPos plotOrigin = gridCellToWorld(state, cell.gx, cell.gz);
                BlockPos surface = world.getTopSolidOrLiquidBlock(plotOrigin).down();

                preparePlotAndRoads(world, state, surface, cell.gx, cell.gz);

                RivalCityState.DistrictType district = pickDistrictForCell(state, cell.gx, cell.gz, targetPlayer);

                boolean useProcedural = (rand.nextFloat() < proceduralRatio) && level >= 4;

                if (useProcedural) {
                    generateProceduralStructure(world, state, surface, cell, district, level, targetPlayer);
                } else {
                    generateTemplateStructure(world, state, surface, cell, district, level, targetPlayer, templates);
                }

                state.occupiedGridCells.add(packGridCell(cell.gx, cell.gz));
            }

            // Backfill empty plots
            tryBackfillEmptyPlots(world, state, templates, level, proceduralRatio, targetPlayer);

            state.currentRingRadius = Math.max(state.currentRingRadius, state.currentGridRadius * state.gridSpacing);
            return true;
        } catch (Throwable t) {
            return false;
        }
    }

    private static void generateProceduralStructure(World world, RivalCityState state, BlockPos surface,
            GridCell cell, RivalCityState.DistrictType district, int level, EntityPlayer targetPlayer) {
        String structType = selectProceduralStructure(state, level, state.currentGridRadius * state.gridSpacing, district);
        EnumFacing facing = pickFacingForGrid(cell.gx, cell.gz, targetPlayer, state.center);

        int[] fp = ProceduralBuildingGenerator.getFootprint(structType, level);
        int pW = fp[0];
        int pD = fp[1];
        int pH = fp[2];

        BlockPos buildPos = surface.up();

        if (!isPositionOccupied(state, buildPos, pW, pD) && evaluateAndPrepareSite(world, surface, pW, pD)) {
            ProceduralBuildingGenerator.generateStructure(world, buildPos, structType, level, facing);

            int[] sample = sampleFootprint(world, buildPos, pW, pD, pH);
            state.templateFootprints.put(buildPos, new RivalCityState.TemplateFootprint(pW, pD, pH, sample[0], sample[1], structType));
            state.placedStructures.add(buildPos);

            updateStatsForStructure(state, structType);
        }
    }

    private static void generateTemplateStructure(World world, RivalCityState state, BlockPos surface,
            GridCell cell, RivalCityState.DistrictType district, int level, EntityPlayer targetPlayer, Set<String> templates) {
        String[] templatePool = pickStructurePoolForDistrict(world, state, level, district);
        String templateName = findValidTemplate(templates, templatePool);
        if (templateName != null) {
            EnumFacing facing = pickFacingForGrid(cell.gx, cell.gz, targetPlayer, state.center);
            BlockPos buildPos = surface.up();

            boolean ok = placeAW2TemplateSafe(world, templateName, buildPos, facing, state);
            if (ok) {
                state.placedStructures.add(buildPos);
                updateStatsForStructure(state, getStructureTypeNameForTemplate(templateName));
            }
        }
    }

    // =====================================================================
    // PROCEDURAL CITY GENERATION (FALLBACK)
    // =====================================================================

    /**
     * Full procedural city generation when AW2 is unavailable.
     */
    public static void generateProceduralCity(World world, RivalCityState state, int level) {
        int radius = state.currentRingRadius;
        if (radius == 0) radius = 30;

        int ringsThisGrow = RivalCityConfig.ringsPerGrowthBase + level / 2;

        for (int ring = 0; ring < ringsThisGrow; ring++) {
            radius += RivalCityConfig.baseRingStep + (level * 2);

            int structureCount = 8 + level * 3;
            List<BlockPos> ringPositions = new ArrayList<>();

            for (int i = 0; i < structureCount; i++) {
                double angle = (i * Math.PI * 2.0 / structureCount) + rand.nextDouble() * 0.3;
                double jitter = (rand.nextDouble() - 0.5) * 10.0;
                int r = (int) (radius + jitter);

                int offsetX = (int) (Math.cos(angle) * r);
                int offsetZ = (int) (Math.sin(angle) * r);

                BlockPos raw = state.center.add(offsetX, 0, offsetZ);
                if (!world.isBlockLoaded(raw, false)) continue;

                BlockPos ground = findBuildSurface(world, raw);
                BlockPos pos = ground.up();

                int[] fp = ProceduralBuildingGenerator.getFootprint(selectProceduralStructure(state, level, radius), level);
                int w = fp[0];
                int d = fp[1];

                if (isPositionOccupied(state, pos, w, d)) continue;
                if (!evaluateAndPrepareSite(world, ground, w, d)) continue;

                String structType = selectProceduralStructure(state, level, r);
                EnumFacing facing = EnumFacing.HORIZONTALS[rand.nextInt(4)];

                ProceduralBuildingGenerator.generateStructure(world, pos, structType, level, facing);

                state.placedStructures.add(pos);
                ringPositions.add(pos);
                updateStatsForStructure(state, structType);
            }

            if (!ringPositions.isEmpty()) {
                state.majorIntersections.add(ringPositions.get(ringPositions.size() / 2));
            }
        }

        state.currentRingRadius = radius;
    }

    // =====================================================================
    // DENSITY BACKFILL
    // =====================================================================

    /**
     * Fill in sparse rings with additional structures.
     */
    public static void performDensityBackfill(World world, RivalCityState state, int level) {
        if (rand.nextFloat() > RivalCityConfig.backfillProbability) return;

        int attemptsRemaining = RivalCityConfig.backfillMaxAttempts;

        for (Map.Entry<Integer, Integer> entry : state.structuresPerRing.entrySet()) {
            int ringIndex = entry.getKey();
            int count = entry.getValue();

            if (count < RivalCityConfig.minStructuresPerRing) {
                int needed = RivalCityConfig.minStructuresPerRing - count;
                int ringRadius = ringIndex * RivalCityConfig.baseRingStep + 30;

                for (int i = 0; i < needed && attemptsRemaining > 0; i++) {
                    attemptsRemaining--;

                    double angle = rand.nextDouble() * Math.PI * 2.0;
                    double jitter = (rand.nextDouble() - 0.5) * 15.0;
                    int r = (int) (ringRadius + jitter);

                    int offsetX = (int) (Math.cos(angle) * r);
                    int offsetZ = (int) (Math.sin(angle) * r);

                    BlockPos raw = state.center.add(offsetX, 0, offsetZ);
                    if (!world.isBlockLoaded(raw, false)) continue;

                    BlockPos ground = findBuildSurface(world, raw);
                    BlockPos pos = ground.up();
                    
                    if (isPositionOccupied(state, pos, 14, 14)) continue;
                    if (!evaluateAndPrepareSite(world, ground, 14, 14)) continue;

                    String[] backfillPool = {"HOUSING", "FARM", "WATCHTOWER", "SILO"};
                    String structType = backfillPool[rand.nextInt(backfillPool.length)];
                    EnumFacing facing = EnumFacing.HORIZONTALS[rand.nextInt(4)];

                    ProceduralBuildingGenerator.generateStructure(world, pos, structType, level, facing);

                    state.placedStructures.add(pos);
                    entry.setValue(count + 1);
                    updateStatsForStructure(state, structType);

                    EpochRunnerMod.logger.debug("[RIVAL] Backfilled " + structType + " at ring " + ringIndex);
                }
            }
        }

        state.expansionManager.backfillDensity(world, state.stats);
    }

    // =====================================================================
    // ROAD NETWORK GENERATION
    // =====================================================================

    /**
     * Generate proper road network connecting structures.
     */
    public static void generateRoadNetwork(World world, RivalCityState state) {
        if (state.center == null) return;
        if (state.placedStructures.size() < 2) return;

        List<BlockPos> structures = new ArrayList<>(state.placedStructures);

        structures.sort((a, b) -> {
            double da = a.distanceSq(state.center);
            double db = b.distanceSq(state.center);
            return Double.compare(da, db);
        });

        // Main roads from center to major intersections
        for (BlockPos intersection : state.majorIntersections) {
            if (intersection.equals(state.center)) continue;
            createMainRoad(world, state, state.center, intersection);
        }

        // Ring roads
        Map<Integer, List<BlockPos>> structuresByRadius = new HashMap<>();
        for (BlockPos pos : structures) {
            int radius = (int) Math.sqrt(pos.distanceSq(state.center)) / RivalCityConfig.baseRingStep;
            structuresByRadius.computeIfAbsent(radius, k -> new ArrayList<>()).add(pos);
        }

        for (List<BlockPos> ringStructures : structuresByRadius.values()) {
            if (ringStructures.size() < 2) continue;

            ringStructures.sort((a, b) -> {
                double angleA = Math.atan2(a.getZ() - state.center.getZ(), a.getX() - state.center.getX());
                double angleB = Math.atan2(b.getZ() - state.center.getZ(), b.getX() - state.center.getX());
                return Double.compare(angleA, angleB);
            });

            for (int i = 0; i < ringStructures.size(); i++) {
                BlockPos from = ringStructures.get(i);
                BlockPos to = ringStructures.get((i + 1) % ringStructures.size());

                if (from.distanceSq(to) < 100 * 100) {
                    createSidePath(world, state, from, to);
                }
            }
        }

        // Radial paths from structures toward center
        for (BlockPos pos : structures) {
            if (pos.equals(state.center)) continue;

            BlockPos nearest = null;
            double nearestDist = Double.MAX_VALUE;
            double myDist = pos.distanceSq(state.center);

            for (BlockPos other : structures) {
                if (other.equals(pos)) continue;
                double otherDist = other.distanceSq(state.center);
                if (otherDist >= myDist) continue;

                double dist = pos.distanceSq(other);
                if (dist < nearestDist && dist < 60 * 60) {
                    nearestDist = dist;
                    nearest = other;
                }
            }

            if (nearest != null) {
                createSidePath(world, state, pos, nearest);
            } else if (myDist > 30 * 30) {
                BlockPos midpoint = new BlockPos(
                        (pos.getX() + state.center.getX()) / 2,
                        0,
                        (pos.getZ() + state.center.getZ()) / 2
                );
                midpoint = world.getTopSolidOrLiquidBlock(midpoint);
                createSidePath(world, state, pos, midpoint);
            }
        }
    }

    private static void createMainRoad(World world, RivalCityState state, BlockPos from, BlockPos to) {
        createRoad(world, state, from, to, RivalCityConfig.mainRoadWidth, Blocks.COBBLESTONE.getDefaultState());
    }

    private static void createSidePath(World world, RivalCityState state, BlockPos from, BlockPos to) {
        createRoad(world, state, from, to, RivalCityConfig.sideRoadWidth, Blocks.GRAVEL.getDefaultState());
    }

    /**
     * Roads are AXIS-ALIGNED, L-shaped streets: an X leg then a Z leg through a shared corner.
     * The old point-to-point diagonals with a per-step random sine wobble read as "kind of random"
     * trails; straight legs match the grid the plots are laid on and read as city streets.
     */
    private static void createRoad(World world, RivalCityState state, BlockPos from, BlockPos to,
                                   int width, IBlockState material) {
        if (from.getX() == to.getX() && from.getZ() == to.getZ()) return;
        // Corner: run east-west first, then north-south (deterministic — repaved roads overlap).
        paveStraight(world, state, from.getX(), from.getZ(), to.getX(), from.getZ(), width, material);
        paveStraight(world, state, to.getX(), from.getZ(), to.getX(), to.getZ(), width, material);
    }

    /** Pave one straight axis-aligned segment (either x1==x2 or z1==z2). */
    private static void paveStraight(World world, RivalCityState state, int x1, int z1, int x2, int z2,
                                     int width, IBlockState material) {
        int half = width / 2;
        int stepX = Integer.compare(x2, x1);
        int stepZ = Integer.compare(z2, z1);
        if (stepX == 0 && stepZ == 0) return;
        int len = Math.max(Math.abs(x2 - x1), Math.abs(z2 - z1));
        for (int i = 0; i <= len; i++) {
            int cx = x1 + stepX * i;
            int cz = z1 + stepZ * i;
            for (int w = -half; w <= half; w++) {
                // Width runs perpendicular to the leg's axis.
                int roadX = (stepX != 0) ? cx : cx + w;
                int roadZ = (stepX != 0) ? cz + w : cz;

                BlockPos roadPos = new BlockPos(roadX, 0, roadZ);
                if (!world.isBlockLoaded(roadPos, false)) continue;

                BlockPos surface = world.getTopSolidOrLiquidBlock(roadPos).down();

                Block existingBlock = world.getBlockState(surface).getBlock();
                if (existingBlock == Blocks.GRASS || existingBlock == Blocks.DIRT ||
                        existingBlock == Blocks.SAND || existingBlock == Blocks.GRAVEL) {
                    world.setBlockState(surface, material, 2);
                    state.roadBlocks.add(surface.toLong());
                }
            }
        }
    }

    // =====================================================================
    // GRID LAYOUT HELPERS
    // =====================================================================

    public static long packGridCell(int gx, int gz) {
        return (((long) gx) << 32) ^ (gz & 0xFFFFFFFFL);
    }

    public static int unpackGridX(long packed) {
        return (int) (packed >> 32);
    }

    public static int unpackGridZ(long packed) {
        return (int) packed;
    }

    public static BlockPos gridCellToWorld(RivalCityState state, int gx, int gz) {
        int spacing = Math.max(8, state.gridSpacing);
        int halfPlot = state.gridPlotSize / 2;
        int x = state.center.getX() + (gx * spacing);
        int z = state.center.getZ() + (gz * spacing);
        return new BlockPos(x - halfPlot, state.center.getY(), z - halfPlot);
    }

    public static GridCell pickNextGridCell(World world, RivalCityState state, EntityPlayer biasToPlayer) {
        int nextR = Math.max(1, state.currentGridRadius + 1);
        int lateral = Math.max(2, nextR / 2);

        double dirX = 0.0, dirZ = 1.0;
        if (biasToPlayer != null && state.center != null) {
            double dx = (biasToPlayer.posX - state.center.getX());
            double dz = (biasToPlayer.posZ - state.center.getZ());
            double len = Math.sqrt(dx*dx + dz*dz);
            if (len > 0.001) { dirX = dx / len; dirZ = dz / len; }
        }

        GridCell best = null;
        double bestScore = -1e18;

        for (int gx = -nextR; gx <= nextR; gx++) {
            for (int gz = -nextR; gz <= nextR; gz++) {
                int cheb = Math.max(Math.abs(gx), Math.abs(gz));
                if (cheb != nextR) continue;

                double proj = gx * dirX + gz * dirZ;
                double perp = Math.abs((-dirZ * gx) + (dirX * gz));
                if (perp > (lateral + 2)) continue;

                long key = packGridCell(gx, gz);
                if (state.occupiedGridCells.contains(key)) continue;

                if (!hasAdjacentOccupied(state, gx, gz)) continue;

                double score = proj * 10.0 - perp * 2.0 + (rand.nextDouble() * 1.25);

                BlockPos p = gridCellToWorld(state, gx, gz);
                if (!world.isBlockLoaded(p, false)) continue;

                if (score > bestScore) {
                    bestScore = score;
                    best = new GridCell(gx, gz);
                }
            }
        }

        if (best == null) {
            int r = nextR + 2;
            for (int tries = 0; tries < 120; tries++) {
                int gx = rand.nextInt(r * 2 + 1) - r;
                int gz = rand.nextInt(r * 2 + 1) - r;
                long key = packGridCell(gx, gz);
                if (state.occupiedGridCells.contains(key)) continue;
                if (!hasAdjacentOccupied(state, gx, gz)) continue;

                BlockPos p = gridCellToWorld(state, gx, gz);
                if (!world.isBlockLoaded(p, false)) continue;

                best = new GridCell(gx, gz);
                break;
            }
        }

        if (best != null) {
            state.currentGridRadius = Math.max(state.currentGridRadius, Math.max(Math.abs(best.gx), Math.abs(best.gz)));
        }
        return best;
    }

    private static boolean hasAdjacentOccupied(RivalCityState state, int gx, int gz) {
        return state.occupiedGridCells.contains(packGridCell(gx + 1, gz)) ||
                state.occupiedGridCells.contains(packGridCell(gx - 1, gz)) ||
                state.occupiedGridCells.contains(packGridCell(gx, gz + 1)) ||
                state.occupiedGridCells.contains(packGridCell(gx, gz - 1)) ||
                state.occupiedGridCells.contains(packGridCell(gx + 1, gz + 1)) ||
                state.occupiedGridCells.contains(packGridCell(gx - 1, gz - 1)) ||
                state.occupiedGridCells.contains(packGridCell(gx + 1, gz - 1)) ||
                state.occupiedGridCells.contains(packGridCell(gx - 1, gz + 1));
    }

    // =====================================================================
    // PLOT AND TERRAIN PREPARATION
    // =====================================================================

    public static void preparePlotAndRoads(World world, RivalCityState state, BlockPos surface, int gx, int gz) {
        int plotW = state.gridPlotSize;
        int roadW = state.gridRoadWidth;
        int spacing = state.gridSpacing;

        int x = surface.getX();
        int z = surface.getZ();

        // Determine if this cell borders a major avenue
        boolean majorX = (gx != 0) && (Math.abs(gx) % RivalCityConfig.majorRoadEvery == 0);
        boolean majorZ = (gz != 0) && (Math.abs(gz) % RivalCityConfig.majorRoadEvery == 0);

        int northRoad = majorZ ? roadW + 1 : roadW;
        int eastRoad = majorX ? roadW + 1 : roadW;

        // Carve roads on north (+Z) and east (+X) edges
        carveRoadStrip(world, state, x, x + plotW - 1, z + plotW, northRoad, true);
        carveRoadStrip(world, state, z, z + plotW - 1, x + plotW, eastRoad, false);

        // Corner intersection
        if (majorX || majorZ) {
            carveRoadRect(world, state, x + plotW, z + plotW, eastRoad, northRoad);
        }
    }

    private static void carveRoadStrip(World world, RivalCityState state, int xStart, int xEndOrZ, 
            int zStartOrX, int width, boolean alongX) {
        if (world == null) return;
        int yBase = world.getSeaLevel();

        if (alongX) {
            int z0 = zStartOrX;
            for (int x = xStart; x <= xEndOrZ; x++) {
                for (int w = 0; w < width; w++) {
                    BlockPos p = new BlockPos(x, yBase, z0 + w);
                    BlockPos top = world.getTopSolidOrLiquidBlock(p).down();
                    setRoadBlock(world, state, top);
                }
            }
        } else {
            int x0 = xStart;
            for (int z = zStartOrX; z <= xEndOrZ; z++) {
                for (int w = 0; w < width; w++) {
                    BlockPos p = new BlockPos(x0 + w, yBase, z);
                    BlockPos top = world.getTopSolidOrLiquidBlock(p).down();
                    setRoadBlock(world, state, top);
                }
            }
        }
    }

    private static void carveRoadRect(World world, RivalCityState state, int x, int z, int w, int d) {
        if (world == null) return;
        int yBase = world.getSeaLevel();
        for (int dx = 0; dx < w; dx++) {
            for (int dz = 0; dz < d; dz++) {
                BlockPos p = new BlockPos(x + dx, yBase, z + dz);
                BlockPos top = world.getTopSolidOrLiquidBlock(p).down();
                setRoadBlock(world, state, top);
            }
        }
    }

    private static void setRoadBlock(World world, RivalCityState state, BlockPos ground) {
        if (world == null || ground == null) return;
        try {
            Block b = world.getBlockState(ground).getBlock();
            if (b == Blocks.GRASS || b == Blocks.DIRT || b == Blocks.SAND || b == Blocks.GRAVEL || b == Blocks.STONE) {
                world.setBlockState(ground, Blocks.GRAVEL.getDefaultState(), 2);
                state.roadBlocks.add(ground.toLong());
            }
        } catch (Throwable ignored) {}
    }

    // =====================================================================
    // TERRAIN EVALUATION AND PREPARATION
    // =====================================================================

    public static boolean isNaturalSurfaceBlock(IBlockState state) {
        Block b = state.getBlock();
        return b == Blocks.GRASS || b == Blocks.DIRT || b == Blocks.STONE || b == Blocks.SAND
                || b == Blocks.GRAVEL || b == Blocks.CLAY || b == Blocks.MYCELIUM;
    }

    public static BlockPos findBuildSurface(World world, BlockPos raw) {
        BlockPos top = world.getTopSolidOrLiquidBlock(raw);
        if (!world.isBlockLoaded(top, false)) return top;

        BlockPos cursor = top;
        for (int i = 0; i < RivalCityConfig.MAX_ROOF_SCAN_DOWN; i++) {
            IBlockState st = world.getBlockState(cursor);
            if (isNaturalSurfaceBlock(st)) {
                return cursor;
            }
            cursor = cursor.down();
            if (cursor.getY() <= 1) break;
        }

        return top;
    }

    public static boolean evaluateAndPrepareSite(World world, BlockPos groundBlock, int w, int d) {
        int halfW = Math.max(1, w / 2);
        int halfD = Math.max(1, d / 2);

        int minY = Integer.MAX_VALUE;
        int maxY = Integer.MIN_VALUE;

        for (int dx = -halfW; dx <= halfW; dx += 2) {
            for (int dz = -halfD; dz <= halfD; dz += 2) {
                BlockPos p = groundBlock.add(dx, 0, dz);
                if (!world.isBlockLoaded(p, false)) return false;

                BlockPos g = findBuildSurface(world, p);
                int y = g.getY();
                minY = Math.min(minY, y);
                maxY = Math.max(maxY, y);
            }
        }

        if ((maxY - minY) > RivalCityConfig.MAX_SITE_GRADE) return false;

        int targetY = minY;
        return flattenFootprint(world, new BlockPos(groundBlock.getX(), targetY, groundBlock.getZ()), w, d, targetY);
    }

    public static boolean flattenFootprint(World world, BlockPos centerAtTargetY, int w, int d, int targetY) {
        int halfW = Math.max(1, w / 2);
        int halfD = Math.max(1, d / 2);

        for (int dx = -halfW; dx <= halfW; dx++) {
            for (int dz = -halfD; dz <= halfD; dz++) {
                BlockPos col = new BlockPos(centerAtTargetY.getX() + dx, targetY, centerAtTargetY.getZ() + dz);

                BlockPos g = findBuildSurface(world, col);
                int gY = g.getY();

                if (gY < targetY) {
                    int diff = targetY - gY;
                    if (diff > RivalCityConfig.MAX_FILL_HEIGHT) return false;

                    for (int y = gY + 1; y <= targetY; y++) {
                        BlockPos fp = new BlockPos(col.getX(), y, col.getZ());
                        if (world.isAirBlock(fp) || world.getBlockState(fp).getMaterial().isReplaceable()) {
                            world.setBlockState(fp, Blocks.DIRT.getDefaultState(), 2);
                        }
                    }
                    world.setBlockState(new BlockPos(col.getX(), targetY, col.getZ()), Blocks.GRASS.getDefaultState(), 2);
                }

                if (gY > targetY) {
                    for (int y = gY; y > targetY; y--) {
                        BlockPos cp = new BlockPos(col.getX(), y, col.getZ());
                        if (cp.getY() <= 1) break;
                        world.setBlockToAir(cp);
                    }
                    world.setBlockState(new BlockPos(col.getX(), targetY, col.getZ()), Blocks.GRASS.getDefaultState(), 2);
                }
            }
        }
        return true;
    }

    // =====================================================================
    // POSITION CHECKING
    // =====================================================================

    public static boolean isPositionOccupied(RivalCityState state, BlockPos pos, int w, int d) {
        int halfW = Math.max(1, w / 2);
        int halfD = Math.max(1, d / 2);

        int minX = pos.getX() - halfW - RivalCityConfig.LOT_BUFFER_BLOCKS;
        int maxX = pos.getX() + halfW + RivalCityConfig.LOT_BUFFER_BLOCKS;
        int minZ = pos.getZ() - halfD - RivalCityConfig.LOT_BUFFER_BLOCKS;
        int maxZ = pos.getZ() + halfD + RivalCityConfig.LOT_BUFFER_BLOCKS;

        for (BlockPos placed : state.placedStructures) {
            RivalCityState.TemplateFootprint fp = state.templateFootprints.get(placed);

            if (fp != null) {
                int pHalfW = Math.max(1, fp.width / 2);
                int pHalfD = Math.max(1, fp.depth / 2);

                int pMinX = placed.getX() - pHalfW - RivalCityConfig.LOT_BUFFER_BLOCKS;
                int pMaxX = placed.getX() + pHalfW + RivalCityConfig.LOT_BUFFER_BLOCKS;
                int pMinZ = placed.getZ() - pHalfD - RivalCityConfig.LOT_BUFFER_BLOCKS;
                int pMaxZ = placed.getZ() + pHalfD + RivalCityConfig.LOT_BUFFER_BLOCKS;

                boolean xOverlap = (minX <= pMaxX) && (maxX >= pMinX);
                boolean zOverlap = (minZ <= pMaxZ) && (maxZ >= pMinZ);
                if (xOverlap && zOverlap) return true;
            } else {
                if (pos.distanceSq(placed) < (14 * 14)) return true;
            }
        }
        return false;
    }

    // =====================================================================
    // DISTRICT AND STRUCTURE SELECTION
    // =====================================================================

    public static RivalCityState.DistrictType pickDistrictForCell(RivalCityState state, int gx, int gz, EntityPlayer targetPlayer) {
        if (state == null || state.center == null) return RivalCityState.DistrictType.CIVIC_CORE;

        int distCells = Math.max(Math.abs(gx), Math.abs(gz));
        int r = Math.max(1, state.currentGridRadius);
        float dist01 = Math.min(1.0f, (float) distCells / (float) r);

        boolean frontierTowardPlayer = false;
        if (targetPlayer != null) {
            int dx = targetPlayer.getPosition().getX() - state.center.getX();
            int dz = targetPlayer.getPosition().getZ() - state.center.getZ();
            int dot = (gx * dx) + (gz * dz);
            frontierTowardPlayer = dot > 0 && dist01 >= 0.70f;
        }

        if (dist01 <= 0.18f) {
            float indP = state.stats.getIndustryPercent();
            if (indP >= 0.75f && rand.nextFloat() < 0.25f) return RivalCityState.DistrictType.INFRASTRUCTURE;
            return rand.nextFloat() < 0.55f ? RivalCityState.DistrictType.CIVIC_CORE : RivalCityState.DistrictType.RESIDENTIAL;
        }

        if (frontierTowardPlayer) {
            float moraleP = (float) state.stats.getMorale() / 100.0f;
            if (moraleP >= 0.35f || state.stats.getIndustryPercent() >= 0.40f) {
                return rand.nextFloat() < 0.70f ? RivalCityState.DistrictType.MILITARY_GARRISON : RivalCityState.DistrictType.INDUSTRIAL;
            }
            return RivalCityState.DistrictType.MILITARY_GARRISON;
        }

        float popP = state.stats.getPopulationPercent();
        float indP = state.stats.getIndustryPercent();

        int total = 0;
        for (RivalCityState.DistrictType dt : RivalCityState.DistrictType.values()) 
            total += state.districtCounts.getOrDefault(dt, 0);
        total = Math.max(1, total);

        int wantResidential = (int) (total * (0.28f + (popP - indP) * 0.18f));
        int wantIndustrial = (int) (total * (0.22f + (indP - popP) * 0.22f));
        int wantMilitary = (int) (total * (0.18f + (1.0f - ((float) state.stats.getMorale() / 100.0f)) * 0.08f));
        int wantAgri = (int) (total * 0.12f);
        int wantInfra = (int) (total * (0.10f + indP * 0.06f));
        int wantCivic = Math.max(0, total - (wantResidential + wantIndustrial + wantMilitary + wantAgri + wantInfra));

        int haveResidential = state.districtCounts.getOrDefault(RivalCityState.DistrictType.RESIDENTIAL, 0);
        int haveIndustrial = state.districtCounts.getOrDefault(RivalCityState.DistrictType.INDUSTRIAL, 0);
        int haveMilitary = state.districtCounts.getOrDefault(RivalCityState.DistrictType.MILITARY_GARRISON, 0);
        int haveAgri = state.districtCounts.getOrDefault(RivalCityState.DistrictType.AGRICULTURE, 0);
        int haveInfra = state.districtCounts.getOrDefault(RivalCityState.DistrictType.INFRASTRUCTURE, 0);
        int haveCivic = state.districtCounts.getOrDefault(RivalCityState.DistrictType.CIVIC_CORE, 0);

        int dRes = wantResidential - haveResidential;
        int dInd = wantIndustrial - haveIndustrial;
        int dMil = wantMilitary - haveMilitary;
        int dAg = wantAgri - haveAgri;
        int dInf = wantInfra - haveInfra;
        int dCiv = wantCivic - haveCivic;

        if (dist01 >= 0.78f) {
            if (dMil >= dInd && dMil >= dAg) return RivalCityState.DistrictType.MILITARY_GARRISON;
            if (dInd >= dAg) return RivalCityState.DistrictType.INDUSTRIAL;
            return RivalCityState.DistrictType.AGRICULTURE;
        }

        int best = dCiv;
        RivalCityState.DistrictType bestType = RivalCityState.DistrictType.CIVIC_CORE;

        if (dRes > best) { best = dRes; bestType = RivalCityState.DistrictType.RESIDENTIAL; }
        if (dInd > best) { best = dInd; bestType = RivalCityState.DistrictType.INDUSTRIAL; }
        if (dMil > best) { best = dMil; bestType = RivalCityState.DistrictType.MILITARY_GARRISON; }
        if (dAg > best) { best = dAg; bestType = RivalCityState.DistrictType.AGRICULTURE; }
        if (dInf > best) { best = dInf; bestType = RivalCityState.DistrictType.INFRASTRUCTURE; }

        if (rand.nextFloat() < 0.12f) {
            RivalCityState.DistrictType[] mix = RivalCityState.DistrictType.values();
            return mix[rand.nextInt(mix.length)];
        }

        return bestType;
    }

    public static String selectProceduralStructure(RivalCityState state, int level, int radius) {
        return selectProceduralStructure(state, level, radius, null);
    }

    public static String selectProceduralStructure(RivalCityState state, int level, int radius, RivalCityState.DistrictType district) {
        if (district == null) {
            if (level >= 7) {
                String[] opts = {"SKYSCRAPER", "COOLING_TOWER", "FACTORY", "SILO", "RADAR_PAD", "OUTPOST"};
                return opts[rand.nextInt(opts.length)];
            }
            String[] opts = {"HOUSING", "FARM", "WATCHTOWER", "OUTPOST", "SILO"};
            return opts[rand.nextInt(opts.length)];
        }

        switch (district) {
            case INDUSTRIAL: {
                if (level >= 6) {
                    String[] opts = {"FACTORY", "COOLING_TOWER", "SILO", "POWER_PLANT"};
                    return opts[rand.nextInt(opts.length)];
                }
                String[] opts = {"FACTORY", "SILO", "OUTPOST"};
                return opts[rand.nextInt(opts.length)];
            }
            case MILITARY_GARRISON: {
                if (level >= 5) {
                    String[] opts = {"FORT", "BARRACKS", "WATCHTOWER", "BUNKER"};
                    return opts[rand.nextInt(opts.length)];
                }
                String[] opts = {"WATCHTOWER", "OUTPOST", "FORT"};
                return opts[rand.nextInt(opts.length)];
            }
            case AGRICULTURE: {
                String[] opts = {"OUTPOST", "WATCHTOWER"};
                return opts[rand.nextInt(opts.length)];
            }
            case RESIDENTIAL: {
                if (level >= 7) {
                    String[] opts = {"SKYSCRAPER", "OUTPOST"};
                    return opts[rand.nextInt(opts.length)];
                }
                return "OUTPOST";
            }
            case INFRASTRUCTURE: {
                if (level >= 6) {
                    String[] opts = {"RADAR_PAD", "ANTENNA", "POWER_PLANT"};
                    return opts[rand.nextInt(opts.length)];
                }
                String[] opts = {"RADAR_PAD", "OUTPOST"};
                return opts[rand.nextInt(opts.length)];
            }
            case CIVIC_CORE:
            default: {
                if (level >= 8) {
                    String[] opts = {"SKYSCRAPER", "RADAR_PAD", "POWER_PLANT"};
                    return opts[rand.nextInt(opts.length)];
                }
                String[] opts = {"OUTPOST", "WATCHTOWER"};
                return opts[rand.nextInt(opts.length)];
            }
        }
    }

    public static String[] pickStructurePoolForDistrict(World world, RivalCityState state, int level, 
            RivalCityState.DistrictType district) {
        if (district == null) district = RivalCityState.DistrictType.CIVIC_CORE;

        switch (district) {
            case INDUSTRIAL:
                if (level <= 3) return combineArrays(RING1_PRIMITIVE, RING5_INDUSTRY);
                if (level <= 6) return combineArrays(RING5_INDUSTRY, RING3_CIVIC);
                return combineArrays(RING5_INDUSTRY, RING6_AIRSHIPS);
            case MILITARY_GARRISON:
                if (level <= 3) return combineArrays(RING1_PRIMITIVE, RING4_DEFENSE);
                if (level <= 6) return combineArrays(RING4_DEFENSE, RING3_CIVIC);
                return combineArrays(RING4_DEFENSE, RING6_AIRSHIPS);
            case AGRICULTURE:
                return combineArrays(RING2_FARMS, HOUSING);
            case INFRASTRUCTURE:
                if (level <= 4) return combineArrays(RING3_CIVIC, RING2_FARMS);
                return combineArrays(RING6_AIRSHIPS, RING5_INDUSTRY);
            case RESIDENTIAL:
                if (level <= 4) return combineArrays(HOUSING, RING3_CIVIC);
                if (level <= 8) return combineArrays(HOUSING, combineArrays(RING3_CIVIC, RING5_INDUSTRY));
                return combineArrays(HOUSING, RING6_AIRSHIPS);
            case CIVIC_CORE:
            default:
                if (level <= 2) return combineArrays(RING1_PRIMITIVE, HOUSING);
                if (level <= 4) return combineArrays(RING3_CIVIC, HOUSING);
                if (level <= 6) return combineArrays(RING3_CIVIC, combineArrays(RING4_DEFENSE, HOUSING));
                return combineArrays(RING3_CIVIC, combineArrays(RING5_INDUSTRY, HOUSING));
        }
    }

    private static EnumFacing pickFacingForGrid(int gx, int gz, EntityPlayer target, BlockPos center) {
        if (target != null && center != null) {
            double dx = target.posX - center.getX();
            double dz = target.posZ - center.getZ();
            if (Math.abs(dx) > Math.abs(dz)) return dx >= 0 ? EnumFacing.EAST : EnumFacing.WEST;
            return dz >= 0 ? EnumFacing.SOUTH : EnumFacing.NORTH;
        }
        if (Math.abs(gx) > Math.abs(gz)) return gx >= 0 ? EnumFacing.EAST : EnumFacing.WEST;
        return gz >= 0 ? EnumFacing.SOUTH : EnumFacing.NORTH;
    }

    // =====================================================================
    // GARRISON ANCHORS
    // =====================================================================

    private static void ensureFrontierGarrisonAnchors(World world, RivalCityState state, EntityPlayer targetPlayer) {
        if (world == null || state == null || state.center == null) return;

        long now = world.getTotalWorldTime();
        if (now - state.lastGarrisonAnchorTick < 600) return;
        state.lastGarrisonAnchorTick = now;

        if (targetPlayer == null) return;

        int dx = targetPlayer.getPosition().getX() - state.center.getX();
        int dz = targetPlayer.getPosition().getZ() - state.center.getZ();

        if (Math.abs(dx) + Math.abs(dz) < 8) return;

        int sx = dx >= 0 ? 1 : -1;
        int sz = dz >= 0 ? 1 : -1;

        int r = Math.max(2, state.currentGridRadius);

        int gx = sx * r;
        int gz = sz * r;

        long packed = packGridCell(gx, gz);
        for (RivalCityState.GarrisonAnchor a : state.garrisonAnchors) {
            if (a.pack() == packed) return;
        }

        state.garrisonAnchors.add(new RivalCityState.GarrisonAnchor(gx, gz));

        while (state.garrisonAnchors.size() > 3) {
            state.garrisonAnchors.remove(0);
        }
    }

    // =====================================================================
    // BACKFILL EMPTY PLOTS
    // =====================================================================

    private static void tryBackfillEmptyPlots(World world, RivalCityState state, Set<String> templates, int level,
                                              float proceduralRatio, EntityPlayer targetPlayer) {
        if (rand.nextFloat() > RivalCityConfig.backfillEmptyPlotChance) return;

        int r = Math.max(2, state.currentGridRadius);
        int attempts = Math.max(4, RivalCityConfig.backfillEmptyPlotAttempts);

        for (int i = 0; i < attempts; i++) {
            int gx = rand.nextInt(r * 2 + 1) - r;
            int gz = rand.nextInt(r * 2 + 1) - r;

            long key = packGridCell(gx, gz);
            if (state.occupiedGridCells.contains(key)) continue;

            int neighborCount = 0;
            if (state.occupiedGridCells.contains(packGridCell(gx + 1, gz))) neighborCount++;
            if (state.occupiedGridCells.contains(packGridCell(gx - 1, gz))) neighborCount++;
            if (state.occupiedGridCells.contains(packGridCell(gx, gz + 1))) neighborCount++;
            if (state.occupiedGridCells.contains(packGridCell(gx, gz - 1))) neighborCount++;
            if (neighborCount < 2) continue;

            BlockPos plotOrigin = gridCellToWorld(state, gx, gz);
            if (!world.isBlockLoaded(plotOrigin, false)) continue;

            BlockPos surface = world.getTopSolidOrLiquidBlock(plotOrigin).down();
            preparePlotAndRoads(world, state, surface, gx, gz);

            RivalCityState.DistrictType district = pickDistrictForCell(state, gx, gz, targetPlayer);

            boolean useProcedural = (rand.nextFloat() < proceduralRatio) && level >= 4;

            if (useProcedural) {
                String structType = selectProceduralStructure(state, level, state.currentGridRadius * state.gridSpacing, district);
                EnumFacing facing = pickFacingForGrid(gx, gz, targetPlayer, state.center);

                int[] fp = ProceduralBuildingGenerator.getFootprint(structType, level);
                int pW = fp[0];
                int pD = fp[1];
                int pH = fp[2];

                BlockPos buildPos = surface.up();
                if (!isPositionOccupied(state, buildPos, pW, pD) && evaluateAndPrepareSite(world, surface, pW, pD)) {
                    ProceduralBuildingGenerator.generateStructure(world, buildPos, structType, level, facing);

                    int[] sample = sampleFootprint(world, buildPos, pW, pD, pH);
                    state.templateFootprints.put(buildPos, new RivalCityState.TemplateFootprint(pW, pD, pH, sample[0], sample[1], structType));
                    state.placedStructures.add(buildPos);

                    updateStatsForStructure(state, structType);
                    state.occupiedGridCells.add(key);
                }
            } else {
                String[] templatePool = pickStructurePoolForDistrict(world, state, level, district);
                String templateName = findValidTemplate(templates, templatePool);
                if (templateName != null) {
                    EnumFacing facing = pickFacingForGrid(gx, gz, targetPlayer, state.center);
                    BlockPos buildPos = surface.up();
                    boolean ok = placeAW2TemplateSafe(world, templateName, buildPos, facing, state);
                    if (ok) {
                        state.placedStructures.add(buildPos);
                        updateStatsForStructure(state, getStructureTypeNameForTemplate(templateName));
                        state.occupiedGridCells.add(key);
                    }
                }
            }
        }
    }

    // =====================================================================
    // AW2 TEMPLATE HELPERS
    // =====================================================================

    public static boolean placeAW2TemplateSafe(World world, String templateName, BlockPos anchor,
                                                EnumFacing facing, RivalCityState state) {
        if (templateName == null) return false;
        if (!world.isBlockLoaded(anchor, false)) return false;

        BlockPos surface = anchor;

        try {
            java.util.Optional<net.shadowmage.ancientwarfare.structure.template.StructureTemplate> opt =
                    net.shadowmage.ancientwarfare.structure.template.StructureTemplateManager.getTemplate(templateName);

            if (!opt.isPresent()) return false;

            net.shadowmage.ancientwarfare.structure.template.StructureTemplate tmpl = opt.get();

            net.shadowmage.ancientwarfare.structure.template.build.StructureBuilderWorldGen builder =
                    new net.shadowmage.ancientwarfare.structure.template.build.StructureBuilderWorldGen(
                            world, tmpl, facing, surface);
            builder.instantConstruction();

            fixTemplateNPCFactions(world, surface, 80);

            if (tmpl.getSize() != null) {
                int w = tmpl.getSize().getX();
                int d = tmpl.getSize().getZ();
                int h = tmpl.getSize().getY();

                if (facing == EnumFacing.EAST || facing == EnumFacing.WEST) {
                    int tmp = w;
                    w = d;
                    d = tmp;
                }

                String st = getStructureTypeNameForTemplate(templateName);
                int[] sample = sampleFootprint(world, surface, w, d, h);

                state.templateFootprints.put(anchor, new RivalCityState.TemplateFootprint(w, d, h, sample[0], sample[1], st));
            }

            return true;
        } catch (Throwable ignored) {
            return false;
        }
    }

    public static String getStructureTypeNameForTemplate(String templateName) {
        if (templateName == null) return "UNKNOWN";
        String lower = templateName.toLowerCase();
        if (lower.contains("farm") || lower.contains("field")) return "FARM";
        if (lower.contains("house") || lower.contains("home")) return "HOUSING";
        if (lower.contains("tower") || lower.contains("watch")) return "WATCHTOWER";
        if (lower.contains("castle") || lower.contains("fort")) return "MILITARY_FORT";
        if (lower.contains("barrack") || lower.contains("camp")) return "BARRACKS";
        if (lower.contains("factory") || lower.contains("plant")) return "FACTORY";
        if (lower.contains("silo") || lower.contains("tank") || lower.contains("storage")) return "SILO";
        if (lower.contains("radar") || lower.contains("antenna")) return "RADAR";
        if (lower.contains("sky") || lower.contains("towerblock") || lower.contains("highrise")) return "SKYSCRAPER";
        return "UNKNOWN";
    }

    public static int[] sampleFootprint(World world, BlockPos base, int w, int d, int h) {
        int hh = Math.max(4, Math.min(h, 40));
        int stepXZ = 2;
        int stepY = 3;

        int samples = 0;
        int filled = 0;

        for (int y = 0; y < hh; y += stepY) {
            for (int x = 0; x < w; x += stepXZ) {
                for (int z = 0; z < d; z += stepXZ) {
                    samples++;
                    BlockPos p = base.add(x, y, z);
                    if (!world.isAirBlock(p)) filled++;
                }
            }
        }
        return new int[]{samples, filled};
    }

    private static void fixTemplateNPCFactions(World world, BlockPos center, int radius) {
        try {
            BlockPos min = center.add(-radius, -16, -radius);
            BlockPos max = center.add(radius, 64, radius);

            java.util.Set<String> from = new java.util.HashSet<>();
            java.util.List<String> pool = java.util.Collections.singletonList(RivalCityState.RIVAL_AW2_FACTION);

            trySwapFactionsInAreaCompat(world, min, max, from, pool);
        } catch (Throwable ignored) {}
    }

    private static void trySwapFactionsInAreaCompat(World world, BlockPos min, BlockPos max,
                                                     Set<String> fromFactions, List<String> toFactionPool) {
        try {
            Class<?> cls = studio.ERM.war.util.AdvancedSpawnerFactionSwapper.class;

            for (java.lang.reflect.Method mm : cls.getDeclaredMethods()) {
                if (!"swapFactionsInArea".equals(mm.getName())) continue;
                Class<?>[] p = mm.getParameterTypes();
                if (p.length == 7
                        && p[0] == World.class
                        && p[1] == BlockPos.class
                        && p[2] == BlockPos.class
                        && java.util.Set.class.isAssignableFrom(p[3])
                        && (p[4] == String.class || p[4] == Object.class)
                        && java.util.List.class.isAssignableFrom(p[5])
                        && (p[6] == long.class || p[6] == Long.class)) {
                    mm.setAccessible(true);
                    mm.invoke(null, world, min, max, fromFactions, null, toFactionPool, rand.nextLong());
                    return;
                }
            }

            for (java.lang.reflect.Method mm : cls.getDeclaredMethods()) {
                if (!"swapFactionsInArea".equals(mm.getName())) continue;
                Class<?>[] p = mm.getParameterTypes();
                if (p.length == 6
                        && p[0] == World.class
                        && p[1] == BlockPos.class
                        && p[2] == BlockPos.class
                        && java.util.Set.class.isAssignableFrom(p[3])
                        && java.util.List.class.isAssignableFrom(p[4])
                        && (p[5] == long.class || p[5] == Long.class)) {
                    mm.setAccessible(true);
                    mm.invoke(null, world, min, max, fromFactions, toFactionPool, rand.nextLong());
                    return;
                }
            }
        } catch (Throwable ignored) {}
    }

    public static String findValidTemplate(Set<String> templates, String[] preferred) {
        List<String> validMatches = new ArrayList<>();
        for (String pref : preferred) {
            for (String name : templates) {
                if (!name.toLowerCase(Locale.ROOT).contains(pref.toLowerCase(Locale.ROOT))) continue;
                try {
                    java.util.Optional<net.shadowmage.ancientwarfare.structure.template.StructureTemplate> opt =
                            net.shadowmage.ancientwarfare.structure.template.StructureTemplateManager.getTemplate(name);
                    if (opt.isPresent() && isTemplateValid(opt.get())) validMatches.add(name);
                } catch (Throwable ignored) {}
            }
        }
        if (validMatches.isEmpty()) return null;
        return validMatches.get(rand.nextInt(validMatches.size()));
    }

    private static boolean isTemplateValid(net.shadowmage.ancientwarfare.structure.template.StructureTemplate template) {
        try {
            if (template.getSize() == null) return false;
            int x = template.getSize().getX();
            int z = template.getSize().getZ();
            return x > 0 && z > 0 && x <= 160 && z <= 160;
        } catch (Throwable e) {
            return false;
        }
    }

    public static String findTemplate(Collection<String> available, String[] preferred) {
        for (String pref : preferred) {
            for (String avail : available) {
                if (avail.toLowerCase(Locale.ROOT).contains(pref.toLowerCase(Locale.ROOT))) return avail;
            }
        }
        return null;
    }

    public static String[] combineArrays(String[] a, String[] b) {
        String[] res = new String[a.length + b.length];
        System.arraycopy(a, 0, res, 0, a.length);
        System.arraycopy(b, 0, res, a.length, b.length);
        return res;
    }

    // =====================================================================
    // STATS INTEGRATION
    // =====================================================================

    public static void updateStatsForStructure(RivalCityState state, String structType) {
        RivalFactionStats.StructureType type = mapStructureType(structType);
        if (type != null) {
            state.stats.onStructureBuilt(type);
        }

        RivalCityState.DistrictType dt = inferDistrictFromStructureType(structType);
        if (dt != null) {
            int v = state.districtCounts.getOrDefault(dt, 0);
            state.districtCounts.put(dt, v + 1);
        }
    }

    public static void updateStatsForTemplate(RivalCityState state, String templateName) {
        String lower = templateName.toLowerCase();

        if (lower.contains("farm") || lower.contains("field")) {
            state.stats.onStructureBuilt(RivalFactionStats.StructureType.FARM);
        } else if (lower.contains("house") || lower.contains("home")) {
            state.stats.onStructureBuilt(RivalFactionStats.StructureType.HOUSING);
        } else if (lower.contains("tower") || lower.contains("watch")) {
            state.stats.onStructureBuilt(RivalFactionStats.StructureType.WATCHTOWER);
        } else if (lower.contains("castle") || lower.contains("fort")) {
            state.stats.onStructureBuilt(RivalFactionStats.StructureType.MILITARY_FORT);
        } else if (lower.contains("barrack") || lower.contains("camp")) {
            state.stats.onStructureBuilt(RivalFactionStats.StructureType.BARRACKS);
        } else if (lower.contains("mill") || lower.contains("smith") || lower.contains("mine")) {
            state.stats.onStructureBuilt(RivalFactionStats.StructureType.FACTORY);
        } else {
            state.stats.onStructureBuilt(RivalFactionStats.StructureType.HOUSING);
        }
    }

    public static RivalFactionStats.StructureType mapStructureType(String structure) {
        if (structure == null) return null;
        switch (structure.toUpperCase()) {
            case "SKYSCRAPER": return RivalFactionStats.StructureType.SKYSCRAPER;
            case "COOLING_TOWER": return RivalFactionStats.StructureType.COOLING_TOWER;
            case "FACTORY": case "FOUNDRY": return RivalFactionStats.StructureType.FACTORY;
            case "SILO": case "STORAGE_TANK": return RivalFactionStats.StructureType.SILO_STORAGE;
            case "ANTENNA": case "RADAR": return RivalFactionStats.StructureType.ANTENNA_RADAR;
            case "HOUSING": case "VILLAGE": return RivalFactionStats.StructureType.HOUSING;
            case "FARM": return RivalFactionStats.StructureType.FARM;
            case "FORT": case "BUNKER": return RivalFactionStats.StructureType.MILITARY_FORT;
            case "BARRACKS": return RivalFactionStats.StructureType.BARRACKS;
            case "WATCHTOWER": case "OUTPOST": return RivalFactionStats.StructureType.WATCHTOWER;
            default: return null;
        }
    }

    public static RivalCityState.DistrictType inferDistrictFromStructureType(String structure) {
        if (structure == null) return null;
        String s = structure.toUpperCase();

        if (s.contains("FARM")) return RivalCityState.DistrictType.AGRICULTURE;

        if (s.contains("FORT") || s.contains("WATCH") || s.contains("BUNKER") || s.contains("OUTPOST") || s.contains("BARRACK")) {
            return RivalCityState.DistrictType.MILITARY_GARRISON;
        }

        if (s.contains("FACTORY") || s.contains("FOUNDRY") || s.contains("COOLING") || s.contains("SILO") || 
                s.contains("STORAGE") || s.contains("TANK") || s.contains("POWER")) {
            return RivalCityState.DistrictType.INDUSTRIAL;
        }

        if (s.contains("RADAR") || s.contains("ANTENNA")) {
            return RivalCityState.DistrictType.INFRASTRUCTURE;
        }

        if (s.contains("SKYSCRAPER")) {
            return RivalCityState.DistrictType.RESIDENTIAL;
        }

        return RivalCityState.DistrictType.CIVIC_CORE;
    }
}
