package studio.ERM.handlers;

import net.minecraft.block.state.IBlockState;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;

import java.util.HashMap;
import java.util.Map;

/**
 * Lightweight world backup utility used by StructureLoader.
 *
 * This class deliberately DOES NOT own its own config file.
 * Structure selection and scheduling lives in the war configs; backups are purely runtime.
 */
public final class WorldBackupManager {

    private WorldBackupManager() { }

    /**
     * Captures the original block states + tile entity NBT for a region before a structure is pasted.
     * StructureLoader populates this during placement, and you can later restore it if needed.
     */
    public static final class StructureBackup {
        public final Map<BlockPos, IBlockState> originalBlocks = new HashMap<>();
        public final Map<BlockPos, NBTTagCompound> originalTiles = new HashMap<>();
    }

    /**
     * Restores a previously captured backup.
     */
    public static void restore(World world, StructureBackup backup) {
        if (world == null || world.isRemote || backup == null) return;

        // Restore blocks
        for (Map.Entry<BlockPos, IBlockState> e : backup.originalBlocks.entrySet()) {
            BlockPos pos = e.getKey();
            IBlockState state = e.getValue();
            if (pos != null && state != null) {
                world.setBlockState(pos, state, 2);
            }
        }

        // Restore tile entities
        for (Map.Entry<BlockPos, NBTTagCompound> e : backup.originalTiles.entrySet()) {
            BlockPos pos = e.getKey();
            NBTTagCompound nbt = e.getValue();
            if (pos == null || nbt == null) continue;

            TileEntity te = world.getTileEntity(pos);
            if (te != null) {
                te.readFromNBT(nbt);
                te.markDirty();
            }
        }
    }
}
