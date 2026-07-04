package studio.ERM.strategic.civil.armory;

import net.minecraft.client.gui.Gui;
import net.minecraft.client.gui.GuiButton;
import net.minecraft.client.gui.inventory.GuiContainer;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.inventory.Slot;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.text.TextFormatting;
import studio.ERM.war.districts.TileEntityDistrictMarker;
import studio.ERM.war.map.net.C2SDistrictDepotEdit;
import studio.ERM.war.map.net.TacticalWarMapNetwork;

import java.io.IOException;

/**
 * THE ARMORY screen — six loadout stands (hand/off/helm/chest/legs/boots ghost patterns) each with a
 * soldier-count adjuster, over the real depot stock and player inventory. Set a loadout by clicking a
 * ghost slot with the item on your cursor; set how many soldiers should carry it with the ± buttons.
 */
public class GuiArmory extends GuiContainer {

    private static final int COL_BG = 0xF0101418, COL_BORDER = 0xFF2E3A44, COL_SLOT = 0xFF1E262E,
            COL_GHOST = 0xFF241C2E, COL_TEXT = 0xFFE0E0E0;
    private static final String[] SLOT_HINT = {"Wpn", "Off", "Hlm", "Cht", "Leg", "Bts"};

    private final ContainerArmory container;
    private final BlockPos pos;

    public GuiArmory(EntityPlayer player, TileEntityDistrictMarker te) {
        super(new ContainerArmory(player, te));
        this.container = (ContainerArmory) inventorySlots;
        this.pos = te.getPos();
        this.xSize = 196;
        this.ySize = 314;
    }

    @Override
    public void initGui() {
        super.initGui();
        buttonList.clear();
        for (int row = 0; row < TileEntityDistrictMarker.LOADOUTS; row++) {
            int y = guiTop + ContainerArmory.LOAD_Y + row * ContainerArmory.ROW_H;
            addButton(new GuiButton(row * 2, guiLeft + 152, y, 12, 14, "-"));
            addButton(new GuiButton(row * 2 + 1, guiLeft + 180, y, 12, 14, "+"));
        }
    }

    @Override
    protected void actionPerformed(GuiButton b) throws IOException {
        int row = b.id / 2;
        int delta = (b.id % 2 == 0 ? -1 : 1) * (isShiftKeyDown() ? 5 : 1);
        TacticalWarMapNetwork.sendToServer(C2SDistrictDepotEdit.loadoutCount(pos, row, delta));
    }

    @Override
    protected void drawGuiContainerBackgroundLayer(float partialTicks, int mouseX, int mouseY) {
        Gui.drawRect(guiLeft - 1, guiTop - 1, guiLeft + xSize + 1, guiTop + ySize + 1, COL_BORDER);
        Gui.drawRect(guiLeft, guiTop, guiLeft + xSize, guiTop + ySize, COL_BG);
        for (int i = 0; i < container.inventorySlots.size(); i++) {
            Slot s = container.inventorySlots.get(i);
            int color = (i < ContainerArmory.LOADOUT_END) ? COL_GHOST : COL_SLOT;
            int x = guiLeft + s.xPos - 1, y = guiTop + s.yPos - 1;
            Gui.drawRect(x, y, x + 18, y + 18, COL_BORDER);
            Gui.drawRect(x + 1, y + 1, x + 17, y + 17, color);
        }
    }

    @Override
    protected void drawGuiContainerForegroundLayer(int mouseX, int mouseY) {
        fontRenderer.drawStringWithShadow(TextFormatting.GOLD + "Armory " + TextFormatting.GRAY
                + "— combat loadouts", 8, 5, COL_TEXT);
        // Column hints above the first loadout row.
        for (int c = 0; c < SLOT_HINT.length; c++) {
            fontRenderer.drawString(SLOT_HINT[c], ContainerArmory.LOAD_X + c * 18, ContainerArmory.LOAD_Y - 9, 0x808080);
        }
        // Per-loadout count between the ± buttons.
        for (int row = 0; row < TileEntityDistrictMarker.LOADOUTS; row++) {
            int y = ContainerArmory.LOAD_Y + row * ContainerArmory.ROW_H + 3;
            String n = String.valueOf(container.counts[row]);
            fontRenderer.drawStringWithShadow(n, 170 - fontRenderer.getStringWidth(n) / 2, y, 0xFFFFFF66);
        }
        fontRenderer.drawStringWithShadow(TextFormatting.AQUA + "Depot stock " + TextFormatting.GRAY
                + "— issued to soldiers (couriers restock)", 8, ContainerArmory.DEPOT_Y - 10, COL_TEXT);
        fontRenderer.drawStringWithShadow("Inventory", 8, ContainerArmory.INV_Y - 10, COL_TEXT);
    }

    @Override
    public void drawScreen(int mouseX, int mouseY, float partialTicks) {
        drawDefaultBackground();
        super.drawScreen(mouseX, mouseY, partialTicks);
        renderHoveredToolTip(mouseX, mouseY);
    }
}
