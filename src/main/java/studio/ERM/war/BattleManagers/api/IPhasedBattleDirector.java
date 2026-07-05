package studio.ERM.war.BattleManagers.api;

/**
 * Optional extension for directors that support phased deployments (Deploying -> Combat).
 *
 * BattleEngine will expose a bossbar while a phased director is active.
 */
public interface IPhasedBattleDirector extends IBattleDirector {

    enum BattlePhase {
        DEPLOYING,
        COMBAT
    }

    /**
     * Current phase for UI/engine behavior.
     */
    BattlePhase getBattlePhase();

    /**
     * 0..1 deployment progress used for bossbar fill while DEPLOYING.
     * If not applicable, return 0.
     */
    float getDeployProgress();

    /**
     * A short label for the bossbar, e.g. "Deploying" or "Combat".
     * BattleEngine will append the battle site name if provided.
     */
    String getPhaseLabel();
}
