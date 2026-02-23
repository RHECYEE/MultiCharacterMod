package co.runed.multicharacter.compat;

import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.world.World;
import net.minecraft.world.storage.MapStorage;
import net.minecraft.world.storage.WorldSavedData;

public class WorldDataGuns extends WorldSavedData {
    private static final String DATA_NAME = "MultiCharacter_GunsSetting";
    private boolean gunsEnabled = false;

    public WorldDataGuns() {
        super(DATA_NAME);
    }

    public WorldDataGuns(String name) {
        super(name);
    }

    public static WorldDataGuns get(World world) {
        MapStorage storage = world.getMapStorage();
        if (storage == null) return null;

        WorldDataGuns instance = (WorldDataGuns) storage.getOrLoadData(WorldDataGuns.class, DATA_NAME);
        if (instance == null) {
            instance = new WorldDataGuns();
            storage.setData(DATA_NAME, instance);
        }
        return instance;
    }

    public boolean areGunsEnabled() { return gunsEnabled; }
    public void setGunsEnabled(boolean enabled) { this.gunsEnabled = enabled; this.markDirty(); }

    @Override
    public void readFromNBT(NBTTagCompound nbt) { this.gunsEnabled = nbt.getBoolean("gunsEnabled"); }
    @Override
    public NBTTagCompound writeToNBT(NBTTagCompound nbt) { nbt.setBoolean("gunsEnabled", this.gunsEnabled); return nbt; }
}