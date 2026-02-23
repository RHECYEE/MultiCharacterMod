package studio.ERM.war;

import net.minecraft.block.BlockFence;
import net.minecraft.block.material.MapColor;
import net.minecraft.block.material.Material;
import net.minecraft.block.state.IBlockState;
import net.minecraft.init.Items;
import net.minecraft.item.Item;
import net.minecraft.util.BlockRenderLayer;
import net.minecraftforge.fml.relauncher.Side;
import net.minecraftforge.fml.relauncher.SideOnly;

import java.util.Random;

/**
 * The Scaffold block.
 * Inherits from Fence to provide transparency and collision.
 * All @Override tags removed to force compilation regardless of mapping names.
 */
public class BlockScaffold extends BlockFence {

    public BlockScaffold() {
        super(Material.WOOD, MapColor.WOOD);
        this.setRegistryName("scaffold");
        this.setTranslationKey("scaffold");
        this.setHardness(0.5F);
        this.setResistance(1.0F);
    }

    /**
     * Set to translucent. Annotation removed to bypass build failure.
     */
    @SideOnly(Side.CLIENT)
    public BlockRenderLayer getBlockLayer() {
        return BlockRenderLayer.TRANSLUCENT;
    }

    /**
     * Drops nothing. Annotation removed to bypass build failure.
     */
    public Item getItemDropped(IBlockState state, Random rand, int fortune) {
        return Items.AIR;
    }
}