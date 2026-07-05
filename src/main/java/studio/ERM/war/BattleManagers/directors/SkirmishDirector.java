package studio.ERM.war.BattleManagers.directors;

import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;
import studio.ERM.war.BattleManagers.api.BattleOutcome;
import studio.ERM.war.BattleManagers.api.IBattleDirector;
import studio.ERM.war.BattleManagers.cards.UnitCard;
import studio.ERM.war.BattleManagers.entities.EntityFormationCarrier;
import studio.ERM.war.rival.RivalCityManager;

/**
 * v7 — Small skirmish, single formation. Uses warLevel from rival city.
 */
public class SkirmishDirector implements IBattleDirector {

    private final UnitCard card;
    private EntityPlayer activator;
    private BlockPos site;
    private EntityFormationCarrier carrier;
    private int tickAge = 0;
    private boolean finished = false;
    private BattleOutcome outcome = BattleOutcome.ABORTED;
    private static final int MAX_DURATION_TICKS = 60 * 20;

    public SkirmishDirector(UnitCard card) { this.card = card; }

    @Override
    public void start(World world, EntityPlayer activator, BlockPos battleSite) {
        this.activator = activator;
        int warLevel = Math.max(1, RivalCityManager.getRivalCityLevel());

        int y = Math.max(62, world.getTopSolidOrLiquidBlock(new BlockPos(battleSite.getX(), 64, battleSite.getZ())).getY());
        this.site = new BlockPos(battleSite.getX(), y, battleSite.getZ());

        if (world.isRemote) return;

        double angle = world.rand.nextDouble() * Math.PI * 2;
        double distance = 35.0;
        double spawnX = site.getX() + Math.cos(angle) * distance;
        double spawnZ = site.getZ() + Math.sin(angle) * distance;
        int spawnY = Math.max(62, world.getTopSolidOrLiquidBlock(new BlockPos((int) spawnX, 64, (int) spawnZ)).getY());

        carrier = new EntityFormationCarrier(world);
        carrier.setPosition(spawnX + 0.5, spawnY + 1.0, spawnZ + 0.5);
        carrier.configureFromCard(card, warLevel);
        carrier.setBattleContext(activator, site);
        carrier.setMoveTarget(site, 0.08D);
        world.spawnEntity(carrier);
    }

    @Override
    public void tick(World world) {
        if (world.isRemote || finished) return;
        tickAge++;
        if (tickAge > MAX_DURATION_TICKS) { forceResolve(BattleOutcome.VICTORY); return; }
        if (activator == null || activator.isDead) { forceResolve(BattleOutcome.ABORTED); return; }
        if (carrier == null || carrier.isDead) { forceResolve(BattleOutcome.VICTORY); return; }

        BlockPos playerPos = activator.getPosition();
        if (carrier.getDistance(playerPos.getX(), playerPos.getY(), playerPos.getZ()) > 5.0)
            carrier.setMoveTarget(playerPos, 0.08D);
    }

    @Override public boolean isFinished() { return finished; }
    @Override public void stop(World world) { if (!world.isRemote && carrier != null && !carrier.isDead) carrier.setDead(); }
    @Override public String getDirectorId() { return "skirmish"; }
    public void forceResolve(BattleOutcome o) { this.outcome = o == null ? BattleOutcome.ABORTED : o; this.finished = true; }
    public BattleOutcome getOutcome() { return outcome; }
}
