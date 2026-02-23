package studio.ERM.handlers;

import net.minecraft.util.math.BlockPos;

/**
 * Tracks the location of the most recently placed invasion structure.
 * This ensures mobs spawn near the structure's center during wave phases.
 */
public class StructureTracker {

    private static BlockPos lastStructureCenter = null;
    private static int lastSpawnRadius = 0;

    /**
     * Sets the center and radius of the recently spawned structure.
     */
    public static void setLastStructureLocation(BlockPos center, int radius) {
        lastStructureCenter = center;
        lastSpawnRadius = radius;
        if (center != null) {
            studio.ERM.EpochRunnerMod.logger.info("Structure location tracked: " + center + " with radius " + radius);
        }
    }

    /**
     * Gets the center of the last spawned structure.
     * Returns null if no invasion structure is currently active.
     */
    public static BlockPos getLastStructureCenter() {
        return lastStructureCenter;
    }

    /**
     * Gets the radius for mob spawning around the last structure.
     */
    public static int getLastSpawnRadius() {
        return lastSpawnRadius;
    }
}