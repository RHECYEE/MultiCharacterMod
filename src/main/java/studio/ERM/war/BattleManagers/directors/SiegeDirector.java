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
import studio.ERM.war.strategy.StrategicChunk;
import studio.ERM.war.strategy.WarHeatMap;
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
    // ENGINEER CORPS (the most important part of the siege). A shared, reservable work queue: the
    // planner classifies EVERY obstacle on the assault axis (water/gap/cliff/wall) into a saved
    // MilitaryRoute, then engineer crews pull tasks off the queue and VISIBLY build/mine/ladder/breach
    // them -- ready for all obstacle types at once, with the wall BREACH as the top-priority objective.
    private final List<RouteNode> route = new ArrayList<>();   // the saved MilitaryRoute (staging->core)
    private final List<EngTask> engQueue = new ArrayList<>();  // shared obstacle/work queue
    private final List<EngCrew> engCrews = new ArrayList<>();  // engineer squads that pull from the queue
    private BlockPos routeStart = null;                        // staging foot on the army side of the route
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

    // The defender's REAL base, mapped by the strategic heat map at siege start. The siege re-aims at
    // the cluster CORE (storage/machine/living concentration) and breaches a low-defense PERIMETER
    // chunk facing the army -- "conduct a siege against the base", not "attack where the player stood".
    private java.util.List<StrategicChunk> baseCluster = null;
    private StrategicChunk baseCore = null;

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

        // STRATEGIC TARGETING. The siege MUST lock onto the actual castle, not wherever the player
        // happened to stand when they triggered it -- otherwise the whole army forms up in an empty
        // field and digs a pointless hole. Search order, strongest signal first:
        //   1. PROTECTED BLOCKS -- the blocks the defender marked with the protection stick. Stored
        //      globally, so this finds the castle at ANY range (the heat scan only saw a 96-block radius
        //      of LOADED chunks, which is why a castle triggered from a distance was missed).
        //   2. STRUCTURE scan -- man-made building blocks, for a base that wasn't protect-sticked.
        //   3. HEAT core -- tile-entity (chest/machine) concentration.
        //   4. the trigger point.
        // Shared resolver -- IDENTICAL to what `/war heat` shows, so the debug board reflects the siege.
        try {
            studio.ERM.war.strategy.SiegeTargeting.Result tr =
                    studio.ERM.war.strategy.SiegeTargeting.resolve(world, site, 256, 120, 8);
            this.site = tr.target;
            this.baseCluster = tr.heatCluster; // may be null (protected/structure path); breach falls back fine
            this.baseCore = tr.heatCore;
            EpochRunnerMod.logger.info("[Siege] target = " + site + " via " + tr.reason
                    + " (protectedBlocks=" + tr.protectedBlocks.size()
                    + ", hottest=" + (tr.hottest != null ? (int) tr.hottest.totalHeat() : 0) + ")");
        } catch (Throwable t) {
            EpochRunnerMod.logger.warn("[Siege] targeting failed: " + t);
        }

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

    /**
     * Pick the single wall section this whole siege will breach. Chunk STRATEGY -> block TACTICS:
     * if the heat map mapped a base, pick the low-defense PERIMETER chunk facing the army, then
     * locally scan that chunk for the actual wall block. Otherwise fall back to a local scan.
     */
    private void chooseBreachCorridor(World world) {
        // Decide which side the army comes from, then RAY-WALK from a staging foot outside that side
        // straight in toward the base core and drop the breach on the FIRST real wall (the first sharp
        // upward jump in the surface). The old random ±9 scan kept landing the breach in an open field
        // SHORT of the castle -- this guarantees it lands ON the wall, on the assault axis.
        BlockPos edgeOutside;
        StrategicChunk edge = pickPerimeterChunkTowardAttack();
        if (edge != null) {
            int ex = (edge.chunkX << 4) + 8, ez = (edge.chunkZ << 4) + 8;
            BlockPos edgeCenter = new BlockPos(ex, surfaceY(world, ex, ez), ez);
            edgeOutside = outsidePoint(world, edgeCenter, 30.0); // step OUT toward the army from the edge
        } else {
            edgeOutside = frontPoint(world, ENCIRCLE_RING, 0.0);
        }
        routeStart = edgeOutside;
        breachCorridor = detectWallOnPath(world, edgeOutside, site);
        EpochRunnerMod.logger.info("[Siege] breach corridor = " + breachCorridor + " (wall on assault axis "
                + edgeOutside.getX() + "," + edgeOutside.getZ() + " -> core " + site.getX() + "," + site.getZ()
                + (edge != null ? "; perimeter chunk def=" + (int) edge.defenseHeat : "; landward bearing") + ")");
    }

    /**
     * Ray-walk from an OUTSIDE staging point straight toward the base core and return the foot of the
     * REAL wall: the first place the surface jumps up sharply (a built wall / steep rampart). If no
     * wall is found (open base) return a point just outside the core. This is what makes the breach
     * land ON the castle wall instead of in a field short of it.
     */
    private BlockPos detectWallOnPath(World world, BlockPos from, BlockPos to) {
        double dx = to.getX() - from.getX(), dz = to.getZ() - from.getZ();
        double dist = Math.sqrt(dx * dx + dz * dz);
        if (dist < 1.0) return to;
        double ux = dx / dist, uz = dz / dist;
        int prevSurf = surfaceY(world, from.getX(), from.getZ());
        int n = (int) Math.ceil(dist);
        for (int s = 1; s <= n; s++) {
            int x = (int) Math.round(from.getX() + ux * s);
            int z = (int) Math.round(from.getZ() + uz * s);
            int surf = surfaceY(world, x, z);
            // A wall = either a sharp surface jump OR a man-made block at body height (a castle wall the
            // same height as its approach reads as man-made, not as a jump). Breach at the OUTSIDE foot.
            boolean built = false;
            try {
                built = studio.ERM.war.strategy.SiegeTargeting.isManMade(world.getBlockState(new BlockPos(x, prevSurf + 1, z)))
                     || studio.ERM.war.strategy.SiegeTargeting.isManMade(world.getBlockState(new BlockPos(x, prevSurf + 2, z)));
            } catch (Throwable ignored) {}
            if (surf - prevSurf >= 3 || built) {
                return new BlockPos(x, prevSurf, z);
            }
            prevSurf = surf;
        }
        int bx = (int) Math.round(to.getX() - ux * 6);  // no clear wall: breach just outside the core
        int bz = (int) Math.round(to.getZ() - uz * 6);
        return new BlockPos(bx, surfaceY(world, bx, bz), bz);
    }

    /** The base-cluster chunk on the side the army comes from, preferring the LOWEST-defense edge. */
    private StrategicChunk pickPerimeterChunkTowardAttack() {
        if (baseCluster == null || baseCluster.isEmpty()) return null;
        double cx = Math.cos(frontBearing), cz = Math.sin(frontBearing); // toward the army staging side
        StrategicChunk best = null;
        double bestScore = -Double.MAX_VALUE;
        for (StrategicChunk c : baseCluster) {
            double dx = ((c.chunkX << 4) + 8) - site.getX();
            double dz = ((c.chunkZ << 4) + 8) - site.getZ();
            double proj = dx * cx + dz * cz;           // front edge faces the attackers
            double score = proj - c.defenseHeat * 0.1; // bias toward the soft (low-defense) spot
            if (score > bestScore) { bestScore = score; best = c; }
        }
        return best;
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
        EpochRunnerMod.logger.info("[Siege] -> ENGINEER PUSH: planning military route (warLevel=" + warLevel + ")");

        if (breachCorridor == null) chooseBreachCorridor(world);

        // 1) PLAN: classify EVERY obstacle on the assault axis into the saved MilitaryRoute + work queue.
        planMilitaryRoute(world);

        // 2) CREWS: spawn the engineer squads that pull tasks off the SHARED queue and build them. They
        //    start OUTSIDE the wall and walk in to whatever task they reserve (bridge/ramp/clear/breach).
        engCrews.clear();
        double ang = Math.atan2(breachCorridor.getZ() - site.getZ(), breachCorridor.getX() - site.getX());
        double px = -Math.sin(ang), pz = Math.cos(ang);
        int engineerCount = (warLevel <= 3) ? 2 : (warLevel <= 6) ? 3 : 4;
        double emid = (engineerCount - 1) / 2.0;
        for (int i = 0; i < engineerCount; i++) {
            double off = (i - emid) * 3.0;
            int bx = (int) Math.round(breachCorridor.getX() + px * off);
            int bz = (int) Math.round(breachCorridor.getZ() + pz * off);
            BlockPos start = outsidePoint(world, new BlockPos(bx, surfaceY(world, bx, bz), bz), 12.0);
            EntityFormationCarrier eng = spawnCarrierAt(world, start, getCard("SiegeUnit"), true);
            if (eng != null) engCrews.add(new EngCrew(eng));
        }

        // 3) ESCORT: shield-wall infantry stand BETWEEN the workers and the defender and HOLD (no chase),
        //    soaking pressure so the engineers can work the queue. The protection, not the punch.
        int shields = (warLevel <= 3) ? 2 : 3;
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
     * THE ENGINEER PLANNER. Ray-walk the whole assault axis (staging -> wall -> core), classify EVERY
     * column into an obstacle, and build a CONNECTED, terrain-following MilitaryRoute: each node carries
     * a walkable grade Y clamped to +-1 per step, so the road can never float over a dip or bury into a
     * rise (that was the "floating bridge" bug). The non-passable stretches are then grouped into a
     * small set of reservable EngTasks -- bridges, ramps, head-clears, the WALL BREACH (top priority),
     * and a LADDER escalade -- so the crews are ready for all obstacle types at once.
     */
    private void planMilitaryRoute(World world) {
        route.clear();
        engQueue.clear();
        if (breachCorridor == null || routeStart == null) return;

        // Plan staging -> wall -> a little PAST the wall (the interior corridor is floored by
        // levelBreachPath after the breach). We deliberately do NOT route all the way to the core, so
        // the engineers don't try to breach every interior building -- the wall is the objective.
        double toCore = Math.atan2(site.getZ() - breachCorridor.getZ(), site.getX() - breachCorridor.getX());
        int endX = breachCorridor.getX() + (int) Math.round(Math.cos(toCore) * 8);
        int endZ = breachCorridor.getZ() + (int) Math.round(Math.sin(toCore) * 8);
        double dx = endX - routeStart.getX(), dz = endZ - routeStart.getZ();
        double dist = Math.sqrt(dx * dx + dz * dz);
        if (dist < 1.0) return;
        double ux = dx / dist, uz = dz / dist;
        int n = (int) Math.ceil(dist);

        int breachIdx = -1; double breachBest = Double.MAX_VALUE;
        int gradeY = surfaceY(world, routeStart.getX(), routeStart.getZ());
        for (int s = 0; s <= n; s++) {
            int x = (int) Math.round(routeStart.getX() + ux * s);
            int z = (int) Math.round(routeStart.getZ() + uz * s);
            int surf = surfaceY(world, x, z);
            Obstacle ob = classifyColumn(world, x, z, surf, gradeY);
            // Terrain-following walkable grade -- clamp the step to +-1 so the route CONNECTS. The wall
            // itself is passed THROUGH at the approach grade (we breach it, we don't climb the grade up it).
            if (ob != Obstacle.WALL) {
                if (surf > gradeY + 1) gradeY++;
                else if (surf < gradeY - 1) gradeY--;
                else gradeY = surf;
            }
            route.add(new RouteNode(x, z, gradeY, ob));
            double dToWall = Math.hypot(x - breachCorridor.getX(), z - breachCorridor.getZ());
            if (dToWall < breachBest) { breachBest = dToWall; breachIdx = route.size() - 1; }
        }

        // Group consecutive non-passable nodes of the same family into one task.
        int i = 0;
        while (i < route.size()) {
            Obstacle ob = route.get(i).obstacle;
            if (ob == Obstacle.PASSABLE) { i++; continue; }
            int j = i;
            while (j + 1 < route.size() && taskFamily(route.get(j + 1).obstacle) == taskFamily(ob)) j++;
            EngWork w = workFor(ob);
            if (w == EngWork.BREACH) {
                engQueue.add(new EngTask(EngWork.BREACH, ob, i, j, 100)); // the wall: top priority
                engQueue.add(new EngTask(EngWork.LADDER, ob, i, j, 40));  // escalade alongside the breach
            } else {
                engQueue.add(new EngTask(w, ob, i, j, 50 - i));          // nearest-to-staging first
            }
            i = j + 1;
        }

        // FAILSAFE: the wall IS the objective -- guarantee a breach task at it even if the surface scan
        // never flagged a height jump (a flat-topped wall level with its own approach).
        boolean haveBreach = false;
        for (EngTask t : engQueue) if (t.work == EngWork.BREACH) { haveBreach = true; break; }
        if (!haveBreach && breachIdx >= 0) {
            engQueue.add(new EngTask(EngWork.BREACH, Obstacle.WALL, breachIdx, breachIdx, 100));
            engQueue.add(new EngTask(EngWork.LADDER, Obstacle.WALL, breachIdx, breachIdx, 40));
        }
        engQueue.sort((a, b) -> b.priority - a.priority);
        EpochRunnerMod.logger.info("[Siege] MilitaryRoute: " + route.size() + " nodes; "
                + engQueue.size() + " engineering tasks " + summarizeQueue());
    }

    /** Classify one route column from its surface height vs the current walkable grade. */
    private Obstacle classifyColumn(World world, int x, int z, int surf, int gradeY) {
        try {
            Material top = world.getBlockState(new BlockPos(x, surf, z)).getMaterial();
            if (top == Material.LAVA) return Obstacle.LAVA;
            if (top == Material.WATER) return Obstacle.WATER;
            int rise = surf - gradeY;
            if (rise >= 3) return Obstacle.WALL;          // a real vertical barrier
            boolean head = world.getBlockState(new BlockPos(x, gradeY + 1, z)).getMaterial().isSolid()
                        || world.getBlockState(new BlockPos(x, gradeY + 2, z)).getMaterial().isSolid();
            if (head && rise >= 1) return Obstacle.WALL;  // solid wall the same height as the approach
            if (rise == 2) return Obstacle.CLIFF;         // a steep step up -> ramp/cut
            if (surf <= gradeY - 3) return Obstacle.GAP;  // a ravine / drop -> bridge
            return Obstacle.PASSABLE;
        } catch (Throwable t) { return Obstacle.PASSABLE; }
    }

    private static int taskFamily(Obstacle o) {
        switch (o) {
            case WATER: case LAVA: case GAP: return 1; // bridge family
            case CLIFF:                       return 2; // ramp family
            case WALL:                        return 3; // breach family
            default:                          return 0;
        }
    }
    private static EngWork workFor(Obstacle o) {
        switch (o) {
            case WATER: case LAVA: case GAP: return EngWork.BRIDGE;
            case CLIFF:                       return EngWork.RAMP;
            case WALL:                        return EngWork.BREACH;
            default:                          return EngWork.CLEAR;
        }
    }
    /** Work units a task needs (one per node for road work; a fixed carve budget for breach/ladder). */
    private int taskNeeded(EngTask t) {
        switch (t.work) {
            case BREACH: return 16;
            case LADDER: return RAMP_STEPS + RAMP_CREST;
            default:     return Math.max(1, t.toIdx - t.fromIdx + 1);
        }
    }
    private String summarizeQueue() {
        int br = 0, ld = 0, bg = 0, rp = 0, cl = 0;
        for (EngTask t : engQueue) switch (t.work) {
            case BREACH: br++; break; case LADDER: ld++; break; case BRIDGE: bg++; break;
            case RAMP: rp++; break; default: cl++; break;
        }
        return "(breach=" + br + " ladder=" + ld + " bridge=" + bg + " ramp=" + rp + " clear=" + cl + ")";
    }

    /**
     * Build one connected, terrain-following route node across a 3-wide lane: floor the grade over any
     * gap/liquid (bridge/fill) AND cut a head-high passage through any solid (ramp-cut/clear). Floor
     * blocks go through setCampBlock (revert on siege end); cuts go through damageBlock (antigrief-aware).
     */
    private void buildRouteNode(World world, RouteNode node) {
        double ang = Math.atan2(breachCorridor.getZ() - site.getZ(), breachCorridor.getX() - site.getX());
        double px = -Math.sin(ang), pz = Math.cos(ang);
        for (int w = -1; w <= 1; w++) {
            int x = (int) Math.round(node.x + px * w);
            int z = (int) Math.round(node.z + pz * w);
            try {
                BlockPos floor = new BlockPos(x, node.gradeY - 1, z);
                if (world.isAirBlock(floor) || world.getBlockState(floor).getMaterial().isLiquid()) {
                    setCampBlock(world, floor, Blocks.COBBLESTONE.getDefaultState());
                }
                for (int y = node.gradeY; y <= node.gradeY + 2; y++) {
                    BlockPos hp = new BlockPos(x, y, z);
                    Material m = world.getBlockState(hp).getMaterial();
                    if (m.isLiquid()) setCampBlock(world, hp, Blocks.AIR.getDefaultState());
                    else if (m.isSolid()) damageBlock(world, hp); // cut the cliff/overhang for headroom
                }
            } catch (Throwable ignored) {}
        }
    }

    /** Carve a 3-wide, 4-tall walk-through gap THROUGH the wall, one block deeper toward the core each step. */
    private void breachStep(World world, RouteNode wn, int depth) {
        double inAng = Math.atan2(site.getZ() - wn.z, site.getX() - wn.x);
        double ix = Math.cos(inAng), iz = Math.sin(inAng);  // inward toward the core
        double px = -iz, pz = ix;                            // along the wall face (width)
        int cx = (int) Math.round(wn.x + ix * depth);
        int cz = (int) Math.round(wn.z + iz * depth);
        for (int w = -1; w <= 1; w++) {
            int x = (int) Math.round(cx + px * w);
            int z = (int) Math.round(cz + pz * w);
            for (int y = wn.gradeY; y <= wn.gradeY + 3; y++) damageBlock(world, new BlockPos(x, y, z));
        }
        explosionEffect(world, new BlockPos(cx, wn.gradeY + 1, cz));
    }

    /** One ladder rung up the outer face of the wall at a route node (reverts when the siege ends). */
    private void placeLadderRungAt(World world, RouteNode wn, int step) {
        try {
            net.minecraft.util.EnumFacing outward = net.minecraft.util.EnumFacing.getFacingFromVector(
                    wn.x - site.getX(), 0, wn.z - site.getZ());
            BlockPos col = new BlockPos(wn.x, wn.gradeY, wn.z).offset(outward); // one block out from the face
            BlockPos p = new BlockPos(col.getX(), wn.gradeY + step, col.getZ());
            BlockPos support = p.offset(outward.getOpposite()); // the wall the ladder clings to
            if (world.isAirBlock(p) && world.getBlockState(support).getMaterial().isSolid()) {
                setCampBlock(world, p, Blocks.LADDER.getDefaultState()
                        .withProperty(net.minecraft.block.BlockLadder.FACING, outward));
            }
        } catch (Throwable ignored) {}
    }

    /**
     * Engineers are CONSTRUCTION CREWS, not combat mobs. Each crew RESERVES the highest-priority
     * unclaimed task on the shared queue (the wall breach first, then the nearest bridge/ramp/clear),
     * walks to it, and VISIBLY builds it block-by-block at ~130% iron-pick speed -- bridging moats,
     * cutting ramps, laddering & breaching the wall, all in parallel. When a crew dies its task returns
     * to the queue for another crew. The phase completes when every task on the route is done.
     */
    private void tickEngineers(World world) {
        if (engQueue.isEmpty()) { engineersComplete = true; return; }

        for (EngCrew crew : engCrews) {
            EntityFormationCarrier eng = crew.carrier;
            if (eng == null || eng.isDead) { releaseTask(crew); continue; } // give the task back

            // Sapper charge cooking: pull back from the blast, then resume.
            if (tickAge < crew.retreatUntil) {
                eng.setMoveTarget(outsidePoint(world, breachCorridor, 12.0), 0.11 + warLevel * 0.004);
                continue;
            }

            EngTask t = crew.current;
            if (t == null || t.done) t = reserveNextTask(crew);
            if (t == null) { // queue drained: form up at the breach
                eng.setMoveTarget(breachCorridor, 0.06 + warLevel * 0.004);
                continue;
            }

            BlockPos wp = workPos(world, t);
            double d = eng.getDistance(wp.getX(), wp.getY(), wp.getZ());
            if (d > ARRIVE_DIST) { eng.setMoveTarget(wp, 0.07 + warLevel * 0.004); continue; }

            crew.workTicks++;
            doTaskWork(world, crew, t); // one unit of visible work
        }

        boolean allDone = true;
        for (EngTask t : engQueue) if (!t.done) { allDone = false; break; }
        engineersComplete = allDone;
    }

    /** Do one visible unit of work on the crew's current task; mark it done + chain follow-ups. */
    private void doTaskWork(World world, EngCrew crew, EngTask t) {
        switch (t.work) {
            case BRIDGE: case RAMP: case CLEAR: {           // ~130% pick: 2 nodes/tick
                for (int k = 0; k < 2 && t.progress < taskNeeded(t); k++, t.progress++) {
                    int idx = Math.min(t.fromIdx + t.progress, t.toIdx);
                    buildRouteNode(world, route.get(idx));
                }
                break;
            }
            case LADDER:
                placeLadderRungAt(world, route.get(t.fromIdx), t.progress);
                t.progress++;
                break;
            case BREACH:
                breachStep(world, route.get(t.fromIdx), t.progress / 2); // deepen every 2 ticks
                if (warLevel >= 8 && t.progress == 6) {                  // high tech: a real TNT charge
                    plantSapperCharge(world, breachCorridor);
                    crew.retreatUntil = tickAge + 36;
                }
                t.progress++;
                break;
        }
        if (t.progress >= taskNeeded(t)) {
            t.done = true;
            if (t.work == EngWork.BREACH) {                 // open + floor the corridor through the gap
                openGroundBreach(world, breachCorridor);
                levelBreachPath(world, breachCorridor);
            }
            releaseTask(crew);
        }
    }

    /** Reserve the best unclaimed task for a crew: highest priority, then nearest to the crew. */
    private EngTask reserveNextTask(EngCrew crew) {
        releaseTask(crew);
        EngTask best = null; double bestScore = -Double.MAX_VALUE;
        for (EngTask t : engQueue) {
            if (t.done || t.claimedBy != null) continue;
            RouteNode wn = route.get(t.fromIdx);
            double dn = crew.carrier.getDistance(wn.x, wn.gradeY, wn.z);
            double score = t.priority * 100.0 - dn; // priority dominates; distance breaks ties
            if (score > bestScore) { bestScore = score; best = t; }
        }
        if (best != null) { best.claimedBy = crew; crew.current = best; }
        return best;
    }

    /** Release a crew's reserved task back to the queue (death / completion). */
    private void releaseTask(EngCrew crew) {
        if (crew.current != null && crew.current.claimedBy == crew && !crew.current.done) {
            crew.current.claimedBy = null;
        }
        crew.current = null;
    }

    /** Where a crew stands to work a task: just OUTSIDE the node it faces. Breach/ladder anchor on the
     *  wall (fromIdx); road work anchors on the segment MIDPOINT (a stable spot, so the crew actually
     *  "arrives" and builds instead of forever chasing a target that races ahead of it). */
    private BlockPos workPos(World world, EngTask t) {
        int idx = (t.work == EngWork.BREACH || t.work == EngWork.LADDER)
                ? t.fromIdx : (t.fromIdx + t.toIdx) / 2;
        RouteNode wn = route.get(idx);
        return outsidePoint(world, new BlockPos(wn.x, wn.gradeY, wn.z), 2.0);
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
                        && Math.abs(s.target.getX() - breachCorridor.getX()) <= 5
                        && Math.abs(s.target.getZ() - breachCorridor.getZ()) <= 5;
                // Only carve terrain where there is an ACTUAL wall/structure. Hitting open ground used to
                // dig pointless craters in the field (the "catapult is digging holes" report) -- now a
                // round that lands on open grass just makes a visual blast, no excavation.
                if (corridorHit && hasStructureAt(world, s.target)) {
                    openGroundBreach(world, s.target); // carve the wall down to ground at the corridor
                } else if (hasStructureAt(world, s.target)) {
                    breachWall(world, s.target, 2);    // a stray round that still hit a wall: crater it
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

        // EntityFallingBlock physics per tick: motionY -= 0.04 (gravity, BEFORE move), then move, then
        // motion *= 0.98 (drag, AFTER move). We solve the drag+gravity recurrence in closed form so the
        // round lands EXACTLY on the target after `flight` ticks. A long flight time gives a tall, slow,
        // dramatic ~60-block arc (the "fling it way up" the player wanted) -- not a wimpy flat toss --
        // while staying accurate. Every round is TRACKED and killed at impact, so none litter cobblestone.
        final int flight = 85;
        final double gAcc = 0.04;  // gravity per tick (subtracted before move)
        final double drag = 0.98;  // motion multiplier per tick (after move)
        final double dragFactor = (1.0 - Math.pow(drag, flight)) / (1.0 - drag); // effective ticks with drag
        final double mStar = -drag * gAcc / (1.0 - drag); // vertical motion fixed point under gravity+drag

        // A converging CLUSTER of boulders per shot for spectacle (each lands in a tight pattern on the
        // breach). Count scales with war level. The big launch report sells the heave.
        int boulders = Math.max(2, Math.min(5, 2 + warLevel / 3));
        world.playSound(null, from, SoundEvents.ENTITY_GENERIC_EXPLODE, SoundCategory.HOSTILE, 2.4F, 0.7F);
        for (int b = 0; b < boulders; b++) {
            int tx = target.getX() + (b == 0 ? 0 : world.rand.nextInt(5) - 2);
            int tz = target.getZ() + (b == 0 ? 0 : world.rand.nextInt(5) - 2);
            int ty = target.getY();
            double vx = ((tx + 0.5) - launchX) / dragFactor;
            double vz = ((tz + 0.5) - launchZ) / dragFactor;
            double vy = mStar + (((ty + 0.5) - launchY) - flight * (mStar - gAcc)) / dragFactor;
            try {
                // EntityFallingBlock setDead()s on its FIRST tick unless the block at its spawn position
                // IS the falling block. Place the block at the launch position for that one tick.
                double lx = launchX + (b == 0 ? 0 : (world.rand.nextDouble() - 0.5) * 1.5);
                double lz = launchZ + (b == 0 ? 0 : (world.rand.nextDouble() - 0.5) * 1.5);
                BlockPos launchBlock = new BlockPos(lx, launchY, lz);
                if (world.isAirBlock(launchBlock)) {
                    world.setBlockState(launchBlock, Blocks.COBBLESTONE.getDefaultState(), 2);
                }
                EntityFallingBlock fb = new EntityFallingBlock(world, lx, launchY, lz,
                        Blocks.COBBLESTONE.getDefaultState());
                fb.motionX = vx; fb.motionY = vy; fb.motionZ = vz;
                world.spawnEntity(fb);
                catapultShots.add(new CatapultShot(fb, new BlockPos(tx, ty, tz), tickAge + flight));
            } catch (Throwable ignored) {}
        }
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
        // Carve from the breach FOOT (the route grade at the wall), not the core ground -- on sloped or
        // raised terrain those differ, and using the core Y left a lip / hole short of the real opening.
        int floorY = Math.min(at.getY(), site.getY());
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

    /**
     * Once a lane is breached, clear AND floor a wide (5x3) corridor from the wall inward to the base
     * at ground level, so the assault pours straight through instead of bottlenecking on rubble or
     * dropping into holes. Clears via damageBlock (antigrief-aware); floors gaps via setCampBlock so
     * the added cobblestone reverts when the siege ends. This is the "make it bigger + level it out".
     */
    private void levelBreachPath(World world, BlockPos bp) {
        int floorY = bp.getY();
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

    /** True if there is an actual wall/structure at an impact point worth carving (vs open ground). */
    private boolean hasStructureAt(World world, BlockPos at) {
        try {
            for (int dx = -1; dx <= 1; dx++)
                for (int dz = -1; dz <= 1; dz++)
                    for (int dy = 0; dy <= 4; dy++) {
                        if (studio.ERM.war.strategy.SiegeTargeting.isManMade(
                                world.getBlockState(new BlockPos(at.getX() + dx, at.getY() + dy, at.getZ() + dz))))
                            return true;
                    }
        } catch (Throwable ignored) {}
        return false;
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
            // ALREADY a scaffold (a prior hit recorded the real original): leave it. Re-recording would
            // save the SCAFFOLD as the "original", so /war repair restored an invisible block -- exactly
            // the leftover-invisible-blocks bug. The first hit's repair order already has the true block.
            if (EpochRunnerMod.scaffold != null && st.getBlock() == EpochRunnerMod.scaffold) return;
            if (st.getBlockHardness(world, pos) < 0) return; // bedrock / unbreakable

            if (EpochRunnerMod.scaffold != null && isClaimedLand(world, pos)) {
                WarWorldData data = WarWorldData.get(world);
                if (data != null) {
                    if (!data.getRepairMap().containsKey(pos.toImmutable())) {
                        data.addRepairOrder(pos.toImmutable(), st); // record only the FIRST (true) original
                    }
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
        engCrews.clear();
        engQueue.clear();
        route.clear();
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

    /** A terrain obstacle the route can cross (drives the engineering action + mobility cost). */
    private enum Obstacle { PASSABLE, WATER, LAVA, GAP, CLIFF, WALL }

    /** A unit of engineering work pulled off the shared queue. */
    private enum EngWork { CLEAR, BRIDGE, RAMP, LADDER, BREACH }

    /** One node of the saved MilitaryRoute: a column + its terrain-following walkable grade + obstacle. */
    private static final class RouteNode {
        final int x, z, gradeY;
        final Obstacle obstacle;
        RouteNode(int x, int z, int gradeY, Obstacle obstacle) {
            this.x = x; this.z = z; this.gradeY = gradeY; this.obstacle = obstacle;
        }
    }

    /**
     * One reservable engineering task: a span of route nodes [fromIdx,toIdx] and the work to do there.
     * A crew claims it ({@code claimedBy}), advances {@code progress} as it visibly builds, and marks it
     * {@code done}. Higher {@code priority} is taken first (the wall breach = 100).
     */
    private static final class EngTask {
        final EngWork work;
        final Obstacle obstacle;
        final int fromIdx, toIdx;
        final int priority;
        int progress = 0;
        boolean done = false;
        EngCrew claimedBy = null;
        EngTask(EngWork work, Obstacle obstacle, int fromIdx, int toIdx, int priority) {
            this.work = work; this.obstacle = obstacle;
            this.fromIdx = fromIdx; this.toIdx = toIdx; this.priority = priority;
        }
    }

    /** One engineer squad that pulls tasks off the shared queue and builds them. */
    private static final class EngCrew {
        final EntityFormationCarrier carrier;
        EngTask current = null;
        int workTicks = 0;
        int retreatUntil = 0; // tickAge before which the crew pulls back from a planted charge
        EngCrew(EntityFormationCarrier carrier) { this.carrier = carrier; }
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
