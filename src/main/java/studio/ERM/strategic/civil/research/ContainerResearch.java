package studio.ERM.strategic.civil.research;

import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.inventory.Container;
import net.minecraft.item.ItemStack;
import net.minecraft.util.math.BlockPos;

/** Slotless backing container for the research-tree screen (lifecycle only; all state is packets). */
public class ContainerResearch extends Container {

    public final BlockPos depot;
    private final EntityPlayer player;

    public ContainerResearch(EntityPlayer player, BlockPos depot) {
        this.player = player;
        this.depot = depot;
    }

    @Override
    public boolean canInteractWith(EntityPlayer p) {
        return p == player && !p.isDead
                && p.getDistanceSq(depot.getX() + 0.5, depot.getY() + 0.5, depot.getZ() + 0.5) <= 100.0;
    }

    @Override
    public ItemStack transferStackInSlot(EntityPlayer p, int index) {
        return ItemStack.EMPTY;
    }
}
