package studio.ERM.war;

import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.util.text.TextComponentString;
import net.minecraft.util.text.TextFormatting;
import net.minecraft.world.World;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import net.minecraftforge.fml.common.gameevent.TickEvent;
import studio.ERM.EpochRunnerMod;
import studio.ERM.war.districts.DistrictType;

/**
 * WAR STATUS FEEDBACK
 * 
 * Provides clear signals to players about:
 * - Why something stopped working
 * - What they should respond to
 * - Where the pressure is
 * 
 * This is legibility, not UI bloat.
 * Players need to understand why things break.
 */
@Mod.EventBusSubscriber(modid = EpochRunnerMod.MODID)
public class WarStatusFeedback {
    
    private static int tickCounter = 0;
    private static WarStateAuthority.WarPhase lastPhase = WarStateAuthority.WarPhase.PEACE;
    
    /**
     * Periodic status update to players
     */
    @SubscribeEvent
    public static void onWorldTick(TickEvent.WorldTickEvent event) {
        if (event.phase != TickEvent.Phase.END) return;
        if (event.world.isRemote) return;
        
        tickCounter++;
        World world = event.world;
        
        // Update war state
        WarStateAuthority.get().tick(world);
        
        // Update raids
        WarRaidSpawner.tick(world);
        
        // Check for phase changes and notify
        WarStateAuthority.WarPhase currentPhase = WarStateAuthority.get().getCurrentPhase();
        if (currentPhase != lastPhase) {
            onPhaseChanged(world, lastPhase, currentPhase);
            lastPhase = currentPhase;
        }
        
        // Periodic status line (every 30 seconds during events)
        if (tickCounter % 600 == 0 && WarStateAuthority.get().isEventActive()) {
            sendStatusToAll(world);
        }
    }
    
    /**
     * Handle phase change notifications
     */
    private static void onPhaseChanged(World world, WarStateAuthority.WarPhase oldPhase, 
                                       WarStateAuthority.WarPhase newPhase) {
        String message;
        
        switch (newPhase) {
            case PEACE:
                message = "§a☮ Peace restored. Districts operating normally.";
                break;
            case COLD_WAR:
                message = "§eTensions rising... Prepare defenses.";
                break;
            case RAIDS_ACTIVE:
                message = "§c⚔ Raid in progress! Defend your territory!";
                break;
            case FRONTLINE_ACTIVE:
                message = "§c⚠ Frontline active. Border chunks contested.";
                break;
            case INVASION_ACTIVE:
                message = "§4☠ INVASION! Districts OFFLINE. All hands to defense!";
                break;
            case BATTLE_ACTIVE:
                message = "§5⚔ BATTLE ENGAGED! Victory or death!";
                break;
            case ESCALATION_LOCK:
                message = "§7Combat ended. Recovering...";
                break;
            default:
                message = null;
        }
        
        if (message != null) {
            broadcastMessage(world, message);
        }
        
        // Special notifications for district status
        if (newPhase.severity >= WarStateAuthority.WarPhase.INVASION_ACTIVE.severity) {
            broadcastMessage(world, "§c⚡ All districts: §4OFFLINE");
        } else if (oldPhase.severity >= WarStateAuthority.WarPhase.INVASION_ACTIVE.severity &&
                   newPhase.severity < WarStateAuthority.WarPhase.INVASION_ACTIVE.severity) {
            broadcastMessage(world, "§a⚡ Districts coming back online...");
        }
    }
    
    /**
     * Send current status to all players
     */
    private static void sendStatusToAll(World world) {
        String status = WarStateAuthority.get().getStatusLine();
        float efficiency = WarStateAuthority.get().getDistrictEfficiency();
        
        String effString = efficiency == 0 ? "§4OFFLINE" : 
            (efficiency < 1.0 ? "§e" + (int)(efficiency * 100) + "%" : "§a100%");
        
        String fullStatus = status + " §7| Districts: " + effString;
        
        for (EntityPlayer player : world.playerEntities) {
            player.sendStatusMessage(new TextComponentString(fullStatus), true);
        }
    }
    
    /**
     * Broadcast a message to all players
     */
    private static void broadcastMessage(World world, String message) {
        for (EntityPlayer player : world.playerEntities) {
            player.sendMessage(new TextComponentString(message));
        }
    }
    
    /**
     * Get district status string for a specific district
     */
    public static String getDistrictStatus(DistrictType type) {
        WarStateAuthority authority = WarStateAuthority.get();
        float eff = authority.getDistrictEfficiency();
        
        if (eff == 0) {
            return TextFormatting.DARK_RED + "OFFLINE";
        } else if (eff < 0.5f) {
            return TextFormatting.RED + "SUPPRESSED";
        } else if (eff < 1.0f) {
            return TextFormatting.YELLOW + "REDUCED";
        } else {
            return TextFormatting.GREEN + "ONLINE";
        }
    }
    
    /**
     * Get event-specific feedback message
     */
    public static String getEventFeedback() {
        WarStateAuthority authority = WarStateAuthority.get();
        
        if (!authority.isEventActive()) {
            return "§aNo active threats";
        }
        
        StringBuilder sb = new StringBuilder();
        sb.append(authority.getCurrentPhase().color);
        sb.append(authority.getActiveEventName());
        
        int level = authority.getCurrentLevel();
        if (level > 1) {
            sb.append(" §7(Level ").append(level).append("/10)");
        }
        
        return sb.toString();
    }
    
    /**
     * Get reason why something stopped working
     */
    public static String getDisruptionReason(String systemName) {
        WarStateAuthority authority = WarStateAuthority.get();
        WarStateAuthority.WarPhase phase = authority.getCurrentPhase();
        
        switch (phase) {
            case RAIDS_ACTIVE:
                return systemName + " §6paused §7- raid in progress";
            case INVASION_ACTIVE:
                return systemName + " §coffline §7- invasion disrupting operations";
            case BATTLE_ACTIVE:
                return systemName + " §coffline §7- battle in progress";
            case ESCALATION_LOCK:
                return systemName + " §erecovering §7- post-combat cooldown";
            default:
                return systemName + " §aoperational";
        }
    }
    
    /**
     * Send a personal status update to one player
     */
    public static void sendPersonalStatus(EntityPlayer player) {
        WarStateAuthority authority = WarStateAuthority.get();
        
        player.sendMessage(new TextComponentString("§e=== WAR STATUS ==="));
        player.sendMessage(new TextComponentString(authority.getStatusLine()));
        player.sendMessage(new TextComponentString(
            "§7District Efficiency: §f" + (int)(authority.getDistrictEfficiency() * 100) + "%"
        ));
        player.sendMessage(new TextComponentString(
            "§7Escalation: §f" + String.format("%.0f", authority.getEscalation()) + "%"
        ));
        
        if (authority.isEventActive()) {
            player.sendMessage(new TextComponentString(
                "§7Event Center: §f" + formatBlockPos(authority.getEventCenter())
            ));
        }
    }
    
    private static String formatBlockPos(net.minecraft.util.math.BlockPos pos) {
        if (pos == null) return "N/A";
        return pos.getX() + ", " + pos.getY() + ", " + pos.getZ();
    }
}
