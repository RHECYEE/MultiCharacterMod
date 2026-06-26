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
}
