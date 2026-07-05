package studio.WorldSpawner.spawn;

import net.minecraft.world.World;
import studio.WorldSpawner.data.WorldSpawnerWorldData;

public class WorldSpawnManager {

    public static void tick() {}

    /** Per-world spawn tick driven by {@code WorldSpawnerModule}. */
    public static void tick(World world, WorldSpawnerWorldData data) {
        if (world == null || world.isRemote || data == null) return;
        // Spawn logic hook (currently a no-op placeholder).
    }
}
