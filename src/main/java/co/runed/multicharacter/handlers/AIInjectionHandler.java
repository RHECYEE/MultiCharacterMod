package co.runed.multicharacter.handlers;

import co.runed.multicharacter.compat.EntityAIFlansGunAttack;
import co.runed.multicharacter.vehicle.EntityAIFindAmmo;
import com.flansmod.common.guns.ItemGun;
import net.minecraft.entity.EntityCreature;
import net.minecraft.entity.EntityList;
import net.minecraft.entity.ai.EntityAITasks;
import net.minecraft.inventory.EntityEquipmentSlot;
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

            // Gun infantry are AW2 Combat NPCs, Wither Skeletons, and our own battle EntitySoldier.
            // (Modern/Modular CITIZENS are deliberately NOT gun users -- per the design they are
            // civilian workers; the gun role belongs to AW2 soldiers / EntitySoldier. They were
            // briefly added here and that was wrong, so they're excluded again.)
            boolean isAW2Soldier = regName.contains("ancientwarfare") && regName.contains("combat");
            boolean isSkeleton = regName.contains("wither_skeleton");
            boolean isErmInfantry = npc instanceof studio.ERM.war.BattleManagers.entities.EntitySoldier;

            if (isAW2Soldier || isSkeleton || isErmInfantry) {

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

                    // CRITICAL: gun infantry must NOT drop their weapon/armour on death. Besides being
                    // correct (no farming a battlefield of free Flan guns), this kills a hard crash:
                    // a dropped gun the player picks up fires a broken pack advancement
                    // (minecraft:inventory_changed -> recipe reward -> ForgeHooks.sendRecipeBook NPE),
                    // which was crashing the server mid-siege as soldiers died and dropped guns.
                    for (EntityEquipmentSlot slot : EntityEquipmentSlot.values()) {
                        npc.setDropChance(slot, 0.0F);
                    }

                    // The gun AI / find-ammo tasks reference Flan core classes. Those are present in
                    // this pack (guns are configured), but guard anyway: a missing Flan class must
                    // never throw out of an EntityJoinWorldEvent handler and abort the spawn.
                    try {
                        // Priority 1: Gun Combat. Must out-rank any melee/charge task (EntitySoldier's
                        // EntityAIAttackMelee is priority 2, AW2's combat AI similar) so a gun-armed
                        // soldier KEEPS ITS DISTANCE and shoots instead of sprinting into punching
                        // range. The gun AI's own movement strafes/holds at standoff distance.
                        npc.tasks.addTask(1, new EntityAIFlansGunAttack(npc, 1.1D, 20, 100.0F));

                        // Priority 2: Find Ammo (only runs when the gun AI can't — i.e. out of ammo).
                        npc.tasks.addTask(2, new EntityAIFindAmmo(npc));

                        // Initialize Ammo NBT if missing
                        if (!npc.getEntityData().hasKey("Infantry_CurrentAmmo")) {
                            npc.getEntityData().setInteger("Infantry_CurrentAmmo", 30);
                        }
                    } catch (Throwable t) {
                        studio.ERM.EpochRunnerMod.logger.error(
                                "[MCM] Failed to inject Flan gun AI into " + regName + ": " + t, t);
                    }
                }
            }
        }
    }
}