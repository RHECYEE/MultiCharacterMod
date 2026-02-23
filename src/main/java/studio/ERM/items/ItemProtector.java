package studio.ERM.items;

import net.minecraft.creativetab.CreativeTabs;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.util.EnumActionResult;
import net.minecraft.util.EnumFacing;
import net.minecraft.util.EnumHand;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;
import studio.ERM.handlers.ProtectionHandler;
import studio.ERM.war.items.ItemBase;

public class ItemProtector extends ItemBase {
    public ItemProtector() {
        super("homosapien", "entity_protector");
        setCreativeTab(CreativeTabs.COMBAT);
    }

    @Override
    public EnumActionResult onItemUse(EntityPlayer player, World world, BlockPos pos, EnumHand hand, EnumFacing facing, float hitX, float hitY, float hitZ) {
        if (!world.isRemote) {
            ProtectionHandler.toggleProtection(player, pos);
        }
        return EnumActionResult.SUCCESS;
    }
}