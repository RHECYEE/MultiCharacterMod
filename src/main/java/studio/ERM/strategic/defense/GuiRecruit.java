package studio.ERM.strategic.defense;

import net.minecraft.client.gui.GuiButton;
import net.minecraft.client.gui.inventory.GuiContainer;
import net.minecraft.client.renderer.GlStateManager;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.item.ItemStack;
import net.minecraft.util.ResourceLocation;
import studio.ERM.strategic.StrategicReinforcement;
import studio.ERM.war.config.WarLevelsConfig;

import java.io.IOException;

/**
 * PHASE 2 — the RECRUIT screen. Weapons/supply row + a real ARMOR row over the player's inventory.
 * Contract types cycle PERMANENT / MERCENARY / PERMANENT VEHICLE (vehicle contracts read the Flan
 * vehicle item in the Hand slot; cost comes from the per-ShortName config map). The squad button
 * multiplies the order (the loadout is the squad's kit pattern; cost scales per soldier).
 */
public class GuiRecruit extends GuiContainer {

    private static final ResourceLocation CHEST_TEX =
            new ResourceLocation("textures/gui/container/generic_54.png");
    private static final int[] SQUADS = {1, 2, 4, 8};

    private int kind = 1;      // 0 permanent, 1 mercenary, 2 permanent vehicle
    private int squadIdx = 0;  // index into SQUADS
    private GuiButton typeButton;
    private GuiButton squadButton;
    private GuiButton confirmButton;

    public GuiRecruit(EntityPlayer player) {
        super(new ContainerRecruit(player));
        this.xSize = 176;
        this.ySize = 149; // two loadout rows (17 + 36) + player inventory (96)
    }

    @Override
    public void initGui() {
        super.initGui();
        buttonList.clear();
        typeButton = addButton(new GuiButton(0, guiLeft, guiTop - 66, xSize, 20, typeLabel()));
        squadButton = addButton(new GuiButton(2, guiLeft, guiTop - 44, xSize, 20, squadLabel()));
        confirmButton = addButton(new GuiButton(1, guiLeft, guiTop - 22, xSize, 20, confirmLabel()));
    }

    private int squad() { return (kind == 2) ? 1 : SQUADS[squadIdx]; }

    private int unitCost() {
        if (kind == 0) return WarLevelsConfig.recruitPermanentCost();
        if (kind == 1) return WarLevelsConfig.recruitMercCost();
        // Vehicle: price the Flan vehicle item currently in the Hand slot.
        ItemStack hand = ((ContainerRecruit) inventorySlots).loadout.getStackInSlot(0);
        String shortName = StrategicReinforcement.flanShortNameOf(hand);
        return WarLevelsConfig.recruitVehicleCost(shortName);
    }

    private String typeLabel() {
        switch (kind) {
            case 0:  return "Contract: PERMANENT (standing army)";
            case 2:  return "Contract: PERMANENT VEHICLE (Flan item in Hand slot)";
            default: return "Contract: MERCENARY (" + WarLevelsConfig.recruitMercDays() + " days)";
        }
    }

    private String squadLabel() {
        return (kind == 2) ? "Squad: crew included with vehicle"
                : "Squad size: x" + squad() + "  (same kit each)";
    }

    private String confirmLabel() {
        return "CONFIRM CONTRACT  (-" + (unitCost() * squad()) + " CB)";
    }

    private void refreshLabels() {
        typeButton.displayString = typeLabel();
        squadButton.displayString = squadLabel();
        confirmButton.displayString = confirmLabel();
    }

    @Override
    protected void actionPerformed(GuiButton button) throws IOException {
        if (button.id == 0) {
            kind = (kind + 1) % 3;
        } else if (button.id == 2 && kind != 2) {
            squadIdx = (squadIdx + 1) % SQUADS.length;
        } else if (button.id == 1) {
            studio.ERM.war.map.net.TacticalWarMapNetwork.sendToServer(
                    new studio.ERM.war.map.net.C2SRecruitConfirm(kind, squad()));
        }
        refreshLabels();
    }

    @Override
    public void updateScreen() {
        super.updateScreen();
        refreshLabels(); // vehicle cost follows whatever item sits in the Hand slot
    }

    @Override
    protected void drawGuiContainerBackgroundLayer(float partialTicks, int mouseX, int mouseY) {
        GlStateManager.color(1F, 1F, 1F, 1F);
        mc.getTextureManager().bindTexture(CHEST_TEX);
        // Top: header + two slot rows from the generic chest texture; bottom: the player-inventory panel.
        drawTexturedModalRect(guiLeft, guiTop, 0, 0, xSize, 17 + 36);
        drawTexturedModalRect(guiLeft, guiTop + 17 + 36, 0, 126, xSize, 96);
    }

    @Override
    protected void drawGuiContainerForegroundLayer(int mouseX, int mouseY) {
        fontRenderer.drawString("Recruit Loadout", 8, 6, 0x404040);
        fontRenderer.drawString("Inventory", 8, 56, 0x404040);
        // Compact half-scale hints so nothing overlaps.
        GlStateManager.pushMatrix();
        GlStateManager.scale(0.5F, 0.5F, 1F);
        drawHint(kind == 2 ? "Vehicle" : "Hand", 8, 18);
        drawHint("Off", 26, 18);
        drawHint("Supplies", 116, 18);
        drawHint("Armor", 8, 36);
        GlStateManager.popMatrix();
    }

    /** Draw a tiny hint above a slot; coordinates are the SLOT's (pre-scale) position. */
    private void drawHint(String text, int slotX, int slotY) {
        fontRenderer.drawString(text, slotX * 2, (slotY - 5) * 2, 0x666666);
    }

    @Override
    public void drawScreen(int mouseX, int mouseY, float partialTicks) {
        drawDefaultBackground();
        super.drawScreen(mouseX, mouseY, partialTicks);
        renderHoveredToolTip(mouseX, mouseY);
    }
}
