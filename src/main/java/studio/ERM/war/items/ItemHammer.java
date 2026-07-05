package studio.ERM.war.items;

import net.minecraft.creativetab.CreativeTabs;
import net.minecraft.item.Item;

/**
 * Hammer item for Builder job assignment.
 * When given to a citizen, they become a builder and rebuild scaffolds.
 */
public class ItemHammer extends Item {
    public ItemHammer() {
        setCreativeTab(CreativeTabs.TOOLS);
        setMaxStackSize(1);
        setMaxDamage(256); // Durability
    }
}
