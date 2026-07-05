package co.runed.multicharacter.client.gui;

import co.runed.multicharacter.character.Character;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiListExtended;

import java.util.ArrayList;
import java.util.List;

public class CharacterSelectionList extends GuiListExtended {

    private final CharacterListGuiScreen parentScreen;
    private final List<GuiCharacterListEntry> entries = new ArrayList<>();
    private int selectedIndex = -1;

    public CharacterSelectionList(CharacterListGuiScreen parentScreen, Minecraft mc, int width, int height, int top, int bottom, int slotHeight) {
        super(mc, width, height, top, bottom, slotHeight);
        this.parentScreen = parentScreen;
    }

    public void updateCharacters(List<Character> characters) {
        entries.clear();
        if (characters != null) {
            for (Character c : characters) {
                entries.add(new GuiCharacterListEntry(parentScreen, c));
            }
        }
    }

    @Override
    public IGuiListEntry getListEntry(int index) {
        if (index < 0 || index >= entries.size()) return null;
        return entries.get(index);
    }

    @Override
    protected int getSize() {
        return entries.size();
    }

    @Override
    protected boolean isSelected(int slotIndex) {
        return slotIndex == selectedIndex;
    }

    public int getSelected() {
        return selectedIndex;
    }

    public void setSelectedSlotIndex(int index) {
        this.selectedIndex = index;
    }

    public void setDimensions(int width, int height, int top, int bottom) {
        this.width = width;
        this.height = height;
        this.top = top;
        this.bottom = bottom;
    }
}
