package studio.ERM.war.districts.inventory;

import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.inventory.ClickType;
import net.minecraft.inventory.Container;
import net.minecraft.inventory.IContainerListener;
import net.minecraft.inventory.Slot;
import net.minecraft.item.ItemStack;
import net.minecraftforge.items.SlotItemHandler;
import studio.ERM.strategic.civil.CivilMarker;
import studio.ERM.strategic.civil.DistrictRegistry;
import studio.ERM.war.districts.TileEntityDistrictMarker;

/**
 * THE UNIVERSAL DISTRICT CONTROLLER — one shared container for every district's depot block.
 *
 * Slot map (order added):
 *   0..19   IN request templates (GHOST: items the district keeps stocked — courier deliveries)
 *   20..39  OUT request templates (GHOST: items the district exports — courier pickups)
 *   40..66  the DEPOT: 27 real slots workers deposit into / pull upkeep from
 *   67..102 player inventory + hotbar
 *
 * Ghost templates hold a size-1 COPY of whatever the cursor carries when clicked (click with an
 * empty cursor to clear). No items are ever consumed by templates. Worker count and district
 * binding sync to the client as window properties.
 */
public class ContainerDistrict extends Container {

    public static final int IN_START = 0;
    public static final int OUT_START = IN_START + TileEntityDistrictMarker.TEMPLATE_SLOTS;   // 20
    public static final int DEPOT_START = OUT_START + TileEntityDistrictMarker.TEMPLATE_SLOTS; // 40
    public static final int PLAYER_START = DEPOT_START + TileEntityDistrictMarker.DEPOT_SLOTS; // 67
    public static final int PLAYER_END = PLAYER_START + 36;                                    // 103

    // Pixel layout shared with GuiDistrict (xSize 196). A 16px row under the worker counter holds the
    // per-kind option toggle (e.g. Lumber's Tree/Fruit mode), so every section sits 16px lower.
    public static final int TPL_X = 8, IN_Y = 57, OUT_Y = 104;
    public static final int DEPOT_X = 17, DEPOT_Y = 151;
    public static final int INV_X = 17, INV_Y = 211, HOTBAR_Y = 269;

    public final TileEntityDistrictMarker te;
    private final EntityPlayer player;

    // Client-visible state (window properties).
    public int desiredWorkers;
    public int districtKind = -1;
    public int districtUidDisplay = 0;
    public int rivalLevel = 1;
    public int subMode = 0; // LUMBER: 0 = Tree Farm, 1 = Fruit Farm

    public ContainerDistrict(EntityPlayer player, TileEntityDistrictMarker te) {
        this.player = player;
        this.te = te;

        // IN templates: 2 rows x 10.
        for (int i = 0; i < TileEntityDistrictMarker.TEMPLATE_SLOTS; i++) {
            addSlotToContainer(new SlotTemplate(te.inTemplates, i,
                    TPL_X + (i % 10) * 18, IN_Y + (i / 10) * 18));
        }
        // OUT templates: 2 rows x 10.
        for (int i = 0; i < TileEntityDistrictMarker.TEMPLATE_SLOTS; i++) {
            addSlotToContainer(new SlotTemplate(te.outTemplates, i,
                    TPL_X + (i % 10) * 18, OUT_Y + (i / 10) * 18));
        }
        // Depot: 3 rows x 9 of real storage.
        for (int i = 0; i < TileEntityDistrictMarker.DEPOT_SLOTS; i++) {
            addSlotToContainer(new SlotItemHandler(te.depot, i,
                    DEPOT_X + (i % 9) * 18, DEPOT_Y + (i / 9) * 18));
        }
        // Player inventory + hotbar.
        for (int row = 0; row < 3; row++) {
            for (int col = 0; col < 9; col++) {
                addSlotToContainer(new Slot(player.inventory, col + row * 9 + 9,
                        INV_X + col * 18, INV_Y + row * 18));
            }
        }
        for (int col = 0; col < 9; col++) {
            addSlotToContainer(new Slot(player.inventory, col, INV_X + col * 18, HOTBAR_Y));
        }
    }

    /** Ghost slot: never accepts or yields real items through vanilla mechanics. */
    private static class SlotTemplate extends SlotItemHandler {
        SlotTemplate(net.minecraftforge.items.IItemHandler handler, int index, int x, int y) {
            super(handler, index, x, y);
        }
        @Override public boolean isItemValid(ItemStack stack) { return false; }
        @Override public boolean canTakeStack(EntityPlayer player) { return false; }
    }

    private boolean isTemplate(int slotId) {
        return slotId >= IN_START && slotId < DEPOT_START;
    }

    /**
     * Template interaction: any direct click SETS the ghost to a copy of the cursor stack
     * (empty cursor clears it). Drag-painting skips ghosts on its own (isItemValid=false).
     */
    @Override
    public ItemStack slotClick(int slotId, int dragType, ClickType clickType, EntityPlayer player) {
        if (isTemplate(slotId)
                && (clickType == ClickType.PICKUP || clickType == ClickType.PICKUP_ALL
                    || clickType == ClickType.QUICK_MOVE || clickType == ClickType.SWAP
                    || clickType == ClickType.CLONE || clickType == ClickType.THROW)) {
            ItemStack cursor = player.inventory.getItemStack();
            ItemStack ghost = ItemStack.EMPTY;
            if (clickType == ClickType.PICKUP && !cursor.isEmpty()) {
                ghost = cursor.copy();
                ghost.setCount(1);
            }
            // QUICK_MOVE/THROW/empty-cursor PICKUP all mean "clear".
            boolean in = slotId < OUT_START;
            int idx = in ? slotId - IN_START : slotId - OUT_START;
            (in ? te.inTemplates : te.outTemplates).setStackInSlot(idx, ghost);
            detectAndSendChanges();
            return ItemStack.EMPTY;
        }
        return super.slotClick(slotId, dragType, clickType, player);
    }

    @Override
    public ItemStack transferStackInSlot(EntityPlayer playerIn, int index) {
        if (isTemplate(index)) return ItemStack.EMPTY; // handled in slotClick (clears)
        ItemStack copy = ItemStack.EMPTY;
        Slot slot = inventorySlots.get(index);
        if (slot != null && slot.getHasStack()) {
            ItemStack stack = slot.getStack();
            copy = stack.copy();
            if (index < PLAYER_START) { // depot -> player
                if (!mergeItemStack(stack, PLAYER_START, PLAYER_END, true)) return ItemStack.EMPTY;
            } else {                    // player -> depot
                if (!mergeItemStack(stack, DEPOT_START, PLAYER_START, false)) return ItemStack.EMPTY;
            }
            if (stack.isEmpty()) slot.putStack(ItemStack.EMPTY);
            else slot.onSlotChanged();
        }
        return copy;
    }

    @Override
    public boolean canInteractWith(EntityPlayer playerIn) {
        return !te.isInvalid() && playerIn.getDistanceSq(
                te.getPos().getX() + 0.5, te.getPos().getY() + 0.5, te.getPos().getZ() + 0.5) <= 64.0;
    }

    // ------------------------------------------------------------------
    // Window-property sync: worker count + binding + rival level.
    // ------------------------------------------------------------------

    @Override
    public void detectAndSendChanges() {
        super.detectAndSendChanges();
        if (player.world.isRemote) return;
        int workers = te.getDesiredWorkers();
        int kind = -1, uidDisp = 0;
        CivilMarker district = DistrictRegistry.byUid(player.world, te.getDistrictUid());
        if (district != null) { kind = district.kind; uidDisp = district.uid & 0xFFFF; }
        int rl = DistrictRegistry.rivalLevel(player.world);
        int sm = te.getSubMode();
        for (IContainerListener l : listeners) {
            if (workers != desiredWorkers) l.sendWindowProperty(this, 0, workers);
            if (kind != districtKind) l.sendWindowProperty(this, 1, kind);
            if (uidDisp != districtUidDisplay) l.sendWindowProperty(this, 2, uidDisp);
            if (rl != rivalLevel) l.sendWindowProperty(this, 3, rl);
            if (sm != subMode) l.sendWindowProperty(this, 4, sm);
        }
        desiredWorkers = workers;
        districtKind = kind;
        districtUidDisplay = uidDisp;
        rivalLevel = rl;
        subMode = sm;
    }

    @Override
    public void addListener(IContainerListener listener) {
        super.addListener(listener);
        // Push initial values (the != guards in detectAndSendChanges would suppress them).
        listener.sendWindowProperty(this, 0, te.getDesiredWorkers());
        CivilMarker district = player.world.isRemote ? null
                : DistrictRegistry.byUid(player.world, te.getDistrictUid());
        listener.sendWindowProperty(this, 1, district != null ? district.kind : -1);
        listener.sendWindowProperty(this, 2, district != null ? district.uid & 0xFFFF : 0);
        listener.sendWindowProperty(this, 3,
                player.world.isRemote ? 1 : DistrictRegistry.rivalLevel(player.world));
        listener.sendWindowProperty(this, 4, te.getSubMode());
    }

    @Override
    public void updateProgressBar(int id, int value) {
        switch (id) {
            case 0: desiredWorkers = value; break;
            case 1: districtKind = value; break;
            case 2: districtUidDisplay = value; break;
            case 3: rivalLevel = value; break;
            case 4: subMode = value; break;
            default: break;
        }
    }
}
