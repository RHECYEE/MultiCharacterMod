package studio.ERM.items;

import net.minecraft.creativetab.CreativeTabs;
import net.minecraft.client.util.ITooltipFlag;
import net.minecraft.entity.EntityLivingBase;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.util.ActionResult;
import net.minecraft.util.EnumActionResult;
import net.minecraft.util.EnumFacing;
import net.minecraft.util.EnumHand;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.text.TextComponentString;
import net.minecraft.util.text.TextFormatting;
import net.minecraft.world.World;
import net.minecraftforge.items.CapabilityItemHandler;
import studio.ERM.EpochRunnerMod;
import studio.ERM.handlers.ProtectionHandler;
import studio.ERM.strategic.civil.CivilMarker;
import studio.ERM.strategic.civil.CivilPlanData;
import studio.ERM.strategic.civil.DistrictRegistry;
import studio.ERM.strategic.defense.ErmGuiHandler;
import studio.ERM.war.districts.TileEntityDistrictMarker;
import studio.ERM.war.items.ItemBase;

import javax.annotation.Nullable;
import java.util.List;

/**
 * THE PEN & PAPER — the protection stick evolved into the settlement's administrative multi-tool.
 * One item, many modes; instead of carrying a wand per system the player cycles what it IS:
 *
 *   shift + LEFT-click   cycle mode (Protection Stick -> Courier Marker -> ...)
 *   shift + RIGHT-click  the current mode's menu/summary
 *   RIGHT-click block    the current mode's action
 *
 * Modes:
 *   PROTECTION — the original stick: toggle block protection (restore-after-battle).
 *   COURIER    — right-click a District Marker block to bind it as the depot of the district
 *                polygon containing it and open the Universal District Controller (worker count +
 *                20 IN / 20 OUT courier request templates). The depot persists: couriers and
 *                workers die, the setup does not.
 */
public class ItemProtector extends ItemBase {

    public static final int MODE_PROTECTION = 0;
    public static final int MODE_COURIER = 1;

    public static final String[] MODE_NAMES = {"Protection Stick", "Courier Marker"};

    private static final String NBT_MODE = "erm_pp_mode";
    private static final String NBT_CYCLE_T = "erm_pp_cycle_t";

    public ItemProtector() {
        super("homosapien", "entity_protector");
        setCreativeTab(CreativeTabs.COMBAT);
        setMaxStackSize(1);
    }

    public static int getMode(ItemStack stack) {
        NBTTagCompound tag = stack.getTagCompound();
        int m = tag != null ? tag.getInteger(NBT_MODE) : 0;
        return (m >= 0 && m < MODE_NAMES.length) ? m : 0;
    }

    private static NBTTagCompound tag(ItemStack stack) {
        if (stack.getTagCompound() == null) stack.setTagCompound(new NBTTagCompound());
        return stack.getTagCompound();
    }

    // ------------------------------------------------------------------
    // shift + LEFT-click: cycle mode. onEntitySwing fires server-side too (CPacketAnimation ->
    // swingArm), so the cycle is authoritative; the held-stack NBT change syncs back to the
    // client. Debounced because digging re-swings every few ticks.
    // ------------------------------------------------------------------

    @Override
    public boolean onEntitySwing(EntityLivingBase living, ItemStack stack) {
        if (!living.world.isRemote && living.isSneaking() && living instanceof EntityPlayer) {
            NBTTagCompound tag = tag(stack);
            long now = living.world.getTotalWorldTime();
            if (now - tag.getLong(NBT_CYCLE_T) >= 8) {
                tag.setLong(NBT_CYCLE_T, now);
                int mode = (getMode(stack) + 1) % MODE_NAMES.length;
                tag.setInteger(NBT_MODE, mode);
                ((EntityPlayer) living).sendMessage(new TextComponentString(
                        TextFormatting.GOLD + "Pen & Paper" + TextFormatting.GRAY + " -> "
                                + TextFormatting.AQUA + MODE_NAMES[mode]
                                + TextFormatting.DARK_GRAY + "  (" + hint(mode) + ")"));
            }
        }
        return false;
    }

    private static String hint(int mode) {
        switch (mode) {
            case MODE_COURIER: return "right-click a District Marker block inside a drawn district";
            default:           return "right-click a block to toggle protection";
        }
    }

    // ------------------------------------------------------------------
    // RIGHT-click block: the mode's action. shift variant = menu/summary.
    // onItemUseFirst (not onItemUse) so the tool preempts BLOCK activation — otherwise the
    // depot/chest/door GUI would swallow the click and the mode action could never fire.
    // ------------------------------------------------------------------

    @Override
    public EnumActionResult onItemUseFirst(EntityPlayer player, World world, BlockPos pos, EnumFacing side,
                                           float hitX, float hitY, float hitZ, EnumHand hand) {
        ItemStack stack = player.getHeldItem(hand);
        int mode = getMode(stack);

        if (player.isSneaking()) {
            if (!world.isRemote) sendModeMenu(player, world, mode);
            return EnumActionResult.SUCCESS;
        }

        switch (mode) {
            case MODE_COURIER:
                if (!world.isRemote) courierUse(player, world, pos);
                return EnumActionResult.SUCCESS;
            case MODE_PROTECTION:
            default:
                if (!world.isRemote) ProtectionHandler.toggleProtection(player, pos);
                return EnumActionResult.SUCCESS;
        }
    }

    @Override
    public ActionResult<ItemStack> onItemRightClick(World world, EntityPlayer player, EnumHand hand) {
        ItemStack stack = player.getHeldItem(hand);
        if (player.isSneaking()) {
            if (!world.isRemote) sendModeMenu(player, world, getMode(stack));
            return new ActionResult<>(EnumActionResult.SUCCESS, stack);
        }
        return new ActionResult<>(EnumActionResult.PASS, stack);
    }

    /**
     * COURIER MARKER action: on a District Marker block -> bind it as the containing polygon's
     * depot and open the controller. On any other inventory -> explain what qualifies (chest
     * logistics endpoints arrive with the global courier manager).
     */
    private static void courierUse(EntityPlayer player, World world, BlockPos pos) {
        TileEntity te = world.getTileEntity(pos);

        if (te instanceof TileEntityDistrictMarker) {
            CivilMarker district = DistrictRegistry.bindDepot(world, pos);
            if (district != null) {
                player.sendMessage(new TextComponentString(TextFormatting.GREEN + "Depot bound: "
                        + TextFormatting.AQUA + CivilMarker.nameOf(district.kind) + " district"
                        + TextFormatting.GRAY + " #" + (district.uid & 0xFFFF)));
            } else {
                player.sendMessage(new TextComponentString(TextFormatting.YELLOW
                        + "No district here — draw a district polygon around this spot on the map's "
                        + "Civilian tab first (the depot still opens, unbound)."));
            }
            player.openGui(EpochRunnerMod.instance, ErmGuiHandler.GUI_DISTRICT_DEPOT, world,
                    pos.getX(), pos.getY(), pos.getZ());
            return;
        }

        if (te != null && te.hasCapability(CapabilityItemHandler.ITEM_HANDLER_CAPABILITY, null)) {
            player.sendMessage(new TextComponentString(TextFormatting.YELLOW
                    + "Only a District Marker block can be a depot. (Marking plain chests as "
                    + "logistics endpoints comes with the settlement courier update.)"));
        } else {
            player.sendMessage(new TextComponentString(TextFormatting.GRAY
                    + "Courier Marker: right-click a District Marker block inside a drawn district."));
        }
    }

    /** shift-RIGHT-click: the mode's "menu" — a status summary of what this mode manages. */
    private static void sendModeMenu(EntityPlayer player, World world, int mode) {
        switch (mode) {
            case MODE_COURIER: {
                int districts = 0, bound = 0;
                for (CivilMarker m : CivilPlanData.get(world).markers) {
                    if (m.isRoad()) continue;
                    districts++;
                    if (m.hasDepot()) bound++;
                }
                player.sendMessage(new TextComponentString(TextFormatting.GOLD + "[Courier Marker] "
                        + TextFormatting.GRAY + "districts drawn: " + TextFormatting.WHITE + districts
                        + TextFormatting.GRAY + "  with depot: " + TextFormatting.WHITE + bound
                        + TextFormatting.GRAY + "  workers hired: " + TextFormatting.WHITE
                        + studio.ERM.strategic.civil.DistrictWorkExecutor.assignedCount()
                        + TextFormatting.DARK_GRAY + "  (rival level "
                        + DistrictRegistry.rivalLevel(world) + " gates output tables)"));
                break;
            }
            case MODE_PROTECTION:
            default:
                player.sendMessage(new TextComponentString(TextFormatting.GOLD + "[Protection Stick] "
                        + TextFormatting.GRAY + "right-click toggles battle-damage protection on a block; "
                        + "protected blocks restore after sieges."));
                break;
        }
    }

    // ------------------------------------------------------------------
    // Presentation
    // ------------------------------------------------------------------

    @Override
    public String getItemStackDisplayName(ItemStack stack) {
        return "Pen & Paper — " + MODE_NAMES[getMode(stack)];
    }

    @Override
    public void addInformation(ItemStack stack, @Nullable World world, List<String> tooltip, ITooltipFlag flag) {
        tooltip.add(TextFormatting.GRAY + "Shift+Left-click: cycle tool");
        tooltip.add(TextFormatting.GRAY + "Shift+Right-click: tool menu");
        tooltip.add(TextFormatting.DARK_GRAY + hint(getMode(stack)));
    }
}
