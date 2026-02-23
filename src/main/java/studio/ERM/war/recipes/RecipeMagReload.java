package co.runed.multicharacter.compat;

import net.minecraft.inventory.InventoryCrafting;
import net.minecraft.item.ItemStack;
import net.minecraft.item.crafting.IRecipe;
import net.minecraft.util.NonNullList;
import net.minecraft.world.World;
import net.minecraftforge.registries.IForgeRegistryEntry;

public class RecipeMagReload extends IForgeRegistryEntry.Impl<IRecipe> implements IRecipe {

    @Override
    public boolean matches(InventoryCrafting inv, World worldIn) {
        ItemStack mag = ItemStack.EMPTY;
        int ammoCount = 0;

        for (int i = 0; i < inv.getSizeInventory(); i++) {
            ItemStack stack = inv.getStackInSlot(i);
            if (!stack.isEmpty()) {
                if (stack.getItem() instanceof ItemInfantryMag) {
                    if (!mag.isEmpty()) return false;
                    mag = stack;
                } else if (stack.getItem() == ModItems.INFANTRY_AMMO) {
                    ammoCount++;
                } else {
                    return false;
                }
            }
        }
        return !mag.isEmpty() && ammoCount > 0 && mag.getItemDamage() > 0;
    }

    @Override
    public ItemStack getCraftingResult(InventoryCrafting inv) {
        ItemStack mag = ItemStack.EMPTY;
        int ammoCount = 0;

        for (int i = 0; i < inv.getSizeInventory(); i++) {
            ItemStack stack = inv.getStackInSlot(i);
            if (!stack.isEmpty()) {
                if (stack.getItem() instanceof ItemInfantryMag) {
                    mag = stack.copy();
                } else if (stack.getItem() == ModItems.INFANTRY_AMMO) {
                    ammoCount++;
                }
            }
        }

        if (mag.isEmpty()) return ItemStack.EMPTY;
        int newDamage = Math.max(0, mag.getItemDamage() - ammoCount);
        mag.setItemDamage(newDamage);
        return mag;
    }

    @Override
    public boolean canFit(int width, int height) { return width * height >= 2; }

    @Override
    public ItemStack getRecipeOutput() { return ItemStack.EMPTY; }

    @Override
    public NonNullList<ItemStack> getRemainingItems(InventoryCrafting inv) {
        return NonNullList.withSize(inv.getSizeInventory(), ItemStack.EMPTY);
    }
}