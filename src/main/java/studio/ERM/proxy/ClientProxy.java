package studio.ERM.proxy;

import net.minecraftforge.client.model.ModelLoader;
import net.minecraft.client.renderer.block.model.ModelResourceLocation;
import studio.ERM.EpochRunnerMod;

public class ClientProxy extends CommonProxy {
    @Override
    public void registerModels() {
        // MOVE ALL YOUR "ModelLoader" CALLS HERE
        // This stops the Server from ever seeing them.
    }
}