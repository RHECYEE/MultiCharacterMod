package studio.ERM.war.items;

import net.minecraft.creativetab.CreativeTabs;
import net.minecraft.item.Item;

/**
 * Gold Wrench - alias for sabotage fixer.
 */
public class ItemGoldWrench extends Item {
    public ItemGoldWrench() {
        setCreativeTab(CreativeTabs.TOOLS);
        setMaxStackSize(1);
        setMaxDamage(64);
    }
}
