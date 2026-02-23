package studio.ERM.war.handlers;

import net.minecraft.entity.Entity;
import net.minecraft.init.Items;
import net.minecraft.inventory.IInventory;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.tileentity.TileEntityChest;
import net.minecraft.util.ResourceLocation;
import net.minecraft.util.math.AxisAlignedBB;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import net.minecraftforge.fml.common.gameevent.TickEvent;
import net.minecraftforge.fml.common.registry.ForgeRegistries;
import studio.ERM.EpochRunnerMod;

import java.util.List;

/**
 * Vehicle Fuel Handler
 * 
 * Allows friendly vehicles to automatically retrieve fuel from nearby chests.
 * Fuel type is configurable (defaults to coal for easy mode).
 * 
 * Features:
 * - Configurable fuel item (via config)
 * - Configurable search radius
 * - Works with any chest/inventory
 * - Integration with AW2 and Flan's vehicles
 */
@Mod.EventBusSubscriber(modid = EpochRunnerMod.MODID)
public class VehicleFuelHandler {
    
    // Configurable values
    public static String fuelItemId = "minecraft:coal"; // Default to coal
    public static int searchRadius = 16; // Blocks
    public static int fuelCheckIntervalTicks = 100; // 5 seconds
    public static int fuelPerRefuel = 1; // Items taken per refuel
    public static float fuelEfficiency = 1.0f; // Multiplier for fuel duration
    
    private static int tickCounter = 0;
    
    @SubscribeEvent
    public static void onWorldTick(TickEvent.WorldTickEvent event) {
        if (event.phase != TickEvent.Phase.END) return;
        if (event.world.isRemote) return;
        
        tickCounter++;
        if (tickCounter < fuelCheckIntervalTicks) return;
        tickCounter = 0;
        
        World world = event.world;
        
        // Find all friendly vehicles that need fuel
        for (Entity entity : world.loadedEntityList) {
            if (isFriendlyVehicle(entity) && needsFuel(entity)) {
                attemptRefuel(world, entity);
            }
        }
    }
    
    /**
     * Checks if an entity is a friendly vehicle.
     * Friendly vehicles are those owned by players or converted via citizen-to-pilot.
     */
    private static boolean isFriendlyVehicle(Entity entity) {
        String className = entity.getClass().getName().toLowerCase();
        
        // Check for explicit friendlyVehicle flag (set by citizen-to-pilot conversion)
        if (entity.getEntityData().hasKey("friendlyVehicle") && 
            entity.getEntityData().getBoolean("friendlyVehicle")) {
            return true;
        }
        
        // Check for player team (set by citizen-to-pilot)
        if (entity.getEntityData().hasKey("mcmTeam")) {
            String team = entity.getEntityData().getString("mcmTeam");
            // Friendly if not ENEMY or EMPIRE (enemy faction)
            if (team != null && !team.equals("ENEMY") && !team.equals("EMPIRE")) {
                return true;
            }
        }
        
        // Check for Flan's vehicles
        if (className.contains("flansmod") && className.contains("vehicle")) {
            return isPlayerOwned(entity);
        }
        
        // Check for AW2 vehicles
        if (className.contains("ancientwarfare") && className.contains("vehicle")) {
            return isPlayerOwned(entity);
        }
        
        // Check for our custom vehicle entities
        if (className.contains("homosapien") && className.contains("vehicle")) {
            return true;
        }
        
        // Check for tank operators
        if (className.contains("tankoperator")) {
            return true;
        }
        
        return false;
    }
    
    /**
     * Checks if a vehicle is owned by a player (not enemy).
     */
    private static boolean isPlayerOwned(Entity entity) {
        // Check NBT for ownership
        if (entity.getEntityData().hasKey("ownerUUID")) {
            return true;
        }
        
        // Check for team/faction data
        if (entity.getEntityData().hasKey("team")) {
            String team = entity.getEntityData().getString("team");
            return "PLAYER".equals(team) || "player".equals(team);
        }
        
        // Default to true if no faction data
        return true;
    }
    
    /**
     * Checks if a vehicle needs fuel.
     */
    private static boolean needsFuel(Entity entity) {
        // Check Flan's fuel system
        try {
            if (entity.getClass().getName().contains("flansmod")) {
                // Use reflection to check fuel level
                java.lang.reflect.Field fuelField = findField(entity.getClass(), "fuel", "fuelInTank");
                if (fuelField != null) {
                    fuelField.setAccessible(true);
                    Object fuelValue = fuelField.get(entity);
                    if (fuelValue instanceof Number) {
                        return ((Number) fuelValue).floatValue() < 50.0f; // Refuel below 50%
                    }
                }
            }
        } catch (Exception e) {
            // Reflection failed, assume needs fuel
        }
        
        // Check custom fuel NBT
        if (entity.getEntityData().hasKey("fuel")) {
            return entity.getEntityData().getFloat("fuel") < 50.0f;
        }
        
        // No fuel system detected, doesn't need fuel
        return false;
    }
    
    /**
     * Attempts to refuel a vehicle from nearby chests.
     */
    private static void attemptRefuel(World world, Entity vehicle) {
        BlockPos vehiclePos = vehicle.getPosition();
        
        // Search for chests in radius
        AxisAlignedBB searchArea = new AxisAlignedBB(
            vehiclePos.getX() - searchRadius, vehiclePos.getY() - 3, vehiclePos.getZ() - searchRadius,
            vehiclePos.getX() + searchRadius, vehiclePos.getY() + 3, vehiclePos.getZ() + searchRadius
        );
        
        // Find all tile entities in range
        for (int x = -searchRadius; x <= searchRadius; x++) {
            for (int y = -3; y <= 3; y++) {
                for (int z = -searchRadius; z <= searchRadius; z++) {
                    BlockPos checkPos = vehiclePos.add(x, y, z);
                    TileEntity te = world.getTileEntity(checkPos);
                    
                    if (te instanceof IInventory) {
                        IInventory inv = (IInventory) te;
                        ItemStack fuel = extractFuel(inv);
                        
                        if (!fuel.isEmpty()) {
                            // Found fuel, apply to vehicle
                            applyFuel(vehicle, fuel);
                            
                            EpochRunnerMod.logger.info("[NUCLEAR-LOG] Vehicle " + 
                                vehicle.getEntityId() + " refueled from chest at " + checkPos);
                            return;
                        }
                    }
                }
            }
        }
    }
    
    /**
     * Extracts fuel item from an inventory.
     */
    private static ItemStack extractFuel(IInventory inventory) {
        Item fuelItem = getFuelItem();
        if (fuelItem == null) return ItemStack.EMPTY;
        
        for (int i = 0; i < inventory.getSizeInventory(); i++) {
            ItemStack stack = inventory.getStackInSlot(i);
            
            if (!stack.isEmpty() && stack.getItem() == fuelItem) {
                // Found fuel
                ItemStack extracted = stack.splitStack(fuelPerRefuel);
                inventory.markDirty();
                return extracted;
            }
        }
        
        return ItemStack.EMPTY;
    }
    
    /**
     * Applies fuel to a vehicle.
     */
    private static void applyFuel(Entity vehicle, ItemStack fuelStack) {
        float fuelAmount = fuelStack.getCount() * 100.0f * fuelEfficiency;
        
        // Try Flan's fuel system
        try {
            if (vehicle.getClass().getName().contains("flansmod")) {
                java.lang.reflect.Field fuelField = findField(vehicle.getClass(), "fuel", "fuelInTank");
                if (fuelField != null) {
                    fuelField.setAccessible(true);
                    Object current = fuelField.get(vehicle);
                    if (current instanceof Float) {
                        fuelField.set(vehicle, ((Float) current) + fuelAmount);
                        return;
                    }
                }
            }
        } catch (Exception e) {
            // Reflection failed
        }
        
        // Use custom NBT fuel system
        float currentFuel = vehicle.getEntityData().getFloat("fuel");
        vehicle.getEntityData().setFloat("fuel", Math.min(100.0f, currentFuel + fuelAmount));
    }
    
    /**
     * Gets the configured fuel item.
     */
    public static Item getFuelItem() {
        try {
            ResourceLocation loc = new ResourceLocation(fuelItemId);
            Item item = ForgeRegistries.ITEMS.getValue(loc);
            if (item != null) return item;
        } catch (Exception e) {
            EpochRunnerMod.logger.warn("[NUCLEAR-LOG] Invalid fuel item ID: " + fuelItemId);
        }
        
        // Default to coal
        return Items.COAL;
    }
    
    /**
     * Helper to find a field by multiple possible names.
     */
    private static java.lang.reflect.Field findField(Class<?> clazz, String... names) {
        for (String name : names) {
            try {
                return clazz.getDeclaredField(name);
            } catch (NoSuchFieldException e) {
                // Try next name
            }
            
            // Check superclass
            if (clazz.getSuperclass() != null) {
                java.lang.reflect.Field field = findField(clazz.getSuperclass(), name);
                if (field != null) return field;
            }
        }
        return null;
    }
    
    /**
     * Manually refuel a specific vehicle (for commands/items).
     */
    public static boolean manualRefuel(Entity vehicle, int amount) {
        if (!isFriendlyVehicle(vehicle)) return false;
        
        float fuelAmount = amount * 100.0f * fuelEfficiency;
        float currentFuel = vehicle.getEntityData().getFloat("fuel");
        vehicle.getEntityData().setFloat("fuel", Math.min(100.0f, currentFuel + fuelAmount));
        
        return true;
    }
    
    /**
     * Gets the current fuel level of a vehicle (0-100).
     */
    public static float getFuelLevel(Entity vehicle) {
        // Try Flan's system first
        try {
            if (vehicle.getClass().getName().contains("flansmod")) {
                java.lang.reflect.Field fuelField = findField(vehicle.getClass(), "fuel", "fuelInTank");
                if (fuelField != null) {
                    fuelField.setAccessible(true);
                    Object fuelValue = fuelField.get(vehicle);
                    if (fuelValue instanceof Number) {
                        return ((Number) fuelValue).floatValue();
                    }
                }
            }
        } catch (Exception e) {
            // Reflection failed
        }
        
        // Use NBT
        return vehicle.getEntityData().getFloat("fuel");
    }
}
