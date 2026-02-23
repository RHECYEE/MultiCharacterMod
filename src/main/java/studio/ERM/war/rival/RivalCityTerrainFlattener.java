package studio.ERM.war.rival;

import net.minecraft.block.state.IBlockState;
import net.minecraft.init.Blocks;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;

/**
 * Handles terrain flattening and preparation for rival city spawning.
 * Creates a realistic city foundation by flattening terrain.
 */
public class RivalCityTerrainFlattener {
    
    /**
     * Flatten terrain around a center point to create a city foundation.
     * 
     * @param world The world
     * @param center Center position
     * @param radius Flattening radius
     * @param targetHeight Target Y level for flattening
     * @param smooth Whether to smooth edges
     */
    public static void flattenTerrain(World world, BlockPos center, int radius, int targetHeight, boolean smooth) {
        int smoothRadius = smooth ? RivalCityConfig.FLATTEN_SMOOTH_RADIUS : 0;
        int totalRadius = radius + smoothRadius;
        
        for (int x = -totalRadius; x <= totalRadius; x++) {
            for (int z = -totalRadius; z <= totalRadius; z++) {
                BlockPos pos = center.add(x, 0, z);
                double distance = Math.sqrt(x * x + z * z);
                
                if (distance <= radius) {
                    // Inside main circle - full flatten
                    flattenColumn(world, pos, targetHeight);
                } else if (smooth && distance <= totalRadius) {
                    // In smooth transition zone
                    double blend = 1.0 - ((distance - radius) / smoothRadius);
                    int currentHeight = world.getHeight(pos).getY();
                    int blendedHeight = (int) (targetHeight * blend + currentHeight * (1.0 - blend));
                    flattenColumn(world, pos, blendedHeight);
                }
            }
        }
    }
    
    /**
     * Flatten a single column to target height.
     */
    private static void flattenColumn(World world, BlockPos basePos, int targetHeight) {
        // Get current surface height
        int surfaceY = world.getHeight(basePos).getY();
        
        if (surfaceY > targetHeight) {
            // Remove blocks above target
            for (int y = targetHeight + 1; y <= surfaceY + 10; y++) {
                BlockPos pos = new BlockPos(basePos.getX(), y, basePos.getZ());
                if (!world.isAirBlock(pos)) {
                    world.setBlockState(pos, Blocks.AIR.getDefaultState(), 2);
                }
            }
            
            // Set surface block
            BlockPos surfacePos = new BlockPos(basePos.getX(), targetHeight, basePos.getZ());
            world.setBlockState(surfacePos, Blocks.GRASS.getDefaultState(), 2);
            
            // Fill with dirt below
            for (int y = targetHeight - 1; y > targetHeight - 5; y--) {
                BlockPos pos = new BlockPos(basePos.getX(), y, basePos.getZ());
                world.setBlockState(pos, Blocks.DIRT.getDefaultState(), 2);
            }
            
        } else if (surfaceY < targetHeight) {
            // Fill up to target
            for (int y = surfaceY; y < targetHeight; y++) {
                BlockPos pos = new BlockPos(basePos.getX(), y, basePos.getZ());
                if (y < targetHeight - 1) {
                    world.setBlockState(pos, Blocks.DIRT.getDefaultState(), 2);
                } else {
                    world.setBlockState(pos, Blocks.GRASS.getDefaultState(), 2);
                }
            }
        }
        
        // Add foundation layer (stone) for stability
        for (int y = targetHeight - 5; y < targetHeight - 1; y++) {
            BlockPos pos = new BlockPos(basePos.getX(), y, basePos.getZ());
            IBlockState current = world.getBlockState(pos);
            if (current.getBlock() == Blocks.AIR || current.getBlock() == Blocks.WATER) {
                world.setBlockState(pos, Blocks.STONE.getDefaultState(), 2);
            }
        }
    }
    
    /**
     * Create roads/paths radiating from center (optional enhancement).
     */
    public static void createRoads(World world, BlockPos center, int radius) {
        // Create 4 main roads (N, S, E, W)
        int roadWidth = 3;
        
        // North-South road
        for (int z = -radius; z <= radius; z++) {
            for (int x = -roadWidth/2; x <= roadWidth/2; x++) {
                BlockPos roadPos = new BlockPos(center.getX() + x, center.getY(), center.getZ() + z);
                BlockPos above = roadPos.up();
                
                world.setBlockState(roadPos, Blocks.STONE.getDefaultState(), 2);
                world.setBlockState(above, Blocks.AIR.getDefaultState(), 2);
            }
        }
        
        // East-West road
        for (int x = -radius; x <= radius; x++) {
            for (int z = -roadWidth/2; z <= roadWidth/2; z++) {
                BlockPos roadPos = new BlockPos(center.getX() + x, center.getY(), center.getZ() + z);
                BlockPos above = roadPos.up();
                
                world.setBlockState(roadPos, Blocks.STONE.getDefaultState(), 2);
                world.setBlockState(above, Blocks.AIR.getDefaultState(), 2);
            }
        }
    }
    
    /**
     * Clear trees and vegetation in an area.
     */
    public static void clearVegetation(World world, BlockPos center, int radius) {
        for (int x = -radius; x <= radius; x++) {
            for (int z = -radius; z <= radius; z++) {
                if (x * x + z * z > radius * radius) continue;
                
                BlockPos basePos = center.add(x, 0, z);
                int surfaceY = world.getHeight(basePos).getY();
                
                // Clear vegetation up to 20 blocks above surface
                for (int y = surfaceY; y < surfaceY + 20; y++) {
                    BlockPos pos = new BlockPos(basePos.getX(), y, basePos.getZ());
                    IBlockState state = world.getBlockState(pos);
                    
                    // Remove leaves, logs, plants
                    if (state.getBlock() == Blocks.LOG ||
                        state.getBlock() == Blocks.LOG2 ||
                        state.getBlock() == Blocks.LEAVES ||
                        state.getBlock() == Blocks.LEAVES2 ||
                        state.getBlock() == Blocks.TALLGRASS ||
                        state.getBlock() == Blocks.DOUBLE_PLANT ||
                        state.getBlock() == Blocks.RED_FLOWER ||
                        state.getBlock() == Blocks.YELLOW_FLOWER ||
                        state.getBlock() == Blocks.SAPLING ||
                        state.getBlock() == Blocks.VINE) {
                        world.setBlockState(pos, Blocks.AIR.getDefaultState(), 2);
                    }
                }
            }
        }
    }
}
