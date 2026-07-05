package studio.ERM.strategic;

import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.nbt.NBTTagList;
import net.minecraft.world.World;
import net.minecraft.world.storage.MapStorage;
import net.minecraft.world.storage.WorldSavedData;
import studio.ERM.EpochRunnerMod;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.function.Supplier;

/**
 * PHASE 2 — the persistent STRATEGIC MAP registry (per world). Every strategic object lives here with
 * a stable UUID; the {@link StrategicSimulator} ticks them. Save/load dispatches through the type
 * factory registry so new archetypes (trader, convoy, courier, proxy...) just register a factory.
 *
 * Recovery rule: objects are always LOADED FROM DISK AS UNLOADED (entity ids are stale across a
 * restart); the entity-join orphan sweep in the simulator removes any chunk-saved stragglers.
 */
public class StrategicMapData extends WorldSavedData {

    private static final String NAME = "erm_strategic_map";

    /** Type-key -> factory, for deserialization + spawning. Register every archetype here. */
    public static final Map<String, Supplier<StrategicObject>> FACTORIES = new LinkedHashMap<>();
    static {
        FACTORIES.put("patrol", StrategicPatrol::new);
        FACTORIES.put("trader", StrategicTrader::new);
        FACTORIES.put("reinforcement", StrategicReinforcement::new);
        FACTORIES.put("convoy", StrategicConvoy::new);
        FACTORIES.put("roamer", StrategicRoamer::new);
    }

    public final Map<UUID, StrategicObject> objects = new LinkedHashMap<>();

    public StrategicMapData() { super(NAME); }
    public StrategicMapData(String name) { super(name); }

    public static StrategicMapData get(World world) {
        MapStorage storage = world.getPerWorldStorage();
        StrategicMapData data = (StrategicMapData) storage.getOrLoadData(StrategicMapData.class, NAME);
        if (data == null) {
            data = new StrategicMapData(NAME);
            storage.setData(NAME, data);
        }
        return data;
    }

    public void add(StrategicObject o) {
        if (o == null) return;
        objects.put(o.id, o);
        markDirty();
    }

    public void remove(UUID id) {
        objects.remove(id);
        markDirty();
    }

    @Override
    public void readFromNBT(NBTTagCompound nbt) {
        objects.clear();
        NBTTagList list = nbt.getTagList("objects", 10);
        for (int i = 0; i < list.tagCount(); i++) {
            NBTTagCompound tag = list.getCompoundTagAt(i);
            String type = tag.getString("type");
            Supplier<StrategicObject> factory = FACTORIES.get(type);
            if (factory == null) {
                EpochRunnerMod.logger.warn("[Strategic] unknown object type '" + type + "' in save; dropped");
                continue;
            }
            try {
                StrategicObject o = factory.get();
                o.readFromNBT(tag);
                if (o.strength > 0) objects.put(o.id, o);
            } catch (Throwable t) {
                EpochRunnerMod.logger.warn("[Strategic] failed to load a '" + type + "': " + t);
            }
        }
        EpochRunnerMod.logger.info("[Strategic] map loaded: " + objects.size() + " object(s)");
    }

    @Override
    public NBTTagCompound writeToNBT(NBTTagCompound nbt) {
        NBTTagList list = new NBTTagList();
        for (StrategicObject o : objects.values()) {
            try { list.appendTag(o.writeToNBT(new NBTTagCompound())); } catch (Throwable ignored) {}
        }
        nbt.setTag("objects", list);
        return nbt;
    }
}
