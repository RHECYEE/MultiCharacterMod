package studio.ERM.war.rival;

import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;
import studio.ERM.EpochRunnerMod;
import studio.ERM.war.vehicle.EntityAIPilot;

import java.util.List;
import java.util.Random;

/**
 * Spawns and manages patrol squads and garrison vehicles for rival cities.
 *
 * Patrol squads: groups of EntityAIPilot on foot, with weapons from config
 * Garrison vehicles: EntityAIPilot in Flans vehicles, triggered at higher levels
 *
 * Both use the per-level weapon/vehicle arrays from RivalCityConfig.
 */
public final class RivalCityPatrolManager {

    private static final Random rand = new Random();
    private static final String EMPIRE_TEAM = "empire";

    private RivalCityPatrolManager() {}

    /**
     * Spawn/refresh patrol squads and garrison vehicles for a city.
     * Called on spawn, grow, and level change.
     */
    public static void spawnPatrols(World world, RivalCityData city) {
        if (world == null || world.isRemote || city == null || city.getCenter() == null) return;

        int level = city.getLevel();
        BlockPos center = city.getCenter();

        // ── Patrol Squads (foot patrols with weapons) ──
        int patrolSquads = getPatrolSquadCount(level);
        if (patrolSquads > 0) {
            spawnPatrolSquads(world, city, patrolSquads, level);
        }

        // ── Garrison Vehicles (Flans vehicles with pilots) ──
        int garrisonCount = getGarrisonCount(level);
        if (garrisonCount > 0) {
            spawnGarrisonVehicles(world, city, garrisonCount, level);
        }
    }

    // ════════════════════════════════════════════════════
    //  PATROL SQUADS
    // ════════════════════════════════════════════════════

    private static void spawnPatrolSquads(World world, RivalCityData city, int squads, int level) {
        BlockPos center = city.getCenter();
        int radius = Math.max(30, city.getRadius() - 20);
        String[] weapons = RivalCityConfig.getPatrolWeaponsForLevel(level);

        int spawned = 0;
        for (int s = 0; s < squads; s++) {
            int squadSize = RivalCityConfig.PATROL_SQUAD_SIZE;

            // Pick a random point within city radius for patrol origin
            double angle = rand.nextDouble() * 2 * Math.PI;
            int dist = 15 + rand.nextInt(Math.max(1, radius));
            int px = center.getX() + (int)(Math.cos(angle) * dist);
            int pz = center.getZ() + (int)(Math.sin(angle) * dist);
            BlockPos top = world.getTopSolidOrLiquidBlock(new BlockPos(px, 64, pz));
            int py = Math.max(55, top.getY());

            for (int i = 0; i < squadSize; i++) {
                try {
                    EntityAIPilot pilot = new EntityAIPilot(world);
                    double spawnX = px + (i % 3) * 2.0 + 0.5;
                    double spawnZ = pz + (i / 3) * 2.0 + 0.5;
                    pilot.setPosition(spawnX, py + 1, spawnZ);
                    pilot.setMcmTeam(EMPIRE_TEAM);

                    // Equip weapon from level config
                    if (weapons != null && weapons.length > 0) {
                        String weaponId = weapons[rand.nextInt(weapons.length)].trim();
                        Item item = Item.getByNameOrId(weaponId);
                        if (item != null) {
                            pilot.setItemStackToSlot(net.minecraft.inventory.EntityEquipmentSlot.MAINHAND, new ItemStack(item));
                        }
                    }

                    pilot.enablePersistence();
                    world.spawnEntity(pilot);
                    spawned++;
                } catch (Throwable t) {
                    EpochRunnerMod.logger.debug("[RivalPatrol] Failed to spawn pilot: {}", t.getMessage());
                }
            }
        }

        if (spawned > 0) {
            EpochRunnerMod.logger.info("[RivalPatrol] Spawned {} patrol pilots ({} squads) at level {}",
                    spawned, squads, level);
        }
    }

    // ════════════════════════════════════════════════════
    //  GARRISON VEHICLES
    // ════════════════════════════════════════════════════

    private static void spawnGarrisonVehicles(World world, RivalCityData city, int count, int level) {
        BlockPos center = city.getCenter();
        int radius = Math.max(30, city.getRadius() - 10);
        String[] vehicles = RivalCityConfig.getGarrisonVehiclesForLevel(level);

        if (vehicles == null || vehicles.length == 0) return;

        int spawned = 0;
        for (int v = 0; v < count; v++) {
            try {
                String vehicleShortName = vehicles[rand.nextInt(vehicles.length)].trim();

                // Pick spawn point on road
                double angle = rand.nextDouble() * 2 * Math.PI;
                int dist = 10 + rand.nextInt(Math.max(1, radius));
                int vx = center.getX() + (int)(Math.cos(angle) * dist);
                int vz = center.getZ() + (int)(Math.sin(angle) * dist);
                BlockPos top = world.getTopSolidOrLiquidBlock(new BlockPos(vx, 64, vz));
                int vy = Math.max(55, top.getY());

                EntityAIPilot pilot = new EntityAIPilot(world);
                pilot.setPosition(vx + 0.5, vy + 1, vz + 0.5);
                pilot.setMcmTeam(EMPIRE_TEAM);
                pilot.setVehicleType(vehicleShortName);

                // Equip the pilot with a weapon too
                String[] weapons = RivalCityConfig.getPatrolWeaponsForLevel(level);
                if (weapons != null && weapons.length > 0) {
                    String weaponId = weapons[rand.nextInt(weapons.length)].trim();
                    Item item = Item.getByNameOrId(weaponId);
                    if (item != null) {
                        pilot.setItemStackToSlot(net.minecraft.inventory.EntityEquipmentSlot.MAINHAND, new ItemStack(item));
                    }
                }

                pilot.enablePersistence();
                world.spawnEntity(pilot);
                spawned++;
            } catch (Throwable t) {
                EpochRunnerMod.logger.debug("[RivalPatrol] Failed to spawn garrison vehicle: {}", t.getMessage());
            }
        }

        if (spawned > 0) {
            EpochRunnerMod.logger.info("[RivalPatrol] Spawned {} garrison vehicles at level {}", spawned, level);
        }
    }

    // ════════════════════════════════════════════════════
    //  SCALING
    // ════════════════════════════════════════════════════

    /** Patrol squads scale with level. 0 at level 1, then increasing. */
    private static int getPatrolSquadCount(int level) {
        if (level <= 1) return 0;
        if (level <= 3) return 1;
        if (level <= 5) return 2;
        if (level <= 7) return 3;
        if (level <= 9) return 4;
        return 5;
    }

    /** Garrison vehicles only appear at higher levels. */
    private static int getGarrisonCount(int level) {
        if (level < RivalCityConfig.GARRISON_START_LEVEL) return 0;
        return Math.min(level - RivalCityConfig.GARRISON_START_LEVEL + 1, 6);
    }
}
