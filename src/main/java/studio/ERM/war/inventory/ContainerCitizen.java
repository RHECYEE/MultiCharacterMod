package studio.ERM.war.inventory;

import net.minecraft.entity.player.InventoryPlayer;
import net.minecraft.inventory.Container;
import net.minecraft.inventory.Slot;
import net.minecraft.item.ItemStack;
import net.minecraftforge.items.IItemHandler;
import net.minecraftforge.items.SlotItemHandler;

import studio.ERM.war.entities.EntityModularCitizen;

public class ContainerCitizen extends Container {

    private final EntityModularCitizen citizen;
    private final IItemHandler citizenInv;

    public ContainerCitizen(InventoryPlayer playerInv, EntityModularCitizen citizen) {
        this.citizen = citizen;
        this.citizenInv = citizen.getInternalInv();

        // Citizen internal inventory: 36 slots (4 rows of 9)
        int slotIndex = 0;
        int startX = 8;
        int startY = 18;

        for (int row = 0; row < 4; row++) {
            for (int col = 0; col < 9; col++) {
                this.addSlotToContainer(new SlotItemHandler(citizenInv, slotIndex++, startX + col * 18, startY + row * 18));
            }
        }

        // Player inventory (3 rows)
        int playerStartY = startY + 4 * 18 + 14;
        for (int row = 0; row < 3; row++) {
            for (int col = 0; col < 9; col++) {
                this.addSlotToContainer(new Slot(playerInv, col + row * 9 + 9, startX + col * 18, playerStartY + row * 18));
            }
        }

        // Hotbar
        int hotbarY = playerStartY + 3 * 18 + 4;
        for (int col = 0; col < 9; col++) {
            this.addSlotToContainer(new Slot(playerInv, col, startX + col * 18, hotbarY));
        }
    }

    @Override
    public boolean canInteractWith(net.minecraft.entity.player.EntityPlayer playerIn) {
        return citizen != null && citizen.isEntityAlive() && playerIn.getDistanceSq(citizen) <= 64.0D;
    }

    @Override
    public ItemStack transferStackInSlot(net.minecraft.entity.player.EntityPlayer playerIn, int index) {
        ItemStack empty = ItemStack.EMPTY;
        Slot slot = this.inventorySlots.get(index);
        if (slot == null || !slot.getHasStack()) return empty;

        ItemStack stack = slot.getStack();
        ItemStack copy = stack.copy();

        int citizenSlots = 36;

        if (index < citizenSlots) {
            // citizen -> player
            if (!this.mergeItemStack(stack, citizenSlots, this.inventorySlots.size(), true)) {
                return empty;
            }
        } else {
            // player -> citizen
            if (!this.mergeItemStack(stack, 0, citizenSlots, false)) {
                return empty;
            }
        }

        if (stack.isEmpty()) {
            slot.putStack(ItemStack.EMPTY);
        } else {
            slot.onSlotChanged();
        }

        return copy;
    }
}
