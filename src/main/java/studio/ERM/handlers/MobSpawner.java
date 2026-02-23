package studio.ERM.handlers;

import net.minecraft.command.ICommandSender;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityList;
import net.minecraft.entity.EntityLiving;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.server.MinecraftServer;
import net.minecraft.util.ResourceLocation;
import net.minecraft.util.math.AxisAlignedBB;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;
import net.minecraft.world.WorldServer;
import studio.ERM.EpochRunnerMod;
import java.util.List;
import java.util.Random;

/**
 * Handles the spawning of entities via direct creation or command-line execution.
 */
public class MobSpawner {

    /**
     * Centralized spawn method used by WarRaidSpawner and WarRefugeeManager.
     * Resolves the "cannot find symbol" error by providing the expected signature.
     */
    public static EntityLiving spawnMob(World world, BlockPos pos, String entityIdentifier) {
        if (!(world instanceof WorldServer)) return null;
        WorldServer worldServer = (WorldServer) world;

        // Check if the identifier is a full command/NBT string or just an ID
        if (entityIdentifier.contains("{") || entityIdentifier.contains(" ")) {
            executeSummon(worldServer, pos, entityIdentifier, world.getClosestPlayer(pos.getX(), pos.getY(), pos.getZ(), 64, false));
            // Note: executeSummon returns void, so we fetch the entity from the world if needed
            return null;
        } else {
            // Direct spawning for simple Entity IDs (e.g., ancientwarfare:ogg_refugee)
            Entity entity = EntityList.createEntityByIDFromName(new ResourceLocation(entityIdentifier), world);
            if (entity instanceof EntityLiving) {
                EntityLiving living = (EntityLiving) entity;
                living.setLocationAndAngles(pos.getX() + 0.5, pos.getY(), pos.getZ() + 0.5, 0, 0);
                world.spawnEntity(living);
                return living;
            }
        }
        return null;
    }

    /**
     * Summons a mob using the raw /summon command structure and tries to aggro it.
     */
    public static void executeSummon(WorldServer world, BlockPos pos, String commandNBT, EntityPlayer targetPlayer) {
        if (commandNBT == null || commandNBT.isEmpty()) {
            EpochRunnerMod.logger.warn("Received empty summon command NBT.");
            return;
        }

        String summonCommand = commandNBT;
        String x = String.valueOf(pos.getX());
        String y = String.valueOf(pos.getY());
        String z = String.valueOf(pos.getZ());

        summonCommand = summonCommand.replaceFirst("~", x).replaceFirst("~", y).replaceFirst("~", z);

        String persistenceTag = "PersistenceRequired:1b";

        if (!summonCommand.contains("PersistenceRequired")) {
            int nbtStart = summonCommand.indexOf('{');
            if (nbtStart != -1) {
                String start = summonCommand.substring(0, nbtStart + 1);
                String end = summonCommand.substring(nbtStart + 1);
                summonCommand = end.length() > 1 ? start + persistenceTag + "," + end : start + persistenceTag + end;
            } else {
                summonCommand += " {" + persistenceTag + "}";
            }
        }

        String command = "/summon " + summonCommand;

        try {
            MinecraftServer server = world.getMinecraftServer();
            ICommandSender serverSender = server;

            AxisAlignedBB spawnArea = new AxisAlignedBB(pos).grow(1.5D, 1.5D, 1.5D);
            List<Entity> entitiesBefore = world.getEntitiesWithinAABB(Entity.class, spawnArea, null);

            server.commandManager.executeCommand(serverSender, command);
            EpochRunnerMod.logger.info("MOB_SPAWN: Executing summon command: " + command);

            List<Entity> entitiesAfter = world.getEntitiesWithinAABB(Entity.class, spawnArea, null);

            int mobsAggroed = 0;
            for (Entity entity : entitiesAfter) {
                if (!entitiesBefore.contains(entity) && entity instanceof EntityLiving) {
                    EntityLiving mob = (EntityLiving) entity;
                    if (targetPlayer != null) {
                        mob.setAttackTarget(targetPlayer);
                        mob.faceEntity(targetPlayer, 30.0F, 30.0F);
                        if (mob.getNavigator() != null) {
                            mob.getNavigator().tryMoveToEntityLiving(targetPlayer, 1.0D);
                        }
                        mob.enablePersistence();
                        mobsAggroed++;
                    }
                }
            }
            EpochRunnerMod.logger.info("MOB_SPAWN: Aggroed " + mobsAggroed + " new entities.");

        } catch (Exception e) {
            EpochRunnerMod.logger.error("Failed to execute summon command: " + command, e);
        }
    }

    /**
     * Spawns a list of mobs defined by their raw command NBT strings.
     */
    public static void spawnWave(WorldServer world, EntityPlayer targetPlayer, List<String> mobNBTCommands, int spawnRadius, float multiplier) {
        Random random = new Random();
        BlockPos centerPos = targetPlayer.getPosition(); // Simplified fallback

        for (String commandNBT : mobNBTCommands) {
            int spawnCount = Math.max(1, (int) (1 * multiplier));
            for (int i = 0; i < spawnCount; i++) {
                int dx = random.nextInt(spawnRadius * 2) - spawnRadius;
                int dz = random.nextInt(spawnRadius * 2) - spawnRadius;
                BlockPos spawnPos = world.getTopSolidOrLiquidBlock(centerPos.add(dx, 5, dz));
                executeSummon(world, spawnPos, commandNBT, targetPlayer);
            }
        }
    }
}