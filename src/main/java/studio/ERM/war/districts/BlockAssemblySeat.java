package studio.ERM.war.districts;

import net.minecraft.block.Block;
import net.minecraft.block.material.Material;
import net.minecraft.block.state.IBlockState;
import net.minecraft.creativetab.CreativeTabs;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.util.EnumFacing;
import net.minecraft.util.EnumHand;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;
import net.minecraft.util.text.TextComponentString;
import net.minecraft.util.text.TextFormatting;
import studio.ERM.EpochRunnerMod;
import studio.ERM.strategic.civil.CivilMarker;
import studio.ERM.strategic.civil.CivilPlanData;
import studio.ERM.strategic.defense.ErmGuiHandler;

/**
 * THE ASSEMBLY SEAT block — a Factory's crafting station. Place seats inside a Factory district and
 * chain them side by side; each holds its own recipe (right-click to set it). The FactoryManager
 * counts the seats to size the district's workforce and runs each manned seat's craft on a timer.
 */
public class BlockAssemblySeat extends Block {

    public BlockAssemblySeat() {
        super(Material.IRON);
        setHardness(2.5F);
        setResistance(10F);
        setCreativeTab(CreativeTabs.REDSTONE);
    }

    @Override
    public boolean hasTileEntity(IBlockState state) { return true; }

    @Override
    public TileEntity createTileEntity(World world, IBlockState state) {
        return new TileEntityAssemblySeat();
    }

    @Override
    public boolean onBlockActivated(World world, BlockPos pos, IBlockState state, EntityPlayer player,
                                    EnumHand hand, EnumFacing facing, float hx, float hy, float hz) {
        if (!world.isRemote) {
            player.openGui(EpochRunnerMod.instance, ErmGuiHandler.GUI_SEAT, world,
                    pos.getX(), pos.getY(), pos.getZ());
        }
        return true;
    }

    @Override
    public void onBlockPlacedBy(World world, BlockPos pos, IBlockState state,
                                net.minecraft.entity.EntityLivingBase placer, net.minecraft.item.ItemStack stack) {
        super.onBlockPlacedBy(world, pos, state, placer, stack);
        if (world.isRemote || !(placer instanceof EntityPlayer)) return;
        // Friendly hint: is this seat inside a Factory district?
        boolean inFactory = false;
        for (CivilMarker m : CivilPlanData.get(world).markers) {
            if (m.kind == CivilMarker.FACTORY && !m.isRoad() && m.contains(pos.getX(), pos.getZ())) {
                inFactory = true; break;
            }
        }
        placer.sendMessage(new TextComponentString(inFactory
                ? TextFormatting.GREEN + "Assembly seat placed in a Factory district — right-click to set its recipe."
                : TextFormatting.YELLOW + "Assembly seat placed OUTSIDE any Factory district; draw a Factory around it."));
    }
}
