package studio.ERM.war.districts;

import net.minecraft.block.Block;
import net.minecraft.block.material.Material;
import net.minecraft.block.state.IBlockState;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.world.World;

import javax.annotation.Nullable;

/**
 * Marker block that carries a {@link TileEntityDistrictMarker} storing the district type.
 */
public class BlockDistrictMarker extends Block {

    public BlockDistrictMarker() {
        super(Material.ROCK);
        setHardness(1.5F);
    }

    @Override
    public boolean hasTileEntity(IBlockState state) {
        return true;
    }

    @Nullable
    @Override
    public TileEntity createTileEntity(World world, IBlockState state) {
        return new TileEntityDistrictMarker();
    }
}
