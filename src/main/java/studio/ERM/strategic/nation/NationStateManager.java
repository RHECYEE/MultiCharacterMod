package studio.ERM.strategic.nation;

import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.util.EnumFacing;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.ChunkPos;
import net.minecraft.util.text.TextComponentString;
import net.minecraft.util.text.TextFormatting;
import net.minecraft.world.WorldServer;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import net.minecraftforge.fml.common.gameevent.TickEvent;
import studio.ERM.EpochRunnerMod;
import studio.ERM.strategic.civil.DistrictRegistry;
import studio.ERM.strategic.patrol.PatrolFramework;
import studio.ERM.war.config.SchematicCatalog;
import studio.ERM.war.world.WarWorldData;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Random;

/**
 * NATION STATES — the SECOND AI civilization. The Rival Empire competes; nations POPULATE.
 * Intentionally shallow individually, numerous collectively: static settlements, generated
 * "noise" (patrols, traders — couriers/travelers/diplomacy later), no economy simulation.
 *
 *   SPAWN  — when the rival city is seeded (/war rival city), FOUR nations of shuffled cultures
 *            spawn on a ring around the player (the rival takes one bearing; nations fill the
 *            rest), claim a small land halo (blue "Other Land" on the war map) and pitch a
 *            TRIBAL CAMP from the catalog's Level 0 templates.
 *   TIER 2 — the first time the RIVAL reaches level 2+, each nation builds its permanent town
 *            ONCE from its culture's schematic packs and then NEVER grows again (the rival keeps
 *            expanding/repairing; nations stay static — that's the design line between them).
 *   NOISE  — every few minutes a nation dispatches a guard patrol (PatrolFramework, era skins)
 *            and occasionally a strategic trader caravan routed past the player's settlement.
 */
public final class NationStateManager {

    private static final int RING_MIN = 520, RING_MAX = 760;
    private static final int NATION_COUNT = 4;
    private static final int CAMP_CLAIM_CHUNKS = 2, TOWN_CLAIM_CHUNKS = 4;
    private static final long PATROL_EVERY = 7 * 60 * 20L;   // ~7 min per nation
    private static final long TRADER_EVERY = 18 * 60 * 20L;  // ~18 min per nation
    private static final Random RNG = new Random();

    private NationStateManager() {}

    private static int tickCounter = 0;

    // ==================================================================
    // SPAWN — the ring, called from /war rival city after the rival seeds
    // ==================================================================

    public static void spawnRing(WorldServer world, EntityPlayer player, BlockPos rivalCenter) {
        NationStateData data = NationStateData.get(world);
        if (!data.nations.isEmpty()) return; // one ring per world — nations are permanent

        // The rival owns its bearing; nations take evenly spaced bearings around the rest.
        double rivalAng = Math.atan2(rivalCenter.getZ() - player.posZ, rivalCenter.getX() - player.posX);
        List<NationCulture> cultures = new ArrayList<>(Arrays.asList(NationCulture.values()));
        Collections.shuffle(cultures, RNG);

        for (int i = 0; i < NATION_COUNT && i < cultures.size(); i++) {
            double ang = rivalAng + Math.PI * 2 * (i + 1) / (NATION_COUNT + 1);
            int dist = RING_MIN + RNG.nextInt(RING_MAX - RING_MIN);
            BlockPos probe = new BlockPos(player.posX + Math.cos(ang) * dist, 64,
                    player.posZ + Math.sin(ang) * dist);

            NationStateData.Nation n = new NationStateData.Nation();
            n.culture = cultures.get(i).ordinal();
            n.name = cultures.get(i).rollName(RNG);
            n.center = world.isBlockLoaded(probe, false)
                    ? world.getTopSolidOrLiquidBlock(probe) : probe.up(6);
            n.tier = 1;
            data.nations.add(n);

            claimHalo(world, n, CAMP_CLAIM_CHUNKS);
            buildCamp(world, n);
            player.sendMessage(new TextComponentString(TextFormatting.GOLD + n.name
                    + TextFormatting.GRAY + " (" + n.culture().displayName + ") has settled "
                    + (int) Math.sqrt(n.center.distanceSq(player.getPosition())) + "m to your "
                    + bearing(ang) + "."));
            EpochRunnerMod.logger.info("[Nations] " + n.name + " (" + n.culture().displayName
                    + ") settled @ " + n.center.getX() + "," + n.center.getZ());
        }
        data.markDirty();
    }

    /** Nation land shows as the map's blue "Other Land" — a real claim, just not RIVAL/player. */
    private static void claimHalo(WorldServer world, NationStateData.Nation n, int radiusChunks) {
        WarWorldData war = WarWorldData.get(world);
        ChunkPos c = new ChunkPos(n.center);
        for (int dx = -radiusChunks; dx <= radiusChunks; dx++) {
            for (int dz = -radiusChunks; dz <= radiusChunks; dz++) {
                ChunkPos p = new ChunkPos(c.x + dx, c.z + dz);
                if ("NEUTRAL".equals(war.getOwner(p))) war.setOwner(p, "NATION:" + n.name);
            }
        }
    }

    /** Tier 1: a tribal camp — a few Level 0 templates pitched around the home block, or simple
     *  procedural teepees when no .aws templates are loaded (so the nation is always VISIBLE). */
    private static void buildCamp(WorldServer world, NationStateData.Nation n) {
        List<String> pool = SchematicCatalog.existing(SchematicCatalog.TRIBAL, null);
        int placed = 0;
        for (int i = 0; i < 3; i++) {
            BlockPos at = surfaceNear(world, n.center, i == 0 ? 0 : 14 + RNG.nextInt(10));
            if (at == null) continue;
            if (!pool.isEmpty() && placeTemplate(world, pool.get(RNG.nextInt(pool.size())), at)) placed++;
            else { buildFallbackTent(world, at); placed++; } // no template -> a procedural teepee
        }
        EpochRunnerMod.logger.info("[Nations] " + n.name + " camp: " + placed + " tent(s) pitched"
                + (pool.isEmpty() ? " (procedural — no Tribal templates loaded)" : ""));
    }

    /** A simple procedural teepee: a central spruce-log pole inside a tapering brown-wool cone, with
     *  a front door gap and a small fire pit. Reads as a tribal tent without any schematic assets. */
    private static void buildFallbackTent(WorldServer world, BlockPos c) {
        try {
            net.minecraft.block.state.IBlockState log = net.minecraft.init.Blocks.LOG.getStateFromMeta(1); // spruce
            net.minecraft.block.state.IBlockState wool = net.minecraft.init.Blocks.WOOL.getStateFromMeta(12); // brown
            // Cone walls: radius-2 ring at the base, radius-1 ring above.
            for (int dx = -2; dx <= 2; dx++) for (int dz = -2; dz <= 2; dz++) {
                if (Math.abs(dx) == 2 || Math.abs(dz) == 2) world.setBlockState(c.add(dx, 0, dz), wool, 2);
            }
            for (int dx = -1; dx <= 1; dx++) for (int dz = -1; dz <= 1; dz++) {
                if (Math.abs(dx) == 1 || Math.abs(dz) == 1) world.setBlockState(c.add(dx, 1, dz), wool, 2);
            }
            // Central pole poking out the top.
            for (int dy = 0; dy <= 3; dy++) world.setBlockState(c.up(dy), log, 2);
            // Door gap (south face) + a fire pit at the entrance.
            world.setBlockToAir(c.add(0, 0, 2));
            world.setBlockToAir(c.add(0, 1, 2));
            world.setBlockState(c.add(0, 0, 3), net.minecraft.init.Blocks.NETHERRACK.getDefaultState(), 2);
            world.setBlockState(c.add(0, 1, 3), net.minecraft.init.Blocks.FIRE.getDefaultState(), 2);
        } catch (Throwable ignored) {}
    }

    /**
     * Tier 2 — THE one-shot town. Runs once when the rival reaches level 2: a ring of the
     * culture's own schematics (its architecture is its identity), a wider claim halo, then the
     * nation is FINISHED — it never expands, colonizes or rebuilds (unlike the rival).
     */
    private static void buildTown(WorldServer world, NationStateData.Nation n) {
        NationCulture culture = n.culture();
        List<String> pool = new ArrayList<>();
        for (String pack : culture.packs) {
            pool.addAll(SchematicCatalog.existing(SchematicCatalog.NATION_STATES, pack));
            pool.addAll(SchematicCatalog.existing(SchematicCatalog.OUTPOSTS_CAMPS, pack));
        }
        if (pool.isEmpty()) pool = SchematicCatalog.existing(SchematicCatalog.NATION_STATES, null);
        if (pool.isEmpty()) {
            // No town templates loaded -> a cluster of procedural teepees so the town is still visible.
            for (int i = 0; i < 6; i++) {
                double ang = Math.PI * 2 * i / 6;
                BlockPos at = surfaceNear(world, n.center.add((int) (Math.cos(ang) * (18 + i * 4)), 0,
                        (int) (Math.sin(ang) * (18 + i * 4))), 3);
                if (at != null) buildFallbackTent(world, at);
            }
            EpochRunnerMod.logger.info("[Nations] " + n.name + " town: procedural (no town templates loaded)");
        } else {
            int placed = 0;
            // Two rings of lots around the centre: 4 close, 4 far, offset bearings.
            for (int i = 0; i < 8; i++) {
                double ang = Math.PI * 2 * i / 8 + (i >= 4 ? Math.PI / 8 : 0);
                int r = i < 4 ? 22 : 44;
                BlockPos at = surfaceNear(world,
                        n.center.add((int) (Math.cos(ang) * r), 0, (int) (Math.sin(ang) * r)), 0);
                if (at != null && placeTemplate(world, pool.get(RNG.nextInt(pool.size())), at)) placed++;
            }
            EpochRunnerMod.logger.info("[Nations] " + n.name + " built its town (" + placed + " structures)");
        }
        claimHalo(world, n, TOWN_CLAIM_CHUNKS);
        n.tier = 2;
        for (EntityPlayer p : world.playerEntities) {
            p.sendMessage(new TextComponentString(TextFormatting.GOLD + n.name + TextFormatting.GRAY
                    + " has grown into a permanent town. It will hold this land, no more."));
        }
    }

    /** Same guarded AW2 placement the rival city uses — one template, instant, missing = false. */
    private static boolean placeTemplate(WorldServer world, String templateName, BlockPos anchor) {
        try {
            java.util.Optional<net.shadowmage.ancientwarfare.structure.template.StructureTemplate> opt =
                    net.shadowmage.ancientwarfare.structure.template.StructureTemplateManager.getTemplate(templateName);
            if (!opt.isPresent()) return false;
            new net.shadowmage.ancientwarfare.structure.template.build.StructureBuilderWorldGen(
                    world, opt.get(), EnumFacing.HORIZONTALS[RNG.nextInt(4)], anchor)
                    .instantConstruction();
            return true;
        } catch (Throwable t) {
            return false;
        }
    }

    private static BlockPos surfaceNear(WorldServer world, BlockPos around, int scatter) {
        BlockPos probe = scatter <= 0 ? around
                : around.add(RNG.nextInt(scatter * 2 + 1) - scatter, 0, RNG.nextInt(scatter * 2 + 1) - scatter);
        BlockPos at = new BlockPos(probe.getX(), 64, probe.getZ());
        if (!world.isBlockLoaded(at, false)) return null;
        BlockPos s = world.getTopSolidOrLiquidBlock(at);
        return world.getBlockState(s.down()).getMaterial().isLiquid() ? null : s;
    }

    private static String bearing(double ang) {
        String[] dirs = {"east", "southeast", "south", "southwest", "west", "northwest", "north", "northeast"};
        int idx = (int) Math.floor(((ang % (Math.PI * 2) + Math.PI * 2) % (Math.PI * 2)) / (Math.PI / 4) + 0.5) % 8;
        return dirs[idx];
    }

    // ==================================================================
    // THE NOISE LOOP — tier upgrades, patrols, traders
    // ==================================================================

    @SubscribeEvent
    public static void onWorldTick(TickEvent.WorldTickEvent e) {
        if (e.phase != TickEvent.Phase.END || e.side.isClient() || !(e.world instanceof WorldServer)) return;
        if (++tickCounter % 200 != 0) return;
        WorldServer world = (WorldServer) e.world;

        NationStateData data = NationStateData.get(world);
        if (data.nations.isEmpty()) return;
        long now = world.getTotalWorldTime();
        int rivalLevel = DistrictRegistry.rivalLevel(world);

        for (NationStateData.Nation n : data.nations) {
          // DEFENSIVE: one nation's failure (a bad AW2 build, a patrol/skin NPE) must never crash the
          // world tick and take the server down — the "nation states cause null crashes" report.
          try {
            if (n == null || n.center == null) continue;
            // TIER 2: the rival advancing to level 2 turns every camp into its permanent town, once.
            if (n.tier < 2 && rivalLevel >= 2) {
                try { buildTown(world, n); } catch (Throwable t) {
                    EpochRunnerMod.logger.error("[Nations] buildTown failed for " + n.name, t);
                    n.tier = 2; // don't retry a crashing build every pass
                }
                data.markDirty();
            }

            // PATROL NOISE: a small guard patrol wanders out from the settlement (era skins applied
            // by the framework). If the player destroys it, the clock simply rolls another later.
            if (now - n.lastPatrol > PATROL_EVERY && RNG.nextInt(3) == 0) {
                n.lastPatrol = now;
                try {
                    if (PatrolFramework.spawnAt(world, "nation_guard_patrol", n.center)
                            && PatrolFramework.VERBOSE) {
                        EpochRunnerMod.logger.info("[Nations] " + n.name + " dispatched a patrol");
                    }
                } catch (Throwable t) {
                    EpochRunnerMod.logger.error("[Nations] patrol dispatch failed for " + n.name, t);
                }
            }

            // TRADER NOISE: a strategic caravan routed from the nation PAST the player — commerce
            // the player can watch materialize on the road (the sim already handles the rest).
            if (now - n.lastTrader > TRADER_EVERY && RNG.nextInt(3) == 0 && !world.playerEntities.isEmpty()) {
                n.lastTrader = now;
                try {
                    EntityPlayer p = world.playerEntities.get(0);
                    studio.ERM.strategic.StrategicTrader t = new studio.ERM.strategic.StrategicTrader();
                    t.level = Math.max(1, rivalLevel);
                    t.escorts = rivalLevel >= 3 ? 2 : 0;
                    t.route.add(new BlockPos(n.center.getX(), 0, n.center.getZ()));
                    t.route.add(new BlockPos((int) p.posX + RNG.nextInt(60) - 30, 0,
                            (int) p.posZ + RNG.nextInt(60) - 30));
                    t.x = n.center.getX() + 0.5;
                    t.z = n.center.getZ() + 0.5;
                    studio.ERM.strategic.StrategicMapData.get(world).add(t);
                    EpochRunnerMod.logger.info("[Nations] trader caravan departed " + n.name);
                } catch (Throwable ignored) {}
            }
          } catch (Throwable outer) {
            EpochRunnerMod.logger.error("[Nations] per-nation tick failed (guarded, no crash)", outer);
          }
        }
    }
}
