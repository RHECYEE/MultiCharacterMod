package studio.ERM.war.items;

import net.minecraft.creativetab.CreativeTabs;
import net.minecraft.item.Item;

/**
 * Blueprint - Used for construction/engineering tasks.
 */
public class ItemBlueprint extends Item {
    public ItemBlueprint() {
        setCreativeTab(CreativeTabs.MISC);
        setMaxStackSize(16);
    }
}
