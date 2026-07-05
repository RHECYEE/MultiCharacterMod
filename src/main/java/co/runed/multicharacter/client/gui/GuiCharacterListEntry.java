package co.runed.multicharacter.client.gui;

import co.runed.multicharacter.character.Character;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiListExtended;

public class GuiCharacterListEntry implements GuiListExtended.IGuiListEntry {

    public final Character character;
    private final CharacterListGuiScreen parentScreen;

    public GuiCharacterListEntry(CharacterListGuiScreen parentScreen, Character character) {
        this.parentScreen = parentScreen;
        this.character = character;
    }

    @Override
    public void updatePosition(int slotIndex, int x, int y, float partialTicks) {
    }

    @Override
    public void drawEntry(int slotIndex, int x, int y, int listWidth, int slotHeight, int mouseX, int mouseY, boolean isSelected, float partialTicks) {
        Minecraft mc = Minecraft.getMinecraft();
        String name = character != null ? character.getName() : "Unknown";
        mc.fontRenderer.drawStringWithShadow(name != null ? name : "Unknown", x + 2, y + 2, 0xFFFFFF);
    }

    @Override
    public boolean mousePressed(int slotIndex, int mouseX, int mouseY, int mouseEvent, int relativeX, int relativeY) {
        parentScreen.selectCharacter(slotIndex);
        return true;
    }

    @Override
    public void mouseReleased(int slotIndex, int x, int y, int mouseEvent, int relativeX, int relativeY) {
    }
}
