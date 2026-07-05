package studio.ERM.war.rival;

import net.minecraft.block.Block;
import net.minecraft.block.state.IBlockState;
import net.minecraft.init.Blocks;
import net.minecraft.util.EnumFacing;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;
import studio.ERM.EpochRunnerMod;

/**
 * RivalCityCoreProtection - Adds core protection features.
 * 
 * APPLY THESE CHANGES TO RivalCityGenerator:
 * 
 * 1. In tryGenerateAW2City(), after placing core structure at level 1:
 *    Call: RivalCityCoreProtection.generateMoatAndBridges(world, state);
 * 
 * 2. In pickNextGridCell(), add this check at the start:
 *    if (RivalCityCoreProtection.isInProtectedZone(state, gx, gz)) continue;
 * 
 * 3. In generateProceduralCity(), add protection check before placing structures
 */
public class RivalCityCoreProtection {

    /**
     * Check if a grid cell is in the protected core zone.
     */
    public static boolean isInProtectedZone(RivalCityState state, int gx, int gz) {
        if (state == null || state.center == null) return false;
        
        int spacing = Math.max(8, state.gridSpacing);
        int worldX = gx * spacing;
        int worldZ = gz * spacing;
        double dist = Math.sqrt(worldX * worldX + worldZ * worldZ);
        
        return dist < RivalCityConfig.coreExclusionRadius;
    }
    
    /**
     * Check if a world position is in the protected zone.
     */
    public static boolean isPositionProtected(BlockPos pos, BlockPos cityCenter) {
        return RivalCityConfig.isInCoreExclusionZone(pos, cityCenter);
    }
    
    /**
     * Generate moat and bridges around the core structure.
     * Call this after placing the core structure at level 1.
     */
    public static void generateMoatAndBridges(World world, RivalCityState state) {
        if (!RivalCityConfig.generateCoreMoat) return;
        if (world == null || state == null || state.center == null) return;
        
        BlockPos center = state.center;
        int moatRadius = RivalCityConfig.moatRadius;
        int moatWidth = RivalCityConfig.moatWidth;
        int moatDepth = RivalCityConfig.moatDepth;
        
        if (EpochRunnerMod.logger != null) {
            EpochRunnerMod.logger.info("[RIVAL] Generating moat around core at radius " + moatRadius);
        }
        
        // Generate circular moat
        for (int angle = 0; angle < 360; angle += 2) {
            double rad = Math.toRadians(angle);
            
            // Inner and outer edges of moat
            for (int w = -moatWidth/2; w <= moatWidth/2; w++) {
                int r = moatRadius + w;
                int x = center.getX() + (int)(Math.cos(rad) * r);
                int z = center.getZ() + (int)(Math.sin(rad) * r);
                
                // Skip bridge positions
                if (isBridgePosition(angle)) continue;
                
                // Get surface height
                BlockPos surface = world.getTopSolidOrLiquidBlock(new BlockPos(x, 64, z));
                
                // Dig moat
                for (int y = 0; y < moatDepth; y++) {
                    BlockPos pos = surface.down(y);
                    if (y == moatDepth - 1) {
                        // Bottom of moat - place water
                        world.setBlockState(pos, Blocks.WATER.getDefaultState(), 2);
                    } else {
                        // Sides of moat
                        world.setBlockState(pos, Blocks.AIR.getDefaultState(), 2);
                    }
                }
            }
        }
        
        // Generate bridges at cardinal directions
        if (RivalCityConfig.generateMoatBridges) {
            generateBridge(world, state, EnumFacing.NORTH);
            generateBridge(world, state, EnumFacing.SOUTH);
            generateBridge(world, state, EnumFacing.EAST);
            generateBridge(world, state, EnumFacing.WEST);
        }
    }
    
    /**
     * Check if an angle is a bridge position.
     */
    private static boolean isBridgePosition(int angle) {
        // Bridges at 0° (N), 90° (E), 180° (S), 270° (W)
        int tolerance = 8;  // Degrees to skip for bridge
        
        if (Math.abs(angle - 0) < tolerance || Math.abs(angle - 360) < tolerance) return true;
        if (Math.abs(angle - 90) < tolerance) return true;
        if (Math.abs(angle - 180) < tolerance) return true;
        if (Math.abs(angle - 270) < tolerance) return true;
        
        return false;
    }
    
    /**
     * Generate a wooden bridge across the moat.
     */
    private static void generateBridge(World world, RivalCityState state, EnumFacing direction) {
        BlockPos center = state.center;
        int moatRadius = RivalCityConfig.moatRadius;
        int moatWidth = RivalCityConfig.moatWidth;
        int bridgeWidth = RivalCityConfig.bridgeWidth;
        
        // Bridge position
        int dx = direction.getXOffset();
        int dz = direction.getZOffset();
        
        // Bridge spans from inside moat to outside
        int startR = moatRadius - moatWidth/2 - 2;
        int endR = moatRadius + moatWidth/2 + 2;
        
        IBlockState planks = Blocks.PLANKS.getDefaultState();
        IBlockState fence = Blocks.OAK_FENCE.getDefaultState();
        
        for (int r = startR; r <= endR; r++) {
            int bx = center.getX() + dx * r;
            int bz = center.getZ() + dz * r;
            
            // Get ground level
            BlockPos groundPos = world.getTopSolidOrLiquidBlock(new BlockPos(bx, 64, bz));
            int groundY = groundPos.getY();
            
            // Bridge deck
            for (int w = -bridgeWidth/2; w <= bridgeWidth/2; w++) {
                int px, pz;
                if (dx != 0) {
                    px = bx;
                    pz = bz + w;
                } else {
                    px = bx + w;
                    pz = bz;
                }
                
                BlockPos bridgePos = new BlockPos(px, groundY, pz);
                world.setBlockState(bridgePos, planks, 2);
                
                // Railings on edges
                if (Math.abs(w) == bridgeWidth/2) {
                    world.setBlockState(bridgePos.up(), fence, 2);
                }
            }
        }
        
        // Store bridge end as main street anchor
        BlockPos bridgeEnd = center.add(dx * endR, 0, dz * endR);
        state.majorIntersections.add(world.getTopSolidOrLiquidBlock(bridgeEnd));
        
        if (EpochRunnerMod.logger != null) {
            EpochRunnerMod.logger.info("[RIVAL] Generated bridge to " + direction);
        }
    }
    
    /**
     * Generate main streets radiating from bridge exits.
     * Call after bridges are placed.
     */
    public static void generateMainStreets(World world, RivalCityState state) {
        if (!RivalCityConfig.alignMainStreetToBridge) return;
        if (world == null || state == null || state.center == null) return;
        
        int streetLength = RivalCityConfig.CityZone.MAIN_STREET.outerRadius - RivalCityConfig.moatRadius;
        int streetWidth = 6;
        
        IBlockState stone = Blocks.COBBLESTONE.getDefaultState();
        IBlockState gravel = Blocks.GRAVEL.getDefaultState();
        
        // Streets in cardinal directions
        for (EnumFacing dir : EnumFacing.HORIZONTALS) {
            int dx = dir.getXOffset();
            int dz = dir.getZOffset();
            
            int startR = RivalCityConfig.moatRadius + RivalCityConfig.moatWidth/2 + 4;
            
            for (int r = startR; r < startR + streetLength; r++) {
                int cx = state.center.getX() + dx * r;
                int cz = state.center.getZ() + dz * r;
                
                for (int w = -streetWidth/2; w <= streetWidth/2; w++) {
                    int px, pz;
                    if (dx != 0) {
                        px = cx;
                        pz = cz + w;
                    } else {
                        px = cx + w;
                        pz = cz;
                    }
                    
                    BlockPos surface = world.getTopSolidOrLiquidBlock(new BlockPos(px, 64, pz)).down();
                    
                    // Center of street is gravel, edges are stone
                    if (Math.abs(w) <= 1) {
                        world.setBlockState(surface, gravel, 2);
                    } else {
                        world.setBlockState(surface, stone, 2);
                    }
                }
            }
        }
    }
}
