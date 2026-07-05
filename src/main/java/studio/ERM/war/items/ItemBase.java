package studio.ERM.war.items;

import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;

public class ItemBase extends Item {
    private final String modid;
    protected String name;

    public ItemBase(String modid, String name) {
        this.modid = modid;
        this.name = name; // e.g. "infantry_ammo"
        this.setRegistryName(modid, name);
    }

    // FIX: Hardcode the English name directly into the item tooltip.
    // This bypasses the broken "item.null.name" translation issue entirely.
    @Override
    public String getItemStackDisplayName(ItemStack stack) {
        // Convert "infantry_ammo" to "Infantry Ammo"
        String raw = this.name.replace("_", " ");
        // Capitalize first letters
        StringBuilder result = new StringBuilder();
        for(String word : raw.split(" ")) {
            if(!word.isEmpty()) {
                result.append(Character.toUpperCase(word.charAt(0))).append(word.substring(1)).append(" ");
            }
        }
        return result.toString().trim();
    }
}