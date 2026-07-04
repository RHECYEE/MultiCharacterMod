package studio.ERM.war.districts.inventory;

import net.minecraft.client.gui.Gui;
import net.minecraft.client.gui.GuiButton;
import net.minecraft.client.gui.inventory.GuiContainer;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.inventory.Slot;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.text.TextFormatting;
import studio.ERM.strategic.civil.CivilMarker;
import studio.ERM.war.districts.TileEntityDistrictMarker;
import studio.ERM.war.map.net.C2SDistrictDepotEdit;
import studio.ERM.war.map.net.TacticalWarMapNetwork;

import java.io.IOException;

/**
 * THE UNIVERSAL DISTRICT CONTROLLER screen — same panel for every district kind:
 *
 *   [-] Assigned Workers [+]      (shift-click steps by 5)
 *   IN   — 20 ghost templates the couriers keep stocked here
 *   OUT  — 20 ghost templates the couriers export from here
 *   DEPOT — 27 real slots (workers deposit yields / pull upkeep)
 *
 * Drawn programmatically (rects, like the war map) because the 10-wide template rows outgrow the
 * 176px chest texture. Click a template with an item on the cursor to set it; empty cursor clears.
 */
public class GuiDistrict extends GuiContainer {

    private static final int COL_BG = 0xF0101418;
    private static final int COL_BORDER = 0xFF2E3A44;
    private static final int COL_SLOT = 0xFF1E262E;
    private static final int COL_SLOT_IN = 0xFF16321E;   // green tint: keep stocked
    private static final int COL_SLOT_OUT = 0xFF33280F;  // amber tint: exports
    private static final int COL_TEXT = 0xFFE0E0E0;

    private final ContainerDistrict container;
    private final BlockPos pos;

    public GuiDistrict(EntityPlayer player, TileEntityDistrictMarker te) {
        super(new ContainerDistrict(player, te));
        this.container = (ContainerDistrict) inventorySlots;
        this.pos = te.getPos();
        this.xSize = 196;
        this.ySize = 294;
    }

    private GuiButton lumberModeButton;

    @Override
    public void initGui() {
        super.initGui();
        buttonList.clear();
        addButton(new GuiButton(0, guiLeft + xSize - 58, guiTop + 14, 14, 14, "-"));
        addButton(new GuiButton(1, guiLeft + xSize - 22, guiTop + 14, 14, 14, "+"));
        // Lumber districts get a Tree Farm / Fruit Farm mode toggle.
        lumberModeButton = addButton(new GuiButton(2, guiLeft + 8, guiTop + 28, xSize - 16, 14, lumberModeLabel()));
    }

    private String lumberModeLabel() {
        return "Mode: " + (container.subMode == 1 ? "Fruit Farm (grows orchard, yields fruit)"
                                                   : "Tree Farm (fells + replants for logs)");
    }

    @Override
    protected void actionPerformed(GuiButton button) throws IOException {
        int step = isShiftKeyDown() ? 5 : 1;
        if (button.id == 0) {
            TacticalWarMapNetwork.sendToServer(new C2SDistrictDepotEdit(pos, -step));
        } else if (button.id == 1) {
            TacticalWarMapNetwork.sendToServer(new C2SDistrictDepotEdit(pos, step));
        } else if (button.id == 2) {
            TacticalWarMapNetwork.sendToServer(C2SDistrictDepotEdit.toggleSubMode(pos, 2));
        }
    }

    @Override
    protected void drawGuiContainerBackgroundLayer(float partialTicks, int mouseX, int mouseY) {
        // Panel + border.
        Gui.drawRect(guiLeft - 1, guiTop - 1, guiLeft + xSize + 1, guiTop + ySize + 1, COL_BORDER);
        Gui.drawRect(guiLeft, guiTop, guiLeft + xSize, guiTop + ySize, COL_BG);

        // Slot squares, tinted by section.
        for (int i = 0; i < container.inventorySlots.size(); i++) {
            Slot s = container.inventorySlots.get(i);
            int color = COL_SLOT;
            if (i < ContainerDistrict.OUT_START) color = COL_SLOT_IN;
            else if (i < ContainerDistrict.DEPOT_START) color = COL_SLOT_OUT;
            int x = guiLeft + s.xPos - 1, y = guiTop + s.yPos - 1;
            Gui.drawRect(x, y, x + 18, y + 18, COL_BORDER);
            Gui.drawRect(x + 1, y + 1, x + 17, y + 17, color);
        }
    }

    @Override
    protected void drawGuiContainerForegroundLayer(int mouseX, int mouseY) {
        String title = container.districtKind >= 0
                ? CivilMarker.nameOf(container.districtKind) + " District"
                        + TextFormatting.DARK_GRAY + " #" + container.districtUidDisplay
                : TextFormatting.YELLOW + "District Depot" + TextFormatting.GRAY + " (unbound)";
        fontRenderer.drawStringWithShadow(TextFormatting.GOLD + title, 8, 4, COL_TEXT);

        // Worker counter between the -/+ buttons.
        fontRenderer.drawStringWithShadow("Assigned Workers", 8, 17, COL_TEXT);
        String n = String.valueOf(container.desiredWorkers);
        fontRenderer.drawStringWithShadow(n, xSize - 41 + (18 - fontRenderer.getStringWidth(n)) / 2,
                17, 0xFFFFFF66);

        fontRenderer.drawStringWithShadow(TextFormatting.GREEN + "IN " + TextFormatting.GRAY
                + "— couriers keep these stocked", 8, ContainerDistrict.IN_Y - 10, COL_TEXT);
        fontRenderer.drawStringWithShadow(TextFormatting.GOLD + "OUT " + TextFormatting.GRAY
                + "— couriers export these", 8, ContainerDistrict.OUT_Y - 10, COL_TEXT);
        fontRenderer.drawStringWithShadow(TextFormatting.AQUA + "Depot " + TextFormatting.GRAY
                + "— rival level " + container.rivalLevel + " outputs",
                8, ContainerDistrict.DEPOT_Y - 10, COL_TEXT);
        fontRenderer.drawStringWithShadow("Inventory", 8, ContainerDistrict.INV_Y - 10, COL_TEXT);
    }

    @Override
    public void drawScreen(int mouseX, int mouseY, float partialTicks) {
        // The Tree/Fruit toggle only applies to Lumber districts; keep its label in sync.
        if (lumberModeButton != null) {
            lumberModeButton.visible = container.districtKind == CivilMarker.LUMBER;
            lumberModeButton.displayString = lumberModeLabel();
        }
        drawDefaultBackground();
        super.drawScreen(mouseX, mouseY, partialTicks);
        renderHoveredToolTip(mouseX, mouseY);
    }
}
