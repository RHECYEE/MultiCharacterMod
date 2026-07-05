package co.runed.multicharacter.vehicle;

import net.minecraft.entity.EntityCreature;
import net.minecraft.entity.ai.EntityAIBase;

/**
 * AI task for infantry entities to search for ammo resupply.
 */
public class EntityAIFindAmmo extends EntityAIBase {

    private final EntityCreature entity;

    public EntityAIFindAmmo(EntityCreature entity) {
        this.entity = entity;
        this.setMutexBits(0);
    }

    @Override
    public boolean shouldExecute() {
        return entity.getEntityData().getBoolean("Infantry_NeedsAmmo");
    }

    @Override
    public void updateTask() {
        // Stub: ammo search logic
    }
}
