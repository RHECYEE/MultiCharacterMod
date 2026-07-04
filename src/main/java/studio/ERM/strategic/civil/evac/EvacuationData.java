package studio.ERM.strategic.civil.evac;

import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;
import net.minecraft.world.storage.MapStorage;
import net.minecraft.world.storage.WorldSavedData;

import java.util.HashSet;
import java.util.Set;

/** Persisted set of Evacuation Point positions in a world — where civilians gather during an evac. */
public class EvacuationData extends WorldSavedData {

    private static final String NAME = "erm_evac_points";

    public final Set<BlockPos> points = new HashSet<>();

    public EvacuationData() { super(NAME); }
    public EvacuationData(String name) { super(name); }

    public static EvacuationData get(World world) {
        MapStorage storage = world.getPerWorldStorage();
        EvacuationData data = (EvacuationData) storage.getOrLoadData(EvacuationData.class, NAME);
        if (data == null) {
            data = new EvacuationData(NAME);
            storage.setData(NAME, data);
        }
        return data;
    }

    public void add(BlockPos p) { if (points.add(p.toImmutable())) markDirty(); }

    public void remove(BlockPos p) { if (points.remove(p)) markDirty(); }

    /** Nearest evacuation point to a position, or null if none exist. */
    public BlockPos nearest(BlockPos from) {
        BlockPos best = null;
        double bd = Double.MAX_VALUE;
        for (BlockPos p : points) {
            double d = p.distanceSq(from);
            if (d < bd) { bd = d; best = p; }
        }
        return best;
    }

    @Override
    public void readFromNBT(NBTTagCompound nbt) {
        points.clear();
        int[] a = nbt.getIntArray("pts");
        for (int i = 0; i + 2 < a.length; i += 3) points.add(new BlockPos(a[i], a[i + 1], a[i + 2]));
    }

    @Override
    public NBTTagCompound writeToNBT(NBTTagCompound nbt) {
        int[] arr = new int[points.size() * 3];
        int i = 0;
        for (BlockPos p : points) { arr[i++] = p.getX(); arr[i++] = p.getY(); arr[i++] = p.getZ(); }
        nbt.setIntArray("pts", arr);
        return nbt;
    }
}
