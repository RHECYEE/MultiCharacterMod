package studio.ERM.war.handlers;

import net.minecraft.entity.item.EntityItem;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.util.ResourceLocation;
import net.minecraft.util.math.AxisAlignedBB;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import net.minecraftforge.fml.common.gameevent.TickEvent;
import net.minecraftforge.fml.common.registry.ForgeRegistries;
import studio.ERM.EpochRunnerMod;
import studio.ERM.war.world.WarWorldData;
import studio.ERM.war.config.WarRivalCityDistrictsConfig;
import studio.ERM.war.districts.DistrictType;
import studio.ERM.war.districts.TileEntityDistrictMarker;
import studio.ERM.war.entities.EntityModernCitizen;
import studio.ERM.war.items.ItemExpertise;

import java.util.*;

@Mod.EventBusSubscriber(modid = EpochRunnerMod.MODID)
public class DistrictRewardHandler {

    private static final int REWARD_INTERVAL_TICKS = 12000; // 10 minutes
    private static int tickCounter = 0;
    private static Random rand = new Random();

    // Default reward lists
    private static final String[] DEFAULT_PAMS_FOOD = {"harvestcraft:garlicchickenitem", "harvestcraft:epicbltitem", "harvestcraft:supremepizzaitem"};
    private static final String[] RAW_ORES = {"minecraft:iron_ore", "minecraft:gold_ore", "minecraft:coal_ore", "minecraft:diamond_ore"};
    private static final String[] WOOD_TYPES = {"minecraft:log", "minecraft:log2"};

    @SubscribeEvent
    public static void onWorldTick(TickEvent.WorldTickEvent event) {
        if (event.phase != TickEvent.Phase.END || event.world.isRemote) return;

        tickCounter++;
        if (tickCounter < REWARD_INTERVAL_TICKS) return;
        tickCounter = 0;

        WarWorldData data = WarWorldData.get(event.world);
        for (TileEntity te : event.world.loadedTileEntityList) {
            if (te instanceof TileEntityDistrictMarker) {
                processDistrict(event.world, (TileEntityDistrictMarker) te, data);
            }
        }
    }

    private static void processDistrict(World world, TileEntityDistrictMarker marker, WarWorldData data) {
        // Use getCanonical() to ensure BUILDER logic runs even if the type is set to RESOURCE
        DistrictType type = marker.getDistrictType().getCanonical();
        int workerCount = countWorkersInDistrict(world, marker.getPos(), 32, type);

        if (workerCount == 0) return;

        switch (type) {
            case POWER:
                processPowerDistrict(world, marker.getPos(), workerCount, data);
                break;
            case AGRICULTURE: // Canonical for AGRI
                processAgriDistrict(world, marker.getPos(), workerCount);
                break;
            case RESOURCE: // Canonical for BUILDER
                processResourceDistrict(world, marker.getPos(), workerCount);
                break;
            case DEFENSE: // Canonical for MILITARY
                processMilitaryDistrict(world, marker.getPos(), workerCount, data);
                break;
            default:
                break;
        }
    }

    private static int countWorkersInDistrict(World world, BlockPos center, int radius, DistrictType districtType) {
        AxisAlignedBB area = new AxisAlignedBB(center).grow(radius);
        List<EntityModernCitizen> citizens = world.getEntitiesWithinAABB(EntityModernCitizen.class, area);
        int count = 0;

        for (EntityModernCitizen citizen : citizens) {
            // NPC matches if their job (canonical) matches the district type
            if (citizen.getCurrentJob().getCanonical() == districtType) {
                count++;
            }
        }
        return count;
    }

    private static void processPowerDistrict(World world, BlockPos pos, int workers, WarWorldData data) {
        spawnExpertise(world, pos, workers, DistrictType.POWER);
        data.getStats("PLAYER").powerStaff = workers;
    }

    private static void processAgriDistrict(World world, BlockPos pos, int workers) {
        spawnExpertise(world, pos, workers, DistrictType.AGRICULTURE);
        int foodCount = Math.min(workers * 2, 10);
        for (int i = 0; i < foodCount; i++) {
            ItemStack food = getItemStackFromId(DEFAULT_PAMS_FOOD[rand.nextInt(DEFAULT_PAMS_FOOD.length)]);
            if (!food.isEmpty()) spawnItemNearPos(world, pos, food);
        }
    }

    private static void processResourceDistrict(World world, BlockPos pos, int workers) {
        spawnExpertise(world, pos, workers, DistrictType.RESOURCE);
        for (int i = 0; i < workers * 2; i++) {
            ItemStack ore = getItemStackFromId(RAW_ORES[rand.nextInt(RAW_ORES.length)]);
            if (!ore.isEmpty()) spawnItemNearPos(world, pos, ore);
        }
    }

    private static void processMilitaryDistrict(World world, BlockPos pos, int workers, WarWorldData data) {
        spawnExpertise(world, pos, workers, DistrictType.DEFENSE);
        WarWorldData.FactionStats stats = data.getStats("PLAYER");
        stats.commandPoints += (int)(WarRivalCityDistrictsConfig.get().districts.cpProductionBase * (1.0f + workers * 0.1));
        stats.militaryStaff = workers;
    }

    private static void spawnExpertise(World world, BlockPos pos, int workers, DistrictType type) {
        for (int i = 0; i < workers; i++) {
            ItemStack stack = ItemExpertise.createFromDistrict(type);
            if (!stack.isEmpty()) spawnItemNearPos(world, pos, stack);
        }
    }

    private static void spawnItemNearPos(World world, BlockPos center, ItemStack stack) {
        double x = center.getX() + 0.5 + (rand.nextDouble() - 0.5) * 4;
        double z = center.getZ() + 0.5 + (rand.nextDouble() - 0.5) * 4;
        EntityItem entity = new EntityItem(world, x, center.getY() + 1, z, stack);
        entity.setDefaultPickupDelay();
        world.spawnEntity(entity);
    }

    private static ItemStack getItemStackFromId(String id) {
        Item item = ForgeRegistries.ITEMS.getValue(new ResourceLocation(id));
        return item != null ? new ItemStack(item) : ItemStack.EMPTY;
    }
}