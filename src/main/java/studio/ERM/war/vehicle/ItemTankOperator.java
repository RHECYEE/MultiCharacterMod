package co.runed.multicharacter.vehicle;

import co.runed.multicharacter.MultiCharacterMod;
import co.runed.multicharacter.compat.ItemBase;

public class ItemTankOperator extends ItemBase {
    public ItemTankOperator() {
        super(MultiCharacterMod.MODID, "tank_operator_spawner");
        this.setMaxStackSize(1);
    }
}