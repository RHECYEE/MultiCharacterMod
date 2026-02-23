package studio.ERM.war.BattleManagers.api;

import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;

/**
 * A Battle Director is one "match instance" that owns objectives, phases, pacing,
 * and issues high-level orders to unit controllers.
 *
 * Battles are NOT persistent: if the world unloads, they are invalidated.
 */
public interface IBattleDirector {

    /**
     * Called once when the battle is activated/spawned.
     */
    void start(World world, EntityPlayer activator, BlockPos battleSite);

    /**
     * Called every tick while the battle is active (only while players are nearby).
     */
    void tick(World world);

    /**
     * True when the director has ended the battle (victory/defeat/surrender/abort).
     */
    boolean isFinished();

    /**
     * Called once after finished; should perform minimal cleanup and emit resolved events.
     */
    void stop(World world);

    /**
     * Human-readable name for logs/debug overlays.
     */
    String getDirectorId();
}
