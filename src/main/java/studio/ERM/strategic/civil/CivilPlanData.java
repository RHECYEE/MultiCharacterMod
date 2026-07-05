package studio.ERM.strategic.civil;

import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.nbt.NBTTagList;
import net.minecraft.world.World;
import net.minecraft.world.storage.MapStorage;
import net.minecraft.world.storage.WorldSavedData;

import java.util.ArrayList;
import java.util.List;

/**
 * The persistent CIVILIAN INFRASTRUCTURE PLAN for a world: the roads and district polygons the
 * player drew on the map's Civilian tab. Like the defensive plan, this is standing doctrine — the
 * civilian systems (couriers, workers, road repair, pathing preference) execute against it later.
 */
public class CivilPlanData extends WorldSavedData {

    private static final String NAME = "erm_civil_plan";

    public final List<CivilMarker> markers = new ArrayList<>();

    public CivilPlanData() { super(NAME); }
    public CivilPlanData(String name) { super(name); }

    public static CivilPlanData get(World world) {
        MapStorage storage = world.getPerWorldStorage();
        CivilPlanData data = (CivilPlanData) storage.getOrLoadData(CivilPlanData.class, NAME);
        if (data == null) {
            data = new CivilPlanData(NAME);
            storage.setData(NAME, data);
        }
        return data;
    }

    /** Remove the marker whose centroid is nearest (x,z), if within {@code maxDist} blocks. */
    public boolean removeNearest(int x, int z, double maxDist) {
        CivilMarker best = null;
        double bd = maxDist * maxDist;
        for (CivilMarker m : markers) {
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
            CivilMarker m = new CivilMarker();
            m.readFromNBT(list.getCompoundTagAt(i));
            if (m.points.size() >= m.minPoints()) markers.add(m);
        }
    }

    @Override
    public NBTTagCompound writeToNBT(NBTTagCompound nbt) {
        NBTTagList list = new NBTTagList();
        for (CivilMarker m : markers) list.appendTag(m.writeToNBT(new NBTTagCompound()));
        nbt.setTag("markers", list);
        return nbt;
    }
}
