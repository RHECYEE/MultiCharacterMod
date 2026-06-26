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

        // Exposure must build ONLY in genuinely hostile territory.
        // A chunk counts as the player's own (safe) when it is NEUTRAL, matches the
        // player's UUID, OR is stored under the legacy literal "PLAYER" token. Some
        // claim paths / older saves persist player-owned chunks as "PLAYER" rather than
        // the raw UUID (see WarWorldData.normalizeOwnerString), so the old check —
        // which only recognised the UUID — treated the player's OWN claimed land as
        // enemy and endlessly spammed the "you've been seen" narrator line.
        boolean ownedByPlayer = currentOwner.equals(playerID) || currentOwner.equalsIgnoreCase("PLAYER");
        boolean safeTerritory = currentOwner.equals("NEUTRAL") || ownedByPlayer;

        if (!safeTerritory) {
            updateExposure(player, stats, data);
        } else {
            // Decay exposure in safe territory and re-arm the one-shot warning once
            // we've cooled down enough, so a future genuine incursion can warn again.
            stats.ambushExposure = Math.max(0, stats.ambushExposure - 0.5f);
            if (stats.ambushExposure < 40.0f) stats.exposureWarned = false;
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

        // Narrator warning — fire ONCE per exposure ramp, not every tick while exposure
        // sits in a band (the old [60,65) check re-sent the line every 2 seconds = spam).
        if (stats.ambushExposure >= 60.0f && !stats.exposureWarned) {
            stats.exposureWarned = true;
            WarNarratorManager.sendNarratorMessage(player, "You’ve been visible for a while.", "That has a cost.");
        }

        // Trigger Ambush
        if (stats.ambushExposure >= 100.0f) {
            triggerAmbush(player, stats, data);
        }
    }

    private void triggerAmbush(EntityPlayer player, WarWorldData.FactionStats stats, WarWorldData data) {
        stats.ambushExposure = 0;
        stats.exposureWarned = false; // reset one-shot warning for the next ramp
        stats.ambushCooldown = player.world.getTotalWorldTime() + 12000; // 10 minute cooldown

        WarNarratorManager.sendNarratorMessage(player, "Contact.", "You were expected.");
        WarRaidSpawner.startRaid(player, stats.era); // Reusing raid engine for ambush
    }
}