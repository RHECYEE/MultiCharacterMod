package studio.ERM.war.BattleManagers.core;

import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityList;
import net.minecraft.inventory.IInventory;
import net.minecraft.item.ItemStack;
import net.minecraft.util.ResourceLocation;
import net.minecraft.world.World;

/**
 * CHEST-CART toolbox shared by the trader caravan and the siege LOOT TRAIN:
 *  - create the REAL AW2 chest cart reflectively (the vehicle module stays a soft dependency),
 *  - push item stacks into ANY cart-ish entity's cargo (item-handler capability, IInventory,
 *    a chested mule's hidden horseChest, or AW2's nested vehicle inventory -- all found generically),
 *  - make a cart indestructible (the loot train is fought over via its guards, never just blown up).
 */
public final class ChestCartHelper {

    private ChestCartHelper() {}

    /**
     * Create (NOT spawn) an AW2 chest cart: ancientwarfarevehicle:vehicle with the "chest_cart"
     * VehicleType applied reflectively. Returns null when the AW2 vehicle module isn't installed or no
     * cart type exists -- callers fall back to their own plan (chest mule / chest grid).
     */
    public static Entity createChestCart(World world) {
        try {
            Entity v = EntityList.createEntityByIDFromName(
                    new ResourceLocation("ancientwarfarevehicle", "vehicle"), world);
            if (v == null) return null;
            Object cartType = findCartVehicleType();
            if (cartType == null) return null;
            Class<?> iVehicleType = Class.forName("net.shadowmage.ancientwarfare.vehicle.entity.IVehicleType");
            v.getClass().getMethod("setVehicleType", iVehicleType, int.class).invoke(v, cartType, 0);
            return v;
        } catch (Throwable t) {
            return null;
        }
    }

    /** The AW2 VehicleType for the chest cart: prefer a config name with "chest"+"cart", else any "cart". */
    private static Object findCartVehicleType() {
        try {
            Class<?> vtClass = Class.forName("net.shadowmage.ancientwarfare.vehicle.entity.types.VehicleType");
            java.lang.reflect.Field f = vtClass.getDeclaredField("vehicleTypes");
            f.setAccessible(true);
            Object reg = f.get(null);
            Iterable<?> all;
            if (reg instanceof Object[]) all = java.util.Arrays.asList((Object[]) reg);
            else if (reg instanceof Iterable) all = (Iterable<?>) reg;
            else if (reg instanceof java.util.Map) all = ((java.util.Map<?, ?>) reg).values();
            else return null;
            Object anyCart = null;
            for (Object vt : all) {
                if (vt == null) continue;
                try {
                    Object cn = vt.getClass().getMethod("getConfigName").invoke(vt);
                    if (cn == null) continue;
                    String name = cn.toString().toLowerCase();
                    if (!name.contains("cart")) continue;
                    if (name.contains("chest")) return vt;
                    if (anyCart == null) anyCart = vt;
                } catch (Throwable ignored) {}
            }
            return anyCart;
        } catch (Throwable t) {
            return null;
        }
    }

    /**
     * Insert a stack into a cart entity's cargo. Tries, in order: the Forge item-handler capability,
     * the entity as a direct IInventory, then a generic reflective scan for an IInventory field (this
     * finds a chested mule's horseChest by TYPE, name-independent and obfuscation-safe) including one
     * level into an AW2-style "inventory"/"storage" holder object. Returns the remainder (EMPTY = all in).
     */
    public static ItemStack insertIntoCart(Entity cart, ItemStack stack) {
        if (cart == null || cart.isDead || stack == null || stack.isEmpty()) return stack;
        try {
            if (cart.hasCapability(net.minecraftforge.items.CapabilityItemHandler.ITEM_HANDLER_CAPABILITY, null)) {
                net.minecraftforge.items.IItemHandler h = cart.getCapability(
                        net.minecraftforge.items.CapabilityItemHandler.ITEM_HANDLER_CAPABILITY, null);
                if (h != null) {
                    stack = net.minecraftforge.items.ItemHandlerHelper.insertItemStacked(h, stack, false);
                    if (stack.isEmpty()) return ItemStack.EMPTY;
                }
            }
        } catch (Throwable ignored) {}
        IInventory inv = (cart instanceof IInventory) ? (IInventory) cart : findInventoryReflect(cart);
        if (inv != null) return insertIntoInventory(inv, stack);
        return stack;
    }

    /** Generic cargo-inventory hunt: any IInventory-typed field on the entity (works for the vanilla
     *  chested mule by type, not name), then one level into "inventory"/"storage" holder objects (AW2). */
    private static IInventory findInventoryReflect(Entity cart) {
        try {
            for (Class<?> c = cart.getClass(); c != null && c != Object.class; c = c.getSuperclass()) {
                for (java.lang.reflect.Field f : c.getDeclaredFields()) {
                    if (!IInventory.class.isAssignableFrom(f.getType())) continue;
                    f.setAccessible(true);
                    Object v = f.get(cart);
                    if (v instanceof IInventory) return (IInventory) v;
                }
            }
            for (Class<?> c = cart.getClass(); c != null && c != Object.class; c = c.getSuperclass()) {
                for (java.lang.reflect.Field f : c.getDeclaredFields()) {
                    String n = f.getName().toLowerCase();
                    if (!n.contains("inventory") && !n.contains("storage")) continue;
                    f.setAccessible(true);
                    Object holder = f.get(cart);
                    if (holder == null) continue;
                    for (java.lang.reflect.Field g : holder.getClass().getDeclaredFields()) {
                        if (!IInventory.class.isAssignableFrom(g.getType())) continue;
                        g.setAccessible(true);
                        Object v = g.get(holder);
                        if (v instanceof IInventory) return (IInventory) v;
                    }
                }
            }
        } catch (Throwable ignored) {}
        return null;
    }

    /** Vanilla merge-then-fill insert into an IInventory. Returns the remainder (EMPTY = fully inserted). */
    public static ItemStack insertIntoInventory(IInventory inv, ItemStack stack) {
        if (inv == null || stack == null || stack.isEmpty()) return stack;
        try {
            for (int i = 0; i < inv.getSizeInventory() && !stack.isEmpty(); i++) {
                ItemStack slot = inv.getStackInSlot(i);
                if (slot.isEmpty()) {
                    int n = Math.min(stack.getCount(), Math.min(stack.getMaxStackSize(), inv.getInventoryStackLimit()));
                    ItemStack put = stack.copy();
                    put.setCount(n);
                    inv.setInventorySlotContents(i, put);
                    stack.shrink(n);
                } else if (ItemStack.areItemsEqual(slot, stack) && ItemStack.areItemStackTagsEqual(slot, stack)
                        && slot.getCount() < Math.min(slot.getMaxStackSize(), inv.getInventoryStackLimit())) {
                    int add = Math.min(Math.min(slot.getMaxStackSize(), inv.getInventoryStackLimit())
                            - slot.getCount(), stack.getCount());
                    slot.grow(add);
                    stack.shrink(add);
                }
            }
            inv.markDirty();
        } catch (Throwable ignored) {}
        return stack.isEmpty() ? ItemStack.EMPTY : stack;
    }

    /** True when the cart's cargo has NO room left (every resolvable slot filled to its cap). Used by the
     *  occupation's "the wagons are packed, time to go home" departure gate. Unresolvable cargo = false. */
    public static boolean isCartFull(Entity cart) {
        if (cart == null || cart.isDead) return false;
        try {
            if (cart.hasCapability(net.minecraftforge.items.CapabilityItemHandler.ITEM_HANDLER_CAPABILITY, null)) {
                net.minecraftforge.items.IItemHandler h = cart.getCapability(
                        net.minecraftforge.items.CapabilityItemHandler.ITEM_HANDLER_CAPABILITY, null);
                if (h != null && h.getSlots() > 0) {
                    for (int i = 0; i < h.getSlots(); i++) {
                        ItemStack st = h.getStackInSlot(i);
                        if (st.isEmpty() || st.getCount() < Math.min(st.getMaxStackSize(), h.getSlotLimit(i)))
                            return false;
                    }
                    return true;
                }
            }
        } catch (Throwable ignored) {}
        IInventory inv = (cart instanceof IInventory) ? (IInventory) cart : findInventoryReflect(cart);
        if (inv == null || inv.getSizeInventory() == 0) return false;
        try {
            for (int i = 0; i < inv.getSizeInventory(); i++) {
                ItemStack st = inv.getStackInSlot(i);
                if (st.isEmpty() || st.getCount() < Math.min(st.getMaxStackSize(), inv.getInventoryStackLimit()))
                    return false;
            }
        } catch (Throwable t) {
            return false;
        }
        return true;
    }

    /** Flip the entity's private invulnerable flag (obfuscation-safe): the loot train can only be
     *  emptied through its guards, never removed with a TNT block. Best-effort; failure = destructible. */
    public static void makeInvulnerable(Entity e) {
        if (e == null) return;
        try {
            net.minecraftforge.fml.relauncher.ReflectionHelper.setPrivateValue(
                    Entity.class, e, Boolean.TRUE, "invulnerable", "field_83001_bt");
        } catch (Throwable ignored) {}
    }
}
