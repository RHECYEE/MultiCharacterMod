package studio.ERM.war.items;

import net.minecraft.creativetab.CreativeTabs;
import net.minecraft.item.Item;

/**
 * Command Buck - Military district assignment item.
 */
public class ItemCommandBuck extends Item {
    public ItemCommandBuck() {
        setCreativeTab(CreativeTabs.COMBAT);
        setMaxStackSize(64);
    }
}
