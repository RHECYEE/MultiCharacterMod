package studio.ERM.war.blocks;

import net.minecraft.block.Block;
import net.minecraft.block.material.Material;
import net.minecraft.block.state.IBlockState;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.util.EnumFacing;
import net.minecraft.util.EnumHand;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;

import javax.annotation.Nullable;

public class BlockBanquetHall extends Block {

    public BlockBanquetHall() {
        super(Material.WOOD);
        setHardness(2.5F);
        setResistance(12.0F);
        setHarvestLevel("axe", 0);
        setTranslationKey("homosapien.banquet_hall");
    }

    @Override
    public boolean hasTileEntity(IBlockState state) {
        return true;
    }

    @Nullable
    @Override
    public TileEntity createTileEntity(World world, IBlockState state) {
        return new TileEntityBanquetHall();
    }

    @Override
    public boolean onBlockActivated(World worldIn, BlockPos pos, IBlockState state, EntityPlayer playerIn,
                                    EnumHand hand, EnumFacing facing, float hitX, float hitY, float hitZ) {
        if (worldIn == null || playerIn == null) return false;
        if (worldIn.isRemote) return true;

        TileEntity te = worldIn.getTileEntity(pos);
        if (te instanceof TileEntityBanquetHall) {
            playerIn.displayGUIChest((TileEntityBanquetHall) te);
            return true;
        }
        return false;
    }

    @Override
    public void breakBlock(World worldIn, BlockPos pos, IBlockState state) {
        TileEntity te = worldIn.getTileEntity(pos);
        if (te instanceof TileEntityBanquetHall) {
            ((TileEntityBanquetHall) te).dropAllContents();
        }
        super.breakBlock(worldIn, pos, state);
    }
}
