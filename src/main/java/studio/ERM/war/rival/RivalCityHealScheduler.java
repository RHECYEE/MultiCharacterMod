package studio.ERM.war.rival;

import net.minecraft.init.Blocks;
import net.minecraft.util.EnumFacing;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import net.minecraftforge.fml.common.gameevent.TickEvent;
import studio.ERM.EpochRunnerMod;

import java.util.*;

/**
 * Handles gradual city healing over time.
 *
 * When /war rival heal is used, terrain is restored immediately but buildings
 * are rebuilt in batches over ~5 minutes. Each batch places a few buildings
 * with a chat notification so the player can watch the city rebuild.
 *
 * Uses Forge's ServerTickEvent to count ticks between batches.
 */
@Mod.EventBusSubscriber(modid = EpochRunnerMod.MODID)
public class RivalCityHealScheduler {

    private static final List<HealJob> activeJobs = new ArrayList<>();
    private static final Random rand = new Random();

    /**
     * Schedule a gradual rebuild.
     *
     * @param world          The world
     * @param city           The city data
     * @param destroyedPos   Positions of destroyed buildings
     * @param perBatch       How many buildings to place per batch
     * @param ticksBetween   Ticks between each batch (200 = 10 seconds)
     */
    public static void schedule(World world, RivalCityData city, List<BlockPos> destroyedPos,
                                int perBatch, int ticksBetween) {
        if (destroyedPos == null || destroyedPos.isEmpty()) return;

        HealJob job = new HealJob();
        job.worldDim = world.provider.getDimension();
        job.city = city;
        job.remaining = new ArrayList<>(destroyedPos);
        job.perBatch = Math.max(1, perBatch);
        job.ticksBetween = Math.max(20, ticksBetween); // minimum 1 second
        job.ticksUntilNext = 60; // First batch after 3 seconds

        Collections.shuffle(job.remaining); // Random order for visual effect

        synchronized (activeJobs) {
            // Remove any existing job for the same city
            activeJobs.removeIf(j -> j.city == city);
            activeJobs.add(job);
        }

        EpochRunnerMod.logger.info("[RivalCity] Heal scheduled: {} buildings in batches of {} every {} ticks",
                destroyedPos.size(), perBatch, ticksBetween);
    }

    @SubscribeEvent
    public static void onServerTick(TickEvent.ServerTickEvent event) {
        if (event.phase != TickEvent.Phase.END) return;

        synchronized (activeJobs) {
            Iterator<HealJob> it = activeJobs.iterator();
            while (it.hasNext()) {
                HealJob job = it.next();

                job.ticksUntilNext--;
                if (job.ticksUntilNext > 0) continue;

                // Time to place a batch
                job.ticksUntilNext = job.ticksBetween;

                // Get the world
                World world = net.minecraftforge.fml.common.FMLCommonHandler.instance()
                        .getMinecraftServerInstance()
                        .getWorld(job.worldDim);

                if (world == null) {
                    it.remove();
                    continue;
                }

                int placed = processBatch(world, job);

                if (placed > 0) {
                    // Notify players near the city
                    BlockPos center = job.city.getCenter();
                    if (center != null) {
                        String msg = "§6[WarEngine] §eRival city rebuilding... §f" + placed + " buildings restored. §7("
                                + job.remaining.size() + " remaining)";
                        for (net.minecraft.entity.player.EntityPlayer p : world.playerEntities) {
                            if (p.getDistanceSq(center) < 200 * 200) {
                                p.sendMessage(new net.minecraft.util.text.TextComponentString(msg));
                            }
                        }
                    }
                }

                // Job complete?
                if (job.remaining.isEmpty()) {
                    EpochRunnerMod.logger.info("[RivalCity] Heal complete for city at {}",
                            job.city.getCenter());

                    // Final notification
                    BlockPos center = job.city.getCenter();
                    if (center != null) {
                        String msg = "§6[WarEngine] §a★ Rival city rebuild complete!";
                        for (net.minecraft.entity.player.EntityPlayer p : world.playerEntities) {
                            if (p.getDistanceSq(center) < 200 * 200) {
                                p.sendMessage(new net.minecraft.util.text.TextComponentString(msg));
                            }
                        }
                    }

                    it.remove();
                }
            }
        }
    }

    private static int processBatch(World world, HealJob job) {
        int placed = 0;
        int toPlace = Math.min(job.perBatch, job.remaining.size());

        // Get growth template pool
        List<String> pool = Collections.emptyList();
        if (RivalCityAW2Bridge.isAW2Available()) {
            Set<String> all = RivalCityAW2Bridge.listTemplates();
            if (!all.isEmpty()) {
                pool = new ArrayList<>();
                for (String t : all) {
                    if (!isLargeTemplate(t)) pool.add(t);
                }
            }
        }

        // Use local static Random (RivalCityManager.rand is private)

        for (int i = 0; i < toPlace && !job.remaining.isEmpty(); i++) {
            BlockPos pos = job.remaining.remove(0);

            // Get surface Y
            int surfaceY = world.getHeight(pos).getY();
            BlockPos surfacePos = new BlockPos(pos.getX(), surfaceY, pos.getZ());

            // Place dirt pad first
            placePad(world, surfacePos, 10);

            // Place building
            boolean success = false;

            if (!pool.isEmpty()) {
                String template = pool.get(rand.nextInt(pool.size()));
                EnumFacing facing = EnumFacing.HORIZONTALS[rand.nextInt(4)];
                success = RivalCityAW2Bridge.placeTemplate(world, template, surfacePos, facing);
                if (success) {
                    job.city.addBuilding(surfacePos, template, facing);
                }
            }

            if (!success) {
                // Procedural fallback
                try {
                    EnumFacing facing = EnumFacing.HORIZONTALS[rand.nextInt(4)];
                    int type = rand.nextInt(3);
                    switch (type) {
                        case 0: ProceduralBuildingGenerator.generateSkyscraper(world, surfacePos, job.city.getLevel(), facing); break;
                        case 1: ProceduralBuildingGenerator.generateFactory(world, surfacePos, job.city.getLevel(), facing); break;
                        case 2: ProceduralBuildingGenerator.generateSilo(world, surfacePos, job.city.getLevel()); break;
                    }
                    success = true;
                } catch (Throwable ignored) {}
            }

            if (success) placed++;
        }

        return placed;
    }

    private static void placePad(World world, BlockPos center, int radius) {
        for (int dx = -radius; dx <= radius; dx++) {
            for (int dz = -radius; dz <= radius; dz++) {
                if (dx * dx + dz * dz > radius * radius) continue;

                BlockPos col = new BlockPos(center.getX() + dx, center.getY(), center.getZ() + dz);
                world.setBlockState(col, Blocks.GRASS.getDefaultState(), 2);
                for (int d = 1; d <= 4; d++) {
                    world.setBlockState(col.down(d), Blocks.DIRT.getDefaultState(), 2);
                }
                for (int d = 5; d <= 6; d++) {
                    if (world.isAirBlock(col.down(d)) || world.getBlockState(col.down(d)).getBlock() == Blocks.WATER) {
                        world.setBlockState(col.down(d), Blocks.STONE.getDefaultState(), 2);
                    }
                }
            }
        }
    }

    private static boolean isLargeTemplate(String name) {
        String lower = name.toLowerCase();
        String[] big = {"castle", "citadel", "fortress", "fort", "walledcity", "palace",
                "pyramid", "colosseum", "cathedral", "keep", "stronghold",
                "airship", "balloon", "ship", "galleon", "aqueduct", "bridge"};
        for (String kw : big) {
            if (lower.contains(kw)) return true;
        }
        return false;
    }

    private static class HealJob {
        int worldDim;
        RivalCityData city;
        List<BlockPos> remaining;
        int perBatch;
        int ticksBetween;
        int ticksUntilNext;
    }
}
