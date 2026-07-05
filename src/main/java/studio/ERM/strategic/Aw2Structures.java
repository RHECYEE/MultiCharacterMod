package studio.ERM.strategic;

import net.minecraft.util.EnumFacing;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.WorldServer;
import studio.ERM.EpochRunnerMod;
import studio.ERM.war.config.SchematicCatalog;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Random;
import java.util.Set;

/**
 * THE ONE SHARED AW2 STRUCTURE BRIDGE. Every system that raises a building — strategic resource
 * camps (AI and player), nation states, mission camps, siege works — places real AW2 .aws schematics
 * through here. NO procedural tents, NO bespoke per-system placement code: the rival city already
 * proved the template path, so everyone uses it.
 *
 * Selection doctrine (two tiers, never fails while AW2 has ANY templates loaded):
 *   1. The SchematicCatalog category (the user's AW2_Schematic_Organization.xlsx export) — the
 *      curated pick when the catalog is populated.
 *   2. KEYWORD substring match over EVERYTHING AW2 actually has loaded (the rival city's
 *      findValidTemplate doctrine) — so an empty/missing catalog still yields a real building.
 *
 * All AW2 access is guarded (catch Throwable): if AW2 is absent every call is a clean no-op/null.
 */
public final class Aw2Structures {

    private static final Random RNG = new Random();

    private Aw2Structures() {}

    /** Every template name AW2 has loaded right now (empty set when AW2 is absent). */
    public static Set<String> loaded() {
        try {
            Class.forName("net.shadowmage.ancientwarfare.structure.template.StructureTemplateManager");
            Set<String> t = net.shadowmage.ancientwarfare.structure.template.StructureTemplateManager.getTemplates();
            return t != null ? t : Collections.<String>emptySet();
        } catch (Throwable t) {
            return Collections.emptySet();
        }
    }

    /**
     * Pick a template: catalog category first, then keyword substring match over all loaded
     * templates. Returns null ONLY when AW2 has nothing loaded at all (or no keyword matched and
     * the catalog is empty) — callers should then SKIP construction, never tent it.
     */
    public static String pick(String catalogCategory, String nationOrNull, String[] keywordFallback) {
        try {
            if (catalogCategory != null) {
                String fromCatalog = SchematicCatalog.pick(catalogCategory, nationOrNull);
                if (fromCatalog != null) return fromCatalog;
            }
            Set<String> all = loaded();
            if (all.isEmpty() || keywordFallback == null || keywordFallback.length == 0) return null;
            List<String> matches = new ArrayList<>();
            for (String name : all) {
                String low = name.toLowerCase();
                for (String kw : keywordFallback) {
                    if (low.contains(kw)) { matches.add(name); break; }
                }
            }
            return matches.isEmpty() ? null : matches.get(RNG.nextInt(matches.size()));
        } catch (Throwable t) {
            return null;
        }
    }

    /** Place one template at {@code anchor} (top-surface block). Chunk must be loaded (no force-load
     *  — construction WAITS for the world to arrive; see ResourceCampManager's pending-build state). */
    public static boolean place(WorldServer world, String templateName, BlockPos anchor, EnumFacing facing) {
        if (world == null || templateName == null || anchor == null) return false;
        if (!world.isBlockLoaded(anchor, false)) return false;
        try {
            java.util.Optional<net.shadowmage.ancientwarfare.structure.template.StructureTemplate> opt =
                    net.shadowmage.ancientwarfare.structure.template.StructureTemplateManager.getTemplate(templateName);
            if (!opt.isPresent()) return false;
            new net.shadowmage.ancientwarfare.structure.template.build.StructureBuilderWorldGen(
                    world, opt.get(), facing != null ? facing : EnumFacing.NORTH, anchor)
                    .instantConstruction();
            EpochRunnerMod.logger.info("[AW2] placed '" + templateName + "' @ "
                    + anchor.getX() + "," + anchor.getY() + "," + anchor.getZ());
            return true;
        } catch (Throwable t) {
            EpochRunnerMod.logger.warn("[AW2] failed to place '" + templateName + "': " + t);
            return false;
        }
    }

    public static boolean placeRandomFacing(WorldServer world, String templateName, BlockPos anchor) {
        return place(world, templateName, anchor, EnumFacing.HORIZONTALS[RNG.nextInt(4)]);
    }

    /** A buildable surface near {@code around}: loaded, top-solid, not over liquid. Null when unloaded. */
    public static BlockPos surfaceNear(WorldServer world, BlockPos around, int scatter) {
        try {
            BlockPos probe = scatter <= 0 ? around
                    : around.add(RNG.nextInt(scatter * 2 + 1) - scatter, 0, RNG.nextInt(scatter * 2 + 1) - scatter);
            BlockPos at = new BlockPos(probe.getX(), 64, probe.getZ());
            if (!world.isBlockLoaded(at, false)) return null;
            BlockPos s = world.getTopSolidOrLiquidBlock(at);
            return world.getBlockState(s.down()).getMaterial().isLiquid() ? null : s;
        } catch (Throwable t) {
            return null;
        }
    }
}
