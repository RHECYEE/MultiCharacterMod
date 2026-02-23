package studio.WorldSpawner;

import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.common.event.FMLServerStartingEvent;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import net.minecraftforge.fml.common.gameevent.TickEvent;
import studio.ERM.EpochRunnerMod;
import studio.WorldSpawner.command.CommandWorldSpawner;
import studio.WorldSpawner.config.WorldSpawnerConfig;
import studio.WorldSpawner.data.WorldSpawnerWorldData;
import studio.WorldSpawner.spawn.WorldSpawnManager;

@Mod.EventBusSubscriber(modid = EpochRunnerMod.MODID)
public final class WorldSpawnerModule {

    private static int serverTicks = 0;

    private WorldSpawnerModule() {
    }

    @SubscribeEvent
    public static void onServerStarting(FMLServerStartingEvent event) {
        WorldSpawnerConfig.getInstance().loadFromDisk();
        event.registerServerCommand(new CommandWorldSpawner());
    }

    @SubscribeEvent
    public static void onWorldTick(TickEvent.WorldTickEvent event) {
        if (event.phase != TickEvent.Phase.END) return;
        if (event.world == null || event.world.isRemote) return;

        serverTicks++;
        if (serverTicks < 0) serverTicks = 0;

        WorldSpawnerWorldData data = WorldSpawnerWorldData.get(event.world);
        if (data == null) return;

        WorldSpawnManager.tick(event.world, data);
    }
}
