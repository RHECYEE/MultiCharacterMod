package studio.ERM.war.districts;

import net.minecraft.block.Block;
import net.minecraft.block.material.Material;
import net.minecraft.block.state.IBlockState;
import net.minecraft.creativetab.CreativeTabs;
import net.minecraft.entity.EntityLivingBase;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.init.Items;
import net.minecraft.item.ItemStack;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.util.EnumFacing;
import net.minecraft.util.EnumHand;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.text.TextComponentString;
import net.minecraft.util.text.TextFormatting;
import net.minecraft.world.World;
import studio.ERM.EpochRunnerMod;
import studio.ERM.strategic.civil.BedAssignmentData;
import studio.ERM.strategic.civil.CivilMarker;
import studio.ERM.strategic.civil.CivilPlanData;
import studio.ERM.strategic.civil.DistrictRegistry;
import studio.ERM.strategic.defense.ErmGuiHandler;

/**
 * THE FAMILY KITCHEN — a single-block, drop-in KITCHEN district for one household. Instead of drawing
 * a Kitchen polygon, the player places this block inside a home; on placement it AUTO-REGISTERS a tiny
 * KITCHEN {@link CivilMarker} bound to itself, so the existing systems treat it as a full kitchen with
 * NO extra wiring: the citizen food AI already eats at the nearest KITCHEN depot (so the residents
 * whose beds are closest naturally adopt this one), and couriers already restock KITCHEN depots from
 * the Warehouse. It seeds a bread request so food flows in by default; open it to adjust the requests.
 */
public class BlockFamilyKitchen extends Block {

    private static final int RADIUS = 3; // half-size of the auto KITCHEN polygon

    public BlockFamilyKitchen() {
        super(Material.ROCK);
        setHardness(2.0F);
        setResistance(8F);
        setCreativeTab(CreativeTabs.DECORATIONS);
    }

    @Override
    public boolean hasTileEntity(IBlockState state) { return true; }

    @Override
    public TileEntity createTileEntity(World world, IBlockState state) {
        return new TileEntityDistrictMarker();
    }

    @Override
    public boolean onBlockActivated(World world, BlockPos pos, IBlockState state, EntityPlayer player,
                                    EnumHand hand, EnumFacing facing, float hx, float hy, float hz) {
        if (!world.isRemote) {
            player.openGui(EpochRunnerMod.instance, ErmGuiHandler.GUI_DISTRICT_DEPOT, world,
                    pos.getX(), pos.getY(), pos.getZ());
        }
        return true;
    }

    @Override
    public void onBlockPlacedBy(World world, BlockPos pos, IBlockState state,
                                EntityLivingBase placer, ItemStack stack) {
        super.onBlockPlacedBy(world, pos, state, placer, stack);
        if (world.isRemote) return;
        TileEntity te = world.getTileEntity(pos);
        if (!(te instanceof TileEntityDistrictMarker)) return;
        TileEntityDistrictMarker depot = (TileEntityDistrictMarker) te;

        // Auto-register a small KITCHEN district polygon around this block, bound to it as the depot.
        CivilMarker m = new CivilMarker();
        m.kind = CivilMarker.KITCHEN;
        m.points.add(new BlockPos(pos.getX() - RADIUS, 0, pos.getZ() - RADIUS));
        m.points.add(new BlockPos(pos.getX() + RADIUS, 0, pos.getZ() - RADIUS));
        m.points.add(new BlockPos(pos.getX() + RADIUS, 0, pos.getZ() + RADIUS));
        m.points.add(new BlockPos(pos.getX() - RADIUS, 0, pos.getZ() + RADIUS));
        m.depotPos = pos;
        CivilPlanData plan = CivilPlanData.get(world);
        plan.markers.add(m);
        plan.markDirty();

        depot.setDistrictUid(m.uid);
        depot.setDesiredWorkers(0); // a family kitchen is stocked by couriers, not staffed
        // Seed a couple of food requests so couriers keep it stocked from the Warehouse by default.
        depot.inTemplates.setStackInSlot(0, new ItemStack(Items.BREAD));
        depot.inTemplates.setStackInSlot(1, new ItemStack(Items.COOKED_BEEF));

        int residents = countNearbyBeds(world, pos);
        if (placer instanceof EntityPlayer) {
            ((EntityPlayer) placer).sendMessage(new TextComponentString(TextFormatting.GREEN
                    + "Family Kitchen established" + TextFormatting.GRAY + " — serves the "
                    + residents + " nearest household bed(s); couriers will keep it stocked."));
        }
        EpochRunnerMod.logger.info("[FamilyKitchen] placed at " + pos + " serving ~" + residents + " beds");
    }

    @Override
    public void breakBlock(World world, BlockPos pos, IBlockState state) {
        if (!world.isRemote) {
            // Remove the auto KITCHEN marker bound to this block, then drop the binding.
            CivilPlanData plan = CivilPlanData.get(world);
            plan.markers.removeIf(m -> m.kind == CivilMarker.KITCHEN && pos.equals(m.depotPos));
            plan.markDirty();
            DistrictRegistry.unbindDepot(world, pos);
            // Spill the depot inventory so food isn't lost.
            TileEntity te = world.getTileEntity(pos);
            if (te instanceof TileEntityDistrictMarker) {
                TileEntityDistrictMarker d = (TileEntityDistrictMarker) te;
                for (int i = 0; i < d.depot.getSlots(); i++) {
                    ItemStack s = d.depot.getStackInSlot(i);
                    if (!s.isEmpty()) net.minecraft.inventory.InventoryHelper.spawnItemStack(
                            world, pos.getX(), pos.getY(), pos.getZ(), s);
                }
            }
        }
        super.breakBlock(world, pos, state);
    }

    /** Assigned beds within ~12 blocks — the household this kitchen serves (feedback only). */
    private static int countNearbyBeds(World world, BlockPos pos) {
        int n = 0;
        try {
            for (BedAssignmentData.Claim c : BedAssignmentData.get(world).claims.values()) {
                if (c.bed != null && c.bed.distanceSq(pos) <= 12 * 12) n++;
            }
        } catch (Throwable ignored) {}
        return n;
    }
}
