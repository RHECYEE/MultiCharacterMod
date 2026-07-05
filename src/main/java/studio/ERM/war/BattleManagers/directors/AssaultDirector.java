package studio.ERM.war.BattleManagers.directors;

import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;
import studio.ERM.war.BattleManagers.api.BattleOutcome;
import studio.ERM.war.BattleManagers.api.IBattleDirector;
import studio.ERM.war.BattleManagers.cards.UnitCard;
import studio.ERM.war.BattleManagers.cards.UnitCardRegistry;
import studio.ERM.war.BattleManagers.entities.EntityFormationCarrier;
import studio.ERM.war.rival.RivalCityManager;

import java.util.ArrayList;
import java.util.List;

/**
 * v7 — Full-scale assault with multiple waves, scaled by warLevel.
 *
 * Wave 1: Infantry (ShieldWall/HeavyInfantry based on level)
 * Wave 2: Reinforcements (more infantry + ranged)
 * Wave 3 (L5+): Heavy units or vehicles
 *
 * Reinforcement points per level (configurable):
 *   L1-3: 2 waves
 *   L4-6: 3 waves (infantry + elite)
 *   L7+:  3 waves (infantry + elite + vehicles)
 */
public class AssaultDirector implements IBattleDirector {

    private final UnitCard card;

    private EntityPlayer activator;
    private BlockPos site;
    private int warLevel = 1;

    private List<EntityFormationCarrier> carriers = new ArrayList<>();

    private int tickAge = 0;
    private int phase = 0;
    private int maxPhases = 2;
    private boolean finished = false;
    private BattleOutcome outcome = BattleOutcome.ABORTED;

    private static final int MAX_DURATION_TICKS = 180 * 20;
    private static final int PHASE_DELAY_TICKS = 35 * 20;

    public AssaultDirector(UnitCard card) {
        this.card = card;
    }

    @Override
    public void start(World world, EntityPlayer activator, BlockPos battleSite) {
        this.activator = activator;
        this.site = getSurfacePos(world, battleSite);
        this.warLevel = Math.max(1, RivalCityManager.getRivalCityLevel());
        this.maxPhases = (warLevel <= 3) ? 2 : 3;

        if (world.isRemote) return;

        spawnWave(world, 1);
        phase = 1;
    }

    private void spawnWave(World world, int waveNum) {
        int count;
        String cardName;
        double distance;

        switch (waveNum) {
            case 1: // Infantry wave
                count = 2 + (warLevel / 3);
                cardName = (warLevel >= 4) ? "HeavyInfantry" : "ShieldWall";
                distance = 50.0;
                break;
            case 2: // Reinforcement wave
                count = 2 + (warLevel / 4);
                cardName = (warLevel >= 6) ? "MixedCompany" : "SkirmishLine";
                distance = 55.0;
                break;
            case 3: // Elite/vehicle wave (L5+)
                count = 1 + (warLevel / 5);
                cardName = (warLevel >= 8) ? "VehiclePlatoon" : "EliteSquad";
                distance = 60.0;
                break;
            default: return;
        }

        count = Math.min(count, 6);
        double baseAngle = world.rand.nextDouble() * Math.PI * 2;

        for (int i = 0; i < count; i++) {
            double angle = baseAngle + (i * (Math.PI * 2 / count));
            double spawnX = site.getX() + Math.cos(angle) * distance;
            double spawnZ = site.getZ() + Math.sin(angle) * distance;
            int spawnY = Math.max(62, world.getTopSolidOrLiquidBlock(
                    new BlockPos((int) spawnX, 64, (int) spawnZ)).getY());

            UnitCard waveCard = UnitCardRegistry.get(cardName);
            if (waveCard == null) waveCard = card;
            if (waveCard == null) waveCard = UnitCardRegistry.getDefault();

            EntityFormationCarrier carrier = new EntityFormationCarrier(world);
            carrier.setPosition(spawnX + 0.5, spawnY + 1.0, spawnZ + 0.5);
            carrier.configureFromCard(waveCard, warLevel);
            carrier.setBattleContext(activator, site);
            carrier.setMoveTarget(site, 0.06D);
            world.spawnEntity(carrier);
            carriers.add(carrier);
        }
    }

    @Override
    public void tick(World world) {
        if (world.isRemote || finished) return;
        tickAge++;

        if (tickAge > MAX_DURATION_TICKS) { forceResolve(BattleOutcome.VICTORY); return; }
        if (activator == null || activator.isDead) { forceResolve(BattleOutcome.DEFEAT); return; }

        carriers.removeIf(c -> c == null || c.isDead);

        // Spawn next wave on timer or when current wave cleared
        if (phase < maxPhases && (carriers.isEmpty() || tickAge > phase * PHASE_DELAY_TICKS)) {
            phase++;
            spawnWave(world, phase);
        }

        if (carriers.isEmpty() && phase >= maxPhases) {
            forceResolve(BattleOutcome.VICTORY);
            return;
        }

        // Chase player
        BlockPos playerPos = activator.getPosition();
        for (EntityFormationCarrier c : carriers) {
            if (c.getDistance(playerPos.getX(), playerPos.getY(), playerPos.getZ()) > 6.0)
                c.setMoveTarget(playerPos, 0.06D);
        }
    }

    @Override public boolean isFinished() { return finished; }

    @Override
    public void stop(World world) {
        if (world.isRemote) return;
        for (EntityFormationCarrier c : carriers) if (c != null && !c.isDead) c.setDead();
        carriers.clear();
    }

    @Override public String getDirectorId() { return "assault"; }

    public void forceResolve(BattleOutcome o) { this.outcome = o == null ? BattleOutcome.ABORTED : o; this.finished = true; }
    public BattleOutcome getOutcome() { return outcome; }
    public int getCurrentWave() { return phase; }
    public int getTotalWaves() { return maxPhases; }

    private BlockPos getSurfacePos(World world, BlockPos pos) {
        int y = Math.max(62, world.getTopSolidOrLiquidBlock(new BlockPos(pos.getX(), 64, pos.getZ())).getY());
        return new BlockPos(pos.getX(), y, pos.getZ());
    }
}
