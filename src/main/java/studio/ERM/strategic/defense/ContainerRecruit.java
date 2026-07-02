package studio.ERM.strategic.defense;

import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.inventory.Container;
import net.minecraft.inventory.InventoryBasic;
import net.minecraft.inventory.Slot;
import net.minecraft.item.ItemStack;

/**
 * PHASE 2 — the RECRUIT loadout container (chest-style): one row of 9 LOADOUT slots on top (0 mainhand,
 * 1 offhand, 2 head, 3 chest, 4 legs, 5 feet, 6-8 supplies/ammo) with the player's inventory below.
 * The player physically builds the recruit's kit from their own items; CONFIRM consumes the gear +
 * the Command-Buck fee. Closing without confirming returns everything.
 */
public class ContainerRecruit extends Container {

    public final InventoryBasic loadout = new InventoryBasic("recruit_loadout", false, 9);
    private final EntityPlayer player;

    public ContainerRecruit(EntityPlayer player) {
        this.player = player;

        // Loadout row (chest-style top row).
        for (int i = 0; i < 9; i++) {
            addSlotToContainer(new Slot(loadout, i, 8 + i * 18, 18));
        }
        // Player inventory (rows) + hotbar, standard 1-row-chest offsets.
        for (int row = 0; row < 3; row++) {
            for (int col = 0; col < 9; col++) {
                addSlotToContainer(new Slot(player.inventory, col + row * 9 + 9, 8 + col * 18, 49 + row * 18));
            }
        }
        for (int col = 0; col < 9; col++) {
            addSlotToContainer(new Slot(player.inventory, col, 8 + col * 18, 107));
        }
    }

    @Override
    public boolean canInteractWith(EntityPlayer playerIn) {
        return playerIn == player && !playerIn.isDead;
    }

    @Override
    public ItemStack transferStackInSlot(EntityPlayer playerIn, int index) {
        ItemStack copy = ItemStack.EMPTY;
        Slot slot = inventorySlots.get(index);
        if (slot != null && slot.getHasStack()) {
            ItemStack stack = slot.getStack();
            copy = stack.copy();
            if (index < 9) { // loadout -> player inventory
                if (!mergeItemStack(stack, 9, 45, true)) return ItemStack.EMPTY;
            } else {         // player inventory -> loadout
                if (!mergeItemStack(stack, 0, 9, false)) return ItemStack.EMPTY;
            }
            if (stack.isEmpty()) slot.putStack(ItemStack.EMPTY);
            else slot.onSlotChanged();
        }
        return copy;
    }

    @Override
    public void onContainerClosed(EntityPlayer playerIn) {
        super.onContainerClosed(playerIn);
        // Not confirmed (or leftovers): give the gear back — the contract only consumes on CONFIRM.
        if (!playerIn.world.isRemote) {
            for (int i = 0; i < loadout.getSizeInventory(); i++) {
                ItemStack st = loadout.removeStackFromSlot(i);
                if (!st.isEmpty() && !playerIn.inventory.addItemStackToInventory(st)) {
                    playerIn.dropItem(st, false);
                }
            }
        }
    }
}
