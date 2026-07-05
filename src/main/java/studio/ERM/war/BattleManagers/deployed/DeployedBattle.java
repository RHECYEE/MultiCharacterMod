package studio.ERM.war.BattleManagers.deployed;

import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.util.math.BlockPos;

import java.util.UUID;

/**
 * Represents a battle that has been deployed (placed) on the map by a player.
 *
 * The battle will activate when the player enters the trigger radius.
 */
public class DeployedBattle {

    private final UUID id;
    private final UUID ownerUuid;
    private final String ownerName;
    private final String directorId;
    private final BlockPos position;
    private final int cpCost;
    private final long deployedAt; // World time when deployed

    // Difficulty snapshot at deploy time (1..10). Used by directors for scaling.
    private final int level;

    private final String battleSiteName;

    private boolean triggered;
    private boolean completed;

    public DeployedBattle(UUID ownerUuid, String ownerName, String directorId,
                          BlockPos position, int cpCost, int level, long worldTime, String battleSiteName) {
        this.id = UUID.randomUUID();
        this.ownerUuid = ownerUuid;
        this.ownerName = ownerName;
        this.directorId = directorId;
        this.position = position;
        this.cpCost = cpCost;
        this.level = clampLevel(level);
        this.deployedAt = worldTime;
        this.battleSiteName = battleSiteName == null ? "" : battleSiteName;
        this.triggered = false;
        this.completed = false;
    }

    private DeployedBattle(UUID id, UUID ownerUuid, String ownerName, String directorId,
                           BlockPos position, int cpCost, int level, long deployedAt,
                           String battleSiteName,
                           boolean triggered, boolean completed) {
        this.id = id;
        this.ownerUuid = ownerUuid;
        this.ownerName = ownerName;
        this.directorId = directorId;
        this.position = position;
        this.cpCost = cpCost;
        this.level = clampLevel(level);
        this.deployedAt = deployedAt;
        this.battleSiteName = battleSiteName == null ? "" : battleSiteName;
        this.triggered = triggered;
        this.completed = completed;
    }

    private static int clampLevel(int level) {
        int v = level;
        if (v < 1) v = 1;
        if (v > 10) v = 10;
        return v;
    }

    public UUID getId() {
        return id;
    }

    public UUID getOwnerUuid() {
        return ownerUuid;
    }

    public String getOwnerName() {
        return ownerName;
    }

    public String getDirectorId() {
        return directorId;
    }

    public BlockPos getPosition() {
        return position;
    }

    public int getX() {
        return position.getX();
    }

    public int getZ() {
        return position.getZ();
    }

    public int getCpCost() {
        return cpCost;
    }

    public int getLevel() {
        return level;
    }

    public long getDeployedAt() {
        return deployedAt;
    }

    public String getBattleSiteName() {
        return battleSiteName;
    }

    public boolean isTriggered() {
        return triggered;
    }

    public void setTriggered(boolean triggered) {
        this.triggered = triggered;
    }

    public boolean isCompleted() {
        return completed;
    }

    public void setCompleted(boolean completed) {
        this.completed = completed;
    }

    public boolean isInTriggerRange(int x, int z) {
        int radius = Math.max(8, DeployedBattleConfig.CATEGORY.triggerRadiusBlocks);
        long dx = (long) x - (long) position.getX();
        long dz = (long) z - (long) position.getZ();
        return (dx * dx + dz * dz) <= (long) radius * (long) radius;
    }

    public boolean isInTriggerRange(BlockPos playerPos) {
        if (playerPos == null) return false;
        return isInTriggerRange(playerPos.getX(), playerPos.getZ());
    }

    // ===================== NBT =====================

    public NBTTagCompound writeToNBT() {
        NBTTagCompound tag = new NBTTagCompound();
        tag.setString("id", id.toString());
        tag.setString("ownerUuid", ownerUuid.toString());
        tag.setString("ownerName", ownerName == null ? "" : ownerName);
        tag.setString("directorId", directorId == null ? "" : directorId);
        tag.setInteger("x", position.getX());
        tag.setInteger("y", position.getY());
        tag.setInteger("z", position.getZ());
        tag.setInteger("cpCost", cpCost);
        tag.setInteger("level", level);
        tag.setLong("deployedAt", deployedAt);
        tag.setString("battleSiteName", battleSiteName == null ? "" : battleSiteName);
        tag.setBoolean("triggered", triggered);
        tag.setBoolean("completed", completed);
        return tag;
    }

    public static DeployedBattle readFromNBT(NBTTagCompound tag) {
        if (tag == null) return null;

        try {
            UUID id = UUID.fromString(tag.getString("id"));
            UUID ownerUuid = UUID.fromString(tag.getString("ownerUuid"));
            String ownerName = tag.getString("ownerName");
            String directorId = tag.getString("directorId");
            int x = tag.getInteger("x");
            int y = tag.getInteger("y");
            int z = tag.getInteger("z");
            int cpCost = tag.getInteger("cpCost");
            int level = tag.hasKey("level") ? tag.getInteger("level") : 1;
            long deployedAt = tag.getLong("deployedAt");
            String battleSiteName = tag.getString("battleSiteName");
            boolean triggered = tag.getBoolean("triggered");
            boolean completed = tag.getBoolean("completed");

            return new DeployedBattle(
                    id,
                    ownerUuid,
                    ownerName,
                    directorId,
                    new BlockPos(x, y, z),
                    cpCost,
                    level,
                    deployedAt,
                    battleSiteName,
                    triggered,
                    completed
            );
        } catch (Throwable ignored) {
            return null;
        }
    }
}
