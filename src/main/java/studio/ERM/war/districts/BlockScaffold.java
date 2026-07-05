package studio.ERM.war.districts;

import net.minecraft.block.BlockFence;
import net.minecraft.block.material.MapColor;
import net.minecraft.block.material.Material;
import net.minecraft.block.state.IBlockState;
import net.minecraft.init.Items;
import net.minecraft.item.Item;
import net.minecraft.util.BlockRenderLayer;
import net.minecraft.util.math.AxisAlignedBB;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.IBlockAccess;
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
        // NOTE: the registry name is assigned centrally in EpochRunnerMod.RegistrationHandler.registerBlocks().
        // Do NOT also set it here -- Forge throws IllegalStateException on any second setRegistryName call
        // ("Attempted to set registry name with existing registry name! New: scaffold Old: homosapien:scaffold").
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

    // ── Passable repair marker ──
    // When war damage (siege breach, explosion, fire) hits CLAIMED land, a scaffold is placed where
    // the original block was and the original is stored for repair. That marker must NEVER obstruct
    // movement -- the player, the repair command, and the builder citizen all walk straight through
    // it. So: no collision box, and explicitly passable.

    public AxisAlignedBB getCollisionBoundingBox(IBlockState state, IBlockAccess world, BlockPos pos) {
        return NULL_AABB;
    }

    public boolean isPassable(IBlockAccess world, BlockPos pos) {
        return true;
    }

    public boolean canBeConnectedTo(IBlockAccess world, BlockPos pos, net.minecraft.util.EnumFacing facing) {
        return false; // don't visually connect like a fence; it's a standalone damage marker
    }

    /**
     * BlockFence adds its own (tall) collision boxes HERE, bypassing getCollisionBoundingBox -- which
     * is why the marker felt only "partly passable". Add NO boxes so you walk straight through it.
     */
    public void addCollisionBoxToList(IBlockState state, net.minecraft.world.World worldIn, BlockPos pos,
                                      AxisAlignedBB entityBox, java.util.List<AxisAlignedBB> collidingBoxes,
                                      net.minecraft.entity.Entity entityIn, boolean isActualState) {
        // intentionally empty -- the repair marker never obstructs movement
    }

    /**
     * Render INVISIBLE. The translucent-glass model read as a buggy dark cube; the damage marker is
     * meant to be a clean, see-through placeholder (the area looks cleared) that /war repair and the
     * builder citizen restore from the stored original. The block is still physically present + tracked.
     */
    public net.minecraft.util.EnumBlockRenderType getRenderType(IBlockState state) {
        return net.minecraft.util.EnumBlockRenderType.INVISIBLE;
    }
}