package studio.ERM.handlers;

import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.util.math.ChunkPos;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import net.minecraftforge.fml.common.gameevent.TickEvent;
import studio.ERM.war.WarNarratorManager;
import studio.ERM.war.WarRaidSpawner;
import studio.ERM.war.world.WarWorldData;

/**
 * Tracks player "Exposure" in enemy territory to trigger ambushes.
 * Deterministic stalking without chunk-loading overhead.
 */
public class WarAmbushTracker {

    @SubscribeEvent
    public void onPlayerTick(TickEvent.PlayerTickEvent event) {
        if (event.phase != TickEvent.Phase.END || event.player.world.isRemote) return;

        EntityPlayer player = event.player;
        if (player.ticksExisted % 40 != 0) return; // Check every 2 seconds

        WarWorldData data = WarWorldData.get(player.world);
        String currentOwner = data.getOwner(new ChunkPos(player.getPosition()));
        String playerID = player.getUniqueID().toString();
        WarWorldData.FactionStats stats = data.getStats(playerID);

        // Logic: Exposure only builds in enemy territory
        if (!currentOwner.equals("NEUTRAL") && !currentOwner.equals(playerID)) {
            updateExposure(player, stats, data);
        } else {
            // Decay exposure in safe territory
            stats.ambushExposure = Math.max(0, stats.ambushExposure - 0.5f);
        }
    }

    private void updateExposure(EntityPlayer player, WarWorldData.FactionStats stats, WarWorldData data) {
        if (stats.ambushCooldown > player.world.getTotalWorldTime()) return;

        // Calculate movement delta
        double dist = player.getDistanceSq(stats.lastAmbushPos != null ? stats.lastAmbushPos : player.getPosition());
        float multiplier = player.isSprinting() || player.getRidingEntity() != null ? 2.0f : 1.0f; // Noise penalty

        if (player.world.isRaining() || !player.world.isDaytime()) multiplier += 0.5f; // Visibility penalty

        stats.ambushExposure += (float)(Math.sqrt(dist) * multiplier * 0.05f);
        stats.lastAmbushPos = player.getPosition();

        // Narrator warnings
        if (stats.ambushExposure >= 60.0f && stats.ambushExposure < 65.0f) {
            WarNarratorManager.sendNarratorMessage(player, "You’ve been visible for a while.", "That has a cost.");
        }

        // Trigger Ambush
        if (stats.ambushExposure >= 100.0f) {
            triggerAmbush(player, stats, data);
        }
    }

    private void triggerAmbush(EntityPlayer player, WarWorldData.FactionStats stats, WarWorldData data) {
        stats.ambushExposure = 0;
        stats.ambushCooldown = player.world.getTotalWorldTime() + 12000; // 10 minute cooldown

        WarNarratorManager.sendNarratorMessage(player, "Contact.", "You were expected.");
        WarRaidSpawner.startRaid(player, stats.era); // Reusing raid engine for ambush
    }
}