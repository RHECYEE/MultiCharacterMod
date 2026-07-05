package studio.ERM.strategic.civil;

import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.nbt.NBTTagList;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;
import net.minecraft.world.storage.WorldSavedData;
import net.minecraftforge.items.IItemHandler;
import net.minecraftforge.items.ItemHandlerHelper;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

/**
 * THE DEPOT INBOX — goods addressed to a district depot whose chunk is NOT loaded. Instead of
 * force-loading the chunk to touch the tile entity (expensive, and it fights the chunk manager),
 * the delivery is written HERE, into persistent district data; the physical chest fills the moment
 * the depot's chunk loads naturally ({@link studio.ERM.war.districts.TileEntityDistrictMarker}
 * drains its own inbox in onLoad, and loaded-depot deliveries drain first too).
 *
 * This is the strategic-layer doctrine: the DATA is the settlement's ledger, the blocks are just
 * its projection when someone is there to see it.
 */
public class DepotInboxData extends WorldSavedData {

    private static final String KEY = "erm_depot_inbox";
    private static final int MAX_STACKS_PER_DEPOT = 256; // runaway guard

    /** depot pos (toLong) -> stacks awaiting the chunk to load. */
    public final Map<Long, List<ItemStack>> pending = new HashMap<>();

    public DepotInboxData() { super(KEY); }
    public DepotInboxData(String name) { super(name); }

    public static DepotInboxData get(World world) {
        DepotInboxData data = (DepotInboxData) world.getPerWorldStorage().getOrLoadData(DepotInboxData.class, KEY);
        if (data == null) {
            data = new DepotInboxData();
            world.getPerWorldStorage().setData(KEY, data);
        }
        return data;
    }

    /** Queue a delivery for an unloaded depot. Returns false only when the inbox guard is full. */
    public boolean queue(BlockPos depot, ItemStack stack) {
        if (depot == null || stack == null || stack.isEmpty()) return false;
        List<ItemStack> list = pending.get(depot.toLong());
        if (list == null) { list = new ArrayList<>(); pending.put(depot.toLong(), list); }
        if (list.size() >= MAX_STACKS_PER_DEPOT) return false;
        list.add(stack.copy());
        markDirty();
        return true;
    }

    /** Anything waiting for this depot? (cheap check for onLoad) */
    public boolean hasPending(BlockPos depot) {
        List<ItemStack> list = pending.get(depot.toLong());
        return list != null && !list.isEmpty();
    }

    /**
     * Drain this depot's queued mail into its inventory. Whatever doesn't fit STAYS queued (a full
     * chest keeps its backlog until couriers make room). Returns the number of stacks delivered.
     */
    public int drainInto(BlockPos depot, IItemHandler dst) {
        List<ItemStack> list = pending.get(depot.toLong());
        if (list == null || list.isEmpty() || dst == null) return 0;
        int delivered = 0;
        Iterator<ItemStack> it = list.iterator();
        while (it.hasNext()) {
            ItemStack st = it.next();
            ItemStack left = ItemHandlerHelper.insertItemStacked(dst, st, false);
            if (left.isEmpty()) {
                it.remove();
                delivered++;
            } else if (left.getCount() != st.getCount()) {
                list.set(list.indexOf(st), left); // partial fit: keep the remainder queued
                delivered++;
                break; // chest is full past this point
            } else {
                break; // nothing fits any more
            }
        }
        if (list.isEmpty()) pending.remove(depot.toLong());
        if (delivered > 0) markDirty();
        return delivered;
    }

    @Override
    public void readFromNBT(NBTTagCompound nbt) {
        pending.clear();
        NBTTagList depots = nbt.getTagList("depots", 10);
        for (int i = 0; i < depots.tagCount(); i++) {
            NBTTagCompound d = depots.getCompoundTagAt(i);
            List<ItemStack> list = new ArrayList<>();
            NBTTagList items = d.getTagList("items", 10);
            for (int j = 0; j < items.tagCount(); j++) {
                ItemStack st = new ItemStack(items.getCompoundTagAt(j));
                if (!st.isEmpty()) list.add(st);
            }
            if (!list.isEmpty()) pending.put(d.getLong("pos"), list);
        }
    }

    @Override
    public NBTTagCompound writeToNBT(NBTTagCompound nbt) {
        NBTTagList depots = new NBTTagList();
        for (Map.Entry<Long, List<ItemStack>> e : pending.entrySet()) {
            NBTTagCompound d = new NBTTagCompound();
            d.setLong("pos", e.getKey());
            NBTTagList items = new NBTTagList();
            for (ItemStack st : e.getValue()) items.appendTag(st.writeToNBT(new NBTTagCompound()));
            d.setTag("items", items);
            depots.appendTag(d);
        }
        nbt.setTag("depots", depots);
        return nbt;
    }
}
