package studio.ERM.strategic.civil.research;

import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.world.World;
import net.minecraft.world.storage.MapStorage;
import net.minecraft.world.storage.WorldSavedData;

import java.util.HashMap;
import java.util.Map;

/**
 * OUR research progress, separate from AW2's item-based model: per player, the node currently being
 * researched and the accumulated worker-seconds ("research points"). When points reach the node's
 * employee-time cost the node is granted to AW2 (unlocking its recipes). Per-world saved data.
 */
public class ErmResearchData extends WorldSavedData {

    private static final String NAME = "erm_research";

    public static class Progress {
        public String current = "";
        public double points = 0;
    }

    public final Map<String, Progress> byPlayer = new HashMap<>();

    public ErmResearchData() { super(NAME); }
    public ErmResearchData(String name) { super(name); }

    public static ErmResearchData get(World world) {
        MapStorage storage = world.getPerWorldStorage();
        ErmResearchData data = (ErmResearchData) storage.getOrLoadData(ErmResearchData.class, NAME);
        if (data == null) {
            data = new ErmResearchData(NAME);
            storage.setData(NAME, data);
        }
        return data;
    }

    public Progress forPlayer(String name) {
        return byPlayer.computeIfAbsent(name, k -> new Progress());
    }

    @Override
    public void readFromNBT(NBTTagCompound nbt) {
        byPlayer.clear();
        NBTTagCompound m = nbt.getCompoundTag("players");
        for (String k : m.getKeySet()) {
            NBTTagCompound e = m.getCompoundTag(k);
            Progress p = new Progress();
            p.current = e.getString("cur");
            p.points = e.getDouble("pts");
            byPlayer.put(k, p);
        }
    }

    @Override
    public NBTTagCompound writeToNBT(NBTTagCompound nbt) {
        NBTTagCompound m = new NBTTagCompound();
        for (Map.Entry<String, Progress> en : byPlayer.entrySet()) {
            NBTTagCompound e = new NBTTagCompound();
            e.setString("cur", en.getValue().current == null ? "" : en.getValue().current);
            e.setDouble("pts", en.getValue().points);
            m.setTag(en.getKey(), e);
        }
        nbt.setTag("players", m);
        return nbt;
    }
}
