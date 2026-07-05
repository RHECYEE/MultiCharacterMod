package studio.ERM.strategic.civil.logistics;

import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.util.math.BlockPos;

import java.util.UUID;

/**
 * ONE TRANSPORT JOB in the settlement's logistics network. Jobs are created by the
 * LogisticsManager from depot IN/OUT templates — never by the player placing routes.
 *
 * Records exactly what the spec asks for: source, destination, cargo, priority, assigned
 * courier, progress. Jobs persist; couriers are replaceable labor (a dead courier's job simply
 * returns to PENDING). While the courier's chunks are unloaded the job keeps progressing
 * virtually on the strategic layer and completes against the depots when they are loaded.
 */
public class CourierJob {

    public static final int TYPE_RESTOCK = 0;   // deliver an IN-template item to the requester
    public static final int TYPE_EXPORT = 1;    // move an OUT surplus to the Warehouse
    public static final String[] TYPE_NAMES = {"Restock", "Export"};

    public static final int STATE_PENDING = 0;   // waiting for a courier
    public static final int STATE_TO_SOURCE = 1; // courier walking to pick up
    public static final int STATE_TO_DEST = 2;   // courier carrying cargo to the destination

    public int uid;
    public int type = TYPE_RESTOCK;
    public BlockPos src = BlockPos.ORIGIN;   // source DEPOT block
    public BlockPos dst = BlockPos.ORIGIN;   // destination DEPOT block
    public ItemStack item = ItemStack.EMPTY; // cargo template (what to move)
    public int count = 16;                   // how many to move (capped by carry capacity)
    public int priority = 50;                // higher = assigned first
    public int state = STATE_PENDING;
    /** Cargo actually picked up (non-empty once the courier has loaded). */
    public ItemStack carried = ItemStack.EMPTY;
    /** Assigned courier (null/none = pending). Not persisted — restarts re-hire. */
    public UUID courier = null;
    /** Blocks of virtual progress accumulated while the courier is unloaded. */
    public double virtualProgress = 0;
    /** Completed — the manager removes it on its next pass. */
    public boolean done = false;

    public boolean assigned() {
        return courier != null;
    }

    public NBTTagCompound writeToNBT(NBTTagCompound tag) {
        tag.setInteger("uid", uid);
        tag.setInteger("type", type);
        tag.setIntArray("src", new int[]{src.getX(), src.getY(), src.getZ()});
        tag.setIntArray("dst", new int[]{dst.getX(), dst.getY(), dst.getZ()});
        NBTTagCompound it = new NBTTagCompound();
        item.writeToNBT(it);
        tag.setTag("item", it);
        tag.setInteger("count", count);
        tag.setInteger("priority", priority);
        tag.setInteger("state", state);
        NBTTagCompound car = new NBTTagCompound();
        carried.writeToNBT(car);
        tag.setTag("carried", car);
        tag.setDouble("vprog", virtualProgress);
        return tag;
    }

    public void readFromNBT(NBTTagCompound tag) {
        uid = tag.getInteger("uid");
        type = tag.getInteger("type");
        int[] s = tag.getIntArray("src");
        int[] d = tag.getIntArray("dst");
        if (s.length == 3) src = new BlockPos(s[0], s[1], s[2]);
        if (d.length == 3) dst = new BlockPos(d[0], d[1], d[2]);
        item = new ItemStack(tag.getCompoundTag("item"));
        count = tag.getInteger("count");
        priority = tag.getInteger("priority");
        state = tag.getInteger("state");
        carried = new ItemStack(tag.getCompoundTag("carried"));
        virtualProgress = tag.getDouble("vprog");
        courier = null; // couriers are transient labor; the manager re-hires
        if (state != STATE_PENDING && carried.isEmpty()) state = STATE_PENDING;
    }
}
