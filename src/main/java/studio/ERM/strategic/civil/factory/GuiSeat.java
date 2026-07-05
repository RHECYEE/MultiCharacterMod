package studio.ERM.strategic.civil.factory;

import net.minecraft.client.gui.Gui;
import net.minecraft.client.gui.inventory.GuiContainer;
import net.minecraft.client.renderer.RenderHelper;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.inventory.InventoryCrafting;
import net.minecraft.inventory.Slot;
import net.minecraft.item.ItemStack;
import net.minecraft.item.crafting.CraftingManager;
import net.minecraft.util.text.TextFormatting;
import studio.ERM.war.districts.TileEntityAssemblySeat;

/**
 * Assembly-seat recipe editor GUI — 3×3 ghost grid + a live output preview (the vanilla crafting
 * result of the pattern), over the player inventory.
 */
public class GuiSeat extends GuiContainer {

    private static final int COL_BG = 0xF0101418, COL_BORDER = 0xFF2E3A44, COL_SLOT = 0xFF241C2E;

    private final ContainerSeat container;
    private final TileEntityAssemblySeat te;
    private final DummyCraft dummy = new DummyCraft();

    public GuiSeat(EntityPlayer player, TileEntityAssemblySeat te) {
        super(new ContainerSeat(player, te));
        this.container = (ContainerSeat) inventorySlots;
        this.te = te;
        this.xSize = 176;
        this.ySize = 166;
    }

    @Override
    protected void drawGuiContainerBackgroundLayer(float partialTicks, int mouseX, int mouseY) {
        Gui.drawRect(guiLeft - 1, guiTop - 1, guiLeft + xSize + 1, guiTop + ySize + 1, COL_BORDER);
        Gui.drawRect(guiLeft, guiTop, guiLeft + xSize, guiTop + ySize, COL_BG);
        for (int i = 0; i < container.inventorySlots.size(); i++) {
            Slot s = container.inventorySlots.get(i);
            int x = guiLeft + s.xPos - 1, y = guiTop + s.yPos - 1;
            Gui.drawRect(x, y, x + 18, y + 18, COL_BORDER);
            Gui.drawRect(x + 1, y + 1, x + 17, y + 17, i < 9 ? COL_SLOT : 0xFF1E262E);
        }
        // Output preview box.
        int ox = guiLeft + 128, oy = guiTop + ContainerSeat.GRID_Y + 18;
        Gui.drawRect(ox - 1, oy - 1, ox + 18, oy + 18, COL_BORDER);
        Gui.drawRect(ox, oy, ox + 17, oy + 17, 0xFF16321E);
    }

    @Override
    protected void drawGuiContainerForegroundLayer(int mouseX, int mouseY) {
        fontRenderer.drawStringWithShadow(TextFormatting.GOLD + "Assembly Seat " + TextFormatting.GRAY
                + "— set a recipe", 8, 6, 0xFFE0E0E0);
        fontRenderer.drawString("→", 116, ContainerSeat.GRID_Y + 22, 0xFFAAAAAA);

        ItemStack out = result();
        if (!out.isEmpty()) {
            RenderHelper.enableGUIStandardItemLighting();
            itemRender.renderItemAndEffectIntoGUI(out, 128, ContainerSeat.GRID_Y + 18);
            if (out.getCount() > 1) itemRender.renderItemOverlays(fontRenderer, out, 128, ContainerSeat.GRID_Y + 18);
            RenderHelper.disableStandardItemLighting();
        } else {
            fontRenderer.drawString("§8none", 122, ContainerSeat.GRID_Y + 40, 0x808080);
        }
    }

    private ItemStack result() {
        for (int i = 0; i < 9; i++) {
            ItemStack s = te.recipe.getStackInSlot(i).copy();
            s.setCount(s.isEmpty() ? 0 : 1);
            dummy.setInventorySlotContents(i, s);
        }
        return CraftingManager.findMatchingResult(dummy, mc.world);
    }

    @Override
    public void drawScreen(int mouseX, int mouseY, float partialTicks) {
        drawDefaultBackground();
        super.drawScreen(mouseX, mouseY, partialTicks);
        renderHoveredToolTip(mouseX, mouseY);
    }

    /** A standalone 3×3 crafting inventory for previewing the recipe result. */
    private static class DummyCraft extends InventoryCrafting {
        DummyCraft() {
            super(new net.minecraft.inventory.Container() {
                @Override public boolean canInteractWith(EntityPlayer p) { return false; }
                @Override public void onCraftMatrixChanged(net.minecraft.inventory.IInventory inv) {}
            }, 3, 3);
        }
    }
}
