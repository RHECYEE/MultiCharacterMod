package studio.ERM.war.BattleManagers.deployed;

import net.minecraftforge.common.config.Config;
import studio.ERM.EpochRunnerMod;

/**
 * Configuration for deployed battles / triggers.
 *
 * Separate config name to avoid colliding with other @Config classes.
 */
@Config(modid = EpochRunnerMod.MODID, name = EpochRunnerMod.MODID + "_battles")
public final class DeployedBattleConfig {

    private DeployedBattleConfig() {}

    @Config.Name("Deployed Battles")
    public static final DeployedBattles CATEGORY = new DeployedBattles();

    public static final class DeployedBattles {

        @Config.Name("Trigger Radius Blocks")
        @Config.Comment({
                "How close the owner must get to a deployed battle to trigger it.",
                "This should match how far away you want 'deployment' to begin."
        })
        @Config.RangeInt(min = 8, max = 512)
        public int triggerRadiusBlocks = 96;

        @Config.Name("Deploying Countdown Seconds")
        @Config.Comment({
                "If the active battle director supports phases, it should use this for the DEPLOYING->COMBAT countdown.",
                "Directors that do not implement IPhasedBattleDirector will ignore it."
        })
        @Config.RangeInt(min = 0, max = 60)
        public int deployingCountdownSeconds = 6;

        @Config.Name("Waypoint Name Prefix")
        @Config.Comment({
                "Prefix used when generating battle site names displayed on the tactical map."
        })
        public String waypointPrefix = "Battle Site";
    }
}
