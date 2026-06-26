package studio.ERM.war.rival;

import net.minecraft.block.Block;
import net.minecraft.block.BlockGlass;
import net.minecraft.block.BlockStainedGlass;
import net.minecraft.block.state.IBlockState;
import net.minecraft.init.Blocks;
import net.minecraft.item.EnumDyeColor;
import net.minecraft.util.EnumFacing;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;
import studio.ERM.EpochRunnerMod;

import java.util.Random;

/**
 * PROCEDURAL BUILDING GENERATOR
 *
 * Generates procedural industrial/modern buildings for rival cities:
 * - Skyscrapers (rectangular prism + antenna)
 * - Cooling Towers (hyperboloid shape)
 * - Factories (sawtooth roof + smokestacks)
 * - Silos/Storage Tanks (cylinders)
 * - Antenna Farms (thin towers + dishes)
 *
 * Design principles:
 * - Simple math, impressive results
 * - City level affects materials and height
 * - Silhouette rule: center=tall, edges=short
 */
public class ProceduralBuildingGenerator {

    private static final Random rand = new Random();

    // ===== MATERIAL PALETTES BY LEVEL =====
    public static class MaterialPalette {
        public final IBlockState primary;
        public final IBlockState secondary;
        public final IBlockState accent;
        public final IBlockState glass;
        public final IBlockState light;
        public final IBlockState roof;

        public MaterialPalette(IBlockState primary, IBlockState secondary, IBlockState accent,
                               IBlockState glass, IBlockState light, IBlockState roof) {
            this.primary = primary;
            this.secondary = secondary;
            this.accent = accent;
            this.glass = glass;
            this.light = light;
            this.roof = roof;
        }
    }

    private static final MaterialPalette[] PALETTES = {
            // Level 1-3: Stone brick, basic
            new MaterialPalette(
                    Blocks.STONEBRICK.getDefaultState(),
                    Blocks.COBBLESTONE.getDefaultState(),
                    Blocks.STONE.getDefaultState(),
                    Blocks.GLASS.getDefaultState(),
                    Blocks.TORCH.getDefaultState(),
                    Blocks.STONE_SLAB.getDefaultState()
            ),
            // Level 4-5: Concrete, industrial
            new MaterialPalette(
                    Blocks.CONCRETE.getStateFromMeta(EnumDyeColor.GRAY.getMetadata()),
                    Blocks.CONCRETE.getStateFromMeta(EnumDyeColor.SILVER.getMetadata()),
                    Blocks.IRON_BLOCK.getDefaultState(),
                    Blocks.GLASS.getDefaultState(),
                    Blocks.GLOWSTONE.getDefaultState(),
                    Blocks.CONCRETE.getStateFromMeta(EnumDyeColor.BLACK.getMetadata())
            ),
            // Level 6-7: Modern concrete, steel, glass
            new MaterialPalette(
                    Blocks.CONCRETE.getStateFromMeta(EnumDyeColor.GRAY.getMetadata()),
                    Blocks.CONCRETE.getStateFromMeta(EnumDyeColor.SILVER.getMetadata()),
                    Blocks.IRON_BLOCK.getDefaultState(),
                    Blocks.STAINED_GLASS.getStateFromMeta(EnumDyeColor.LIGHT_BLUE.getMetadata()),
                    Blocks.SEA_LANTERN.getDefaultState(),
                    Blocks.QUARTZ_BLOCK.getDefaultState()
            ),
            // Level 8-10: Dark modern, lots of glass
            new MaterialPalette(
                    Blocks.CONCRETE.getStateFromMeta(EnumDyeColor.BLACK.getMetadata()),
                    Blocks.CONCRETE.getStateFromMeta(EnumDyeColor.GRAY.getMetadata()),
                    Blocks.QUARTZ_BLOCK.getDefaultState(),
                    Blocks.STAINED_GLASS.getStateFromMeta(EnumDyeColor.CYAN.getMetadata()),
                    Blocks.SEA_LANTERN.getDefaultState(),
                    Blocks.OBSIDIAN.getDefaultState()
            )
    };

    public static MaterialPalette getPalette(int level) {
        if (level <= 3) return PALETTES[0];
        if (level <= 5) return PALETTES[1];
        if (level <= 7) return PALETTES[2];
        return PALETTES[3];
    }

    // ===== SKYSCRAPER =====

    /**
     * Generate a skyscraper (default facing NORTH)
     */
    public static void generateSkyscraper(World world, BlockPos pos, int level) {
        generateSkyscraper(world, pos, level, EnumFacing.NORTH);
    }

    /**
     * Generate a skyscraper
     * @param world The world
     * @param pos Base position
     * @param level City level (1-10)
     * @param facing Direction to face (for door)
     */
    public static void generateSkyscraper(World world, BlockPos pos, int level, EnumFacing facing) {
        MaterialPalette palette = getPalette(level);

        // Dimensions scale with level
        int width = 8 + rand.nextInt(8);   // 8-15
        int depth = 8 + rand.nextInt(8);   // 8-15
        int height = 40 + (level * 8) + rand.nextInt(20); // 48-128

        // Cap height based on level
        height = Math.min(height, 40 + level * 10);

        int floors = height / 4;
        int windowSpacing = 2;

        // Build main tower
        for (int y = 0; y < height; y++) {
            int floor = y / 4;
            boolean isWindowRow = (y % 4 == 2);

            for (int x = 0; x < width; x++) {
                for (int z = 0; z < depth; z++) {
                    BlockPos blockPos = pos.add(x, y, z);

                    // Exterior walls only
                    boolean isExterior = x == 0 || x == width - 1 || z == 0 || z == depth - 1;
                    if (!isExterior) continue;

                    // Windows on window rows
                    if (isWindowRow && (x % windowSpacing == 1 || z % windowSpacing == 1)) {
                        // Window
                        if (level >= 6 && rand.nextFloat() < 0.1f) {
                            world.setBlockState(blockPos, palette.light, 2);
                        } else {
                            world.setBlockState(blockPos, palette.glass, 2);
                        }
                    } else {
                        // Wall
                        if (y % 4 == 0) {
                            world.setBlockState(blockPos, palette.secondary, 2);
                        } else {
                            world.setBlockState(blockPos, palette.primary, 2);
                        }
                    }
                }
            }
        }

        // Flat roof
        for (int x = 0; x < width; x++) {
            for (int z = 0; z < depth; z++) {
                world.setBlockState(pos.add(x, height, z), palette.roof, 2);
            }
        }

        // Antenna mast (25% of building height)
        int antennaHeight = height / 4;
        int antennaCenterX = width / 2;
        int antennaCenterZ = depth / 2;

        for (int y = 0; y < antennaHeight; y++) {
            world.setBlockState(pos.add(antennaCenterX, height + 1 + y, antennaCenterZ),
                    Blocks.IRON_BARS.getDefaultState(), 2);
        }

        // Blinking light at top
        world.setBlockState(pos.add(antennaCenterX, height + 1 + antennaHeight, antennaCenterZ),
                Blocks.REDSTONE_LAMP.getDefaultState(), 2);

        // Optional side vents at level 5+
        if (level >= 5) {
            addSideVents(world, pos, width, depth, height, palette);
        }

        EpochRunnerMod.logger.info("[PROCGEN] Skyscraper " + width + "x" + depth + "x" + height +
                " at " + pos);
    }

    private static void addSideVents(World world, BlockPos pos, int width, int depth, int height,
                                     MaterialPalette palette) {
        // Add small extrusions on sides
        int ventHeight = height / 3;
        int ventY = height / 2;

        // North side vent
        for (int y = 0; y < ventHeight; y += 4) {
            for (int x = width / 3; x < width * 2 / 3; x++) {
                world.setBlockState(pos.add(x, ventY + y, -1), palette.accent, 2);
            }
        }

        // South side vent
        for (int y = 0; y < ventHeight; y += 4) {
            for (int x = width / 3; x < width * 2 / 3; x++) {
                world.setBlockState(pos.add(x, ventY + y, depth), palette.accent, 2);
            }
        }
    }

    // ===== COOLING TOWER =====

    /**
     * Generate a cooling tower (hyperboloid shape)
     * @param world The world
     * @param pos Base center position
     * @param level City level
     */
    public static void generateCoolingTower(World world, BlockPos pos, int level) {
        MaterialPalette palette = getPalette(level);

        int height = 25 + (level * 2) + rand.nextInt(15); // 27-50
        int baseRadius = 10 + rand.nextInt(4); // 10-13
        int waistRadius = 6 + rand.nextInt(2); // 6-7 (narrowest point)
        int topRadius = baseRadius - 2;

        // Build the hyperboloid shape
        for (int y = 0; y < height; y++) {
            float t = (float) y / height;

            // Hyperboloid equation (simplified)
            // Radius starts at base, narrows to waist (middle), widens to top
            int radius;
            if (t < 0.5f) {
                // Bottom half: base to waist
                float blend = t * 2; // 0 to 1
                radius = (int)(baseRadius - (baseRadius - waistRadius) * Math.sin(blend * Math.PI / 2));
            } else {
                // Top half: waist to top
                float blend = (t - 0.5f) * 2; // 0 to 1
                radius = (int)(waistRadius + (topRadius - waistRadius) * Math.sin(blend * Math.PI / 2));
            }

            // Draw hollow cylinder at this Y level
            for (int x = -radius; x <= radius; x++) {
                for (int z = -radius; z <= radius; z++) {
                    double dist = Math.sqrt(x * x + z * z);

                    // Shell only (1-2 blocks thick)
                    if (dist >= radius - 2 && dist <= radius) {
                        BlockPos blockPos = pos.add(x, y, z);

                        // Vary material for texture
                        if (y % 5 == 0) {
                            world.setBlockState(blockPos, palette.secondary, 2);
                        } else {
                            world.setBlockState(blockPos, palette.primary, 2);
                        }
                    }
                }
            }
        }

        // Steam vent at top (optional visual)
        if (level >= 6) {
            world.setBlockState(pos.add(0, height, 0), Blocks.COBBLESTONE_WALL.getDefaultState(), 2);
        }

        EpochRunnerMod.logger.info("[PROCGEN] Cooling tower r=" + baseRadius + " h=" + height +
                " at " + pos);
    }

    // ===== FACTORY =====

    /**
     * Generate a factory with sawtooth roof
     * @param world The world
     * @param pos Base corner position
     * @param level City level
     * @param facing Factory orientation
     */
    public static void generateFactory(World world, BlockPos pos, int level, EnumFacing facing) {
        MaterialPalette palette = getPalette(level);

        int width = 20 + rand.nextInt(20);  // 20-39
        int depth = 20 + rand.nextInt(30);  // 20-49
        int height = 10 + rand.nextInt(8);  // 10-17

        // Main structure (hollow box)
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                for (int z = 0; z < depth; z++) {
                    BlockPos blockPos = pos.add(x, y, z);

                    boolean isWall = x == 0 || x == width - 1 || z == 0 || z == depth - 1;
                    boolean isFloor = y == 0;

                    if (isFloor) {
                        world.setBlockState(blockPos, palette.secondary, 2);
                    } else if (isWall) {
                        // Add windows on walls
                        if (y >= 3 && y <= height - 2 && (x + z) % 4 == 0) {
                            world.setBlockState(blockPos, palette.glass, 2);
                        } else {
                            world.setBlockState(blockPos, palette.primary, 2);
                        }
                    }
                }
            }
        }

        // Sawtooth roof (every 5 blocks)
        int sawtoothInterval = 5;
        for (int z = 0; z < depth; z++) {
            int sawtoothPhase = z % sawtoothInterval;
            int roofHeight = sawtoothPhase < sawtoothInterval / 2 ? sawtoothPhase : sawtoothInterval - sawtoothPhase;

            for (int x = 0; x < width; x++) {
                for (int h = 0; h <= roofHeight; h++) {
                    BlockPos blockPos = pos.add(x, height + h, z);

                    if (h == roofHeight) {
                        world.setBlockState(blockPos, palette.roof, 2);
                    } else if (x == 0 || x == width - 1) {
                        // Side of sawtooth
                        if (h > 0 && rand.nextFloat() < 0.3f) {
                            world.setBlockState(blockPos, palette.glass, 2);
                        } else {
                            world.setBlockState(blockPos, palette.primary, 2);
                        }
                    }
                }
            }
        }

        // Smokestacks
        int numSmokestacks = 2 + rand.nextInt(3);
        for (int i = 0; i < numSmokestacks; i++) {
            int sx = 3 + rand.nextInt(width - 6);
            int sz = 3 + rand.nextInt(depth - 6);
            int stackHeight = 10 + rand.nextInt(10);
            int stackRadius = 2 + rand.nextInt(2);

            generateCylinder(world, pos.add(sx, height, sz), stackRadius, stackHeight,
                    Blocks.BRICK_BLOCK.getDefaultState());
        }

        // Loading bay cutouts
        int numBays = 1 + rand.nextInt(3);
        for (int i = 0; i < numBays; i++) {
            int bayX = 2 + (i * 6);
            if (bayX >= width - 4) break;

            // Cut door hole
            for (int dy = 1; dy <= 4; dy++) {
                for (int dx = 0; dx < 3; dx++) {
                    world.setBlockState(pos.add(bayX + dx, dy, 0), Blocks.AIR.getDefaultState(), 2);
                }
            }
        }

        EpochRunnerMod.logger.info("[PROCGEN] Factory " + width + "x" + depth + "x" + height +
                " at " + pos);
    }

    // ===== SILO / STORAGE TANK =====

    /**
     * Generate a silo or storage tank (cylinder)
     * @param world The world
     * @param pos Base center position
     * @param level City level
     */
    public static void generateSilo(World world, BlockPos pos, int level) {
        MaterialPalette palette = getPalette(level);

        int radius = 4 + rand.nextInt(3); // 4-6
        int height = 15 + rand.nextInt(15); // 15-29
        boolean hasDome = rand.nextBoolean();

        // Main cylinder
        generateCylinder(world, pos, radius, height, palette.primary);

        // Top: dome or flat
        if (hasDome) {
            generateDome(world, pos.add(0, height, 0), radius, palette.primary);
        } else {
            // Flat cap
            for (int x = -radius; x <= radius; x++) {
                for (int z = -radius; z <= radius; z++) {
                    if (x * x + z * z <= radius * radius) {
                        world.setBlockState(pos.add(x, height, z), palette.roof, 2);
                    }
                }
            }
        }

        // Ladder on side
        for (int y = 0; y < height; y++) {
            world.setBlockState(pos.add(radius + 1, y, 0), Blocks.LADDER.getDefaultState(), 2);
        }

        // Pipes at base (level 5+)
        if (level >= 5) {
            for (int i = 0; i < 4; i++) {
                EnumFacing dir = EnumFacing.byHorizontalIndex(i);
                BlockPos pipeStart = pos.add(dir.getXOffset() * (radius + 1), 2, dir.getZOffset() * (radius + 1));
                for (int p = 0; p < 3 + rand.nextInt(5); p++) {
                    world.setBlockState(pipeStart.offset(dir, p), Blocks.IRON_BARS.getDefaultState(), 2);
                }
            }
        }

        EpochRunnerMod.logger.info("[PROCGEN] Silo r=" + radius + " h=" + height + " at " + pos);
    }

    /**
     * Generate a cluster of silos (always appear in groups)
     */
    public static void generateSiloCluster(World world, BlockPos pos, int level, int count) {
        count = Math.max(2, Math.min(6, count));

        int spacing = 12;
        for (int i = 0; i < count; i++) {
            int offsetX = (i % 3) * spacing + rand.nextInt(4) - 2;
            int offsetZ = (i / 3) * spacing + rand.nextInt(4) - 2;

            BlockPos siloPos = pos.add(offsetX, 0, offsetZ);
            siloPos = world.getTopSolidOrLiquidBlock(siloPos);

            generateSilo(world, siloPos, level);
        }
    }

    /**
     * Generate silo cluster with random count (2-6)
     */
    public static void generateSiloCluster(World world, BlockPos pos, int level) {
        generateSiloCluster(world, pos, level, 2 + rand.nextInt(5));
    }

    // ===== ANTENNA FARM / RADAR =====

    /**
     * Generate antenna tower
     * @param world The world
     * @param pos Base position
     * @param level City level
     */
    public static void generateAntennaTower(World world, BlockPos pos, int level) {
        int height = 30 + (level * 3) + rand.nextInt(30); // 33-90
        int baseWidth = 2;

        // Main tower (thin, 1-2 blocks wide)
        for (int y = 0; y < height; y++) {
            // Taper at top
            int width = y > height * 0.7 ? 1 : baseWidth;

            for (int x = 0; x < width; x++) {
                for (int z = 0; z < width; z++) {
                    BlockPos blockPos = pos.add(x, y, z);

                    if (y % 10 == 0) {
                        // Support strut
                        world.setBlockState(blockPos, Blocks.IRON_BLOCK.getDefaultState(), 2);
                    } else {
                        world.setBlockState(blockPos, Blocks.IRON_BARS.getDefaultState(), 2);
                    }
                }
            }

            // Guy wires at certain heights (represented by fence)
            if (y % 15 == 10 && y < height - 10) {
                for (EnumFacing dir : EnumFacing.HORIZONTALS) {
                    int wireLength = 5 + y / 10;
                    for (int w = 1; w <= wireLength; w++) {
                        BlockPos wirePos = pos.add(dir.getXOffset() * w, y - w / 2, dir.getZOffset() * w);
                        if (world.isAirBlock(wirePos)) {
                            world.setBlockState(wirePos, Blocks.IRON_BARS.getDefaultState(), 2);
                        }
                    }
                }
            }
        }

        // Dish at top (using stairs/slabs to approximate)
        BlockPos dishCenter = pos.add(0, height, 0);
        generateDish(world, dishCenter, 3);

        // Blinking light
        world.setBlockState(pos.add(0, height + 2, 0), Blocks.REDSTONE_LAMP.getDefaultState(), 2);

        EpochRunnerMod.logger.info("[PROCGEN] Antenna tower h=" + height + " at " + pos);
    }

    /**
     * Generate a radar dish (approximated with fences/slabs)
     */
    private static void generateDish(World world, BlockPos center, int radius) {
        // Simple dish: ring of blocks
        for (int x = -radius; x <= radius; x++) {
            for (int z = -radius; z <= radius; z++) {
                double dist = Math.sqrt(x * x + z * z);
                if (dist >= radius - 1 && dist <= radius) {
                    world.setBlockState(center.add(x, 0, z), Blocks.IRON_BARS.getDefaultState(), 2);
                }
            }
        }
        // Center strut
        world.setBlockState(center, Blocks.IRON_BLOCK.getDefaultState(), 2);
    }

    // ===== HELPER METHODS =====

    /**
     * Generate a filled cylinder
     */
    private static void generateCylinder(World world, BlockPos center, int radius, int height,
                                         IBlockState material) {
        for (int y = 0; y < height; y++) {
            for (int x = -radius; x <= radius; x++) {
                for (int z = -radius; z <= radius; z++) {
                    double dist = Math.sqrt(x * x + z * z);
                    // Hollow cylinder (shell only)
                    if (dist >= radius - 1 && dist <= radius) {
                        world.setBlockState(center.add(x, y, z), material, 2);
                    }
                }
            }
        }
    }

    /**
     * Generate a dome (half sphere)
     */
    private static void generateDome(World world, BlockPos center, int radius, IBlockState material) {
        for (int y = 0; y <= radius; y++) {
            // Radius at this height (sphere equation)
            int levelRadius = (int) Math.sqrt(radius * radius - y * y);

            for (int x = -levelRadius; x <= levelRadius; x++) {
                for (int z = -levelRadius; z <= levelRadius; z++) {
                    double dist = Math.sqrt(x * x + z * z);
                    // Shell only
                    if (dist >= levelRadius - 1 && dist <= levelRadius) {
                        world.setBlockState(center.add(x, y, z), material, 2);
                    }
                }
            }
        }
    }

    // ===== MILITARY STRUCTURES =====

    /**
     * Generate a military fort/bunker (default facing NORTH)
     */
    public static void generateFort(World world, BlockPos pos, int level) {
        generateFort(world, pos, level, EnumFacing.NORTH);
    }

    /**
     * Generate a military fort/bunker
     */
    public static void generateFort(World world, BlockPos pos, int level, EnumFacing facing) {
        MaterialPalette palette = getPalette(level);

        int size = 15 + rand.nextInt(10);
        int height = 6 + rand.nextInt(4);
        int wallThickness = 2;

        // Outer walls
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < size; x++) {
                for (int z = 0; z < size; z++) {
                    boolean isOuterWall = x < wallThickness || x >= size - wallThickness ||
                            z < wallThickness || z >= size - wallThickness;

                    if (isOuterWall) {
                        BlockPos blockPos = pos.add(x, y, z);

                        // Crenellations at top
                        if (y == height - 1 && ((x + z) % 2 == 0)) {
                            world.setBlockState(blockPos.up(), palette.primary, 2);
                        }

                        // Arrow slits
                        if (y == height / 2 && (x % 4 == 2 || z % 4 == 2)) {
                            world.setBlockState(blockPos, Blocks.AIR.getDefaultState(), 2);
                        } else {
                            world.setBlockState(blockPos, palette.primary, 2);
                        }
                    }
                }
            }
        }

        // Corner towers
        int towerHeight = height + 4;
        int towerRadius = 3;
        int[][] corners = {{0, 0}, {size - 1, 0}, {0, size - 1}, {size - 1, size - 1}};

        for (int[] corner : corners) {
            generateCylinder(world, pos.add(corner[0], 0, corner[1]), towerRadius, towerHeight,
                    palette.primary);
        }

        // Gate (facing the player direction)
        int gateX = size / 2;
        int gateZ = facing == EnumFacing.SOUTH ? size - 1 : 0;
        if (facing == EnumFacing.EAST || facing == EnumFacing.WEST) {
            gateX = facing == EnumFacing.EAST ? size - 1 : 0;
            gateZ = size / 2;
        }

        for (int dy = 1; dy <= 3; dy++) {
            for (int d = -1; d <= 1; d++) {
                BlockPos gatePos;
                if (facing.getAxis() == EnumFacing.Axis.Z) {
                    gatePos = pos.add(gateX + d, dy, gateZ);
                } else {
                    gatePos = pos.add(gateX, dy, gateZ + d);
                }
                world.setBlockState(gatePos, Blocks.AIR.getDefaultState(), 2);
            }
        }

        EpochRunnerMod.logger.info("[PROCGEN] Fort " + size + "x" + size + " at " + pos);
    }

    /**
     * Generate a watchtower
     */
    public static void generateWatchtower(World world, BlockPos pos, int level) {
        MaterialPalette palette = getPalette(level);

        int baseSize = 4;
        int height = 12 + rand.nextInt(8);

        // Tower shaft
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < baseSize; x++) {
                for (int z = 0; z < baseSize; z++) {
                    boolean isWall = x == 0 || x == baseSize - 1 || z == 0 || z == baseSize - 1;
                    if (isWall) {
                        world.setBlockState(pos.add(x, y, z), palette.primary, 2);
                    }
                }
            }
        }

        // Platform at top (overhang)
        int platformSize = baseSize + 2;
        for (int x = -1; x <= baseSize; x++) {
            for (int z = -1; z <= baseSize; z++) {
                world.setBlockState(pos.add(x, height, z), palette.secondary, 2);
            }
        }

        // Roof
        for (int y = 0; y < 3; y++) {
            int roofSize = platformSize - y;
            int offset = y / 2;
            for (int x = offset; x < baseSize - offset; x++) {
                for (int z = offset; z < baseSize - offset; z++) {
                    world.setBlockState(pos.add(x, height + 1 + y, z), palette.roof, 2);
                }
            }
        }

        // Ladder inside
        for (int y = 0; y < height; y++) {
            world.setBlockState(pos.add(1, y, 0), Blocks.LADDER.getDefaultState(), 2);
        }

        EpochRunnerMod.logger.info("[PROCGEN] Watchtower h=" + height + " at " + pos);
    }

    // ===== MAIN GENERATION METHOD =====

    /**
     * Generate a structure by type
     */

    /**
     * Returns an approximate footprint for collision/lot planning: [width, depth, height].
     * These are conservative bounds used by RivalCityManager to prevent overlaps and dirt-pillars.
     */
    public static int[] getFootprint(String structureType, int level) {
        if (structureType == null) return new int[]{14, 14, 18};

        String t = structureType.toUpperCase();
        int h;

        switch (t) {
            case "SKYSCRAPER":
                h = 18 + Math.min(60, level * 6);
                return new int[]{18, 18, h};

            case "COOLING_TOWER":
                h = 16 + Math.min(30, level * 3);
                return new int[]{16, 16, h};

            case "FACTORY":
            case "FOUNDRY":
                h = 10 + Math.min(18, level * 2);
                return new int[]{26, 18, h};

            case "SILO":
            case "STORAGE_TANK":
                h = 14 + Math.min(22, level * 2);
                return new int[]{14, 14, h};

            case "RADAR_PAD":
            case "ANTENNA_FARM":
                h = 20 + Math.min(40, level * 4);
                return new int[]{14, 14, h};

            case "POWER_PLANT":
                h = 14 + Math.min(26, level * 2);
                return new int[]{28, 22, h};

            case "BUNKER":
            case "FORT":
                h = 10 + Math.min(12, level);
                return new int[]{22, 22, h};

            case "WATCHTOWER":
            case "OUTPOST":
                h = 18 + Math.min(20, level * 2);
                return new int[]{12, 12, h};

            default:
                return new int[]{14, 14, 18};
        }
    }

    public static void generateStructure(World world, BlockPos pos, String structureType,
                                         int level, EnumFacing facing) {
        switch (structureType.toUpperCase()) {
            case "SKYSCRAPER":
                generateSkyscraper(world, pos, level, facing);
                break;
            case "COOLING_TOWER":
                generateCoolingTower(world, pos, level);
                break;
            case "FACTORY":
            case "FOUNDRY":
                generateFactory(world, pos, level, facing);
                break;
            case "SILO":
            case "STORAGE_TANK":
                generateSilo(world, pos, level);
                break;
            case "ANTENNA":
            case "RADAR":
                generateAntennaTower(world, pos, level);
                break;
            case "FORT":
            case "BUNKER":
                generateFort(world, pos, level, facing);
                break;
            case "WATCHTOWER":
            case "OUTPOST":
                generateWatchtower(world, pos, level);
                break;
            default:
                // Default to a simple house placeholder
                EpochRunnerMod.logger.warn("[PROCGEN] Unknown structure type: " + structureType);
                break;
        }
    }
}
