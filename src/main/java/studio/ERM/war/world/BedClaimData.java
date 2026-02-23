package studio.ERM.war.world;

import net.minecraft.block.BlockBed;
import net.minecraft.block.properties.PropertyEnum;
import net.minecraft.block.state.IBlockState;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.util.EnumFacing;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;
import net.minecraft.world.storage.MapStorage;
import net.minecraft.world.storage.WorldSavedData;

import javax.annotation.Nullable;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Persistent per-world registry of bed claims.
 *
 * - Claims stored per block position
 * - When claiming a bed, BOTH halves are claimed
 * - Used to block player sleeping in claimed beds
 * - Can be cleared on bed break or NPC death
 */
public class BedClaimData extends WorldSavedData {

    private static final String DATA_NAME = "ERM_BedClaims";

    private final Map<Long, UUID> claims = new HashMap<>();

    public BedClaimData() {
        super(DATA_NAME);
    }

    public BedClaimData(String name) {
        super(name);
    }

    public static BedClaimData get(World world) {
        MapStorage storage = world.getPerWorldStorage();
        BedClaimData data = (BedClaimData) storage.getOrLoadData(BedClaimData.class, DATA_NAME);
        if (data == null) {
            data = new BedClaimData();
            storage.setData(DATA_NAME, data);
        }
        return data;
    }

    /**
     * Claim the bed at any bed position (head or foot). Claims BOTH halves.
     */
    public boolean claimBed(World world, BlockPos anyBedPos, UUID owner) {
        if (world == null || anyBedPos == null || owner == null) return false;

        BlockPos[] parts = resolveHeadAndFoot(world, anyBedPos);
        if (parts == null) return false;

        long headKey = pack(parts[0]);
        long footKey = pack(parts[1]);

        UUID headOwner = claims.get(headKey);
        UUID footOwner = claims.get(footKey);

        if (headOwner != null && !headOwner.equals(owner)) return false;
        if (footOwner != null && !footOwner.equals(owner)) return false;

        claims.put(headKey, owner);
        claims.put(footKey, owner);
        markDirty();
        return true;
    }

    /**
     * Unclaim the bed at any bed position (head or foot), but only if owner matches.
     */
    public void unclaimBed(World world, BlockPos anyBedPos, UUID owner) {
        if (world == null || anyBedPos == null || owner == null) return;

        BlockPos[] parts = resolveHeadAndFoot(world, anyBedPos);
        if (parts == null) {
            long k = pack(anyBedPos);
            UUID cur = claims.get(k);
            if (owner.equals(cur)) {
                claims.remove(k);
                markDirty();
            }
            return;
        }

        long headKey = pack(parts[0]);
        long footKey = pack(parts[1]);

        UUID headOwner = claims.get(headKey);
        UUID footOwner = claims.get(footKey);

        if (owner.equals(headOwner)) claims.remove(headKey);
        if (owner.equals(footOwner)) claims.remove(footKey);

        markDirty();
    }

    /**
     * Force-clear any claim on the bed at any bed position (head or foot), regardless of owner.
     */
    public void unclaimBed(World world, BlockPos anyBedPos) {
        if (world == null || anyBedPos == null) return;

        BlockPos[] parts = resolveHeadAndFoot(world, anyBedPos);
        if (parts == null) {
            long k = pack(anyBedPos);
            if (claims.remove(k) != null) markDirty();
            return;
        }

        long headKey = pack(parts[0]);
        long footKey = pack(parts[1]);

        boolean changed = false;
        if (claims.remove(headKey) != null) changed = true;
        if (claims.remove(footKey) != null) changed = true;

        if (changed) markDirty();
    }

    @Nullable
    public UUID getOwnerAt(BlockPos pos) {
        if (pos == null) return null;
        return claims.get(pack(pos));
    }

    public boolean isClaimedByOther(World world, BlockPos anyBedPos, @Nullable UUID me) {
        if (world == null || anyBedPos == null) return false;

        BlockPos head = normalizeToBedHead(world, anyBedPos);
        if (head == null) return false;

        UUID o = claims.get(pack(head));
        if (o != null) return (me == null || !o.equals(me));

        UUID exact = claims.get(pack(anyBedPos));
        return exact != null && (me == null || !exact.equals(me));
    }

    public static boolean isBedStillValid(World world, BlockPos anyBedPos) {
        if (world == null || anyBedPos == null) return false;

        BlockPos[] parts = resolveHeadAndFoot(world, anyBedPos);
        if (parts == null) return false;

        IBlockState headState = world.getBlockState(parts[0]);
        IBlockState footState = world.getBlockState(parts[1]);

        return (headState.getBlock() instanceof BlockBed) && (footState.getBlock() instanceof BlockBed);
    }

    @Nullable
    public static BlockPos normalizeToBedHead(World world, BlockPos pos) {
        if (world == null || pos == null) return null;
        IBlockState state = world.getBlockState(pos);
        if (!(state.getBlock() instanceof BlockBed)) return null;

        EnumFacing facing = state.getValue(BlockBed.FACING);

        PropertyEnum<?> partProp = BlockBed.PART;
        Object partVal = state.getValue(partProp);
        if (partVal != null && partVal.toString().equalsIgnoreCase("foot")) {
            return pos.offset(facing);
        }
        return pos;
    }

    @Nullable
    public static BlockPos[] resolveHeadAndFoot(World world, BlockPos anyBedPos) {
        BlockPos head = normalizeToBedHead(world, anyBedPos);
        if (head == null) return null;

        IBlockState headState = world.getBlockState(head);
        if (!(headState.getBlock() instanceof BlockBed)) return null;

        EnumFacing facing = headState.getValue(BlockBed.FACING);
        BlockPos foot = head.offset(facing.getOpposite());

        IBlockState footState = world.getBlockState(foot);
        if (!(footState.getBlock() instanceof BlockBed)) return null;

        return new BlockPos[]{head.toImmutable(), foot.toImmutable()};
    }

    @Override
    public void readFromNBT(NBTTagCompound nbt) {
        claims.clear();
        int count = nbt.getInteger("count");
        for (int i = 0; i < count; i++) {
            NBTTagCompound e = nbt.getCompoundTag("e" + i);
            long key = e.getLong("k");
            String uuid = e.getString("u");
            try {
                UUID u = UUID.fromString(uuid);
                claims.put(key, u);
            } catch (Throwable ignored) { }
        }
    }

    @Override
    public NBTTagCompound writeToNBT(NBTTagCompound nbt) {
        nbt.setInteger("count", claims.size());
        int i = 0;
        for (Map.Entry<Long, UUID> e : claims.entrySet()) {
            NBTTagCompound entry = new NBTTagCompound();
            entry.setLong("k", e.getKey());
            entry.setString("u", e.getValue().toString());
            nbt.setTag("e" + i, entry);
            i++;
        }
        return nbt;
    }

    private static long pack(BlockPos p) {
        long x = (p.getX() & 0x3FFFFFFL);
        long y = (p.getY() & 0xFFFL);
        long z = (p.getZ() & 0x3FFFFFFL);
        return (x << 38) | (z << 12) | y;
    }
}
