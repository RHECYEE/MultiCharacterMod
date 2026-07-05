package studio.ERM.war;

import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.ChunkPos;
import net.minecraft.util.text.TextComponentString;
import net.minecraft.util.text.TextFormatting;
import net.minecraft.world.World;
import net.minecraftforge.fml.common.FMLCommonHandler;
import studio.ERM.EpochRunnerMod;

/**
 * WAR STATE AUTHORITY
 * 
 * The single server-side brain that answers:
 * "What phase of war is the world in right now?"
 * 
 * Everything else checks this FIRST before doing anything.
 * This is the conductor that prevents:
 * - Events overlapping
 * - Pacing breaking
 * - Players feeling spammed
 * - Debugging hell
 * 
 * War Phases (in order of escalation):
 * 1. PEACE - No active threats, economy building
 * 2. COLD_WAR - Tension building, raids possible
 * 3. RAIDS_ACTIVE - Small harassing attacks happening
 * 4. FRONTLINE_ACTIVE - Territory being contested
 * 5. INVASION_ACTIVE - Large coordinated attack
 * 6. BATTLE_ACTIVE - Named decisive battle in progress
 * 7. ESCALATION_LOCK - Cooldown between major events
 */
public class WarStateAuthority {
    
    // ===== SINGLETON =====
    private static WarStateAuthority instance;
    
    public static WarStateAuthority get() {
        if (instance == null) {
            instance = new WarStateAuthority();
        }
        return instance;
    }
    
    // ===== WAR PHASE ENUM =====
    public enum WarPhase {
        PEACE("Peace", TextFormatting.GREEN, 0),
        COLD_WAR("Cold War", TextFormatting.YELLOW, 1),
        RAIDS_ACTIVE("Raids Active", TextFormatting.GOLD, 2),
        FRONTLINE_ACTIVE("Frontline Active", TextFormatting.RED, 3),
        INVASION_ACTIVE("Invasion!", TextFormatting.DARK_RED, 4),
        BATTLE_ACTIVE("Battle!", TextFormatting.DARK_PURPLE, 5),
        ESCALATION_LOCK("Cooldown", TextFormatting.GRAY, 6);
        
        public final String displayName;
        public final TextFormatting color;
        public final int severity;
        
        WarPhase(String name, TextFormatting color, int severity) {
            this.displayName = name;
            this.color = color;
            this.severity = severity;
        }
    }
    
    // ===== CURRENT STATE =====
    private WarPhase currentPhase = WarPhase.PEACE;
    private int currentLevel = 1;  // 1-10 scale for all events
    private long phaseStartTick = 0;
    private long phaseDurationTicks = 0;
    
    // Event details
    private String activeEventName = "";
    private BlockPos eventCenter = null;
    private int eventRadius = 0;
    private ChunkPos targetChunk = null;
    
    // Escalation tracking
    private float escalationPressure = 0.0f;  // 0-100, triggers phase changes
    private int consecutivePlayerWins = 0;
    private int consecutivePlayerLosses = 0;
    
    // Cooldowns (in ticks)
    private long lastRaidTick = 0;
    private long lastInvasionTick = 0;
    private long lastBattleTick = 0;
    private static final int RAID_COOLDOWN = 6000;      // 5 minutes
    private static final int INVASION_COOLDOWN = 24000; // 20 minutes
    private static final int BATTLE_COOLDOWN = 36000;   // 30 minutes
    
    // ===== PHASE QUERIES (Everything checks these) =====
    
    public WarPhase getCurrentPhase() {
        return currentPhase;
    }
    
    public int getCurrentLevel() {
        return currentLevel;
    }
    
    public boolean isAtPeace() {
        return currentPhase == WarPhase.PEACE;
    }
    
    public boolean canStartRaid() {
        if (currentPhase.severity >= WarPhase.INVASION_ACTIVE.severity) return false;
        return System.currentTimeMillis() > lastRaidTick + RAID_COOLDOWN * 50;
    }
    
    public boolean canStartInvasion() {
        if (currentPhase.severity >= WarPhase.BATTLE_ACTIVE.severity) return false;
        if (currentPhase == WarPhase.INVASION_ACTIVE) return false;
        return System.currentTimeMillis() > lastInvasionTick + INVASION_COOLDOWN * 50;
    }
    
    public boolean canStartBattle() {
        if (currentPhase == WarPhase.BATTLE_ACTIVE) return false;
        return System.currentTimeMillis() > lastBattleTick + BATTLE_COOLDOWN * 50;
    }
    
    public boolean isEventActive() {
        return currentPhase.severity >= WarPhase.RAIDS_ACTIVE.severity &&
               currentPhase != WarPhase.ESCALATION_LOCK;
    }
    
    public boolean shouldDistrictsOperate() {
        switch (currentPhase) {
            case PEACE:
            case COLD_WAR:
                return true;
            case RAIDS_ACTIVE:
                return true; // Reduced efficiency handled elsewhere
            case ESCALATION_LOCK:
                return true;
            default:
                return false; // Invasion/Battle = districts offline
        }
    }
    
    public float getDistrictEfficiency() {
        switch (currentPhase) {
            case PEACE: return 1.0f;
            case COLD_WAR: return 0.95f;
            case RAIDS_ACTIVE: return 0.7f;
            case FRONTLINE_ACTIVE: return 0.5f;
            case INVASION_ACTIVE: return 0.0f;
            case BATTLE_ACTIVE: return 0.0f;
            case ESCALATION_LOCK: return 0.8f;
            default: return 1.0f;
        }
    }
    
    // ===== PHASE TRANSITIONS =====
    
    /**
     * Start a raid event
     * @param world The world
     * @param center Center of raid
     * @param level Raid level 1-10
     * @return true if raid started
     */
    public boolean startRaid(World world, BlockPos center, int level) {
        if (!canStartRaid()) {
            EpochRunnerMod.logger.info("[WAR] Cannot start raid - cooldown or higher event active");
            return false;
        }
        
        currentPhase = WarPhase.RAIDS_ACTIVE;
        currentLevel = Math.max(1, Math.min(10, level));
        eventCenter = center;
        eventRadius = 48 + (level * 8);
        activeEventName = "Raid Level " + level;
        phaseStartTick = world.getTotalWorldTime();
        phaseDurationTicks = 2400 + (level * 600); // 2-8 minutes based on level
        lastRaidTick = System.currentTimeMillis();
        
        broadcastPhaseChange(world, "⚔ RAID INCOMING - Level " + level);
        EpochRunnerMod.logger.info("[WAR] RAID STARTED - Level " + level + " at " + center);
        
        return true;
    }
    
    /**
     * Start an invasion event
     * @param world The world
     * @param center Center of invasion
     * @param level Invasion level 1-10
     * @param name Optional name
     * @return true if invasion started
     */
    public boolean startInvasion(World world, BlockPos center, int level, String name) {
        if (!canStartInvasion()) {
            EpochRunnerMod.logger.info("[WAR] Cannot start invasion - cooldown or battle active");
            return false;
        }
        
        currentPhase = WarPhase.INVASION_ACTIVE;
        currentLevel = Math.max(1, Math.min(10, level));
        eventCenter = center;
        eventRadius = 64 + (level * 16);
        activeEventName = name != null ? name : "Invasion Level " + level;
        phaseStartTick = world.getTotalWorldTime();
        phaseDurationTicks = 6000 + (level * 1200); // 5-15 minutes based on level
        lastInvasionTick = System.currentTimeMillis();
        
        broadcastPhaseChange(world, "☠ INVASION: " + activeEventName);
        EpochRunnerMod.logger.info("[WAR] INVASION STARTED - " + activeEventName + " at " + center);
        
        return true;
    }
    
    /**
     * Start a named battle
     * @param world The world
     * @param chunk Battle chunk
     * @param level Battle level 1-10
     * @param name Battle name
     * @return true if battle started
     */
    public boolean startBattle(World world, ChunkPos chunk, int level, String name) {
        if (!canStartBattle()) {
            EpochRunnerMod.logger.info("[WAR] Cannot start battle - already in battle or cooldown");
            return false;
        }
        
        currentPhase = WarPhase.BATTLE_ACTIVE;
        currentLevel = Math.max(1, Math.min(10, level));
        targetChunk = chunk;
        eventCenter = new BlockPos(chunk.getXStart() + 8, 64, chunk.getZStart() + 8);
        eventRadius = 64 + (level * 8);
        activeEventName = name;
        phaseStartTick = world.getTotalWorldTime();
        phaseDurationTicks = -1; // Battles don't auto-end
        lastBattleTick = System.currentTimeMillis();
        
        broadcastPhaseChange(world, "⚔ BATTLE BEGINS: " + name + " (Level " + level + ")");
        EpochRunnerMod.logger.info("[WAR] BATTLE STARTED - " + name + " Level " + level);
        
        return true;
    }
    
    /**
     * End the current event
     * @param world The world
     * @param playerWon Did the player win?
     */
    public void endEvent(World world, boolean playerWon) {
        WarPhase previousPhase = currentPhase;
        String previousName = activeEventName;
        
        // Track wins/losses
        if (playerWon) {
            consecutivePlayerWins++;
            consecutivePlayerLosses = 0;
            escalationPressure = Math.max(0, escalationPressure - 10);
        } else {
            consecutivePlayerLosses++;
            consecutivePlayerWins = 0;
            escalationPressure = Math.min(100, escalationPressure + 15);
        }
        
        // Enter cooldown
        currentPhase = WarPhase.ESCALATION_LOCK;
        activeEventName = "";
        eventCenter = null;
        eventRadius = 0;
        targetChunk = null;
        phaseStartTick = world.getTotalWorldTime();
        phaseDurationTicks = getCooldownDuration(previousPhase);
        
        String result = playerWon ? TextFormatting.GREEN + "VICTORY" : TextFormatting.RED + "DEFEAT";
        broadcastPhaseChange(world, previousName + " - " + result);
        EpochRunnerMod.logger.info("[WAR] EVENT ENDED - " + previousName + " - " + (playerWon ? "WIN" : "LOSS"));
    }

    /**
     * Legacy helper referenced by WarRaidSpawner.
     * A raid ending is treated as a "player won" outcome.
     */
    public void endRaid(World world) {
        endEvent(world, true);
    }
    
    /**
     * Force end event (admin/debug)
     */
    public void forceEndEvent(World world) {
        currentPhase = WarPhase.PEACE;
        activeEventName = "";
        eventCenter = null;
        eventRadius = 0;
        targetChunk = null;
        currentLevel = 1;
        
        broadcastPhaseChange(world, "Event ended by authority");
        EpochRunnerMod.logger.info("[WAR] EVENT FORCE ENDED");
    }
    
    /**
     * Tick update - handles auto-transitions
     */
    public void tick(World world) {
        if (world.isRemote) return;
        
        long currentTick = world.getTotalWorldTime();
        
        // Check phase duration
        if (phaseDurationTicks > 0 && currentTick - phaseStartTick > phaseDurationTicks) {
            // Auto-end timed events
            if (currentPhase == WarPhase.RAIDS_ACTIVE) {
                endEvent(world, true); // Survived raid = win
            } else if (currentPhase == WarPhase.ESCALATION_LOCK) {
                // Cooldown over, return to appropriate phase
                currentPhase = calculateBasePhase();
                broadcastPhaseChange(world, "Situation: " + currentPhase.displayName);
            }
        }
        
        // Escalation pressure decay
        if (currentTick % 1200 == 0) { // Every minute
            escalationPressure = Math.max(0, escalationPressure - 1);
        }
        
        // Check for phase escalation based on pressure
        if (currentPhase == WarPhase.PEACE && escalationPressure > 20) {
            currentPhase = WarPhase.COLD_WAR;
            broadcastPhaseChange(world, "Tensions rising...");
        } else if (currentPhase == WarPhase.COLD_WAR && escalationPressure > 50) {
            currentPhase = WarPhase.FRONTLINE_ACTIVE;
            broadcastPhaseChange(world, "Frontline activity detected");
        }
    }
    
    private WarPhase calculateBasePhase() {
        if (escalationPressure > 50) return WarPhase.FRONTLINE_ACTIVE;
        if (escalationPressure > 20) return WarPhase.COLD_WAR;
        return WarPhase.PEACE;
    }
    
    private int getCooldownDuration(WarPhase fromPhase) {
        switch (fromPhase) {
            case RAIDS_ACTIVE: return 1200; // 1 minute
            case INVASION_ACTIVE: return 3600; // 3 minutes
            case BATTLE_ACTIVE: return 6000; // 5 minutes
            default: return 600;
        }
    }
    
    // ===== ESCALATION =====
    
    public void addEscalation(float amount) {
        escalationPressure = Math.min(100, escalationPressure + amount);
    }
    
    public float getEscalation() {
        return escalationPressure;
    }
    
    // ===== STATUS REPORTING =====
    
    /**
     * Get a status line for the player
     */
    public String getStatusLine() {
        StringBuilder sb = new StringBuilder();
        sb.append(currentPhase.color).append("[").append(currentPhase.displayName).append("]");
        
        if (!activeEventName.isEmpty()) {
            sb.append(" ").append(TextFormatting.WHITE).append(activeEventName);
        }
        
        if (currentLevel > 1) {
            sb.append(" §7(Lvl ").append(currentLevel).append(")");
        }
        
        return sb.toString();
    }
    
    /**
     * Get detailed status for commands
     */
    public String[] getDetailedStatus() {
        return new String[] {
            "§e=== WAR STATE AUTHORITY ===",
            "Phase: " + currentPhase.color + currentPhase.displayName,
            "Level: §f" + currentLevel + "/10",
            "Event: §f" + (activeEventName.isEmpty() ? "None" : activeEventName),
            "Escalation: §f" + String.format("%.1f", escalationPressure) + "%",
            "District Efficiency: §f" + String.format("%.0f", getDistrictEfficiency() * 100) + "%",
            "Can Raid: " + (canStartRaid() ? "§aYes" : "§cNo"),
            "Can Invade: " + (canStartInvasion() ? "§aYes" : "§cNo"),
            "Can Battle: " + (canStartBattle() ? "§aYes" : "§cNo")
        };
    }
    
    // ===== BROADCASTING =====
    
    private void broadcastPhaseChange(World world, String message) {
        if (world == null) return;
        
        String formatted = currentPhase.color + "§l[WAR] §r" + currentPhase.color + message;
        
        for (EntityPlayer player : world.playerEntities) {
            player.sendMessage(new TextComponentString(formatted));
        }
    }
    
    /**
     * Send status update to a specific player
     */
    public void sendStatusTo(EntityPlayer player) {
        player.sendMessage(new TextComponentString(getStatusLine()));
    }
    
    // ===== NBT PERSISTENCE =====
    
    public NBTTagCompound writeToNBT(NBTTagCompound compound) {
        compound.setString("phase", currentPhase.name());
        compound.setInteger("level", currentLevel);
        compound.setFloat("escalation", escalationPressure);
        compound.setString("eventName", activeEventName);
        compound.setInteger("wins", consecutivePlayerWins);
        compound.setInteger("losses", consecutivePlayerLosses);
        
        if (eventCenter != null) {
            compound.setInteger("centerX", eventCenter.getX());
            compound.setInteger("centerY", eventCenter.getY());
            compound.setInteger("centerZ", eventCenter.getZ());
        }
        compound.setInteger("radius", eventRadius);
        
        return compound;
    }
    
    public void readFromNBT(NBTTagCompound compound) {
        try {
            currentPhase = WarPhase.valueOf(compound.getString("phase"));
        } catch (Exception e) {
            currentPhase = WarPhase.PEACE;
        }
        currentLevel = compound.getInteger("level");
        escalationPressure = compound.getFloat("escalation");
        activeEventName = compound.getString("eventName");
        consecutivePlayerWins = compound.getInteger("wins");
        consecutivePlayerLosses = compound.getInteger("losses");
        
        if (compound.hasKey("centerX")) {
            eventCenter = new BlockPos(
                compound.getInteger("centerX"),
                compound.getInteger("centerY"),
                compound.getInteger("centerZ")
            );
        }
        eventRadius = compound.getInteger("radius");
    }
    
    // ===== GETTERS =====
    
    public BlockPos getEventCenter() { return eventCenter; }
    public int getEventRadius() { return eventRadius; }
    public String getActiveEventName() { return activeEventName; }
    public ChunkPos getTargetChunk() { return targetChunk; }
}
