package studio.ERM.war;

import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;

/**
 * Legacy facade for kicking off raids. Delegates to the authoritative
 * {@link WarStateAuthority} raid engine so older callers keep working.
 */
public class WarRaidSpawner {

    /** Start a raid of the given level near {@code center}. */
    public static void startRaid(World world, BlockPos center, int level, int count, EntityPlayer player) {
        if (world == null || world.isRemote || center == null) return;
        WarStateAuthority.get().startRaid(world, center, level);
    }

    /** Convenience overload used by the ambush system; era is treated as the raid level. */
    public static void startRaid(EntityPlayer player, int era) {
        if (player == null || player.world == null || player.world.isRemote) return;
        WarStateAuthority.get().startRaid(player.world, player.getPosition(), era);
    }

    /**
     * Per-tick hook. The authoritative raid state is advanced directly via
     * {@link WarStateAuthority#tick(World)}, so this facade does not tick again.
     */
    public static void tick(World world) {
        // Intentionally a no-op: WarStateAuthority is ticked by its own caller.
    }
}
