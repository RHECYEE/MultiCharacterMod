package studio.ERM.war.BattleManagers.api;

import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;
import net.minecraftforge.fml.common.eventhandler.Event;

/**
 * Fired server-side when a battle resolves.
 * Use this to spawn reward structures, grant rewards, and alter rival claims/state.
 */
public class BattleResolvedEvent extends Event {

    public final World world;
    public final EntityPlayer activator;
    public final BlockPos battleSite;
    public final String directorId;
    public final BattleOutcome outcome;

    public final int enemyCasualtiesEstimated;
    public final int enemyFormationsSpawned;

    public BattleResolvedEvent(World world,
                              EntityPlayer activator,
                              BlockPos battleSite,
                              String directorId,
                              BattleOutcome outcome,
                              int enemyCasualtiesEstimated,
                              int enemyFormationsSpawned) {
        this.world = world;
        this.activator = activator;
        this.battleSite = battleSite;
        this.directorId = directorId;
        this.outcome = outcome;
        this.enemyCasualtiesEstimated = enemyCasualtiesEstimated;
        this.enemyFormationsSpawned = enemyFormationsSpawned;
    }
}
