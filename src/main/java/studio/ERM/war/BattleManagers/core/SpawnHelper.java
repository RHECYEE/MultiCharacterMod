package studio.ERM.war.BattleManagers.core;

import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityCreature;
import net.minecraft.entity.EntityLiving;
import net.minecraft.entity.EntityLivingBase;
import net.minecraft.entity.ai.EntityAINearestAttackableTarget;
import net.minecraft.entity.passive.AbstractHorse;
import net.minecraft.entity.passive.EntityHorse;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;
import studio.ERM.EpochRunnerMod;
import studio.ERM.war.BattleManagers.entities.EntitySoldier;
import studio.ERM.war.BattleManagers.entities.SoldierLoadout;
import studio.ERM.war.skins.SkinPoolManager;
import studio.ERM.war.vehicle.EntityAIPilot;

import java.util.Random;
import java.util.UUID;

/**
 * v8 — Spawns EntitySoldier as the primary combat entity on formation release.
 *
 * The old AW2 reflection path silently failed, causing puppets to disappear.
 * Now we spawn our own EntitySoldier with proper loadout, skin, and team.
 *
 * Payload ID format:
 *   "soldier:ROLE"        → EntitySoldier with that role (MELEE, RANGED, HEAVY, SPECIAL, SHIELDWALL, CAVALRY)
 *   "flans:vehicle:NAME"  → EntityAIPilot in Flans vehicle (unchanged)
 *   "aw2:TYPE"            → Legacy fallback (still tries reflection, then falls back to EntitySoldier)
 */
public final class SpawnHelper {

    public static final String AW2_ENEMY_FACTION = "empire";
    private static final Random RNG = new Random();

    private SpawnHelper() {}

    // ══════════════════════════════════════════════
    //  MAIN SPAWN ENTRY POINT
    // ══════════════════════════════════════════════

    public static Entity spawnPayload(World world, BlockPos pos, String payloadId) {
        return spawnPayload(world, pos, payloadId, null, null);
    }

    /**
     * Spawn a payload entity at the given position.
     *
     * @param payloadId  The payload identifier (see format above)
     * @param targetPlayerUuid The player to aggro onto (nullable)
     * @param battleSite Battle center for home position (nullable)
     */
    public static Entity spawnPayload(World world, BlockPos pos, String payloadId, UUID targetPlayerUuid, BlockPos battleSite) {
        return spawnPayload(world, pos, payloadId, targetPlayerUuid, battleSite, 1, "MELEE", "");
    }

    /**
     * Full spawn with level, role, and skin info for EntitySoldier creation.
     */
    public static Entity spawnPayload(World world, BlockPos pos, String payloadId,
                                       UUID targetPlayerUuid, BlockPos battleSite,
                                       int warLevel, String unitRole, String skinKey) {
        if (world.isRemote) return null;
        if (payloadId == null) payloadId = "";
        payloadId = payloadId.trim().toLowerCase();

        Entity spawned;

        if (payloadId.startsWith("flans:vehicle:")) {
            // Flans vehicle with pilot (unchanged)
            String vehicleShortName = payloadId.substring("flans:vehicle:".length()).trim();
            spawned = spawnFlansVehiclePilot(world, pos, vehicleShortName);
        } else if (payloadId.startsWith("soldier:")) {
            // Direct soldier spawn with role
            String role = payloadId.substring("soldier:".length()).toUpperCase().trim();
            if (role.isEmpty()) role = unitRole;
            spawned = spawnSoldier(world, pos, warLevel, role, skinKey);
        } else {
            // Default: spawn EntitySoldier (replaces broken AW2 reflection)
            // Map old aw2: payloads to roles
            String role = mapLegacyPayloadToRole(payloadId, unitRole);
            spawned = spawnSoldier(world, pos, warLevel, role, skinKey);
        }

        // Apply battle aggro
        if (spawned != null && targetPlayerUuid != null) {
            EntityPlayer p = world.getPlayerEntityByUUID(targetPlayerUuid);
            if (p != null && !p.isDead) {
                applyBattleAggro(spawned, p, battleSite);
            }
        }

        return spawned;
    }

    // ══════════════════════════════════════════════
    //  SOLDIER SPAWN
    // ══════════════════════════════════════════════

    private static Entity spawnSoldier(World world, BlockPos pos, int warLevel, String role, String skinKey) {
        try {
            EntitySoldier soldier = new EntitySoldier(world);
            soldier.setPosition(pos.getX() + 0.5, pos.getY(), pos.getZ() + 0.5);
            soldier.setTeam_("empire");
            soldier.configure(warLevel, role, skinKey);

            // Apply skin from pool if no specific skin was passed
            if (skinKey == null || skinKey.isEmpty()) {
                try {
                    SkinPoolManager.applySkinForRivalLevel(soldier, warLevel, RNG);
                } catch (Throwable ignored) {}
            }

            // Set home position for pathing (prevents wandering too far)
            soldier.setHomePosAndDistance(pos, 64);

            world.spawnEntity(soldier);

            // Cavalry: mount soldier on a horse
            if ("CAVALRY".equalsIgnoreCase(role)) {
                try {
                    spawnCavalryMount(world, soldier, pos, warLevel);
                } catch (Throwable t) {
                    EpochRunnerMod.logger.debug("[SpawnHelper] Could not mount cavalry: {}", t.getMessage());
                }
            }

            EpochRunnerMod.logger.debug("[SpawnHelper] Spawned EntitySoldier L{} role={} at {}",
                warLevel, role, pos);

            return soldier;
        } catch (Throwable t) {
            EpochRunnerMod.logger.error("[SpawnHelper] Failed to spawn EntitySoldier: {}", t.getMessage());
            return null;
        }
    }

    /**
     * Spawn a horse and mount the cavalry soldier onto it.
     * Horse speed and health scale with warLevel.
     */
    private static void spawnCavalryMount(World world, EntitySoldier rider, BlockPos pos, int warLevel) {
        EntityHorse horse = new EntityHorse(world);
        horse.setPosition(pos.getX() + 0.5, pos.getY(), pos.getZ() + 0.5);

        // Scale horse stats with war level
        horse.setGrowingAge(0); // Adult
        horse.setHorseTamed(true);
        horse.setHorseSaddled(true);

        // Increase speed and health with level
        double baseSpeed = 0.225 + (warLevel * 0.008);  // 0.233 at L1, 0.305 at L10
        double baseHealth = 20.0 + (warLevel * 3.0);    // 23 at L1, 50 at L10
        double baseJump = 0.5 + (warLevel * 0.03);

        horse.getEntityAttribute(net.minecraft.entity.SharedMonsterAttributes.MOVEMENT_SPEED).setBaseValue(baseSpeed);
        horse.getEntityAttribute(net.minecraft.entity.SharedMonsterAttributes.MAX_HEALTH).setBaseValue(baseHealth);
        horse.setHealth((float) baseHealth);
        try {
            // JUMP_STRENGTH is protected in AbstractHorse, access via reflection
            java.lang.reflect.Field jumpField = AbstractHorse.class.getDeclaredField("JUMP_STRENGTH");
            jumpField.setAccessible(true);
            net.minecraft.entity.ai.attributes.IAttribute jumpAttr =
                    (net.minecraft.entity.ai.attributes.IAttribute) jumpField.get(null);
            horse.getEntityAttribute(jumpAttr).setBaseValue(baseJump);
        } catch (Throwable ignored) {
            // If reflection fails, horse uses default jump strength
        }

        // No drops
        horse.enablePersistence();

        world.spawnEntity(horse);

        // Mount the soldier on the horse
        rider.startRiding(horse, true);

        EpochRunnerMod.logger.debug("[SpawnHelper] Cavalry mounted on horse at {}", pos);
    }

    // ══════════════════════════════════════════════
    //  FLANS VEHICLE SPAWN (unchanged)
    // ══════════════════════════════════════════════

    private static Entity spawnFlansVehiclePilot(World world, BlockPos pos, String vehicleName) {
        EntityAIPilot pilot = new EntityAIPilot(world);
        pilot.setPosition(pos.getX() + 0.5, pos.getY() + 1.0, pos.getZ() + 0.5);
        pilot.setVehicleType(vehicleName);
        pilot.setMcmTeam("empire");
        world.spawnEntity(pilot);
        return pilot;
    }

    // ══════════════════════════════════════════════
    //  LEGACY PAYLOAD MAPPING
    // ══════════════════════════════════════════════

    /**
     * Maps old "aw2:soldier", "aw2:archer" etc. to EntitySoldier roles.
     */
    private static String mapLegacyPayloadToRole(String payloadId, String fallbackRole) {
        if (payloadId.contains("archer") || payloadId.contains("ranged")) return "RANGED";
        if (payloadId.contains("elite") || payloadId.contains("heavy"))   return "HEAVY";
        if (payloadId.contains("leader") || payloadId.contains("officer")) return "SPECIAL";
        if (payloadId.contains("cavalry") || payloadId.contains("mount")) return "CAVALRY";
        if (payloadId.contains("soldier") || payloadId.contains("melee")) return "MELEE";
        return fallbackRole != null ? fallbackRole : "MELEE";
    }

    // ══════════════════════════════════════════════
    //  BATTLE AGGRO
    // ══════════════════════════════════════════════

    /**
     * Forces spawned entities to immediately engage the target player.
     */
    private static void applyBattleAggro(Entity entity, EntityPlayer target, BlockPos battleSite) {
        if (!(entity instanceof EntityLivingBase)) return;
        EntityLivingBase living = (EntityLivingBase) entity;

        if (living instanceof EntityLiving) {
            EntityLiving el = (EntityLiving) living;

            try { el.setAttackTarget(target); } catch (Throwable ignored) {}
            try { el.setRevengeTarget(target); } catch (Throwable ignored) {}

            if (battleSite != null && el instanceof EntityCreature) {
                try { ((EntityCreature) el).setHomePosAndDistance(battleSite, 96); } catch (Throwable ignored) {}
            }

            // Nudge navigation toward player
            try { el.getNavigator().tryMoveToEntityLiving(target, 1.1D); } catch (Throwable ignored) {}
        }
    }
}
