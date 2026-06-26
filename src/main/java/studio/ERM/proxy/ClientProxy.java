package studio.ERM.proxy;

import net.minecraftforge.client.model.ModelLoader;
import net.minecraft.client.renderer.block.model.ModelResourceLocation;
import net.minecraftforge.fml.common.event.FMLInitializationEvent;
import studio.ERM.EpochRunnerMod;
import studio.ERM.war.map.client.TacticalWarMapClient;

public class ClientProxy extends CommonProxy {

    @Override
    public void init(FMLInitializationEvent event) {
        super.init(event);
        // Register tactical war map keybind (M key)
        TacticalWarMapClient.registerKeybind();
    }

    @Override
    public void registerModels() {
        // MOVE ALL YOUR "ModelLoader" CALLS HERE
        // This stops the Server from ever seeing them.
    }
}