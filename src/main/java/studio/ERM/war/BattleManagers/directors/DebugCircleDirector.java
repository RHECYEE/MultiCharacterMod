package studio.ERM.war.BattleManagers.directors;

import net.minecraft.block.Block;
import net.minecraft.block.state.IBlockState;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.init.Blocks;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;
import studio.ERM.war.BattleManagers.api.BattleOutcome;
import studio.ERM.war.BattleManagers.api.IBattleDirector;
import studio.ERM.war.BattleManagers.cards.UnitCard;
import studio.ERM.war.BattleManagers.entities.EntityFormationCarrier;

/**
 * Debug director:
 * - Levels (clears) a large area around the battle site to make observation easy.
 * - Spawns THREE formation carriers that orbit indefinitely to stress-test LOD / release mechanics.
 *
 * This is intentionally aggressive and is meant only for development and tuning.
 */
public class DebugCircleDirector implements IBattleDirector {

    private final UnitCard card;

    private EntityPlayer activator;
    private BlockPos site;

    private EntityFormationCarrier carrierA;
    private EntityFormationCarrier carrierB;
    private EntityFormationCarrier carrierC;

    private boolean finished = false;
    private BattleOutcome outcome = BattleOutcome.ABORTED;

    // Debug leveling tuning
    private static final int CLEAR_RADIUS = 48;   // blocks
    private static final int CLEAR_UP = 28;       // blocks above surface
    private static final int CLEAR_DOWN = 6;      // blocks below surface
    private static final int FLAT_DEPTH = 3;      // dirt layers below surface

    public DebugCircleDirector(UnitCard card) {
        this.card = card;
    }

    @Override
    public void start(World world, EntityPlayer activator, BlockPos battleSite) {
        this.activator = activator;
        this.site = battleSite;

        if (world.isRemote) return;

        // Pick a stable surface around the site.
        BlockPos surface = world.getTopSolidOrLiquidBlock(new BlockPos(battleSite.getX(), 64, battleSite.getZ()));
        BlockPos flatCenter = new BlockPos(battleSite.getX(), surface.getY(), battleSite.getZ());

        // Level / clear the debug arena.
        clearAndFlatten(world, flatCenter, CLEAR_RADIUS, CLEAR_UP, CLEAR_DOWN, FLAT_DEPTH);

        // Spawn 3 carriers with different orbit radii/speeds for visibility.
        carrierA = spawnCarrier(world, flatCenter.add(10, 1, 0), 1, 14.0D, 0.045D);
        carrierB = spawnCarrier(world, flatCenter.add(-10, 1, 0), 2, 20.0D, -0.040D);
        carrierC = spawnCarrier(world, flatCenter.add(0, 1, 10), 3, 26.0D, 0.035D);
    }

    private EntityFormationCarrier spawnCarrier(World world, BlockPos spawnPos, int seed, double orbitRadius, double angularSpeed) {
        EntityFormationCarrier c = new EntityFormationCarrier(world);
        c.setPosition(spawnPos.getX() + 0.5, spawnPos.getY() + 1.0, spawnPos.getZ() + 0.5);
        c.configureFromCard(card, seed);
        c.setBattleContext(activator, site);
        c.setOrbitMode(site, orbitRadius, angularSpeed, false);  // false = allow puppet release near player
        world.spawnEntity(c);
        return c;
    }

    @Override
    public void tick(World world) {
        if (world.isRemote) return;
        if (finished) return;

        // If player is gone, abort (stop will clean carriers).
        if (activator == null || activator.isDead) {
            forceResolve(BattleOutcome.ABORTED);
            return;
        }

        boolean anyAlive = false;

        if (carrierA != null && !carrierA.isDead) {
            carrierA.setBattleContext(activator, site);
            carrierA.setOrbitMode(site, 14.0D, 0.045D, false);
            anyAlive = true;
        }

        if (carrierB != null && !carrierB.isDead) {
            carrierB.setBattleContext(activator, site);
            carrierB.setOrbitMode(site, 20.0D, -0.040D, false);
            anyAlive = true;
        }

        if (carrierC != null && !carrierC.isDead) {
            carrierC.setBattleContext(activator, site);
            carrierC.setOrbitMode(site, 26.0D, 0.035D, false);
            anyAlive = true;
        }

        // If all carriers died, treat as defeat so caller can observe behavior.
        if (!anyAlive) {
            forceResolve(BattleOutcome.DEFEAT);
        }
    }

    @Override
    public boolean isFinished() {
        return finished;
    }

    @Override
    public void stop(World world) {
        if (world.isRemote) return;

        // For debug we remove the carriers (they're director-owned) but we intentionally leave released units.
        if (carrierA != null && !carrierA.isDead) carrierA.setDead();
        if (carrierB != null && !carrierB.isDead) carrierB.setDead();
        if (carrierC != null && !carrierC.isDead) carrierC.setDead();
    }

    @Override
    public String getDirectorId() {
        return "debug_circle";
    }

    public void forceResolve(BattleOutcome outcome) {
        this.outcome = outcome == null ? BattleOutcome.ABORTED : outcome;
        this.finished = true;
    }

    public BattleOutcome getOutcome() {
        return outcome;
    }

    private static void clearAndFlatten(World world, BlockPos center, int radius, int up, int down, int flatDepth) {
        if (world == null || center == null) return;

        int cx = center.getX();
        int cy = center.getY();
        int cz = center.getZ();

        IBlockState air = Blocks.AIR.getDefaultState();
        IBlockState grass = Blocks.GRASS.getDefaultState();
        IBlockState dirt = Blocks.DIRT.getDefaultState();

        int rSq = radius * radius;

        for (int dx = -radius; dx <= radius; dx++) {
            for (int dz = -radius; dz <= radius; dz++) {
                int distSq = (dx * dx) + (dz * dz);
                if (distSq > rSq) continue;

                int x = cx + dx;
                int z = cz + dz;

                // Flatten: surface grass + dirt below
                for (int d = 0; d < flatDepth; d++) {
                    BlockPos p = new BlockPos(x, cy - d, z);
                    if (d == 0) {
                        safeSet(world, p, grass);
                    } else {
                        safeSet(world, p, dirt);
                    }
                }

                // Clear air above (and a bit below surface for trenches/holes)
                for (int y = cy - down; y <= cy + up; y++) {
                    BlockPos p = new BlockPos(x, y, z);

                    // Don't nuke bedrock.
                    Block b = world.getBlockState(p).getBlock();
                    if (b == Blocks.BEDROCK) continue;

                    // Keep our flattened ground layers intact.
                    if (y <= cy && y >= (cy - flatDepth + 1)) continue;

                    safeSet(world, p, air);
                }
            }
        }
    }

    private static void safeSet(World world, BlockPos pos, IBlockState state) {
        try {
            world.setBlockState(pos, state, 2);
        } catch (Throwable ignored) {
        }
    }
}
