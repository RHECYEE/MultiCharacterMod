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
 * SIEGE DIRECTOR
 *
 * Purpose: Large, rare, cinematic base assault — the boss fight of the system.
 *
 * Phases:
 *   Phase 1 — Encirclement:  Enemy surrounds the base perimeter from maximum range.
 *                             Scouts orbit. Main forces close in. Timer visible.
 *   Phase 2 — Breach:        Breach teams + infantry assault the base perimeter.
 *                             Multiple directions. Vehicles arrive at high level.
 *   Phase 3 — Rampage:       Assault force pushes deep. Targets value zones.
 *                             Additional wave if warLevel >= 6.
 *   Phase 4 — Resolution:    Enemies routed (WIN) or base dominated (DEFEAT).
 *
 * The enemy must approach from outside — no spawning inside the base.
 *
 * Duration target: 10–20 minutes (12000–24000 ticks).
 *
 * Victory: Destroy majority of assault force before they achieve dominance.
 * Defeat:  Assault force overwhelms defense.
 */
public class SiegeDirector implements IBattleDirector {

    private final UnitCard card;
    private EntityPlayer activator;
    private BlockPos site;
    private int warLevel = 1;

    private final List<EntityFormationCarrier> carriers = new ArrayList<>();

    private int tickAge = 0;
    private int phase = 0;
    private int lastPhaseChangeTick = 0;
    private boolean finished = false;
    private BattleOutcome outcome = BattleOutcome.ABORTED;

    // Phase timings (ticks)
    private static final int MAX_DURATION_TICKS = 20 * 60 * 20;  // 20 minutes hard cap
    private static final int PHASE2_DELAY       = 2 * 60 * 20;   // 2 min encirclement before breach
    private static final int PHASE3_DELAY       = 4 * 60 * 20;   // 4 min breach before rampage
    private static final int PHASE4_DELAY       = 8 * 60 * 20;   // 8 min rampage before resolution

    // Spawn rings — enemy stages outside base, then closes in
    private static final double ENCIRCLE_RING   = 80.0;  // outer staging ring
    private static final double BREACH_RING     = 55.0;  // breach approach
    private static final double ASSAULT_RING    = 40.0;  // rampage approach

    public SiegeDirector(UnitCard card) {
        this.card = card;
    }

    @Override
    public void start(World world, EntityPlayer activator, BlockPos battleSite) {
        this.activator = activator;
        this.warLevel = Math.max(1, RivalCityManager.getRivalCityLevel());

        int y = Math.max(62, world.getTopSolidOrLiquidBlock(
                new BlockPos(battleSite.getX(), 64, battleSite.getZ())).getY());
        this.site = new BlockPos(battleSite.getX(), y, battleSite.getZ());

        if (world.isRemote) return;

        beginPhase1(world);
    }

    // ════════════════════════════════════════════════════════════
    //  PHASE 1 — ENCIRCLEMENT
    //  Enemy forms a ring around the base. Scouts orbit, main force
    //  holds position on the staging ring before advancing.
    // ════════════════════════════════════════════════════════════

    private void beginPhase1(World world) {
        phase = 1;
        lastPhaseChangeTick = tickAge;

        // Scout orbiters — patrol the outer ring, visible to player
        int scoutCount = (warLevel <= 3) ? 1 : 2;
        double baseAngle = world.rand.nextDouble() * Math.PI * 2;
        for (int i = 0; i < scoutCount; i++) {
            double angle = baseAngle + (i * Math.PI);
            EntityFormationCarrier scout = spawnCarrierAtRing(world, angle, ENCIRCLE_RING, getCard("ScoutTeam"));
            if (scout != null) {
                scout.setOrbitMode(site, ENCIRCLE_RING, 0.018 + (i * 0.006));
            }
        }

        // Staging force — encircles and holds before advancing
        int stagingGroups = (warLevel <= 3) ? 3 : (warLevel <= 6) ? 4 : 5;
        for (int i = 0; i < stagingGroups; i++) {
            double angle = baseAngle + (i * (Math.PI * 2.0 / stagingGroups));
            angle += (world.rand.nextDouble() - 0.5) * 0.3; // slight jitter
            EntityFormationCarrier staging = spawnCarrierAtRing(world, angle, ENCIRCLE_RING, pickStagingCard());
            if (staging != null) {
                // Hold position on the ring — slow orbit while waiting
                staging.setOrbitMode(site, ENCIRCLE_RING, 0.008 + (world.rand.nextDouble() * 0.005));
            }
        }
    }

    // ════════════════════════════════════════════════════════════
    //  PHASE 2 — BREACH
    //  Main infantry and breach teams advance toward the base.
    //  Multiple breach angles. Vehicles at high level.
    // ════════════════════════════════════════════════════════════

    private void beginPhase2(World world) {
        phase = 2;
        lastPhaseChangeTick = tickAge;

        // Release orbiting staging force — make them advance
        for (EntityFormationCarrier c : carriers) {
            if (c != null && !c.isDead) {
                c.setMoveTarget(site, 0.05 + (warLevel * 0.003));
            }
        }

        // Spawn dedicated breach teams
        int breachCount = (warLevel <= 4) ? 2 : (warLevel <= 7) ? 3 : 4;
        double baseAngle = world.rand.nextDouble() * Math.PI * 2;
        for (int i = 0; i < breachCount; i++) {
            double angle = baseAngle + (i * (Math.PI * 2.0 / breachCount));
            EntityFormationCarrier breachTeam = spawnCarrierAtRing(world, angle, BREACH_RING, pickBreachCard());
            if (breachTeam != null) {
                breachTeam.setMoveTarget(site, 0.06 + (warLevel * 0.003));
            }
        }

        // Vehicles at L6+
        if (warLevel >= 6) {
            double vAngle = baseAngle + Math.PI * 0.5;
            EntityFormationCarrier vehicle = spawnCarrierAtRing(world, vAngle, BREACH_RING + 10, getCard("VehiclePlatoon"));
            if (vehicle != null) {
                vehicle.setMoveTarget(site, 0.04 + (warLevel * 0.002));
            }
        }
    }

    // ════════════════════════════════════════════════════════════
    //  PHASE 3 — RAMPAGE
    //  Elite assault force pushes deep. Targets value zones.
    //  Extra reinforcement wave at L6+.
    // ════════════════════════════════════════════════════════════

    private void beginPhase3(World world) {
        phase = 3;
        lastPhaseChangeTick = tickAge;

        // All surviving carriers advance more aggressively
        for (EntityFormationCarrier c : carriers) {
            if (c != null && !c.isDead) {
                c.setMoveTarget(activator.getPosition(), 0.065 + (warLevel * 0.004));
            }
        }

        // Deep assault wave
        int assaultCount = (warLevel <= 5) ? 2 : (warLevel <= 8) ? 3 : 4;
        double baseAngle = world.rand.nextDouble() * Math.PI * 2;
        for (int i = 0; i < assaultCount; i++) {
            double angle = baseAngle + (i * (Math.PI * 2.0 / assaultCount));
            EntityFormationCarrier assaulter = spawnCarrierAtRing(world, angle, ASSAULT_RING, pickAssaultCard());
            if (assaulter != null) {
                assaulter.setMoveTarget(activator.getPosition(), 0.07 + (warLevel * 0.004));
            }
        }

        // Extra reinforcement wave at L6+
        if (warLevel >= 6) {
            int extraCount = warLevel >= 9 ? 3 : 2;
            for (int i = 0; i < extraCount; i++) {
                double angle = baseAngle + Math.PI + (i * (Math.PI / extraCount));
                EntityFormationCarrier extra = spawnCarrierAtRing(world, angle, ENCIRCLE_RING, pickStagingCard());
                if (extra != null) {
                    extra.setMoveTarget(activator.getPosition(), 0.055 + (warLevel * 0.003));
                }
            }
        }
    }

    // ════════════════════════════════════════════════════════════
    //  PHASE 4 — RESOLUTION
    //  Evaluate if the enemy has achieved dominance or been routed.
    // ════════════════════════════════════════════════════════════

    private void beginPhase4(World world) {
        phase = 4;
        lastPhaseChangeTick = tickAge;
        // Resolution is computed in tick() — we just mark the phase here
    }

    // ════════════════════════════════════════════════════════════
    //  TICK
    // ════════════════════════════════════════════════════════════

    @Override
    public void tick(World world) {
        if (world.isRemote || finished) return;
        tickAge++;

        if (tickAge > MAX_DURATION_TICKS) { forceResolve(BattleOutcome.VICTORY); return; }
        if (activator == null || activator.isDead) { forceResolve(BattleOutcome.DEFEAT); return; }

        carriers.removeIf(c -> c == null || c.isDead);

        int ticksSincePhase = tickAge - lastPhaseChangeTick;

        switch (phase) {
            case 1:
                // Advance to Breach after encirclement delay
                if (ticksSincePhase >= PHASE2_DELAY) {
                    beginPhase2(world);
                }
                break;

            case 2:
                // Advance to Rampage after breach delay, or early if all breach units dead
                if (ticksSincePhase >= PHASE3_DELAY || carriers.isEmpty()) {
                    beginPhase3(world);
                } else {
                    // Keep breach units aimed at player
                    chasePlayer(0.055 + (warLevel * 0.003));
                }
                break;

            case 3:
                // Advance to Resolution after rampage delay, or early if all dead
                if (ticksSincePhase >= PHASE4_DELAY || carriers.isEmpty()) {
                    beginPhase4(world);
                } else {
                    // Aggressive player chase during rampage
                    chasePlayer(0.07 + (warLevel * 0.004));
                }
                break;

            case 4:
                // Resolution: enemy wins if many carriers near player, player wins if carriers depleted
                if (carriers.isEmpty()) {
                    forceResolve(BattleOutcome.VICTORY);
                } else {
                    // Check carrier density near player — if enough surround player, it's DEFEAT
                    int nearPlayer = countCarriersNearPlayer(25.0);
                    int totalAlive = carriers.size();
                    if (nearPlayer >= totalAlive / 2 && nearPlayer >= 3) {
                        forceResolve(BattleOutcome.DEFEAT);
                    } else {
                        // Continue chasing
                        chasePlayer(0.06 + (warLevel * 0.003));
                    }
                }
                break;

            default:
                if (!carriers.isEmpty()) {
                    chasePlayer(0.05);
                } else {
                    forceResolve(BattleOutcome.VICTORY);
                }
                break;
        }
    }

    private void chasePlayer(double speed) {
        if (activator == null) return;
        BlockPos playerPos = activator.getPosition();
        for (EntityFormationCarrier c : carriers) {
            if (c != null && !c.isDead) {
                double dist = c.getDistance(playerPos.getX(), playerPos.getY(), playerPos.getZ());
                if (dist > 6.0) {
                    c.setMoveTarget(playerPos, speed);
                }
            }
        }
    }

    private int countCarriersNearPlayer(double range) {
        if (activator == null) return 0;
        BlockPos p = activator.getPosition();
        int count = 0;
        for (EntityFormationCarrier c : carriers) {
            if (c != null && !c.isDead) {
                if (c.getDistance(p.getX(), p.getY(), p.getZ()) <= range) count++;
            }
        }
        return count;
    }

    // ════════════════════════════════════════════════════════════
    //  SPAWN HELPERS
    // ════════════════════════════════════════════════════════════

    private EntityFormationCarrier spawnCarrierAtRing(World world, double angle, double ringRadius, UnitCard unitCard) {
        double spawnX = site.getX() + Math.cos(angle) * ringRadius;
        double spawnZ = site.getZ() + Math.sin(angle) * ringRadius;
        int spawnY = Math.max(62, world.getTopSolidOrLiquidBlock(
                new BlockPos((int) spawnX, 64, (int) spawnZ)).getY());

        if (unitCard == null) unitCard = card != null ? card : UnitCardRegistry.getDefault();

        EntityFormationCarrier carrier = new EntityFormationCarrier(world);
        carrier.setPosition(spawnX + 0.5, spawnY + 1.0, spawnZ + 0.5);
        carrier.configureFromCard(unitCard, warLevel);
        carrier.setBattleContext(activator, site);

        world.spawnEntity(carrier);
        carriers.add(carrier);
        return carrier;
    }

    // ════════════════════════════════════════════════════════════
    //  CARD SELECTION
    // ════════════════════════════════════════════════════════════

    private UnitCard pickStagingCard() {
        if (warLevel <= 2) return getCard("ShieldWall");
        if (warLevel <= 4) return getCard("HeavyInfantry");
        if (warLevel <= 6) return getCard("Phalanx");
        if (warLevel <= 8) return getCard("EliteSquad");
        return getCard("EliteSquad");
    }

    private UnitCard pickBreachCard() {
        if (warLevel <= 3) return getCard("ShieldWall");
        if (warLevel <= 5) return getCard("HeavyInfantry");
        if (warLevel <= 7) return getCard("Phalanx");
        return getCard("EliteSquad");
    }

    private UnitCard pickAssaultCard() {
        if (warLevel <= 4) return getCard("HeavyInfantry");
        if (warLevel <= 6) return getCard("Phalanx");
        if (warLevel <= 8) return getCard("EliteSquad");
        return getCard("EliteSquad");
    }

    private UnitCard getCard(String name) {
        UnitCard c = UnitCardRegistry.get(name);
        return c != null ? c : (card != null ? card : UnitCardRegistry.getDefault());
    }

    // ════════════════════════════════════════════════════════════
    //  IBattleDirector
    // ════════════════════════════════════════════════════════════

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
    public String getDirectorId() { return "siege"; }

    public void forceResolve(BattleOutcome o) {
        this.outcome = o == null ? BattleOutcome.ABORTED : o;
        this.finished = true;
    }

    public BattleOutcome getOutcome() { return outcome; }
    public int getCurrentPhase() { return phase; }
    public int getTotalPhases() { return 4; }
    public int getTickAge() { return tickAge; }

    /** Seconds remaining until next phase (for HUD timers). */
    public int getSecondsUntilNextPhase() {
        int nextDelay;
        switch (phase) {
            case 1: nextDelay = PHASE2_DELAY; break;
            case 2: nextDelay = PHASE3_DELAY; break;
            case 3: nextDelay = PHASE4_DELAY; break;
            default: return 0;
        }
        int remaining = nextDelay - (tickAge - lastPhaseChangeTick);
        return Math.max(0, remaining / 20);
    }
}
