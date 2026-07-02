package studio.ERM.strategic.defense;

import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.nbt.NBTTagList;
import net.minecraft.world.World;
import net.minecraft.world.storage.MapStorage;
import net.minecraft.world.storage.WorldSavedData;

import java.util.ArrayList;
import java.util.List;

/**
 * PHASE 2 — the persistent DEFENSIVE PLAN for a world: the markers the player drew on the Military
 * overlay, plus the live FALL BACK state. The plan is standing doctrine; it survives restarts and is
 * executed by {@link DefensePlanExecutor} whenever forces are available.
 */
public class DefensePlanData extends WorldSavedData {

    private static final String NAME = "erm_defense_plan";

    public final List<DefenseMarker> markers = new ArrayList<>();
    public boolean fallbackActive = false;

    public DefensePlanData() { super(NAME); }
    public DefensePlanData(String name) { super(name); }

    public static DefensePlanData get(World world) {
        MapStorage storage = world.getPerWorldStorage();
        DefensePlanData data = (DefensePlanData) storage.getOrLoadData(DefensePlanData.class, NAME);
        if (data == null) {
            data = new DefensePlanData(NAME);
            storage.setData(NAME, data);
        }
        return data;
    }

    /** Remove the marker whose centroid is nearest (x,z), if within {@code maxDist} blocks. */
    public boolean removeNearest(int x, int z, double maxDist) {
        DefenseMarker best = null;
        double bd = maxDist * maxDist;
        for (DefenseMarker m : markers) {
            net.minecraft.util.math.BlockPos c = m.center();
            double dx = c.getX() - x, dz = c.getZ() - z;
            double d = dx * dx + dz * dz;
            if (d < bd) { bd = d; best = m; }
        }
        if (best != null) { markers.remove(best); markDirty(); return true; }
        return false;
    }

    @Override
    public void readFromNBT(NBTTagCompound nbt) {
        markers.clear();
        NBTTagList list = nbt.getTagList("markers", 10);
        for (int i = 0; i < list.tagCount(); i++) {
            DefenseMarker m = new DefenseMarker();
            m.readFromNBT(list.getCompoundTagAt(i));
            if (!m.points.isEmpty()) markers.add(m);
        }
        fallbackActive = nbt.getBoolean("fallback");
    }

    @Override
    public NBTTagCompound writeToNBT(NBTTagCompound nbt) {
        NBTTagList list = new NBTTagList();
        for (DefenseMarker m : markers) list.appendTag(m.writeToNBT(new NBTTagCompound()));
        nbt.setTag("markers", list);
        nbt.setBoolean("fallback", fallbackActive);
        return nbt;
    }
}
