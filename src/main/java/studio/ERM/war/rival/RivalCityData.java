package studio.ERM.war.rival;

import net.minecraft.util.EnumFacing;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.ChunkPos;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.List;

/**
 * Persistent data for a rival city, including building records.
 */
public class RivalCityData {

    /** All placed buildings. */
    public List<BuildingRecord> placements = new ArrayList<>();

    /** Claim radius in chunks (drives territory ownership). Scales with level. */
    public int radiusChunks = 4;

    /** Chunks currently owned by this city. Mutated in place for save stability. */
    public final List<ChunkPos> ownedChunks = new ArrayList<>();

    private BlockPos center;
    private int level = 1;

    @Nullable
    public BlockPos getCenter() {
        return center;
    }

    public void setCenter(@Nullable BlockPos center) {
        this.center = center;
    }

    public int getLevel() {
        return level;
    }

    public void setLevel(int level) {
        this.level = Math.max(1, level);
        // Larger cities claim more territory.
        this.radiusChunks = Math.max(4, 3 + this.level);
    }

    /** City influence radius in blocks, derived from the chunk claim radius. */
    public int getRadius() {
        return radiusChunks * 16;
    }

    /**
     * Register a building placement.
     */
    public void addBuilding(BlockPos pos, String template, EnumFacing facing) {
        BuildingRecord r = new BuildingRecord();
        r.pos = pos;
        r.template = template;
        r.facing = facing;
        placements.add(r);
    }

    /**
     * Lightweight record of a placed building.
     */
    public static class BuildingRecord {
        public BlockPos pos;
        public String template;
        public EnumFacing facing;
    }
}
