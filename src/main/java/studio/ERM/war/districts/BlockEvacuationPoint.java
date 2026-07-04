package studio.ERM.war.districts;

import net.minecraft.block.Block;
import net.minecraft.block.material.Material;
import net.minecraft.block.state.IBlockState;
import net.minecraft.creativetab.CreativeTabs;
import net.minecraft.entity.EntityLivingBase;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.item.ItemStack;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.text.TextComponentString;
import net.minecraft.util.text.TextFormatting;
import net.minecraft.world.World;
import studio.ERM.EpochRunnerMod;
import studio.ERM.strategic.civil.evac.EvacuationData;

/**
 * AN EVACUATION POINT — a safe gathering spot (castle keep, shelter, bunker, town square...). Place
 * them anywhere; during an evacuation civilians walk to the nearest one. Persisted in EvacuationData.
 */
public class BlockEvacuationPoint extends Block {

    public BlockEvacuationPoint() {
        super(Material.ROCK);
        setHardness(2.0F);
        setResistance(12F);
        setCreativeTab(CreativeTabs.DECORATIONS);
    }

    @Override
    public void onBlockPlacedBy(World world, BlockPos pos, IBlockState state,
                                EntityLivingBase placer, ItemStack stack) {
        super.onBlockPlacedBy(world, pos, state, placer, stack);
        if (world.isRemote) return;
        EvacuationData.get(world).add(pos);
        if (placer instanceof EntityPlayer) {
            ((EntityPlayer) placer).sendMessage(new TextComponentString(TextFormatting.GREEN
                    + "Evacuation Point registered — civilians will shelter here during an evacuation."));
        }
        EpochRunnerMod.logger.info("[Evac] point registered at " + pos);
    }

    @Override
    public void breakBlock(World world, BlockPos pos, IBlockState state) {
        if (!world.isRemote) EvacuationData.get(world).remove(pos);
        super.breakBlock(world, pos, state);
    }
}
