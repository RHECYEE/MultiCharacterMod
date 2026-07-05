package studio.ERM.strategic.civil.armory;

import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.inventory.ClickType;
import net.minecraft.inventory.Container;
import net.minecraft.inventory.IContainerListener;
import net.minecraft.inventory.Slot;
import net.minecraft.item.ItemStack;
import net.minecraftforge.items.SlotItemHandler;
import studio.ERM.war.districts.TileEntityDistrictMarker;

/**
 * THE ARMORY — the main combat-supply hub. Six LOADOUT stands (each 6 ghost slots: hand, offhand,
 * helm, chest, legs, boots) define the kit patterns; a per-loadout COUNT sets how many soldiers to
 * equip with each. The real DEPOT stock below is what's actually issued (and what couriers restock).
 *
 * Slots: 0..35 loadout ghosts (row-major, 6×6) · 36..62 depot stock · 63..98 player inv + hotbar.
 */
public class ContainerArmory extends Container {

    public static final int LOAD_X = 26, LOAD_Y = 32, ROW_H = 20;
    public static final int DEPOT_X = 17, DEPOT_Y = 166;
    public static final int INV_X = 17, INV_Y = 232, HOTBAR_Y = 290;
    public static final int LOADOUT_END = TileEntityDistrictMarker.LOADOUTS * TileEntityDistrictMarker.LOADOUT_SLOTS; // 36
    public static final int DEPOT_END = LOADOUT_END + TileEntityDistrictMarker.DEPOT_SLOTS; // 63

    public final TileEntityDistrictMarker te;
    private final EntityPlayer player;
    public final int[] counts = new int[TileEntityDistrictMarker.LOADOUTS];

    public ContainerArmory(EntityPlayer player, TileEntityDistrictMarker te) {
        this.player = player;
        this.te = te;

        for (int row = 0; row < TileEntityDistrictMarker.LOADOUTS; row++) {
            for (int col = 0; col < TileEntityDistrictMarker.LOADOUT_SLOTS; col++) {
                addSlotToContainer(new GhostSlot(te.loadouts, row * TileEntityDistrictMarker.LOADOUT_SLOTS + col,
                        LOAD_X + col * 18, LOAD_Y + row * ROW_H));
            }
        }
        for (int i = 0; i < TileEntityDistrictMarker.DEPOT_SLOTS; i++) {
            addSlotToContainer(new SlotItemHandler(te.depot, i, DEPOT_X + (i % 9) * 18, DEPOT_Y + (i / 9) * 18));
        }
        for (int r = 0; r < 3; r++) {
            for (int c = 0; c < 9; c++) {
                addSlotToContainer(new Slot(player.inventory, c + r * 9 + 9, INV_X + c * 18, INV_Y + r * 18));
            }
        }
        for (int c = 0; c < 9; c++) addSlotToContainer(new Slot(player.inventory, c, INV_X + c * 18, HOTBAR_Y));
    }

    private static class GhostSlot extends SlotItemHandler {
        GhostSlot(net.minecraftforge.items.IItemHandler h, int i, int x, int y) { super(h, i, x, y); }
        @Override public boolean isItemValid(ItemStack s) { return false; }
        @Override public boolean canTakeStack(EntityPlayer p) { return false; }
    }

    private boolean isLoadout(int id) { return id >= 0 && id < LOADOUT_END; }

    @Override
    public ItemStack slotClick(int slotId, int dragType, ClickType clickType, EntityPlayer p) {
        if (isLoadout(slotId) && (clickType == ClickType.PICKUP || clickType == ClickType.PICKUP_ALL
                || clickType == ClickType.QUICK_MOVE || clickType == ClickType.SWAP
                || clickType == ClickType.CLONE || clickType == ClickType.THROW)) {
            ItemStack cursor = p.inventory.getItemStack();
            ItemStack ghost = ItemStack.EMPTY;
            if (clickType == ClickType.PICKUP && !cursor.isEmpty()) { ghost = cursor.copy(); ghost.setCount(1); }
            te.loadouts.setStackInSlot(slotId, ghost);
            detectAndSendChanges();
            return ItemStack.EMPTY;
        }
        return super.slotClick(slotId, dragType, clickType, p);
    }

    @Override
    public ItemStack transferStackInSlot(EntityPlayer p, int index) {
        if (isLoadout(index)) return ItemStack.EMPTY;
        ItemStack copy = ItemStack.EMPTY;
        Slot slot = inventorySlots.get(index);
        if (slot != null && slot.getHasStack()) {
            ItemStack stack = slot.getStack();
            copy = stack.copy();
            if (index < DEPOT_END) { // depot -> player
                if (!mergeItemStack(stack, DEPOT_END, DEPOT_END + 36, true)) return ItemStack.EMPTY;
            } else {                 // player -> depot
                if (!mergeItemStack(stack, LOADOUT_END, DEPOT_END, false)) return ItemStack.EMPTY;
            }
            if (stack.isEmpty()) slot.putStack(ItemStack.EMPTY); else slot.onSlotChanged();
        }
        return copy;
    }

    @Override
    public boolean canInteractWith(EntityPlayer p) {
        return !te.isInvalid() && p.getDistanceSq(te.getPos().getX() + 0.5, te.getPos().getY() + 0.5,
                te.getPos().getZ() + 0.5) <= 64.0;
    }

    @Override
    public void detectAndSendChanges() {
        super.detectAndSendChanges();
        if (player.world.isRemote) return;
        for (IContainerListener l : listeners) {
            for (int i = 0; i < counts.length; i++) {
                int c = te.getLoadoutCount(i);
                if (c != counts[i]) l.sendWindowProperty(this, i, c);
            }
        }
        for (int i = 0; i < counts.length; i++) counts[i] = te.getLoadoutCount(i);
    }

    @Override
    public void addListener(IContainerListener listener) {
        super.addListener(listener);
        for (int i = 0; i < counts.length; i++) listener.sendWindowProperty(this, i, te.getLoadoutCount(i));
    }

    @Override
    public void updateProgressBar(int id, int value) {
        if (id >= 0 && id < counts.length) counts[id] = value;
    }
}
