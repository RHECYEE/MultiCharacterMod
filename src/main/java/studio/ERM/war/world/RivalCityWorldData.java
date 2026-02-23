package studio.ERM.war;

import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;
import net.minecraft.world.storage.MapStorage;
import net.minecraft.world.storage.WorldSavedData;

import java.util.HashMap;
import java.util.Map;

/**
 * Persistent per-world rival-city state.
 * Keyed by "ownerKey" (player UUID or scoreboard team name).
 */
public class RivalCityWorldData extends WorldSavedData {
    public static final String DATA_NAME = "epochrunner_rival_cities";

    private final Map<String, NBTTagCompound> citiesByOwner = new HashMap<>();

    public RivalCityWorldData() {
        super(DATA_NAME);
    }

    public RivalCityWorldData(String name) {
        super(name);
    }

    public static RivalCityWorldData get(World world) {
        MapStorage storage = world.getPerWorldStorage();
        RivalCityWorldData data = (RivalCityWorldData) storage.getOrLoadData(RivalCityWorldData.class, DATA_NAME);
        if (data == null) {
            data = new RivalCityWorldData();
            storage.setData(DATA_NAME, data);
        }
        return data;
    }

    public NBTTagCompound getCityTag(String ownerKey) {
        return citiesByOwner.get(ownerKey);
    }

    public void putCityTag(String ownerKey, NBTTagCompound tag) {
        if (ownerKey == null) return;
        if (tag == null) citiesByOwner.remove(ownerKey);
        else citiesByOwner.put(ownerKey, tag);
        markDirty();
    }

    public Iterable<Map.Entry<String, NBTTagCompound>> entries() {
        return citiesByOwner.entrySet();
    }

    @Override
    public void readFromNBT(NBTTagCompound nbt) {
        citiesByOwner.clear();
        if (!nbt.hasKey("Cities")) return;

        NBTTagCompound cities = nbt.getCompoundTag("Cities");
        for (String key : cities.getKeySet()) {
            NBTTagCompound city = cities.getCompoundTag(key);
            citiesByOwner.put(key, city);
        }
    }

    @Override
    public NBTTagCompound writeToNBT(NBTTagCompound nbt) {
        NBTTagCompound cities = new NBTTagCompound();
        for (Map.Entry<String, NBTTagCompound> e : citiesByOwner.entrySet()) {
            cities.setTag(e.getKey(), e.getValue());
        }
        nbt.setTag("Cities", cities);
        return nbt;
    }

    // Small helpers (optional)
    public static BlockPos readPos(NBTTagCompound tag, String key) {
        if (!tag.hasKey(key)) return null;
        return BlockPos.fromLong(tag.getLong(key));
    }

    public static void writePos(NBTTagCompound tag, String key, BlockPos pos) {
        if (pos == null) tag.removeTag(key);
        else tag.setLong(key, pos.toLong());
    }
}
