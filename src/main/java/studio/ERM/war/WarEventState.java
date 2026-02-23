package studio.ERM.war;

import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.ChunkPos;
import net.minecraft.world.World;
import studio.ERM.war.districts.DistrictType;

import java.util.*;

/**
 * Tracks the current war event state for the world.
 * 
 * Events:
 * - NORMAL: No active threats
 * - ALERT: Threat detected but no active event
 * - RAID: Fast, punitive, targeted harassment
 * - INVASION: Territory contest, large coordinated force
 * - BATTLE: Player-chosen, named, decisive engagement
 */
public class WarEventState {
    
    private static EventType currentEvent = EventType.NORMAL;
    private static BlockPos eventCenter = null;
    private static int eventRadius = 0;
    private static long eventStartTick = 0;
    private static String eventName = "";
    private static Set<ChunkPos> affectedChunks = new HashSet<>();
    
    public enum EventType {
        NORMAL,
        ALERT,
        RAID,
        INVASION,
        BATTLE
    }
    
    /**
     * Start a raid event
     */
    public static void startRaid(World world, BlockPos center, int radius) {
        currentEvent = EventType.RAID;
        eventCenter = center;
        eventRadius = radius;
        eventStartTick = world.getTotalWorldTime();
        eventName = "Raid";
        calculateAffectedChunks();
        
        // Notify all citizens in range
        notifyCitizens(world, EventType.RAID);
    }
    
    /**
     * Start an invasion event
     */
    public static void startInvasion(World world, BlockPos center, int radius, String name) {
        currentEvent = EventType.INVASION;
        eventCenter = center;
        eventRadius = radius;
        eventStartTick = world.getTotalWorldTime();
        eventName = name != null ? name : "Invasion";
        calculateAffectedChunks();
        
        notifyCitizens(world, EventType.INVASION);
    }
    
    /**
     * Start a battle event (player-chosen, named)
     */
    public static void startBattle(World world, BlockPos center, int radius, String battleName) {
        currentEvent = EventType.BATTLE;
        eventCenter = center;
        eventRadius = radius;
        eventStartTick = world.getTotalWorldTime();
        eventName = battleName;
        calculateAffectedChunks();
        
        notifyCitizens(world, EventType.BATTLE);
    }
    
    /**
     * Set alert state (threat detected)
     */
    public static void setAlert(World world, BlockPos threatLocation) {
        if (currentEvent == EventType.NORMAL) {
            currentEvent = EventType.ALERT;
            eventCenter = threatLocation;
            eventRadius = 64; // Alert radius
            calculateAffectedChunks();
            
            notifyCitizens(world, EventType.ALERT);
        }
    }
    
    /**
     * End current event
     */
    public static void endEvent(World world) {
        EventType previous = currentEvent;
        currentEvent = EventType.NORMAL;
        eventCenter = null;
        eventRadius = 0;
        eventName = "";
        affectedChunks.clear();
        
        if (world != null && previous != EventType.NORMAL) {
            notifyCitizens(world, EventType.NORMAL);
        }
    }
    
    /**
     * Check if a position is in the affected area
     */
    public static boolean isInEventArea(BlockPos pos) {
        if (eventCenter == null || currentEvent == EventType.NORMAL) {
            return false;
        }
        return eventCenter.getDistance(pos.getX(), pos.getY(), pos.getZ()) <= eventRadius;
    }
    
    /**
     * Check if a chunk is affected by the current event
     */
    public static boolean isChunkAffected(ChunkPos chunk) {
        return affectedChunks.contains(chunk);
    }
    
    /**
     * Get district effectiveness based on current event
     */
    public static float getDistrictEffectiveness(DistrictType type, BlockPos districtPos) {
        if (!isInEventArea(districtPos)) {
            return 1.0f;
        }
        return type.getEffectiveness(toDistrictEventState());
    }
    
    /**
     * Convert current event to district event state
     */
    private static DistrictType.EventState toDistrictEventState() {
        switch (currentEvent) {
            case ALERT: return DistrictType.EventState.ALERT;
            case RAID: return DistrictType.EventState.RAID;
            case INVASION: return DistrictType.EventState.INVASION;
            case BATTLE: return DistrictType.EventState.BATTLE;
            default: return DistrictType.EventState.NORMAL;
        }
    }
    
    /**
     * Calculate chunks affected by current event
     */
    private static void calculateAffectedChunks() {
        affectedChunks.clear();
        if (eventCenter == null) return;
        
        int chunkRadius = (eventRadius / 16) + 1;
        ChunkPos centerChunk = new ChunkPos(eventCenter);
        
        for (int cx = -chunkRadius; cx <= chunkRadius; cx++) {
            for (int cz = -chunkRadius; cz <= chunkRadius; cz++) {
                affectedChunks.add(new ChunkPos(centerChunk.x + cx, centerChunk.z + cz));
            }
        }
    }
    
    /**
     * Notify citizens about event state change
     */
    private static void notifyCitizens(World world, EventType event) {
        // This will be called by citizen AI to check state
        // Citizens poll this rather than being pushed to
    }
    
    // Getters
    public static EventType getCurrentEvent() { return currentEvent; }
    public static BlockPos getEventCenter() { return eventCenter; }
    public static int getEventRadius() { return eventRadius; }
    public static String getEventName() { return eventName; }
    public static long getEventStartTick() { return eventStartTick; }
    public static boolean isEventActive() { return currentEvent != EventType.NORMAL; }
    public static boolean isRaid() { return currentEvent == EventType.RAID; }
    public static boolean isInvasion() { return currentEvent == EventType.INVASION; }
    public static boolean isBattle() { return currentEvent == EventType.BATTLE; }
}
