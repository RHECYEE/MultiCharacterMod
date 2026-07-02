package studio.ERM.strategic.defense;

import net.minecraft.client.gui.GuiButton;
import net.minecraft.client.gui.inventory.GuiContainer;
import net.minecraft.client.renderer.GlStateManager;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.util.ResourceLocation;
import studio.ERM.war.config.WarLevelsConfig;

import java.io.IOException;

/**
 * PHASE 2 — the RECRUIT screen: a chest-style loadout row over the player's inventory, with the
 * contract-type toggle and CONFIRM above it. Slot guide: hand / offhand / helmet / chest / legs /
 * boots / 3 supply slots. Cost is the configured Command-Buck constant per contract type (later:
 * scales with the gear's value).
 */
public class GuiRecruit extends GuiContainer {

    private static final ResourceLocation CHEST_TEX =
            new ResourceLocation("textures/gui/container/generic_54.png");
    private static final String[] SLOT_HINTS = {"Hand", "Off", "Helm", "Chest", "Legs", "Boots", "S", "S", "S"};

    private boolean mercenary = true;
    private GuiButton typeButton;
    private GuiButton confirmButton;

    public GuiRecruit(EntityPlayer player) {
        super(new ContainerRecruit(player));
        this.xSize = 176;
        this.ySize = 131; // one chest row (17 + 18) + player inventory section (96)
    }

    @Override
    public void initGui() {
        super.initGui();
        buttonList.clear();
        typeButton = addButton(new GuiButton(0, guiLeft, guiTop - 44, xSize, 20, typeLabel()));
        confirmButton = addButton(new GuiButton(1, guiLeft, guiTop - 22, xSize, 20, confirmLabel()));
    }

    private String typeLabel() {
        return mercenary
                ? "Contract: MERCENARY (" + WarLevelsConfig.recruitMercCost() + " CB, "
                    + WarLevelsConfig.recruitMercDays() + " days)"
                : "Contract: PERMANENT (" + WarLevelsConfig.recruitPermanentCost() + " CB, standing army)";
    }

    private String confirmLabel() {
        int cost = mercenary ? WarLevelsConfig.recruitMercCost() : WarLevelsConfig.recruitPermanentCost();
        return "CONFIRM CONTRACT  (-" + cost + " CB)";
    }

    @Override
    protected void actionPerformed(GuiButton button) throws IOException {
        if (button.id == 0) {
            mercenary = !mercenary;
            typeButton.displayString = typeLabel();
            confirmButton.displayString = confirmLabel();
        } else if (button.id == 1) {
            studio.ERM.war.map.net.TacticalWarMapNetwork.sendToServer(
                    new studio.ERM.war.map.net.C2SRecruitConfirm(mercenary));
        }
    }

    @Override
    protected void drawGuiContainerBackgroundLayer(float partialTicks, int mouseX, int mouseY) {
        GlStateManager.color(1F, 1F, 1F, 1F);
        mc.getTextureManager().bindTexture(CHEST_TEX);
        // Top: header + one slot row from the generic chest texture; bottom: the player-inventory panel.
        drawTexturedModalRect(guiLeft, guiTop, 0, 0, xSize, 17 + 18);
        drawTexturedModalRect(guiLeft, guiTop + 17 + 18, 0, 126, xSize, 96);
    }

    @Override
    protected void drawGuiContainerForegroundLayer(int mouseX, int mouseY) {
        fontRenderer.drawString("Recruit Loadout", 8, 6, 0x404040);
        fontRenderer.drawString("Inventory", 8, 38, 0x404040);
        // Slot guide under the loadout row.
        for (int i = 0; i < SLOT_HINTS.length; i++) {
            String h = SLOT_HINTS[i];
            int w = fontRenderer.getStringWidth(h);
            fontRenderer.drawString(h, 8 + i * 18 + (16 - w) / 2, 37 - 10, 0x777777);
        }
    }

    @Override
    public void drawScreen(int mouseX, int mouseY, float partialTicks) {
        drawDefaultBackground();
        super.drawScreen(mouseX, mouseY, partialTicks);
        renderHoveredToolTip(mouseX, mouseY);
    }
}
