package studio.ERM.strategic.civil;

import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.nbt.NBTTagList;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;
import net.minecraft.world.storage.WorldSavedData;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * PERSISTENT bed ownership: NPC UUID -> claimed bed (HEAD block) + housing class. This is what makes the
 * settlement feel lived-in: a citizen keeps the SAME bed across nights, saves, and chunk reloads until the
 * bed breaks, its district changes, or the citizen dies. Assignment/repair runs in
 * {@link BedAssignmentManager}; this class is only the saved state.
 */
public class BedAssignmentData extends WorldSavedData {

    private static final String KEY = "erm_bed_assignments";

    public static class Claim {
        public final BlockPos bed;
        /** true = a Barracks bed (military housing); false = Residential (civilian). */
        public final boolean military;

        public Claim(BlockPos bed, boolean military) {
            this.bed = bed;
            this.military = military;
        }
    }

    /** citizen uuid -> its claimed bed. */
    public final Map<UUID, Claim> claims = new HashMap<>();

    public BedAssignmentData() { super(KEY); }
    public BedAssignmentData(String name) { super(name); }

    public static BedAssignmentData get(World world) {
        BedAssignmentData data = (BedAssignmentData) world.getPerWorldStorage().getOrLoadData(BedAssignmentData.class, KEY);
        if (data == null) {
            data = new BedAssignmentData();
            world.getPerWorldStorage().setData(KEY, data);
        }
        return data;
    }

    /** Every claimed bed position (for the sidebar's "Beds free A/T" and duplicate-claim checks). */
    public Set<BlockPos> claimedBeds() {
        Set<BlockPos> out = new HashSet<>();
        for (Claim c : claims.values()) out.add(c.bed);
        return out;
    }

    public boolean isBedClaimed(BlockPos bed) {
        for (Claim c : claims.values()) if (c.bed.equals(bed)) return true;
        return false;
    }

    public void put(UUID npc, BlockPos bed, boolean military) {
        claims.put(npc, new Claim(bed, military));
        markDirty();
    }

    public void remove(UUID npc) {
        if (claims.remove(npc) != null) markDirty();
    }

    @Override
    public void readFromNBT(NBTTagCompound nbt) {
        claims.clear();
        NBTTagList list = nbt.getTagList("claims", 10);
        for (int i = 0; i < list.tagCount(); i++) {
            NBTTagCompound t = list.getCompoundTagAt(i);
            try {
                claims.put(new UUID(t.getLong("uM"), t.getLong("uL")),
                        new Claim(BlockPos.fromLong(t.getLong("bed")), t.getBoolean("mil")));
            } catch (Throwable ignored) {}
        }
    }

    @Override
    public NBTTagCompound writeToNBT(NBTTagCompound nbt) {
        NBTTagList list = new NBTTagList();
        for (Map.Entry<UUID, Claim> e : claims.entrySet()) {
            NBTTagCompound t = new NBTTagCompound();
            t.setLong("uM", e.getKey().getMostSignificantBits());
            t.setLong("uL", e.getKey().getLeastSignificantBits());
            t.setLong("bed", e.getValue().bed.toLong());
            t.setBoolean("mil", e.getValue().military);
            list.appendTag(t);
        }
        nbt.setTag("claims", list);
        return nbt;
    }
}
