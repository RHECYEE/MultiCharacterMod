package studio.ERM.strategic.defense;

import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.inventory.Container;
import net.minecraft.inventory.EntityEquipmentSlot;
import net.minecraft.inventory.InventoryBasic;
import net.minecraft.inventory.Slot;
import net.minecraft.item.ItemStack;

/**
 * PHASE 2 — the RECRUIT loadout container. Layout:
 *   Row 1 (weapons/supplies): 0 mainhand (or the FLAN VEHICLE item on a vehicle contract), 1 offhand,
 *                             6/7/8 supply slots.
 *   Row 2 (armor):            2 head, 3 chest, 4 legs, 5 feet — real armor slots with the vanilla
 *                             silhouette icons, restricted to armor that fits the slot.
 * The player physically builds the recruit's kit from their own items; CONFIRM consumes it. Closing
 * without confirming returns everything.
 */
public class ContainerRecruit extends Container {

    public final InventoryBasic loadout = new InventoryBasic("recruit_loadout", false, 9);
    private final EntityPlayer player;

    public ContainerRecruit(EntityPlayer player) {
        this.player = player;

        // Row 1: hand, offhand, then the three supply slots on the right.
        addSlotToContainer(new Slot(loadout, 0, 8, 18));
        addSlotToContainer(new Slot(loadout, 1, 26, 18) {
            @Override public String getSlotTexture() { return "minecraft:items/empty_armor_slot_shield"; }
        });
        addSlotToContainer(new Slot(loadout, 6, 116, 18));
        addSlotToContainer(new Slot(loadout, 7, 134, 18));
        addSlotToContainer(new Slot(loadout, 8, 152, 18));

        // Row 2: the four ARMOR slots (restricted + vanilla silhouettes).
        addArmorSlot(2, 8, 36, EntityEquipmentSlot.HEAD, "minecraft:items/empty_armor_slot_helmet");
        addArmorSlot(3, 26, 36, EntityEquipmentSlot.CHEST, "minecraft:items/empty_armor_slot_chestplate");
        addArmorSlot(4, 44, 36, EntityEquipmentSlot.LEGS, "minecraft:items/empty_armor_slot_leggings");
        addArmorSlot(5, 62, 36, EntityEquipmentSlot.FEET, "minecraft:items/empty_armor_slot_boots");

        // Player inventory + hotbar (two-row-chest offsets).
        for (int row = 0; row < 3; row++) {
            for (int col = 0; col < 9; col++) {
                addSlotToContainer(new Slot(player.inventory, col + row * 9 + 9, 8 + col * 18, 67 + row * 18));
            }
        }
        for (int col = 0; col < 9; col++) {
            addSlotToContainer(new Slot(player.inventory, col, 8 + col * 18, 125));
        }
    }

    private void addArmorSlot(int idx, int x, int y, final EntityEquipmentSlot armorType, final String tex) {
        addSlotToContainer(new Slot(loadout, idx, x, y) {
            @Override
            public boolean isItemValid(ItemStack stack) {
                return !stack.isEmpty()
                        && net.minecraft.entity.EntityLiving.getSlotForItemStack(stack) == armorType;
            }
            @Override
            public String getSlotTexture() { return tex; }
        });
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
            } else {         // player inventory -> loadout (armor routes to its slot; else the free row)
                EntityEquipmentSlot fit = net.minecraft.entity.EntityLiving.getSlotForItemStack(stack);
                int armorIdx = -1;
                if (fit == EntityEquipmentSlot.HEAD) armorIdx = 5;   // container slot index of loadout 2
                else if (fit == EntityEquipmentSlot.CHEST) armorIdx = 6;
                else if (fit == EntityEquipmentSlot.LEGS) armorIdx = 7;
                else if (fit == EntityEquipmentSlot.FEET) armorIdx = 8;
                if (armorIdx >= 0 && mergeItemStack(stack, armorIdx, armorIdx + 1, false)) {
                    // placed in its armor slot
                } else if (!mergeItemStack(stack, 0, 5, false)) {
                    return ItemStack.EMPTY;
                }
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
