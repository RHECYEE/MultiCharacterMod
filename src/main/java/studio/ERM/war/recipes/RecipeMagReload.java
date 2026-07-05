package studio.ERM.war.recipes;

import net.minecraft.inventory.InventoryCrafting;
import net.minecraft.item.ItemStack;
import net.minecraft.item.crafting.IRecipe;
import net.minecraft.world.World;
import net.minecraftforge.registries.IForgeRegistryEntry;

public class RecipeMagReload extends IForgeRegistryEntry.Impl<IRecipe> implements IRecipe {
    @Override public boolean matches(InventoryCrafting inv, World world) { return false; }
    @Override public ItemStack getCraftingResult(InventoryCrafting inv) { return ItemStack.EMPTY; }
    @Override public boolean canFit(int w, int h) { return false; }
    @Override public ItemStack getRecipeOutput() { return ItemStack.EMPTY; }
    // CRITICAL: mark DYNAMIC so vanilla EXCLUDES this from the recipe book / recipe-unlock system.
    // As a non-dynamic recipe with an EMPTY output it was being fed into the advancement recipe-unlock
    // path (Container.detectAndSendChanges -> InventoryChangeTrigger -> unlockRecipes -> sendRecipeBook),
    // which NPE-crashed "Ticking player" whenever the player's inventory changed.
    @Override public boolean isDynamic() { return true; }
}
