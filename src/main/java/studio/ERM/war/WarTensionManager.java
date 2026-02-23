package studio.ERM.handlers;

import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.util.math.ChunkPos;
import net.minecraft.util.text.TextComponentString;
import net.minecraft.util.text.TextFormatting;
import net.minecraft.world.World;
import net.minecraftforge.event.entity.living.LivingDeathEvent;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import net.minecraftforge.fml.common.gameevent.TickEvent;
import studio.ERM.war.world.WarWorldData;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Manages the "Tension" state and Raid lifecycle.
 * Handles natural cooling, border friction, and the 3-death loss condition.
 *
 * ALSO provides a stable, shared API for HUD/scoreboard:
 *  - thresholds (RAID/BATTLE/AIRSTRIKE/CRISIS)
 *  - tier label + color helpers
 */
public class WarTensionManager {

    // ======================================================================
    // PUBLIC UI / SHARED THRESHOLDS (0..100 scale)
    // These are referenced by HUD + scoreboard and MUST remain public.
    // ======================================================================
    public static final int RAID_THRESHOLD = 25;
    public static final int BATTLE_THRESHOLD = 50;
    public static final int AIRSTRIKE_THRESHOLD = 75;
    public static final int CRISIS_THRESHOLD = 90;

    /**
     * Returns a short tier label for a given tension value.
     * Values outside 0..100 are clamped.
     */
    public static String getTensionTier(int tension) {
        int t = clampToPercent(tension);

        if (t >= CRISIS_THRESHOLD) return "CRISIS";
        if (t >= AIRSTRIKE_THRESHOLD) return "AIRSTRIKE";
        if (t >= BATTLE_THRESHOLD) return "BATTLE";
        if (t >= RAID_THRESHOLD) return "RAID";
        return "CALM";
    }

    /**
     * Returns an ARGB color (0xAARRGGBB) representing tension tier.
     * Used by HUD rendering.
     */
    public static int getTensionColor(int tension) {
        int t = clampToPercent(tension);

        if (t >= CRISIS_THRESHOLD) return 0xFF7F0000;     // dark red
        if (t >= AIRSTRIKE_THRESHOLD) return 0xFFFF0000;  // red
        if (t >= BATTLE_THRESHOLD) return 0xFFFFAA00;     // gold/orange
        if (t >= RAID_THRESHOLD) return 0xFFFFFF55;       // yellow
        return 0xFF55FF55;                                 // green
    }

    /**
     * Returns a TextFormatting value for chat/scoreboard display.
     */
    public static TextFormatting getTensionFormatting(int tension) {
        int t = clampToPercent(tension);

        if (t >= CRISIS_THRESHOLD) return TextFormatting.DARK_RED;
        if (t >= AIRSTRIKE_THRESHOLD) return TextFormatting.RED;
        if (t >= BATTLE_THRESHOLD) return TextFormatting.GOLD;
        if (t >= RAID_THRESHOLD) return TextFormatting.YELLOW;
        return TextFormatting.GREEN;
    }

    private static int clampToPercent(int tension) {
        if (tension < 0) return 0;
        if (tension > 100) return 100;
        return tension;
    }

    // ======================================================================
    // EXISTING RAID LOGIC (kept intact)
    // ======================================================================

    // This is your raid trigger threshold; leaving it as-is to avoid behavior changes.
    private static final float TENSION_THRESHOLD_RAID = 80.0f;

    private static final float BORDER_FRICTION_CAP = 40.0f;
    private static final float DECAY_PER_HOUR = 2.0f;

    // Tracks player deaths during an active raid
    private final Map<UUID, Integer> raidDeaths = new HashMap<>();

    @SubscribeEvent
    public void onWorldTick(TickEvent.WorldTickEvent event) {
        if (event.phase != TickEvent.Phase.END || event.world.isRemote) return;

        // Perform logic every minute (1200 ticks) to save performance
        if (event.world.getTotalWorldTime() % 1200 == 0) {
            updateWorldTension(event.world);
        }
    }

    private void updateWorldTension(World world) {
        WarWorldData data = WarWorldData.get(world);

        // 1. Check for Global Peace (Air Defense 100)
        if (data.getStats("PLAYER_GLOBAL").airDefense >= 100) return;

        for (EntityPlayer player : world.playerEntities) {
            WarWorldData.FactionStats stats = data.getStats(player.getUniqueID().toString());

            // 2. Natural Cooling (Lowered through time)
            if (stats.tension > 0) {
                stats.tension = Math.max(0, stats.tension - (DECAY_PER_HOUR / 60.0f));
            }

            // 3. Border Friction
            // Checks if player chunks touch AW2 chunks; raises tension to a cap
            if (isBorderShared(world, player, data)) {
                stats.tension = Math.min(BORDER_FRICTION_CAP, stats.tension + 0.5f);
            }

            // 4. Trigger Raid
            if (stats.tension >= TENSION_THRESHOLD_RAID && !isRaidActive(player.getUniqueID())) {
                startRaid(player);
            }
        }

        data.markDirty();
    }

    private boolean isBorderShared(World world, EntityPlayer player, WarWorldData data) {
        ChunkPos playerChunk = new ChunkPos(player.getPosition());
        String playerID = player.getUniqueID().toString();

        // Simple cross-check of adjacent chunks
        for (int x = -1; x <= 1; x++) {
            for (int z = -1; z <= 1; z++) {
                String owner = data.getOwner(new ChunkPos(playerChunk.x + x, playerChunk.z + z));
                if (!owner.equals("NEUTRAL") && !owner.equals(playerID)) {
                    return true; // Border is shared with a rival (AW2) faction
                }
            }
        }
        return false;
    }

    private void startRaid(EntityPlayer player) {
        raidDeaths.put(player.getUniqueID(), 0);
        player.sendMessage(new TextComponentString(TextFormatting.RED + "WAR DECLARED! Enemy forces are raiding your territory."));
        player.sendMessage(new TextComponentString(TextFormatting.GRAY + "Loss Condition: 3 Deaths. Defend your people and your colony."));
        // Logic to spawn AW2 warriors targeting NPCs/Players would be called here
    }

    @SubscribeEvent
    public void onPlayerDeath(LivingDeathEvent event) {
        if (!(event.getEntityLiving() instanceof EntityPlayer)) return;

        EntityPlayer player = (EntityPlayer) event.getEntityLiving();
        UUID uuid = player.getUniqueID();

        if (isRaidActive(uuid)) {
            int deaths = raidDeaths.get(uuid) + 1;
            raidDeaths.put(uuid, deaths);

            if (deaths >= 3) {
                endRaid(player, false); // Player lost
            } else {
                player.sendMessage(new TextComponentString(TextFormatting.YELLOW + "Raid Warning: You have died " + deaths + "/3 times!"));
            }
        }
    }

    private void endRaid(EntityPlayer player, boolean victory) {
        raidDeaths.remove(player.getUniqueID());

        WarWorldData data = WarWorldData.get(player.world);
        WarWorldData.FactionStats stats = data.getStats(player.getUniqueID().toString());

        if (victory) {
            player.sendMessage(new TextComponentString(TextFormatting.GREEN + "RAID DEFLECTED! Enemy forces are retreating. Tension lowered."));
            stats.tension = 0; // Massive drop on victory
        } else {
            player.sendMessage(new TextComponentString(TextFormatting.DARK_RED + "TOTAL DEFEAT! The enemy has broken your command."));
            stats.tension = 50.0f; // Reset to moderate tension
            // Penalty logic (e.g., losing CP or territory) would go here
        }

        data.markDirty();
    }

    public boolean isRaidActive(UUID playerUUID) {
        return raidDeaths.containsKey(playerUUID);
    }
}
