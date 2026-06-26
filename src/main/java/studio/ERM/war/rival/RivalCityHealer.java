package studio.ERM.war.rival;

import net.minecraft.block.Block;
import net.minecraft.init.Blocks;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.util.EnumFacing;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;

import javax.annotation.Nullable;
import java.util.List;
import java.util.Random;

/**
 * Simple, pragmatic regeneration logic for the "punching bag" rival city.
 *
 * Design goal: when a plot is cratered or a structure is destroyed, the heal run
 * should (1) re-level the area with dirt, then (2) redevelop a replacement structure.
 *
 * This intentionally does not attempt perfect terrain blending; it prioritizes
 * reliability and predictable runtime cost.
 */
public final class RivalCityHealer {

    private RivalCityHealer() {
    }

    public static int heal(World world, @Nullable RivalCityData city, @Nullable RivalCityState state, Random rand) {
        if (world == null || world.isRemote) return 0;
        if (city == null || state == null || state.center == null) return 0;

        RivalCityConfig.ConfigData cfg = RivalCityConfig.get();
        int maxRepairs = Math.max(0, cfg.healMaxPlotsPerRun);
        if (maxRepairs <= 0) {
            // Still allow optional redevelopment growth to keep the city expanding.
            return 0;
        }

        int radius = Math.max(1, cfg.healRadiusBlocks);
        int fillDepth = Math.max(1, cfg.healFillDepth);
        int clearAbove = Math.max(0, cfg.healClearAbove);

        List<RivalCityData.BuildingRecord> records = city.placements;
        if (records == null || records.isEmpty()) return 0;

        int repaired = 0;

        // Iterate in reverse so we can safely remove/replace entries.
        for (int i = records.size() - 1; i >= 0 && repaired < maxRepairs; i--) {
            RivalCityData.BuildingRecord r = records.get(i);
            if (r == null || r.pos == null) continue;

            BlockPos plotCenter = r.pos;

            if (!isPlotDamaged(world, plotCenter)) continue;

            int targetY = computeTargetY(world, plotCenter, radius);
            levelWithDirt(world, plotCenter, targetY, radius, fillDepth, clearAbove);

            // Redevelop: place a replacement structure on the healed plot.
            String template = (r.template != null && !r.template.trim().isEmpty()) ? r.template : RivalCityTemplates.pickNextTemplate(state.level, rand);
            EnumFacing facing = (r.facing != null) ? r.facing : EnumFacing.NORTH;

            // Try the previous template first; if that fails, fall back to random pool.
            boolean placed = tryPlaceStructure(world, plotCenter, template, state.level, facing);
            if (!placed) {
                for (int t = 0; t < 3 && !placed; t++) {
                    String fallback = RivalCityTemplates.pickNextTemplate(state.level, rand);
                    EnumFacing f = EnumFacing.HORIZONTALS[rand.nextInt(EnumFacing.HORIZONTALS.length)];
                    placed = tryPlaceStructure(world, plotCenter, fallback, state.level, f);
                    if (placed) {
                        template = fallback;
                        facing = f;
                    }
                }
            }

            if (placed) {
                r.template = template;
                r.facing = facing;
                repaired++;
            }
        }

        return repaired;
    }

    private static boolean tryPlaceStructure(World world, BlockPos anchor, String template, int level, EnumFacing facing) {
        if (template == null || template.trim().isEmpty()) return false;
        try {
            // ProceduralBuildingGenerator.generateStructure is "void" in this repo;
            // treat exceptions as failure.
            ProceduralBuildingGenerator.generateStructure(world, anchor, template, level, facing);
            return true;
        } catch (Throwable t) {
            return false;
        }
    }

    private static boolean isPlotDamaged(World world, BlockPos plotCenter) {
        // Heuristic: sample a few points at/around the plot surface. If mostly air
        // (or if there's an obvious crater), treat as damaged.
        int airLike = 0;
        int samples = 0;

        int[] offsets = new int[]{0, 2, -2, 4, -4};
        for (int dx : offsets) {
            for (int dz : offsets) {
                BlockPos p = plotCenter.add(dx, 0, dz);
                BlockPos top = world.getHeight(p);

                // Check the top block itself and the block just below it.
                BlockPos b1 = top;
                BlockPos b0 = top.down();
                if (isAirLike(world, b1)) airLike++;
                if (isAirLike(world, b0)) airLike++;
                samples += 2;
            }
        }

        // Consider damaged if >= 60% air-like.
        return samples > 0 && airLike * 10 >= samples * 6;
    }

    private static boolean isAirLike(World world, BlockPos pos) {
        if (pos == null) return true;
        if (world.isAirBlock(pos)) return true;
        Block b = world.getBlockState(pos).getBlock();
        return b == Blocks.AIR;
    }

    private static int computeTargetY(World world, BlockPos center, int radius) {
        long sum = 0;
        int count = 0;

        for (int dx = -radius; dx <= radius; dx++) {
            for (int dz = -radius; dz <= radius; dz++) {
                BlockPos p = center.add(dx, 0, dz);
                int y = world.getHeight(p).getY();
                sum += y;
                count++;
            }
        }

        if (count <= 0) {
            return world.getHeight(center).getY();
        }

        int avg = (int) (sum / count);

        // Keep within sane range.
        int minY = 4;
        int maxY = world.getActualHeight() - 4;
        if (avg < minY) avg = minY;
        if (avg > maxY) avg = maxY;
        return avg;
    }

    private static void levelWithDirt(World world, BlockPos center, int targetY, int radius, int fillDepth, int clearAbove) {
        int yMin = Math.max(1, targetY - fillDepth);
        int yMax = Math.min(world.getActualHeight() - 1, targetY + Math.max(1, clearAbove));

        for (int dx = -radius; dx <= radius; dx++) {
            for (int dz = -radius; dz <= radius; dz++) {
                BlockPos col = center.add(dx, 0, dz);

                // Fill from yMin..targetY with dirt
                for (int y = yMin; y <= targetY; y++) {
                    BlockPos p = new BlockPos(col.getX(), y, col.getZ());
                    if (safeToOverwrite(world, p)) {
                        world.setBlockState(p, Blocks.DIRT.getDefaultState(), 2);
                    }
                }

                // Clear above to avoid floating debris blocking placement.
                for (int y = targetY + 1; y <= yMax; y++) {
                    BlockPos p = new BlockPos(col.getX(), y, col.getZ());
                    if (safeToOverwrite(world, p)) {
                        world.setBlockState(p, Blocks.AIR.getDefaultState(), 2);
                    }
                }
            }
        }
    }

    private static boolean safeToOverwrite(World world, BlockPos pos) {
        if (pos == null) return false;
        Block b = world.getBlockState(pos).getBlock();
        if (b == Blocks.BEDROCK) return false;
        TileEntity te = world.getTileEntity(pos);
        // Avoid nuking tile entities (chests, machines, etc.).
        return te == null;
    }
}
