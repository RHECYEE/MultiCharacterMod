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
import studio.ERM.war.BattleManagers.entities.EntitySoldier;
import studio.ERM.war.air.AirStrikeController;
import studio.ERM.war.rival.RivalCityManager;
import studio.ERM.war.strategy.BaseAnalyzer;
import studio.ERM.war.strategy.BaseAnalyzer.AccessNode;
import studio.ERM.war.strategy.BaseAnalyzer.AccessType;
import studio.ERM.war.strategy.BaseAnalyzer.BaseType;
import studio.ERM.war.strategy.FireSector;
import studio.ERM.war.strategy.RouteStatus;
import studio.ERM.war.strategy.SiegeObjective;
import studio.ERM.war.strategy.SquadAllocation;
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
    // Shield-wall carriers assigned to GUARD the engineer crews: they march with their ward and hold
    // between it and the base ("engineers advancing under escort"), re-tasked as crews move/die.
    private final List<EntityFormationCarrier> engEscorts = new ArrayList<>();
    // Where each volley/ballista carrier is MARCHING to (its firing slot). They spawn at the staging line
    // and physically walk to the bombard ring instead of materializing mid-field at phase start.
    private final Map<EntityFormationCarrier, BlockPos> bombardSlots = new HashMap<>();
    // Units the director spawns DIRECTLY (cavalry knights + their horses), tracked so stop() removes
    // them -- spawnCavalryCharge used to drop them untracked, so they lingered after the siege ended.
    private final List<net.minecraft.entity.Entity> looseUnits = new ArrayList<>();

    // PHASE 2 -- INTERIOR HEATSPOT CAPTURE + LOOTING. After the breach, scan the base interior for
    // valuable tile entities (chests/beds/furnaces/machines/ME drives), build a loot DEPOT (a 6x6 pad of
    // chests) at the breach, build stairs/ramps to any elevated/blocked heatspot, march squads to each,
    // and TELEPORT the looted items straight into the depot chests. "A breach is not success; a reachable,
    // looted objective is success."
    private List<BlockPos> heatspots = new ArrayList<>();
    private final java.util.Set<BlockPos> capturedSpots = new java.util.HashSet<>();
    private final List<BlockPos> lootChests = new ArrayList<>();
    private BlockPos lootDepot = null;
    // PHYSICAL HAULING: a soldier that REACHES a chest grabs its items and carries them back to the depot
    // (instead of the loot teleporting on proximity). Maps the courier soldier -> the stacks it is carrying.
    private final java.util.Map<EntitySoldier, java.util.List<net.minecraft.item.ItemStack>> couriers = new java.util.HashMap<>();
    private BlockPos routeStart = null;                        // staging foot on the army side of the route
    private boolean engineersComplete = false;
    // The wall breach has a real walk-through opening (set when the BREACH task completes, or by the
    // surge collapse failsafe). Guards the never-stall guarantee now that widening is hand-worked.
    private boolean breachOpened = false;
    // The surge has begun: the route is CLEARED for vehicles no matter what work remains on the queue
    // (crews keep finishing it visually, but nothing WAITS on it anymore). Round-1 vehicle-freeze fix.
    private boolean surgeStarted = false;
    // The state of the assault route, derived PURELY from engQueue (see recomputeRouteStatus). The ONE
    // shared signal between engineers (who clear it) and vehicles (who wait for CLEARED before rolling the
    // breach). Starts OPEN; NOTHING reads it yet -- later commits wire up the vehicles + objective graph.
    private RouteStatus routeStatus = RouteStatus.OPEN;
    // PHASE 2 of the engineer push: once the wall breach is open, each crew mines a staircase/tunnel from
    // the breach interior up into the base to a heat spot the director bound to that crew. tunnelPhase
    // flips true when phase 1 finishes; crewHeatSpot holds each crew's assigned interior objective.
    private boolean tunnelPhase = false;
    private final java.util.Map<EngCrew, BlockPos> crewHeatSpot = new HashMap<>();
    private final List<BlockPos> completedHeatSpots = new ArrayList<>(); // dug-through spots, for the push
    // Typed SHADOW of the heatspot objectives (breach root + one node per heatspot), rebuilt whenever the
    // objective/capture state changes. Read-only: nothing decides off it yet -- it is the substrate the
    // squad-allocation scorer ranks over (see buildObjectiveGraph). The lists above stay source-of-truth.
    private final List<SiegeObjective> objectiveGraph = new ArrayList<>();
    // C13/C14: decouple "objectives are scanned" from "the interior pads/graph are built", so the scan can be
    // pulled EARLIER (engineer phase, C15) while the depot/ramps stay at the surge. Both idempotent guards.
    private boolean heatspotsScanned = false;
    private boolean interiorBuilt = false;
    // ASSAULT ROUTE NETWORK: the ONE ordered corridor of walkable waypoints (staging -> breach -> foothold ->
    // objective) the whole army follows NODE BY NODE, instead of each unit A*-ing to a far objective across
    // walls/water (which stalled them). The front advances only when ~most of the column reaches the node --
    // so the line strides in together (see buildAssaultPath / advanceAssaultColumn).
    private final List<BlockPos> assaultPath = new ArrayList<>();
    private int assaultFront = 0;
    private int assaultFrontSince = 0;
    private boolean assaultPathBuilt = false;
    private int assaultBreachIdx = -1; // index of the breach-mouth node in assaultPath (reform gate)
    private int reformUntil = 0;       // tickAge until which the column HOLDS at the breach to reform
    // Real Flan armour (EntityAIPilot-crewed). The mis-named "VehiclePlatoon" card is just infantry;
    // THESE are the actual driving/firing tanks.
    private final List<EntityAIPilot> vehicles = new ArrayList<>();

    // Catapults: positions behind the line + in-flight shots.
    private final List<BlockPos> catapultSites = new ArrayList<>();
    private final List<CatapultShot> catapultShots = new ArrayList<>();
    private int catapultCooldown = 0;
    // Tick countdown for the BOMBARDMENT-phase jet/bomber dispatcher. Set on phase entry; the
    // P_BOMBARD tick decrements it and fires one air event at zero, then re-arms it -- so jets and
    // bombers come in on a rhythm over the whole barrage instead of all at once (or never).
    private int airStrikeCooldown = 0;
    // Real AW2 siege-machine entities (catapult/ballista) spawned as the visible battery, tracked for
    // cleanup. Spawned reflectively so the vehicle module isn't a hard dependency.
    private final List<Entity> siegeMachines = new ArrayList<>();

    // Direction from the base out to the assault front (the side the army comes from).
    private double frontBearing = 0.0;

    // The ONE breach corridor for this siege, chosen at Deployment. ALL bombardment converges here
    // until there is a real ground-level gap, and the engineers then exploit exactly this spot. A
    // siege has a single objective: open this corridor (not swiss-cheese the whole wall).
    private BlockPos breachCorridor = null;

    // The REAL deployment/staging centre -- where the army actually forms up (front line + camp). The
    // engineer route + bridge MUST start here, not at some independently-computed perimeter point, or the
    // bridge "only does half the distance" and doesn't connect to where everything spawned.
    private BlockPos stagingCenter = null;

    // The INVASION release point: a reachable spot just INSIDE the breach (up the ramp on the platform).
    // The deep core is usually blocked by interior buildings, so carriers targeting it piled at the ramp
    // bottom and never released; releasing here puts soldiers (with an interior home pos) inside the base.
    private BlockPos interiorObjective = null;

    // The defender's REAL base, mapped by the strategic heat map at siege start. The siege re-aims at
    // the cluster CORE (storage/machine/living concentration) and breaches a low-defense PERIMETER
    // chunk facing the army -- "conduct a siege against the base", not "attack where the player stood".
    private java.util.List<StrategicChunk> baseCluster = null;
    private StrategicChunk baseCore = null;
    // Base-type detection (SURFACE/UNDERGROUND/SKY/OCEAN + real approach ground level + doctrine hints),
    // computed ONCE at siege start so the director attacks the base as the right KIND of problem.
    private studio.ERM.war.strategy.BaseAnalyzer.BaseAnalysis baseAnalysis = null;

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
    // Human-pace engineering: a crew walks to its work FRONT and places/mines EXACTLY ONE BLOCK every
    // ENG_WORK_INTERVAL ticks (a vigorous crew rhythm) -- every block of every bridge/breach/ladder is an
    // individual, visible action. A crew that genuinely stops making progress toward its work (not just a
    // long honest march) falls back to remote-building so the siege never stalls -- but ONLY when there is
    // no earlier pending obstacle that could still open its path (a crew waiting at the moat while another
    // crew finishes the causeway is the intended picture, not a stall).
    private static final int ENG_WORK_INTERVAL = 3;        // ~0.15s per block
    private static final int ENG_NO_PROGRESS_GIVEUP = 160; // 8s with ZERO march progress -> remote fallback (keep the work moving)
    private static final int ENG_MARCH_CAP = 1500;         // 75s absolute walk budget per task (never-stall)

    public SiegeDirector(UnitCard card) {
        this.card = card;
    }

    /** The detected base type that drives doctrine, defaulting to SURFACE when analysis is unavailable. */
    private BaseType doctrine() {
        return (baseAnalysis != null && baseAnalysis.primaryType != null)
                ? baseAnalysis.primaryType : BaseType.SURFACE;
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
            // BASE-TYPE DETECTION: classify the base (surface/underground/sky/ocean) + the REAL approach
            // ground level so the breach + doctrine target the actual base, not roofs/trees/the trigger spot.
            try {
                this.baseAnalysis = studio.ERM.war.strategy.BaseAnalyzer.analyze(world, site, tr.protectedBlocks);
                EpochRunnerMod.logger.info("[Siege] base analysis: " + baseAnalysis);
            } catch (Throwable bt) { EpochRunnerMod.logger.warn("[Siege] base analysis failed: " + bt); }
        } catch (Throwable t) {
            EpochRunnerMod.logger.warn("[Siege] targeting failed: " + t);
        }

        // Pick the bearing that stages the army on the MOST land (don't form up on open ocean).
        this.frontBearing = pickLandwardBearing(world);
        // Lock in the staging centre = where the army forms up. The engineer route/bridge starts HERE.
        this.stagingCenter = frontPoint(world, ENCIRCLE_RING, 0.0);

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

        // If the base sits in/by the sea, send a naval picket of Flan S100 boats to ring it on the
        // water -- jeeps can't drive the ocean, so a water base gets boats instead.
        maybeSpawnNavalPatrol(world);
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
        // The route ALWAYS starts at the real staging centre (where the army formed up) so the bridge/
        // road connects the deployment zone to the breach -- not some independent perimeter point that
        // left the bridge floating halfway across the water, disconnected from where everything spawned.
        BlockPos edgeOutside = (stagingCenter != null) ? stagingCenter : frontPoint(world, ENCIRCLE_RING, 0.0);
        routeStart = edgeOutside;

        // BREACH-CANDIDATE SCORING. Instead of always breaching the wall dead-ahead, sample several wall
        // sections within the army's approach arc and pick the best by a composite of: close to interior
        // HEAT (so the breach lands near the loot), LOW defensive danger, and LOW engineer cost (avoid
        // water/lava/ravine approaches). A small straight-ahead prior keeps the route short unless a side
        // is clearly better. Falls back to the original straight-in pick if scoring finds nothing.
        BlockPos wallPos = null;
        java.util.List<BreachCandidate> cands = scoreBreachCandidates(world, edgeOutside);
        BreachCandidate best = null;
        for (BreachCandidate c : cands) if (best == null || c.score > best.score) best = c;
        if (best != null) {
            wallPos = best.wall;
            EpochRunnerMod.logger.info("[Siege] breach scoring: " + cands.size() + " candidate(s), best score="
                    + String.format("%.2f", best.score) + " (access=" + String.format("%.2f", best.access)
                    + " danger=" + String.format("%.2f", best.danger) + " cost=" + String.format("%.2f", best.cost)
                    + ") @ " + xyz(best.wall));
        }
        if (wallPos == null) { // fallback: the original "16 out, snap to first wall within 12" pick
            double toStage = Math.atan2(edgeOutside.getZ() - site.getZ(), edgeOutside.getX() - site.getX());
            int nx = site.getX() + (int) Math.round(Math.cos(toStage) * 16);
            int nz = site.getZ() + (int) Math.round(Math.sin(toStage) * 16);
            BlockPos nearCore = new BlockPos(nx, terrainGroundY(world, nx, nz), nz);
            BlockPos wall = detectWallOnPath(world, outsidePoint(world, nearCore, 12.0), nearCore);
            wallPos = (wall != null) ? wall : nearCore;
        }

        // ACCESS BIAS. If the analyzer found a real ENTRANCE (door/gate) on the army's side, breach THERE
        // -- an existing opening is near-zero engineer cost, and for an underground base the entrance IS
        // the way in. Only snap when it's on the approach side (never breach a wall behind the base).
        if (baseAnalysis != null) {
            AccessNode entr = baseAnalysis.nearestAccessToward(edgeOutside, AccessType.DOOR, AccessType.GATE);
            if (entr != null && entr.pos != null && onApproachSide(entr.pos, edgeOutside)) {
                wallPos = new BlockPos(entr.pos.getX(),
                        terrainGroundY(world, entr.pos.getX(), entr.pos.getZ()), entr.pos.getZ());
                EpochRunnerMod.logger.info("[Siege] breach biased to entrance " + entr);
            }
        }
        // WALK-AROUND CHECK. If there's an EXISTING walk-through opening near the chosen point -- a gate /
        // archway / gap, or simply the OPEN END of the wall -- steer the corridor to THAT instead of
        // breaching a solid wall right beside it ("they breached a wall they could've walked around").
        // planMilitaryRoute then sees a passable column (no breach task) and the army just walks in.
        BlockPos opening = findExistingOpening(world, wallPos);
        if (opening != null) {
            wallPos = opening;
            EpochRunnerMod.logger.info("[Siege] existing opening near breach -> WALK THROUGH @ "
                    + xyz(opening) + " (no breach needed)");
        }

        // Breach foot = the open-field ground just OUTSIDE the wall on the army side (where the assault
        // forms up). Sample surfaceY at 3/6/9 blocks out and take the MEDIAN, which rejects a single tree's
        // roof (too high) or a single pit (too low). Do NOT use terrainGroundY here: it dives past the
        // castle's man-made foundation to natural rock and read ~6 blocks too low (the "ground marked too
        // low" report). The open field outside the wall has no roof, so surfaceY there IS the real ground.
        BlockPos o3 = outsidePoint(world, wallPos, 3.0);
        BlockPos o6 = outsidePoint(world, wallPos, 6.0);
        BlockPos o9 = outsidePoint(world, wallPos, 9.0);
        int s3 = surfaceY(world, o3.getX(), o3.getZ());
        int s6 = surfaceY(world, o6.getX(), o6.getZ());
        int s9 = surfaceY(world, o9.getX(), o9.getZ());
        int gOut = Math.max(Math.min(s3, s6), Math.min(Math.max(s3, s6), s9)); // median of the 3 samples
        // Prefer the BaseAnalyzer's detected approach ground on the army's side (vegetation-skipping +
        // ring-bucketed = the deliberate "real ground" measure that fixes roof/tree/tower false positives),
        // but only when it broadly agrees with the local median (sanity clamp, so a bad analysis can't throw
        // the breach off).
        if (baseAnalysis != null) {
            int approach = baseAnalysis.approachYToward(routeStart != null ? routeStart : wallPos);
            if (Math.abs(approach - gOut) <= 6) gOut = approach;
        }
        breachCorridor = new BlockPos(wallPos.getX(), gOut, wallPos.getZ());
        EpochRunnerMod.logger.info("[Siege] breach corridor = " + breachCorridor + " (near core "
                + site.getX() + "," + site.getZ() + "; route starts at staging " + routeStart.getX()
                + "," + routeStart.getZ() + ")");
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

    /**
     * Scan the wall face around {@code wallPos} for an EXISTING walk-through opening the army could just use
     * -- a gate/archway/gap, or the OPEN END of the wall -- so we don't breach a solid wall right beside an
     * open way in. An opening = a lateral column where body-height space is CLEAR across the wall line (2
     * blocks outside to 3 inside). Returns the nearest such column on the wall line, else null (wall is
     * solid across the scan window -> a real breach is warranted).
     */
    private BlockPos findExistingOpening(World world, BlockPos wallPos) {
        double ang = Math.atan2(wallPos.getZ() - site.getZ(), wallPos.getX() - site.getX()); // core -> wall (outward)
        double px = -Math.sin(ang), pz = Math.cos(ang);  // along the wall face
        double ix = Math.cos(ang), iz = Math.sin(ang);   // outward; -ix,-iz = inward toward the core
        BlockPos best = null; int bestLat = Integer.MAX_VALUE;
        for (int lat = -16; lat <= 16; lat++) {
            int wx = (int) Math.round(wallPos.getX() + px * lat);
            int wz = (int) Math.round(wallPos.getZ() + pz * lat);
            int gy = terrainGroundY(world, wx, wz);
            boolean clear = true;
            for (int d = 2; d >= -3; d--) { // from 2 outside to 3 inside of the wall line
                int cx = (int) Math.round(wx + ix * d);
                int cz = (int) Math.round(wz + iz * d);
                try {
                    if (world.getBlockState(new BlockPos(cx, gy + 1, cz)).getMaterial().isSolid()
                     || world.getBlockState(new BlockPos(cx, gy + 2, cz)).getMaterial().isSolid()) { clear = false; break; }
                } catch (Throwable t) { clear = false; break; }
            }
            if (clear && Math.abs(lat) < bestLat) { bestLat = Math.abs(lat); best = new BlockPos(wx, gy, wz); }
        }
        return best;
    }

    /** True if a solid wall actually blocks the way in just past the breach foot (body height, 0-5 blocks
     *  inward toward the core). Gates the forced-breach failsafe so we never breach an already-open path. */
    private boolean wallBlocksBreach(World world) {
        if (breachCorridor == null) return false;
        double toCore = Math.atan2(site.getZ() - breachCorridor.getZ(), site.getX() - breachCorridor.getX());
        double ix = Math.cos(toCore), iz = Math.sin(toCore);
        int fy = breachCorridor.getY();
        for (int d = 0; d <= 5; d++) {
            int x = (int) Math.round(breachCorridor.getX() + ix * d);
            int z = (int) Math.round(breachCorridor.getZ() + iz * d);
            try {
                if (world.getBlockState(new BlockPos(x, fy + 1, z)).getMaterial().isSolid()
                 || world.getBlockState(new BlockPos(x, fy + 2, z)).getMaterial().isSolid()) return true;
            } catch (Throwable ignored) {}
        }
        return false;
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
    //  BREACH-CANDIDATE SCORING  (pick the smartest wall, not the nearest)
    // ════════════════════════════════════════════════════════════

    /** A scored candidate breach point: where + why (the three normalized components + composite). */
    private static final class BreachCandidate {
        BlockPos wall; double bearing; double danger, access, cost, score;
    }

    /**
     * Sample wall sections across the army's approach ARC (the staging bearing ±25°/±50°), snap each to a
     * real wall, and score it: accessToHeat (close to interior loot = good), danger (defended chunk = bad),
     * engineerCost (water/lava/ravine approach = bad). A small straight-ahead prior avoids needless detours.
     */
    private java.util.List<BreachCandidate> scoreBreachCandidates(World world, BlockPos edgeOutside) {
        java.util.List<BreachCandidate> out = new ArrayList<>();
        if (site == null || edgeOutside == null) return out;
        double baseBearing = Math.atan2(edgeOutside.getZ() - site.getZ(), edgeOutside.getX() - site.getX());
        java.util.List<BlockPos> heat = enumerateHeatSpots(world); // interior value spots for accessToHeat
        double[] offs = { 0.0, Math.toRadians(25), Math.toRadians(-25), Math.toRadians(50), Math.toRadians(-50) };
        for (double off : offs) {
            try {
                double bearing = baseBearing + off;
                int nx = site.getX() + (int) Math.round(Math.cos(bearing) * 16);
                int nz = site.getZ() + (int) Math.round(Math.sin(bearing) * 16);
                BlockPos nearCore = new BlockPos(nx, terrainGroundY(world, nx, nz), nz);
                int fx = site.getX() + (int) Math.round(Math.cos(bearing) * 28);
                int fz = site.getZ() + (int) Math.round(Math.sin(bearing) * 28);
                BlockPos from = new BlockPos(fx, surfaceY(world, fx, fz), fz);
                BlockPos wall = detectWallOnPath(world, from, nearCore);
                if (wall == null) continue;
                BreachCandidate c = new BreachCandidate();
                c.wall = wall; c.bearing = bearing;
                c.danger = candidateDanger(wall);
                c.access = candidateAccessToHeat(wall, heat);
                c.cost = candidateEngineerCost(world, wall);
                c.score = c.access * 0.4 + (1.0 - c.danger) * 0.3 + (1.0 - c.cost) * 0.3
                        + (off == 0.0 ? 0.05 : 0.0); // straight-ahead prior (keep the route short)
                out.add(c);
            } catch (Throwable ignored) {}
        }
        return out;
    }

    /** Defensive danger at a wall: the nearest base-cluster chunk's defense + structural heat, normalized
     *  to ~[0,1]. combatHeat is 0 outside an active fight, so we use the standing defensive footprint. */
    private double candidateDanger(BlockPos wall) {
        if (baseCluster == null || baseCluster.isEmpty() || wall == null) return 0.3;
        int cx = wall.getX() >> 4, cz = wall.getZ() >> 4;
        StrategicChunk nearest = null; double bd = Double.MAX_VALUE;
        for (StrategicChunk c : baseCluster) {
            double d = Math.hypot(c.chunkX - cx, c.chunkZ - cz);
            if (d < bd) { bd = d; nearest = c; }
        }
        if (nearest == null) return 0.3;
        double h = nearest.defenseHeat + nearest.structuralHeat * 0.25;
        return Math.max(0.0, Math.min(1.0, h / 400.0));
    }

    /** How close the wall is to the nearest interior heat spot (1 = right on the loot, ->0 = far away). */
    private double candidateAccessToHeat(BlockPos wall, java.util.List<BlockPos> heat) {
        if (wall == null) return 0.0;
        double best = Double.MAX_VALUE;
        if (heat != null) for (BlockPos h : heat) {
            double d = Math.hypot(wall.getX() - h.getX(), wall.getZ() - h.getZ());
            if (d < best) best = d;
        }
        if (best == Double.MAX_VALUE) { // no heat spots resolved: fall back to distance to the core
            if (site == null) return 0.5;
            best = Math.hypot(wall.getX() - site.getX(), wall.getZ() - site.getZ());
        }
        return 1.0 / (1.0 + best / 24.0);
    }

    /** Rough engineer cost of the final approach into the wall: water/lava/ravine columns cost most,
     *  cliffs less, flat ground nothing. Normalized to ~[0,1]. A pre-filter, not a precise budget. */
    private double candidateEngineerCost(World world, BlockPos wall) {
        if (wall == null) return 0.5;
        BlockPos start = outsidePoint(world, wall, 12.0);
        double dx = wall.getX() - start.getX(), dz = wall.getZ() - start.getZ();
        double dist = Math.hypot(dx, dz);
        if (dist < 1.0) return 0.2;
        double ux = dx / dist, uz = dz / dist;
        int n = (int) Math.ceil(dist), hard = 0, samples = 0;
        int grade = surfaceY(world, start.getX(), start.getZ());
        for (int s = 0; s <= n; s++) {
            int x = (int) Math.round(start.getX() + ux * s);
            int z = (int) Math.round(start.getZ() + uz * s);
            int surf = surfaceY(world, x, z);
            Obstacle ob = classifyColumn(world, x, z, surf, grade);
            if (ob == Obstacle.WATER || ob == Obstacle.LAVA || ob == Obstacle.GAP) hard += 2;
            else if (ob == Obstacle.CLIFF) hard += 1;
            if (ob != Obstacle.WALL) {
                if (surf > grade + 1) grade++;
                else if (surf < grade - 1) grade--;
                else grade = surf;
            }
            samples++;
        }
        return Math.max(0.0, Math.min(1.0, hard / (double) (Math.max(1, samples) * 2)));
    }

    /** Is {@code pos} on the same side of the base as the army staging point (so we don't breach behind it)? */
    private boolean onApproachSide(BlockPos pos, BlockPos edgeOutside) {
        if (site == null || pos == null || edgeOutside == null) return true;
        double ax = pos.getX() - site.getX(), az = pos.getZ() - site.getZ();
        double bx = edgeOutside.getX() - site.getX(), bz = edgeOutside.getZ() - site.getZ();
        return (ax * bx + az * bz) >= 0;
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
        int guns = (warLevel >= 10) ? 5 : (warLevel >= 9) ? 4 : (warLevel >= 8) ? 3 : (warLevel >= 6) ? 2 : 1;
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

        // Stand up a ballista/volley line: it SPAWNS with the army at the staging line and MARCHES DOWN to
        // its mid-range firing slot (refreshBombardment walks each carrier to its slot) -- no more skirmish
        // groups materializing mid-field at the phase flip. They volley as they advance and settle on the slot.
        int volleyGroups = (warLevel <= 3) ? 1 : (warLevel <= 6) ? 2 : 3;
        for (int i = 0; i < volleyGroups; i++) {
            double lateral = (i - (volleyGroups - 1) / 2.0) * 14.0;
            BlockPos slot = frontPoint(world, BOMBARD_RING + 6.0, lateral);
            BlockPos start = frontPoint(world, ENCIRCLE_RING - 4.0, lateral);
            EntityFormationCarrier volley = spawnCarrierAt(world, start, pickBombardCard(), false);
            if (volley != null) {
                volley.setSuppressionTarget(activator != null ? activator.getPosition() : site);
                bombardLine.add(volley);
                bombardSlots.put(volley, slot);
            }
        }

        // Hostile aircraft work the area during the barrage. Helicopters get an initial CAS pass
        // (BF109/heli pool by level), then a TICK-DRIVEN dispatcher (tickBombardmentAir) layers
        // level-scaled JET (L8+) and BOMBER (L9-10) strikes over the whole phase so the player
        // actually SEES planes bombing/strafing the breach and base.
        if (warLevel >= 3) {
            boolean cas = AirStrikeController.requestHostileCAS(world, site, warLevel);
            EpochRunnerMod.logger.info("[Siege] bombardment CAS requested -> launched=" + cas);
        }
        // First jet/bomber wave comes in shortly after deployment, not instantly (let the catapults open).
        // SKY doctrine front-loads the air: the platform can't be reached from the ground, so the aircraft
        // are the main effort, not a garnish -- bring the first wave in fast.
        airStrikeCooldown = (doctrine() == BaseType.SKY) ? 3 * 20 : 8 * 20;
    }

    // ════════════════════════════════════════════════════════════
    //  PHASE 3 — ENGINEER PUSH (breach jobs by tech level)
    // ════════════════════════════════════════════════════════════

    private void beginEngineerPush(World world) {
        phase = P_ENGINEER;
        lastPhaseChangeTick = tickAge;
        engineersComplete = false;
        tunnelPhase = false;
        crewHeatSpot.clear();
        completedHeatSpots.clear();
        EpochRunnerMod.logger.info("[Siege] -> ENGINEER PUSH: planning military route (warLevel=" + warLevel + ")");

        if (breachCorridor == null) chooseBreachCorridor(world);

        // 1) PLAN: classify EVERY obstacle on the assault axis into the saved MilitaryRoute + work queue.
        planMilitaryRoute(world);

        // 1b) C15: KNOW THE INTERIOR LOOT NOW (not at the surge) so the crews can dig the VISIBLE vertical
        //     climb to elevated loot DURING this phase (C16). Scan ONLY -- the depot/ramps still build at the
        //     surge (setupInteriorObjectives is gated by interiorBuilt). Regressions are neutralized: the
        //     tunnels + breach-scoring use the SEPARATE enumerateHeatSpots, and the depot is still sized at
        //     the surge from the same heatspot list. The early beams confirm the objectives are known.
        ensureHeatspotsScanned(world);
        publishAssaultDebug(world);

        // 1c) DEBUG: announce WHAT we detected and HOW the engineers will reach it (base type + route plan),
        //     to the log AND the player's chat -- so the siege's read of the base is visible, not guessed.
        announceSiegePlan(world);

        // 2) CREWS: spawn the engineer squads AT THE STAGING LINE (with the rest of the army -- nothing
        //    materializes at the wall) and let them MARCH to whatever task they reserve. The reserve/stand
        //    machinery walks them the whole way; the progress-based give-up tolerates the long honest march.
        engCrews.clear();
        int engineerCount = (warLevel <= 3) ? 2 : (warLevel <= 6) ? 3 : 4;
        double emid = (engineerCount - 1) / 2.0;
        for (int i = 0; i < engineerCount; i++) {
            double off = (i - emid) * 6.0;
            BlockPos start = frontPoint(world, ENCIRCLE_RING - 6.0, off);
            EntityFormationCarrier eng = spawnCarrierAt(world, start, getCard("SiegeUnit"), true);
            if (eng != null) {
                eng.setEngineerMode(true); // construction crew: NEVER releases combat soldiers
                EngCrew crew = new EngCrew(eng);
                crew.id = i;
                engCrews.add(crew);
            }
        }

        // 3) ESCORT: shield-wall infantry that MARCH WITH the crews and hold between them and the defender
        //    (tickEngineerEscorts re-tasks them as the crews move). They also start at the staging line and
        //    advance under the same honest walk -- engineers advancing under escort, not appearing at the wall.
        engEscorts.clear();
        int shields = (warLevel <= 3) ? 2 : 3;
        double smid = (shields - 1) / 2.0;
        for (int i = 0; i < shields; i++) {
            double off = (i - smid) * 8.0;
            BlockPos start = frontPoint(world, ENCIRCLE_RING - 10.0, off);
            EntityFormationCarrier shield = spawnCarrierAt(world, start, getCard("ShieldWall"), true);
            if (shield != null) {
                shield.setBattleContext(activator, outsidePoint(world, breachCorridor, 5.0));
                engEscorts.add(shield);
            }
        }

        // SKY doctrine at LOW tech: with no aircraft to fast-rope, raise a laddered SIEGE TOWER up to the
        // platform so the infantry can physically climb to a floating base. (L6+ relies on helis/jets.)
        if (doctrine() == BaseType.SKY && warLevel < 6) buildSiegeTower(world);
    }

    /**
     * SKY low-tech assault aid: raise a laddered cobblestone SIEGE TOWER from the approach ground up to the
     * platform level near the breach, so infantry can climb to a floating base when there are no aircraft.
     * Reverts with the camp (setCampBlock). Guarded; a no-op if the base isn't meaningfully elevated.
     */
    private void buildSiegeTower(World world) {
        if (site == null) return;
        BlockPos at = (breachCorridor != null) ? breachCorridor : site;
        int bx = at.getX(), bz = at.getZ();
        int groundY = (baseAnalysis != null)
                ? baseAnalysis.approachYToward(routeStart != null ? routeStart : at)
                : surfaceY(world, bx, bz);
        int topY = site.getY() + 1; // one above the platform deck so troops step off onto it
        if (topY - groundY < 5) return; // not meaningfully elevated; ramps/stairs already handle it
        try {
            for (int y = groundY; y <= topY; y++)
                setCampBlock(world, new BlockPos(bx, y, bz), Blocks.COBBLESTONE.getDefaultState());
            // Ladders on the +X face: the pillar sits to the WEST of the ladder, so the ladder FACING = EAST.
            IBlockState ladder = Blocks.LADDER.getDefaultState()
                    .withProperty(net.minecraft.block.BlockLadder.FACING, net.minecraft.util.EnumFacing.EAST);
            for (int y = groundY; y <= topY; y++)
                setCampBlock(world, new BlockPos(bx + 1, y, bz), ladder);
            EpochRunnerMod.logger.info("[Siege] SKY siege tower raised at " + bx + "," + bz
                    + " from y" + groundY + " to y" + topY);
        } catch (Throwable t) {
            EpochRunnerMod.logger.warn("[Siege] siege tower build failed: " + t.getMessage());
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

        int breachIdx = -1; double breachBest = Double.MAX_VALUE;
        int gradeY = surfaceY(world, routeStart.getX(), routeStart.getZ());
        // Walk the ray at 0.5-block resolution and DEDUPE cells, so a DIAGONAL path covers every cell it
        // passes through -- no corner gaps (the "dashed / broken" bridge). Then step the grade per cell.
        int steps = (int) Math.ceil(dist * 2);
        int lastX = Integer.MIN_VALUE, lastZ = Integer.MIN_VALUE;
        for (int s = 0; s <= steps; s++) {
            double f = s * 0.5;
            int x = (int) Math.round(routeStart.getX() + ux * f);
            int z = (int) Math.round(routeStart.getZ() + uz * f);
            if (x == lastX && z == lastZ) continue; // the half-step landed on the same cell
            lastX = x; lastZ = z;
            int surf = surfaceY(world, x, z);
            Obstacle ob = classifyColumn(world, x, z, surf, gradeY);
            // Terrain-following walkable grade -- clamp the step to +-1 so the route CONNECTS.
            if (ob == Obstacle.WALL) {
                // pass THROUGH at grade (we breach it, we don't climb the grade up it)
            } else if (ob == Obstacle.WATER || ob == Obstacle.LAVA) {
                // HOLD the causeway LEVEL across liquid -- do NOT follow the surface DOWN into deep water
                // (the dip that left the bridge underwater / broken). Keep the last land grade across the span.
            } else {
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
            // The breach + the ramp-up (levelBreachPath) ARE the path in -- no LADDER task (the old
            // escalade ladders placed floating on the breached wall and did nothing).
            engQueue.add(new EngTask(w, ob, i, j, w == EngWork.BREACH ? 100 : 50 - i));
            i = j + 1;
        }

        // FAILSAFE: guarantee a breach task ONLY IF a wall genuinely blocks the way in. Previously this
        // ALWAYS forced a breach when the route scan found no WALL node -- but "no wall on the route" means
        // the army can already WALK IN, so forcing a breach made engineers mine a wall next to an open path
        // ("breached a wall they could've walked around"). Now it only fires when there's a real solid wall
        // at body height just inside the breach foot.
        boolean haveBreach = false;
        for (EngTask t : engQueue) if (t.work == EngWork.BREACH) { haveBreach = true; break; }
        if (!haveBreach && breachIdx >= 0 && wallBlocksBreach(world)) {
            engQueue.add(new EngTask(EngWork.BREACH, Obstacle.WALL, breachIdx, breachIdx, 100));
        }
        // OCEAN doctrine: prioritise the CAUSEWAY -- get the bridges across the water down first so the
        // assault has dry footing. A sea base is won by REACHING it, not by cratering a distant wall.
        if (doctrine() == BaseType.OCEAN)
            for (EngTask t : engQueue) if (t.work == EngWork.BRIDGE) t.priority = Math.max(t.priority, 90);
        engQueue.sort((a, b) -> b.priority - a.priority);
        EpochRunnerMod.logger.info("[Siege] MilitaryRoute: " + route.size() + " nodes; "
                + engQueue.size() + " engineering tasks " + summarizeQueue());
        publishEngineerPlanDebug(world);
        recomputeRouteStatus();
    }

    /**
     * Derive {@link #routeStatus} PURELY from the current engineer queue -- a read-only projection over
     * state that already exists, so it changes NO behavior on its own. Called whenever the queue's shape
     * changes (route planned, a task reserved/finished). BLOCKED_TOO_HARD is sticky and set externally
     * (a unit reporting it cannot pass); the derivation never clears it until the work actually completes.
     */
    private void recomputeRouteStatus() {
        // Once the surge is on, the route is CLEARED for good: crews may still be finishing corridor/
        // ladder work visually (they now keep ticking into the surge/assault), but no vehicle waits on it.
        if (surgeStarted) { routeStatus = RouteStatus.CLEARED; return; }
        if (engineersComplete) { routeStatus = RouteStatus.CLEARED; return; }
        // Sticky: a reported hard-block stays until engineersComplete flips it CLEARED above.
        if (routeStatus == RouteStatus.BLOCKED_TOO_HARD) return;
        boolean anyUnclaimed = false, anyClaimed = false, anyPending = false;
        for (EngTask t : engQueue) {
            if (t.done) continue;
            anyPending = true;
            if (t.claimedBy != null) anyClaimed = true; else anyUnclaimed = true;
        }
        if (!anyPending)      routeStatus = engQueue.isEmpty() ? RouteStatus.OPEN : RouteStatus.CLEARED;
        else if (anyUnclaimed) routeStatus = RouteStatus.NEEDS_ENGINEER;
        else                   routeStatus = RouteStatus.BEING_CLEARED;
    }

    /** Read-only view of the assault route's state (the engineer<->vehicle shared signal). */
    public RouteStatus getRouteStatus() { return routeStatus; }

    /** The single breach corridor chosen at Deployment (null before it is chosen). */
    public BlockPos getBreachCorridor() { return breachCorridor; }

    /**
     * PHASE 2 of the engineer push. The wall breach is open; now each engineer squad mines a real,
     * terrain-following staircase/tunnel from the breach interior up INTO the base to the heat spot the
     * director bound to that squad, and the assault then pushes in along those tunnels. Resets
     * engineersComplete so tickEngineers keeps the crews working the new TUNNEL tasks.
     */
    private void beginEngineerTunnels(World world) {
        tunnelPhase = true;
        // Clear out any drained phase-1 tasks (keeps the all-done check + reserveNextTask scan clean).
        // The handoff now fires as soon as the EXTERNAL route work is done, so WIDEN/CORRIDOR polish may
        // still be mid-work -- those tasks stay queued and their crews stay ON them.
        engQueue.removeIf(t -> t.done);
        // Release only IDLE crews (their task finished); a crew mid-WIDEN/CORRIDOR keeps its job and picks
        // up remaining tunnel/ladder work from the queue afterwards.
        for (EngCrew crew : engCrews) {
            if (crew.current != null && crew.current.done) releaseTask(crew);
        }

        // The interior foot of the breach = ~3 blocks inside the wall, on the breach grade. Every tunnel
        // starts here and fans out to its own heat spot.
        double toCore = Math.atan2(site.getZ() - breachCorridor.getZ(), site.getX() - breachCorridor.getX());
        int ifx = breachCorridor.getX() + (int) Math.round(Math.cos(toCore) * 3);
        int ifz = breachCorridor.getZ() + (int) Math.round(Math.sin(toCore) * 3);
        BlockPos interiorFoot = new BlockPos(ifx, breachCorridor.getY(), ifz);

        List<BlockPos> spots = enumerateHeatSpots(world);
        boolean anyPending = false;
        for (EngTask t : engQueue) if (!t.done) { anyPending = true; break; }
        if (spots.isEmpty()) { engineersComplete = !anyPending; return; } // nothing to dig to

        int planned = 0;
        for (int ci = 0; ci < engCrews.size(); ci++) {
            EngCrew crew = engCrews.get(ci);
            if (crew == null || crew.carrier == null || crew.carrier.isDead) continue;
            BlockPos spot = spots.get(ci % spots.size()); // round-robin if more crews than spots
            crewHeatSpot.put(crew, spot);
            EngTask tunnel = planEngineerTunnel(world, interiorFoot, spot, ci);
            if (tunnel != null) {
                tunnel.shaftSpot = spot; // the DESTINATION rides on the task (any crew may finish it)
                if (crew.current == null) { // idle squad: claim ITS tunnel now; busy squads come back for theirs
                    tunnel.claimedBy = crew;
                    crew.current = tunnel;
                    crew.reservedAtTick = tickAge;
                    crew.arrived = false;
                    crew.bestDistToStand = Double.MAX_VALUE;
                    crew.noProgressTicks = 0; crew.marchTicks = 0; crew.remoteLogged = false;
                }
                engQueue.add(tunnel);
                planned++;
            }
        }
        // Also queue a VISIBLE TRIPLE-LADDER up (or down) to each real loot spot that is ABOVE or BELOW the
        // entry level (the scanHeatspots tile-entity loot, available now that the scan runs at engineer push).
        // Each is UNCLAIMED -- a crew reserves one after finishing its tunnel and builds the 3-pillar climb
        // rung by rung. The assault-begin raiseLadderColumn failsafe still guarantees the climb exists.
        int shafts = 0;
        for (BlockPos spot : heatspots) {
            EngTask ladder = planTripleLadder(world, spot, interiorFoot.getY());
            if (ladder != null) { engQueue.add(ladder); shafts++; }
        }
        engQueue.sort((a, b) -> b.priority - a.priority);
        // Only report "all done" when NOTHING is pending (tunnels, ladders, or leftover widen/corridor).
        engineersComplete = (planned == 0 && shafts == 0) && !anyPending;
        recomputeRouteStatus();
        EpochRunnerMod.logger.info("[Siege] PHASE2 tunnels: " + planned + " interior staircase(s) + "
                + shafts + " triple-ladder(s) to loot, from " + xyz(interiorFoot));
    }

    /**
     * Enqueue a TRIPLE_LADDER task so a crew VISIBLY builds a 3-pillar laddered climb UP (or DOWN) to a loot
     * spot that sits above/below the entry level, during the engineer phase (instead of the column blinking in
     * at the surge). Anchors a route node at the column base so the reserve/stand machinery walks the crew
     * there. Returns null when the spot is within 1 block of the entry (no climb needed). Reachability is still
     * guaranteed by the surge {@link #raiseLadderColumn} failsafe (also triple, idempotent, completes partials).
     */
    private EngTask planTripleLadder(World world, BlockPos spot, int fromY) {
        if (spot == null || Math.abs(spot.getY() - fromY) <= 1) return null; // level with the entry -> no ladder
        int idx = route.size();
        int lowY = Math.min(fromY, spot.getY());
        // RouteNode is (x, z, gradeY): base anchor for stand/reserve. (Args were swapped here before --
        // the anchor landed at z=lowY with grade=Z, garbling reserve-distance scoring for ladder tasks.)
        route.add(new RouteNode(spot.getX(), spot.getZ(), lowY, Obstacle.WALL));
        EngTask t = new EngTask(EngWork.TRIPLE_LADDER, Obstacle.WALL, idx, idx, 55); // just below the TUNNEL band
        t.shaftSpot = spot;
        t.shaftBaseY = fromY;
        return t;
    }

    /**
     * Enumerate the interior heat spots the tunnels target: the base-cluster chunks ranked by INTERIOR
     * VALUE (storage+machine+living+power, the same metric WarHeatMap.coreOf uses), as world chunk
     * centres. Falls back to the heat core, then the resolved site, so a protected/structure target
     * (no cluster) still gets one objective.
     */
    private List<BlockPos> enumerateHeatSpots(World world) {
        List<BlockPos> out = new ArrayList<>();
        if (baseCluster != null && !baseCluster.isEmpty()) {
            List<StrategicChunk> ranked = new ArrayList<>(baseCluster);
            ranked.sort((a, b) -> Double.compare(
                    (b.storageHeat + b.machineHeat + b.livingHeat + b.powerHeat),
                    (a.storageHeat + a.machineHeat + a.livingHeat + a.powerHeat)));
            int max = (warLevel >= 6) ? 4 : 3; // cap to the crew count band
            for (StrategicChunk c : ranked) {
                if (out.size() >= max) break;
                int wx = (c.chunkX << 4) + 8, wz = (c.chunkZ << 4) + 8;
                out.add(new BlockPos(wx, terrainGroundY(world, wx, wz), wz));
            }
        }
        if (out.isEmpty() && baseCore != null) {
            int wx = (baseCore.chunkX << 4) + 8, wz = (baseCore.chunkZ << 4) + 8;
            out.add(new BlockPos(wx, terrainGroundY(world, wx, wz), wz));
        }
        if (out.isEmpty() && site != null) {
            out.add(new BlockPos(site.getX(), terrainGroundY(world, site.getX(), site.getZ()), site.getZ()));
        }
        return out;
    }

    /**
     * Ray-walk from the breach interior foot to one heat spot, appending terrain-following RouteNodes to
     * the saved route whose gradeY STAIRS up/down (clamped +-1 per column, so it is a real staircase, not
     * a vertical hole). Returns the EngTask spanning the appended nodes (work = TUNNEL), or null if the
     * span is trivial.
     */
    private EngTask planEngineerTunnel(World world, BlockPos from, BlockPos spot, int crewIdx) {
        double dx = spot.getX() - from.getX(), dz = spot.getZ() - from.getZ();
        double dist = Math.sqrt(dx * dx + dz * dz);
        if (dist < 2.0) return null;
        double ux = dx / dist, uz = dz / dist;
        int n = (int) Math.min(Math.ceil(dist), 64); // cap a runaway tunnel length
        int fromIdx = route.size();
        int gradeY = from.getY();
        for (int s = 1; s <= n; s++) {
            int x = (int) Math.round(from.getX() + ux * s);
            int z = (int) Math.round(from.getZ() + uz * s);
            int grnd = terrainGroundY(world, x, z); // follow the natural floor INSIDE the base
            if (grnd > gradeY + 1) gradeY++;          // climb one step (staircase up)
            else if (grnd < gradeY - 1) gradeY--;     // descend one step (staircase down)
            else gradeY = grnd;
            route.add(new RouteNode(x, z, gradeY, Obstacle.WALL)); // mined like a wall passage
        }
        int toIdx = route.size() - 1;
        if (toIdx < fromIdx) return null;
        // 60 - crewIdx keeps the tunnels distinct + ordered; far below the (already-done) breach's 100.
        return new EngTask(EngWork.TUNNEL, Obstacle.WALL, fromIdx, toIdx, 60 - crewIdx);
    }

    /**
     * ENGINEER DEBUG OVERLAY. Show -- big and in-world -- exactly what the engineers decided: the
     * objective beam, the planned route line, a coloured box on every obstacle with a floating label of
     * the obstacle type + the tool chosen + the tools REJECTED (red X), and full logs of all of it
     * (including the bombardment foot-Y vs roof-Y check). Lets the player SEE the engineers' reasoning.
     */
    private void publishEngineerPlanDebug(World world) {
        try {
            java.util.List<studio.ERM.war.strategy.WarHeatDebug.Marker> mk = new ArrayList<>();
            java.util.List<studio.ERM.war.strategy.WarHeatDebug.Label> lb = new ArrayList<>();

            // OBJECTIVE -- tall green beam + label. Also log the foot-Y vs roof-Y so we can see whether
            // the bombardment is aiming at ground level (breach) or the roof (surfaceY).
            int roofY = surfaceY(world, breachCorridor.getX(), breachCorridor.getZ());
            mk.add(studio.ERM.war.strategy.WarHeatDebug.Marker.beam(breachCorridor, 0f, 1f, 0f, 32));
            lb.add(new studio.ERM.war.strategy.WarHeatDebug.Label(breachCorridor.up(33),
                    "OBJECTIVE: BREACH " + xyz(breachCorridor) + "  footY=" + breachCorridor.getY()
                    + " roofY=" + roofY));
            EpochRunnerMod.logger.info("[EngDebug] ===== ENGINEER PLAN =====");
            EpochRunnerMod.logger.info("[EngDebug] objective=BREACH @ " + xyz(breachCorridor)
                    + "  bombard footY=" + breachCorridor.getY() + " (ground) vs roofY=" + roofY
                    + " -> catapults aim footY (" + (breachCorridor.getY() < roofY ? "GOOD: ground" : "check") + ")");

            // ROUTE -- cyan line staging -> breach.
            if (routeStart != null) {
                mk.add(studio.ERM.war.strategy.WarHeatDebug.Marker.line(routeStart, breachCorridor, 0f, 1f, 1f));
                lb.add(new studio.ERM.war.strategy.WarHeatDebug.Label(routeStart.up(3),
                        "STAGING " + xyz(routeStart) + " -> route " + route.size() + " nodes"));
                double straight = Math.sqrt(routeStart.distanceSq(breachCorridor));
                EpochRunnerMod.logger.info("[EngDebug] route: staging " + xyz(routeStart) + " -> breach "
                        + xyz(breachCorridor) + "  straight=" + (int) straight + " blocks, " + route.size() + " nodes");
            }

            // OBSTACLES -- a coloured box + tool label per task, plus a red X for the rejected tools.
            int idx = 0;
            for (EngTask t : engQueue) {
                RouteNode wn = route.get(t.fromIdx);
                BlockPos at = new BlockPos(wn.x, wn.gradeY, wn.z);
                float[] col = obstacleColor(t.obstacle);
                int span = t.toIdx - t.fromIdx + 1;
                mk.add(studio.ERM.war.strategy.WarHeatDebug.Marker.box(at, col[0], col[1], col[2], 2));
                lb.add(new studio.ERM.war.strategy.WarHeatDebug.Label(at.up(5),
                        t.obstacle + " -> " + toolLabel(t.work) + "  (span " + span + ", prio " + t.priority + ")"));
                // rejected tools: a red X a couple blocks to the side + the reason.
                BlockPos rej = at.add(3, 0, 0);
                mk.add(studio.ERM.war.strategy.WarHeatDebug.Marker.cross(rej, 2));
                lb.add(new studio.ERM.war.strategy.WarHeatDebug.Label(rej.up(3), "REJECTED: " + rejectedReason(t.obstacle)));
                EpochRunnerMod.logger.info("[EngDebug] obstacle #" + (idx++) + " " + t.obstacle + " @ " + xyz(at)
                        + " span=" + span + " -> TOOL=" + toolLabel(t.work) + " | rejected: " + rejectedReason(t.obstacle));
            }

            studio.ERM.war.strategy.WarHeatDebug.show(world, mk, lb, 90 * 20);
            EpochRunnerMod.logger.info("[EngDebug] crews=" + engCrews.size() + " (all engineerMode=combat-disabled), "
                    + "showing plan board for 90s. Watch [EngDebug] EXEC logs for live tool work.");
        } catch (Throwable t) {
            EpochRunnerMod.logger.warn("[EngDebug] publish failed: " + t);
        }
    }

    private static String xyz(BlockPos p) { return p.getX() + "," + p.getY() + "," + p.getZ(); }

    private static float[] obstacleColor(Obstacle o) {
        switch (o) {
            case WATER: return new float[]{0.1f, 0.4f, 1.0f}; // blue
            case LAVA:  return new float[]{1.0f, 0.4f, 0.0f}; // orange
            case GAP:   return new float[]{0.7f, 0.0f, 1.0f}; // purple
            case CLIFF: return new float[]{1.0f, 1.0f, 0.0f}; // yellow
            case WALL:  return new float[]{1.0f, 0.0f, 0.0f}; // red
            default:    return new float[]{1.0f, 1.0f, 1.0f};
        }
    }
    private static String toolLabel(EngWork w) {
        switch (w) {
            case BRIDGE:   return "BRIDGE(cobblestone)";
            case RAMP:     return "RAMP/CUT(cobblestone)";
            case LADDER:   return "LADDER(scale wall)";
            case BREACH:   return "BREACH(mine 3x4 gap)";
            case TUNNEL:   return "TUNNEL(staircase to heat)";
            case WIDEN:    return "WIDEN(open the gap wide)";
            case CORRIDOR: return "CORRIDOR(floor the way in)";
            case ACCESS:   return "ACCESS(stairs to the loot)";
            default:       return "CLEAR(head)";
        }
    }
    private static String rejectedReason(Obstacle o) {
        switch (o) {
            case WATER: case LAVA: case GAP:
                return "LADDER(no wall), RAMP(no step), BREACH(not solid)";
            case CLIFF:
                return "BRIDGE(no liquid/gap), BREACH(not man-made)";
            case WALL:
                return "BRIDGE/RAMP(it's a vertical wall, must mine/ladder)";
            default:
                return "BRIDGE/BREACH(passable already)";
        }
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
    /** Work UNITS a task spans (a unit = one route node / breach slice / ladder row / baked op; each unit
     *  decomposes into individual BlockOps that are performed one per swing). */
    private int taskNeeded(EngTask t) {
        if (t.baked != null) return t.baked.size();               // pre-baked jobs: one op per unit
        switch (t.work) {
            case BREACH: return 16;
            case LADDER: return RAMP_STEPS + RAMP_CREST;
            case TUNNEL: return Math.max(1, t.toIdx - t.fromIdx + 1); // one mined node of work each
            case TRIPLE_LADDER: return (t.shaftSpot != null)         // one ladder ROW per unit
                    ? Math.max(1, Math.abs(t.shaftSpot.getY() - t.shaftBaseY) + 1) : 1;
            default:     return Math.max(1, t.toIdx - t.fromIdx + 1);
        }
    }
    private String summarizeQueue() {
        int br = 0, ld = 0, bg = 0, rp = 0, cl = 0, tn = 0, sh = 0, wd = 0, co = 0, ac = 0;
        for (EngTask t : engQueue) switch (t.work) {
            case BREACH: br++; break; case LADDER: ld++; break; case BRIDGE: bg++; break;
            case RAMP: rp++; break; case TUNNEL: tn++; break; case TRIPLE_LADDER: sh++; break;
            case WIDEN: wd++; break; case CORRIDOR: co++; break; case ACCESS: ac++; break; default: cl++; break;
        }
        return "(breach=" + br + " ladder=" + ld + " bridge=" + bg + " ramp=" + rp + " clear=" + cl
                + " tunnel=" + tn + " triladder=" + sh + " widen=" + wd + " corridor=" + co + " access=" + ac + ")";
    }

    /** Log + chat-announce the detected base type and HOW the engineers will reach it -- so the siege's read
     *  of the base (and its plan) is visible to the player, not guessed at. Called at the engineer push. */
    private void announceSiegePlan(World world) {
        try {
            BaseType bt = doctrine();
            String how;
            switch (bt) {
                case UNDERGROUND: how = "dig stair-tunnels to the entrances/shafts and hold the exits"; break;
                case SKY:         how = "build a siege tower / fast-rope onto the platform"; break;
                case OCEAN:       how = "bridge a causeway across the water to the shore"; break;
                default:          how = "breach the wall, then ramp/bridge the approach"; break;
            }
            String plan = "[Siege] BASE TYPE: " + bt + " -> engineers will " + how + ". Route = "
                    + route.size() + " nodes " + summarizeQueue()
                    + "; breach @ " + (breachCorridor != null ? xyz(breachCorridor) : "?")
                    + "; " + heatspots.size() + " loot objective(s).";
            EpochRunnerMod.logger.info(plan);
            if (activator != null)
                activator.sendMessage(new net.minecraft.util.text.TextComponentString("§6" + plan));
        } catch (Throwable t) {
            EpochRunnerMod.logger.warn("[Siege] announceSiegePlan failed: " + t);
        }
    }

    /**
     * Build one connected, terrain-following route node across a 3-wide lane: floor the grade over any
     * gap/liquid (bridge/fill) AND cut a head-high passage through any solid (ramp-cut/clear). Floor
     * blocks go through setCampBlock (revert on siege end); cuts go through damageBlock (antigrief-aware).
     */
    /** Half-width of engineer lanes/breaches, scaled by tech level so high-level armies get a road wide
     *  enough for TANKS and jeeps (L1-5 = 3 wide, L6-8 = 5 wide, L9-10 = 7 wide). */
    private int laneHalf() { return (warLevel >= 9) ? 3 : (warLevel >= 6) ? 2 : 1; }
    /** Headroom of the lane (taller at high level so vehicles fit under). */
    private int laneHeight() { return (warLevel >= 6) ? 4 : 3; }

    // ── PER-BLOCK OP COLLECTION ─────────────────────────────────────────────────────────────────
    // Every engineer job is decomposed into individual BlockOps (mine ONE block / place ONE block).
    // The collectors below hold the exact geometry the old instant builders used, but EMIT ops instead
    // of setting blocks -- the paced work loop then performs exactly one op per swing, so the player
    // watches every block of every bridge, breach, corridor and ladder get physically worked.

    /** Collect the ops for ONE route node of road/bridge/ramp work: floor/deck placements first (the
     *  crew lays the walkway it stands on), then the headroom cuts. Same geometry as the old instant
     *  buildRouteNode, including the raise-the-deck-over-water discipline. */
    private void collectRouteNodeOps(World world, RouteNode node, java.util.Collection<BlockOp> out) {
        double ang = Math.atan2(breachCorridor.getZ() - site.getZ(), breachCorridor.getX() - site.getX());
        double px = -Math.sin(ang), pz = Math.cos(ang);
        int hw = laneHalf(), hh = laneHeight();
        java.util.List<BlockOp> cuts = new ArrayList<>();
        for (int w = -hw; w <= hw; w++) {
            int x = (int) Math.round(node.x + px * w);
            int z = (int) Math.round(node.z + pz * w);
            try {
                BlockPos floor = new BlockPos(x, node.gradeY - 1, z);
                // Over water the grade sits AT the water surface, so a deck at gradeY-1 is 1 block UNDER
                // water and floods. Detect water and RAISE the deck one block so the causeway sits ABOVE
                // the waterline; on land the floor is gradeY-1 and the walk level gradeY, as before.
                boolean overWater = world.getBlockState(floor).getMaterial().isLiquid()
                        || world.getBlockState(new BlockPos(x, node.gradeY, z)).getMaterial().isLiquid();
                if (world.isAirBlock(floor) || world.getBlockState(floor).getMaterial().isLiquid()) {
                    out.add(BlockOp.place(floor, Blocks.COBBLESTONE.getDefaultState()));
                }
                int deck = node.gradeY;
                if (overWater) {
                    out.add(BlockOp.place(new BlockPos(x, node.gradeY, z), Blocks.COBBLESTONE.getDefaultState()));
                    deck = node.gradeY + 1; // walkway one block above the waterline
                }
                for (int y = deck; y <= deck + hh; y++) {
                    BlockPos hp = new BlockPos(x, y, z);
                    Material m = world.getBlockState(hp).getMaterial();
                    if (m.isLiquid() || m.isSolid()) cuts.add(BlockOp.mine(hp)); // headroom cut
                }
            } catch (Throwable ignored) {}
        }
        out.addAll(cuts);
    }

    /** Collect the ops for ONE node of the phase-2 interior staircase: always floor the tread, then
     *  carve the head-high passage. Same discipline as the road work (floors revert; cuts antigrief). */
    private void collectTunnelNodeOps(World world, RouteNode node, java.util.Collection<BlockOp> out) {
        double ang = Math.atan2(breachCorridor.getZ() - site.getZ(), breachCorridor.getX() - site.getX());
        double px = -Math.sin(ang), pz = Math.cos(ang);
        int hw = laneHalf(), hh = laneHeight();
        java.util.List<BlockOp> cuts = new ArrayList<>();
        for (int w = -hw; w <= hw; w++) {
            int x = (int) Math.round(node.x + px * w);
            int z = (int) Math.round(node.z + pz * w);
            try {
                BlockPos floor = new BlockPos(x, node.gradeY - 1, z);
                if (world.isAirBlock(floor) || world.getBlockState(floor).getMaterial().isLiquid()) {
                    out.add(BlockOp.place(floor, Blocks.COBBLESTONE.getDefaultState()));
                }
                for (int y = node.gradeY; y <= node.gradeY + hh; y++) {
                    BlockPos hp = new BlockPos(x, y, z);
                    Material m = world.getBlockState(hp).getMaterial();
                    if (m.isLiquid() || m.isSolid()) cuts.add(BlockOp.mine(hp));
                }
            } catch (Throwable ignored) {}
        }
        out.addAll(cuts);
    }

    /** Collect the mining ops for one DEPTH SLICE of the wall breach (bottom-up per column, so the gap
     *  visibly opens from the ground and deepens into the wall). */
    private void collectBreachStepOps(World world, RouteNode wn, int depth, java.util.Collection<BlockOp> out) {
        double inAng = Math.atan2(site.getZ() - wn.z, site.getX() - wn.x);
        double ix = Math.cos(inAng), iz = Math.sin(inAng);  // inward toward the core
        double px = -iz, pz = ix;                            // along the wall face (width)
        int cx = (int) Math.round(wn.x + ix * depth);
        int cz = (int) Math.round(wn.z + iz * depth);
        int hw = laneHalf(), hh = laneHeight();
        for (int y = wn.gradeY; y <= wn.gradeY + hh; y++) {   // bottom-up: the opening grows from the floor
            for (int w = -hw; w <= hw; w++) {
                int x = (int) Math.round(cx + px * w);
                int z = (int) Math.round(cz + pz * w);
                try {
                    Material m = world.getBlockState(new BlockPos(x, y, z)).getMaterial();
                    if (m.isSolid() || m.isLiquid()) out.add(BlockOp.mine(new BlockPos(x, y, z)));
                } catch (Throwable ignored) {}
            }
        }
    }

    /** Collect ops for ONE row of the triple ladder (3 pillar+ladder columns): each pillar block and
     *  each ladder is its own op, so the climb visibly grows rung by rung. */
    private void collectLadderRowOps(World world, BlockPos spot, int y, java.util.Collection<BlockOp> out) {
        IBlockState ladder = Blocks.LADDER.getDefaultState()
                .withProperty(net.minecraft.block.BlockLadder.FACING, net.minecraft.util.EnumFacing.EAST);
        for (int dz = -1; dz <= 1; dz++) {
            try {
                BlockPos pillar = new BlockPos(spot.getX(), y, spot.getZ() + dz);
                if (world.isAirBlock(pillar) || world.getBlockState(pillar).getMaterial().isLiquid())
                    out.add(BlockOp.place(pillar, Blocks.COBBLESTONE.getDefaultState()));
                out.add(BlockOp.place(new BlockPos(spot.getX() + 1, y, spot.getZ() + dz), ladder));
            } catch (Throwable ignored) {}
        }
    }

    /** Collect the ops of the breach WIDENING (the old instant openGroundBreach, hand-worked): raze each
     *  column of the level-scaled opening from the foot up. After a sapper blast most are already gone
     *  and re-validate to no-ops -- low-tech armies mine the whole opening by hand. */
    private void collectOpenGroundBreachOps(World world, BlockPos at, java.util.Collection<BlockOp> out) {
        int floorY = at.getY();
        int half = (warLevel >= 9) ? 6 : (warLevel >= 6) ? 4 : 2;
        int depth = (warLevel >= 9) ? 4 : (warLevel >= 6) ? 2 : 1;
        double ang = Math.atan2(at.getZ() - site.getZ(), at.getX() - site.getX());
        double px = -Math.sin(ang), pz = Math.cos(ang);
        double ix = (site.getX() - at.getX()), iz = (site.getZ() - at.getZ());
        double ilen = Math.max(0.001, Math.hypot(ix, iz));
        ix /= ilen; iz /= ilen;
        for (int d = 0; d <= depth; d++) {
            for (int w = -half; w <= half; w++) {
                int x = (int) Math.round(at.getX() + px * w + ix * d);
                int z = (int) Math.round(at.getZ() + pz * w + iz * d);
                int top = surfaceY(world, x, z);
                for (int y = floorY; y <= top + 2; y++) {
                    try {
                        Material m = world.getBlockState(new BlockPos(x, y, z)).getMaterial();
                        if (m.isSolid() || m.isLiquid()) out.add(BlockOp.mine(new BlockPos(x, y, z)));
                    } catch (Throwable ignored) {}
                }
            }
        }
    }

    /** Collect the ops of the interior CORRIDOR (the old instant levelBreachPath, hand-worked): the
     *  walkable ramp from the breach foot inward, floored + cleared column by column so the way into the
     *  base visibly emerges under the crew cutting it. */
    private void collectLevelBreachPathOps(World world, BlockPos bp, java.util.Collection<BlockOp> out) {
        double dist = Math.hypot(site.getX() - bp.getX(), site.getZ() - bp.getZ());
        if (dist < 1.0) return;
        double ux = (site.getX() - bp.getX()) / dist, uz = (site.getZ() - bp.getZ()) / dist;
        double px = -uz, pz = ux;
        int hw = laneHalf(), hh = laneHeight();
        int steps = (int) Math.min(dist, 40);
        int rampY = bp.getY();
        for (int s = 0; s <= steps; s++) {
            int baseX = (int) Math.round(bp.getX() + ux * s);
            int baseZ = (int) Math.round(bp.getZ() + uz * s);
            int grnd = terrainGroundY(world, baseX, baseZ);
            if (grnd > rampY + 1) rampY++;
            else if (grnd < rampY - 1) rampY--;
            else rampY = grnd;
            rampY = Math.min(rampY, bp.getY() + 5);
            for (int w = -hw; w <= hw; w++) {
                int x = (int) Math.round(baseX + px * w);
                int z = (int) Math.round(baseZ + pz * w);
                try {
                    BlockPos floor = new BlockPos(x, rampY - 1, z);
                    if (world.isAirBlock(floor) || world.getBlockState(floor).getMaterial().isLiquid())
                        out.add(BlockOp.place(floor, Blocks.COBBLESTONE.getDefaultState()));
                    for (int y = rampY; y <= rampY + hh; y++) {
                        Material m = world.getBlockState(new BlockPos(x, y, z)).getMaterial();
                        if (m.isSolid() || m.isLiquid()) out.add(BlockOp.mine(new BlockPos(x, y, z)));
                    }
                } catch (Throwable ignored) {}
            }
        }
    }

    /** Collect the ops of an ACCESS stair (the old instant buildAccessRamp, hand-worked): one connected
     *  3-wide staircase from the breach interior to a loot spot, plus the laddered column when the spot
     *  is still above the final grade. */
    private void collectAccessRampOps(World world, BlockPos spot, java.util.Collection<BlockOp> out) {
        BlockPos from = (interiorObjective != null) ? interiorObjective : breachCorridor;
        if (from == null || spot == null) return;
        double dx = spot.getX() - from.getX(), dz = spot.getZ() - from.getZ();
        double dist = Math.sqrt(dx * dx + dz * dz);
        if (dist < 2.0) return;
        double ux = dx / dist, uz = dz / dist;
        double px = -uz, pz = ux;
        int n = (int) Math.min(Math.ceil(dist), 80);
        int gradeY = from.getY();
        for (int s = 0; s <= n; s++) {
            int bx = (int) Math.round(from.getX() + ux * s);
            int bz = (int) Math.round(from.getZ() + uz * s);
            int grnd = terrainGroundY(world, bx, bz);
            if (grnd > gradeY + 1) gradeY++;
            else if (grnd < gradeY - 1) gradeY--;
            else gradeY = grnd;
            for (int w = -1; w <= 1; w++) {
                int x = bx + (int) Math.round(px * w);
                int z = bz + (int) Math.round(pz * w);
                try {
                    BlockPos tread = new BlockPos(x, gradeY - 1, z);
                    if (world.isAirBlock(tread) || world.getBlockState(tread).getMaterial().isLiquid())
                        out.add(BlockOp.place(tread, Blocks.COBBLESTONE.getDefaultState()));
                    for (int h = 0; h <= 2; h++) {
                        Material m = world.getBlockState(new BlockPos(x, gradeY + h, z)).getMaterial();
                        if (m.isSolid() || m.isLiquid()) out.add(BlockOp.mine(new BlockPos(x, gradeY + h, z)));
                    }
                } catch (Throwable ignored) {}
            }
        }
        if (spot.getY() > gradeY + 1) {
            for (int y = Math.min(gradeY, spot.getY()); y <= Math.max(gradeY, spot.getY()); y++)
                collectLadderRowOps(world, spot, y, out);
        }
    }

    /**
     * Perform ONE BlockOp against the live world. Returns true only when it actually changed something
     * (a real swing); stale ops -- the barrage already destroyed the block, a prior crew already laid the
     * deck -- return false and cost no time, which is what lets a sapper blast fast-forward the rest of
     * a breach task. Placement goes through setCampBlock (recorded, reverts); mining through the
     * antigrief-aware damageBlock; liquids clear via setCampBlock exactly like the old builders.
     */
    private boolean applyOp(World world, BlockOp op) {
        try {
            IBlockState cur = world.getBlockState(op.pos);
            if (op.place) {
                if (cur.getBlock() == op.state.getBlock()) return false;         // already built
                if (!world.isAirBlock(op.pos) && !cur.getMaterial().isLiquid()
                        && !cur.getMaterial().isReplaceable()) return false;      // something solid grew here
                setCampBlock(world, op.pos, op.state);
                return true;
            }
            if (world.isAirBlock(op.pos)) return false;                           // already gone
            if (EpochRunnerMod.scaffold != null && cur.getBlock() == EpochRunnerMod.scaffold) return false;
            if (cur.getMaterial().isLiquid()) { setCampBlock(world, op.pos, Blocks.AIR.getDefaultState()); return true; }
            damageBlock(world, op.pos);
            return true;
        } catch (Throwable t) {
            return false;
        }
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

            // COMBAT-HIJACK CHECK (logged once per crew): an engineer must be in engineerMode so it can
            // never release combat soldiers / chase the player. If this ever logs hijack=true, that's the
            // bug where engineers "turned into hostile mobs".
            if (!crew.hijackChecked) {
                crew.hijackChecked = true;
                EpochRunnerMod.logger.info("[EngDebug] crew#" + crew.id + " combatHijack="
                        + (!eng.isEngineerMode()) + " (engineerMode=" + eng.isEngineerMode() + ")");
            }

            // Sapper charge cooking: pull back from the blast, then resume.
            if (tickAge < crew.retreatUntil) {
                eng.setMoveTarget(outsidePoint(world, breachCorridor, 12.0), 0.11 + warLevel * 0.004);
                continue;
            }

            EngTask t = crew.current;
            if (t == null || t.done) {
                t = reserveNextTask(crew);
                if (t != null) {
                    crew.reservedAtTick = tickAge; crew.arrived = false;
                    crew.bestDistToStand = Double.MAX_VALUE; crew.noProgressTicks = 0;
                    crew.marchTicks = 0; crew.remoteLogged = false;
                    RouteNode wn = route.get(t.fromIdx);
                    EpochRunnerMod.logger.info("[EngDebug] crew#" + crew.id + " RESERVED " + t.work + "/"
                            + t.obstacle + " @ " + xyz(new BlockPos(wn.x, wn.gradeY, wn.z)) + "  dist="
                            + (int) eng.getDistance(wn.x, wn.gradeY, wn.z) + "  tool=" + toolLabel(t.work));
                }
            }
            if (t == null) { // queue drained: form up at the breach
                stepCarrierToward(eng, breachCorridor, 0.06 + warLevel * 0.004);
                continue;
            }

            // HUMAN-PACE BUILDING. The crew WALKS to its work FRONT -- the spot just behind the next block
            // to lay/mine -- and performs ONE BlockOp every ENG_WORK_INTERVAL ticks with an arm swing +
            // particles + sound. The front is always on already-worked ground, so the crew follows its own
            // bridge/corridor as it extends. Give-up is PROGRESS-based, not a fixed timer: a long honest
            // march (crews now start at the staging line) or a wait at the moat while another crew finishes
            // the causeway is fine; only a crew making zero progress with nothing pending that could open
            // its path falls back to remote-building (the never-stall guarantee, no longer a routine cheat).
            double engSpeed = 0.13 + warLevel * 0.006; // brisk work-pace shuffle (was a ~0.3x crawl)
            BlockPos stand = workStandPos(world, t);
            double dH = Math.hypot(eng.posX - (stand.getX() + 0.5), eng.posZ - (stand.getZ() + 0.5));
            boolean atFront = dH <= 3.5 && Math.abs(eng.posY - stand.getY()) <= 5.0;
            if (!atFront) {
                // Stride the open approach at a real pace, ease down only close to the work.
                double marchSpeed = (dH > 12.0) ? Math.max(engSpeed, 0.24) : engSpeed;
                stepCarrierToward(eng, stand, marchSpeed);
                if (!crew.arrived) {
                    crew.arrived = true;
                    EpochRunnerMod.logger.info("[EngDebug] crew#" + crew.id + " marching to work front "
                            + xyz(stand) + " -> " + toolLabel(t.work) + " on " + t.obstacle);
                }
                if (dH < crew.bestDistToStand - 0.5) { crew.bestDistToStand = dH; crew.noProgressTicks = 0; }
                else crew.noProgressTicks++;
                crew.marchTicks++;
                // Blocked mid-march? If an EARLIER unclaimed obstacle exists on the route, divert to it --
                // that's the work that opens this crew's own path (the breach crew grabs the moat bridge).
                if (crew.noProgressTicks == 120) {
                    EngTask earlier = null;
                    for (EngTask q : engQueue) {
                        if (q.done || q.claimedBy != null || q == t) continue;
                        if (q.fromIdx < t.fromIdx && (earlier == null || q.fromIdx < earlier.fromIdx)) earlier = q;
                    }
                    if (earlier != null) {
                        releaseTask(crew);
                        earlier.claimedBy = crew; crew.current = earlier;
                        crew.bestDistToStand = Double.MAX_VALUE; crew.noProgressTicks = 0; crew.marchTicks = 0;
                        EpochRunnerMod.logger.info("[EngDebug] crew#" + crew.id
                                + " blocked en route -> diverting to earlier " + toolLabel(earlier.work));
                        continue;
                    }
                }
                boolean earlierPending = false;
                for (EngTask q : engQueue) {
                    if (!q.done && q != t && q.fromIdx < t.fromIdx) { earlierPending = true; break; }
                }
                boolean giveUp = (crew.noProgressTicks >= ENG_NO_PROGRESS_GIVEUP && !earlierPending)
                        || crew.marchTicks >= ENG_MARCH_CAP;
                if (!giveUp) continue; // keep walking / waiting for the path to open
                if (!crew.remoteLogged) {
                    crew.remoteLogged = true;
                    EpochRunnerMod.logger.info("[EngDebug] crew#" + crew.id + " genuinely stuck ("
                            + crew.noProgressTicks + "t no progress) -> remote-building " + toolLabel(t.work));
                }
                // fall through: build remotely this tick (graceful degrade, never stalls the siege)
            } else {
                crew.noProgressTicks = 0;
                crew.marchTicks = 0;
                if (dH < crew.bestDistToStand) crew.bestDistToStand = dH;
            }
            // HUMAN PACE: one placed/mined BLOCK per interval.
            if (tickAge - crew.lastWorkTick < ENG_WORK_INTERVAL) continue;
            crew.lastWorkTick = tickAge;
            crew.workTicks++;
            doTaskWork(world, crew, t);    // exactly ONE block of work (FX anchored at that block)
        }

        tickEngineerEscorts(world);

        boolean allDone = true;
        for (EngTask t : engQueue) if (!t.done) { allDone = false; break; }
        engineersComplete = allDone;
        recomputeRouteStatus();
    }

    /**
     * ESCORT DUTY: each shield-wall carrier marches WITH an engineer crew and holds ~4 blocks on the
     * base side of it -- physically between the workers and the defender -- re-tasked as crews move,
     * finish, or die. With no crews left it falls back to guarding the breach mouth.
     */
    private void tickEngineerEscorts(World world) {
        // Escort duty is an ENGINEER-PHASE job: once the surge is on, the assault column takes the shield
        // walls (advanceAssaultColumn drives every non-engineer carrier) -- don't fight it for their orders.
        if (phase != P_ENGINEER) return;
        if (engEscorts.isEmpty() || tickAge % 20 != 0) return;
        engEscorts.removeIf(c -> c == null || c.isDead);
        List<EngCrew> live = new ArrayList<>();
        for (EngCrew c : engCrews) if (c.carrier != null && !c.carrier.isDead) live.add(c);
        for (int i = 0; i < engEscorts.size(); i++) {
            EntityFormationCarrier shield = engEscorts.get(i);
            BlockPos guard;
            if (!live.isEmpty()) {
                EntityFormationCarrier ward = live.get(i % live.size()).carrier;
                double ang = Math.atan2(site.getZ() - ward.posZ, site.getX() - ward.posX);
                guard = new BlockPos(ward.posX + Math.cos(ang) * 4.0, ward.posY, ward.posZ + Math.sin(ang) * 4.0);
            } else if (breachCorridor != null) {
                guard = outsidePoint(world, breachCorridor, 5.0);
            } else {
                continue;
            }
            shield.setBattleContext(activator, guard);
            stepCarrierToward(shield, guard, 0.09 + warLevel * 0.003);
        }
    }

    /** Where a crew STANDS to work its task: the wall foot for breach/ladder/widen; beside the column
     *  base for the triple ladder; for corridor/access it FOLLOWS its own advancing work front; otherwise
     *  the route node just BEHIND the one being built (always already-built ground, so the crew walks the
     *  route as it EXTENDS rather than pathing across the un-built gap). */
    private BlockPos workStandPos(World world, EngTask t) {
        if (route.isEmpty() || breachCorridor == null) return (breachCorridor != null) ? breachCorridor : site;
        if (t.work == EngWork.BREACH || t.work == EngWork.LADDER || t.work == EngWork.WIDEN)
            return outsidePoint(world, breachCorridor, 3.0);
        if (t.work == EngWork.TRIPLE_LADDER && t.shaftSpot != null) {   // stand beside the ladder column base
            int lowY = Math.min(t.shaftSpot.getY(), t.shaftBaseY);
            return new BlockPos(t.shaftSpot.getX() - 1, lowY, t.shaftSpot.getZ());
        }
        if (t.baked != null) { // CORRIDOR/ACCESS: chase the advancing work front (the op positions ARE the
            // walkable lane the crew is cutting -- do NOT re-derive ground here, terrainGroundY dives under
            // man-made floors and would put the stand outside the crew's vertical arrival band)
            return (t.lastOpPos != null) ? t.lastOpPos
                    : new BlockPos(route.get(t.fromIdx).x, route.get(t.fromIdx).gradeY, route.get(t.fromIdx).z);
        }
        int front = Math.min(t.fromIdx + Math.max(0, t.progress - 1), t.toIdx);
        int standIdx = Math.max(0, Math.min(front - 1, route.size() - 1));
        RouteNode wn = route.get(standIdx);
        return new BlockPos(wn.x, wn.gradeY, wn.z);
    }

    /** The block the crew is currently placing/mining (anchor for the work effects). */
    private BlockPos workFrontBlock(EngTask t) {
        if (t.lastOpPos != null) return t.lastOpPos;
        if (route.isEmpty() || breachCorridor == null) return (breachCorridor != null) ? breachCorridor : site;
        if (t.work == EngWork.BREACH || t.work == EngWork.LADDER || t.work == EngWork.WIDEN)
            return breachCorridor.up(1);
        if (t.work == EngWork.TRIPLE_LADDER && t.shaftSpot != null) {
            int lowY = Math.min(t.shaftSpot.getY(), t.shaftBaseY);
            int need = Math.max(1, Math.abs(t.shaftSpot.getY() - t.shaftBaseY) + 1);
            return new BlockPos(t.shaftSpot.getX(), lowY + Math.min(t.progress, need - 1), t.shaftSpot.getZ());
        }
        int front = Math.max(0, Math.min(t.fromIdx + t.progress, route.size() - 1));
        RouteNode wn = route.get(front);
        return new BlockPos(wn.x, wn.gradeY, wn.z);
    }

    /** Visual feedback for ONE block of engineer work: an arm swing + a look at the block + break/place
     *  particles + a stone clack AT the exact block just worked, so every single block reads as labour. */
    private void engWorkFx(World world, EntityFormationCarrier eng, EngTask t) {
        try {
            eng.swingArm(net.minecraft.util.EnumHand.MAIN_HAND);
            BlockPos f = workFrontBlock(t);
            boolean mining = t.lastOpMine;
            try {
                eng.getLookHelper().setLookPosition(f.getX() + 0.5, f.getY() + 0.5, f.getZ() + 0.5, 30F, 30F);
            } catch (Throwable ignored) {}
            if (world instanceof WorldServer) {
                WorldServer ws = (WorldServer) world;
                if (mining) {
                    ws.spawnParticle(EnumParticleTypes.BLOCK_CRACK, f.getX() + 0.5, f.getY() + 0.5, f.getZ() + 0.5,
                            8, 0.3, 0.3, 0.3, 0.0,
                            net.minecraft.block.Block.getStateId(Blocks.COBBLESTONE.getDefaultState()));
                } else {
                    ws.spawnParticle(EnumParticleTypes.CLOUD, f.getX() + 0.5, f.getY() + 0.5, f.getZ() + 0.5,
                            4, 0.2, 0.2, 0.2, 0.0);
                }
            }
            world.playSound(null, f, mining ? SoundEvents.BLOCK_STONE_HIT : SoundEvents.BLOCK_STONE_PLACE,
                    SoundCategory.BLOCKS, 0.7F, 0.9F + (tickAge % 5) * 0.04F);
        } catch (Throwable ignored) {}
    }

    /** Fill a task's op buffer with the BlockOps of work unit {@code unit}. */
    private void generateUnitOps(World world, EngTask t, int unit) {
        try {
            if (t.baked != null) {                                      // pre-baked: one op per unit
                if (unit < t.baked.size()) t.ops.add(t.baked.get(unit));
                return;
            }
            switch (t.work) {
                case BRIDGE: case RAMP: case CLEAR:
                    collectRouteNodeOps(world, route.get(Math.min(t.fromIdx + unit, t.toIdx)), t.ops);
                    break;
                case TUNNEL:
                    collectTunnelNodeOps(world, route.get(Math.min(t.fromIdx + unit, t.toIdx)), t.ops);
                    break;
                case BREACH:
                    collectBreachStepOps(world, route.get(t.fromIdx), unit / 2, t.ops);
                    break;
                case TRIPLE_LADDER:
                    if (t.shaftSpot != null) {
                        int lowY = Math.min(t.shaftSpot.getY(), t.shaftBaseY);
                        collectLadderRowOps(world, t.shaftSpot, lowY + unit, t.ops);
                    }
                    break;
                case LADDER: {
                    RouteNode wn = route.get(t.fromIdx);
                    net.minecraft.util.EnumFacing outward = net.minecraft.util.EnumFacing.getFacingFromVector(
                            wn.x - site.getX(), 0, wn.z - site.getZ());
                    BlockPos col = new BlockPos(wn.x, wn.gradeY, wn.z).offset(outward);
                    BlockPos p = new BlockPos(col.getX(), wn.gradeY + unit, col.getZ());
                    if (world.getBlockState(p.offset(outward.getOpposite())).getMaterial().isSolid()) {
                        t.ops.add(BlockOp.place(p, Blocks.LADDER.getDefaultState()
                                .withProperty(net.minecraft.block.BlockLadder.FACING, outward)));
                    }
                    break;
                }
                default:
                    break;
            }
        } catch (Throwable ignored) {}
    }

    /**
     * Perform exactly ONE real block of work on a task: refill the op buffer from the next work unit(s)
     * as needed, skip ops the world already resolved (barrage rubble, another crew's deck), and stop the
     * moment one block actually changes. Returns false when the task has no work left at all.
     */
    private boolean pumpOneOp(World world, EngTask t) {
        // The guard bounds a worst-case all-stale sweep; it must EXCEED any task's total op count
        // (the corridor is the biggest at ~1700) or a heavily-stale task would falsely complete.
        for (int guard = 0; guard < 4096; guard++) {
            BlockOp op = t.ops.poll();
            if (op == null) {
                if (t.progress >= taskNeeded(t)) return false;      // task fully exhausted
                generateUnitOps(world, t, t.progress);
                t.progress++;
                continue;
            }
            if (applyOp(world, op)) {
                t.lastOpPos = op.pos;
                t.lastOpMine = !op.place;
                return true;                                        // one visible block this swing
            }
        }
        return false; // pathological all-stale sweep: treat as no work left (guard against livelock)
    }

    /** Do ONE block of work on the crew's current task; when the task exhausts, complete + chain follow-ups. */
    private void doTaskWork(World world, EngCrew crew, EngTask t) {
        // OPS PER SWING (config-driven: WarLevelsConfig siege.mineSpeedMultiplier, default 4). Bulk terraform
        // (bridge/ramp/clear/widen/corridor/tunnel) lays SEVERAL blocks per swing so a wide crossing/corridor
        // finishes in reasonable time -- the "mine at a crawl" fix. The dramatic single-gap work (the breach
        // carve, ladder rungs) stays one-per-swing so it still reads.
        int bulkOps = Math.max(1, (int) Math.round(studio.ERM.war.config.WarLevelsConfig.mineSpeedMultiplier()));
        int opsPerSwing = (t.work == EngWork.BREACH || t.work == EngWork.LADDER
                || t.work == EngWork.TRIPLE_LADDER) ? 1 : bulkOps;
        boolean exhausted = false;
        for (int i = 0; i < opsPerSwing; i++) {
            if (!pumpOneOp(world, t)) { exhausted = true; break; } // no ops left -> task is complete
        }
        // Still has ops left? -> a working swing (FX + the high-tech sapper charge), then return.
        if (!exhausted) {
            engWorkFx(world, crew.carrier, t);
            // High tech: once the hand-mining has bitten a few slices into the wall, plant a REAL charge --
            // an explosion removing blocks instantly is physics, not a cheat; the rest of the breach ops
            // re-validate as already-destroyed and fast-forward.
            if (t.work == EngWork.BREACH && warLevel >= 8 && !t.sapperFired && t.progress >= 6) {
                t.sapperFired = true;
                plantSapperCharge(world, breachCorridor);
                crew.retreatUntil = tickAge + 36;
            }
            return;
        }
        t.done = true;
        EpochRunnerMod.logger.info("[EngDebug] crew#" + crew.id + " COMPLETED " + t.work + "/" + t.obstacle);
        if (t.work == EngWork.BREACH) {
            // The wall has a walk-through gap. The WIDENING of the opening and the interior CORRIDOR are
            // now QUEUED WORK the crews perform block-by-block -- not an instant terraform on completion.
            breachOpened = true;
            enqueueBreachFollowups(world);
        } else if (t.work == EngWork.TUNNEL) {          // this squad's staircase reached its heat spot
            BlockPos spot = crewHeatSpot.get(crew);
            if (spot != null) {
                completedHeatSpots.add(spot);
                interiorObjective = spot;              // the push now has an interior goal to flow to
                EpochRunnerMod.logger.info("[Siege] PHASE2 crew#" + crew.id
                        + " tunnel broke through to heat spot " + xyz(spot));
            }
        } else if (t.work == EngWork.TRIPLE_LADDER && t.shaftSpot != null) { // the triple-ladder climb is built
            EpochRunnerMod.logger.info("[Siege] PHASE2 crew#" + crew.id
                    + " built the triple-ladder climb to loot " + xyz(t.shaftSpot));
        }
        releaseTask(crew);
    }

    /** Queue the post-breach jobs as HAND-WORKED tasks: widen the gap (old openGroundBreach) and cut the
     *  walkable corridor into the base (old levelBreachPath). Crews pull them by priority right after the
     *  wall falls, so the opening visibly grows and the way in visibly emerges under their hands. */
    private void enqueueBreachFollowups(World world) {
        if (breachCorridor == null) return;
        java.util.List<BlockOp> widen = new ArrayList<>();
        collectOpenGroundBreachOps(world, breachCorridor, widen);
        EngTask wt = addBakedTask(EngWork.WIDEN, Obstacle.WALL, breachCorridor, widen, 95);
        java.util.List<BlockOp> corridor = new ArrayList<>();
        collectLevelBreachPathOps(world, breachCorridor, corridor);
        EngTask ct = addBakedTask(EngWork.CORRIDOR, Obstacle.WALL, breachCorridor, corridor, 85);
        engQueue.sort((a, b) -> b.priority - a.priority);
        recomputeRouteStatus();
        EpochRunnerMod.logger.info("[Siege] breach follow-up work queued: widen="
                + (wt != null ? wt.baked.size() : 0) + " ops, corridor="
                + (ct != null ? ct.baked.size() : 0) + " ops");
    }

    /** Enqueue a pre-baked op-list task anchored at {@code anchor} (gets its own route anchor node so the
     *  reserve/stand machinery works on it). Returns null when there is nothing to do. */
    private EngTask addBakedTask(EngWork work, Obstacle obstacle, BlockPos anchor, java.util.List<BlockOp> ops, int priority) {
        if (ops == null || ops.isEmpty()) return null;
        int idx = route.size();
        route.add(new RouteNode(anchor.getX(), anchor.getZ(), anchor.getY(), obstacle));
        EngTask t = new EngTask(work, obstacle, idx, idx, priority);
        t.baked = ops;
        engQueue.add(t);
        return t;
    }

    /** Reserve the best unclaimed task for a crew: highest priority, then nearest to the crew. */
    /**
     * C10/C12: push the shared {@link RouteStatus} + the army-side breach-hold point to the director's
     * ground vehicles (so tanks WAIT for the breach instead of bulldozing an un-breached wall), and poll
     * them for a stuck report (so a crew diverts to clear whatever is in front). Throttled; one-way
     * (director -> pilot), so there is no back-reference to clean up when the siege ends.
     */
    private void tickVehicleRouteSignals(World world) {
        if (breachCorridor == null || vehicles.isEmpty()) return;
        // Hold on the ARMY side of the FIRST unfinished piece of EXTERNAL route work (an unfinished
        // bridge / ramp / head-clear / the breach itself) -- "tanks wait while the engineers finish the
        // crossing" -- instead of blindly at the wall foot, which could be ACROSS the very moat the crews
        // are still bridging. Falls back to just outside the breach when no external work is pending.
        BlockPos hold = outsidePoint(world, breachCorridor, 8.0);
        int firstIdx = Integer.MAX_VALUE;
        for (EngTask t : engQueue) {
            if (t.done) continue;
            if (t.work != EngWork.BRIDGE && t.work != EngWork.RAMP
                    && t.work != EngWork.CLEAR && t.work != EngWork.BREACH) continue;
            if (t.fromIdx < firstIdx) firstIdx = t.fromIdx;
        }
        if (firstIdx != Integer.MAX_VALUE && !route.isEmpty()) {
            RouteNode n = route.get(Math.max(0, Math.min(route.size() - 1, firstIdx - 6)));
            hold = new BlockPos(n.x, n.gradeY, n.z);
        }
        boolean push = (tickAge % 10 == 0);
        for (EntityAIPilot v : vehicles) {
            if (v == null || v.isDead) continue;
            if (push) { v.setDirectorRouteStatus(routeStatus); v.setBreachHold(hold); }
            if (v.isStuckBlocked()) { reportBlocked(v.getBlockedPos()); v.clearStuckBlocked(); }
        }
    }

    /**
     * C12: a vehicle reported it cannot get past something. Bump the priority of the nearest UNCLAIMED
     * engineer task so the next free crew reserves it and VISIBLY diverts to clear that exact spot -- the
     * "what prevents my army from reaching the objective?" loop made concrete. Only ever RAISES a priority
     * (capped below the breach's 100), so it can never starve the breach itself.
     */
    private void reportBlocked(BlockPos pos) {
        if (pos == null || engQueue.isEmpty()) return;
        EngTask nearest = null; double bd = Double.MAX_VALUE;
        for (EngTask t : engQueue) {
            if (t.done || t.claimedBy != null) continue; // a claimed task is already being worked
            BlockPos wp = taskMidPoint(t);
            if (wp == null) continue;
            double d = wp.distanceSq(pos);
            if (d < bd) { bd = d; nearest = t; }
        }
        if (nearest != null && nearest.priority < 95) {
            nearest.priority = Math.min(95, nearest.priority + 30);
            EpochRunnerMod.logger.info("[Siege] vehicle blocked near " + xyz(pos) + " -> bumped "
                    + toolLabel(nearest.work) + " task priority to " + nearest.priority);
        }
    }

    /** Mid-span world position of an engineer task (for nearest-task lookups). */
    private BlockPos taskMidPoint(EngTask t) {
        if (t == null || route.isEmpty()) return null;
        int mid = Math.max(0, Math.min(route.size() - 1, (t.fromIdx + t.toIdx) / 2));
        RouteNode n = route.get(mid);
        return new BlockPos(n.x, n.gradeY, n.z);
    }

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

    /** Where a crew stands to work a task: just OUTSIDE the NEAR edge of the obstacle (fromIdx), on the
     *  army side. It must be a spot the crew can actually stand on -- the old midpoint anchor for a water
     *  segment sat IN the moat, which the carrier avoids (water path-priority -1), so it never "arrived"
     *  and never built. The director places the blocks across the span regardless of the exact stance. */
    private BlockPos workPos(World world, EngTask t) {
        RouteNode wn = route.get(t.fromIdx);
        return outsidePoint(world, new BlockPos(wn.x, wn.gradeY, wn.z), 2.0);
    }

    /** Sapper charge: the dramatic blast EFFECT + the antigrief-aware ground breach -- but NO real
     *  damaging TNT entity. A live EntityTNTPrimed here cratered the engineers' own escort and the
     *  nearby line (a major friendly-fire source); openGroundBreach already opens the gap, repairably. */
    private void plantSapperCharge(World world, BlockPos at) {
        try {
            world.playSound(null, at, SoundEvents.ENTITY_CREEPER_PRIMED, SoundCategory.HOSTILE, 1.2F, 0.8F);
        } catch (Throwable ignored) {}
        explosionEffect(world, at);
        openGroundBreach(world, at);
    }

    // ════════════════════════════════════════════════════════════
    //  PHASE 4 — SURGE
    // ════════════════════════════════════════════════════════════

    private void beginSurge(World world) {
        phase = P_SURGE;
        lastPhaseChangeTick = tickAge;
        // FIX (vehicle freeze): the surge means "the breach is open enough -- GO". Force the route CLEARED so
        // any tank still holding at the breach foot (C11) rolls in, even if a shaft/task lingered unfinished.
        // surgeStarted keeps recomputeRouteStatus pinned CLEARED even though the crews now keep ticking
        // (and finishing their remaining work visually) through the surge and assault.
        surgeStarted = true;
        routeStatus = RouteStatus.CLEARED;
        // NEVER-STALL FAILSAFE: if the crews never fully opened the wall (phase timer beat them, or they
        // all died), the sustained barrage finally collapses it NOW -- one dramatic blast, then the assault
        // always has its opening. This replaces the old silent instant openGroundBreach on task completion.
        if (!breachOpened && breachCorridor != null) {
            explosionEffect(world, breachCorridor.up(1));
            openGroundBreach(world, breachCorridor);
            levelBreachPath(world, breachCorridor);
            breachOpened = true;
            EpochRunnerMod.logger.info("[Siege] surge: wall was not fully breached in time -> barrage collapse failsafe");
        }
        EpochRunnerMod.logger.info("[Siege] -> SURGE: invasion through the breach (warLevel=" + warLevel + ")");

        int surgeWaves = (warLevel >= 8) ? 3 : (warLevel >= 5) ? 2 : 1;
        boolean airLaunched = AirStrikeController.launchHostileSurge(world, site, warLevel, surgeWaves);
        EpochRunnerMod.logger.info("[Siege] surge airstrike requested (" + surgeWaves + " waves) -> launched=" + airLaunched);

        // RELEASE AT THE BREACH (reachable, ground level), NOT a deep interior point. Aiming carriers at
        // a point inside the castle (walled off by buildings) meant they never reached release range and
        // never spawned a soldier -- the army just stood at the ramp foot. Releasing at the breach lands
        // the soldiers right there; they have full pathfinding + a home pos, so THEY climb the ramp and
        // press the defender. interiorObjective (a short way up the ramp) is just the movement goal.
        double toCore = Math.atan2(site.getZ() - breachCorridor.getZ(), site.getX() - breachCorridor.getX());
        int ix = breachCorridor.getX() + (int) Math.round(Math.cos(toCore) * 8);
        int iz = breachCorridor.getZ() + (int) Math.round(Math.sin(toCore) * 8);
        interiorObjective = new BlockPos(ix, terrainGroundY(world, ix, iz), iz);

        // If PHASE-2 tunnels broke through, send the army to the highest-value dug heat spot via the
        // tunnels, not just the breach mouth. completedHeatSpots.get(0) is the top-ranked chunk
        // (enumerateHeatSpots ranked them), so it is the priority objective. The actual interior push is
        // driven by interiorObjective through advanceThroughBreach each P_SURGE/P_ASSAULT tick.
        if (!completedHeatSpots.isEmpty()) {
            interiorObjective = completedHeatSpots.get(0);
            EpochRunnerMod.logger.info("[Siege] surge pushing through tunnels to heat spot "
                    + xyz(interiorObjective));
        }

        // Set up the interior objectives NOW (at the surge), so the troops start streaming inside toward the
        // SPREAD of green markers during the surge -- by the time the interior-assault phase starts they are
        // already well inside, not milling at the breach.
        setupInteriorObjectives(world);

        // Build the ONE assault corridor and start marching the army along it node-by-node (each formation +
        // its soldiers advance together as a column) instead of each unit trying to path to a far objective
        // through walls/water. The interior spread + looting takes over in the interior-assault phase.
        buildAssaultPath(world);
        advanceAssaultColumn(world);

        spawnCavalryCharge(world);

        // KSK fast-rope insertion into the interior as the assault goes in (heavier transport at higher levels).
        // SKY doctrine: a floating platform can't be stormed from the ground, so fast-rope troops ONTO the
        // platform TOP at ANY tech level -- helis are the only way up. Other base types keep the L6+ insertion.
        boolean skyInsert = doctrine() == BaseType.SKY;
        if (warLevel >= 6 || skyInsert) {
            BlockPos dz = skyInsert ? site : ((interiorObjective != null) ? interiorObjective : breachCorridor);
            String heli = (warLevel >= 9) ? "chinook" : (warLevel >= 7) ? "blackhawk" : "littlebird";
            AirStrikeController.launchInsertion(world, dz, warLevel, heli);
            EpochRunnerMod.logger.info("[Siege] fast-rope insertion " + heli + " -> " + xyz(dz)
                    + (skyInsert ? " (SKY platform)" : ""));
        }

        // A fresh armoured push commits with the line.
        if (warLevel >= 6) spawnArmourColumn(world, ENCIRCLE_RING - 2.0); // from the staging zone, not the wall
    }

    /**
     * CAVALRY CHARGE. The carrier -> contact-slice -> mount path NEVER produced a single horseman (the
     * carriers stalled before release range, so they never released and never mounted). Spawn the mounted
     * knights DIRECTLY instead: each "soldier:cavalry" payload is an EntitySoldier mounted on a horse
     * (SpawnHelper.spawnCavalryMount), aggroed on the defender and homed at the interior objective, so a
     * real wave of horsemen appears at the wall and charges through the breach. Guaranteed to show.
     */
    private void spawnCavalryCharge(World world) {
        int cavCount = (warLevel >= 7) ? 8 : 5;
        java.util.UUID tgt = (activator != null) ? activator.getUniqueID() : null;
        BlockPos home = (interiorObjective != null) ? interiorObjective : breachCorridor;
        int spawned = 0;
        for (int i = 0; i < cavCount; i++) {
            double lateral = (i - (cavCount - 1) / 2.0) * 3.0;
            // Spawn at the STAGING ZONE (where the army formed up), NOT at the wall -- nothing teleports in
            // at the breach. They then CHARGE in from staging toward the interior objective.
            BlockPos at = frontPoint(world, ENCIRCLE_RING - 4.0, lateral);
            try {
                net.minecraft.entity.Entity e = studio.ERM.war.BattleManagers.core.SpawnHelper.spawnPayload(
                        world, at, "soldier:cavalry", tgt, home, warLevel, "CAVALRY", "");
                if (e != null) {
                    spawned++;
                    looseUnits.add(e);                              // the rider
                    if (e.getRidingEntity() != null) looseUnits.add(e.getRidingEntity()); // its horse
                }
            } catch (Throwable t) {
                EpochRunnerMod.logger.warn("[Siege] cavalry spawn failed: " + t.getMessage());
            }
        }
        EpochRunnerMod.logger.info("[Siege] CAVALRY CHARGE: spawned " + spawned + "/" + cavCount + " mounted knights");
    }

    /** INVASION movement: drive every non-engineer assault carrier THROUGH the breach the engineers
     *  opened, then up the ramp into the core. Outside the wall they head for the breach gap; once past
     *  it they push to the core, releasing their contact-slice soldiers INSIDE the base. */
    private void advanceThroughBreach(World world, double speed) {
        if (breachCorridor == null) { advanceLine(world, WALL_RING, speed); return; }
        BlockPos goal = (interiorObjective != null) ? interiorObjective : breachCorridor;
        // Spread axis along the wall, so once through the breach the formations FAN OUT into the base to
        // different lanes instead of all stacking at one doorway (crude version of "seek different
        // hotspots" -- each carrier's own lateral slot becomes its interior lane).
        double ang = Math.atan2(breachCorridor.getZ() - site.getZ(), breachCorridor.getX() - site.getX());
        double px = -Math.sin(ang), pz = Math.cos(ang);
        for (LineUnit u : frontLine) {
            EntityFormationCarrier c = u.carrier;
            if (c == null || c.isDead || c.isEngineerMode()) continue;
            double dBreach = c.getDistance(breachCorridor.getX(), breachCorridor.getY(), breachCorridor.getZ());
            if (dBreach > 6) { c.setMoveTarget(breachCorridor, speed); continue; } // funnel to the gap
            BlockPos lane = new BlockPos(                                            // then fan out inside
                    goal.getX() + (int) Math.round(px * u.lateral * 0.5),
                    goal.getY(),
                    goal.getZ() + (int) Math.round(pz * u.lateral * 0.5));
            c.setMoveTarget(lane, speed);
        }
    }

    // ════════════════════════════════════════════════════════════
    //  PHASE 5 — INTERIOR ASSAULT
    // ════════════════════════════════════════════════════════════

    private void beginInteriorAssault(World world) {
        phase = P_ASSAULT;
        lastPhaseChangeTick = tickAge;
        EpochRunnerMod.logger.info("[Siege] -> INTERIOR ASSAULT / LOOTING (warLevel=" + warLevel + ")");

        // Objectives were set up at the SURGE (so the troops have already been streaming inside). Make sure
        // they exist (in case the surge was cut short), but DON'T re-scan -- that would reset capture progress.
        setupInteriorObjectives(world);

        // LATE LADDER FAILSAFE: any still-uncaptured loot that sits well above/below the entry level gets
        // its triple-ladder column ensured NOW -- idempotent over a hand-built climb (no blink when the
        // crews already finished it), and it only fills what they didn't. Reachability is never lost.
        int entryY = (interiorObjective != null) ? interiorObjective.getY()
                : (breachCorridor != null) ? breachCorridor.getY() : site.getY();
        for (BlockPos spot : heatspots) {
            if (capturedSpots.contains(spot)) continue;
            if (Math.abs(spot.getY() - entryY) > 2) raiseLadderColumn(world, spot, entryY);
        }

        // Top up the storming force and make sure every formation + soldier is directed at an objective.
        int forced = 0;
        for (EntityFormationCarrier c : carriers) {
            if (c == null || c.isDead || c.isEngineerMode()) continue;
            forced += c.releaseContactSlice(3);
        }
        driveFormationsToObjectives(world);
        driveSoldiersToObjectives(world);
        publishAssaultDebug(world);
        EpochRunnerMod.logger.info("[Siege] INTERIOR ASSAULT: " + heatspots.size() + " objectives, depot @ "
                + (lootDepot != null ? xyz(lootDepot) : "?") + "; committed " + forced + " more troops");
    }

    /**
     * Set up the interior objectives ONCE: scan the heat map for the spread of rooms to take, build the
     * loot depot down at the camp, cut a connected access stair to each objective, and publish the debug
     * overlay. Guarded (returns if objectives already exist) so calling it surge->assault never resets
     * capture progress.
     */
    /**
     * C14: scan the interior heat objectives ONCE (idempotent). Split out of {@link #setupInteriorObjectives}
     * so the scan can be pulled EARLIER (engineer phase, C15) -- giving the crews the real elevated loot to
     * dig shafts to -- WITHOUT also building the depot/ramps, which stay at the surge.
     */
    private void ensureHeatspotsScanned(World world) {
        if (heatspotsScanned) return;
        heatspotsScanned = true;
        scanHeatspots(world);
        addAccessObjectives(world); // doctrine: hold the exits (underground) / take the docks (ocean) too
    }

    private void setupInteriorObjectives(World world) {
        ensureHeatspotsScanned(world);   // C14: no-op if the scan was already pulled forward (C15)
        if (interiorBuilt) return;       // C13: build the pads/graph/debug ONCE, decoupled from "have objectives"
        interiorBuilt = true;
        buildLootDepot(world);
        // ACCESS stairs are now HAND-CUT: instead of a staircase to EVERY heatspot blinking in across the
        // whole base at the surge (up to 64 instant carve-ups), queue paced ACCESS tasks to the TOP few
        // objectives -- the crews cut them block-by-block while the assault flows (they keep ticking through
        // the surge/assault now). Reachability of everything else is still guaranteed by the tunnels, the
        // triple-ladder tasks, the assault-begin ladder failsafe, and ultimately the loot timeout.
        int accessTasks = 0;
        for (int i = 0; i < heatspots.size() && accessTasks < 4; i++) {
            BlockPos spot = heatspots.get(i);
            java.util.List<BlockOp> ops = new ArrayList<>();
            collectAccessRampOps(world, spot, ops);
            if (!ops.isEmpty() && addBakedTask(EngWork.ACCESS, Obstacle.CLIFF, spot, ops, 45 - i) != null)
                accessTasks++;
        }
        if (accessTasks > 0) {
            engQueue.sort((a, b) -> b.priority - a.priority);
            EpochRunnerMod.logger.info("[Siege] queued " + accessTasks + " hand-cut ACCESS stair task(s)");
        }
        buildObjectiveGraph(); // typed shadow of the final objective set (read-only; nothing decides off it yet)
        publishAssaultDebug(world);
        EpochRunnerMod.logger.info("[Siege] interior objectives set: " + heatspots.size() + " heatspots, depot @ "
                + (lootDepot != null ? xyz(lootDepot) : "?") + " with " + lootChests.size() + " chests");
    }

    /**
     * Build a typed SHADOW of the interior objectives: the breach as ROOT + one node per heatspot, ranked
     * by the existing nearest-core scan order (earlier in the list = higher value = higher priority),
     * tagged {@code vertical} when the loot sits above the breach foot (the same test
     * the access-ramp collector uses), with {@code captured} mirrored from {@code capturedSpots}. Pure
     * read-only projection -- decisions still run off the heatspot/capturedSpots lists, so behavior is
     * byte-identical; this is the substrate {@link SquadAllocation} ranks over (C9).
     */
    private void buildObjectiveGraph() {
        objectiveGraph.clear();
        SiegeObjective root = (breachCorridor != null) ? SiegeObjective.breachRoot(breachCorridor) : null;
        if (root != null) objectiveGraph.add(root);
        int n = heatspots.size();
        int baseY = (breachCorridor != null) ? breachCorridor.getY() : (site != null ? site.getY() : 0);
        BlockPos from = (breachCorridor != null) ? breachCorridor : (routeStart != null ? routeStart : site);
        for (int i = 0; i < n; i++) {
            BlockPos spot = heatspots.get(i);
            int priority = n - i;                                  // nearest-core scan order = value rank
            int pathCost = (from != null) ? (int) Math.sqrt(from.distanceSq(spot)) : 0;
            boolean vertical = spot.getY() > baseY + 1;            // loot above the breach foot needs a climb
            SiegeObjective node = new SiegeObjective(spot, priority, pathCost, vertical, false);
            node.captured = capturedSpots.contains(spot);
            if (root != null) { root.neighbors.add(node); node.neighbors.add(root); }
            objectiveGraph.add(node);
        }
    }

    /** Keep the typed shadow in sync when a heatspot is looted (mirror capturedSpots). */
    private void markObjectiveCaptured(BlockPos spot) {
        for (SiegeObjective o : objectiveGraph)
            if (o != null && !o.root && o.pos.equals(spot)) { o.captured = true; return; }
    }

    /** Read-only view of the typed objective graph (breach root + interior heat nodes). */
    public List<SiegeObjective> getObjectiveGraph() { return objectiveGraph; }

    // How close (squared) a carrier must be to the breach corridor to count as "at the breach mouth".
    private static final double BREACH_MOUTH_SQ = 12.0 * 12.0;

    /**
     * Classify what a carrier is doing right now into a named {@link FireSector} -- a pure LABEL over the
     * CURRENT behavior (no firing change here; C6/C7 act on the labels). Engineer-mode crews hold fire
     * (they never fight); bombardLine carriers suppress from their standoff slot; carriers at the breach
     * mouth are clearing it; the rest of the surge/assault advances and fires; otherwise free fire =
     * today's default. Mapping current behavior onto the enum first keeps the later behavior changes small.
     */
    private FireSector sectorFor(EntityFormationCarrier c) {
        if (c == null) return FireSector.FREE_FIRE;
        if (c.isEngineerMode()) return FireSector.HOLD_FIRE;            // engineers focus on the work
        if (bombardLine.contains(c)) return FireSector.SUPPRESS_FROM_SLOT;
        if (breachCorridor != null && c.getDistanceSq(breachCorridor) <= BREACH_MOUTH_SQ)
            return FireSector.BREACH_CLEARING;
        if (phase == P_SURGE || phase == P_ASSAULT) return FireSector.ADVANCE_AND_FIRE;
        return FireSector.FREE_FIRE;
    }

    /**
     * DOCTRINE OBJECTIVES from the analyzer's access nodes: the army should also SEIZE the base's real
     * access points, not just the loot. UNDERGROUND -> hold the entrances/shafts ("hold the exits");
     * OCEAN -> take the docks/causeways; SKY -> seize the shafts/hatches up to the platform. These join
     * the heatspot list so formations/soldiers march to them and the assault secures the way in.
     */
    private void addAccessObjectives(World world) {
        if (baseAnalysis == null || baseAnalysis.accessNodes.isEmpty()) return;
        BaseType t = doctrine();
        int added = 0;
        for (AccessNode n : baseAnalysis.accessNodes) {
            if (n == null || n.pos == null) continue;
            boolean want;
            switch (t) {
                case OCEAN:       want = (n.type == AccessType.DOCK || n.type == AccessType.BRIDGE); break;
                case UNDERGROUND: want = (n.type == AccessType.DOOR || n.type == AccessType.GATE
                                          || n.type == AccessType.LADDER_SHAFT || n.type == AccessType.TRAPDOOR); break;
                case SKY:         want = (n.type == AccessType.LADDER_SHAFT || n.type == AccessType.TRAPDOOR); break;
                default:          want = false; break; // SURFACE: the breach + heatspots are enough
            }
            if (!want) continue;
            BlockPos p = n.pos;
            boolean dup = false;
            for (BlockPos h : heatspots) if (h.distanceSq(p) < 64) { dup = true; break; } // within 8 blocks
            if (!dup && heatspots.size() < 16) { heatspots.add(p); added++; }
        }
        if (added > 0)
            EpochRunnerMod.logger.info("[Siege] doctrine added " + added + " access objective(s) for " + t);
    }

    /**
     * Build the interior OBJECTIVE list -- the heat map is the common language. Objectives come from:
     *   1) the base-cluster CHUNKS ranked by interior value (storage/machine/living/power) -- guaranteed
     *      spread across the whole base, one per chunk (this is what gives several distinct green markers
     *      instead of the single one everyone was standing around);
     *   2) the actual valuable TILE ENTITIES + beds in the loaded chunks around the core.
     * Candidates are then CLUSTERED (deduped within ~8 blocks) so we get a handful of distinct objectives
     * spread through the base -- not one, not fifty. Nearest-core first.
     */
    private void scanHeatspots(World world) {
        heatspots.clear();
        capturedSpots.clear();
        // EVERY IInventory is a target now (the player asked: all inventories are loot). Protected containers
        // are the PRIORITY heatspots (the player marked them "mine"); then every other distinct inventory; then
        // heat-map chunk centres to fill where no TE is loaded. Dedup only TOUCHING blocks so a double-chest
        // counts once but distinct chests EACH become their own objective.
        java.util.List<BlockPos> protectedCand = new ArrayList<>();
        java.util.List<BlockPos> invCand = new ArrayList<>();
        java.util.List<BlockPos> heatCand = new ArrayList<>();
        int invScanned = 0;

        // 0) PROTECTED containers = priority (range-independent global markers).
        try {
            for (BlockPos p : studio.ERM.handlers.ProtectionHandler.protectedPositionsNear(world, site, 96)) {
                if (p == null) continue;
                net.minecraft.tileentity.TileEntity te = world.getTileEntity(p);
                if (isLootContainer(te)) protectedCand.add(p.toImmutable());
            }
        } catch (Throwable ignored) {}

        // 1) HEAT MAP rooms (spread, one objective per high-value chunk) -- fill, lowest priority.
        if (baseCluster != null && !baseCluster.isEmpty()) {
            java.util.List<StrategicChunk> ranked = new ArrayList<>(baseCluster);
            ranked.sort((a, b) -> Double.compare(
                    (b.storageHeat + b.machineHeat + b.livingHeat + b.powerHeat),
                    (a.storageHeat + a.machineHeat + a.livingHeat + a.powerHeat)));
            int max = (warLevel >= 6) ? 10 : 7;
            for (StrategicChunk c : ranked) {
                if (heatCand.size() >= max) break;
                int wx = (c.chunkX << 4) + 8, wz = (c.chunkZ << 4) + 8;
                heatCand.add(new BlockPos(wx, terrainGroundY(world, wx, wz), wz));
            }
        }

        // 2) EVERY valuable TILE ENTITY (chest/machine/item-handler) + beds in the loaded chunks around core.
        int ccx = site.getX() >> 4, ccz = site.getZ() >> 4, r = 6;
        for (int dx = -r; dx <= r; dx++) {
            for (int dz = -r; dz <= r; dz++) {
                net.minecraft.world.chunk.Chunk chunk = world.getChunkProvider().getLoadedChunk(ccx + dx, ccz + dz);
                if (chunk == null) continue;
                for (Map.Entry<BlockPos, net.minecraft.tileentity.TileEntity> e
                        : new ArrayList<>(chunk.getTileEntityMap().entrySet())) {
                    net.minecraft.tileentity.TileEntity te = e.getValue();
                    if (te == null) continue;
                    BlockPos p = e.getKey().toImmutable();
                    boolean valuable = isLootContainer(te);
                    try {
                        if (!valuable && world.getBlockState(p).getBlock() instanceof net.minecraft.block.BlockBed) valuable = true;
                    } catch (Throwable ignored) {}
                    if (valuable) { invCand.add(p); invScanned++; }
                }
            }
        }

        // 3) Assemble in PRIORITY order (protected -> every inventory nearest-core -> heat rooms), dedup TOUCHING
        //    only (within ~2 blocks). Protected are added first so the cap never drops them.
        invCand.sort((a, b) -> Double.compare(a.distanceSq(site), b.distanceSq(site)));
        java.util.List<BlockPos> all = new ArrayList<>();
        all.addAll(protectedCand);
        all.addAll(invCand);
        all.addAll(heatCand);
        int cap = 64; // high enough to reach "every chest" on real bases without runaway block-building
        int dropped = 0;
        for (BlockPos p : all) {
            boolean near = false;
            for (BlockPos h : heatspots) if (h.distanceSq(p) < 4.0) { near = true; break; } // touching only (~2 blocks)
            if (near) continue;
            if (heatspots.size() >= cap) { dropped++; continue; }
            heatspots.add(p);
        }

        // Always leave at least one objective.
        if (heatspots.isEmpty()) {
            if (baseCore != null) {
                int wx = (baseCore.chunkX << 4) + 8, wz = (baseCore.chunkZ << 4) + 8;
                heatspots.add(new BlockPos(wx, terrainGroundY(world, wx, wz), wz));
            } else if (site != null) {
                heatspots.add(new BlockPos(site.getX(), terrainGroundY(world, site.getX(), site.getZ()), site.getZ()));
            }
        }
        EpochRunnerMod.logger.info("[Siege] heatspots: " + heatspots.size() + " objective(s) from "
                + protectedCand.size() + " protected + " + invScanned + " inventories scanned"
                + (dropped > 0 ? " (" + dropped + " over the " + cap + " cap)" : ""));
    }

    /** True if a tile entity is a lootable container (vanilla IInventory or a Forge item-handler/modded). */
    private boolean isLootContainer(net.minecraft.tileentity.TileEntity te) {
        return te != null && ((te instanceof net.minecraft.inventory.IInventory)
                || te.hasCapability(net.minecraftforge.items.CapabilityItemHandler.ITEM_HANDLER_CAPABILITY, null));
    }

    /**
     * Build the loot DEPOT at the deployment camp: a stone pad with a SPACED grid of single chests the
     * looted items teleport into (persists as the player's war reward). Chests are laid one block of air
     * apart -- NEVER face-adjacent -- because two touching vanilla chests auto-merge into a DOUBLE chest,
     * and the old tight row placed with no block update produced the "chest-in-chest / invisible chest"
     * glitch (a malformed half-rendered double-chest TE). Spacing + a real block update (flag 3) keeps
     * every chest a clean, openable single. Capacity scales with the haul so loot never overflows to the
     * ground (which would despawn it).
     */
    private void buildLootDepot(World world) {
        lootChests.clear();
        // Put the depot DOWN ON THE GROUND at the deployment camp (where the army staged), not floating in
        // the middle of the rubble -- the loot is teleported to camp, so the distance is irrelevant.
        BlockPos c = (stagingCenter != null) ? stagingCenter
                : (interiorObjective != null) ? interiorObjective : breachCorridor;
        int y = surfaceY(world, c.getX(), c.getZ());
        lootDepot = new BlockPos(c.getX(), y, c.getZ());

        int want = Math.max(6, Math.min(24, heatspots.size() + 2 + warLevel)); // scale to the haul
        final int cols = 4;
        int rows = (want + cols - 1) / cols;

        // Stone pad covering the whole spaced grid (+1 margin); headroom cleared antigrief-aware.
        for (int dx = -1; dx <= cols * 2; dx++) {
            for (int dz = -1; dz <= rows * 2; dz++) {
                BlockPos floor = new BlockPos(c.getX() + dx, y - 1, c.getZ() + dz);
                try { world.setBlockState(floor, Blocks.STONEBRICK.getDefaultState(), 2); } catch (Throwable ignored) {}
                for (int h = 0; h <= 2; h++) damageBlock(world, new BlockPos(c.getX() + dx, y + h, c.getZ() + dz));
            }
        }

        // Spaced SINGLE chests (2 apart so they can never merge), placed with flag 3 (block update + client)
        // so the TE initializes and renders; only tracked once we confirm a real chest TE actually exists.
        for (int i = 0; i < want; i++) {
            int gx = i % cols, gz = i / cols;
            BlockPos cp = new BlockPos(c.getX() + gx * 2, y, c.getZ() + gz * 2);
            try {
                world.setBlockState(cp, Blocks.CHEST.getDefaultState(), 3);
                if (world.getTileEntity(cp) instanceof net.minecraft.tileentity.TileEntityChest) lootChests.add(cp);
            } catch (Throwable ignored) {}
        }
        EpochRunnerMod.logger.info("[Siege] loot depot @ " + xyz(lootDepot) + " with " + lootChests.size()
                + " spaced chest(s) (cap " + want + ")");
    }

    /**
     * Raise a laddered cobblestone column in the loot {@code spot}'s own column from {@code fromY} up to the
     * spot's Y, so an elevated heatspot becomes climbable. Fills only AIR/liquid (never overwrites the loot
     * block, which is solid) and clings ladders to the +X face (the pillar is their west support). Reverts
     * with the camp via setCampBlock.
     */
    private void raiseLadderColumn(World world, BlockPos spot, int fromY) {
        int lowY = Math.min(fromY, spot.getY()), highY = Math.max(fromY, spot.getY());
        for (int y = lowY; y <= highY; y++) buildTripleLadderRow(world, spot, y);
    }

    /** One ROW of the TRIPLE ladder: three side-by-side pillar+ladder columns (offset along Z from the
     *  objective column) so up to THREE entities climb at once. Reuses {@link #placeShaftRung} for each. */
    private void buildTripleLadderRow(World world, BlockPos spot, int y) {
        for (int dz = -1; dz <= 1; dz++)
            placeShaftRung(world, new BlockPos(spot.getX(), spot.getY(), spot.getZ() + dz), y);
    }

    /**
     * Place ONE rung of a vertical access shaft at height {@code y} in the loot {@code spot}'s own column: a
     * cobblestone pillar block (only into AIR/liquid -- never the solid loot) + a ladder clinging to its +X
     * (east) face. Shared by the paced engineer SHAFT dig (C16, one rung per human-paced call) and the
     * {@link #raiseLadderColumn} surge failsafe. Idempotent via setCampBlock, so re-placing a dug rung is a
     * harmless no-op -- which is why the surge failsafe can run over a completed shaft without a visible blink.
     */
    private void placeShaftRung(World world, BlockPos spot, int y) {
        try {
            IBlockState ladder = Blocks.LADDER.getDefaultState()
                    .withProperty(net.minecraft.block.BlockLadder.FACING, net.minecraft.util.EnumFacing.EAST);
            BlockPos pillar = new BlockPos(spot.getX(), y, spot.getZ());
            if (world.isAirBlock(pillar) || world.getBlockState(pillar).getMaterial().isLiquid())
                setCampBlock(world, pillar, Blocks.COBBLESTONE.getDefaultState());
            setCampBlock(world, new BlockPos(spot.getX() + 1, y, spot.getZ()), ladder); // ladder clings to the pillar
        } catch (Throwable ignored) {}
    }

    /** Drive the assault troops room-to-room to the heatspots and LOOT each into the depot when a soldier
     *  actually REACHES it (or a staggered timeout fires, so a spot a squad can't path to still resolves). */
    private void tickLooting(World world, int tsp) {
        if (heatspots.isEmpty()) return;
        // THE DIRECTOR DIRECTS: keep driving each formation (carrier + puppet squad) to its objective, and
        // keep the released soldiers marching to objectives -- not chasing the player.
        driveFormationsToObjectives(world);
        driveSoldiersToObjectives(world);
        // Refresh the debug overlay every 5s so captured/uncaptured states update live.
        if (tickAge % 100 == 0) publishAssaultDebug(world);

        if (tickAge % 10 != 0) return; // throttle the capture scan
        deliverCouriers(world); // couriers that reached the depot drop their haul in

        for (BlockPos spot : new ArrayList<>(heatspots)) {
            if (capturedSpots.contains(spot)) continue;
            // PHYSICAL LOOT: a soldier that gets within ~2 blocks GRABS the chest's items and hauls them back to
            // the depot (becoming a courier). A staggered timeout still force-loots (teleport) so a spot a squad
            // can't path to (or climb to) never stalls the siege.
            boolean timedOut = tsp > 200 + heatspots.indexOf(spot) * 30;
            EntitySoldier grabber = (lootDepot != null) ? nearestFreeSoldierWithin(world, spot, 2.5) : null;
            if (grabber != null) {
                java.util.List<net.minecraft.item.ItemStack> haul = extractToHaul(world, spot);
                capturedSpots.add(spot);
                markObjectiveCaptured(spot);
                if (haul.isEmpty()) {
                    EpochRunnerMod.logger.info("[Siege] secured (empty) " + xyz(spot)
                            + " [" + capturedSpots.size() + "/" + heatspots.size() + "]");
                } else {
                    couriers.put(grabber, haul);
                    grabber.setMarchObjective(lootDepot); // turn around and carry the loot home
                    EpochRunnerMod.logger.info("[Siege] GRABBED " + xyz(spot) + " -> a soldier hauls "
                            + haul.size() + " stack(s) to the depot [" + capturedSpots.size() + "/"
                            + heatspots.size() + "]");
                }
            } else if (timedOut) {
                int moved = lootContainer(world, spot); // fallback: direct teleport so loot always resolves
                capturedSpots.add(spot);
                markObjectiveCaptured(spot);
                EpochRunnerMod.logger.info("[Siege] LOOT " + xyz(spot) + " (" + moved + " stacks -> depot, timeout) ["
                        + capturedSpots.size() + "/" + heatspots.size() + "]");
            }
        }
    }

    /** Nearest live empire soldier within {@code R} of {@code pos} that isn't already hauling loot. */
    private EntitySoldier nearestFreeSoldierWithin(World world, BlockPos pos, double R) {
        net.minecraft.util.math.AxisAlignedBB sb = new net.minecraft.util.math.AxisAlignedBB(
                pos.getX() - R, pos.getY() - R, pos.getZ() - R, pos.getX() + R, pos.getY() + R, pos.getZ() + R);
        EntitySoldier best = null; double bd = Double.MAX_VALUE;
        for (EntitySoldier s : world.getEntitiesWithinAABB(EntitySoldier.class, sb)) {
            if (s == null || s.isDead || couriers.containsKey(s)) continue;
            try { if (!"empire".equalsIgnoreCase(s.getTeam_())) continue; } catch (Throwable ignored) { continue; }
            double d = s.getDistanceSq(pos);
            if (d < bd) { bd = d; best = s; }
        }
        return best;
    }

    /** Pull a container's items into a carried haul list (emptying the source). Shared by the courier grab
     *  and the timeout {@link #lootContainer} fallback. */
    private java.util.List<net.minecraft.item.ItemStack> extractToHaul(World world, BlockPos pos) {
        java.util.List<net.minecraft.item.ItemStack> haul = new ArrayList<>();
        try {
            net.minecraft.tileentity.TileEntity te = world.getTileEntity(pos);
            if (te == null) return haul;
            net.minecraftforge.items.IItemHandler src = te.getCapability(
                    net.minecraftforge.items.CapabilityItemHandler.ITEM_HANDLER_CAPABILITY, null);
            if (src != null) {
                for (int i = 0; i < src.getSlots(); i++) {
                    net.minecraft.item.ItemStack got = src.extractItem(i, 64, false);
                    if (!got.isEmpty()) haul.add(got);
                }
            } else if (te instanceof net.minecraft.inventory.IInventory) {
                net.minecraft.inventory.IInventory inv = (net.minecraft.inventory.IInventory) te;
                for (int i = 0; i < inv.getSizeInventory(); i++) {
                    net.minecraft.item.ItemStack st = inv.getStackInSlot(i);
                    if (st != null && !st.isEmpty()) {
                        haul.add(st.copy());
                        inv.setInventorySlotContents(i, net.minecraft.item.ItemStack.EMPTY);
                    }
                }
                inv.markDirty();
            }
        } catch (Throwable ignored) {}
        return haul;
    }

    /** Deposit a courier's haul when it reaches the depot; also deposit (so loot is never lost) if it died. */
    private void deliverCouriers(World world) {
        if (couriers.isEmpty() || lootDepot == null) return;
        java.util.Iterator<java.util.Map.Entry<EntitySoldier, java.util.List<net.minecraft.item.ItemStack>>> it
                = couriers.entrySet().iterator();
        while (it.hasNext()) {
            java.util.Map.Entry<EntitySoldier, java.util.List<net.minecraft.item.ItemStack>> e = it.next();
            EntitySoldier s = e.getKey();
            boolean dead = (s == null || s.isDead);
            boolean home = (!dead && s.getDistanceSq(lootDepot) <= 9.0); // within 3 of the depot
            if (dead || home) {
                int n = e.getValue().size();
                for (net.minecraft.item.ItemStack st : e.getValue()) depositToDepot(world, st);
                it.remove();
                if (home) EpochRunnerMod.logger.info("[Siege] courier delivered " + n + " stack(s) to the depot");
            }
        }
    }

    /** Deposit every remaining courier's haul (siege end) so loot in transit is never lost. */
    private void flushCouriers(World world) {
        if (couriers.isEmpty()) return;
        for (java.util.List<net.minecraft.item.ItemStack> haul : couriers.values())
            for (net.minecraft.item.ItemStack st : haul) depositToDepot(world, st);
        couriers.clear();
    }

    /**
     * THE DIRECTOR DIRECTS. Assign each surviving combat FORMATION (carrier + its puppet squad) to an
     * interior objective and DRIVE IT there -- squads advancing as units toward the green markers, not
     * soldiers reverting to vanilla "chase the player" mob AI. The carrier's release point is set to its
     * objective so its contact slice commits AT the objective. Round-robins the formations across the
     * uncaptured objectives so they fan out across the base instead of all stacking on one spot.
     */
    /**
     * Assemble the ONE assault corridor the whole army follows: the engineer route (staging -> breach),
     * subsampled to ~10 walkable waypoints, then the breach mouth, the interior foothold, and the primary
     * objective. These are ENGINEER-BUILT walkable positions, so following them node-by-node never asks a unit
     * to path through a wall/water (which is what stalled the per-unit A*). Rebuilt once at the surge.
     */
    private void buildAssaultPath(World world) {
        assaultPath.clear();
        assaultFront = 0;
        assaultFrontSince = tickAge;
        assaultBreachIdx = -1;
        reformUntil = 0;
        // (a) approach: route up to the node nearest the breach, subsampled.
        int breachNode = route.size() - 1;
        if (breachCorridor != null) {
            double best = Double.MAX_VALUE;
            for (int i = 0; i < route.size(); i++) {
                RouteNode n = route.get(i);
                double d = breachCorridor.distanceSq(n.x, n.gradeY, n.z);
                if (d < best) { best = d; breachNode = i; }
            }
        }
        int stepEvery = Math.max(1, breachNode / 10);
        for (int i = 0; i <= breachNode && i < route.size(); i += stepEvery) {
            RouteNode n = route.get(i);
            assaultPath.add(new BlockPos(n.x, n.gradeY, n.z));
        }
        // (b) breach mouth -> interior foothold -> the primary objective (top heatspot).
        if (breachCorridor != null) {
            assaultPath.add(breachCorridor);
            assaultBreachIdx = assaultPath.size() - 1; // the reform-gate node: the wall gap itself
        }
        if (interiorObjective != null) assaultPath.add(interiorObjective);
        if (!heatspots.isEmpty()) assaultPath.add(heatspots.get(0));
        assaultPathBuilt = true;
        EpochRunnerMod.logger.info("[Siege] assault route: " + assaultPath.size()
                + " corridor node(s) rally->objective");
        publishAssaultRouteDebug(world);
    }

    /**
     * March the whole army along {@link #assaultPath} as ONE column: every formation + soldier pushes to the
     * CURRENT corridor node, and the front only advances once ~70% of the column has arrived (or a dwell
     * timeout so it never stalls). This is the "formation center advances, everyone follows" behaviour --
     * units follow the built route instead of each solving its own path to a far objective. Once the corridor
     * is walked, hand off to the objective spread (40/25/25/10) + looting for the interior assault.
     */
    private void advanceAssaultColumn(World world) {
        if (!assaultPathBuilt) buildAssaultPath(world);
        if (assaultPath.isEmpty() || assaultFront >= assaultPath.size()) {
            driveFormationsToObjectives(world); // corridor walked -> spread to loot objectives
            driveSoldiersToObjectives(world);
            return;
        }
        BlockPos node = assaultPath.get(assaultFront);
        // Drive the FORMATIONS (carriers = column centres) to the node + count arrivals -- the front advances
        // on THEM (released soldiers may not exist yet, which would race the front straight to the end).
        int near = 0, total = 0;
        double R2 = 8.0 * 8.0;
        for (EntityFormationCarrier c : carriers) {
            if (c == null || c.isDead || c.isEngineerMode()) continue;
            c.setBattleContext(activator, node);
            stepCarrierToward(c, node, 0.09 + warLevel * 0.004);
            total++;
            if (c.getDistanceSq(node.getX() + 0.5, node.getY(), node.getZ() + 0.5) <= R2) near++;
        }
        if (site != null) {
            net.minecraft.util.math.AxisAlignedBB box = new net.minecraft.util.math.AxisAlignedBB(
                    site.getX() - 160, 0, site.getZ() - 160, site.getX() + 160, 255, site.getZ() + 160);
            for (EntitySoldier s : world.getEntitiesWithinAABB(EntitySoldier.class, box)) {
                if (s == null || s.isDead) continue;
                if (couriers.containsKey(s)) continue;
                try { if (!"empire".equalsIgnoreCase(s.getTeam_())) continue; } catch (Throwable ignored) { continue; }
                s.setMarchObjective(node); // soldiers FOLLOW the column to the same node (no gate)
            }
        }
        // Advance the front when enough of the FORMATIONS reach the node -- or after a dwell so it never
        // stalls. THE BREACH MOUTH IS SPECIAL: the column gathers to a higher threshold and then HOLDS a
        // beat, shield walls closing up shoulder-to-shoulder, before pushing through the gap together --
        // the "reform before the storm" moment -- instead of trickling through in ones and twos.
        boolean atBreachNode = (assaultFront == assaultBreachIdx);
        double needFrac = atBreachNode ? 0.85 : 0.7;
        int dwellCap = atBreachNode ? 300 : 120;
        boolean enough = (total == 0) || (near >= Math.max(1, (int) (total * needFrac)));
        boolean advance;
        if (atBreachNode) {
            if (enough && reformUntil == 0) {
                reformUntil = tickAge + 60; // 3s of visible reforming at the wall gap
                EpochRunnerMod.logger.info("[Siege] assault column REFORMING at the breach (" + near + "/" + total + ")");
                try {
                    studio.ERM.war.strategy.WarHeatDebug.show(world,
                            java.util.Collections.singletonList(
                                    studio.ERM.war.strategy.WarHeatDebug.Marker.box(node, 1f, 0.6f, 0.1f, 3)),
                            java.util.Collections.singletonList(
                                    new studio.ERM.war.strategy.WarHeatDebug.Label(node.up(6), "REFORMING FOR THE PUSH")),
                            5 * 20);
                } catch (Throwable ignored) {}
            }
            advance = (reformUntil > 0 && tickAge >= reformUntil) || (tickAge - assaultFrontSince > dwellCap);
        } else {
            advance = enough || (tickAge - assaultFrontSince > dwellCap);
        }
        if (advance) {
            assaultFront++;
            assaultFrontSince = tickAge;
            reformUntil = 0;
            if (tickAge % 20 == 0 || assaultFront >= assaultPath.size())
                publishAssaultRouteDebug(world);
        }
    }

    /** Debug overlay for the assault corridor: a line through every node + a bright beam on the CURRENT front. */
    private void publishAssaultRouteDebug(World world) {
        try {
            java.util.List<studio.ERM.war.strategy.WarHeatDebug.Marker> mk = new ArrayList<>();
            java.util.List<studio.ERM.war.strategy.WarHeatDebug.Label> lb = new ArrayList<>();
            for (int i = 0; i + 1 < assaultPath.size(); i++)
                mk.add(studio.ERM.war.strategy.WarHeatDebug.Marker.line(assaultPath.get(i), assaultPath.get(i + 1), 0.2f, 0.7f, 1f));
            if (assaultFront < assaultPath.size()) {
                BlockPos f = assaultPath.get(assaultFront);
                mk.add(studio.ERM.war.strategy.WarHeatDebug.Marker.beam(f, 0.2f, 0.7f, 1f, 12));
                lb.add(new studio.ERM.war.strategy.WarHeatDebug.Label(f.up(13), "ASSAULT FRONT " + (assaultFront + 1) + "/" + assaultPath.size()));
            }
            studio.ERM.war.strategy.WarHeatDebug.show(world, mk, lb, 120 * 20);
        } catch (Throwable ignored) {}
    }

    private void driveFormationsToObjectives(World world) {
        if (heatspots.isEmpty()) return;
        // C9: distribute the live combat carriers across the SCORED objectives in the 40/25/25/10 main-effort
        // split (concentrate the mass on the top room) instead of an even round-robin. Falls back to the
        // pre-C9 round-robin when the objective graph isn't built yet (degrades safely).
        java.util.List<BlockPos> perCarrier = buildForceAssignment();
        if (perCarrier.isEmpty()) return;
        int i = 0;
        for (EntityFormationCarrier c : carriers) {
            if (c == null || c.isDead || c.isEngineerMode()) continue;
            BlockPos obj = perCarrier.get(Math.min(i, perCarrier.size() - 1));
            i++;
            c.setBattleContext(activator, obj); // commit the contact slice AT the objective, not the breach
            if (c.getDistance(obj.getX(), obj.getY(), obj.getZ()) > 4.0) {
                stepCarrierToward(c, obj, 0.16 + warLevel * 0.006); // WALK there via reachable waypoints (brisk, was a crawl)
            }
        }
    }

    /**
     * One objective BlockPos per assignable carrier, in carrier order, realizing the 40/25/25/10 main-effort
     * split over the SCORED objective graph (top objective ~40% of the force, #2/#3 25%, #4 10%; remainder +
     * objectives beyond the top four fold into the main effort -- the loot-timeout still captures the rest).
     * Falls back to the raw uncaptured-heatspot round-robin (the exact pre-C9 behavior) when the objective
     * graph isn't built yet, so the change degrades safely.
     */
    private java.util.List<BlockPos> buildForceAssignment() {
        int squadCount = 0;
        for (EntityFormationCarrier c : carriers)
            if (c != null && !c.isDead && !c.isEngineerMode()) squadCount++;
        java.util.List<BlockPos> perCarrier = new ArrayList<>();
        if (squadCount == 0) return perCarrier;

        java.util.List<SiegeObjective> ranked = SquadAllocation.rankObjectives(objectiveGraph);
        if (ranked.isEmpty()) {
            // FALLBACK == pre-C9 round-robin over uncaptured heatspots.
            java.util.List<BlockPos> targets = new ArrayList<>();
            for (BlockPos h : heatspots) if (!capturedSpots.contains(h)) targets.add(h);
            if (targets.isEmpty()) {
                BlockPos hold = (lootDepot != null) ? lootDepot : breachCorridor;
                if (hold != null) targets.add(hold);
            }
            for (int k = 0; k < squadCount && !targets.isEmpty(); k++)
                perCarrier.add(targets.get(k % targets.size()));
            return perCarrier;
        }

        int[] alloc = SquadAllocation.splitForces(squadCount, ranked.size());
        for (int oi = 0; oi < ranked.size(); oi++)
            for (int s = 0; s < alloc[oi]; s++) perCarrier.add(ranked.get(oi).pos);
        while (perCarrier.size() < squadCount) perCarrier.add(ranked.get(0).pos); // safety: leftover -> main effort

        if (tickAge % 100 == 0) { // observable: the main-effort distribution on the green markers
            StringBuilder sb = new StringBuilder("[Siege] force split ").append(squadCount).append(" squads ->");
            for (int oi = 0; oi < ranked.size() && oi < WEIGHTS_LOG; oi++)
                sb.append(" #").append(oi + 1).append(xyz(ranked.get(oi).pos)).append("x").append(alloc[oi]);
            EpochRunnerMod.logger.info(sb.toString());
        }
        return perCarrier;
    }
    private static final int WEIGHTS_LOG = 4;

    /**
     * Keep the already-released EntitySoldiers (contact-slice fighters + cavalry) under DIRECTOR CONTROL:
     * give each a march objective (the nearest uncaptured one) so it walks there and only engages an
     * adjacent enemy, instead of reverting to vanilla "chase the player" mob AI. See
     * {@link EntitySoldier#setMarchObjective}.
     */
    private void driveSoldiersToObjectives(World world) {
        if (site == null || heatspots.isEmpty()) return;
        double R = 160.0;
        net.minecraft.util.math.AxisAlignedBB box = new net.minecraft.util.math.AxisAlignedBB(
                site.getX() - R, 0, site.getZ() - R, site.getX() + R, 255, site.getZ() + R);
        for (EntitySoldier s : world.getEntitiesWithinAABB(EntitySoldier.class, box)) {
            if (s == null || s.isDead) continue;
            if (couriers.containsKey(s)) continue; // a hauling courier marches to the depot, don't re-target it
            if (s.getEntityData().hasKey("erm_strategic")) continue; // a passing strategic patrol is NOT ours
            try { if (!"empire".equalsIgnoreCase(s.getTeam_())) continue; } catch (Throwable ignored) { continue; }
            BlockPos goal = nearestUncaptured(s.getPosition());
            if (goal == null) goal = (lootDepot != null) ? lootDepot : breachCorridor;
            s.setMarchObjective(goal);
        }
    }

    /**
     * PRE-SURGE DISCIPLINE. Before the breach opens there are no interior objectives, so loose soldiers
     * (contact slices, catapult crews, cavalry survivors) had NO march order -- they free-hunted the
     * player into an ugly orbiting swarm. March them to a HOLD LINE instead: a rank spread along the
     * wall-face axis, {@code standoff} blocks outside the breach. Each soldier gets a stable slot from
     * its entity id, so the crowd resolves into a WAITING BATTLE LINE (the RTS look); the 6-block march
     * engage-guard means they still fight anything that walks into them.
     */
    private void holdSoldiersAtLine(World world, double standoff) {
        if (site == null || breachCorridor == null) return;
        BlockPos anchor = outsidePoint(world, breachCorridor, Math.max(6.0, standoff));
        double ang = Math.atan2(breachCorridor.getZ() - site.getZ(), breachCorridor.getX() - site.getX());
        double px = -Math.sin(ang), pz = Math.cos(ang); // along the wall face
        double R = 150.0;
        net.minecraft.util.math.AxisAlignedBB box = new net.minecraft.util.math.AxisAlignedBB(
                site.getX() - R, 0, site.getZ() - R, site.getX() + R, 255, site.getZ() + R);
        for (EntitySoldier s : world.getEntitiesWithinAABB(EntitySoldier.class, box)) {
            if (s == null || s.isDead) continue;
            if (couriers.containsKey(s)) continue;
            if (s.getEntityData().hasKey("erm_strategic")) continue;
            try { if (!"empire".equalsIgnoreCase(s.getTeam_())) continue; } catch (Throwable ignored) { continue; }
            int slot = (s.getEntityId() % 17) - 8;                 // stable rank slot, ~2-block spacing
            int rank = (s.getEntityId() / 17) % 3;                 // up to 3 ranks deep
            int hx = (int) Math.round(anchor.getX() + px * slot * 2.0 + Math.cos(ang) * rank * 2.0);
            int hz = (int) Math.round(anchor.getZ() + pz * slot * 2.0 + Math.sin(ang) * rank * 2.0);
            s.setMarchObjective(new BlockPos(hx, surfaceY(world, hx, hz), hz));
        }
    }

    /**
     * DEBUG OVERLAY for the interior assault: a tall beam on every heatspot the troops are told to SEEK
     * (green = uncaptured objective, grey = already looted), a gold box + beam on the LOOT DEPOT, and a
     * green line tracing each engineer-built access stair from the depot to its objective.
     */
    private void publishAssaultDebug(World world) {
        try {
            java.util.List<studio.ERM.war.strategy.WarHeatDebug.Marker> mk = new ArrayList<>();
            java.util.List<studio.ERM.war.strategy.WarHeatDebug.Label> lb = new ArrayList<>();
            if (lootDepot != null) {
                mk.add(studio.ERM.war.strategy.WarHeatDebug.Marker.beam(lootDepot, 1f, 0.85f, 0f, 14));
                mk.add(studio.ERM.war.strategy.WarHeatDebug.Marker.box(lootDepot, 1f, 0.85f, 0f, 3));
                lb.add(new studio.ERM.war.strategy.WarHeatDebug.Label(lootDepot.up(15),
                        "LOOT DEPOT (" + lootChests.size() + " chests)"));
            }
            for (BlockPos sp : heatspots) {
                boolean done = capturedSpots.contains(sp);
                float r = done ? 0.5f : 0f, g = done ? 0.5f : 1f, b = done ? 0.5f : 0.25f;
                mk.add(studio.ERM.war.strategy.WarHeatDebug.Marker.beam(sp, r, g, b, done ? 6 : 18));
                lb.add(new studio.ERM.war.strategy.WarHeatDebug.Label(sp.up(done ? 7 : 19),
                        (done ? "LOOTED " : "OBJECTIVE: LOOT ") + xyz(sp)));
                if (lootDepot != null)
                    mk.add(studio.ERM.war.strategy.WarHeatDebug.Marker.line(lootDepot, sp, 0f, 1f, 0.4f));
            }
            // FIRE SECTORS (C5): float each formation's assigned posture above it so the player can SEE the
            // distinct JOBS -- the breach mouth reads BREACH_CLEARING while deeper squads ADVANCE_AND_FIRE,
            // engineers HOLD_FIRE and the standoff line SUPPRESS_FROM_SLOT. Pure overlay; changes no firing.
            for (EntityFormationCarrier c : carriers) {
                if (c == null || c.isDead) continue;
                lb.add(new studio.ERM.war.strategy.WarHeatDebug.Label(c.getPosition().up(3), "FIRE:" + sectorFor(c)));
            }
            for (EntityFormationCarrier c : bombardLine) {
                if (c == null || c.isDead) continue;
                lb.add(new studio.ERM.war.strategy.WarHeatDebug.Label(c.getPosition().up(3), "FIRE:" + sectorFor(c)));
            }
            studio.ERM.war.strategy.WarHeatDebug.show(world, mk, lb, 120 * 20);
            EpochRunnerMod.logger.info("[Siege] assault debug: " + heatspots.size() + " objective beams, "
                    + capturedSpots.size() + " looted, depot " + (lootDepot != null ? xyz(lootDepot) : "?"));
        } catch (Throwable t) {
            EpochRunnerMod.logger.warn("[Siege] assault debug failed: " + t);
        }
    }

    private BlockPos nearestUncaptured(BlockPos from) {
        BlockPos best = null; double bd = Double.MAX_VALUE;
        for (BlockPos s : heatspots) {
            if (capturedSpots.contains(s)) continue;
            double d = s.distanceSq(from);
            if (d < bd) { bd = d; best = s; }
        }
        return best;
    }

    /** Teleport a container's items straight into the depot chests (modded via IItemHandler, else vanilla
     *  IInventory). Removes from the source + populates the depot, exactly as the player asked. */
    private int lootContainer(World world, BlockPos pos) {
        java.util.List<net.minecraft.item.ItemStack> haul = extractToHaul(world, pos);
        for (net.minecraft.item.ItemStack st : haul) depositToDepot(world, st);
        return haul.size();
    }

    /** Put a stack into the first depot chest with room; drop at the depot if every chest is full. */
    private void depositToDepot(World world, net.minecraft.item.ItemStack stack) {
        if (stack == null || stack.isEmpty()) return;
        for (BlockPos cp : lootChests) {
            net.minecraft.tileentity.TileEntity te = world.getTileEntity(cp);
            if (!(te instanceof net.minecraft.inventory.IInventory)) continue;
            net.minecraft.inventory.IInventory chest = (net.minecraft.inventory.IInventory) te;
            for (int i = 0; i < chest.getSizeInventory() && !stack.isEmpty(); i++) {
                net.minecraft.item.ItemStack slot = chest.getStackInSlot(i);
                if (slot.isEmpty()) {
                    int n = Math.min(stack.getCount(), stack.getMaxStackSize());
                    net.minecraft.item.ItemStack put = stack.copy(); put.setCount(n);
                    chest.setInventorySlotContents(i, put);
                    stack.shrink(n);
                } else if (net.minecraft.item.ItemStack.areItemsEqual(slot, stack)
                        && net.minecraft.item.ItemStack.areItemStackTagsEqual(slot, stack)
                        && slot.getCount() < slot.getMaxStackSize()) {
                    int add = Math.min(slot.getMaxStackSize() - slot.getCount(), stack.getCount());
                    slot.grow(add); stack.shrink(add);
                }
            }
            chest.markDirty();
            if (stack.isEmpty()) return;
        }
        if (!stack.isEmpty() && lootDepot != null) {
            try {
                world.spawnEntity(new net.minecraft.entity.item.EntityItem(world,
                        lootDepot.getX() + 0.5, lootDepot.getY() + 1.0, lootDepot.getZ() + 0.5, stack));
            } catch (Throwable ignored) {}
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
        tickVehicleRouteSignals(world); // C10/C12: push RouteStatus + breachHold to vehicles; poll stuck reports

        switch (phase) {
            case P_DEPLOY:
                // Hold formation up front; creep forward menacingly but do not engage yet.
                advanceLine(world, ENCIRCLE_RING - 6.0, 0.03 + warLevel * 0.002);
                if (tsp >= PHASE_DEPLOY_TICKS) beginBombardment(world);
                break;

            case P_BOMBARD:
                tickCatapults(world);
                refreshBombardment();
                tickBombardmentAir(world);
                advanceLine(world, BOMBARD_RING, 0.045 + warLevel * 0.003);
                // Loose soldiers (contact slices, crews) HOLD A LINE at standoff instead of free-hunting
                // the player into an ugly swarm -- the RTS look: ranks waiting for the breach.
                if (tickAge % 100 == 0) holdSoldiersAtLine(world, BOMBARD_RING - 6.0);
                if (tsp >= PHASE_BOMBARD_TICKS || waveDefeated(tsp)) beginEngineerPush(world);
                break;

            case P_ENGINEER:
                // NOTE: NO catapults here -- the barrage opened the breach during BOMBARDMENT. If the
                // catapults kept cratering the corridor now they would blow the footing out from under
                // the engineers working in it (which is what made the engineer phase die in 1 tick).
                refreshBombardment();
                tickEngineers(world);
                advanceLine(world, WALL_RING + 4.0, 0.05 + warLevel * 0.003);
                // Ranks form up just outside the breach, waiting for the engineers -- not swarming the player.
                if (tickAge % 100 == 0) holdSoldiersAtLine(world, 12.0);
                // Keep the engineer plan board (route line + obstacle boxes) VISIBLE for the whole phase --
                // it published once and expired after 90s, which is why the blue route line "disappeared".
                if (tsp > 0 && tsp % (60 * 20) == 0) publishEngineerPlanDebug(world);
                // Only advance early once the engineers GENUINELY finished (engineersComplete now means
                // every task is done, not "all dead") AND a minimum dwell has passed, so they actually
                // get seen laddering / mining / sapping.
                // PHASE 1 -> PHASE 2 handoff: when the WALL breach (phase-1 tasks) is done, don't end the
                // phase -- start the interior tunnel dig instead. Only AFTER the tunnels finish (or the
                // phase timer runs out) do we surge.
                if (engineersComplete && !tunnelPhase && tsp >= MIN_PHASE_DWELL) {
                    beginEngineerTunnels(world);
                }
                boolean engineeringDone = engineersComplete && (tunnelPhase || engQueue.isEmpty());
                // Don't surge into a WALL: wait until the breach is actually OPEN before committing the
                // assault (the "they didn't finish the breach before the phase ended" fix). Normally we
                // surge when the engineering is done; if the phase timer elapses we surge only once the
                // breach is open; a hard cap (+60s) is the never-hang failsafe.
                boolean timeUp = tsp >= PHASE_ENGINEER_TICKS;
                boolean hardCap = tsp >= PHASE_ENGINEER_TICKS + 60 * 20;
                if ((engineeringDone && tsp >= MIN_PHASE_DWELL)
                        || (breachOpened && timeUp)
                        || hardCap
                        || waveDefeated(tsp)) beginSurge(world);
                break;

            case P_SURGE:
                // Crews KEEP WORKING under the advancing assault -- finishing the corridor, the ladders, the
                // access stairs -- instead of freezing mid-swing at the phase flip. Late phase-1 completion
                // still hands off to the interior tunnel dig, so the visible work chain never breaks.
                tickEngineers(world);
                if (engineersComplete && !tunnelPhase) beginEngineerTunnels(world);
                // THE DIRECTOR DIRECTS: drive each formation (carrier + puppet squad) through the breach to
                // its assigned objective, and keep the released soldiers MARCHING to objectives (not chasing
                // the player). Squads advance as units; by the assault they are well inside.
                if (tickAge % 5 == 0) advanceAssaultColumn(world); // march the column along the engineer corridor
                if (tsp >= PHASE_SURGE_TICKS || waveDefeated(tsp)) beginInteriorAssault(world);
                break;

            case P_ASSAULT:
                tickEngineers(world); // crews finish their remaining work, then form up at the breach
                tickLooting(world, tsp);
                if (waveDefeated(tsp)) {
                    flushCouriers(world);                // deposit any loot still in transit
                    forceResolve(BattleOutcome.VICTORY); // the assault was fought off
                } else if (capturedSpots.size() >= heatspots.size() || tsp >= PHASE_ASSAULT_TICKS) {
                    flushCouriers(world);
                    forceResolve(BattleOutcome.VICTORY); // every heatspot looted, or the window elapsed
                }
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
                stepCarrierToward(c, target, speed);
            }
        }
    }

    /**
     * Order a carrier to WALK toward a (possibly far) goal by handing its navigator a REACHABLE near waypoint
     * -- the ground navigator can only solve short hops, so a far interior objective made it stall/snap instead
     * of visibly marching. Clamping the target to ~14 blocks ahead each drive cycle makes the whole line stride
     * in continuously (the same incremental walk the engineer crews use), not teleport to its destination.
     */
    private void stepCarrierToward(EntityFormationCarrier c, BlockPos goal, double speed) {
        if (c == null || goal == null) return;
        // WALK SPEED (config-driven: WarLevelsConfig siege.walkSpeedMultiplier, default 3x). All engineer
        // marches + formation advances + firing-slot moves route through here. Capped so the ground
        // navigator doesn't overshoot its waypoints at very high multipliers.
        speed = Math.min(0.9, speed * studio.ERM.war.config.WarLevelsConfig.walkSpeedMultiplier());
        double dx = goal.getX() + 0.5 - c.posX, dz = goal.getZ() + 0.5 - c.posZ;
        double d = Math.sqrt(dx * dx + dz * dz);
        final double MAX = 14.0; // near enough for the ground navigator to always solve a path
        if (d <= MAX) { c.setMoveTarget(goal, speed); return; }
        double f = MAX / d;
        double wx = c.posX + dx * f, wz = c.posZ + dz * f;
        // SLIDE TO THE OPENING. A straight-line intermediate point can land ON a wall face, stalling the
        // march until the give-up timer. Probe sideways along the face for a column whose surface is
        // walkable from here (small rise only) and aim there instead -- units now flow around walls
        // through existing gates/gaps/stairs rather than bumping the face.
        double ux = dx / d, uz = dz / d, px = -uz, pz = ux;
        int allowY = (int) Math.max(c.posY, goal.getY()) + 3; // tolerate stairs/ramps toward a high goal
        double bx = wx, bz = wz;
        for (int off : new int[]{0, 2, -2, 4, -4, 6, -6, 9, -9, 12, -12}) {
            int sxi = (int) Math.floor(wx + px * off), szi = (int) Math.floor(wz + pz * off);
            if (surfaceY(c.world, sxi, szi) <= allowY) { bx = wx + px * off; bz = wz + pz * off; break; }
        }
        c.setMoveTarget(new BlockPos(bx, goal.getY(), bz), speed);
    }

    /** March each volley/ballista carrier to its firing slot (they spawn at the staging line now) and
     *  keep the line's suppression aimed at the defender's current position. */
    private void refreshBombardment() {
        if (bombardLine.isEmpty()) return;
        if ((tickAge % 10) != 0) return;
        BlockPos p = (activator != null) ? activator.getPosition() : null;
        for (EntityFormationCarrier c : bombardLine) {
            if (c == null || c.isDead) continue;
            BlockPos slot = bombardSlots.get(c);
            if (slot != null && c.getDistanceSq(slot) > 9.0) {
                stepCarrierToward(c, slot, 0.08 + warLevel * 0.003); // walk down to the firing slot
            }
            if (p != null) c.setSuppressionTarget(p);
        }
    }

    /**
     * BOMBARDMENT-phase air dispatcher. Runs every tick during P_BOMBARD, but only ACTS when the
     * cooldown reaches zero, so jets/bombers arrive on a rhythm (~one event every 7-9s, a little
     * faster at higher levels) instead of spamming. Levels:
     *   L8     -> jet strafing/gun runs on the breach
     *   L9     -> jets + occasional Lancaster carpet run
     *   L10    -> jets + B52 carpet bombing
     * Targets alternate between the chosen breach corridor and the base core/site so the player sees
     * both the wall AND the interior get hit. Spawn geometry is handled by AirStrikeController at the
     * standard approach distance (verified visible: spawn < ~135 blocks from the player, under the
     * 180-block despawn radius), so high passes are not culled on arrival.
     */
    private void tickBombardmentAir(World world) {
        boolean sky = doctrine() == BaseType.SKY;
        // SKY bases are AIRCRAFT-PRIMARY (you can't catapult a floating platform): commit air earlier --
        // gunship CAS from L6 and jets/bombers from L8. Other base types keep the original L8 jet gate.
        int gate = sky ? 6 : 8;
        if (warLevel < gate) return;              // below the gate, rely on the initial CAS heli pass only
        if (airStrikeCooldown > 0) { airStrikeCooldown--; return; }

        // Alternate target: breach corridor (open the wall) vs base core (terrorise the interior).
        BlockPos coreTgt = (baseCore != null)
                ? new BlockPos((baseCore.chunkX << 4) + 8, surfaceY(world, (baseCore.chunkX << 4) + 8, (baseCore.chunkZ << 4) + 8), (baseCore.chunkZ << 4) + 8)
                : site;
        BlockPos tgt = (breachCorridor != null && (tickAge / 20) % 2 == 0) ? breachCorridor : coreTgt;

        // SKY doctrine: bombard the PLATFORM itself and its UNDERSIDE/supports (the approach-ground column
        // beneath the core) -- the way to bring a sky base down is to hit the deck and the pillars holding it.
        if (sky) {
            BlockPos underside = new BlockPos(site.getX(),
                    (baseAnalysis != null) ? baseAnalysis.approachY : surfaceY(world, site.getX(), site.getZ()),
                    site.getZ());
            tgt = ((tickAge / 20) % 2 == 0) ? site : underside;
        }

        // L8+ -> jets/bombers (dispatchBombers no-ops below L8). SKY L6-7 -> helicopter gunship CAS on the
        // platform so the air-primary doctrine still has teeth before jets unlock.
        boolean launched = (warLevel >= 8)
                ? AirStrikeController.dispatchBombers(world, tgt, warLevel)
                : AirStrikeController.requestHostileCAS(world, tgt, warLevel);
        EpochRunnerMod.logger.info("[Siege] bombardment air dispatch -> " + tgt + " launched=" + launched
                + " L" + warLevel + (sky ? " (SKY)" : ""));

        // Re-arm. SKY flies a tighter cadence (air IS the siege); higher levels also a bit faster.
        airStrikeCooldown = sky ? Math.max(3 * 20, (7 * 20) - warLevel * 4)
                                : Math.max(5 * 20, (9 * 20) - warLevel * 6);
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
            // SLOW + METHODICAL: a deliberate ~2s cadence (not a machine-gun barrage). Slower fire reads as a
            // proper siege bombardment, and each round does big destruction (see breachWall radius below).
            else { launchCatapult(world); catapultCooldown = Math.max(34, 70 - warLevel * 3); }
        }
        Iterator<CatapultShot> it = catapultShots.iterator();
        while (it.hasNext()) {
            CatapultShot s = it.next();
            // Track the live boulder so we know where it actually came to rest (it can clip a building
            // mid-arc and place a cobblestone block on a rooftop -- that was the "cobblestone that
            // doesn't blow up" litter).
            boolean alive = s.block != null && !s.block.isDead;
            if (alive) {
                s.lastX = (int) Math.floor(s.block.posX);
                s.lastY = (int) Math.floor(s.block.posY);
                s.lastZ = (int) Math.floor(s.block.posZ);
            }
            // MEME: a DIRECT cobblestone hit on the player -> massive knockback + ~8 hearts.
            if (alive && activator != null && !activator.isDead) {
                double ddx = s.block.posX - activator.posX;
                double ddy = s.block.posY - (activator.posY + 1.0);
                double ddz = s.block.posZ - activator.posZ;
                if (ddx * ddx + ddy * ddy + ddz * ddz < 2.6 * 2.6) {
                    activator.attackEntityFrom(net.minecraft.util.DamageSource.FALLING_BLOCK, 16.0F);
                    double kx = activator.posX - s.block.posX, kz = activator.posZ - s.block.posZ;
                    double klen = Math.max(0.001, Math.sqrt(kx * kx + kz * kz));
                    activator.addVelocity((kx / klen) * 2.4, 1.2, (kz / klen) * 2.4);
                    activator.velocityChanged = true;
                    explosionEffect(world, new BlockPos((int) activator.posX, (int) activator.posY, (int) activator.posZ));
                    s.block.setDead();
                    it.remove();
                    continue;
                }
            }
            boolean landed = !alive && s.lastY != Integer.MIN_VALUE;
            if (landed || tickAge >= s.impactTick) {
                if (landed) { // remove the cobblestone the falling block left where it came to rest
                    try {
                        BlockPos lp = new BlockPos(s.lastX, s.lastY, s.lastZ);
                        if (world.getBlockState(lp).getBlock() == Blocks.COBBLESTONE) world.setBlockToAir(lp);
                        if (world.getBlockState(lp.down()).getBlock() == Blocks.COBBLESTONE) world.setBlockToAir(lp.down());
                    } catch (Throwable ignored) {}
                }
                // A single TNT-SIZED (a tad smaller) blast where the round lands -- NOT openGroundBreach,
                // which razed whole columns to the ground ("levels entire chunks in single blocks"). The
                // catapult only SOFTENS the wall; the ENGINEERS open the real ground breach. breachWall is a
                // friendly-safe, antigrief sphere of damageBlock (claimed land -> repairable scaffold), and
                // scatterDebris flings a few rubble blocks that are also logged for /war repair.
                // ONE real blast a tad smaller than vanilla TNT (3.5 vs 4.0) that ACTUALLY destroys the
                // structure + damages defenders. Terrain damage is ON: ProtectionHandler.onExplosionDetonate
                // routes claimed blocks through the repair system (scaffold = invisible + /war repairable)
                // and spares protected blocks, so it visibly blows the base apart but stays recoverable.
                // (Removed scatterDebris -- it was ADDING cobblestone, the "shots just add cobblestone" bug;
                // and breachWall, whose claimed-land scaffolds looked like the shot did nothing.)
                // Detonate at the ACTUAL impact point. s.target.getY() is the breach-FOOT ground; once the
                // creeping barrage marches into the higher interior that Y is UNDERGROUND, so the blast went
                // off below the surface -- invisible + destroying nothing ("many not exploding"). Use where
                // the round actually came to rest (landed), else the surface column at the target.
                BlockPos impact = landed
                        ? new BlockPos(s.lastX, s.lastY, s.lastZ)
                        : new BlockPos(s.target.getX(), surfaceY(world, s.target.getX(), s.target.getZ()), s.target.getZ());
                explosionEffect(world, impact);
                // FULL-TNT destruction but BLOCK-ONLY (no entity damage) so the catapult never friendly-fires
                // the siege's own troops/tanks/aircraft -- the old world.newExplosion(null,...) caught every
                // nearby entity (null source = the FF handler couldn't cancel it). breachWall is the antigrief
                // damageBlock sphere: claimed land -> repairable scaffold, rival/neutral -> cleared to air.
                // DEVASTATING: a big crater per round (scaled by tech level) so the bombardment actually
                // tears the base apart. Block-only (no entity damage) keeps it friendly-safe.
                breachWall(world, impact, (warLevel >= 8) ? 5 : (warLevel >= 6) ? 4 : 3);
                if (alive) s.block.setDead();
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
            // CREEPING BARRAGE: as the bombardment goes on, march the aim point inward from the wall
            // toward the core, so the army ERASES MORE of the castle over time instead of digging one
            // hole at the wall (the "advance the target as they stop doing damage" note). Scales with
            // level -- at L10 it walks deep into the base and pulverises it. Aim at the breach FOOT Y
            // (ground), NOT surfaceY (which is the roof). Real artillery SPREAD, not machine-accuracy.
            int maxCreep = (warLevel >= 9) ? 26 : (warLevel >= 6) ? 16 : 8;
            // SLOW creep -- the aim point walks inward methodically (~1 block every 3s) so each spot gets
            // properly pulverised before the barrage moves on, instead of racing across the base.
            int creep = Math.min(maxCreep, (tickAge - lastPhaseChangeTick) / 60);
            double toCore = Math.atan2(site.getZ() - breachCorridor.getZ(), site.getX() - breachCorridor.getX());
            int cx = breachCorridor.getX() + (int) Math.round(Math.cos(toCore) * creep);
            int cz = breachCorridor.getZ() + (int) Math.round(Math.sin(toCore) * creep);
            int spread = (warLevel >= 9) ? 6 : 4;
            int sx = cx + world.rand.nextInt(spread * 2 + 1) - spread;
            int sz = cz + world.rand.nextInt(spread * 2 + 1) - spread;
            target = new BlockPos(sx, breachCorridor.getY(), sz);
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

        // The catapult is a small machine: ONE cobblestone per shot (the user's ask). It lobs a single
        // round; the cadence (catapultCooldown) provides the rhythm, not a cluster.
        int boulders = 1;
        world.playSound(null, from, SoundEvents.ENTITY_GENERIC_EXPLODE, SoundCategory.HOSTILE, 2.4F, 0.7F);
        for (int b = 0; b < boulders; b++) {
            // Every boulder scatters (even the first) -- the barrage felt machine-accurate before.
            int tx = target.getX() + world.rand.nextInt(7) - 3;
            int tz = target.getZ() + world.rand.nextInt(7) - 3;
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
                boolean placedSupport = false;
                if (world.isAirBlock(launchBlock)) {
                    world.setBlockState(launchBlock, Blocks.COBBLESTONE.getDefaultState(), 2);
                    placedSupport = true;
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
     * Fling a few cobblestone "debris" blocks onto the surface around a catapult impact, each LOGGED for
     * /war repair (recorded as originally-air, so the repair command later clears it). Unlike scatterRubble
     * -- which uses setCampBlock and only reverts when the whole siege ends -- this debris persists after
     * the battle until the player runs /war repair, which is what the user asked for.
     */
    private void scatterDebris(World world, BlockPos center) {
        try {
            WarWorldData data = WarWorldData.get(world);
            int pieces = 2 + world.rand.nextInt(3); // 2-4 chunks of rubble
            for (int n = 0; n < pieces; n++) {
                int rx = center.getX() + world.rand.nextInt(7) - 3;
                int rz = center.getZ() + world.rand.nextInt(7) - 3;
                BlockPos p = new BlockPos(rx, surfaceY(world, rx, rz), rz);
                if (!world.isAirBlock(p)) continue;
                // Record original (air) BEFORE placing, but never clobber an existing repair order (e.g.
                // a block damageBlock already scaffolded here) -- that would lose the true original.
                if (data != null && !data.getRepairMap().containsKey(p.toImmutable())) {
                    data.addRepairOrder(p.toImmutable(), Blocks.AIR.getDefaultState());
                }
                world.setBlockState(p, Blocks.COBBLESTONE.getDefaultState(), 2);
            }
        } catch (Throwable ignored) {}
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
        // Width AND depth scale with tech level: at L9-10 the barrage pulverises a wide, deep section of
        // the front into rubble (13 wide x 5 deep) so the engineers barely have to finish the breach.
        // Carve from the passed foot Y (callers set this to the field ground at the breach) UP to each
        // column's top. Do NOT dive with terrainGroundY here -- that read ~6 blocks too low under the
        // castle foundation. On a building the column is razed from the foot up; on open ground it's a
        // shallow divot. The foot itself is now the correct field-ground from chooseBreachCorridor.
        int floorY = at.getY();
        int half = (warLevel >= 9) ? 6 : (warLevel >= 6) ? 4 : 2;   // front width (13 / 9 / 5)
        int depth = (warLevel >= 9) ? 4 : (warLevel >= 6) ? 2 : 1;  // blocks carved INTO the wall
        double ang = Math.atan2(at.getZ() - site.getZ(), at.getX() - site.getX());
        double px = -Math.sin(ang), pz = Math.cos(ang); // along the wall (front width)
        double ix = (site.getX() - at.getX()), iz = (site.getZ() - at.getZ());
        double ilen = Math.max(0.001, Math.hypot(ix, iz));
        ix /= ilen; iz /= ilen;                          // inward toward the core (carve depth)
        for (int d = 0; d <= depth; d++) {
            for (int w = -half; w <= half; w++) {
                int x = (int) Math.round(at.getX() + px * w + ix * d);
                int z = (int) Math.round(at.getZ() + pz * w + iz * d);
                int top = surfaceY(world, x, z);
                for (int y = floorY; y <= top + 2; y++) {
                    damageBlock(world, new BlockPos(x, y, z));
                }
            }
        }
    }

    // ════════════════════════════════════════════════════════════
    //  ENGINEERING  (real blocks; breaches are terrain-only and friendly-safe)
    // ════════════════════════════════════════════════════════════

    /**
     * Once a lane is breached, cut AND floor a wide corridor from the breach foot inward to the base --
     * but as a walkable RAMP that climbs to follow the interior floor (the castle usually sits on a
     * raised platform, so a flat trench at moat level left "no real path up"). Width + headroom scale
     * with level so tanks can drive in. Floors gaps via setCampBlock (reverts on siege end); cuts via
     * damageBlock (antigrief-aware).
     */
    private void levelBreachPath(World world, BlockPos bp) {
        double dist = Math.hypot(site.getX() - bp.getX(), site.getZ() - bp.getZ());
        if (dist < 1.0) return;
        double ux = (site.getX() - bp.getX()) / dist, uz = (site.getZ() - bp.getZ()) / dist; // toward core
        double px = -uz, pz = ux; // corridor width axis
        int hw = laneHalf(), hh = laneHeight();
        int steps = (int) Math.min(dist, 40);
        int rampY = bp.getY();
        for (int s = 0; s <= steps; s++) {
            int baseX = (int) Math.round(bp.getX() + ux * s);
            int baseZ = (int) Math.round(bp.getZ() + uz * s);
            // Follow the TRUE GROUND (terrainGroundY), not surfaceY -- surfaceY is the ROOF of any building
            // along the corridor, so the ramp used to climb onto rooftops and "float in the air". The ground
            // grade gives a walkable ramp that stays on the terrain. Rise still capped to +5 so it eases onto
            // a low platform and never builds a cobblestone TOWER up the side of a tall building.
            int grnd = terrainGroundY(world, baseX, baseZ);
            if (grnd > rampY + 1) rampY++;
            else if (grnd < rampY - 1) rampY--;
            else rampY = grnd;
            rampY = Math.min(rampY, bp.getY() + 5);
            for (int w = -hw; w <= hw; w++) {
                int x = (int) Math.round(baseX + px * w);
                int z = (int) Math.round(baseZ + pz * w);
                BlockPos floor = new BlockPos(x, rampY - 1, z);
                try {
                    if (world.isAirBlock(floor) || world.getBlockState(floor).getMaterial().isLiquid()) {
                        setCampBlock(world, floor, Blocks.COBBLESTONE.getDefaultState());
                    }
                } catch (Throwable ignored) {}
                for (int y = rampY; y <= rampY + hh; y++) {
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

    /** Spawn one real Flan tank (EntityAIPilot crew) at an absolute front position. Returns the pilot. */
    private EntityAIPilot spawnTankAtFront(World world, double lateral, double ring, String flansShortName) {
        BlockPos at = frontPoint(world, ring, lateral);
        return spawnTankAt(world, at, flansShortName);
    }

    private EntityAIPilot spawnTankAt(World world, BlockPos at, String flansShortName) {
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
            return pilot;
        } catch (Throwable t) {
            EpochRunnerMod.logger.warn("[Siege] Failed to spawn tank '" + flansShortName + "': " + t.getMessage());
            return null;
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
        if (warLevel >= 10) {
            // L10 MODERN: modern MBTs only (WW2 armour retired). Emphasize Abrams/T90.
            pool = new String[] { "abrams", "abrams", "abrams", "T90", "T90", "T90",
                                  "Leo2A6", "Leo2A6", "ChallyII", "ChallyII" };
        } else if (warLevel >= 9) {
            // L9 LATE COLD WAR: Abrams/T90 mainstay, Leo2A6/ChallyII rarer, a few T34/KV1 reserve stocks.
            pool = new String[] { "abrams", "abrams", "abrams", "T90", "T90", "T90",
                                  "Leo2A6", "ChallyII", "T34", "KV1" };
        } else if (warLevel >= 8) {
            // L8 EARLY COLD WAR: legacy WW2 armour is the bulk; modern MBTs are RARE prototype sightings.
            pool = new String[] { "T34", "T34", "KV1", "KV1", "Sherman", "Sherman", "Churchill", "Churchill",
                                  "Cromwell", "Cromwell", "M10", "M10", "Hellcat", "Hellcat",
                                  "abrams", "T90", "Leo2A6", "ChallyII" };
        } else if (warLevel >= 6) {
            pool = new String[] { "Tiger", "Tiger131", "Panzer", "PanzerIIL", "Sherman", "T34",
                                  "Churchill", "Cromwell", "StuG", "M10", "Hellcat", "KV1" };
        } else {
            pool = new String[] { "Sherman", "Panzer", "Cromwell" };
        }
        return pool[world.rand.nextInt(pool.length)];
    }

    /** Transport/armoured-car pool by level. Real Flan ShortNames from the installed packs. */
    private String pickTransport(World world) {
        String[] pool;
        if (warLevel >= 10) {
            pool = new String[] { "Humvee", "Humvee", "Greyhound", "Greyhound" };             // modern only
        } else if (warLevel >= 9) {
            pool = new String[] { "Humvee", "Humvee", "Greyhound", "Greyhound",               // modern mainstay
                                  "M3Halftrack", "SdkFz251", "Jeep" };                          // + reserve stocks
        } else if (warLevel >= 8) {
            pool = new String[] { "M3Halftrack", "SdkFz251", "Jeep", "SASJeep", "Kubel", "BMWR75" }; // WW2 transports
        } else {
            pool = new String[] { "Jeep", "SASJeep", "Kubel", "M3Halftrack", "SdkFz251", "BMWR75" };
        }
        return pool[world.rand.nextInt(pool.length)];
    }

    /** A recon/transport screen of jeeps + halftracks (real Flan vehicles) ahead of the line. */
    private void spawnTransportPlatoon(World world, double ring) {
        int count = (warLevel >= 8) ? 4 : 3;
        double mid = (count - 1) / 2.0;
        for (int i = 0; i < count; i++) {
            double lateral = (i - mid) * (TANK_LANE_SPACING * 0.75);
            String vt = pickTransport(world);
            EntityAIPilot p = spawnTankAtFront(world, lateral, ring, vt);
            // CARRIERS (halftrack / SdkFz251 / M3) rally at the breach to drop their troops near the
            // assault. SCOUTS (jeep/kubel/humvee/BMW/SASJeep/...) are NOT rallied so their SCOUT movement
            // style takes over -- they circle the base perimeter at standoff instead of charging the gate.
            if (p != null && breachCorridor != null) {
                String low = vt.toLowerCase();
                boolean carrier = low.contains("halftrack") || low.contains("sdkfz251") || low.contains("m3");
                if (carrier) p.setRallyPoint(outsidePoint(world, breachCorridor, 8.0 + i * 2.0));
            }
        }
    }

    // ════════════════════════════════════════════════════════════
    //  NAVAL PATROL  (S100 boats when the base is on the water)
    // ════════════════════════════════════════════════════════════

    /**
     * When the target base is girdled by water, spawn a small flotilla of Flan S100 patrol boats that
     * picket the sea around it (crude patrol: each boat takes station at a point on the water ring),
     * instead of land jeeps that can't drive the ocean. Boats sit on the water surface; if "s100" isn't
     * a loaded Flan ShortName the pilot summon simply no-ops (logged), so this is safe to always attempt.
     */
    private void maybeSpawnNavalPatrol(World world) {
        int waterRing = 0, samples = 0;
        for (int a = 0; a < 12; a++) {
            double ang = a * (Math.PI * 2 / 12);
            int x = site.getX() + (int) Math.round(Math.cos(ang) * 36);
            int z = site.getZ() + (int) Math.round(Math.sin(ang) * 36);
            samples++;
            try {
                BlockPos top = world.getTopSolidOrLiquidBlock(new BlockPos(x, 64, z));
                if (world.getBlockState(top).getMaterial() == Material.WATER
                 || world.getBlockState(top.down()).getMaterial() == Material.WATER) waterRing++;
            } catch (Throwable ignored) {}
        }
        // OCEAN doctrine: the analyzer already confirmed a water base, so commit a real fleet and accept a
        // lighter water ring (a base on a peninsula/atoll still wants the shoreline picketed). Otherwise
        // keep the conservative gate so an inland base with a pond never spawns a navy.
        boolean ocean = doctrine() == BaseType.OCEAN;
        int gate = ocean ? samples / 3 : samples / 2;
        if (waterRing < gate) return; // not a water base; the jeeps are fine
        int boats = ocean ? Math.min(14, 6 + warLevel) : Math.min(8, 3 + warLevel / 2); // bigger fleet for a true sea base
        int placed = 0;
        for (int i = 0; i < boats; i++) {
            double ang = (Math.PI * 2 * i) / boats;
            BlockPos water = null;
            // Search OUTWARD from the base for genuinely deep open sea -- spawning at the inner ring landed
            // the boats in the shallow moat where they beached. Push them out to water >=2 deep.
            for (int rad = 44; rad <= 76 && water == null; rad += 8) {
                int x = site.getX() + (int) Math.round(Math.cos(ang) * rad);
                int z = site.getZ() + (int) Math.round(Math.sin(ang) * rad);
                water = findWaterSurface(world, x, z);
            }
            if (water == null) continue;
            EntityAIPilot boat = spawnBoatAt(world, water, "s100");
            if (boat != null) { boat.setRallyPoint(water); placed++; }
        }
        EpochRunnerMod.logger.info("[Siege] naval patrol: water ring " + waterRing + "/" + samples
                + " -> spawned " + placed + " S100 boat(s)");
    }

    /** A point of OPEN water at least 3 blocks deep (so a boat floats and doesn't beach in the shallow moat). */
    private BlockPos findWaterSurface(World world, int x, int z) {
        try {
            // getTopSolidOrLiquidBlock returns the AIR block ABOVE the surface, so the water SURFACE is
            // top.down(). The old code checked `top` for WATER (it's air) and so ALWAYS returned null --
            // which is why no S100s ever spawned. Check the surface column for >=3 deep open water.
            BlockPos surface = world.getTopSolidOrLiquidBlock(new BlockPos(x, 64, z)).down();
            if (world.getBlockState(surface).getMaterial() == Material.WATER
                    && world.getBlockState(surface.down()).getMaterial() == Material.WATER
                    && world.getBlockState(surface.down(2)).getMaterial() == Material.WATER) {
                return surface; // the water surface block, floatable
            }
        } catch (Throwable ignored) {}
        return null;
    }

    /** Spawn one EntityAIPilot-crewed Flan boat on the water (tracked in {@link #vehicles} for cleanup). */
    private EntityAIPilot spawnBoatAt(World world, BlockPos at, String shortName) {
        try {
            EntityAIPilot pilot = new EntityAIPilot(world);
            pilot.setPosition(at.getX() + 0.5, at.getY() + 1.0, at.getZ() + 0.5);
            pilot.setVehicleType(shortName);
            pilot.setMcmTeam("empire");
            // The pilot control loop early-returns without a target; give it the activator so it actually
            // runs (controlBoat then PATROLS the water near the base + the turrets fire), instead of just
            // floating where it spawned.
            if (activator != null) { try { pilot.setAttackTarget(activator); } catch (Throwable ignored) {} }
            world.spawnEntity(pilot);
            vehicles.add(pilot);
            return pilot;
        } catch (Throwable t) {
            EpochRunnerMod.logger.warn("[Siege] boat '" + shortName + "' spawn failed: " + t.getMessage());
            return null;
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
     * The NATURAL TERRAIN ground in a column -- scan DOWN from the surface past any man-made blocks to
     * the first natural solid block, and stand on top of it. surfaceY returns the ROOF when a building
     * (or the player) is in the column, which made the breach foot + ramp anchor at roof height and
     * "build the ramp way up in the air". This returns the actual ground the structure sits on.
     */
    private int terrainGroundY(World world, int x, int z) {
        int top = surfaceY(world, x, z);
        for (int y = top; y > Math.max(4, top - 48); y--) {
            try {
                IBlockState st = world.getBlockState(new BlockPos(x, y, z));
                net.minecraft.block.material.Material m = st.getMaterial();
                if (m.isSolid() && !m.isLiquid() && !studio.ERM.war.strategy.SiegeTargeting.isManMade(st)) {
                    return y + 1; // stand on top of the first natural terrain block
                }
            } catch (Throwable ignored) {}
        }
        return Math.max(62, top - 10);
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
        // Score all 16 bearings by how much of their staging zone is dry land, then pick RANDOMLY among
        // the ones that are "land enough" (>= 70% of the best), plus a small jitter. This gives the siege
        // a different staging side each time (variety) instead of deterministically the single best one,
        // which made the platform always start in the same spot.
        double[] angles = new double[16];
        int[] lands = new int[16];
        int bestLand = 0;
        for (int i = 0; i < 16; i++) {
            double ang = i * (Math.PI * 2.0 / 16.0);
            angles[i] = ang;
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
            lands[i] = land;
            if (land > bestLand) bestLand = land;
        }
        java.util.List<Double> ok = new ArrayList<>();
        int threshold = (int) Math.ceil(bestLand * 0.7);
        for (int i = 0; i < 16; i++) if (lands[i] >= threshold) ok.add(angles[i]);
        if (ok.isEmpty()) return world.rand.nextDouble() * Math.PI * 2; // all water -> stage anywhere
        double chosen = ok.get(world.rand.nextInt(ok.size()));
        return chosen + (world.rand.nextDouble() - 0.5) * (Math.PI / 16.0); // jitter off the cardinal angle
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

            // Size the pad GENEROUSLY so the WHOLE battle line (wide fronts + catapults + reserves) spawns
            // ON the platform, not off its edge in the water/rough ground ("troops still spawn way off
            // platform"). Extra depth toward the base too, so the line has pad under it as it forms up.
            int nearR = (int) (ENCIRCLE_RING - 14);
            int farR = (int) (ENCIRCLE_RING + 26);
            int halfWidth = 64;

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
        // FIRST strip any trees/vegetation in the camp footprint so the army doesn't deploy buried in a
        // thick jungle (the "platform didn't clear land" report). Runs even on high ground (where the fill
        // below early-returns), so a jungle hilltop camp still gets its canopy + trunks removed.
        clearVegetationColumn(world, x, z);
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

    /** Strip trees + ground vegetation (logs, leaves, plants, vines, cactus, pumpkins, snow) in a camp
     *  column from the pad floor up ~14 blocks, so the staging area is clear ground, not jungle. Routed
     *  through setCampBlock so it's recorded and reverts when the siege ends. */
    private void clearVegetationColumn(World world, int x, int z) {
        int top = surfaceY(world, x, z);
        int hi = Math.max(padY + 14, top + 14);
        for (int y = hi; y >= padY; y--) {
            BlockPos p = new BlockPos(x, y, z);
            try {
                if (world.isAirBlock(p)) continue;
                IBlockState st = world.getBlockState(p);
                net.minecraft.block.material.Material m = st.getMaterial();
                net.minecraft.block.Block b = st.getBlock();
                boolean veg = m == Material.LEAVES || m == Material.PLANTS || m == Material.VINE
                        || m == Material.CACTUS || m == Material.GOURD || m == Material.SNOW
                        || b == Blocks.LOG || b == Blocks.LOG2;
                if (veg) setCampBlock(world, p, Blocks.AIR.getDefaultState());
            } catch (Throwable ignored) {}
        }
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
            IBlockState orig = world.getBlockState(p);
            if (!campOriginals.containsKey(p)) campOriginals.put(p, orig);
            // On CLAIMED land ALSO record a repair order, so /war repair cleans up siege cobblestone
            // (bridges, ramps, rubble) too -- the player ran /war repair and it only cleared the
            // bombardment scaffolds, leaving the engineer cobblestone (which until now only reverted
            // wholesale on siege END via restoreCamp). Both paths revert to the same original; harmless.
            try {
                if (isClaimedLand(world, p)) {
                    WarWorldData data = WarWorldData.get(world);
                    boolean isScaffold = EpochRunnerMod.scaffold != null && orig.getBlock() == EpochRunnerMod.scaffold;
                    if (data != null && !isScaffold && !data.getRepairMap().containsKey(p)) {
                        data.addRepairOrder(p, orig);
                    }
                }
            } catch (Throwable ignored) {}
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
        for (net.minecraft.entity.Entity e : new ArrayList<>(looseUnits)) {
            if (e != null && !e.isDead) { try { e.setDead(); } catch (Throwable ignored) {} }
        }
        looseUnits.clear();
        // Phase-2 tracking. The depot CHESTS + their loot are left standing (the spoils of the siege);
        // only the access ramps revert (they went through setCampBlock -> restoreCamp).
        heatspots.clear();
        capturedSpots.clear();
        lootChests.clear();
        lootDepot = null;
        vehicles.clear();
        carriers.clear();
        frontLine.clear();
        bombardLine.clear();
        bombardSlots.clear();
        engCrews.clear();
        engEscorts.clear();
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
    /** The army's actual CAMP (staging platform) -- what the map's "enemy camp" icon should mark. */
    public BlockPos getStagingCenter() { return stagingCenter != null ? stagingCenter : site; }

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

    /** A unit of engineering work pulled off the shared queue. TUNNEL is the phase-2 staircase dig from the
     *  breach interior up into the base to a bound heat spot; TRIPLE_LADDER is the DEFAULT vertical solution --
     *  three adjacent pillar+ladder columns a crew builds to reach an objective ABOVE or BELOW the entry level
     *  (so several entities climb at once). RAMP is kept for the external VEHICLE route (tanks can't climb).
     *  WIDEN/CORRIDOR are the post-breach jobs (open the gap wide / floor the way in) that used to happen
     *  instantly on breach completion; ACCESS is the interior stair to a loot spot that used to blink in at
     *  the surge. All three are now block-by-block crew work. */
    private enum EngWork { CLEAR, BRIDGE, RAMP, LADDER, BREACH, TUNNEL, TRIPLE_LADDER, WIDEN, CORRIDOR, ACCESS }

    /** ONE physical engineer action -- mine a single block or place a single block. The atom of visible
     *  work: the paced loop performs exactly one of these per swing. */
    private static final class BlockOp {
        final BlockPos pos;
        final IBlockState state; // PLACE only
        final boolean place;
        private BlockOp(BlockPos pos, IBlockState state, boolean place) {
            this.pos = pos.toImmutable(); this.state = state; this.place = place;
        }
        static BlockOp place(BlockPos p, IBlockState s) { return new BlockOp(p, s, true); }
        static BlockOp mine(BlockPos p) { return new BlockOp(p, null, false); }
    }

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
        int priority; // mutable: doctrine (e.g. OCEAN) can re-weight a task before the queue is sorted
        int progress = 0;          // work UNITS consumed (node / slice / row / baked-op index)
        boolean done = false;
        EngCrew claimedBy = null;
        BlockPos shaftSpot = null; // TRIPLE_LADDER only: the objective column the crew builds ladders to
        int shaftBaseY = 0;        // TRIPLE_LADDER only: the entry floor Y (ladders span this <-> shaftSpot.Y)
        // Per-block execution state: the pending ops of the current unit, plus (for WIDEN/CORRIDOR/ACCESS)
        // the full pre-baked op list. lastOpPos/lastOpMine anchor the FX at the exact block just worked.
        final java.util.ArrayDeque<BlockOp> ops = new java.util.ArrayDeque<>();
        java.util.List<BlockOp> baked = null;
        BlockPos lastOpPos = null;
        boolean lastOpMine = false;
        boolean sapperFired = false; // BREACH at L8+: the one real charge, planted once
        EngTask(EngWork work, Obstacle obstacle, int fromIdx, int toIdx, int priority) {
            this.work = work; this.obstacle = obstacle;
            this.fromIdx = fromIdx; this.toIdx = toIdx; this.priority = priority;
        }
    }

    /** One engineer squad that pulls tasks off the shared queue and builds them. */
    private static final class EngCrew {
        final EntityFormationCarrier carrier;
        int id = 0;
        EngTask current = null;
        int workTicks = 0;
        int retreatUntil = 0;     // tickAge before which the crew pulls back from a planted charge
        int reservedAtTick = -1;  // when the current task was reserved (for stuck detection)
        boolean arrived = false;  // logged once it reaches / gives up reaching the work
        boolean hijackChecked = false; // logged the combat-hijack check once
        int lastWorkTick = -1000; // tickAge of the last placed/mined block (human-pace gate)
        // Progress-based march tracking: remote-build only fires when the crew genuinely stops CLOSING on
        // its work with nothing pending that could open the path (waiting at the moat is not a stall).
        double bestDistToStand = Double.MAX_VALUE;
        int noProgressTicks = 0;
        int marchTicks = 0;
        boolean remoteLogged = false;
        EngCrew(EntityFormationCarrier carrier) { this.carrier = carrier; }
    }

    /** An in-flight catapult round: the visual cobblestone block + where/when it detonates. */
    private static final class CatapultShot {
        final EntityFallingBlock block;
        final BlockPos target;
        final int impactTick;
        int lastX, lastY = Integer.MIN_VALUE, lastZ; // last live position (for cleaning the landed block)
        // The support cobblestone placed at the LAUNCH position (EntityFallingBlock dies on its first
        // tick unless the block exists there). It was NEVER removed -- one floating cobblestone per
        // shot piling up above every catapult, the "/war repair still leaves cobblestone" leak.
        BlockPos launchPos = null;
        int launchTick = 0;
        CatapultShot(EntityFallingBlock block, BlockPos target, int impactTick) {
            this.block = block;
            this.target = target;
            this.impactTick = impactTick;
        }
    }
}
