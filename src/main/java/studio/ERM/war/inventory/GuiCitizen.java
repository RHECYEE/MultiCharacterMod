package studio.ERM.war.inventory;

import net.minecraft.client.gui.inventory.GuiContainer;
import net.minecraft.entity.player.InventoryPlayer;
import net.minecraft.util.ResourceLocation;

import studio.ERM.war.entities.EntityModularCitizen;

public class GuiCitizen extends GuiContainer {

    private static final ResourceLocation BG = new ResourceLocation("minecraft", "textures/gui/container/generic_54.png");

    public GuiCitizen(InventoryPlayer playerInv, EntityModularCitizen citizen) {
        super(new ContainerCitizen(playerInv, citizen));
        // generic_54 is 176x222; we only use part of it, but it's fine.
        this.xSize = 176;
        this.ySize = 222;
    }

    @Override
    protected void drawGuiContainerBackgroundLayer(float partialTicks, int mouseX, int mouseY) {
        this.mc.getTextureManager().bindTexture(BG);
        int x = (this.width - this.xSize) / 2;
        int y = (this.height - this.ySize) / 2;
        this.drawTexturedModalRect(x, y, 0, 0, this.xSize, this.ySize);
    }

    @Override
    protected void drawGuiContainerForegroundLayer(int mouseX, int mouseY) {
        // Keep minimal; localization can be added later.
        this.fontRenderer.drawString("Citizen Inventory", 8, 6, 4210752);
        this.fontRenderer.drawString("Inventory", 8, 128, 4210752);
    }
}
