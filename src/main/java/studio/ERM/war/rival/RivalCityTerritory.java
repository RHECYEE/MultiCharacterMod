package studio.ERM.war.rival;

import net.minecraft.util.math.ChunkPos;
import net.minecraft.world.World;
import studio.ERM.war.world.WarWorldData;

import java.util.ArrayList;
import java.util.List;

/**
 * Claims / releases territory in {@link WarWorldData} for the Rival City.
 * This drives map-border rendering and any faction ownership logic that relies on chunk ownership.
 */
public final class RivalCityTerritory {

    private RivalCityTerritory() {}

    public static final String OWNER_KEY = "RIVAL";

    public static void applyTerritory(World world, WarWorldData wd, RivalCityData city) {
        if (world == null || wd == null || city == null || city.getCenter() == null) return;

        int radius = Math.max(0, city.radiusChunks);
        ChunkPos center = new ChunkPos(city.getCenter());

        List<ChunkPos> newOwned = new ArrayList<>();

        for (int dx = -radius; dx <= radius; dx++) {
            for (int dz = -radius; dz <= radius; dz++) {
                ChunkPos cp = new ChunkPos(center.x + dx, center.z + dz);

                // Square claim with a circular-ish falloff to avoid huge corners.
                int dist = Math.max(Math.abs(dx), Math.abs(dz));
                if (dist > radius) continue;

                wd.setOwner(cp, OWNER_KEY);
                newOwned.add(cp);
            }
        }

        // Mutate the final list in-place (ownedChunks is final for save stability).
        city.ownedChunks.clear();
        city.ownedChunks.addAll(newOwned);

        wd.markDirty();
    }

    public static void clearTerritory(WarWorldData wd, RivalCityData city) {
        if (wd == null || city == null) return;

        for (ChunkPos cp : city.ownedChunks) {
            if (cp == null) continue;
            // Reset ownership to neutral. WarWorldData.getOwner defaults to NEUTRAL if missing.
            wd.setOwner(cp, "NEUTRAL");
        }

        city.ownedChunks.clear();
        wd.markDirty();
    }
}
