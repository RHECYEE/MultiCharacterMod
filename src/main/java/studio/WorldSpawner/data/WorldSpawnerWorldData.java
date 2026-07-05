package studio.WorldSpawner.data;

import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.world.World;
import net.minecraft.world.storage.WorldSavedData;

public class WorldSpawnerWorldData extends WorldSavedData {
    private static final String DATA_NAME = "WorldSpawnerData";

    public WorldSpawnerWorldData() {
        super(DATA_NAME);
    }

    public WorldSpawnerWorldData(String name) {
        super(name);
    }

    public static WorldSpawnerWorldData get(World world) {
        WorldSpawnerWorldData data = (WorldSpawnerWorldData) world.getMapStorage().getOrLoadData(WorldSpawnerWorldData.class, DATA_NAME);
        if (data == null) {
            data = new WorldSpawnerWorldData();
            world.getMapStorage().setData(DATA_NAME, data);
        }
        return data;
    }

    @Override
    public void readFromNBT(NBTTagCompound nbt) {
    }

    @Override
    public NBTTagCompound writeToNBT(NBTTagCompound nbt) {
        return nbt;
    }
}
