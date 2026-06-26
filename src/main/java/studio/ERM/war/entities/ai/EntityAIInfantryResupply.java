package co.runed.multicharacter.compat;

import net.minecraft.entity.EntityCreature;
import net.minecraft.entity.ai.EntityAIBase;
import net.minecraft.inventory.IInventory;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.tileentity.TileEntityChest;
import net.minecraft.util.math.AxisAlignedBB;
import net.minecraft.util.math.Vec3d;
import net.minecraft.util.math.BlockPos;
import studio.ERM.war.items.ItemInfantryMag;

import java.util.ArrayList;
import java.util.List;

public class EntityAIInfantryResupply extends EntityAIBase {

    private final EntityCreature npc;
    private final double speed;
    private final int searchRadius;

    private BlockPos targetChestPos = null;
    private int cooldown = 0;

    public EntityAIInfantryResupply(EntityCreature npc, double speed, int searchRadius) {
        this.npc = npc;
        this.speed = speed;
        this.searchRadius = searchRadius;
        this.setMutexBits(3);
    }

    @Override
    public boolean shouldExecute() {
        if (npc == null || npc.world == null || npc.world.isRemote) return false;
        if (cooldown > 0) { cooldown--; return false; }

        NBTTagCompound nbt = npc.getEntityData();
        int ammo = nbt.getInteger("Infantry_CurrentAmmo");
        boolean needsAmmo = nbt.getBoolean("Infantry_NeedsAmmo");
        int empties = nbt.getInteger("Infantry_EmptyMags");

        boolean hasLoadedMags = hasLoadedMagazineStack();
        boolean shouldResupply = needsAmmo || (ammo <= 0 && !hasLoadedMags) || empties > 0;

        if (!shouldResupply) return false;

        targetChestPos = findSupplyChest();
        return targetChestPos != null;
    }

    @Override
    public boolean shouldContinueExecuting() {
        if (npc == null || npc.world == null || npc.world.isRemote) return false;
        if (targetChestPos == null) return false;

        double distSq = npc.getDistanceSqToCenter(targetChestPos);
        return distSq > 3.0D && !npc.getNavigator().noPath();
    }

    @Override
    public void startExecuting() {
        if (targetChestPos != null) {
            npc.getNavigator().tryMoveToXYZ(targetChestPos.getX() + 0.5, targetChestPos.getY(), targetChestPos.getZ() + 0.5, speed);
        }
    }

    @Override
    public void resetTask() {
        targetChestPos = null;
        npc.getNavigator().clearPath();
        cooldown = 20;
    }

    @Override
    public void updateTask() {
        if (targetChestPos == null) return;

        double distSq = npc.getDistanceSqToCenter(targetChestPos);
        if (distSq > 4.0D) {
            npc.getNavigator().tryMoveToXYZ(targetChestPos.getX() + 0.5, targetChestPos.getY(), targetChestPos.getZ() + 0.5, speed);
            return;
        }

        TileEntity te = npc.world.getTileEntity(targetChestPos);
        IInventory inv = getChestInventory(te);
        if (inv == null) { cooldown = 40; resetTask(); return; }

        NBTTagCompound nbt = npc.getEntityData();

        // 1) Drop off empty mags into the chest
        int empties = nbt.getInteger("Infantry_EmptyMags");
        if (empties > 0) {
            ItemStack emptyStack = new ItemStack(ModItems.MAG_EMPTY, empties);
            int inserted = insertStack(inv, emptyStack);
            int remaining = emptyStack.getCount();
            nbt.setInteger("Infantry_EmptyMags", remaining);
        }

        // 2) Pull loaded mags from the chest into OFFHAND stack (acts as our ammo pouch)
        if (!hasLoadedMagazineStack()) {
            ItemStack pulled = extractBestLoadedMag(inv);
            if (!pulled.isEmpty()) {
                npc.setHeldItem(net.minecraft.util.EnumHand.OFF_HAND, pulled);
                nbt.setBoolean("Infantry_NeedsAmmo", false);
            } else {
                // No mags available - keep needsAmmo true so we keep searching later
                nbt.setBoolean("Infantry_NeedsAmmo", true);
            }
        }

        cooldown = 60;
        resetTask();
    }

    private boolean hasLoadedMagazineStack() {
        ItemStack off = npc.getHeldItemOffhand();
        return !off.isEmpty() && off.getItem() instanceof ItemInfantryMag;
    }

    private BlockPos findSupplyChest() {
        // Prefer chest near home if the NPC has one
        BlockPos origin = npc.getPosition();
        if (npc.hasHome()) origin = npc.getHomePosition();

        int r = searchRadius;
        AxisAlignedBB box = new AxisAlignedBB(origin).grow(r, r, r);

        List<TileEntity> tes = new ArrayList<TileEntity>(npc.world.loadedTileEntityList);
        BlockPos best = null;
        double bestDist = Double.MAX_VALUE;

        for (TileEntity te : tes) {
            if (te == null || te.isInvalid()) continue;
            BlockPos p = te.getPos();
            if (!box.contains(new Vec3d(p.getX() + 0.5, p.getY() + 0.5, p.getZ() + 0.5))) continue;

            IInventory inv = getChestInventory(te);
            if (inv == null) continue;

            boolean hasMags = chestHasLoadedMags(inv);
            boolean isAmmoChestName = isNamedAmmoChest(te);

            // Must have mags, but named chests get priority
            if (!hasMags && !isAmmoChestName) continue;

            double d = npc.getDistanceSqToCenter(p);
            if (isAmmoChestName) d *= 0.5D;

            if (d < bestDist) {
                bestDist = d;
                best = p;
            }
        }
        return best;
    }

    private boolean isNamedAmmoChest(TileEntity te) {
        if (!(te instanceof TileEntityChest)) return false;
        TileEntityChest c = (TileEntityChest) te;
        if (!c.hasCustomName()) return false;
        String n = c.getName().toLowerCase();
        return n.contains("ammo") || n.contains("upkeep") || n.contains("supply");
    }

    private boolean chestHasLoadedMags(IInventory inv) {
        for (int i = 0; i < inv.getSizeInventory(); i++) {
            ItemStack s = inv.getStackInSlot(i);
            if (!s.isEmpty() && s.getItem() instanceof ItemInfantryMag && s.getCount() > 0) return true;
        }
        return false;
    }

    private ItemStack extractBestLoadedMag(IInventory inv) {
        // Prefer higher capacity mags first
        ItemStack best = ItemStack.EMPTY;
        int bestCap = -1;
        int bestSlot = -1;

        for (int i = 0; i < inv.getSizeInventory(); i++) {
            ItemStack s = inv.getStackInSlot(i);
            if (s.isEmpty()) continue;
            if (!(s.getItem() instanceof ItemInfantryMag)) continue;

            int cap = ((ItemInfantryMag) s.getItem()).capacity;
            if (cap > bestCap) {
                bestCap = cap;
                best = s;
                bestSlot = i;
            }
        }

        if (bestSlot >= 0) {
            ItemStack out = best.copy();
            out.setCount(1);
            best.shrink(1);
            inv.markDirty();
            return out;
        }
        return ItemStack.EMPTY;
    }

    private IInventory getChestInventory(TileEntity te) {
        if (te instanceof TileEntityChest) {
            return (TileEntityChest) te;
        }
        if (te instanceof IInventory) {
            return (IInventory) te;
        }
        return null;
    }

    // Returns number inserted (mutates stack to remaining)
    private int insertStack(IInventory inv, ItemStack stack) {
        if (stack.isEmpty()) return 0;

        int inserted = 0;

        for (int i = 0; i < inv.getSizeInventory(); i++) {
            if (stack.isEmpty()) break;
            ItemStack slot = inv.getStackInSlot(i);

            if (slot.isEmpty()) {
                ItemStack put = stack.copy();
                int putCount = Math.min(put.getMaxStackSize(), put.getCount());
                put.setCount(putCount);
                inv.setInventorySlotContents(i, put);
                stack.shrink(putCount);
                inserted += putCount;
                continue;
            }

            if (ItemStack.areItemsEqual(slot, stack) && ItemStack.areItemStackTagsEqual(slot, stack)) {
                int space = slot.getMaxStackSize() - slot.getCount();
                if (space <= 0) continue;
                int move = Math.min(space, stack.getCount());
                slot.grow(move);
                stack.shrink(move);
                inserted += move;
                inv.markDirty();
            }
        }

        inv.markDirty();
        return inserted;
    }
}