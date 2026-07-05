package studio.ERM.proxy;

import net.minecraftforge.fml.common.event.FMLInitializationEvent;
import net.minecraftforge.fml.common.event.FMLPreInitializationEvent;

public class CommonProxy {
    public void preInit(FMLPreInitializationEvent event) {
        // Shared logic goes here (Entities, Networking)
    }

    public void init(FMLInitializationEvent event) {
        // Overridden by ClientProxy for client-specific init
    }

    public void registerModels() {
        // Left empty! The server doesn't care about models.
    }
}