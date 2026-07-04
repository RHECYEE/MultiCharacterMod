package studio.ERM.war.districts;

import net.minecraft.block.Block;
import net.minecraft.block.material.Material;
import net.minecraft.block.state.IBlockState;
import net.minecraft.entity.EntityLivingBase;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.inventory.InventoryHelper;
import net.minecraft.item.ItemStack;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.util.EnumFacing;
import net.minecraft.util.EnumHand;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.text.TextComponentString;
import net.minecraft.util.text.TextFormatting;
import net.minecraft.world.World;
import studio.ERM.EpochRunnerMod;
import studio.ERM.strategic.civil.CivilMarker;
import studio.ERM.strategic.civil.DistrictRegistry;
import studio.ERM.strategic.defense.ErmGuiHandler;

import javax.annotation.Nullable;

/**
 * THE DISTRICT DEPOT BLOCK. Carries {@link TileEntityDistrictMarker} — the district's persistent
 * inventory + courier IN/OUT templates + worker count. Placing it inside a drawn district polygon
 * auto-binds it as that district's depot; right-click opens the Universal District Controller.
 */
public class BlockDistrictMarker extends Block {

    // Chest-shaped model (1..15 x 0..14): the block is NOT a full opaque cube. Without these flags the
    // renderer culls the neighbouring faces and bakes black AO into the 1px gutter around the model --
    // the "void around the borders" halo.
    private static final net.minecraft.util.math.AxisAlignedBB CHEST_AABB =
            new net.minecraft.util.math.AxisAlignedBB(1 / 16.0, 0.0, 1 / 16.0, 15 / 16.0, 14 / 16.0, 15 / 16.0);

    public BlockDistrictMarker() {
        super(Material.ROCK);
        setHardness(1.5F);
    }

    @Override
    public boolean isOpaqueCube(IBlockState state) { return false; }

    @Override
    public boolean isFullCube(IBlockState state) { return false; }

    @Override
    public net.minecraft.util.math.AxisAlignedBB getBoundingBox(IBlockState state,
            net.minecraft.world.IBlockAccess source, BlockPos pos) {
        return CHEST_AABB;
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

    @Override
    public boolean onBlockActivated(World world, BlockPos pos, IBlockState state, EntityPlayer player,
                                    EnumHand hand, EnumFacing facing, float hitX, float hitY, float hitZ) {
        if (!world.isRemote) {
            // Self-healing bind: a depot placed before its polygon was drawn binds on first open.
            TileEntity te = world.getTileEntity(pos);
            if (te instanceof TileEntityDistrictMarker && !((TileEntityDistrictMarker) te).isBound()) {
                DistrictRegistry.bindDepot(world, pos);
            }
            // A Trade Depot district opens the market GUI; every other kind opens the depot controller.
            int gui = ErmGuiHandler.GUI_DISTRICT_DEPOT;
            if (te instanceof TileEntityDistrictMarker) {
                studio.ERM.strategic.civil.CivilMarker d = studio.ERM.strategic.civil.DistrictRegistry
                        .byUid(world, ((TileEntityDistrictMarker) te).getDistrictUid());
                if (d != null && d.kind == studio.ERM.strategic.civil.CivilMarker.TRADE_DEPOT)
                    gui = ErmGuiHandler.GUI_TRADE_DEPOT;
                else if (d != null && d.kind == studio.ERM.strategic.civil.CivilMarker.RESEARCH)
                    gui = ErmGuiHandler.GUI_RESEARCH;
                else if (d != null && d.kind == studio.ERM.strategic.civil.CivilMarker.ARMORY)
                    gui = ErmGuiHandler.GUI_ARMORY;
            }
            player.openGui(EpochRunnerMod.instance, gui, world, pos.getX(), pos.getY(), pos.getZ());
        }
        return true;
    }

    @Override
    public void onBlockPlacedBy(World world, BlockPos pos, IBlockState state,
                                EntityLivingBase placer, ItemStack stack) {
        super.onBlockPlacedBy(world, pos, state, placer, stack);
        if (world.isRemote || !(placer instanceof EntityPlayer)) return;
        CivilMarker district = DistrictRegistry.bindDepot(world, pos);
        if (district != null) {
            placer.sendMessage(new TextComponentString(TextFormatting.GREEN + "Depot bound: "
                    + TextFormatting.AQUA + CivilMarker.nameOf(district.kind) + " district"
                    + TextFormatting.GRAY + " #" + (district.uid & 0xFFFF)));
        } else {
            placer.sendMessage(new TextComponentString(TextFormatting.YELLOW
                    + "Depot placed outside any district — draw a district around it on the map's "
                    + "Civilian tab, then tag it with the Pen & Paper (Courier Marker)."));
        }
    }

    @Override
    public void breakBlock(World world, BlockPos pos, IBlockState state) {
        TileEntity te = world.getTileEntity(pos);
        if (te instanceof TileEntityDistrictMarker) {
            TileEntityDistrictMarker depot = (TileEntityDistrictMarker) te;
            // Spill the REAL depot contents; templates are patterns, not items.
            for (int i = 0; i < depot.depot.getSlots(); i++) {
                ItemStack s = depot.depot.getStackInSlot(i);
                if (!s.isEmpty()) InventoryHelper.spawnItemStack(world, pos.getX(), pos.getY(), pos.getZ(), s);
            }
        }
        if (!world.isRemote) DistrictRegistry.unbindDepot(world, pos);
        super.breakBlock(world, pos, state);
    }
}
