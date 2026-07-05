package studio.ERM.war.items;

import net.minecraft.client.util.ITooltipFlag;
import net.minecraft.creativetab.CreativeTabs;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.util.EnumActionResult;
import net.minecraft.util.EnumFacing;
import net.minecraft.util.EnumHand;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.text.TextComponentString;
import net.minecraft.util.text.TextFormatting;
import net.minecraft.world.World;
import net.minecraft.world.WorldServer;
import net.minecraftforge.fml.relauncher.Side;
import net.minecraftforge.fml.relauncher.SideOnly;
import studio.ERM.EpochRunnerMod;
import studio.ERM.strategic.Aw2Structures;

import javax.annotation.Nullable;
import java.util.List;

/**
 * GIANT SCHEMATIC PLACER — an in-hand builder's tool for dropping the pack's LANDMARK structures by
 * hand: skyscrapers, cooling towers, factories. Sneak-right-click cycles the selected schematic;
 * right-click a block places the currently-selected giant template at that spot (facing the player),
 * resolved from whatever matching AW2 .aws templates the installed pack has loaded (keyword match, so
 * it works across packs). An admin/creative construction tool — no cost, no cooldown.
 */
public class ItemGiantSchematic extends Item {

    /** Each selection is a human name + the keywords used to find a matching loaded AW2 template. */
    public enum Giant {
        SKYSCRAPER("Skyscraper", new String[]{"skyscraper", "highrise", "high_rise", "tower_block", "office"}),
        COOLING_TOWER("Cooling Tower", new String[]{"cooling", "coolingtower", "reactor", "nuclear", "powerplant", "power_plant"}),
        FACTORY("Factory", new String[]{"factory", "industrial", "refinery", "warehouse", "mill", "plant"});

        public final String label;
        public final String[] keywords;
        Giant(String label, String[] keywords) { this.label = label; this.keywords = keywords; }

        public static Giant byIndex(int i) {
            Giant[] v = values();
            return v[Math.floorMod(i, v.length)];
        }
    }

    public ItemGiantSchematic() {
        setMaxStackSize(1);
        setCreativeTab(CreativeTabs.TOOLS);
    }

    /** Sneak-right-click in the AIR cycles the selection (block-clicks route through onItemUse). */
    @Override
    public net.minecraft.util.ActionResult<ItemStack> onItemRightClick(World world, EntityPlayer player, EnumHand hand) {
        ItemStack stack = player.getHeldItem(hand);
        if (player.isSneaking()) {
            if (!world.isRemote) cycle(stack, player);
            return new net.minecraft.util.ActionResult<>(EnumActionResult.SUCCESS, stack);
        }
        return new net.minecraft.util.ActionResult<>(EnumActionResult.PASS, stack);
    }

    @Override
    public EnumActionResult onItemUse(EntityPlayer player, World world, BlockPos pos, EnumHand hand,
                                      EnumFacing facing, float hitX, float hitY, float hitZ) {
        ItemStack stack = player.getHeldItem(hand);

        // Sneak = cycle the selection instead of placing (works whether aiming at ground or air).
        if (player.isSneaking()) {
            if (!world.isRemote) cycle(stack, player);
            return EnumActionResult.SUCCESS;
        }
        if (world.isRemote) return EnumActionResult.SUCCESS;
        if (!(world instanceof WorldServer)) return EnumActionResult.FAIL;

        Giant g = Giant.byIndex(getIndex(stack));
        String template = Aw2Structures.pick(null, null, g.keywords);
        if (template == null) {
            player.sendMessage(new TextComponentString(TextFormatting.RED
                    + "No " + g.label + " schematic is loaded in this pack (nothing matched "
                    + String.join(", ", g.keywords) + ")."));
            return EnumActionResult.FAIL;
        }

        // Place on top of the clicked block, facing back toward the player so the entrance reads right.
        BlockPos anchor = pos.up();
        EnumFacing face = player.getHorizontalFacing().getOpposite();
        boolean ok = Aw2Structures.place((WorldServer) world, template, anchor, face);
        if (ok) {
            player.sendMessage(new TextComponentString(TextFormatting.GREEN + g.label + " raised — "
                    + TextFormatting.YELLOW + template + TextFormatting.GREEN + " at "
                    + anchor.getX() + ", " + anchor.getY() + ", " + anchor.getZ() + "."));
            EpochRunnerMod.logger.info("[GiantSchematic] " + player.getName() + " placed " + template
                    + " (" + g.label + ") @ " + anchor);
        } else {
            player.sendMessage(new TextComponentString(TextFormatting.RED
                    + "Couldn't raise the " + g.label + " here (chunk not ready or placement failed)."));
        }
        return ok ? EnumActionResult.SUCCESS : EnumActionResult.FAIL;
    }

    private void cycle(ItemStack stack, EntityPlayer player) {
        int next = getIndex(stack) + 1;
        setIndex(stack, next);
        Giant g = Giant.byIndex(next);
        player.sendMessage(new TextComponentString(TextFormatting.AQUA + "Giant schematic: "
                + TextFormatting.WHITE + g.label + TextFormatting.GRAY + " (right-click a block to place)."));
    }

    @Override
    @SideOnly(Side.CLIENT)
    public void addInformation(ItemStack stack, @Nullable World world, List<String> tooltip, ITooltipFlag flag) {
        Giant g = Giant.byIndex(getIndex(stack));
        tooltip.add(TextFormatting.GOLD + "Selected: " + TextFormatting.WHITE + g.label);
        tooltip.add(TextFormatting.DARK_GRAY + "Right-click a block: place it here");
        tooltip.add(TextFormatting.DARK_GRAY + "Sneak + right-click: cycle schematic");
    }

    public static int getIndex(ItemStack stack) {
        NBTTagCompound tag = stack.getTagCompound();
        return (tag != null && tag.hasKey("giant")) ? tag.getInteger("giant") : 0;
    }

    public static void setIndex(ItemStack stack, int index) {
        NBTTagCompound tag = stack.getTagCompound();
        if (tag == null) { tag = new NBTTagCompound(); stack.setTagCompound(tag); }
        tag.setInteger("giant", Math.floorMod(index, Giant.values().length));
    }
}
