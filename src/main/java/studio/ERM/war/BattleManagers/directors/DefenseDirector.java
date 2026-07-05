package studio.ERM.war.BattleManagers.directors;

import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.text.TextComponentString;
import net.minecraft.world.World;
import studio.ERM.EpochRunnerMod;
import studio.ERM.war.BattleManagers.api.BattleOutcome;
import studio.ERM.war.BattleManagers.api.IBattleDirector;
import studio.ERM.war.BattleManagers.cards.UnitCard;
import studio.ERM.war.BattleManagers.cards.UnitCardRegistry;
import studio.ERM.war.BattleManagers.entities.EntityFormationCarrier;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/**
 * DEFENSE DIRECTOR
 * 
 * Defend a specific point from enemy waves. Enemies target the defense point.
 * Victory: Survive all waves without the point being "captured"
 * Defeat: Point captured (enemies stay too long) or player dies
 * 
 * The defense point is the battle site center.
 */
public class DefenseDirector implements IBattleDirector {

    private static final Random rand = new Random();

    // Configuration
    private final int totalWaves;
    private final int warLevel;

    // State
    private EntityPlayer activator;
    private BlockPos defensePoint;
    private int tickAge = 0;
    private boolean finished = false;
    private BattleOutcome outcome = BattleOutcome.ABORTED;

    // Wave management
    private int currentWave = 0;
    private int waveStartTick = 0;
    private boolean waveInProgress = false;

    // Capture mechanic
    private int captureProgress = 0;
    private static final int CAPTURE_THRESHOLD = 600; // 30 seconds of enemy presence

    // Spawned carriers
    private final List<EntityFormationCarrier> activeCarriers = new ArrayList<>();

    // Timing
    private static final int TICKS_BETWEEN_WAVES = 300; // 15 seconds
    private static final int SPAWN_RADIUS = 50;
    private static final int DEFENSE_RADIUS = 15;

    public DefenseDirector(int waves, int warLevel) {
        this.totalWaves = Math.max(1, Math.min(10, waves));
        this.warLevel = Math.max(1, Math.min(10, warLevel));
    }

    @Override
    public void start(World world, EntityPlayer activator, BlockPos battleSite) {
        this.activator = activator;
        this.defensePoint = battleSite;
        this.tickAge = 0;
        this.currentWave = 0;
        this.waveInProgress = false;
        this.captureProgress = 0;

        if (world.isRemote) return;

        activator.sendMessage(new TextComponentString(""));
        activator.sendMessage(new TextComponentString("§b§l🛡 DEFENSE BATTLE! 🛡"));
        activator.sendMessage(new TextComponentString("§7Defend this position from §e" + totalWaves + "§7 waves!"));
        activator.sendMessage(new TextComponentString("§7Don't let enemies capture the point!"));
        activator.sendMessage(new TextComponentString("§7War Level: §c" + warLevel));
        activator.sendMessage(new TextComponentString(""));

        EpochRunnerMod.logger.info("[DefenseDirector] Started: " + totalWaves + " waves, level " + warLevel);
    }

    @Override
    public void tick(World world) {
        if (world.isRemote) return;
        if (finished) return;

        tickAge++;

        // Check player status
        if (activator == null || activator.isDead) {
            endBattle(BattleOutcome.DEFEAT, "§c§lDEFEAT! §7The defender has fallen!");
            return;
        }

        // Clean up dead carriers
        activeCarriers.removeIf(c -> c == null || c.isDead);

        // Check capture progress
        int enemiesNearPoint = countEnemiesNearPoint();
        if (enemiesNearPoint > 0) {
            captureProgress += enemiesNearPoint;
            
            // Warn player periodically
            if (captureProgress > CAPTURE_THRESHOLD / 2 && tickAge % 40 == 0) {
                int percent = (captureProgress * 100) / CAPTURE_THRESHOLD;
                activator.sendMessage(new TextComponentString("§c⚠ Point being captured! §e" + percent + "%"));
            }
            
            if (captureProgress >= CAPTURE_THRESHOLD) {
                endBattle(BattleOutcome.DEFEAT, "§c§lDEFEAT! §7The defense point was captured!");
                return;
            }
        } else {
            // Slowly recover if no enemies near
            if (captureProgress > 0) {
                captureProgress = Math.max(0, captureProgress - 2);
            }
        }

        // Wave logic
        if (!waveInProgress) {
            if (currentWave >= totalWaves) {
                endBattle(BattleOutcome.VICTORY, "§a§lVICTORY! §7You successfully defended against all " + totalWaves + " waves!");
                return;
            }

            if (currentWave == 0 || (tickAge - waveStartTick >= TICKS_BETWEEN_WAVES)) {
                startNextWave(world);
            }
        } else {
            if (activeCarriers.isEmpty()) {
                waveInProgress = false;
                waveStartTick = tickAge;
                
                if (currentWave < totalWaves) {
                    activator.sendMessage(new TextComponentString("§a✓ Wave " + currentWave + " repelled!"));
                    activator.sendMessage(new TextComponentString("§7Prepare for the next assault..."));
                }
            }

            // Update carrier targets to the defense point
            for (EntityFormationCarrier carrier : activeCarriers) {
                if (carrier != null && !carrier.isDead) {
                    carrier.setSuppressionTarget(defensePoint);
                }
            }
        }
    }

    private int countEnemiesNearPoint() {
        int count = 0;
        for (EntityFormationCarrier carrier : activeCarriers) {
            if (carrier != null && !carrier.isDead) {
                double dist = carrier.getDistance(defensePoint.getX(), defensePoint.getY(), defensePoint.getZ());
                if (dist <= DEFENSE_RADIUS) {
                    count++;
                }
            }
        }
        return count;
    }

    private void startNextWave(World world) {
        currentWave++;
        waveInProgress = true;
        waveStartTick = tickAge;

        int formationsThisWave = 1 + (currentWave / 2) + (warLevel / 3);
        formationsThisWave = Math.min(formationsThisWave, 5);

        activator.sendMessage(new TextComponentString(""));
        activator.sendMessage(new TextComponentString("§c§l⚠ WAVE " + currentWave + "/" + totalWaves + " INCOMING! ⚠"));
        activator.sendMessage(new TextComponentString("§7" + formationsThisWave + " enemy formation(s) advancing!"));

        for (int i = 0; i < formationsThisWave; i++) {
            spawnFormation(world, i, formationsThisWave);
        }

        EpochRunnerMod.logger.info("[DefenseDirector] Wave " + currentWave + " started with " + formationsThisWave + " formations");
    }

    private void spawnFormation(World world, int index, int total) {
        double angle = (2 * Math.PI * index / total) + (rand.nextDouble() * 0.4 - 0.2);
        int spawnX = defensePoint.getX() + (int)(Math.cos(angle) * SPAWN_RADIUS);
        int spawnZ = defensePoint.getZ() + (int)(Math.sin(angle) * SPAWN_RADIUS);
        int spawnY = Math.max(62, world.getTopSolidOrLiquidBlock(new BlockPos(spawnX, 64, spawnZ)).getY());

        String cardName = (index % 2 == 0) ? "ShieldWall" : "SkirmishLine";
        UnitCard card = UnitCardRegistry.get(cardName);
        if (card == null) {
            card = UnitCardRegistry.get("ShieldWall");
        }
        if (card == null) return;

        EntityFormationCarrier carrier = new EntityFormationCarrier(world);
        carrier.setPosition(spawnX + 0.5, spawnY, spawnZ + 0.5);
        carrier.configureFromCard(card, this.warLevel);
        carrier.setBattleContext(activator, defensePoint);
        carrier.setSuppressionTarget(defensePoint);

        world.spawnEntity(carrier);
        activeCarriers.add(carrier);
    }

    private void endBattle(BattleOutcome result, String message) {
        this.outcome = result;
        this.finished = true;
        
        if (activator != null && !activator.isDead) {
            activator.sendMessage(new TextComponentString(""));
            activator.sendMessage(new TextComponentString(message));
            activator.sendMessage(new TextComponentString(""));
        }
        
        EpochRunnerMod.logger.info("[DefenseDirector] Battle ended: " + result.name());
    }

    @Override
    public boolean isFinished() {
        return finished;
    }

    @Override
    public void stop(World world) {
        if (world.isRemote) return;

        for (EntityFormationCarrier carrier : activeCarriers) {
            if (carrier != null && !carrier.isDead) {
                carrier.setDead();
            }
        }
        activeCarriers.clear();
    }

    @Override
    public String getDirectorId() {
        return "defense";
    }

    public void forceResolve(BattleOutcome outcome) {
        this.outcome = outcome == null ? BattleOutcome.ABORTED : outcome;
        this.finished = true;
    }

    public BattleOutcome getOutcome() {
        return outcome;
    }

    public int getCaptureProgress() {
        return captureProgress;
    }
}
