package studio.ERM.war.districts;

import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.tileentity.TileEntity;
import net.minecraftforge.items.ItemStackHandler;

/**
 * AN ASSEMBLY SEAT — one station on a Factory's assembly line. Holds a 3×3 GHOST recipe grid (a
 * pattern, never consumed) that defines what this seat crafts; the output is the vanilla crafting
 * result of that grid. Seats placed inside a Factory district are counted by the FactoryManager,
 * which sets the district's employee count and runs each manned seat's craft on a timer.
 *
 * "Chaining adds a recipe each": every seat is its own recipe station, so a row of seats = a row of
 * recipes worked in parallel by the district's assigned workers.
 */
public class TileEntityAssemblySeat extends TileEntity {

    public final ItemStackHandler recipe = new ItemStackHandler(9) {
        @Override protected void onContentsChanged(int slot) { markDirty(); }
    };

    /** Next world-time this seat may craft (transient scheduling lives in FactoryManager too). */
    public long nextCraftTick = 0;

    @Override
    public NBTTagCompound writeToNBT(NBTTagCompound c) {
        super.writeToNBT(c);
        c.setTag("recipe", recipe.serializeNBT());
        return c;
    }

    @Override
    public void readFromNBT(NBTTagCompound c) {
        super.readFromNBT(c);
        if (c.hasKey("recipe")) recipe.deserializeNBT(c.getCompoundTag("recipe"));
    }

    public boolean hasRecipe() {
        for (int i = 0; i < recipe.getSlots(); i++) if (!recipe.getStackInSlot(i).isEmpty()) return true;
        return false;
    }
}
