package studio.ERM.strategic.defense;

import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;
import net.minecraftforge.fml.common.network.IGuiHandler;
import studio.ERM.war.districts.TileEntityDistrictMarker;
import studio.ERM.war.districts.inventory.ContainerDistrict;
import studio.ERM.war.districts.inventory.GuiDistrict;

/** GUI ids for the mod's container screens (registered on EpochRunnerMod in init). */
public class ErmGuiHandler implements IGuiHandler {

    public static final int GUI_RECRUIT = 1;
    /** The Universal District Controller: x/y/z = the depot block's position. */
    public static final int GUI_DISTRICT_DEPOT = 2;
    /** The Trade Depot market GUI: x/y/z = the trade-depot block's position. */
    public static final int GUI_TRADE_DEPOT = 3;
    /** The Research tech-tree GUI: x/y/z = the research-depot block's position. */
    public static final int GUI_RESEARCH = 4;

    @Override
    public Object getServerGuiElement(int id, EntityPlayer player, World world, int x, int y, int z) {
        if (id == GUI_RECRUIT) return new ContainerRecruit(player);
        if (id == GUI_DISTRICT_DEPOT) {
            TileEntity te = world.getTileEntity(new BlockPos(x, y, z));
            if (te instanceof TileEntityDistrictMarker) {
                return new ContainerDistrict(player, (TileEntityDistrictMarker) te);
            }
        }
        if (id == GUI_TRADE_DEPOT) {
            return new studio.ERM.strategic.civil.trade.ContainerTradeDepot(player, new BlockPos(x, y, z));
        }
        if (id == GUI_RESEARCH) {
            return new studio.ERM.strategic.civil.research.ContainerResearch(player, new BlockPos(x, y, z));
        }
        return null;
    }

    @Override
    public Object getClientGuiElement(int id, EntityPlayer player, World world, int x, int y, int z) {
        if (id == GUI_RECRUIT) return new GuiRecruit(player);
        if (id == GUI_DISTRICT_DEPOT) {
            TileEntity te = world.getTileEntity(new BlockPos(x, y, z));
            if (te instanceof TileEntityDistrictMarker) {
                return new GuiDistrict(player, (TileEntityDistrictMarker) te);
            }
        }
        if (id == GUI_TRADE_DEPOT) {
            return new studio.ERM.strategic.civil.trade.GuiTradeDepot(player, new BlockPos(x, y, z));
        }
        if (id == GUI_RESEARCH) {
            return new studio.ERM.strategic.civil.research.GuiResearchTree(player, new BlockPos(x, y, z));
        }
        return null;
    }
}
