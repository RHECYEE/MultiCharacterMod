package studio.ERM.war.BattleManagers.cards;

import net.minecraft.util.math.Vec3d;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Determines how puppet slots are arranged around the carrier.
 */
public enum SpacingProfile {
    SHIELD_WALL,
    RING,
    LOOSE_SWARM;

    public List<Vec3d> buildOffsets(int count) {
        if (count <= 0) return Collections.emptyList();

        switch (this) {
            case SHIELD_WALL:
                return buildShieldWall(count);
            case RING:
                return buildRing(count);
            case LOOSE_SWARM:
            default:
                return buildLooseSwarm(count);
        }
    }

    private static List<Vec3d> buildShieldWall(int count) {
        // Simple grid: wide front line, a couple depth rows.
        // Count fills left->right, front->back.
        int cols = Math.max(4, (int) Math.ceil(Math.sqrt(count) * 1.6));
        int rows = (int) Math.ceil((double) count / (double) cols);

        double spacingX = 0.65D;
        double spacingZ = 0.75D;

        List<Vec3d> out = new ArrayList<>(count);
        int idx = 0;
        for (int r = 0; r < rows && idx < count; r++) {
            for (int c = 0; c < cols && idx < count; c++) {
                double x = (c - (cols - 1) / 2.0D) * spacingX;
                double z = (r * spacingZ) + 0.6D;
                out.add(new Vec3d(x, 0.0D, z));
                idx++;
            }
        }
        return out;
    }

    private static List<Vec3d> buildRing(int count) {
        double radius = Math.max(1.6D, Math.min(4.0D, 0.4D * count));
        List<Vec3d> out = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            double a = (Math.PI * 2.0D) * ((double) i / (double) count);
            out.add(new Vec3d(Math.cos(a) * radius, 0.0D, Math.sin(a) * radius));
        }
        return out;
    }

    private static List<Vec3d> buildLooseSwarm(int count) {
        // Deterministic pseudo-random scatter within a disc.
        // The carrier adds time-based jitter on top.
        double radius = Math.max(1.8D, Math.min(5.0D, 0.5D * Math.sqrt(count)));
        List<Vec3d> out = new ArrayList<>(count);

        long seed = 1337L + (count * 97L);
        for (int i = 0; i < count; i++) {
            seed = (seed * 6364136223846793005L + 1442695040888963407L);
            double r = radius * (0.35D + 0.65D * (((seed >>> 16) & 0xFFFF) / 65535.0D));
            seed = (seed * 6364136223846793005L + 1442695040888963407L);
            double a = (Math.PI * 2.0D) * (((seed >>> 16) & 0xFFFF) / 65535.0D);

            double x = Math.cos(a) * r;
            double z = Math.sin(a) * r;
            out.add(new Vec3d(x, 0.0D, z));
        }
        return out;
    }
}
