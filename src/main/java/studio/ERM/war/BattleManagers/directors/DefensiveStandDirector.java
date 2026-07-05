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
 * v7 — Defensive stand: hold position against waves. Scales with warLevel.
 *
 * Waves scale: L1-3 = 3 waves, L4-6 = 4 waves, L7+ = 5 waves.
 * Unit composition escalates each wave.
 */
public class DefensiveStandDirector implements IBattleDirector {

    private final UnitCard card;
    private EntityPlayer activator;
    private BlockPos site;
    private int warLevel = 1;
    private List<EntityFormationCarrier> activeCarriers = new ArrayList<>();
    private int tickAge = 0;
    private int currentWave = 0;
    private int totalWaves = 3;
    private int lastWaveSpawnTick = 0;
    private boolean finished = false;
    private BattleOutcome outcome = BattleOutcome.ABORTED;
    private static final int MAX_DURATION_TICKS = 180 * 20;
    private static final int WAVE_INTERVAL_TICKS = 25 * 20;

    public DefensiveStandDirector(UnitCard card) { this.card = card; }

    @Override
    public void start(World world, EntityPlayer activator, BlockPos battleSite) {
        this.activator = activator;
        this.warLevel = Math.max(1, RivalCityManager.getRivalCityLevel());
        this.totalWaves = (warLevel <= 3) ? 3 : (warLevel <= 6) ? 4 : 5;

        int y = Math.max(62, world.getTopSolidOrLiquidBlock(new BlockPos(battleSite.getX(), 64, battleSite.getZ())).getY());
        this.site = new BlockPos(battleSite.getX(), y, battleSite.getZ());

        if (world.isRemote) return;
        spawnWave(world, 1);
        currentWave = 1;
        lastWaveSpawnTick = 0;
    }

    private void spawnWave(World world, int waveNumber) {
        int unitsInWave = waveNumber + 1 + (warLevel / 4);
        unitsInWave = Math.min(unitsInWave, 5);
        double distance = 40.0;

        // Escalate card type per wave
        String cardName;
        if (waveNumber <= 1) cardName = "ShieldWall";
        else if (waveNumber <= 2) cardName = (warLevel >= 4) ? "HeavyInfantry" : "SkirmishLine";
        else if (waveNumber <= 3) cardName = (warLevel >= 6) ? "Phalanx" : "HeavyInfantry";
        else cardName = (warLevel >= 8) ? "EliteSquad" : "MixedCompany";

        for (int i = 0; i < unitsInWave; i++) {
            double angle = (i * (Math.PI * 2 / unitsInWave)) + (world.rand.nextDouble() * 0.5);
            double spawnX = site.getX() + Math.cos(angle) * distance;
            double spawnZ = site.getZ() + Math.sin(angle) * distance;
            int spawnY = Math.max(62, world.getTopSolidOrLiquidBlock(new BlockPos((int) spawnX, 64, (int) spawnZ)).getY());

            UnitCard waveCard = UnitCardRegistry.get(cardName);
            if (waveCard == null) waveCard = card;
            if (waveCard == null) waveCard = UnitCardRegistry.getDefault();

            EntityFormationCarrier carrier = new EntityFormationCarrier(world);
            carrier.setPosition(spawnX + 0.5, spawnY + 1.0, spawnZ + 0.5);
            carrier.configureFromCard(waveCard, warLevel);
            carrier.setBattleContext(activator, site);
            carrier.setMoveTarget(site, 0.05D + (waveNumber * 0.01D));
            world.spawnEntity(carrier);
            activeCarriers.add(carrier);
        }
    }

    @Override
    public void tick(World world) {
        if (world.isRemote || finished) return;
        tickAge++;
        if (tickAge > MAX_DURATION_TICKS) { forceResolve(BattleOutcome.VICTORY); return; }
        if (activator == null || activator.isDead) { forceResolve(BattleOutcome.DEFEAT); return; }

        activeCarriers.removeIf(c -> c == null || c.isDead);

        if (activeCarriers.isEmpty() && currentWave < totalWaves) {
            if (tickAge - lastWaveSpawnTick > WAVE_INTERVAL_TICKS / 2) {
                currentWave++;
                spawnWave(world, currentWave);
                lastWaveSpawnTick = tickAge;
            }
        } else if (tickAge - lastWaveSpawnTick > WAVE_INTERVAL_TICKS && currentWave < totalWaves) {
            currentWave++;
            spawnWave(world, currentWave);
            lastWaveSpawnTick = tickAge;
        }

        if (currentWave >= totalWaves && activeCarriers.isEmpty()) {
            forceResolve(BattleOutcome.VICTORY);
            return;
        }

        BlockPos playerPos = activator.getPosition();
        for (EntityFormationCarrier c : activeCarriers) {
            if (c.getDistance(playerPos.getX(), playerPos.getY(), playerPos.getZ()) > 5.0)
                c.setMoveTarget(playerPos, 0.05D + (currentWave * 0.01D));
        }
    }

    @Override public boolean isFinished() { return finished; }
    @Override public void stop(World world) {
        if (world.isRemote) return;
        for (EntityFormationCarrier c : activeCarriers) if (c != null && !c.isDead) c.setDead();
        activeCarriers.clear();
    }
    @Override public String getDirectorId() { return "defense"; }
    public void forceResolve(BattleOutcome o) { this.outcome = o == null ? BattleOutcome.ABORTED : o; this.finished = true; }
    public BattleOutcome getOutcome() { return outcome; }
}
