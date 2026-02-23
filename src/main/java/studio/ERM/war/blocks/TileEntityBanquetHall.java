package studio.ERM.war.blocks;

import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.inventory.IInventory;
import net.minecraft.inventory.InventoryHelper;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.util.EnumFacing;
import net.minecraft.util.NonNullList;

import net.minecraftforge.common.util.Constants;
import net.minecraftforge.items.CapabilityItemHandler;
import net.minecraftforge.items.IItemHandler;
import net.minecraftforge.items.wrapper.InvWrapper;

import javax.annotation.Nullable;

public class TileEntityBanquetHall extends TileEntity implements IInventory {

    private static final int SIZE = 54; // double chest size, but in one TE
    private final NonNullList<ItemStack> items = NonNullList.withSize(SIZE, ItemStack.EMPTY);

    @Override
    public int getSizeInventory() {
        return items.size();
    }

    @Override
    public boolean isEmpty() {
        for (ItemStack s : items) {
            if (!s.isEmpty()) return false;
        }
        return true;
    }

    @Override
    public ItemStack getStackInSlot(int index) {
        if (index < 0 || index >= items.size()) return ItemStack.EMPTY;
        return items.get(index);
    }

    @Override
    public ItemStack decrStackSize(int index, int count) {
        if (index < 0 || index >= items.size() || count <= 0) return ItemStack.EMPTY;
        ItemStack existing = items.get(index);
        if (existing.isEmpty()) return ItemStack.EMPTY;

        ItemStack split = existing.splitStack(count);
        if (existing.getCount() <= 0) items.set(index, ItemStack.EMPTY);

        markDirty();
        return split;
    }

    @Override
    public ItemStack removeStackFromSlot(int index) {
        if (index < 0 || index >= items.size()) return ItemStack.EMPTY;
        ItemStack out = items.get(index);
        items.set(index, ItemStack.EMPTY);
        markDirty();
        return out;
    }

    @Override
    public void setInventorySlotContents(int index, ItemStack stack) {
        if (index < 0 || index >= items.size()) return;
        items.set(index, stack == null ? ItemStack.EMPTY : stack);
        if (!items.get(index).isEmpty() && items.get(index).getCount() > getInventoryStackLimit()) {
            items.get(index).setCount(getInventoryStackLimit());
        }
        markDirty();
    }

    @Override
    public int getInventoryStackLimit() {
        return 64;
    }

    @Override
    public boolean isUsableByPlayer(EntityPlayer player) {
        if (player == null || world == null || pos == null) return false;
        if (world.getTileEntity(pos) != this) return false;
        return player.getDistanceSq(pos) <= 64.0D;
    }

    @Override
    public void openInventory(EntityPlayer player) { }

    @Override
    public void closeInventory(EntityPlayer player) { }

    @Override
    public boolean isItemValidForSlot(int index, ItemStack stack) {
        return true;
    }

    @Override
    public int getField(int id) { return 0; }

    @Override
    public void setField(int id, int value) { }

    @Override
    public int getFieldCount() { return 0; }

    @Override
    public void clear() {
        for (int i = 0; i < items.size(); i++) items.set(i, ItemStack.EMPTY);
        markDirty();
    }

    @Override
    public String getName() {
        return "container.erm_banquet_hall";
    }

    @Override
    public boolean hasCustomName() {
        return false;
    }

    @Override
    public void readFromNBT(NBTTagCompound compound) {
        super.readFromNBT(compound);

        if (compound.hasKey("Items", Constants.NBT.TAG_LIST)) {
            net.minecraft.nbt.NBTTagList list = compound.getTagList("Items", Constants.NBT.TAG_COMPOUND);
            for (int i = 0; i < list.tagCount(); i++) {
                NBTTagCompound it = list.getCompoundTagAt(i);
                int slot = it.getInteger("Slot");
                if (slot >= 0 && slot < items.size()) {
                    items.set(slot, new ItemStack(it));
                }
            }
        }
    }

    @Override
    public NBTTagCompound writeToNBT(NBTTagCompound compound) {
        super.writeToNBT(compound);

        net.minecraft.nbt.NBTTagList list = new net.minecraft.nbt.NBTTagList();
        for (int i = 0; i < items.size(); i++) {
            ItemStack s = items.get(i);
            if (!s.isEmpty()) {
                NBTTagCompound it = new NBTTagCompound();
                it.setInteger("Slot", i);
                s.writeToNBT(it);
                list.appendTag(it);
            }
        }
        compound.setTag("Items", list);
        return compound;
    }

    @Override
    public boolean hasCapability(net.minecraftforge.common.capabilities.Capability<?> capability, @Nullable EnumFacing facing) {
        if (capability == CapabilityItemHandler.ITEM_HANDLER_CAPABILITY) return true;
        return super.hasCapability(capability, facing);
    }

    @Override
    @SuppressWarnings("unchecked")
    public <T> T getCapability(net.minecraftforge.common.capabilities.Capability<T> capability, @Nullable EnumFacing facing) {
        if (capability == CapabilityItemHandler.ITEM_HANDLER_CAPABILITY) {
            IItemHandler wrapper = new InvWrapper(this);
            return (T) wrapper;
        }
        return super.getCapability(capability, facing);
    }

    public void dropAllContents() {
        if (world == null || pos == null) return;
        for (ItemStack s : items) {
            if (!s.isEmpty()) {
                InventoryHelper.spawnItemStack(world, pos.getX(), pos.getY(), pos.getZ(), s);
            }
        }
        clear();
    }
}
