package co.runed.multicharacter.handlers;

import co.runed.multicharacter.compat.EntityAIFlansGunAttack;
import co.runed.multicharacter.vehicle.EntityAIFindAmmo;
import com.flansmod.common.guns.ItemGun;
import net.minecraft.entity.EntityCreature;
import net.minecraft.entity.EntityList;
import net.minecraft.entity.ai.EntityAITasks;
import net.minecraft.item.ItemStack;
import net.minecraft.util.ResourceLocation;
import net.minecraftforge.event.entity.EntityJoinWorldEvent;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;

public class AIInjectionHandler {

    @SubscribeEvent
    public void onEntityJoin(EntityJoinWorldEvent event) {
        if (event.getWorld().isRemote) return;

        if (event.getEntity() instanceof EntityCreature) {
            EntityCreature npc = (EntityCreature) event.getEntity();

            ResourceLocation id = EntityList.getKey(npc);
            String regName = (id != null) ? id.toString() : "";

            // Check for AW2 Combat NPCs or Wither Skeletons
            boolean isAW2Soldier = regName.contains("ancientwarfare") && regName.contains("combat");
            boolean isSkeleton = regName.contains("wither_skeleton");

            if (isAW2Soldier || isSkeleton) {

                // CRITICAL FIX: DO NOT RENAME THE ENTITY.
                // Setting a custom name tag breaks AW2 icons and AI orders.
                // npc.setCustomNameTag("Infantry"); <--- REMOVED

                // Check if they are holding a GUN
                boolean hasGun = false;
                ItemStack mainHand = npc.getHeldItemMainhand();
                if (!mainHand.isEmpty()) {
                    String itemName = mainHand.getItem().getRegistryName().toString();
                    if (mainHand.getItem() instanceof ItemGun || itemName.contains("flansmod") || itemName.contains("gun")) {
                        hasGun = true;
                    }
                }

                // ONLY inject Combat AI if they have a gun.
                // Otherwise, let them be normal AW2 soldiers (follow orders, farm, patrol).
                if (hasGun) {
                    // Prevent duplicate AI injection
                    for (EntityAITasks.EntityAITaskEntry entry : npc.tasks.taskEntries) {
                        if (entry.action instanceof EntityAIFlansGunAttack) return;
                    }

                    // Priority 2: Find Ammo (Runs if ammo is empty)
                    npc.tasks.addTask(2, new EntityAIFindAmmo(npc));

                    // Priority 3: Gun Combat (Runs if target exists)
                    npc.tasks.addTask(3, new EntityAIFlansGunAttack(npc, 1.1D, 20, 50.0F));

                    // Initialize Ammo NBT if missing
                    if (!npc.getEntityData().hasKey("Infantry_CurrentAmmo")) {
                        npc.getEntityData().setInteger("Infantry_CurrentAmmo", 30);
                    }
                }
            }
        }
    }
}