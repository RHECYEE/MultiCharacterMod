package studio.ERM.strategic.defense;

import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.world.World;
import net.minecraftforge.fml.common.network.IGuiHandler;

/** GUI ids for the mod's container screens (registered on EpochRunnerMod in init). */
public class ErmGuiHandler implements IGuiHandler {

    public static final int GUI_RECRUIT = 1;

    @Override
    public Object getServerGuiElement(int id, EntityPlayer player, World world, int x, int y, int z) {
        if (id == GUI_RECRUIT) return new ContainerRecruit(player);
        return null;
    }

    @Override
    public Object getClientGuiElement(int id, EntityPlayer player, World world, int x, int y, int z) {
        if (id == GUI_RECRUIT) return new GuiRecruit(player);
        return null;
    }
}
