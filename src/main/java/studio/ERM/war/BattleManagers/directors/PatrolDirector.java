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
 * PATROL ENCOUNTER DIRECTOR
 *
 * Purpose: Medium-scale dynamic encounter — territory friction.
 * "You ran into each other."
 *
 * Phases:
 *   Phase 1 — Contact:       1–2 small groups engage from different angles.
 *   Phase 2 — Escalation:    Reinforcement wave arrives after first contact.
 *   Phase 3 — Break contact: Surviving carriers withdraw after heavy losses.
 *
 * Spawn: Multiple carriers from different directions, closing in gradually.
 * No surprise advantage — both sides see each other coming.
 *
 * Victory: Eliminate majority of patrol forces.
 * Defeat:  Player retreats or is wiped.
 *
 * Duration target: 5–12 minutes (6000–14400 ticks).
 */
public class PatrolDirector implements IBattleDirector {

    private final UnitCard card;
    private EntityPlayer activator;
    private BlockPos site;
    private int warLevel = 1;

    // All carriers (both initial and reinforcement)
    private final List<EntityFormationCarrier> carriers = new ArrayList<>();

    private int tickAge = 0;
    private int phase = 1;
    private boolean reinforcementSpawned = false;
    private boolean breakContactTriggered = false;
    private boolean finished = false;
    private BattleOutcome outcome = BattleOutcome.ABORTED;

    // Phase timings
    private static final int MAX_DURATION_TICKS    = 12 * 60 * 20; // 12 minutes
    private static final int REINFORCEMENT_DELAY   = 3 * 60 * 20;  // 3 minutes after contact
    private static final int BREAK_CONTACT_TICKS   = 9 * 60 * 20;  // 9 minutes — survivors start withdrawing

    // Engagement
    private static final double DETECTION_RANGE   = 35.0;
    private static final double INITIAL_DISTANCE  = 55.0;
    private static final double REINFORCE_DISTANCE = 70.0;

    public PatrolDirector(UnitCard card) { this.card = card; }

    @Override
    public void start(World world, EntityPlayer activator, BlockPos battleSite) {
        this.activator = activator;
        this.warLevel = Math.max(1, RivalCityManager.getRivalCityLevel());

        int y = Math.max(62, world.getTopSolidOrLiquidBlock(
                new BlockPos(battleSite.getX(), 64, battleSite.getZ())).getY());
        this.site = new BlockPos(battleSite.getX(), y, battleSite.getZ());

        if (world.isRemote) return;

        spawnInitialPatrol(world);
    }

    // ─── Phase 1: initial patrol groups closing from different directions ───

    private void spawnInitialPatrol(World world) {
        // Scale group count with level: L1-3: 2 groups, L4-6: 3 groups, L7+: 4 groups
        int groupCount = (warLevel <= 3) ? 2 : (warLevel <= 6) ? 3 : 4;
        double baseAngle = world.rand.nextDouble() * Math.PI * 2;

        for (int i = 0; i < groupCount; i++) {
            double angle = baseAngle + (i * (Math.PI * 2.0 / groupCount));
            // Slight angle jitter so groups don't form a perfect ring
            angle += (world.rand.nextDouble() - 0.5) * 0.4;

            double dist = INITIAL_DISTANCE + (world.rand.nextDouble() * 15 - 7);
            spawnCarrierAt(world, angle, dist, pickInitialCard(), /*orbit=*/false);
        }

        // At L5+ add one orbiting scout that patrols until engaged
        if (warLevel >= 5) {
            EntityFormationCarrier scout = spawnCarrierAt(world,
                    baseAngle + Math.PI, INITIAL_DISTANCE - 10, pickScoutCard(), /*orbit=*/true);
            if (scout != null) {
                scout.setOrbitMode(site, 40.0, 0.025);
            }
        }
    }

    // ─── Phase 2: reinforcement wave arrives mid-battle ───

    private void spawnReinforcements(World world) {
        int reinforceCount = (warLevel <= 4) ? 1 : (warLevel <= 7) ? 2 : 3;
        double baseAngle = world.rand.nextDouble() * Math.PI * 2;

        for (int i = 0; i < reinforceCount; i++) {
            double angle = baseAngle + (i * (Math.PI * 2.0 / reinforceCount));
            spawnCarrierAt(world, angle, REINFORCE_DISTANCE, pickReinforceCard(), /*orbit=*/false);
        }
    }

    private EntityFormationCarrier spawnCarrierAt(World world, double angle, double dist, UnitCard unitCard, boolean orbit) {
        double spawnX = site.getX() + Math.cos(angle) * dist;
        double spawnZ = site.getZ() + Math.sin(angle) * dist;
        int spawnY = Math.max(62, world.getTopSolidOrLiquidBlock(
                new BlockPos((int) spawnX, 64, (int) spawnZ)).getY());

        if (unitCard == null) unitCard = card != null ? card : UnitCardRegistry.getDefault();

        EntityFormationCarrier carrier = new EntityFormationCarrier(world);
        carrier.setPosition(spawnX + 0.5, spawnY + 1.0, spawnZ + 0.5);
        carrier.configureFromCard(unitCard, warLevel);
        carrier.setBattleContext(activator, site);

        if (!orbit) {
            // Move toward site but slowly — gradual approach
            double speed = 0.045 + (warLevel * 0.002);
            carrier.setMoveTarget(site, speed);
        }

        world.spawnEntity(carrier);
        carriers.add(carrier);
        return carrier;
    }

    @Override
    public void tick(World world) {
        if (world.isRemote || finished) return;
        tickAge++;

        // Hard timeout
        if (tickAge > MAX_DURATION_TICKS) { forceResolve(BattleOutcome.VICTORY); return; }
        if (activator == null || activator.isDead) { forceResolve(BattleOutcome.ABORTED); return; }

        // Prune dead carriers
        carriers.removeIf(c -> c == null || c.isDead);

        if (carriers.isEmpty()) {
            forceResolve(BattleOutcome.VICTORY);
            return;
        }

        BlockPos playerPos = activator.getPosition();

        // ─── Phase 1 → Phase 2 transition: spawn reinforcements after delay ───
        if (phase == 1 && !reinforcementSpawned && tickAge >= REINFORCEMENT_DELAY) {
            spawnReinforcements(world);
            reinforcementSpawned = true;
            phase = 2;
        }

        // ─── Phase 3: break contact — surviving carriers withdraw ───
        if (!breakContactTriggered && tickAge >= BREAK_CONTACT_TICKS) {
            breakContactTriggered = true;
            phase = 3;
            triggerBreakContact(world);
        }

        // ─── Per-carrier behavior based on phase ───
        for (EntityFormationCarrier carrier : carriers) {
            double dist = carrier.getDistance(
                    playerPos.getX(), playerPos.getY(), playerPos.getZ());

            if (phase == 3) {
                // Break contact: carriers move away from player
                moveAwayFromPlayer(carrier, playerPos);
            } else {
                // Phases 1 & 2: close in on player once in detection range
                if (dist < DETECTION_RANGE) {
                    double speed = 0.06 + (warLevel * 0.003) + (phase * 0.01);
                    carrier.setMoveTarget(playerPos, speed);
                } else if (dist > DETECTION_RANGE * 1.5) {
                    // Still approaching but slower before detection
                    carrier.setMoveTarget(site, 0.04 + (warLevel * 0.002));
                }
            }
        }

        // Phase 3: finish once all carriers have moved far away or died
        if (phase == 3) {
            boolean allGone = carriers.stream().allMatch(c -> {
                double d = c.getDistance(playerPos.getX(), playerPos.getY(), playerPos.getZ());
                return d > 80.0 || c.isDead;
            });
            if (allGone) {
                forceResolve(BattleOutcome.VICTORY);
            }
        }
    }

    private void triggerBreakContact(World world) {
        // Kill the weakest half of remaining carriers to simulate routing
        int toRoute = Math.max(1, carriers.size() / 2);
        for (int i = 0; i < toRoute && i < carriers.size(); i++) {
            EntityFormationCarrier c = carriers.get(i);
            if (c != null && !c.isDead) c.setDead();
        }
        carriers.removeIf(c -> c == null || c.isDead);
    }

    private void moveAwayFromPlayer(EntityFormationCarrier carrier, BlockPos playerPos) {
        // Move to a point directly opposite the player relative to site
        double dx = site.getX() - playerPos.getX();
        double dz = site.getZ() - playerPos.getZ();
        double len = Math.sqrt(dx * dx + dz * dz);
        if (len < 1.0) return;
        double nx = dx / len;
        double nz = dz / len;
        BlockPos retreat = new BlockPos(
                (int)(site.getX() + nx * 80),
                site.getY(),
                (int)(site.getZ() + nz * 80));
        carrier.setMoveTarget(retreat, 0.07 + (warLevel * 0.003));
    }

    // ─── Card selection helpers ───

    private UnitCard pickInitialCard() {
        if (warLevel <= 2) return getCard("SkirmishLine");
        if (warLevel <= 4) return getCard("ShieldWall");
        if (warLevel <= 6) return getCard("HeavyInfantry");
        if (warLevel <= 8) return getCard("Phalanx");
        return getCard("EliteSquad");
    }

    private UnitCard pickScoutCard() {
        return getCard("ScoutTeam");
    }

    private UnitCard pickReinforceCard() {
        if (warLevel <= 3) return getCard("ShieldWall");
        if (warLevel <= 6) return getCard("HeavyInfantry");
        if (warLevel <= 8) return getCard("Phalanx");
        return getCard("EliteSquad");
    }

    private UnitCard getCard(String name) {
        UnitCard c = UnitCardRegistry.get(name);
        return c != null ? c : (card != null ? card : UnitCardRegistry.getDefault());
    }

    @Override
    public boolean isFinished() { return finished; }

    @Override
    public void stop(World world) {
        if (world.isRemote) return;
        for (EntityFormationCarrier c : new ArrayList<>(carriers)) {
            if (c != null && !c.isDead) c.setDead();
        }
        carriers.clear();
    }

    @Override
    public String getDirectorId() { return "patrol"; }

    public void forceResolve(BattleOutcome o) {
        this.outcome = o == null ? BattleOutcome.ABORTED : o;
        this.finished = true;
    }

    public BattleOutcome getOutcome() { return outcome; }
    public int getPhase() { return phase; }
}
