package studio.ERM.strategic.civil.trade;

import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.inventory.Container;
import net.minecraft.item.ItemStack;
import net.minecraft.util.math.BlockPos;

/**
 * Slotless backing container for the Trade Depot market GUI — exists only to give the screen a proper
 * open/close lifecycle; all trade happens over {@link studio.ERM.war.map.net.C2STradeAction} packets.
 */
public class ContainerTradeDepot extends Container {

    public final BlockPos depot;
    private final EntityPlayer player;

    public ContainerTradeDepot(EntityPlayer player, BlockPos depot) {
        this.player = player;
        this.depot = depot;
    }

    @Override
    public boolean canInteractWith(EntityPlayer p) {
        return p == player && !p.isDead
                && p.getDistanceSq(depot.getX() + 0.5, depot.getY() + 0.5, depot.getZ() + 0.5) <= 64.0;
    }

    @Override
    public ItemStack transferStackInSlot(EntityPlayer p, int index) {
        return ItemStack.EMPTY;
    }
}
