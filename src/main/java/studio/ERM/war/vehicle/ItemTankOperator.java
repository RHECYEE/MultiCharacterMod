package studio.ERM.war.vehicle;

import co.runed.multicharacter.MultiCharacterMod;
import studio.ERM.war.items.ItemBase;

public class ItemTankOperator extends ItemBase {
    public ItemTankOperator() {
        super(MultiCharacterMod.MODID, "tank_operator_spawner");
        this.setMaxStackSize(1);
    }
}