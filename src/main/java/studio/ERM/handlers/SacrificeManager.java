package studio.ERM.handlers;

import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.util.ResourceLocation;
import net.minecraftforge.fml.common.registry.ForgeRegistries;
import studio.ERM.EpochRunnerMod;

public class SacrificeManager {

    /**
     * Checks if the player has the required item to skip the next invasion.
     * @param player The player entity to check.
     * @param itemRegistryName The registry name of the required item (e.g., "minecraft:diamond").
     * @param count The required number of items.
     * @return True if the item is consumed and the invasion should be skipped, false otherwise.
     */
    public static boolean attemptSacrifice(EntityPlayer player, String itemRegistryName, int count) {
        if (player == null || itemRegistryName == null || itemRegistryName.isEmpty() || count <= 0) {
            return false;
        }

        Item requiredItem = ForgeRegistries.ITEMS.getValue(new ResourceLocation(itemRegistryName));

        if (requiredItem == null) {
            EpochRunnerMod.logger.error("Sacrifice item not found: " + itemRegistryName);
            return false;
        }

        // Check if player has the total required count
        int totalFound = 0;

        // Loop through player's entire inventory (including armor and offhand)
        for (ItemStack stack : player.inventory.mainInventory) {
            if (stack.getItem() == requiredItem) {
                totalFound += stack.getCount();
            }
        }

        if (totalFound >= count) {
            // Player has enough, now consume the items
            int remainingToConsume = count;

            // Loop and consume items from the inventory until count is met
            for (int i = 0; i < player.inventory.mainInventory.size(); i++) {
                ItemStack stack = player.inventory.mainInventory.get(i);

                if (stack.getItem() == requiredItem) {
                    int consumed = Math.min(stack.getCount(), remainingToConsume);
                    stack.shrink(consumed);
                    remainingToConsume -= consumed;

                    if (remainingToConsume <= 0) {
                        break;
                    }
                }
            }

            // Send success message to player
            player.sendMessage(new net.minecraft.util.text.TextComponentString(
                    net.minecraft.util.text.TextFormatting.GREEN + "[EpochRunner] Sacrifice successful! The invasion has been temporarily averted."
            ));

            EpochRunnerMod.logger.info("Player " + player.getName() + " sacrificed " + count + "x " + itemRegistryName + " to skip the invasion.");
            return true;
        }

        return false;
    }
}