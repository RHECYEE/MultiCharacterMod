package studio.ERM.war.BattleManagers.deployed;

import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.nbt.NBTTagList;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.text.TextComponentString;
import net.minecraft.util.text.TextFormatting;
import net.minecraft.world.World;
import net.minecraft.world.storage.WorldSavedData;
import net.minecraftforge.common.util.Constants;
import studio.ERM.war.BattleManagers.api.IBattleDirector;
import studio.ERM.war.BattleManagers.core.BattleEngine;
import studio.ERM.war.BattleManagers.directors.BattleDirectorEntry;
import studio.ERM.war.BattleManagers.directors.BattleDirectorRegistry;
import studio.ERM.war.world.WarWorldData;
import net.minecraft.entity.player.EntityPlayerMP;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import studio.ERM.war.BattleManagers.deployed.DeployedBattleTicker;
import studio.ERM.war.BattleManagers.deployed.DeployedBattleConfig;

/**
 * Server-side manager for deployed battles.
 *
 * Handles:
 * - Tracking deployed battles
 * - Triggering battles when players enter the area
 * - CP deduction
 * - Persistence via NBT
 */
public class DeployedBattleManager extends WorldSavedData {

    private static final String DATA_NAME = "ERM_DeployedBattles";

    private final Map<UUID, DeployedBattle> battlesById = new HashMap<>();
    private final List<DeployedBattle> activeBattles = new ArrayList<>();

    // Cooldowns to prevent spam
    private final Map<UUID, Long> playerDeployCooldowns = new HashMap<>();
    private static final long DEPLOY_COOLDOWN_TICKS = 20L * 5L; // 5 seconds between deployments

    public DeployedBattleManager() {
        super(DATA_NAME);
    }

    public DeployedBattleManager(String name) {
        super(name);
    }

    /**
     * Get the manager instance for a world
     */
    public static DeployedBattleManager get(World world) {
        if (world == null || world.isRemote) return null;

        DeployedBattleManager manager = (DeployedBattleManager) world.getMapStorage()
                .getOrLoadData(DeployedBattleManager.class, DATA_NAME);

        if (manager == null) {
            manager = new DeployedBattleManager();
            world.getMapStorage().setData(DATA_NAME, manager);
        }

        return manager;
    }

    /**
     * Attempt to deploy a battle at a location
     */
    public DeployResult deployBattle(World world, EntityPlayer player, String directorId, int worldX, int worldZ) {
        if (world == null || world.isRemote || player == null) {
            return DeployResult.failure("Invalid world or player");
        }

        if (directorId == null || directorId.trim().isEmpty()) {
            return DeployResult.failure("Invalid battle type");
        }

        // Check cooldown
        long now = world.getTotalWorldTime();
        Long lastDeploy = playerDeployCooldowns.get(player.getUniqueID());
        if (lastDeploy != null && now - lastDeploy < DEPLOY_COOLDOWN_TICKS) {
            return DeployResult.failure("Please wait before deploying another battle");
        }

        // Get director entry (server authoritative)
        BattleDirectorEntry entry = getDirectorEntry(directorId);
        if (entry == null) {
            return DeployResult.failure("Unknown battle type: " + directorId);
        }

        // Check player era/level + CP (server authoritative).
        // CP/Era live in the per-player-UUID bucket -- the SAME bucket that /war cp grants to,
        // that WarClaimHandler spends from, that the air designator spends from, that the
        // tactical map HUD displays, AND that this class's own refund path (releaseBattle) pays
        // back into. The old code read the legacy "PLAYER" bucket here, so a GUI battle deploy
        // checked a CP pool that /war cp and the map never touch -> "Not enough CP" even when the
        // map clearly showed plenty, and any refund landed in a different bucket than the charge.
        WarWorldData warData = WarWorldData.get(world);
        WarWorldData.FactionStats stats = warData.getStats(player.getUniqueID().toString());
        if (stats == null) {
            return DeployResult.failure("War stats not initialized");
        }

        if (stats.era < entry.getMinLevel()) {
            return DeployResult.failure("Requires Era " + entry.getMinLevel() + " (you are Era " + stats.era + ")");
        }

        // Compute effective CP cost (debug deployments are free)
        int effectiveCost = getEffectiveCpCost(entry);

        // Check CP
        if (stats.commandPoints < effectiveCost) {
            return DeployResult.failure("Not enough CP. Need " + effectiveCost + ", have " + stats.commandPoints);
        }

        // Check for existing battle at same/near location
        for (DeployedBattle existing : activeBattles) {
            if (!existing.isCompleted() && existing.isInTriggerRange(worldX, worldZ)) {
                return DeployResult.failure("Too close to existing battle deployment");
            }
        }

        // Deduct CP (server-side persistence)
        if (effectiveCost > 0) {
            stats.commandPoints -= effectiveCost;
            warData.markDirty();
        }

        // Snapshot difficulty at deploy time.
        int level = 1;
        try {
            // If RivalCityManager exists, it is the intended scaling input.
            level = studio.ERM.war.rival.RivalCityManager.getRivalCityLevel();
        } catch (Throwable ignored) {
            // Keep default.
        }

        // Create deployed battle
        BlockPos pos = new BlockPos(worldX, 64, worldZ); // Y will be adjusted when triggered
        String siteName = generateBattleSiteName(world, entry, worldX, worldZ);

        DeployedBattle battle = new DeployedBattle(
                player.getUniqueID(),
                player.getName(),
                entry.getId(),
                pos,
                effectiveCost,
                level,
                now,
                siteName
        );

        battlesById.put(battle.getId(), battle);
        activeBattles.add(battle);
        playerDeployCooldowns.put(player.getUniqueID(), now);
        markDirty();

        // Notify player
        String costText = (effectiveCost <= 0)
                ? (TextFormatting.AQUA + "FREE")
                : (TextFormatting.GOLD.toString() + effectiveCost + TextFormatting.GRAY + " CP");

        player.sendMessage(new TextComponentString(
                TextFormatting.GOLD + "[War] " + TextFormatting.GREEN + "Battle deployed! " +
                        TextFormatting.GRAY + "(" + entry.getDisplayName() + " at " + worldX + ", " + worldZ + ", cost " + costText + TextFormatting.GRAY + ")"
        ));

        
        // Push an immediate sync so the client drops the waypoint right away (no waiting for periodic ticker).
        if (player instanceof EntityPlayerMP) {
            try {
                DeployedBattleTicker.syncToPlayer((EntityPlayerMP) player);
            } catch (Throwable ignored) {
            }
        }

        return DeployResult.success(battle);
    }

    /**
     * Called every tick to check for battle triggers
     */
    public void tick(World world) {
        if (world == null || world.isRemote) return;

        List<DeployedBattle> toRemove = new ArrayList<>();

        for (DeployedBattle battle : activeBattles) {
            if (battle.isCompleted()) {
                toRemove.add(battle);
                continue;
            }

            if (battle.isTriggered()) {
                // Already triggered, check if battle engine finished
                BattleEngine engine = BattleEngine.get(world);
                if (!engine.hasActiveBattle()) {
                    battle.setCompleted(true);
                    toRemove.add(battle);
                }
                continue;
            }

            // Check if owner is in trigger range
            EntityPlayer owner = world.getPlayerEntityByUUID(battle.getOwnerUuid());
            if (owner != null && !owner.isDead) {
                if (battle.isInTriggerRange(owner.getPosition())) {
                    triggerBattle(world, battle, owner);
                }
            }
        }

        if (!toRemove.isEmpty()) {
            activeBattles.removeAll(toRemove);
            markDirty();
        }
    }

    private void triggerBattle(World world, DeployedBattle deployed, EntityPlayer player) {
        BattleDirectorEntry entry = getDirectorEntry(deployed.getDirectorId());
        if (entry == null) {
            deployed.setCompleted(true);
            markDirty();
            return;
        }

        BattleEngine engine = BattleEngine.get(world);
        if (engine.hasActiveBattle()) {
            // Already a battle in progress, wait
            return;
        }

        // Find proper Y coordinate at the location
        BlockPos groundPos = findGroundLevel(world, deployed.getPosition());

        // Create and start the battle
        IBattleDirector director = entry.createDirector();
        if (director == null) {
            deployed.setCompleted(true);
            markDirty();
            return;
        }

        deployed.setTriggered(true);
        markDirty();

        engine.startBattle(director, player, groundPos);

        player.sendMessage(new TextComponentString(
                TextFormatting.GOLD + "[War] " + TextFormatting.RED + "BATTLE STARTED! " +
                        TextFormatting.GRAY + entry.getDisplayName()
        ));
    }

    private BlockPos findGroundLevel(World world, BlockPos pos) {
        int x = pos.getX();
        int z = pos.getZ();

        for (int y = 255; y > 1; y--) {
            BlockPos check = new BlockPos(x, y, z);
            if (world.getBlockState(check).getMaterial().isSolid()) {
                return check.up();
            }
        }
        return new BlockPos(x, 64, z);
    }

    /**
     * Get all active (not completed) deployed battles
     */
    public List<DeployedBattle> getActiveBattles() {
        return new ArrayList<>(activeBattles);
    }

    /**
     * Get deployed battles for a specific player
     */
    public List<DeployedBattle> getBattlesForPlayer(UUID playerUuid) {
        List<DeployedBattle> result = new ArrayList<>();
        for (DeployedBattle battle : activeBattles) {
            if (battle.getOwnerUuid().equals(playerUuid) && !battle.isCompleted()) {
                result.add(battle);
            }
        }
        return result;
    }

    /**
     * Cancel a deployed battle and refund CP (partial refund)
     */
    public boolean cancelBattle(World world, EntityPlayer player, UUID battleId) {
        if (world == null || world.isRemote || player == null || battleId == null) return false;

        DeployedBattle battle = battlesById.get(battleId);
        if (battle == null || battle.isTriggered() || battle.isCompleted()) {
            return false;
        }

        if (!battle.getOwnerUuid().equals(player.getUniqueID())) {
            return false;
        }

        // Refund 50% of stored cost (debug deployments store cost=0, so refund=0 as intended)
        int refund = Math.max(0, battle.getCpCost() / 2);

        if (refund > 0) {
            WarWorldData warData = WarWorldData.get(world);
            WarWorldData.FactionStats stats = warData.getStats(player.getUniqueID().toString());
            stats.commandPoints += refund;
            warData.markDirty();
        }

        battle.setCompleted(true);
        activeBattles.remove(battle);
        markDirty();

        player.sendMessage(new TextComponentString(
                TextFormatting.GOLD + "[War] " + TextFormatting.YELLOW + "Battle cancelled. " +
                        TextFormatting.GRAY + refund + " CP refunded."
        ));

        return true;
    }

    // ===================== NBT Persistence =====================

    @Override
    public void readFromNBT(NBTTagCompound nbt) {
        battlesById.clear();
        activeBattles.clear();

        if (nbt == null) return;

        NBTTagList list = nbt.getTagList("battles", Constants.NBT.TAG_COMPOUND);
        for (int i = 0; i < list.tagCount(); i++) {
            NBTTagCompound tag = list.getCompoundTagAt(i);
            DeployedBattle battle = DeployedBattle.readFromNBT(tag);
            if (battle != null && !battle.isCompleted()) {
                battlesById.put(battle.getId(), battle);
                activeBattles.add(battle);
            }
        }
    }

    @Override
    public NBTTagCompound writeToNBT(NBTTagCompound nbt) {
        if (nbt == null) nbt = new NBTTagCompound();

        NBTTagList list = new NBTTagList();
        for (DeployedBattle battle : activeBattles) {
            if (!battle.isCompleted()) {
                list.appendTag(battle.writeToNBT());
            }
        }
        nbt.setTag("battles", list);
        return nbt;
    }

    // ===================== Helpers =====================

    private static BattleDirectorEntry getDirectorEntry(String directorId) {
        // Normalize on the registry API used everywhere (avoid get vs getById drift)
        BattleDirectorEntry entry = BattleDirectorRegistry.getById(directorId);
        if (entry != null) return entry;

        // If some older code is still registering IDs with different casing,
        // getById should already be case-insensitive, but keep this fallback safe.
        String trimmed = directorId == null ? "" : directorId.trim();
        if (!trimmed.isEmpty()) {
            return BattleDirectorRegistry.getById(trimmed);
        }

        return null;
    }

    private static int getEffectiveCpCost(BattleDirectorEntry entry) {
        if (entry == null) return 0;

        int base = Math.max(0, entry.getCpCost());

        // Debug circle is supposed to be free.
        // Server-side authoritative: if the director id/name indicates debug, cost is forced to 0.
        String id = safeLower(entry.getId());
        String name = safeLower(entry.getDisplayName());

        if (id.contains("debug") || name.contains("debug")) {
            return 0;
        }

        // If you specifically name it "debug_circle" or "debugcircle", also cover that.
        if (id.contains("circle") && (id.contains("debug") || name.contains("debug"))) {
            return 0;
        }

        return base;
    }

    private static String safeLower(String s) {
        if (s == null) return "";
        return s.toLowerCase(Locale.ROOT);
    }

    
private static String generateBattleSiteName(World world, BattleDirectorEntry entry, int worldX, int worldZ) {
    String prefix = DeployedBattleConfig.CATEGORY.waypointPrefix;
    if (prefix == null || prefix.trim().isEmpty()) prefix = "Battle Site";

    String type = entry != null ? entry.getDisplayName() : "Unknown";
    if (type == null || type.trim().isEmpty()) type = "Unknown";

    // Deterministic-ish callsign using coords + world time bucket (stable enough, avoids duplicates)
    long t = world != null ? world.getTotalWorldTime() : 0L;
    long salt = (t / 2400L); // ~2 minutes
    long h = ((long) worldX * 341873128712L) ^ ((long) worldZ * 132897987541L) ^ (salt * 2654435761L);
    int code = (int) (Math.abs(h) % 999);

    return prefix + " " + type + " #" + String.format("%03d", code);
}

// ===================== Result Class =====================

    public static class DeployResult {
        private final boolean success;
        private final String message;
        private final DeployedBattle battle;

        private DeployResult(boolean success, String message, DeployedBattle battle) {
            this.success = success;
            this.message = message;
            this.battle = battle;
        }

        public static DeployResult success(DeployedBattle battle) {
            return new DeployResult(true, "Battle deployed", battle);
        }

        public static DeployResult failure(String reason) {
            return new DeployResult(false, reason, null);
        }

        public boolean isSuccess() {
            return success;
        }

        public String getMessage() {
            return message;
        }

        public DeployedBattle getBattle() {
            return battle;
        }
    }
}