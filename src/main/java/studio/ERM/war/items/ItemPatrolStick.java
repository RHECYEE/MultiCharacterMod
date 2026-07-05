package studio.ERM.war.items;

import net.minecraft.creativetab.CreativeTabs;
import net.minecraft.entity.EntityLivingBase;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.util.EnumActionResult;
import net.minecraft.util.EnumFacing;
import net.minecraft.util.EnumHand;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;

import studio.ERM.war.entities.EntityModularCitizen;

import javax.annotation.Nullable;

public class ItemPatrolStick extends Item {

    private static final String NBT_WP1 = "erm_patrol_wp1";
    private static final String NBT_WP2 = "erm_patrol_wp2";

    public ItemPatrolStick() {
        super();
        this.setMaxStackSize(1);
        this.setCreativeTab(CreativeTabs.TOOLS);
    }

    @Override
    public EnumActionResult onItemUse(EntityPlayer player, World worldIn, BlockPos pos, EnumHand hand, EnumFacing facing,
                                     float hitX, float hitY, float hitZ) {
        ItemStack stack = player.getHeldItem(hand);
        if (stack.isEmpty()) return EnumActionResult.FAIL;
        if (!player.canPlayerEdit(pos, facing, stack)) return EnumActionResult.FAIL;

        if (!worldIn.isRemote) {
            BlockPos waypoint = pos.offset(facing);
            NBTTagCompound tag = getOrCreateTag(stack);

            BlockPos wp1 = readPos(tag, NBT_WP1);
            BlockPos wp2 = readPos(tag, NBT_WP2);

            if (wp1 == null) {
                writePos(tag, NBT_WP1, waypoint);
                player.sendMessage(new net.minecraft.util.text.TextComponentString("Patrol WP1 set: " + fmt(waypoint)));
            } else if (wp2 == null) {
                writePos(tag, NBT_WP2, waypoint);
                player.sendMessage(new net.minecraft.util.text.TextComponentString("Patrol WP2 set: " + fmt(waypoint)));
            } else {
                tag.removeTag(NBT_WP1);
                tag.removeTag(NBT_WP2);
                writePos(tag, NBT_WP1, waypoint);
                player.sendMessage(new net.minecraft.util.text.TextComponentString("Patrol reset. WP1 set: " + fmt(waypoint)));
            }
        }

        return EnumActionResult.SUCCESS;
    }

    @Override
    public boolean itemInteractionForEntity(ItemStack stack, EntityPlayer player, EntityLivingBase target, EnumHand hand) {
        if (!(target instanceof EntityModularCitizen)) {
            return false;
        }

        EntityModularCitizen citizen = (EntityModularCitizen) target;

        if (!player.world.isRemote) {
            if (player.isSneaking()) {
                citizen.stopPatrolling();
                player.sendMessage(new net.minecraft.util.text.TextComponentString("Citizen patrol stopped."));
                return true;
            }

            NBTTagCompound tag = getOrCreateTag(stack);
            BlockPos wp1 = readPos(tag, NBT_WP1);
            BlockPos wp2 = readPos(tag, NBT_WP2);

            if (wp1 == null || wp2 == null) {
                player.sendMessage(new net.minecraft.util.text.TextComponentString("Set WP1 and WP2 on blocks first."));
                return true;
            }

            citizen.setPatrolRoute(wp1, wp2);
            citizen.startPatrolling();
            player.sendMessage(new net.minecraft.util.text.TextComponentString("Citizen patrol route set: " + fmt(wp1) + " <-> " + fmt(wp2)));
            return true;
        }

        return true;
    }

    private static NBTTagCompound getOrCreateTag(ItemStack stack) {
        if (!stack.hasTagCompound()) stack.setTagCompound(new NBTTagCompound());
        return stack.getTagCompound();
    }

    @Nullable
    private static BlockPos readPos(NBTTagCompound tag, String key) {
        if (tag == null) return null;
        if (!tag.hasKey(key, 10)) return null;
        NBTTagCompound c = tag.getCompoundTag(key);
        if (!c.hasKey("x", 3)) return null;
        return new BlockPos(c.getInteger("x"), c.getInteger("y"), c.getInteger("z"));
    }

    private static void writePos(NBTTagCompound tag, String key, BlockPos pos) {
        NBTTagCompound c = new NBTTagCompound();
        c.setInteger("x", pos.getX());
        c.setInteger("y", pos.getY());
        c.setInteger("z", pos.getZ());
        tag.setTag(key, c);
    }

    private static String fmt(BlockPos p) {
        return p.getX() + ", " + p.getY() + ", " + p.getZ();
    }
}
