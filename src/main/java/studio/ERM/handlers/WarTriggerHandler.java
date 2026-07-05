package studio.ERM.handlers;

import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.item.ItemStack;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import net.minecraftforge.fml.common.gameevent.TickEvent;
import studio.ERM.war.WarNarratorManager;
import studio.ERM.war.world.WarWorldData;

/**
 * Monitors player actions and inventories for specific milestone triggers.
 * Specifically handles the "Nuclear Power" detection logic.
 */
public class WarTriggerHandler {

    @SubscribeEvent
    public void onPlayerTick(TickEvent.PlayerTickEvent event) {
        if (event.phase != TickEvent.Phase.END || event.player.world.isRemote) return;

        EntityPlayer player = event.player;

        // Only check every 100 ticks (5 seconds) for performance
        if (player.ticksExisted % 100 == 0) {
            WarWorldData data = WarWorldData.get(player.world);
            WarWorldData.FactionStats stats = data.getStats(player.getUniqueID().toString());

            // Check if they haven't hit the nuclear milestone yet
            if (!stats.hasNuclearMilestone && isHoldingNuclearMaterials(player)) {
                triggerNuclearMilestone(player, stats, data);
            }
        }
    }

    private boolean isHoldingNuclearMaterials(EntityPlayer player) {
        for (ItemStack stack : player.inventory.mainInventory) {
            if (stack.isEmpty()) continue;

            String registryName = stack.getItem().getRegistryName().toString();

            // 1. NuclearCraft Fission Controller
            if (registryName.contains("nuclearcraft:fission_controller")) return true;

            // 2. U-235 (Usually metadata based, but we check name for flexibility)
            if (registryName.contains("u235") || registryName.contains("uranium_235")) return true;

            // 3. TBD Placeholder (e.g., a specific block ID you decide later)
            if (registryName.equals("nuclearcraft:TBU")) { // Example placeholder for TBD
                // return true;
            }
        }
        return false;
    }

    private void triggerNuclearMilestone(EntityPlayer player, WarWorldData.FactionStats stats, WarWorldData data) {
        stats.hasNuclearMilestone = true;
        data.markDirty();

        // Play the specific narrator lines for nuclear escalation
        WarNarratorManager.sendNarratorMessage(player,
                "Significant energy signature detected. This could be used for weapons... good thing you made it first",
                "That is a notable vulnerability.");
    }
}