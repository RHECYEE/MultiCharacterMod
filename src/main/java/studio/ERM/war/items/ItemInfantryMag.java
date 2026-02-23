package co.runed.multicharacter.compat;

public class ItemInfantryMag extends ItemBase {

    public final int capacity;

    public ItemInfantryMag(String modid, String name, int capacity) {
        super(modid, name);
        this.capacity = capacity;
        this.setMaxStackSize(16);
        this.setNoRepair();
    }
}