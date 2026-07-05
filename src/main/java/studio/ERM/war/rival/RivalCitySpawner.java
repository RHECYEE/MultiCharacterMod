package studio.ERM.war.rival;

import studio.ERM.war.vehicle.EntityAIPilot;
import com.flansmod.common.driveables.DriveableType;
import com.flansmod.common.driveables.VehicleType;
import com.flansmod.common.types.InfoType;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityList;
import net.minecraft.init.Items;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.util.EnumFacing;
import net.minecraft.util.EnumHand;
import net.minecraft.util.ResourceLocation;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;
import net.minecraftforge.fml.common.Loader;
import net.minecraftforge.fml.common.registry.ForgeRegistries;
import studio.ERM.EpochRunnerMod;
// Note: "RivalCityConfig" unqualified refers to studio.ERM.war.rival.RivalCityConfig (local).
// For external flans vehicle config, we use the fully qualified name below.
import studio.ERM.war.rival.ProceduralBuildingGenerator;
import studio.ERM.war.rival.RivalFactionStats;

import java.lang.reflect.Method;
import java.util.*;

/**
 * RivalCitySpawner - Handles all entity spawning for the rival city system.
 * 
 * Includes:
 * - NPC spawning (AW2 and modern guards)
 * - Vehicle spawning (Flan's mod)
 * - Siege machine spawning
 * - Weapon tier assignment
 */
public class RivalCitySpawner {

    // =====================================================================
    // WEAPON TIERS (levels 1-10)
    // =====================================================================

    public static final String[][] WEAPON_TIERS = {
            {"minecraft:wooden_sword"},
            {"minecraft:stone_sword"},
            {"minecraft:iron_sword"},
            {"minecraft:iron_sword", "minecraft:bow"},
            {"minecraft:diamond_sword", "minecraft:bow"},
            {"flansmod:p38", "minecraft:diamond_sword"},
            {"flansmod:m1garand"},
            {"flansmod:thompson", "flansmod:m1garand"},
            {"flansmod:ak47", "flansmod:m16"},
            {"flansmod:m4a1", "flansmod:ak74"}
    };

    private static final Random rand = new Random();

    // =====================================================================
    // CACHED ENTITY IDS
    // =====================================================================

    private static List<ResourceLocation> cachedSiegeEntityIds = null;
    private static long cachedSiegeEntityIdsAt = 0;

    private static List<ResourceLocation> cachedSiegeEngineerIds = null;
    private static long cachedSiegeEngineerIdsAt = 0;

    // =====================================================================
    // NPC SPAWNING
    // =====================================================================

    /**
     * Spawn rival NPCs based on city state and stats.
     */
    public static void spawnRivalNPCs(World world, RivalCityState state) {
        if (state.center == null) return;
        if (!world.isBlockLoaded(state.center, false)) return;

        int maxFromStats = state.stats.getMaxBattleNPCs();
        int baseCount = Math.min(28, (RivalCityConfig.npcsPerLevel * state.level) + 4);
        int spawnCount = Math.min(baseCount, maxFromStats);

        for (int i = 0; i < spawnCount; i++) {
            RivalCityState.GarrisonAnchor anchor = null;
            if (!state.garrisonAnchors.isEmpty() && rand.nextFloat() < 0.65f) {
                anchor = state.garrisonAnchors.get(rand.nextInt(state.garrisonAnchors.size()));
            }
            
            BlockPos base;
            if (anchor != null) {
                BlockPos aWorld = RivalCityGenerator.gridCellToWorld(state, anchor.gx, anchor.gz);
                int jitter = Math.max(4, state.gridSpacing / 2);
                int offsetX = rand.nextInt(jitter * 2 + 1) - jitter;
                int offsetZ = rand.nextInt(jitter * 2 + 1) - jitter;
                base = aWorld.add(offsetX, 0, offsetZ);
            } else {
                int offsetX = rand.nextInt(Math.max(8, state.size)) - state.size / 2;
                int offsetZ = rand.nextInt(Math.max(8, state.size)) - state.size / 2;
                base = state.center.add(offsetX, 0, offsetZ);
            }
            if (!world.isBlockLoaded(base, false)) continue;

            BlockPos spawnPos = world.getTopSolidOrLiquidBlock(base).up();

            // Level-based NPC type selection
            if (state.level <= 5) {
                spawnUnifiedFactionGuard(world, spawnPos, state, i == 0);
            } else if (state.level <= 7) {
                if (rand.nextFloat() < 0.50f) spawnModernGuard(world, spawnPos, state.level);
                else spawnUnifiedFactionGuard(world, spawnPos, state, i == 0);
            } else {
                if (rand.nextFloat() < 0.80f) spawnModernGuard(world, spawnPos, state.level);
                else spawnUnifiedFactionGuard(world, spawnPos, state, i == 0);
            }
        }

        // Siege machines for levels 3-6
        try {
            if (state.level >= 3 && state.level <= 6) {
                spawnRivalSiegeMachines(world, state);
            }
        } catch (Throwable t) {
            EpochRunnerMod.logger.warn("[RIVAL] Siege spawn hook error: " + t.getMessage());
        }

        // Consume population for the spawn
        state.stats.consumeForBattle(spawnCount / 4, 0, false);
    }

    /**
     * Spawn AW2 NPC with unified faction.
     */
    public static void spawnUnifiedFactionGuard(World world, BlockPos pos, RivalCityState state, boolean isLeader) {
        try {
            ResourceLocation loc = new ResourceLocation("ancientwarfarenpc",
                    isLeader ? "faction.leader" : "faction.soldier");
            Entity npc = EntityList.createEntityByIDFromName(loc, world);

            if (npc != null) {
                npc.setPosition(pos.getX() + 0.5, pos.getY(), pos.getZ() + 0.5);
                setUnifiedFaction(npc);

                if (!isLeader) {
                    ItemStack weapon = getWeaponForLevel(state.level);
                    setNPCWeapon(npc, weapon);
                }

                world.spawnEntity(npc);
                return;
            }
        } catch (Throwable t) {
            EpochRunnerMod.logger.warn("[RIVAL] AW2 NPC spawn failed: " + t.getMessage());
        }

        // Fallback to EntityAIPilot
        EntityAIPilot soldier = new EntityAIPilot(world);
        soldier.setPosition(pos.getX() + 0.5, pos.getY(), pos.getZ() + 0.5);
        soldier.setMcmTeam(RivalCityState.RIVAL_FACTION_NAME);
        soldier.setHeldItem(EnumHand.MAIN_HAND, getWeaponForLevel(state.level));
        world.spawnEntity(soldier);
    }

    /**
     * Spawn modern guard (EntityAIPilot with guns).
     */
    public static void spawnModernGuard(World world, BlockPos pos, int cityLevel) {
        EntityAIPilot soldier = new EntityAIPilot(world);
        soldier.setPosition(pos.getX() + 0.5, pos.getY(), pos.getZ() + 0.5);
        soldier.setMcmTeam(RivalCityState.RIVAL_FACTION_NAME);

        int tier = Math.min(cityLevel - 1, WEAPON_TIERS.length - 1);
        String[] tierList = WEAPON_TIERS[tier];
        if (tierList.length > 0) {
            soldier.setHeldItem(EnumHand.MAIN_HAND, getWeaponStack(tierList[rand.nextInt(tierList.length)]));
        }

        world.spawnEntity(soldier);
    }

    /**
     * Set unified faction on AW2 NPC.
     */
    public static void setUnifiedFaction(Entity npc) {
        if (npc == null) return;

        String[] methods = {
                "setFactionNameAndDefaults", "setFactionName", "setFaction",
                "setFactionId", "setFactionNameAndReset"
        };

        for (String m : methods) {
            try {
                Method mm = npc.getClass().getMethod(m, String.class);
                mm.invoke(npc, RivalCityState.RIVAL_AW2_FACTION);
                return;
            } catch (Throwable ignored) {}
        }

        // Fallback to NBT
        try {
            npc.getEntityData().setString("faction", RivalCityState.RIVAL_AW2_FACTION);
            npc.getEntityData().setString("factionName", RivalCityState.RIVAL_AW2_FACTION);
        } catch (Throwable ignored) {}
    }

    public static void setNPCWeapon(Entity npc, ItemStack weapon) {
        try {
            Method setHeld = npc.getClass().getMethod("setHeldItem", EnumHand.class, ItemStack.class);
            setHeld.invoke(npc, EnumHand.MAIN_HAND, weapon);
        } catch (Throwable ignored) {
            try {
                Method setSlot = npc.getClass().getMethod("setItemStackToSlot",
                        net.minecraft.inventory.EntityEquipmentSlot.class, ItemStack.class);
                setSlot.invoke(npc, net.minecraft.inventory.EntityEquipmentSlot.MAINHAND, weapon);
            } catch (Throwable ignored2) {}
        }
    }

    public static ItemStack getWeaponForLevel(int level) {
        int tier = Math.max(0, Math.min(level - 1, WEAPON_TIERS.length - 1));
        String[] tierList = WEAPON_TIERS[tier];
        if (tierList.length == 0) return new ItemStack(Items.IRON_SWORD);

        String weaponId = tierList[rand.nextInt(tierList.length)];
        return getWeaponStack(weaponId);
    }

    public static ItemStack getWeaponStack(String id) {
        try {
            Item item = ForgeRegistries.ITEMS.getValue(new ResourceLocation(id));
            if (item != null) return new ItemStack(item);
        } catch (Throwable ignored) {}
        return new ItemStack(Items.IRON_SWORD);
    }

    // =====================================================================
    // SIEGE MACHINE SPAWNING
    // =====================================================================

    public static List<ResourceLocation> getSiegeEntityIds() {
        long now = System.currentTimeMillis();
        if (cachedSiegeEntityIds != null && (now - cachedSiegeEntityIdsAt) < 5L * 60L * 1000L) {
            return cachedSiegeEntityIds;
        }

        List<ResourceLocation> found = new ArrayList<>();
        try {
            for (net.minecraftforge.fml.common.registry.EntityEntry entry : ForgeRegistries.ENTITIES.getValuesCollection()) {
                ResourceLocation key = entry.getRegistryName();
                if (key == null) continue;

                String dom = key.getNamespace().toLowerCase(Locale.ROOT);
                String path = key.getPath().toLowerCase(Locale.ROOT);

                boolean aw2 = dom.contains("ancientwarfare");
                boolean siegey = path.contains("siege") || path.contains("ballista") ||
                        path.contains("catapult") || path.contains("trebuchet") ||
                        path.contains("battering") || path.contains("ram");

                if (aw2 && siegey) {
                    found.add(key);
                }
            }
        } catch (Throwable ignored) {}

        cachedSiegeEntityIds = found;
        cachedSiegeEntityIdsAt = now;
        return cachedSiegeEntityIds;
    }

    public static List<ResourceLocation> getSiegeEngineerEntityIds() {
        long now = System.currentTimeMillis();
        if (cachedSiegeEngineerIds != null && (now - cachedSiegeEngineerIdsAt) < 60_000L) {
            return cachedSiegeEngineerIds;
        }

        List<ResourceLocation> found = new ArrayList<>();
        try {
            for (ResourceLocation key : EntityList.getEntityNameList()) {
                if (key == null) continue;
                String dom = key.getNamespace().toLowerCase(Locale.ROOT);
                String path = key.getPath().toLowerCase(Locale.ROOT);

                boolean aw2 = dom.contains("ancientwarfare");
                boolean engineer = path.contains("engineer") || path.contains("sapper") || path.contains("breach");
                boolean siege = path.contains("siege") || path.contains("ram") || path.contains("mortar") || path.contains("trebuchet");

                if (aw2 && engineer && (siege || path.contains("siege_engineer"))) {
                    found.add(key);
                }
            }
        } catch (Throwable ignored) {}

        cachedSiegeEngineerIds = found;
        cachedSiegeEngineerIdsAt = now;
        return cachedSiegeEngineerIds;
    }

    public static void spawnRivalSiegeMachines(World world, RivalCityState state) {
        if (state.center == null) return;
        if (!world.isBlockLoaded(state.center, false)) return;

        List<ResourceLocation> siegeIds = getSiegeEntityIds();
        if (siegeIds == null || siegeIds.isEmpty()) return;

        int maxFromIndustry = Math.max(1, state.stats.getIndustryTier());
        int count = Math.max(1, Math.min(2 + (state.level / 3), 4));

        count = Math.min(count, Math.max(1, maxFromIndustry));
        if (count <= 0) return;
        int spawned = 0;

        for (int i = 0; i < count; i++) {
            try {
                int offsetX = rand.nextInt(Math.max(8, state.size / 2)) - (state.size / 4);
                int offsetZ = rand.nextInt(Math.max(8, state.size / 2)) - (state.size / 4);

                BlockPos base = state.center.add(offsetX, 0, offsetZ);
                if (!world.isBlockLoaded(base, false)) continue;

                BlockPos pos = world.getTopSolidOrLiquidBlock(base).up();
                ResourceLocation pick = siegeIds.get(rand.nextInt(siegeIds.size()));

                Entity siege = EntityList.createEntityByIDFromName(pick, world);
                if (siege == null) continue;

                siege.setPosition(pos.getX() + 0.5, pos.getY(), pos.getZ() + 0.5);
                setUnifiedFaction(siege);

                if (world.spawnEntity(siege)) {
                    spawned++;
                    // Crew for siege
                    if (rand.nextFloat() < 0.90f) {
                        try {
                            List<ResourceLocation> engineers = getSiegeEngineerEntityIds();
                            Entity crew = null;

                            if (engineers != null && !engineers.isEmpty()) {
                                ResourceLocation pickEng = engineers.get(rand.nextInt(engineers.size()));
                                crew = EntityList.createEntityByIDFromName(pickEng, world);
                            }

                            if (crew == null) {
                                spawnUnifiedFactionGuard(world, pos.add(2, 0, 0), state, false);
                            } else {
                                crew.setPosition(pos.getX() + 1.5, pos.getY(), pos.getZ() + 0.5);
                                setUnifiedFaction(crew);
                                world.spawnEntity(crew);
                            }
                        } catch (Throwable ignored) {
                            spawnUnifiedFactionGuard(world, pos.add(2, 0, 0), state, false);
                        }
                    }
                }
            } catch (Throwable t) {
                EpochRunnerMod.logger.warn("[RIVAL] Siege spawn error: " + t.getMessage());
            }
        }

        EpochRunnerMod.logger.info("[RIVAL] Spawned " + spawned + "/" + count + " siege machines");
    }

    // =====================================================================
    // VEHICLE SPAWNING (FLAN'S MOD)
    // =====================================================================

    public static int getBoostedFlansVehicleCountForLevel(int level, int baseCount) {
        if (baseCount <= 0) return 0;
        if (level < 6) return baseCount;

        float mult = 1.25f + (Math.min(10, level) - 6) * 0.25f;
        int boosted = Math.round(baseCount * mult);

        return Math.min(24, Math.max(baseCount, boosted));
    }

    public static void spawnRivalFlansVehicles(World world, RivalCityState state, int count) {
        if (count <= 0) return;
        if (state.center == null) return;
        if (!world.isBlockLoaded(state.center, false)) return;

        String list = getConfiguredFlansListForLevel(state.level);
        if (list == null || list.trim().isEmpty()) return;

        String[] options = list.split(",");
        if (options.length == 0) return;

        int spawned = 0;
        int attempts = 0;

        while (spawned < count && attempts < count * 10) {
            attempts++;

            String shortName = options[rand.nextInt(options.length)].trim();
            if (shortName.isEmpty()) continue;

            shortName = shortName.toLowerCase(Locale.ROOT).replace("flansmod:", "").trim();

            if (studio.ERM.config.RivalCityConfig.validateFlansTypeBeforeSpawn && !isValidFlansVehicleTypeSafe(shortName)) {
                continue;
            }

            BlockPos pos = pickVehicleSpawnPos(world, state);
            if (pos == null) continue;

            spawnFlansVehiclePilot(world, pos, shortName);
            spawned++;
        }
    }

    public static String getConfiguredFlansListForLevel(int level) {
        if (studio.ERM.config.RivalCityConfig.flansVehiclesByLevel == null || studio.ERM.config.RivalCityConfig.flansVehiclesByLevel.length == 0)
            return null;
        int idx = Math.max(1, Math.min(level, studio.ERM.config.RivalCityConfig.flansVehiclesByLevel.length));
        return studio.ERM.config.RivalCityConfig.flansVehiclesByLevel[idx - 1];
    }

    public static boolean isValidFlansVehicleTypeSafe(String shortName) {
        if (!Loader.isModLoaded("flansmod")) return false;
        try {
            InfoType t = InfoType.getType(shortName);
            if (t == null && InfoType.infoTypes != null) {
                for (InfoType it : InfoType.infoTypes.values()) {
                    if (it != null && it.shortName != null && it.shortName.equalsIgnoreCase(shortName)) {
                        t = it;
                        break;
                    }
                }
            }
            if (t == null) return false;
            if (!(t instanceof DriveableType)) return false;
            return (t instanceof VehicleType);
        } catch (Throwable ignored) {
            return false;
        }
    }

    public static BlockPos pickVehicleSpawnPos(World world, RivalCityState state) {
        int tries = 14;
        int minR = Math.max(12, state.size / 4);
        int maxR = Math.max(minR + 8, state.size / 2);

        while (tries-- > 0) {
            double a = rand.nextDouble() * Math.PI * 2.0;
            int r = minR + rand.nextInt(Math.max(1, maxR - minR));
            BlockPos raw = state.center.add((int) (Math.cos(a) * r), 0, (int) (Math.sin(a) * r));
            if (!world.isBlockLoaded(raw, false)) continue;
            return world.getTopSolidOrLiquidBlock(raw).up();
        }
        return null;
    }

    public static void spawnFlansVehiclePilot(World world, BlockPos pos, String vehicleShortName) {
        if (world == null || pos == null) return;

        boolean summoned = false;
        try {
            summoned = trySummonFlansVehicleByCommand(world, pos, vehicleShortName);
        } catch (Throwable ignored) {}

        EntityAIPilot pilot = new EntityAIPilot(world);
        pilot.setPosition(pos.getX() + 0.5, pos.getY(), pos.getZ() + 0.5);
        pilot.setMcmTeam(RivalCityState.RIVAL_FACTION_NAME);
        pilot.setVehicleTargetingEnabled(studio.ERM.config.RivalCityConfig.enableVehicleVsVehicle);

        if (!summoned) {
            pilot.setVehicleType(vehicleShortName);
        }

        world.spawnEntity(pilot);
    }

    public static boolean trySummonFlansVehicleByCommand(World world, BlockPos pos, String vehicleShortName) {
        if (world == null || pos == null) return false;
        if (world.isRemote) return false;
        if (!(world instanceof net.minecraft.world.WorldServer)) return false;

        net.minecraft.server.MinecraftServer server = world.getMinecraftServer();
        if (server == null || server.getCommandManager() == null) return false;

        final net.minecraft.util.math.BlockPos cmdPos = pos;
        final net.minecraft.world.World cmdWorld = world;

        net.minecraft.command.ICommandSender sender = new net.minecraft.command.ICommandSender() {
            @Override
            public String getName() { return "[ERM_RIVAL]"; }

            @Override
            public net.minecraft.util.text.ITextComponent getDisplayName() {
                return new net.minecraft.util.text.TextComponentString(getName());
            }

            @Override
            public void sendMessage(net.minecraft.util.text.ITextComponent component) {}

            @Override
            public boolean canUseCommand(int permLevel, String commandName) { return true; }

            @Override
            public net.minecraft.util.math.BlockPos getPosition() { return cmdPos; }

            @Override
            public net.minecraft.util.math.Vec3d getPositionVector() {
                return new net.minecraft.util.math.Vec3d(cmdPos.getX() + 0.5, cmdPos.getY() + 0.5, cmdPos.getZ() + 0.5);
            }

            @Override
            public net.minecraft.world.World getEntityWorld() { return cmdWorld; }

            @Override
            public net.minecraft.entity.Entity getCommandSenderEntity() { return null; }

            @Override
            public boolean sendCommandFeedback() { return false; }

            @Override
            public void setCommandStat(net.minecraft.command.CommandResultStats.Type type, int amount) {}

            @Override
            public net.minecraft.server.MinecraftServer getServer() { return server; }
        };

        String cmd = "summonvehicle " + vehicleShortName;
        try {
            int result = server.getCommandManager().executeCommand(sender, cmd);
            return result > 0;
        } catch (Throwable t) {
            return false;
        }
    }

    // =====================================================================
    // EXPANSION NODE BUILDING
    // =====================================================================

    /**
     * Generate a building at an expansion node position.
     */
    public static void generateBuildingAtExpansionNode(World world, RivalCityState state,
                                                        studio.ERM.war.rival.RivalExpansionManager.ExpansionNode node) {
        String type = node.structureType == null ? "" : node.structureType.toUpperCase();
        BlockPos pos = world.getTopSolidOrLiquidBlock(node.position);

        switch (type) {
            case "SKYSCRAPER":
                ProceduralBuildingGenerator.generateSkyscraper(world, pos, state.level);
                state.stats.onStructureBuilt(RivalFactionStats.StructureType.SKYSCRAPER);
                break;
            case "COOLING_TOWER":
                ProceduralBuildingGenerator.generateCoolingTower(world, pos, state.level);
                state.stats.onStructureBuilt(RivalFactionStats.StructureType.COOLING_TOWER);
                break;
            case "FACTORY":
                ProceduralBuildingGenerator.generateFactory(world, pos, state.level, node.facing);
                state.stats.onStructureBuilt(RivalFactionStats.StructureType.FACTORY);
                break;
            case "SILO":
            case "STORAGE":
            case "STORAGE_TANK":
                ProceduralBuildingGenerator.generateSiloCluster(world, pos, state.level);
                state.stats.onStructureBuilt(RivalFactionStats.StructureType.SILO_STORAGE);
                break;
            case "RADAR":
            case "RADAR_PAD":
            case "ANTENNA_FARM":
                ProceduralBuildingGenerator.generateStructure(world, pos, "RADAR", state.level, node.facing);
                state.stats.onStructureBuilt(RivalFactionStats.StructureType.ANTENNA_RADAR);
                break;
            case "BARRACKS":
                ProceduralBuildingGenerator.generateFort(world, pos, state.level);
                state.stats.onStructureBuilt(RivalFactionStats.StructureType.BARRACKS);
                break;
            default:
                ProceduralBuildingGenerator.generateFactory(world, pos, Math.max(1, state.level / 2), node.facing);
                state.stats.onStructureBuilt(RivalFactionStats.StructureType.FACTORY);
                break;
        }
    }
}
