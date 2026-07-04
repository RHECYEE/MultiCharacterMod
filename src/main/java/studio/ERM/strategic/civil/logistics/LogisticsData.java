package studio.ERM.strategic.civil.logistics;

import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.nbt.NBTTagList;
import net.minecraft.world.World;
import net.minecraft.world.storage.MapStorage;
import net.minecraft.world.storage.WorldSavedData;

import java.util.ArrayList;
import java.util.List;

/** The persistent settlement-wide transport ledger: every live courier job, in one place. */
public class LogisticsData extends WorldSavedData {

    private static final String NAME = "erm_logistics";

    public final List<CourierJob> jobs = new ArrayList<>();
    private int nextUid = 1;

    public LogisticsData() { super(NAME); }
    public LogisticsData(String name) { super(name); }

    public static LogisticsData get(World world) {
        MapStorage storage = world.getPerWorldStorage();
        LogisticsData data = (LogisticsData) storage.getOrLoadData(LogisticsData.class, NAME);
        if (data == null) {
            data = new LogisticsData(NAME);
            storage.setData(NAME, data);
        }
        return data;
    }

    public int takeUid() {
        markDirty();
        return nextUid++;
    }

    public CourierJob byUid(int uid) {
        for (CourierJob j : jobs) if (j.uid == uid) return j;
        return null;
    }

    @Override
    public void readFromNBT(NBTTagCompound nbt) {
        jobs.clear();
        nextUid = Math.max(1, nbt.getInteger("nextUid"));
        NBTTagList list = nbt.getTagList("jobs", 10);
        for (int i = 0; i < list.tagCount(); i++) {
            CourierJob j = new CourierJob();
            j.readFromNBT(list.getCompoundTagAt(i));
            if (!j.item.isEmpty()) jobs.add(j);
        }
    }

    @Override
    public NBTTagCompound writeToNBT(NBTTagCompound nbt) {
        nbt.setInteger("nextUid", nextUid);
        NBTTagList list = new NBTTagList();
        for (CourierJob j : jobs) if (!j.done) list.appendTag(j.writeToNBT(new NBTTagCompound()));
        nbt.setTag("jobs", list);
        return nbt;
    }
}
