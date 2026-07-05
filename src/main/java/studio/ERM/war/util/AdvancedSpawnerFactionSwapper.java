package studio.ERM.war.util;

import net.minecraft.block.Block;
import net.minecraft.nbt.NBTBase;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.nbt.NBTTagList;
import net.minecraft.nbt.NBTTagString;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.util.ResourceLocation;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;
import net.minecraftforge.fml.common.registry.ForgeRegistries;

/**
 * AdvancedSpawnerFactionSwapper
 *
 * Post-processes Ancient Warfare 2 Advanced Spawner tiles after a structure/template is placed.
 *
 * Goal:
 * - Force spawned NPCs to use a target AW2 faction (e.g., "empire") regardless of what the template
 *   originally had, while preserving NPC subtype/role (soldier, siege_engineer, townsfolk, etc.).
 *
 * How it preserves subtype:
 * - We ONLY rewrite NBT string keys named "factionName" inside the spawner TE NBT.
 * - We do NOT touch entityId, npcTypeName, equipment, or any other fields.
 */
public final class AdvancedSpawnerFactionSwapper {

    private static final ResourceLocation AW2_ADV_SPAWNER_BLOCK = new ResourceLocation("ancientwarfarestructure", "advanced_spawner");

    private AdvancedSpawnerFactionSwapper() {}

    /**
     * Scans a bounding box (inclusive) and swaps any Advanced Spawner TE "factionName" strings to targetFaction.
     *
     * @return number of spawner TEs updated
     */
    public static int swapSpawnerFactionsInBox(World world, BlockPos min, BlockPos max, String targetFaction) {
        if (world == null || min == null || max == null) return 0;
        if (targetFaction == null || targetFaction.trim().isEmpty()) return 0;

        final BlockPos a = new BlockPos(
                Math.min(min.getX(), max.getX()),
                Math.min(min.getY(), max.getY()),
                Math.min(min.getZ(), max.getZ())
        );
        final BlockPos b = new BlockPos(
                Math.max(min.getX(), max.getX()),
                Math.max(min.getY(), max.getY()),
                Math.max(min.getZ(), max.getZ())
        );

        int changed = 0;

        for (int x = a.getX(); x <= b.getX(); x++) {
            for (int y = a.getY(); y <= b.getY(); y++) {
                for (int z = a.getZ(); z <= b.getZ(); z++) {
                    BlockPos pos = new BlockPos(x, y, z);
                    if (!world.isBlockLoaded(pos)) continue;

                    Block block = world.getBlockState(pos).getBlock();
                    ResourceLocation key = ForgeRegistries.BLOCKS.getKey(block);
                    if (!AW2_ADV_SPAWNER_BLOCK.equals(key)) continue;

                    TileEntity te = world.getTileEntity(pos);
                    if (te == null) continue;

                    if (swapFactionInSpawnerTE(te, targetFaction)) {
                        changed++;
                        te.markDirty();
                        world.notifyBlockUpdate(pos, world.getBlockState(pos), world.getBlockState(pos), 3);
                    }
                }
            }
        }

        return changed;
    }

    /**
     * Convenience helper: scans a radius cube around a center.
     */
    public static int swapSpawnerFactionsNear(World world, BlockPos center, int radiusXZ, int minY, int maxY, String targetFaction) {
        if (center == null) return 0;
        BlockPos min = new BlockPos(center.getX() - radiusXZ, center.getY() + minY, center.getZ() - radiusXZ);
        BlockPos max = new BlockPos(center.getX() + radiusXZ, center.getY() + maxY, center.getZ() + radiusXZ);
        return swapSpawnerFactionsInBox(world, min, max, targetFaction);
    }

    private static boolean swapFactionInSpawnerTE(TileEntity te, String targetFaction) {
        try {
            NBTTagCompound tag = new NBTTagCompound();
            te.writeToNBT(tag);

            // Only change keys literally named "factionName".
            boolean changed = replaceFactionNameKeys(tag, targetFaction);
            if (changed) {
                te.readFromNBT(tag);
            }
            return changed;
        } catch (Throwable ignored) {
            return false;
        }
    }

    private static boolean replaceFactionNameKeys(NBTTagCompound compound, String targetFaction) {
        boolean changed = false;

        for (String key : compound.getKeySet()) {
            NBTBase base = compound.getTag(key);
            if (base == null) continue;

            if (base instanceof NBTTagCompound) {
                changed |= replaceFactionNameKeys((NBTTagCompound) base, targetFaction);
            } else if (base instanceof NBTTagList) {
                changed |= replaceFactionNameKeysInList((NBTTagList) base, targetFaction);
            } else if (base instanceof NBTTagString) {
                if ("factionName".equals(key)) {
                    String cur = compound.getString(key);
                    if (cur == null) cur = "";
                    if (!targetFaction.equals(cur)) {
                        compound.setString(key, targetFaction);
                        changed = true;
                    }
                }
            }
        }

        return changed;
    }

    private static boolean replaceFactionNameKeysInList(NBTTagList list, String targetFaction) {
        boolean changed = false;
        for (int i = 0; i < list.tagCount(); i++) {
            NBTBase e = list.get(i);
            if (e instanceof NBTTagCompound) {
                changed |= replaceFactionNameKeys((NBTTagCompound) e, targetFaction);
            } else if (e instanceof NBTTagList) {
                changed |= replaceFactionNameKeysInList((NBTTagList) e, targetFaction);
            }
        }
        return changed;
    }
}
