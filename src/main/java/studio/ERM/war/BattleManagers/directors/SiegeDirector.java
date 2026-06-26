package studio.ERM.war.BattleManagers.directors;

import net.minecraft.block.material.Material;
import net.minecraft.block.state.IBlockState;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityList;
import net.minecraft.entity.item.EntityFallingBlock;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.util.ResourceLocation;
import net.minecraft.init.Blocks;
import net.minecraft.init.SoundEvents;
import net.minecraft.util.EnumParticleTypes;
import net.minecraft.util.SoundCategory;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.ChunkPos;
import net.minecraft.world.World;
import net.minecraft.world.WorldServer;
import studio.ERM.war.world.WarWorldData;
import studio.ERM.EpochRunnerMod;
import studio.ERM.war.BattleManagers.api.BattleOutcome;
import studio.ERM.war.BattleManagers.api.IPhasedBattleDirector;
import studio.ERM.war.BattleManagers.api.IPhasedBattleDirector.BattlePhase;
import studio.ERM.war.BattleManagers.cards.UnitCard;
import studio.ERM.war.BattleManagers.cards.UnitCardRegistry;
import studio.ERM.war.BattleManagers.entities.EntityFormationCarrier;
import studio.ERM.war.air.AirStrikeController;
import studio.ERM.war.rival.RivalCityManager;
import studio.ERM.war.vehicle.EntityAIPilot;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

/**
 * SIEGE DIRECTOR — total-war style siege. ONE director controls army FORMATIONS, not individual mobs.
 *
 * The model is a single advancing BATTLE LINE, not scattered packets:
 *   - DEPLOYMENT  A wide infantry front forms up on one bearing (8-20 carriers side by side), with
 *                 tanks staged in lanes between them and a reserve line behind. The whole line creeps
 *                 forward together. Nothing dismounts or chases — formations are director-managed.
 *   - BOMBARDMENT Catapults behind the line lob cobblestone-disguised shots that arc onto the base,
 *                 breaching walls and scattering rubble. Ballista/volley carriers suppress the wall.
 *                 Hostile aircraft (level-pool) work the area. The line advances to bombard range;
 *                 tanks fire from standoff. (Vehicle pools: L6-7 WW2 only, L8+ full random.)
 *   - ENGINEER    Engineers reach the wall and run a breach JOB scaled to tech level: a real
 *                 cobblestone assault ramp (all levels), a mined tunnel (L5+), and a friendly-safe
 *                 explosive breach (L8+); at L10 all three at once. No pink missing-texture cubes,
 *                 and breaches are terrain-only (manual block removal + effect) so they can NEVER
 *                 kill the attackers' own formations.
 *   - SURGE       Surge airstrike + the line presses to the wall and commits its contact slice.
 *   - ASSAULT     The line pushes into the breach; resolution computed in tick().
 *
 * Formations advance on the BASE under director orders and only commit their troops on arrival
 * (see EntityFormationCarrier#setDirectorManaged) — so the siege reads as cohesive lines closing in,
 * not a mob that pops out of thin air and sprints at the player.
 */
public class SiegeDirector implements IPhasedBattleDirector {

    private final UnitCard card;
    private EntityPlayer activator;
    private BlockPos site;
    private int warLevel = 1;

    // The advancing main front: each carrier keeps its lateral offset so the LINE stays a line as it
    // closes (each unit marches straight in toward its own slot on the wall, not toward a single point).
    private final List<LineUnit> frontLine = new ArrayList<>();
    // Every live carrier this director owns (cleanup / resolution).
    private final List<EntityFormationCarrier> carriers = new ArrayList<>();
    // Volley/ballista carriers that hold at range and suppress the wall tops.
    private final List<EntityFormationCarrier> bombardLine = new ArrayList<>();
    // Engineer breach assignments.
    private final List<EngineerTask> engineerTasks = new ArrayList<>();
    private boolean engineersComplete = false;
    // Real Flan armour (EntityAIPilot-crewed). The mis-named "VehiclePlatoon" card is just infantry;
    // THESE are the actual driving/firing tanks.
    private final List<EntityAIPilot> vehicles = new ArrayList<>();

    // Catapults: positions behind the line + in-flight shots.
    private final List<BlockPos> catapultSites = new ArrayList<>();
    private final List<CatapultShot> catapultShots = new ArrayList<>();
    private int catapultCooldown = 0;
    // Real AW2 siege-machine entities (catapult/ballista) spawned as the visible battery, tracked for
    // cleanup. Spawned reflectively so the vehicle module isn't a hard dependency.
    private final List<Entity> siegeMachines = new ArrayList<>();

    // Direction from the base out to the assault front (the side the army comes from).
    private double frontBearing = 0.0;

    // The ONE breach corridor for this siege, chosen at Deployment. ALL bombardment converges here
    // until there is a real ground-level gap, and the engineers then exploit exactly this spot. A
    // siege has a single objective: open this corridor (not swiss-cheese the whole wall).
    private BlockPos breachCorridor = null;

    // Temporary siege-camp ground/decor. Every block the camp build changes is recorded here with
    // its ORIGINAL state, exactly like the repair system stores claimed chunks, so the whole camp
    // (flattened staging pad, tents, catapult frames) is restored verbatim when the siege ends --
    // no permanent terraforming, and no army standing on open water.
    private final Map<BlockPos, IBlockState> campOriginals = new HashMap<>();
    private int padY = 64;

    private int tickAge = 0;
    private int phase = 0;
    private int lastPhaseChangeTick = 0;
    private boolean finished = false;
    private BattleOutcome outcome = BattleOutcome.ABORTED;

    // Phase identifiers
    private static final int P_DEPLOY   = 1;
    private static final int P_BOMBARD  = 2;
    private static final int P_ENGINEER = 3;
    private static final int P_SURGE    = 4;
    private static final int P_ASSAULT  = 5;
    private static final int TOTAL_PHASES = 5;

    // Per-phase durations (ticks @ 20/s).
    private static final int PHASE_DEPLOY_TICKS   = 75  * 20;
    private static final int PHASE_BOMBARD_TICKS  = 90  * 20;
    private static final int PHASE_ENGINEER_TICKS = 110 * 20;
    private static final int PHASE_SURGE_TICKS    = 75  * 20;
    private static final int PHASE_ASSAULT_TICKS  = 180 * 20;

    private static final int MIN_PHASE_DWELL = 30 * 20;
    private static final int MAX_DURATION_TICKS = 24 * 60 * 20;

    // Objective rings (blocks from site).
    private static final double ENCIRCLE_RING = 80.0; // line forms up here
    private static final double BOMBARD_RING  = 58.0; // line advances here for the barrage
    private static final double WALL_RING     = 40.0; // "the wall" — breach line
    private static final double ASSAULT_RING  = 22.0; // contact / inside the breach

    // Line tuning
    private static final double LINE_SPACING = 5.0;   // blocks between carriers on the front
    private static final double TANK_LANE_SPACING = 16.0;

    // Engineer tuning
    private static final int RAMP_STEPS = 6;
    private static final int RAMP_TICKS_PER_STEP = 8;
    private static final int RAMP_CREST = 4;
    private static final int BREACH_WINDUP = 16;
    private static final double ARRIVE_DIST = 5.0;

    public SiegeDirector(UnitCard card) {
        this.card = card;
    }

    // ════════════════════════════════════════════════════════════
    //  START
    // ════════════════════════════════════════════════════════════

    @Override
    public void start(World world, EntityPlayer activator, BlockPos battleSite) {
        this.activator = activator;
        this.warLevel = Math.max(1, RivalCityManager.getRivalCityLevel());

        int y = surfaceY(world, battleSite.getX(), battleSite.getZ());
        this.site = new BlockPos(battleSite.getX(), y, battleSite.getZ());

        if (world.isRemote) return;

        // Pick the bearing that stages the army on the MOST land (don't form up on open ocean).
        this.frontBearing = pickLandwardBearing(world);

        // Lay down the staging camp FIRST (flatten a pad over water/rough ground + pitch tents) so the
        // line and catapults spawn on solid, level ground instead of out on open water.
        buildCamp(world);

        beginDeployment(world);
    }

    // ════════════════════════════════════════════════════════════
    //  PHASE 1 — DEPLOYMENT (form the battle line)
    // ════════════════════════════════════════════════════════════

    private void beginDeployment(World world) {
        phase = P_DEPLOY;
        lastPhaseChangeTick = tickAge;
        EpochRunnerMod.logger.info("[Siege] -> DEPLOYMENT: forming battle line (warLevel=" + warLevel + ")");

        // One wide infantry front. Width scales with war level.
        int lineCount = Math.max(8, Math.min(20, 6 + warLevel + warLevel / 2));
        int tankEvery = (warLevel >= 6) ? 4 : 0; // a tank in a lane every Nth slot at L6+

        double mid = (lineCount - 1) / 2.0;
        for (int i = 0; i < lineCount; i++) {
            double lateral = (i - mid) * LINE_SPACING;
            spawnLineCarrier(world, lateral, ENCIRCLE_RING, pickStagingCard());

            if (tankEvery > 0 && i % tankEvery == (tankEvery / 2)) {
                String name = pickTank(world);
                spawnTankAtFront(world, lateral, ENCIRCLE_RING + 5.0, name);
            }
        }

        // Reserve line a little behind the front (these make the army feel deep).
        if (warLevel >= 4) {
            int reserves = Math.max(3, lineCount / 3);
            double rmid = (reserves - 1) / 2.0;
            for (int i = 0; i < reserves; i++) {
                double lateral = (i - rmid) * (LINE_SPACING * 1.8);
                spawnLineCarrier(world, lateral, ENCIRCLE_RING + 16.0, pickStagingCard());
            }
        }

        // Mechanized recon screen: real Flan jeeps + halftracks scout ahead of the line (L6+).
        if (warLevel >= 6) {
            spawnTransportPlatoon(world, ENCIRCLE_RING + 10.0);
        }

        // Commit to ONE breach corridor now; everything bombards it and the engineers exploit it.
        chooseBreachCorridor(world);
    }

    /** Pick the single wall section this whole siege will breach (a real wall near the defender). */
    private void chooseBreachCorridor(World world) {
        BlockPos defender = activator != null ? activator.getPosition() : site;
        BlockPos wall = findWallTargetNear(world, defender);
        if (wall != null) {
            breachCorridor = new BlockPos(wall.getX(), surfaceY(world, wall.getX(), wall.getZ()), wall.getZ());
        } else {
            // Open ground / no wall: aim the breach at the near edge of the base on the assault bearing.
            breachCorridor = frontPoint(world, 10.0, 0.0);
        }
        EpochRunnerMod.logger.info("[Siege] breach corridor = " + breachCorridor);
    }

    // ════════════════════════════════════════════════════════════
    //  PHASE 2 — BOMBARDMENT (catapults + suppression + air + standoff armour)
    // ════════════════════════════════════════════════════════════

    private void beginBombardment(World world) {
        phase = P_BOMBARD;
        lastPhaseChangeTick = tickAge;
        EpochRunnerMod.logger.info("[Siege] -> BOMBARDMENT: catapults + CAS (warLevel=" + warLevel + ")");

        // Position the catapults behind the centre of the line. Counts by level: L3-5=1, L6-7=2, L8+=3.
        catapultSites.clear();
        catapultCooldown = 20;
        int guns = (warLevel >= 8) ? 3 : (warLevel >= 6) ? 2 : 1;
        double gmid = (guns - 1) / 2.0;
        for (int i = 0; i < guns; i++) {
            double lateral = (i - gmid) * 18.0;
            BlockPos gun = frontPoint(world, ENCIRCLE_RING + 6.0, lateral);
            catapultSites.add(gun);
            // Prefer a REAL AW2 siege machine (alternating catapult / ballista); fall back to a
            // timber frame only if the AW2 vehicle module isn't available.
            String machine = (i % 2 == 0) ? "catapult" : "ballista";
            if (!spawnAW2SiegeMachine(world, gun, machine)) {
                buildCatapultStructure(world, gun);
            }
            spawnCatapultCrew(world, gun, lateral); // heavy-cavalry vanguard + loader engineers
        }

        // Stand up a ballista/volley line at mid range to suppress the wall tops.
        int volleyGroups = (warLevel <= 3) ? 1 : (warLevel <= 6) ? 2 : 3;
        for (int i = 0; i < volleyGroups; i++) {
            double lateral = (i - (volleyGroups - 1) / 2.0) * 14.0;
            BlockPos at = frontPoint(world, BOMBARD_RING + 6.0, lateral);
            EntityFormationCarrier volley = spawnCarrierAt(world, at, pickBombardCard(), false);
            if (volley != null) {
                volley.setSuppressionTarget(activator != null ? activator.getPosition() : site);
                bombardLine.add(volley);
            }
        }

        // Hostile aircraft work the area during the barrage (BF109/Lancaster/heli pool by level).
        if (warLevel >= 3) AirStrikeController.requestHostileCAS(world, site, warLevel);
    }

    // ════════════════════════════════════════════════════════════
    //  PHASE 3 — ENGINEER PUSH (breach jobs by tech level)
    // ════════════════════════════════════════════════════════════

    private void beginEngineerPush(World world) {
        phase = P_ENGINEER;
        lastPhaseChangeTick = tickAge;
        engineersComplete = false;
        EpochRunnerMod.logger.info("[Siege] -> ENGINEER PUSH: breach jobs (warLevel=" + warLevel + ")");

        if (breachCorridor == null) chooseBreachCorridor(world);

        // Spread the engineers across adjacent sections of the SAME corridor (along the wall face).
        double ang = Math.atan2(breachCorridor.getZ() - site.getZ(), breachCorridor.getX() - site.getX());
        double px = -Math.sin(ang), pz = Math.cos(ang);

        int engineerCount = (warLevel <= 3) ? 1 : (warLevel <= 6) ? 2 : 3;
        double emid = (engineerCount - 1) / 2.0;
        for (int i = 0; i < engineerCount; i++) {
            double off = (i - emid) * 3.0;
            int bx = (int) Math.round(breachCorridor.getX() + px * off);
            int bz = (int) Math.round(breachCorridor.getZ() + pz * off);
            BlockPos breachPoint = new BlockPos(bx, surfaceY(world, bx, bz), bz);
            BlockPos start = outsidePoint(world, breachPoint, 8.0);
            EntityFormationCarrier eng = spawnCarrierAt(world, start, getCard("SiegeUnit"), true);
            if (eng != null) {
                engineerTasks.add(new EngineerTask(eng, off, breachPoint));
                eng.setMoveTarget(breachPoint, 0.07 + warLevel * 0.004);
            }
        }

        // Shield-wall escort: infantry stand BETWEEN the workers and the defender, soaking pressure so
        // the engineers can do their job. They hold this line (no chase) -- the protection, not the punch.
        int shields = (warLevel <= 3) ? 1 : 2;
        double smid = (shields - 1) / 2.0;
        for (int i = 0; i < shields; i++) {
            double off = (i - smid) * 8.0;
            int sx = (int) Math.round(breachCorridor.getX() + px * off);
            int sz = (int) Math.round(breachCorridor.getZ() + pz * off);
            BlockPos guardAt = outsidePoint(world, new BlockPos(sx, surfaceY(world, sx, sz), sz), 3.0);
            EntityFormationCarrier shield = spawnCarrierAt(world, guardAt, getCard("ShieldWall"), true);
            if (shield != null) shield.setBattleContext(activator, guardAt);
        }
    }

    /** A point {@code dist} blocks OUTSIDE the wall from {@code wall} (away from the base core). */
    private BlockPos outsidePoint(World world, BlockPos wall, double dist) {
        double ang = Math.atan2(wall.getZ() - site.getZ(), wall.getX() - site.getX());
        int x = (int) Math.round(wall.getX() + Math.cos(ang) * dist);
        int z = (int) Math.round(wall.getZ() + Math.sin(ang) * dist);
        return new BlockPos(x, surfaceY(world, x, z), z);
    }

    /**
     * Engineers are CONSTRUCTION CREWS, not combat mobs. At the corridor they VISIBLY work: rapidly
     * place a ladder column to scale the wall, fast-mine a tunnel straight through (~130% iron-pick),
     * and at high tech plant a real TNT charge at ground level then RETREAT before it blows. The
     * shield-wall escort (spawned in beginEngineerPush) soaks the pressure so they can keep working.
     */
    private void tickEngineers(World world) {
        boolean allComplete = !engineerTasks.isEmpty();

        for (EngineerTask t : engineerTasks) {
            if (t.done) continue;           // this crew genuinely finished its breach job
            allComplete = false;            // anything not-done (INCLUDING a dead crew) -> phase not complete
            EntityFormationCarrier eng = t.carrier;
            if (eng == null || eng.isDead) continue; // dead before finishing: let the phase run its timer

            // Just planted a charge: pull the workers back, let it cook, then resume.
            if (tickAge < t.retreatUntil) {
                eng.setMoveTarget(outsidePoint(world, t.breachPoint, 12.0), 0.11 + warLevel * 0.004);
                continue;
            }

            double d = eng.getDistance(t.breachPoint.getX(), t.breachPoint.getY(), t.breachPoint.getZ());
            if (d > ARRIVE_DIST) {
                eng.setMoveTarget(t.breachPoint, 0.07 + warLevel * 0.004); // re-issue (nav is one-shot)
                continue;
            }

            t.workTicks++;

            // LADDER team — a climbable ladder column up the wall, every level.
            if (t.workTicks % 4 == 0 && t.stepsBuilt < RAMP_STEPS + RAMP_CREST) {
                placeLadderRung(world, t, t.stepsBuilt);
                t.stepsBuilt++;
            }

            // MINING team — fast tunnel straight through toward the core (mid tech and up).
            if (warLevel >= 5 && t.workTicks % 3 == 0) {
                mineTunnel(world, t, t.workTicks / 3);
            }

            // SAPPER team — plant a real ground-level TNT charge, then retreat from the blast (high tech).
            if (warLevel >= 8 && t.workTicks % 60 == 30) {
                plantSapperCharge(world, t.breachPoint);
                t.retreatUntil = tickAge + 36;
            }

            // Finished after a solid work window (the bombardment also opens the corridor). Floor a
            // clean corridor inward so the assault pours straight through.
            if (t.workTicks >= 140) {
                t.done = true;
                levelBreachPath(world, t);
            }
        }

        engineersComplete = allComplete;
    }

    /** One ladder rung up the outer face of the wall at the corridor (reverts when the siege ends). */
    private void placeLadderRung(World world, EngineerTask t, int step) {
        try {
            net.minecraft.util.EnumFacing outward = net.minecraft.util.EnumFacing.getFacingFromVector(
                    t.breachPoint.getX() - site.getX(), 0, t.breachPoint.getZ() - site.getZ());
            BlockPos col = t.breachPoint.offset(outward); // one block out from the wall face
            int gY = surfaceY(world, col.getX(), col.getZ());
            BlockPos p = new BlockPos(col.getX(), gY + step, col.getZ());
            BlockPos support = p.offset(outward.getOpposite()); // the wall the ladder clings to
            if (world.isAirBlock(p) && world.getBlockState(support).getMaterial().isSolid()) {
                setCampBlock(world, p, Blocks.LADDER.getDefaultState()
                        .withProperty(net.minecraft.block.BlockLadder.FACING, outward));
            }
        } catch (Throwable ignored) {}
    }

    /** Plant a real primed-TNT sapper charge at ground level + guarantee the gap with openGroundBreach. */
    private void plantSapperCharge(World world, BlockPos at) {
        try {
            net.minecraft.entity.item.EntityTNTPrimed tnt = new net.minecraft.entity.item.EntityTNTPrimed(
                    world, at.getX() + 0.5, at.getY() + 0.5, at.getZ() + 0.5, null);
            tnt.setFuse(30);
            world.spawnEntity(tnt);
            world.playSound(null, at, SoundEvents.ENTITY_CREEPER_PRIMED, SoundCategory.HOSTILE, 1.2F, 0.8F);
        } catch (Throwable ignored) {}
        openGroundBreach(world, at);
    }

    // ════════════════════════════════════════════════════════════
    //  PHASE 4 — SURGE
    // ════════════════════════════════════════════════════════════

    private void beginSurge(World world) {
        phase = P_SURGE;
        lastPhaseChangeTick = tickAge;
        EpochRunnerMod.logger.info("[Siege] -> SURGE: airstrike + line commits (warLevel=" + warLevel + ")");

        int surgeWaves = (warLevel >= 8) ? 3 : (warLevel >= 5) ? 2 : 1;
        AirStrikeController.launchHostileSurge(world, site, warLevel, surgeWaves);

        // CAVALRY CHARGE: mounted shock troops sweep in ahead of the infantry. Each carrier releases
        // its mounted soldiers (CAVALRY card -> horse) right at the wall, who then charge the defender.
        int cavUnits = (warLevel >= 7) ? 3 : 2;
        for (int i = 0; i < cavUnits; i++) {
            double lateral = (i - (cavUnits - 1) / 2.0) * 12.0;
            BlockPos at = frontPoint(world, WALL_RING + 3.0, lateral);
            EntityFormationCarrier cav = spawnCarrierAt(world, at, getCard("LightCavalry"), true);
            if (cav != null) cav.setBattleContext(activator, at); // release here; the horsemen then charge
        }

        // A fresh armoured push commits with the line.
        if (warLevel >= 6) spawnArmourColumn(world, WALL_RING + 6.0);
    }

    // ════════════════════════════════════════════════════════════
    //  PHASE 5 — INTERIOR ASSAULT
    // ════════════════════════════════════════════════════════════

    private void beginInteriorAssault(World world) {
        phase = P_ASSAULT;
        lastPhaseChangeTick = tickAge;
        EpochRunnerMod.logger.info("[Siege] -> INTERIOR ASSAULT (warLevel=" + warLevel + ")");

        // Crack the core with a couple of friendly-safe interior breaches.
        int interior = (warLevel <= 4) ? 1 : 2;
        for (int i = 0; i < interior; i++) {
            double lateral = (i - (interior - 1) / 2.0) * 10.0;
            BlockPos at = frontPoint(world, ASSAULT_RING * 0.6, lateral);
            breachWall(world, at, 3);
            explosionEffect(world, at);
        }
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
        bombardLine.removeIf(c -> c == null || c.isDead);
        frontLine.removeIf(u -> u.carrier == null || u.carrier.isDead);
        vehicles.removeIf(v -> v == null || v.isDead);

        int tsp = tickAge - lastPhaseChangeTick;

        switch (phase) {
            case P_DEPLOY:
                // Hold formation up front; creep forward menacingly but do not engage yet.
                advanceLine(world, ENCIRCLE_RING - 6.0, 0.03 + warLevel * 0.002);
                if (tsp >= PHASE_DEPLOY_TICKS) beginBombardment(world);
                break;

            case P_BOMBARD:
                tickCatapults(world);
                refreshBombardment();
                advanceLine(world, BOMBARD_RING, 0.045 + warLevel * 0.003);
                if (tsp >= PHASE_BOMBARD_TICKS || waveDefeated(tsp)) beginEngineerPush(world);
                break;

            case P_ENGINEER:
                // NOTE: NO catapults here -- the barrage opened the breach during BOMBARDMENT. If the
                // catapults kept cratering the corridor now they would blow the footing out from under
                // the engineers working in it (which is what made the engineer phase die in 1 tick).
                refreshBombardment();
                tickEngineers(world);
                advanceLine(world, WALL_RING + 4.0, 0.05 + warLevel * 0.003);
                // Only advance early once the engineers GENUINELY finished (engineersComplete now means
                // every task is done, not "all dead") AND a minimum dwell has passed, so they actually
                // get seen laddering / mining / sapping.
                if (tsp >= PHASE_ENGINEER_TICKS
                        || (engineersComplete && tsp >= MIN_PHASE_DWELL)
                        || waveDefeated(tsp)) beginSurge(world);
                break;

            case P_SURGE:
                advanceLine(world, WALL_RING, 0.06 + warLevel * 0.004);
                if (tsp >= PHASE_SURGE_TICKS || waveDefeated(tsp)) beginInteriorAssault(world);
                break;

            case P_ASSAULT:
                advanceLine(world, ASSAULT_RING - 4.0, 0.06 + warLevel * 0.004);
                if (waveDefeated(tsp)) {
                    forceResolve(BattleOutcome.VICTORY); // the assault was fought off
                } else if (tsp >= PHASE_ASSAULT_TICKS) {
                    forceResolve(BattleOutcome.VICTORY); // window elapsed without breaking the defender
                }
                // NOTE: the attacker-wins -> OCCUPATION path is the next system; the siege currently
                // resolves when the army is wiped or the assault window elapses.
                break;

            default:
                forceResolve(BattleOutcome.VICTORY);
                break;
        }
    }

    // ════════════════════════════════════════════════════════════
    //  LINE MOVEMENT
    // ════════════════════════════════════════════════════════════

    /** Re-issue each front carrier's march order toward its OWN slot on {@code ring} (keeps the line). */
    private void advanceLine(World world, double ring, double speed) {
        for (LineUnit u : frontLine) {
            EntityFormationCarrier c = u.carrier;
            if (c == null || c.isDead) continue;
            BlockPos target = frontPoint(world, ring, u.lateral);
            if (c.getDistance(target.getX(), target.getY(), target.getZ()) > 3.0) {
                c.setMoveTarget(target, speed);
            }
        }
    }

    /** Keep the volley/ballista line firing at the defender's current position. */
    private void refreshBombardment() {
        if (activator == null || bombardLine.isEmpty()) return;
        if ((tickAge % 10) != 0) return;
        BlockPos p = activator.getPosition();
        for (EntityFormationCarrier c : bombardLine) {
            if (c != null && !c.isDead) c.setSuppressionTarget(p);
        }
    }

    private boolean waveDefeated(int ticksSincePhase) {
        return ticksSincePhase >= MIN_PHASE_DWELL && carriers.isEmpty();
    }

    // ════════════════════════════════════════════════════════════
    //  CATAPULTS  (cobblestone-disguised arcing shots → rubble + wall damage, friendly-safe)
    // ════════════════════════════════════════════════════════════

    private void tickCatapults(World world) {
        if (!catapultSites.isEmpty()) {
            if (catapultCooldown > 0) catapultCooldown--;
            else { launchCatapult(world); catapultCooldown = Math.max(25, 60 - warLevel * 3); }
        }
        Iterator<CatapultShot> it = catapultShots.iterator();
        while (it.hasNext()) {
            CatapultShot s = it.next();
            if (tickAge >= s.impactTick) {
                boolean corridorHit = breachCorridor != null
                        && Math.abs(s.target.getX() - breachCorridor.getX()) <= 4
                        && Math.abs(s.target.getZ() - breachCorridor.getZ()) <= 4;
                if (corridorHit) {
                    openGroundBreach(world, s.target); // carve the wall down to ground at the corridor
                } else {
                    breachWall(world, s.target, 2); // stray / player rounds: just a crater
                    scatterRubble(world, s.target);
                }
                explosionEffect(world, s.target);
                if (s.block != null && !s.block.isDead) s.block.setDead();
                it.remove();
            }
        }
    }

    private void launchCatapult(World world) {
        if (catapultSites.isEmpty()) return;
        BlockPos from = catapultSites.get(world.rand.nextInt(catapultSites.size()));

        // Aim at a GROUND-LEVEL breach point on the front of the base so the barrage blows ONE real
        // horizontal opening for the assault -- not swiss-cheese on the tower top. Most rounds cluster
        // tightly on the breach; a few are area fire near the base so it still feels dangerous.
        // Bombardment targets the STRUCTURE, not the defender. Only ~5% of rounds are genuinely aimed
        // at the player; the rest hammer the actual walls (findWallTargetNear), falling back to area
        // fire near the base when there is no wall to find. This is the siege softening the fortress,
        // not sniping the champion.
        BlockPos defender = activator != null ? activator.getPosition() : site;
        if (breachCorridor == null) chooseBreachCorridor(world);
        BlockPos target;
        int roll = world.rand.nextInt(100);
        if (roll < 5) {
            target = defender; // the occasional terrifying near-miss on the player
        } else {
            // Converge on the ONE breach corridor with a tight cluster, so the gap actually opens
            // instead of pockmarking the whole wall.
            int sx = breachCorridor.getX() + world.rand.nextInt(5) - 2;
            int sz = breachCorridor.getZ() + world.rand.nextInt(5) - 2;
            target = new BlockPos(sx, surfaceY(world, sx, sz), sz);
        }

        // Launch from ABOVE the timber frame, nudged toward the target, so the projectile clears the
        // catapult and is actually visible -- it was spawning INSIDE the frame and placing instantly.
        double dirX = (target.getX() + 0.5) - (from.getX() + 0.5);
        double dirZ = (target.getZ() + 0.5) - (from.getZ() + 0.5);
        double dlen = Math.sqrt(dirX * dirX + dirZ * dirZ);
        if (dlen < 0.001) dlen = 1.0;
        double launchX = from.getX() + 0.5 + (dirX / dlen) * 2.5;
        double launchZ = from.getZ() + 0.5 + (dirZ / dlen) * 2.5;
        double launchY = from.getY() + 5.0;

        final int flight = 45;
        final double gAcc = 0.04; // EntityFallingBlock gravity per tick
        double vx = ((target.getX() + 0.5) - launchX) / flight;
        double vz = ((target.getZ() + 0.5) - launchZ) / flight;
        double vy = ((target.getY() - launchY) + 0.5 * gAcc * flight * flight) / flight;

        try {
            // EntityFallingBlock setDead()s itself on its FIRST tick unless the block at its spawn
            // position IS the falling block (sand/gravel spawn from an existing block). That's why the
            // cobblestone vanished the instant it left the catapult. Place the cobblestone at the
            // launch block for that one tick; the falling block clears it and arcs away properly.
            BlockPos launchBlock = new BlockPos(launchX, launchY, launchZ);
            if (world.isAirBlock(launchBlock)) {
                world.setBlockState(launchBlock, Blocks.COBBLESTONE.getDefaultState(), 2);
            }
            EntityFallingBlock fb = new EntityFallingBlock(world, launchX, launchY, launchZ,
                    Blocks.COBBLESTONE.getDefaultState());
            fb.motionX = vx;
            fb.motionY = vy;
            fb.motionZ = vz;
            world.spawnEntity(fb);
            catapultShots.add(new CatapultShot(fb, target, tickAge + flight));
            world.playSound(null, from, SoundEvents.ENTITY_GENERIC_EXPLODE, SoundCategory.HOSTILE, 1.6F, 1.5F);
        } catch (Throwable ignored) {}
    }

    /** Scatter a few cobblestone "rubble" blocks on the surface around an impact. Routed through
     *  setCampBlock so the rubble is RECORDED and reverts when the siege ends -- otherwise /war repair
     *  left this cobblestone scattered all over the battlefield permanently. */
    private void scatterRubble(World world, BlockPos center) {
        for (int n = 0; n < 6; n++) {
            int rx = center.getX() + world.rand.nextInt(9) - 4;
            int rz = center.getZ() + world.rand.nextInt(9) - 4;
            BlockPos p = new BlockPos(rx, surfaceY(world, rx, rz), rz);
            try {
                if (world.isAirBlock(p)) setCampBlock(world, p, Blocks.COBBLESTONE.getDefaultState());
            } catch (Throwable ignored) {}
        }
    }

    /**
     * Carve the wall at the breach corridor from the surface DOWN to the defender's ground level,
     * across a 5-wide front (along the wall face). Repeated catapult hits here open a REAL walk-through
     * gap instead of pockmarking the wall. Antigrief-aware via damageBlock (claimed walls become
     * repairable scaffold, rival/neutral walls clear to air).
     */
    private void openGroundBreach(World world, BlockPos at) {
        int floorY = site.getY();
        double ang = Math.atan2(at.getZ() - site.getZ(), at.getX() - site.getX());
        double px = -Math.sin(ang), pz = Math.cos(ang); // perpendicular = along the wall (corridor width)
        for (int w = -2; w <= 2; w++) {
            int x = (int) Math.round(at.getX() + px * w);
            int z = (int) Math.round(at.getZ() + pz * w);
            int top = surfaceY(world, x, z);
            for (int y = floorY; y <= top + 2; y++) {
                damageBlock(world, new BlockPos(x, y, z));
            }
        }
    }

    // ════════════════════════════════════════════════════════════
    //  ENGINEERING  (real blocks; breaches are terrain-only and friendly-safe)
    // ════════════════════════════════════════════════════════════

    /** One ascending cobblestone ramp column climbing from OUTSIDE the wall up onto the breach point. */
    private void buildRampStep(World world, EngineerTask t, int step) {
        // Outward bearing = from the base core toward the wall, so the ramp climbs from the field up.
        double ang = Math.atan2(t.breachPoint.getZ() - site.getZ(), t.breachPoint.getX() - site.getX());
        int out = RAMP_STEPS - step; // early steps further out; the last step reaches the wall itself
        int x = t.breachPoint.getX() + (int) Math.round(Math.cos(ang) * out);
        int z = t.breachPoint.getZ() + (int) Math.round(Math.sin(ang) * out);
        int gY = surfaceY(world, x, z);
        int rise = Math.min(step, RAMP_CREST);
        for (int y = gY; y <= gY + rise; y++) {
            BlockPos p = new BlockPos(x, y, z);
            try {
                if (world.isAirBlock(p)) setCampBlock(world, p, Blocks.COBBLESTONE.getDefaultState());
            } catch (Throwable ignored) {}
        }
    }

    /** Carve a 1x3 sapper tunnel from the breach point INWARD through the wall toward the base core. */
    private void mineTunnel(World world, EngineerTask t, int depth) {
        if (depth > 10) return;
        double ang = Math.atan2(site.getZ() - t.breachPoint.getZ(), site.getX() - t.breachPoint.getX());
        int x = t.breachPoint.getX() + (int) Math.round(Math.cos(ang) * depth);
        int z = t.breachPoint.getZ() + (int) Math.round(Math.sin(ang) * depth);
        int baseY = t.breachPoint.getY();
        for (int y = baseY; y <= baseY + 2; y++) {
            damageBlock(world, new BlockPos(x, y, z)); // antigrief-aware
        }
    }

    /**
     * Once a lane is breached, clear AND floor a wide (5x3) corridor from the wall inward to the base
     * at ground level, so the assault pours straight through instead of bottlenecking on rubble or
     * dropping into holes. Clears via damageBlock (antigrief-aware); floors gaps via setCampBlock so
     * the added cobblestone reverts when the siege ends. This is the "make it bigger + level it out".
     */
    private void levelBreachPath(World world, EngineerTask t) {
        BlockPos bp = t.breachPoint;
        int floorY = site.getY();
        double dist = Math.hypot(site.getX() - bp.getX(), site.getZ() - bp.getZ());
        if (dist < 1.0) return;
        double ux = (site.getX() - bp.getX()) / dist, uz = (site.getZ() - bp.getZ()) / dist; // toward core
        double px = -uz, pz = ux; // corridor width axis
        int steps = (int) Math.min(dist, 32);
        for (int s = 0; s <= steps; s++) {
            for (int w = -2; w <= 2; w++) {
                int x = (int) Math.round(bp.getX() + ux * s + px * w);
                int z = (int) Math.round(bp.getZ() + uz * s + pz * w);
                BlockPos floor = new BlockPos(x, floorY - 1, z);
                try {
                    if (world.isAirBlock(floor) || world.getBlockState(floor).getMaterial().isLiquid()) {
                        setCampBlock(world, floor, Blocks.COBBLESTONE.getDefaultState());
                    }
                } catch (Throwable ignored) {}
                for (int y = floorY; y <= floorY + 2; y++) {
                    damageBlock(world, new BlockPos(x, y, z));
                }
            }
        }
    }

    /**
     * Terrain-ONLY breach: manually clears a sphere of breakable blocks and plays an explosion
     * effect. Deliberately does NO entity damage so it can never wipe the attackers' own formations
     * (the old world.newExplosion blew up the engineers and their squad).
     */
    private void breachWall(World world, BlockPos at, int radius) {
        int r2 = radius * radius + 1;
        for (int dx = -radius; dx <= radius; dx++) {
            for (int dy = -1; dy <= radius; dy++) {
                for (int dz = -radius; dz <= radius; dz++) {
                    if (dx * dx + dy * dy + dz * dz > r2) continue;
                    damageBlock(world, at.add(dx, dy, dz)); // antigrief-aware: scaffolds claimed land
                }
            }
        }
    }

    private void explosionEffect(World world, BlockPos at) {
        try {
            world.playSound(null, at, SoundEvents.ENTITY_GENERIC_EXPLODE, SoundCategory.HOSTILE,
                    4.0F, 0.85F + world.rand.nextFloat() * 0.2F);
            if (world instanceof WorldServer) {
                ((WorldServer) world).spawnParticle(EnumParticleTypes.EXPLOSION_LARGE,
                        at.getX() + 0.5, at.getY() + 1.0, at.getZ() + 0.5, 8, 1.6, 1.0, 1.6, 0.02);
            }
        } catch (Throwable ignored) {}
    }

    // ════════════════════════════════════════════════════════════
    //  SPAWN HELPERS
    // ════════════════════════════════════════════════════════════

    /** The point on {@code ring} directly in front of a unit at {@code lateral} offset on the front. */
    private BlockPos frontPoint(World world, double ring, double lateral) {
        double cx = Math.cos(frontBearing), cz = Math.sin(frontBearing);
        double lx = -Math.sin(frontBearing), lz = Math.cos(frontBearing);
        int x = (int) Math.floor(site.getX() + cx * ring + lx * lateral);
        int z = (int) Math.floor(site.getZ() + cz * ring + lz * lateral);
        return new BlockPos(x, surfaceY(world, x, z), z);
    }

    /** Spawn an infantry carrier on the front line and register its lateral slot + wall objective. */
    private EntityFormationCarrier spawnLineCarrier(World world, double lateral, double ring, UnitCard unitCard) {
        BlockPos at = frontPoint(world, ring, lateral);
        EntityFormationCarrier c = spawnCarrierAt(world, at, unitCard, true);
        if (c == null) return null;
        // Its release objective is its OWN slot on the wall, so the line deploys along the wall.
        c.setBattleContext(activator, frontPoint(world, WALL_RING, lateral));
        frontLine.add(new LineUnit(c, lateral));
        return c;
    }

    private EntityFormationCarrier spawnCarrierAt(World world, BlockPos at, UnitCard unitCard, boolean directorManaged) {
        if (unitCard == null) unitCard = (card != null) ? card : UnitCardRegistry.getDefault();
        EntityFormationCarrier carrier = new EntityFormationCarrier(world);
        carrier.setPosition(at.getX() + 0.5, at.getY() + 1.0, at.getZ() + 0.5);
        carrier.configureFromCard(unitCard, warLevel);
        carrier.setBattleContext(activator, site);
        carrier.setDirectorManaged(directorManaged);
        world.spawnEntity(carrier);
        carriers.add(carrier);
        return carrier;
    }

    /**
     * Each catapult gets its own vanguard: a screen of heavily-armoured cavalry a few blocks in
     * front of the gun, plus a knot of engineers "manning"/loading it. They hold at the gun (not part
     * of the advancing front line) so the battery reads as a defended, crewed emplacement.
     */
    private void spawnCatapultCrew(World world, BlockPos catapult, double lateral) {
        // Heavy cavalry vanguard, screening just in front of the battery.
        EntityFormationCarrier cav = spawnCarrierAt(world,
                frontPoint(world, ENCIRCLE_RING + 1.0, lateral), getCard("LightCavalry"), true);
        if (cav != null) cav.setBattleContext(activator, catapult);

        // Loader/crew engineers right at the gun.
        EntityFormationCarrier crew = spawnCarrierAt(world,
                catapult.add(2, 0, 0), getCard("SiegeUnit"), true);
        if (crew != null) crew.setBattleContext(activator, catapult);
    }

    /** Spawn one real Flan tank (EntityAIPilot crew) at an absolute front position. */
    private void spawnTankAtFront(World world, double lateral, double ring, String flansShortName) {
        BlockPos at = frontPoint(world, ring, lateral);
        spawnTankAt(world, at, flansShortName);
    }

    private void spawnTankAt(World world, BlockPos at, String flansShortName) {
        try {
            EntityAIPilot pilot = new EntityAIPilot(world);
            pilot.setPosition(at.getX() + 0.5, at.getY() + 1.0, at.getZ() + 0.5);
            pilot.setVehicleType(flansShortName);
            pilot.setMcmTeam("empire");
            if (activator != null) {
                try { pilot.setAttackTarget(activator); } catch (Throwable ignored) {}
            }
            world.spawnEntity(pilot);
            vehicles.add(pilot);
        } catch (Throwable t) {
            EpochRunnerMod.logger.warn("[Siege] Failed to spawn tank '" + flansShortName + "': " + t.getMessage());
        }
    }

    /** Spawn an armour column spread across tank lanes within the front. */
    private void spawnArmourColumn(World world, double ring) {
        int count = (warLevel >= 8) ? 3 : 2;
        double mid = (count - 1) / 2.0;
        for (int i = 0; i < count; i++) {
            double lateral = (i - mid) * TANK_LANE_SPACING;
            spawnTankAtFront(world, lateral, ring, pickTank(world));
        }
    }

    /**
     * Tank pool by level: L6-7 WW2 only, L8+ full random (WW2 + modern). These are REAL Flan ShortNames
     * verified against the installed WW2 + Modern Warfare content packs (the old list used Pershing /
     * Leopard2 / Challenger / "Abrams" which DO NOT EXIST -> the pilot summon silently failed).
     */
    private String pickTank(World world) {
        String[] pool;
        if (warLevel >= 8) {
            pool = new String[] { "Tiger", "TigerII", "Panzer", "Sherman", "T34", "Churchill", "Cromwell",
                                  "IS2", "KV1", "StuG", "abrams", "T90", "Leo2A6", "ChallyII" };
        } else if (warLevel >= 6) {
            pool = new String[] { "Tiger", "Tiger131", "Panzer", "PanzerIIL", "Sherman", "T34",
                                  "Churchill", "Cromwell", "StuG", "M10", "Hellcat", "KV1" };
        } else {
            pool = new String[] { "Sherman", "Panzer", "Cromwell" };
        }
        return pool[world.rand.nextInt(pool.length)];
    }

    /** Transport pool by level (jeeps + halftracks). Real Flan ShortNames from the installed packs. */
    private String pickTransport(World world) {
        String[] pool = (warLevel >= 8)
                ? new String[] { "Jeep", "SASJeep", "Kubel", "M3Halftrack", "SdkFz251", "Humvee", "Greyhound" }
                : new String[] { "Jeep", "SASJeep", "Kubel", "M3Halftrack", "SdkFz251", "BMWR75" };
        return pool[world.rand.nextInt(pool.length)];
    }

    /** A recon/transport screen of jeeps + halftracks (real Flan vehicles) ahead of the line. */
    private void spawnTransportPlatoon(World world, double ring) {
        int count = (warLevel >= 8) ? 4 : 3;
        double mid = (count - 1) / 2.0;
        for (int i = 0; i < count; i++) {
            double lateral = (i - mid) * (TANK_LANE_SPACING * 0.75);
            spawnTankAtFront(world, lateral, ring, pickTransport(world));
        }
    }

    // ════════════════════════════════════════════════════════════
    //  CARD SELECTION
    // ════════════════════════════════════════════════════════════

    private UnitCard pickStagingCard() {
        if (warLevel <= 2) return getCard("ShieldWall");
        if (warLevel <= 4) return getCard("HeavyInfantry");
        if (warLevel <= 6) return getCard("Phalanx");
        return getCard("EliteSquad");
    }

    private UnitCard pickBombardCard() {
        if (warLevel <= 3) return getCard("SkirmishLine");
        return getCard("MixedCompany");
    }

    private UnitCard getCard(String name) {
        UnitCard c = UnitCardRegistry.get(name);
        return c != null ? c : (card != null ? card : UnitCardRegistry.getDefault());
    }

    // ════════════════════════════════════════════════════════════
    //  HELPERS
    // ════════════════════════════════════════════════════════════

    private int surfaceY(World world, double x, double z) {
        return Math.max(62, world.getTopSolidOrLiquidBlock(
                new BlockPos((int) Math.floor(x), 64, (int) Math.floor(z))).getY());
    }

    /**
     * Choose the assault bearing that stages the camp on the MOST solid land, so the army does not
     * form up out on open ocean. Scans 16 bearings and scores each by how much of its camp zone is dry.
     */
    private double pickLandwardBearing(World world) {
        double best = world.rand.nextDouble() * Math.PI * 2;
        int bestLand = -1;
        for (int i = 0; i < 16; i++) {
            double ang = i * (Math.PI * 2.0 / 16.0);
            double cx = Math.cos(ang), cz = Math.sin(ang);
            double lx = -Math.sin(ang), lz = Math.cos(ang);
            int land = 0;
            for (int r = 64; r <= 96; r += 8) {
                for (int lat = -25; lat <= 25; lat += 25) {
                    int x = (int) Math.floor(site.getX() + cx * r + lx * lat);
                    int z = (int) Math.floor(site.getZ() + cz * r + lz * lat);
                    try {
                        BlockPos top = world.getTopSolidOrLiquidBlock(new BlockPos(x, 64, z));
                        if (world.getBlockState(top.down()).getMaterial() != Material.WATER) land++;
                    } catch (Throwable ignored) {}
                }
            }
            if (land > bestLand) { bestLand = land; best = ang; }
        }
        return best;
    }

    /**
     * ANTIGRIEF. ALL siege block destruction (breaches, catapult impacts, interior charges) is routed
     * through here. In CLAIMED player land it does NOT permanently destroy the block: it records the
     * original for repair ({@link WarWorldData#addRepairOrder}) and leaves a PASSABLE scaffold marker
     * the /war repair command and the builder citizen can later restore. Outside claimed land (rival /
     * neutral) it just removes the block. This is the whole point of claiming -- battles damage your
     * land but everything is recoverable, never permanently griefed.
     */
    private void damageBlock(World world, BlockPos pos) {
        try {
            if (world.isAirBlock(pos)) return;
            // Protector-stick blocks are indestructible -- never damage or scaffold them.
            if (studio.ERM.handlers.ProtectionHandler.isProtected(world, pos)) return;
            IBlockState st = world.getBlockState(pos);
            if (st.getBlockHardness(world, pos) < 0) return; // bedrock / unbreakable

            if (EpochRunnerMod.scaffold != null && isClaimedLand(world, pos)) {
                WarWorldData data = WarWorldData.get(world);
                if (data != null) {
                    data.addRepairOrder(pos.toImmutable(), st);
                    world.setBlockState(pos, EpochRunnerMod.scaffold.getDefaultState(), 2);
                    return;
                }
            }
            world.setBlockToAir(pos);
        } catch (Throwable ignored) {}
    }

    private boolean isClaimedLand(World world, BlockPos pos) {
        try {
            WarWorldData data = WarWorldData.get(world);
            if (data == null) return false;
            String owner = data.getOwner(new ChunkPos(pos));
            return owner != null && !"NEUTRAL".equals(owner) && !"RIVAL".equals(owner);
        } catch (Throwable t) {
            return false;
        }
    }

    // ════════════════════════════════════════════════════════════
    //  SIEGE CAMP  (temporary staging pad + tents + catapult frames; FULLY restored on stop)
    // ════════════════════════════════════════════════════════════

    private void buildCamp(World world) {
        try {
            BlockPos centre = frontPoint(world, ENCIRCLE_RING + 6.0, 0.0);
            padY = Math.max(63, centre.getY());

            double cx = Math.cos(frontBearing), cz = Math.sin(frontBearing);
            double lx = -Math.sin(frontBearing), lz = Math.cos(frontBearing);

            int nearR = (int) (ENCIRCLE_RING - 8);
            int farR = (int) (ENCIRCLE_RING + 20);
            int halfWidth = 46;

            // Flatten the pad: fill water / low ground up to padY; leave existing higher ground alone.
            for (int rad = nearR; rad <= farR; rad++) {
                for (int lat = -halfWidth; lat <= halfWidth; lat++) {
                    int x = (int) Math.floor(site.getX() + cx * rad + lx * lat);
                    int z = (int) Math.floor(site.getZ() + cz * rad + lz * lat);
                    padColumn(world, x, z);
                }
            }

            // Pitch a scatter of tents across the rear for the army-encampment look.
            int tents = 8 + warLevel;
            for (int i = 0; i < tents; i++) {
                int rad = (int) (ENCIRCLE_RING + 6 + world.rand.nextInt(18));
                int lat = world.rand.nextInt(2 * halfWidth - 8) - (halfWidth - 4);
                int x = (int) Math.floor(site.getX() + cx * rad + lx * lat);
                int z = (int) Math.floor(site.getZ() + cz * rad + lz * lat);
                buildTent(world, x, z, padY);
            }
        } catch (Throwable t) {
            EpochRunnerMod.logger.warn("[Siege] camp build failed: " + t.getMessage());
        }
    }

    /**
     * Build one pad column if it is water / low ground; never cut existing higher terrain. Fills SOLID
     * from the original surface up to the pad floor so the camp is grounded INTO the terrain instead of
     * a slab floating over the sea. Fill depth is bounded so deep ocean doesn't explode the edit count.
     */
    private void padColumn(World world, int x, int z) {
        int orig = surfaceY(world, x, z); // top of water/terrain (getTopSolidOrLiquidBlock)
        if (orig > padY) return;          // already at/above pad level -> leave it
        // Fill from the pad floor DOWN to the actual SOLID seabed, not just one slab over the water,
        // so the camp is grounded instead of floating. Bounded to 16 deep for very deep ocean.
        int ground = solidGroundY(world, x, z);
        int bottom = Math.max(ground + 1, padY - 16);
        for (int y = padY - 1; y >= bottom; y--) {
            setCampBlock(world, new BlockPos(x, y, z),
                    (y == padY - 1) ? Blocks.GRASS_PATH.getDefaultState() : Blocks.DIRT.getDefaultState());
        }
        BlockPos above = new BlockPos(x, padY, z);
        if (!world.isAirBlock(above)) setCampBlock(world, above, Blocks.AIR.getDefaultState());
    }

    /** Top SOLID block in a column (skipping water/air) so the pad fills down to real ground. */
    private int solidGroundY(World world, int x, int z) {
        BlockPos.MutableBlockPos mp = new BlockPos.MutableBlockPos();
        for (int y = Math.min(padY, 96); y > 2; y--) {
            mp.setPos(x, y, z);
            try { if (world.getBlockState(mp).getMaterial().isSolid()) return y; } catch (Throwable ignored) {}
        }
        return padY - 16;
    }

    /** A small canvas tent: wool walls + a log centre pole + a peaked wool roof. */
    private void buildTent(World world, int cx, int cz, int y) {
        IBlockState canvas = Blocks.WOOL.getDefaultState();
        IBlockState pole = Blocks.LOG.getDefaultState();
        for (int dx = -1; dx <= 1; dx++) {
            for (int dz = -1; dz <= 1; dz++) {
                if (dx == 0 && dz == 0) continue;
                setCampBlock(world, new BlockPos(cx + dx, y, cz + dz), canvas);
                setCampBlock(world, new BlockPos(cx + dx, y + 1, cz + dz), canvas);
            }
        }
        setCampBlock(world, new BlockPos(cx, y + 1, cz), pole);
        setCampBlock(world, new BlockPos(cx, y + 2, cz), canvas);
    }

    /** A rough timber catapult frame so each battery is actually VISIBLE on the field. */
    private void buildCatapultStructure(World world, BlockPos at) {
        IBlockState log = Blocks.LOG.getDefaultState();
        IBlockState plank = Blocks.PLANKS.getDefaultState();
        int x = at.getX(), y = at.getY(), z = at.getZ();
        setCampBlock(world, new BlockPos(x + 1, y, z), log);
        setCampBlock(world, new BlockPos(x - 1, y, z), log);
        setCampBlock(world, new BlockPos(x, y, z + 1), log);
        setCampBlock(world, new BlockPos(x, y, z - 1), log);
        setCampBlock(world, new BlockPos(x + 1, y + 1, z), log);
        setCampBlock(world, new BlockPos(x - 1, y + 1, z), log);
        setCampBlock(world, new BlockPos(x, y + 1, z), plank);
        setCampBlock(world, new BlockPos(x, y + 2, z), plank);
        setCampBlock(world, new BlockPos(x, y + 3, z - 1), plank);
    }

    /**
     * Spawn a REAL Ancient Warfare 2 siege machine (catapult / ballista / trebuchet) as the visible
     * battery. Done entirely by reflection on the AW2 vehicle entity + VehicleType registry so the
     * vehicle module is a soft dependency: if it isn't present (or anything fails) this returns false
     * and the caller drops a timber frame instead. The machine is a visual emplacement -- our own
     * catapult system does the actual firing.
     *
     * @param configKeyword substring to match against AW2 VehicleType config names, e.g. "catapult".
     */
    private boolean spawnAW2SiegeMachine(World world, BlockPos at, String configKeyword) {
        try {
            Entity v = EntityList.createEntityByIDFromName(
                    new ResourceLocation("ancientwarfarevehicle", "vehicle"), world);
            if (v == null) return false;

            Object vehicleType = findVehicleTypeByKeyword(configKeyword);
            if (vehicleType == null) return false;

            // VehicleBase.setVehicleType(IVehicleType, int materialLevel)
            Class<?> iVehicleType = Class.forName("net.shadowmage.ancientwarfare.vehicle.entity.IVehicleType");
            int materialLevel = Math.max(0, Math.min(4, warLevel / 3));
            java.lang.reflect.Method setType = v.getClass().getMethod("setVehicleType", iVehicleType, int.class);
            setType.invoke(v, vehicleType, materialLevel);

            v.setLocationAndAngles(at.getX() + 0.5, at.getY() + 1.0, at.getZ() + 0.5,
                    (float) Math.toDegrees(frontBearing) + 90F, 0F);
            if (v instanceof net.minecraft.entity.EntityLiving) {
                ((net.minecraft.entity.EntityLiving) v).enablePersistence();
            }
            world.spawnEntity(v);
            siegeMachines.add(v);
            return true;
        } catch (Throwable t) {
            EpochRunnerMod.logger.warn("[Siege] AW2 siege machine '" + configKeyword + "' unavailable: " + t);
            return false;
        }
    }

    /** Reflectively find the first AW2 VehicleType whose config name contains {@code keyword}. */
    private Object findVehicleTypeByKeyword(String keyword) {
        try {
            Class<?> vtClass = Class.forName("net.shadowmage.ancientwarfare.vehicle.entity.types.VehicleType");
            java.lang.reflect.Field f = vtClass.getDeclaredField("vehicleTypes");
            f.setAccessible(true);
            Object reg = f.get(null);

            Iterable<?> all;
            if (reg instanceof Object[]) all = java.util.Arrays.asList((Object[]) reg);
            else if (reg instanceof Iterable) all = (Iterable<?>) reg;
            else if (reg instanceof java.util.Map) all = ((java.util.Map<?, ?>) reg).values();
            else return null;

            String want = keyword.toLowerCase();
            Object firstStandFixed = null;
            for (Object vt : all) {
                if (vt == null) continue;
                try {
                    Object cn = vt.getClass().getMethod("getConfigName").invoke(vt);
                    if (cn == null) continue;
                    String name = cn.toString().toLowerCase();
                    if (!name.contains(want)) continue;
                    // Prefer a stationary "stand" variant if present (it sits still like an emplacement).
                    if (name.contains("stand")) return vt;
                    if (firstStandFixed == null) firstStandFixed = vt;
                } catch (Throwable ignored) {}
            }
            return firstStandFixed;
        } catch (Throwable t) {
            return null;
        }
    }

    private void setCampBlock(World world, BlockPos pos, IBlockState state) {
        try {
            BlockPos p = pos.toImmutable();
            if (!campOriginals.containsKey(p)) campOriginals.put(p, world.getBlockState(p));
            world.setBlockState(p, state, 2);
        } catch (Throwable ignored) {}
    }

    private void restoreCamp(World world) {
        for (Map.Entry<BlockPos, IBlockState> e : campOriginals.entrySet()) {
            try { world.setBlockState(e.getKey(), e.getValue(), 2); } catch (Throwable ignored) {}
        }
        campOriginals.clear();
    }

    /**
     * Smart bombardment aiming: scan a ring around the defender for raised SOLID blocks (their wall /
     * fortifications) and return one to hammer, so a chunk of the barrage blows holes in the walls the
     * engineer push then exploits -- instead of every shot landing on the player. Null => no wall
     * found, caller falls back to area fire near the player.
     */
    private BlockPos findWallTargetNear(World world, BlockPos around) {
        for (int attempt = 0; attempt < 24; attempt++) {
            int dx = world.rand.nextInt(19) - 9;
            int dz = world.rand.nextInt(19) - 9;
            if (Math.abs(dx) < 3 && Math.abs(dz) < 3) continue; // not point-blank on the player
            int x = around.getX() + dx, z = around.getZ() + dz;
            for (int dy = 1; dy <= 4; dy++) {
                BlockPos p = new BlockPos(x, around.getY() + dy, z);
                try {
                    if (!world.isAirBlock(p) && world.getBlockState(p).getMaterial().isSolid()) {
                        return p;
                    }
                } catch (Throwable ignored) {}
            }
        }
        return null;
    }

    // ════════════════════════════════════════════════════════════
    //  IBattleDirector / IPhasedBattleDirector
    // ════════════════════════════════════════════════════════════

    @Override
    public boolean isFinished() { return finished; }

    @Override
    public void stop(World world) {
        if (world.isRemote) return;
        for (EntityFormationCarrier c : new ArrayList<>(carriers)) {
            if (c != null && !c.isDead) c.setDead();
        }
        for (EntityAIPilot v : new ArrayList<>(vehicles)) {
            if (v != null && !v.isDead) {
                try {
                    if (v.getRidingEntity() != null && !v.getRidingEntity().isDead) v.getRidingEntity().setDead();
                } catch (Throwable ignored) {}
                v.setDead();
            }
        }
        // Tidy any catapult shots still in flight.
        for (CatapultShot s : catapultShots) {
            if (s.block != null && !s.block.isDead) s.block.setDead();
        }
        for (Entity m : new ArrayList<>(siegeMachines)) {
            if (m != null && !m.isDead) m.setDead();
        }
        siegeMachines.clear();
        catapultShots.clear();
        catapultSites.clear();
        // Put the world back exactly as it was -- pad, tents, catapult frames all reverted.
        restoreCamp(world);
        vehicles.clear();
        carriers.clear();
        frontLine.clear();
        bombardLine.clear();
        engineerTasks.clear();
    }

    @Override
    public String getDirectorId() { return "siege"; }

    @Override
    public BattlePhase getBattlePhase() {
        return (phase == P_DEPLOY) ? BattlePhase.DEPLOYING : BattlePhase.COMBAT;
    }

    @Override
    public float getDeployProgress() {
        if (phase != P_DEPLOY) return 0f;
        float p = (float) (tickAge - lastPhaseChangeTick) / (float) PHASE_DEPLOY_TICKS;
        return Math.max(0f, Math.min(1f, p));
    }

    @Override
    public String getPhaseLabel() {
        switch (phase) {
            case P_DEPLOY:   return "Siege — Deployment";
            case P_BOMBARD:  return "Siege — Bombardment";
            case P_ENGINEER: return "Siege — Engineer Push";
            case P_SURGE:    return "Siege — Surge";
            case P_ASSAULT:  return "Siege — Interior Assault";
            default:          return "Siege";
        }
    }

    public void forceResolve(BattleOutcome o) {
        this.outcome = o == null ? BattleOutcome.ABORTED : o;
        this.finished = true;
    }

    @Override
    public BattleOutcome getOutcome() { return outcome; }
    public int getCurrentPhase() { return phase; }
    public int getTotalPhases() { return TOTAL_PHASES; }
    public int getTickAge() { return tickAge; }

    public int getSecondsUntilNextPhase() {
        int nextDelay;
        switch (phase) {
            case P_DEPLOY:   nextDelay = PHASE_DEPLOY_TICKS;   break;
            case P_BOMBARD:  nextDelay = PHASE_BOMBARD_TICKS;  break;
            case P_ENGINEER: nextDelay = PHASE_ENGINEER_TICKS; break;
            case P_SURGE:    nextDelay = PHASE_SURGE_TICKS;    break;
            default: return 0;
        }
        int remaining = nextDelay - (tickAge - lastPhaseChangeTick);
        return Math.max(0, remaining / 20);
    }

    // ════════════════════════════════════════════════════════════
    //  INNER TYPES
    // ════════════════════════════════════════════════════════════

    /** A carrier on the front line plus its fixed lateral offset, so the line stays a line. */
    private static final class LineUnit {
        final EntityFormationCarrier carrier;
        final double lateral;
        LineUnit(EntityFormationCarrier carrier, double lateral) {
            this.carrier = carrier;
            this.lateral = lateral;
        }
    }

    /** One engineer's breach assignment: approach the lane, raise a ramp, mine/blow the wall. */
    private static final class EngineerTask {
        final EntityFormationCarrier carrier;
        final double lateral;
        final BlockPos breachPoint;
        int workTicks = 0;
        int stepsBuilt = 0;
        boolean breached = false;
        boolean done = false;
        int retreatUntil = 0; // tickAge before which the crew pulls back from a planted charge

        EngineerTask(EntityFormationCarrier carrier, double lateral, BlockPos breachPoint) {
            this.carrier = carrier;
            this.lateral = lateral;
            this.breachPoint = breachPoint;
        }
    }

    /** An in-flight catapult round: the visual cobblestone block + where/when it detonates. */
    private static final class CatapultShot {
        final EntityFallingBlock block;
        final BlockPos target;
        final int impactTick;
        CatapultShot(EntityFallingBlock block, BlockPos target, int impactTick) {
            this.block = block;
            this.target = target;
            this.impactTick = impactTick;
        }
    }
}
