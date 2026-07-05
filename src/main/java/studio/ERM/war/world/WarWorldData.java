package studio.ERM.war.world;

import net.minecraft.block.Block;
import net.minecraft.block.state.IBlockState;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.nbt.NBTTagList;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.ChunkPos;
import net.minecraft.world.World;

import java.util.regex.Pattern;
import net.minecraft.world.storage.MapStorage;
import net.minecraft.world.storage.WorldSavedData;
import net.minecraftforge.common.util.Constants;
import net.minecraftforge.fml.common.FMLCommonHandler;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * The Source of Truth for the Homosapiens_World engine.
 * Tracks territory, player stats, pending war repairs, and sabotage.
 */
public class WarWorldData extends WorldSavedData {

    private static final Pattern UUID_PATTERN = Pattern.compile(
            "^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$"
    );

    private static String normalizeOwnerString(String raw) {
        if (raw == null) return "NEUTRAL";
        String s = raw.trim();
        if (s.isEmpty()) return "NEUTRAL";

        if (s.regionMatches(true, 0, "UUID:", 0, 5)) {
            s = s.substring(5).trim();
        }

        // Normalize known tokens
        if (s.equalsIgnoreCase("neutral")) return "NEUTRAL";
        if (s.equalsIgnoreCase("rival")) return "RIVAL";
        if (s.equalsIgnoreCase("player")) return "PLAYER";

        // If it looks like a UUID, store it as the raw UUID string
        if (UUID_PATTERN.matcher(s).matches()) return s;

        return s;
    }
    // FIXED: Changed from "Homosapien/WarState" to avoid subdirectory creation failure
    // Minecraft's MapStorage doesn't auto-create subdirectories in data/
    private static final String DATA_NAME = "ERM_WarState";

    private final Map<ChunkPos, String> territoryMap = new HashMap<>();
    private final Map<String, FactionStats> factionData = new HashMap<>();
    private final Map<BlockPos, RepairOrder> repairMap = new HashMap<>();
    private final Map<BlockPos, SabotageEntry> sabotagedBlocks = new HashMap<>();
    private final Map<BlockPos, String> citizenBedOwners = new HashMap<>(); // pos -> NPC UUID
    private BlockPos campChestPos;

    public WarWorldData(String name) {
        super(name);
    }

    public static class FactionStats {
        public int era = 0;
        public int commandPoints = 0;
        public int airDefense = 0;
        public float tension = 0.0f;
        public long lastTensionTick = 0;

        public boolean hasNuclearMilestone = false;
        public float ambushExposure = 0.0f;
        public boolean exposureWarned = false; // one-shot guard so the "you've been seen" line fires once per ramp, not every tick
        public BlockPos lastAmbushPos;
        public long ambushCooldown = 0;

        public int powerStaff = 0;
        public int engineerStaff = 0;
        public int militaryStaff = 0;
        public int agriStaff = 0;
    }

    public static class RepairOrder {
        public BlockPos pos;
        public IBlockState originalState;
        public long timestamp;

        public RepairOrder(BlockPos pos, IBlockState state, long time) {
            this.pos = pos;
            this.originalState = state;
            this.timestamp = time;
        }
    }

    public static class SabotageEntry {
        public IBlockState originalState;
        public NBTTagCompound nbt;

        public SabotageEntry(IBlockState state, NBTTagCompound nbt) {
            this.originalState = state;
            this.nbt = nbt;
        }
    }

    // --- Accessors for AI and Handlers ---

    public SabotageEntry getSabotageEntry(BlockPos pos) { return sabotagedBlocks.get(pos); }
    public Map<ChunkPos, String> getTerritoryMap() { return territoryMap; }

    /**
     * Get a copy of all chunk owners for display purposes.
     */
    public Map<ChunkPos, String> getAllChunkOwners() {
        return new HashMap<>(territoryMap);
    }
    public Map<BlockPos, RepairOrder> getRepairMap() { return repairMap; }

    /**
     * Finds the original block state for a specific position.
     * FIXED: Added for EntityAIWarBuilder compatibility.
     */
    public IBlockState getRepairState(BlockPos pos) {
        RepairOrder order = repairMap.get(pos);
        return order != null ? order.originalState : null;
    }

    /**
     * Removes a repair order after a Builder NPC completes the job.
     * FIXED: Added for EntityAIWarBuilder compatibility.
     */
    public void removeRepairOrder(BlockPos pos) {
        if (repairMap.containsKey(pos)) {
            repairMap.remove(pos);
            markDirty();
        }
    }

    public List<BlockPos> getNearbyScaffolds(BlockPos pos, double range) {
        List<BlockPos> found = new ArrayList<>();
        for (BlockPos scaffoldPos : repairMap.keySet()) {
            if (pos.getDistance(scaffoldPos.getX(), scaffoldPos.getY(), scaffoldPos.getZ()) <= range) {
                found.add(scaffoldPos);
            }
        }
        return found;
    }

    public void restoreFromScaffold(BlockPos pos) {
        RepairOrder order = repairMap.remove(pos);
        if (order != null) {
            World world = FMLCommonHandler.instance().getMinecraftServerInstance().getWorld(0);
            if (world != null) {
                world.setBlockState(pos, order.originalState);
                markDirty();
            }
        }
    }

    public static WarWorldData get(World world) {
        if (world == null) return null;

        // Always store in Overworld MapStorage so claims persist reliably
        // and don't split across dimensions / client contexts.
        World storageWorld = world;

        try {
            if (!world.isRemote && world.getMinecraftServer() != null) {
                World overworld = world.getMinecraftServer().getWorld(0);
                if (overworld != null) storageWorld = overworld;
            }
        } catch (Throwable ignored) {}

        MapStorage storage = storageWorld.getMapStorage();
        WarWorldData instance = (WarWorldData) storage.getOrLoadData(WarWorldData.class, DATA_NAME);
        if (instance == null) {
            instance = new WarWorldData(DATA_NAME);
            storage.setData(DATA_NAME, instance);
            instance.markDirty();
        }
        return instance;
    }

    public void setOwner(ChunkPos pos, String factionId) { territoryMap.put(pos, factionId); markDirty(); }
    public String getOwner(ChunkPos pos) { return territoryMap.getOrDefault(pos, "NEUTRAL"); }
    public FactionStats getStats(String factionId) { return factionData.computeIfAbsent(factionId, k -> new FactionStats()); }
    public BlockPos getCampChestPos() { return campChestPos; }
    public void setCampChestPos(BlockPos pos) { this.campChestPos = pos; markDirty(); }
    public void registerSabotage(BlockPos pos, IBlockState state, NBTTagCompound nbt) { sabotagedBlocks.put(pos, new SabotageEntry(state, nbt)); markDirty(); }
    public boolean isSabotaged(BlockPos pos) { return sabotagedBlocks.containsKey(pos); }
    public void removeSabotage(BlockPos pos) { sabotagedBlocks.remove(pos); markDirty(); }
    public void addRepairOrder(BlockPos pos, IBlockState state) { repairMap.put(pos, new RepairOrder(pos, state, System.currentTimeMillis())); markDirty(); }
    public int countClaimsForOwner(String ownerId) {
        int c = 0;
        for (String v : territoryMap.values()) {
            if (ownerId.equals(v)) c++;
        }
        return c;
    }

    // -------------------- Citizen Bed Management --------------------

    public boolean isCitizenBedOccupied(BlockPos pos) {
        return citizenBedOwners.containsKey(pos);
    }

    public String getCitizenBedOwner(BlockPos pos) {
        return citizenBedOwners.get(pos);
    }

    public boolean claimCitizenBed(BlockPos pos, String npcUUID) {
        if (citizenBedOwners.containsKey(pos)) {
            return false; // Already claimed
        }
        citizenBedOwners.put(pos, npcUUID);
        markDirty();
        return true;
    }

    public void releaseCitizenBedByNpc(String npcUUID) {
        citizenBedOwners.entrySet().removeIf(entry -> npcUUID.equals(entry.getValue()));
        markDirty();
    }

    public void releaseCitizenBed(BlockPos pos) {
        citizenBedOwners.remove(pos);
        markDirty();
    }

    /** Decode a repair order's original block state: registry-name+meta (stable), or legacy state-id. */
    private static IBlockState readRepairState(NBTTagCompound tag) {
        try {
            if (tag.hasKey("block")) {
                Block b = Block.getBlockFromName(tag.getString("block"));
                if (b == null) return null;
                return b.getStateFromMeta(tag.getInteger("meta"));
            }
            return Block.getStateById(tag.getInteger("state")); // legacy numeric-id saves
        } catch (Throwable t) {
            return null;
        }
    }

    @Override
    public void readFromNBT(NBTTagCompound nbt) {
        NBTTagList territoryList = nbt.getTagList("Territory", Constants.NBT.TAG_COMPOUND);
        for (int i = 0; i < territoryList.tagCount(); i++) {
            NBTTagCompound tag = territoryList.getCompoundTagAt(i);
            territoryMap.put(new ChunkPos(tag.getInteger("x"), tag.getInteger("z")), normalizeOwnerString(tag.getString("owner")));
        }

        NBTTagCompound factionsTag = nbt.getCompoundTag("Factions");
        for (String key : factionsTag.getKeySet()) {
            NBTTagCompound tag = factionsTag.getCompoundTag(key);
            FactionStats stats = new FactionStats();
            stats.era = tag.getInteger("era");
            stats.commandPoints = tag.getInteger("cp");
            stats.airDefense = tag.getInteger("ad");
            stats.tension = tag.getFloat("tension");
            stats.hasNuclearMilestone = tag.getBoolean("nuke_m");
            stats.ambushExposure = tag.getFloat("exp");
            stats.ambushCooldown = tag.getLong("acd");
            if (tag.hasKey("ax")) stats.lastAmbushPos = new BlockPos(tag.getInteger("ax"), tag.getInteger("ay"), tag.getInteger("az"));
            stats.powerStaff = tag.getInteger("pStaff");
            stats.engineerStaff = tag.getInteger("eStaff");
            stats.militaryStaff = tag.getInteger("mStaff");
            stats.agriStaff = tag.getInteger("aStaff");
            factionData.put(key, stats);
        }

        NBTTagList repairList = nbt.getTagList("Repairs", Constants.NBT.TAG_COMPOUND);
        int repairDropped = 0;
        for (int i = 0; i < repairList.tagCount(); i++) {
            NBTTagCompound tag = repairList.getCompoundTagAt(i);
            BlockPos pos = new BlockPos(tag.getInteger("x"), tag.getInteger("y"), tag.getInteger("z"));
            IBlockState state = readRepairState(tag);
            // Drop unresolved / AIR originals instead of storing a junk order that can never repair.
            if (state == null || state.getBlock() == net.minecraft.init.Blocks.AIR) { repairDropped++; continue; }
            repairMap.put(pos, new RepairOrder(pos, state, tag.getLong("time")));
        }
        // Decisive diagnostic for the save/exit scaffold bug: did the repair record survive the reload?
        studio.ERM.EpochRunnerMod.logger.info("[ERM-Repair] readFromNBT: loaded " + repairMap.size()
                + " repair order(s)" + (repairDropped > 0 ? " (" + repairDropped + " unresolved/air dropped)" : ""));

        NBTTagList sabotageList = nbt.getTagList("Sabotage", Constants.NBT.TAG_COMPOUND);
        for (int i = 0; i < sabotageList.tagCount(); i++) {
            NBTTagCompound tag = sabotageList.getCompoundTagAt(i);
            BlockPos pos = new BlockPos(tag.getInteger("sx"), tag.getInteger("sy"), tag.getInteger("sz"));
            IBlockState state = Block.getStateById(tag.getInteger("s_state"));
            NBTTagCompound teNbt = tag.hasKey("s_nbt") ? tag.getCompoundTag("s_nbt") : null;
            sabotagedBlocks.put(pos, new SabotageEntry(state, teNbt));
        }

        if (nbt.hasKey("camp_x")) this.campChestPos = new BlockPos(nbt.getInteger("camp_x"), nbt.getInteger("camp_y"), nbt.getInteger("camp_z"));

        // Read citizen bed owners
        NBTTagList bedList = nbt.getTagList("CitizenBeds", Constants.NBT.TAG_COMPOUND);
        for (int i = 0; i < bedList.tagCount(); i++) {
            NBTTagCompound tag = bedList.getCompoundTagAt(i);
            BlockPos pos = new BlockPos(tag.getInteger("bx"), tag.getInteger("by"), tag.getInteger("bz"));
            citizenBedOwners.put(pos, tag.getString("owner"));
        }
    }

    @Override
    public NBTTagCompound writeToNBT(NBTTagCompound nbt) {
        NBTTagList territoryList = new NBTTagList();
        for (Map.Entry<ChunkPos, String> entry : territoryMap.entrySet()) {
            NBTTagCompound tag = new NBTTagCompound();
            tag.setInteger("x", entry.getKey().x);
            tag.setInteger("z", entry.getKey().z);
            tag.setString("owner", entry.getValue());
            territoryList.appendTag(tag);
        }
        nbt.setTag("Territory", territoryList);

        NBTTagCompound factionsTag = new NBTTagCompound();
        for (Map.Entry<String, FactionStats> entry : factionData.entrySet()) {
            NBTTagCompound tag = new NBTTagCompound();
            FactionStats s = entry.getValue();
            tag.setInteger("era", s.era);
            tag.setInteger("cp", s.commandPoints);
            tag.setInteger("ad", s.airDefense);
            tag.setFloat("tension", s.tension);
            tag.setBoolean("nuke_m", s.hasNuclearMilestone);
            tag.setFloat("exp", s.ambushExposure);
            tag.setLong("acd", s.ambushCooldown);
            if (s.lastAmbushPos != null) {
                tag.setInteger("ax", s.lastAmbushPos.getX());
                tag.setInteger("ay", s.lastAmbushPos.getY());
                tag.setInteger("az", s.lastAmbushPos.getZ());
            }
            tag.setInteger("pStaff", s.powerStaff);
            tag.setInteger("eStaff", s.engineerStaff);
            tag.setInteger("mStaff", s.militaryStaff);
            tag.setInteger("aStaff", s.agriStaff);
            factionsTag.setTag(entry.getKey(), tag);
        }
        nbt.setTag("Factions", factionsTag);

        NBTTagList repairList = new NBTTagList();
        for (RepairOrder order : repairMap.values()) {
            if (order == null || order.originalState == null) continue;
            NBTTagCompound tag = new NBTTagCompound();
            tag.setInteger("x", order.pos.getX());
            tag.setInteger("y", order.pos.getY());
            tag.setInteger("z", order.pos.getZ());
            // Persist the original block by REGISTRY NAME + meta, not the numeric state id. Numeric
            // block ids are remapped when the modpack changes, so a saved id can resolve to the WRONG
            // block (or AIR) after a save/exit -- the root of "scaffolds stop repairing across a
            // reload". Registry names are stable forever.
            net.minecraft.util.ResourceLocation rn = order.originalState.getBlock().getRegistryName();
            if (rn != null) {
                tag.setString("block", rn.toString());
                tag.setInteger("meta", order.originalState.getBlock().getMetaFromState(order.originalState));
            } else {
                tag.setInteger("state", Block.getStateId(order.originalState)); // last-resort fallback
            }
            tag.setLong("time", order.timestamp);
            repairList.appendTag(tag);
        }
        nbt.setTag("Repairs", repairList);
        if (!repairMap.isEmpty()) {
            studio.ERM.EpochRunnerMod.logger.info("[ERM-Repair] writeToNBT: saved " + repairList.tagCount()
                    + " repair order(s)");
        }

        NBTTagList sabotageList = new NBTTagList();
        for (Map.Entry<BlockPos, SabotageEntry> entry : sabotagedBlocks.entrySet()) {
            NBTTagCompound tag = new NBTTagCompound();
            tag.setInteger("sx", entry.getKey().getX());
            tag.setInteger("sy", entry.getKey().getY());
            tag.setInteger("sz", entry.getKey().getZ());
            tag.setInteger("s_state", Block.getStateId(entry.getValue().originalState));
            if (entry.getValue().nbt != null) tag.setTag("s_nbt", entry.getValue().nbt);
            sabotageList.appendTag(tag);
        }
        nbt.setTag("Sabotage", sabotageList);

        if (campChestPos != null) {
            nbt.setInteger("camp_x", campChestPos.getX());
            nbt.setInteger("camp_y", campChestPos.getY());
            nbt.setInteger("camp_z", campChestPos.getZ());
        }

        // Write citizen bed owners
        NBTTagList bedList = new NBTTagList();
        for (Map.Entry<BlockPos, String> entry : citizenBedOwners.entrySet()) {
            NBTTagCompound tag = new NBTTagCompound();
            tag.setInteger("bx", entry.getKey().getX());
            tag.setInteger("by", entry.getKey().getY());
            tag.setInteger("bz", entry.getKey().getZ());
            tag.setString("owner", entry.getValue());
            bedList.appendTag(tag);
        }
        nbt.setTag("CitizenBeds", bedList);

        return nbt;
    }
}