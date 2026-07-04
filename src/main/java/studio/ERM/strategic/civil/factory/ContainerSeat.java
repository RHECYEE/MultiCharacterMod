package studio.ERM.strategic.civil.factory;

import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.inventory.ClickType;
import net.minecraft.inventory.Container;
import net.minecraft.inventory.Slot;
import net.minecraft.item.ItemStack;
import net.minecraftforge.items.SlotItemHandler;
import studio.ERM.war.districts.TileEntityAssemblySeat;

/**
 * The assembly-seat recipe editor: a 3×3 grid of GHOST slots (pattern only, never consumes items)
 * over the player inventory. Click a ghost slot with an item on the cursor to set that cell; the
 * output is the vanilla crafting result of the grid (previewed in {@link GuiSeat}).
 */
public class ContainerSeat extends Container {

    public static final int GRID_X = 44, GRID_Y = 20, INV_Y = 84, HOTBAR_Y = 142;

    public final TileEntityAssemblySeat te;
    private final EntityPlayer player;

    public ContainerSeat(EntityPlayer player, TileEntityAssemblySeat te) {
        this.player = player;
        this.te = te;
        for (int r = 0; r < 3; r++) {
            for (int c = 0; c < 3; c++) {
                addSlotToContainer(new Ghost(te.recipe, r * 3 + c, GRID_X + c * 18, GRID_Y + r * 18));
            }
        }
        for (int r = 0; r < 3; r++) {
            for (int c = 0; c < 9; c++) {
                addSlotToContainer(new Slot(player.inventory, c + r * 9 + 9, 8 + c * 18, INV_Y + r * 18));
            }
        }
        for (int c = 0; c < 9; c++) addSlotToContainer(new Slot(player.inventory, c, 8 + c * 18, HOTBAR_Y));
    }

    private static class Ghost extends SlotItemHandler {
        Ghost(net.minecraftforge.items.IItemHandler h, int i, int x, int y) { super(h, i, x, y); }
        @Override public boolean isItemValid(ItemStack s) { return false; }
        @Override public boolean canTakeStack(EntityPlayer p) { return false; }
    }

    @Override
    public ItemStack slotClick(int slotId, int dragType, ClickType clickType, EntityPlayer p) {
        if (slotId >= 0 && slotId < 9 && (clickType == ClickType.PICKUP || clickType == ClickType.PICKUP_ALL
                || clickType == ClickType.QUICK_MOVE || clickType == ClickType.SWAP
                || clickType == ClickType.CLONE || clickType == ClickType.THROW)) {
            ItemStack cursor = p.inventory.getItemStack();
            ItemStack ghost = ItemStack.EMPTY;
            if (clickType == ClickType.PICKUP && !cursor.isEmpty()) { ghost = cursor.copy(); ghost.setCount(1); }
            te.recipe.setStackInSlot(slotId, ghost);
            detectAndSendChanges();
            return ItemStack.EMPTY;
        }
        return super.slotClick(slotId, dragType, clickType, p);
    }

    @Override
    public ItemStack transferStackInSlot(EntityPlayer p, int index) { return ItemStack.EMPTY; }

    @Override
    public boolean canInteractWith(EntityPlayer p) {
        return !te.isInvalid() && p.getDistanceSq(te.getPos().getX() + 0.5, te.getPos().getY() + 0.5,
                te.getPos().getZ() + 0.5) <= 64.0;
    }
}
