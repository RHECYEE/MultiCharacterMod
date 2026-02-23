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
 * v7 — Ambush: enemy forces surround player from multiple directions.
 * Simple director: no terrain, no templates. Just squads converging.
 *
 * Scales with rival city level:
 *   L1-3: 2 squads of ShieldWall
 *   L4-6: 3 squads of HeavyInfantry
 *   L7-9: 3 squads + 1 EliteSquad
 *   L10:  4 squads + 1 VehiclePlatoon
 */
public class AmbushDirector implements IBattleDirector {

    private final UnitCard card;

    private EntityPlayer activator;
    private BlockPos site;
    private int warLevel = 1;

    private List<EntityFormationCarrier> carriers = new ArrayList<>();

    private int tickAge = 0;
    private boolean finished = false;
    private BattleOutcome outcome = BattleOutcome.ABORTED;

    private static final int MAX_DURATION_TICKS = 120 * 20;

    public AmbushDirector(UnitCard card) {
        this.card = card;
    }

    @Override
    public void start(World world, EntityPlayer activator, BlockPos battleSite) {
        this.activator = activator;
        this.site = battleSite;
        this.warLevel = Math.max(1, RivalCityManager.getRivalCityLevel());

        if (world.isRemote) return;

        // Surface Y
        BlockPos surface = world.getTopSolidOrLiquidBlock(new BlockPos(battleSite.getX(), 64, battleSite.getZ()));
        BlockPos surfaceSite = new BlockPos(battleSite.getX(), Math.max(62, surface.getY()), battleSite.getZ());

        // Scale squad count and composition with level
        int squadCount = (warLevel <= 3) ? 2 : (warLevel <= 6) ? 3 : (warLevel <= 9) ? 4 : 5;
        double distance = 60.0 + (warLevel * 3);
        double baseAngle = world.rand.nextDouble() * Math.PI * 2;

        for (int i = 0; i < squadCount; i++) {
            double angle = baseAngle + (i * (Math.PI * 2 / squadCount));
            double spawnX = surfaceSite.getX() + Math.cos(angle) * distance;
            double spawnZ = surfaceSite.getZ() + Math.sin(angle) * distance;
            int spawnY = world.getTopSolidOrLiquidBlock(new BlockPos((int) spawnX, 64, (int) spawnZ)).getY();
            spawnY = Math.max(62, spawnY);

            // Pick card based on level and squad index
            UnitCard squadCard = pickCardForSquad(i, squadCount);

            EntityFormationCarrier carrier = new EntityFormationCarrier(world);
            carrier.setPosition(spawnX + 0.5, spawnY + 1.0, spawnZ + 0.5);
            carrier.configureFromCard(squadCard, warLevel);
            carrier.setBattleContext(activator, surfaceSite);
            carrier.setMoveTarget(surfaceSite, 0.055D + (warLevel * 0.002D));
            world.spawnEntity(carrier);
            carriers.add(carrier);
        }
    }

    private UnitCard pickCardForSquad(int index, int total) {
        // Last squad at high levels gets elite/vehicle card
        if (index == total - 1 && warLevel >= 7) {
            UnitCard elite = UnitCardRegistry.get("EliteSquad");
            if (elite != null) return elite;
        }
        if (index == total - 1 && warLevel >= 10) {
            UnitCard vehicle = UnitCardRegistry.get("VehiclePlatoon");
            if (vehicle != null) return vehicle;
        }
        // Mid-level: heavy infantry
        if (warLevel >= 4) {
            UnitCard heavy = UnitCardRegistry.get("HeavyInfantry");
            if (heavy != null) return heavy;
        }
        return card != null ? card : UnitCardRegistry.getDefault();
    }

    @Override
    public void tick(World world) {
        if (world.isRemote || finished) return;
        tickAge++;

        if (tickAge > MAX_DURATION_TICKS) { forceResolve(BattleOutcome.VICTORY); return; }
        if (activator == null || activator.isDead) { forceResolve(BattleOutcome.ABORTED); return; }

        carriers.removeIf(c -> c == null || c.isDead);
        if (carriers.isEmpty()) { forceResolve(BattleOutcome.VICTORY); return; }

        // Chase player
        BlockPos playerPos = activator.getPosition();
        for (EntityFormationCarrier carrier : carriers) {
            if (carrier.getDistance(playerPos.getX(), playerPos.getY(), playerPos.getZ()) > 5.0)
                carrier.setMoveTarget(playerPos, 0.055D + (warLevel * 0.002D));
        }
    }

    @Override public boolean isFinished() { return finished; }

    @Override
    public void stop(World world) {
        if (world.isRemote) return;
        for (EntityFormationCarrier c : carriers) if (c != null && !c.isDead) c.setDead();
        carriers.clear();
    }

    @Override public String getDirectorId() { return "ambush"; }

    public void forceResolve(BattleOutcome outcome) {
        this.outcome = outcome == null ? BattleOutcome.ABORTED : outcome;
        this.finished = true;
    }

    public BattleOutcome getOutcome() { return outcome; }
}
