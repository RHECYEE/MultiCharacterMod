package studio.ERM.strategic.nation;

import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.nbt.NBTTagList;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;
import net.minecraft.world.storage.MapStorage;
import net.minecraft.world.storage.WorldSavedData;

import java.util.ArrayList;
import java.util.List;

/**
 * THE NATION STATES of a world — the lightweight second AI civilization. Unlike the Rival Empire
 * they never level, expand, colonize or rebuild: a nation is a NAME + CULTURE + HOME + one
 * settlement tier (1 = tribal camp, 2 = its one-shot permanent town). Everything else about them
 * (patrols, traders, reactions) is generated noise, not simulated economy.
 */
public class NationStateData extends WorldSavedData {

    private static final String NAME = "erm_nation_states";

    public static class Nation {
        public String name = "";
        public int culture = 0;         // NationCulture ordinal
        public BlockPos center = BlockPos.ORIGIN;
        /** 1 = tribal camp; 2 = permanent town built (final — nations stop growing here). */
        public int tier = 1;
        /** Cosmetic dispatch clocks (world time of last patrol / trader). */
        public long lastPatrol = 0, lastTrader = 0;

        public NationCulture culture() {
            NationCulture[] all = NationCulture.values();
            return all[Math.max(0, Math.min(culture, all.length - 1))];
        }

        NBTTagCompound writeToNBT(NBTTagCompound t) {
            t.setString("name", name);
            t.setInteger("culture", culture);
            t.setLong("center", center.toLong());
            t.setInteger("tier", tier);
            return t;
        }

        void readFromNBT(NBTTagCompound t) {
            name = t.getString("name");
            culture = t.getInteger("culture");
            center = BlockPos.fromLong(t.getLong("center"));
            tier = Math.max(1, t.getInteger("tier"));
        }
    }

    public final List<Nation> nations = new ArrayList<>();

    public NationStateData() { super(NAME); }
    public NationStateData(String name) { super(name); }

    public static NationStateData get(World world) {
        MapStorage storage = world.getPerWorldStorage();
        NationStateData data = (NationStateData) storage.getOrLoadData(NationStateData.class, NAME);
        if (data == null) {
            data = new NationStateData(NAME);
            storage.setData(NAME, data);
        }
        return data;
    }

    @Override
    public void readFromNBT(NBTTagCompound nbt) {
        nations.clear();
        NBTTagList list = nbt.getTagList("nations", 10);
        for (int i = 0; i < list.tagCount(); i++) {
            Nation n = new Nation();
            n.readFromNBT(list.getCompoundTagAt(i));
            if (!n.name.isEmpty()) nations.add(n);
        }
    }

    @Override
    public NBTTagCompound writeToNBT(NBTTagCompound nbt) {
        NBTTagList list = new NBTTagList();
        for (Nation n : nations) list.appendTag(n.writeToNBT(new NBTTagCompound()));
        nbt.setTag("nations", list);
        return nbt;
    }
}
