package studio.ERM.war.rival;

import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.nbt.NBTTagList;
import net.minecraft.util.math.BlockPos;
import studio.ERM.war.rival.RivalExpansionManager;
import studio.ERM.war.rival.RivalFactionStats;

import java.util.*;

/**
 * RivalCityState - Contains all state/data classes for the rival city system.
 * 
 * Extracted from the monolithic RivalCityManager for better organization.
 */
public class RivalCityState {

    // =====================================================================
    // CONSTANTS
    // =====================================================================

    /** The single faction name used for ALL rival NPCs */
    public static final String RIVAL_FACTION_NAME = "RIVAL";
    public static final String RIVAL_AW2_FACTION = "empire";

    // =====================================================================
    // INNER CLASSES
    // =====================================================================

    /**
     * Tracks the footprint of a placed structure for destruction detection.
     */
    public static class TemplateFootprint {
        public final int width;
        public final int depth;
        public final int height;
        public final int baseSamples;
        public final int baseFilled;
        public final String structureType;
        public boolean destroyed = false;
        public long lastCheckTick = 0;

        public TemplateFootprint(int w, int d, int h, int baseSamples, int baseFilled, String structureType) {
            this.width = w;
            this.depth = d;
            this.height = h;
            this.baseSamples = baseSamples;
            this.baseFilled = baseFilled;
            this.structureType = structureType;
        }
    }

    /**
     * District intent for plot placement.
     */
    public enum DistrictType {
        CIVIC_CORE,
        RESIDENTIAL,
        INDUSTRIAL,
        MILITARY_GARRISON,
        AGRICULTURE,
        INFRASTRUCTURE
    }

    /**
     * Lightweight garrison anchor stored as grid coordinates relative to city center.
     */
    public static final class GarrisonAnchor {
        public final int gx;
        public final int gz;

        public GarrisonAnchor(int gx, int gz) {
            this.gx = gx;
            this.gz = gz;
        }

        public long pack() {
            return packGridCell(gx, gz);
        }

        public static GarrisonAnchor unpack(long packed) {
            int gx = unpackGridX(packed);
            int gz = unpackGridZ(packed);
            return new GarrisonAnchor(gx, gz);
        }

        public BlockPos toApproxWorldPos(RivalCityState state) {
            if (state == null || state.center == null) return null;
            int spacing = Math.max(1, state.gridSpacing);
            int x = state.center.getX() + (gx * spacing);
            int z = state.center.getZ() + (gz * spacing);
            return new BlockPos(x, state.center.getY(), z);
        }

        private static long packGridCell(int gx, int gz) {
            return (((long) gx) << 32) ^ (gz & 0xFFFFFFFFL);
        }

        private static int unpackGridX(long packed) {
            return (int) (packed >> 32);
        }

        private static int unpackGridZ(long packed) {
            return (int) packed;
        }
    }

    // =====================================================================
    // STATE FIELDS
    // =====================================================================

    public String ownerKey;
    public BlockPos center;
    public int level = 1;
    public int size = 64;
    public int currentRingRadius = 0;

    // Grid-based city layout
    public int gridPlotSize;
    public int gridRoadWidth;
    public int gridSpacing;
    public int currentGridRadius = 0;
    public final Set<Long> occupiedGridCells = new HashSet<>();

    // Structure tracking
    public final Set<BlockPos> placedStructures = new HashSet<>();
    public final Map<BlockPos, TemplateFootprint> templateFootprints = new HashMap<>();
    public final Map<Integer, Integer> structuresPerRing = new HashMap<>();
    public final Map<Long, Long> destroyedPlots = new HashMap<>();

    // District tracking
    public final Map<DistrictType, Integer> districtCounts = new EnumMap<>(DistrictType.class);

    // Frontier garrison anchors
    public final List<GarrisonAnchor> garrisonAnchors = new ArrayList<>();
    public long lastGarrisonAnchorTick = 0L;

    // Frontier tracking for garrison placement
    public BlockPos lastKnownPlayerPos = null;
    public long lastAnchorRefreshTick = 0L;

    // Road network
    public final Set<Long> roadBlocks = new HashSet<>();
    public final List<BlockPos> majorIntersections = new ArrayList<>();

    // Level progression: the CORE town structure (castle/walled centre) is placed exactly once,
    // when the settlement first reaches level 2 — level 1 is the tribal tent camp.
    public boolean coreStructurePlaced = false;

    // CLAIM-DRIVEN GROWTH: the player's claimed chunks accumulate as credits; every
    // playerClaimsPerBatch credits banks one growth batch; each batch grows the city by
    // growthChunksPerBatch chunks (consumed by the manager's growth tick, level 2+ only).
    public int claimCredits = 0;
    public int growthBatches = 0;

    // Ring-growth bookkeeping: grid cells currently holding FARMS (the organic frontier). When a
    // farm cell becomes interior (two rings inside the frontier), it is rebuilt as housing/civic
    // and the fields effectively migrate outward.
    public final Set<Long> farmCells = new HashSet<>();

    // One-shot LANDMARKS already raised (config key strings), e.g. the L6 factory, L8 skyscraper.
    public final Set<String> placedLandmarks = new HashSet<>();

    // SATELLITE TOWNS raised by growth batches: {centerChunkX, centerChunkZ, halfSizeChunks} per
    // entry. Used to keep new satellites from overlapping existing ones and to route link roads.
    public final List<int[]> satellites = new ArrayList<>();

    // Stats integration
    public RivalFactionStats stats;
    public RivalExpansionManager expansionManager;

    public long lastNPCSpawnTick = 0L;

    // =====================================================================
    // CONSTRUCTOR
    // =====================================================================

    public RivalCityState(String ownerKey) {
        this.ownerKey = ownerKey;
        this.stats = new RivalFactionStats(ownerKey);
        this.expansionManager = new RivalExpansionManager(ownerKey);
        
        // Initialize grid settings from config defaults
        this.gridPlotSize = RivalCityConfig.plotSize;
        this.gridRoadWidth = RivalCityConfig.roadWidth;
        this.gridSpacing = RivalCityConfig.plotSpacing;
    }

    // =====================================================================
    // NBT SERIALIZATION
    // =====================================================================

    public NBTTagCompound toNBT() {
        NBTTagCompound nbt = new NBTTagCompound();
        nbt.setString("ownerKey", ownerKey);
        if (center != null) {
            nbt.setLong("center", center.toLong());
        }
        nbt.setInteger("level", level);
        nbt.setInteger("size", size);
        nbt.setInteger("ringRadius", currentRingRadius);
        nbt.setBoolean("corePlaced", coreStructurePlaced);
        nbt.setInteger("claimCredits", claimCredits);
        nbt.setInteger("growthBatches", growthBatches);
        // Packed grid-cell longs stored as (gx, gz) int pairs (1.12 has no long-array getter).
        int[] farms = new int[farmCells.size() * 2];
        int fi = 0;
        for (Long f : farmCells) {
            farms[fi++] = (int) (f >> 32);
            farms[fi++] = (int) (long) f;
        }
        nbt.setIntArray("farmCells", farms);
        NBTTagList lmList = new NBTTagList();
        for (String lm : placedLandmarks) lmList.appendTag(new net.minecraft.nbt.NBTTagString(lm));
        nbt.setTag("landmarks", lmList);
        int[] sats = new int[satellites.size() * 3];
        int si = 0;
        for (int[] s : satellites) {
            sats[si++] = s[0];
            sats[si++] = s[1];
            sats[si++] = s[2];
        }
        nbt.setIntArray("satellites", sats);
        nbt.setInteger("gridPlotSize", gridPlotSize);
        nbt.setInteger("gridRoadWidth", gridRoadWidth);
        nbt.setInteger("gridSpacing", gridSpacing);
        nbt.setInteger("gridRadius", currentGridRadius);

        // District counts
        NBTTagCompound dTag = new NBTTagCompound();
        for (DistrictType dt : DistrictType.values()) {
            int v = districtCounts.getOrDefault(dt, 0);
            dTag.setInteger(dt.name(), v);
        }
        nbt.setTag("districtCounts", dTag);

        // Garrison anchors
        NBTTagList aList = new NBTTagList();
        for (GarrisonAnchor a : garrisonAnchors) {
            NBTTagCompound at = new NBTTagCompound();
            at.setInteger("gx", a.gx);
            at.setInteger("gz", a.gz);
            aList.appendTag(at);
        }
        nbt.setTag("garrisonAnchors", aList);
        nbt.setLong("lastGarrisonAnchorTick", lastGarrisonAnchorTick);

        // Grid cells
        NBTTagList gList = new NBTTagList();
        for (Long packed : occupiedGridCells) {
            NBTTagCompound t = new NBTTagCompound();
            t.setLong("c", packed);
            gList.appendTag(t);
        }
        nbt.setTag("gridCells", gList);
        
        nbt.setTag("stats", stats.writeToNBT(new NBTTagCompound()));
        nbt.setTag("expansion", expansionManager.writeToNBT(new NBTTagCompound()));

        // Template footprints serialization
        NBTTagList tList = new NBTTagList();
        for (Map.Entry<BlockPos, TemplateFootprint> e : templateFootprints.entrySet()) {
            NBTTagCompound t = new NBTTagCompound();
            t.setLong("a", e.getKey().toLong());
            TemplateFootprint fp = e.getValue();
            t.setInteger("w", fp.width);
            t.setInteger("d", fp.depth);
            t.setInteger("h", fp.height);
            t.setInteger("bs", fp.baseSamples);
            t.setInteger("bf", fp.baseFilled);
            t.setString("st", fp.structureType == null ? "UNKNOWN" : fp.structureType);
            t.setBoolean("dead", fp.destroyed);
            tList.appendTag(t);
        }
        nbt.setTag("templates", tList);

        return nbt;
    }

    public void fromNBT(NBTTagCompound nbt) {
        ownerKey = nbt.getString("ownerKey");
        if (nbt.hasKey("center")) {
            center = BlockPos.fromLong(nbt.getLong("center"));
        }
        level = nbt.getInteger("level");
        size = nbt.getInteger("size");
        currentRingRadius = nbt.getInteger("ringRadius");
        coreStructurePlaced = nbt.getBoolean("corePlaced");
        claimCredits = nbt.getInteger("claimCredits");
        growthBatches = nbt.getInteger("growthBatches");
        farmCells.clear();
        int[] farms = nbt.getIntArray("farmCells");
        for (int i = 0; i + 1 < farms.length; i += 2) {
            farmCells.add((((long) farms[i]) << 32) ^ (farms[i + 1] & 0xFFFFFFFFL));
        }
        placedLandmarks.clear();
        NBTTagList lmList = nbt.getTagList("landmarks", net.minecraftforge.common.util.Constants.NBT.TAG_STRING);
        for (int i = 0; i < lmList.tagCount(); i++) {
            placedLandmarks.add(lmList.getStringTagAt(i));
        }
        satellites.clear();
        int[] sats = nbt.getIntArray("satellites");
        for (int i = 0; i + 2 < sats.length; i += 3) {
            satellites.add(new int[]{sats[i], sats[i + 1], sats[i + 2]});
        }

        // Grid layout
        gridPlotSize = nbt.hasKey("gridPlotSize") ? nbt.getInteger("gridPlotSize") : RivalCityConfig.plotSize;
        gridRoadWidth = nbt.hasKey("gridRoadWidth") ? nbt.getInteger("gridRoadWidth") : RivalCityConfig.roadWidth;
        gridSpacing = nbt.hasKey("gridSpacing") ? nbt.getInteger("gridSpacing") : (gridPlotSize + gridRoadWidth);
        currentGridRadius = nbt.hasKey("gridRadius") ? nbt.getInteger("gridRadius") : 0;
        
        occupiedGridCells.clear();
        if (nbt.hasKey("gridCells")) {
            NBTTagList gList = nbt.getTagList("gridCells", net.minecraftforge.common.util.Constants.NBT.TAG_COMPOUND);
            for (int i = 0; i < gList.tagCount(); i++) {
                NBTTagCompound t = gList.getCompoundTagAt(i);
                occupiedGridCells.add(t.getLong("c"));
            }
        }

        // District counts
        districtCounts.clear();
        if (nbt.hasKey("districtCounts")) {
            NBTTagCompound dTag = nbt.getCompoundTag("districtCounts");
            for (DistrictType dt : DistrictType.values()) {
                if (dTag.hasKey(dt.name())) {
                    districtCounts.put(dt, dTag.getInteger(dt.name()));
                }
            }
        }

        // Garrison anchors
        garrisonAnchors.clear();
        if (nbt.hasKey("garrisonAnchors")) {
            NBTTagList aList = nbt.getTagList("garrisonAnchors", net.minecraftforge.common.util.Constants.NBT.TAG_COMPOUND);
            for (int i = 0; i < aList.tagCount(); i++) {
                NBTTagCompound at = aList.getCompoundTagAt(i);
                garrisonAnchors.add(new GarrisonAnchor(at.getInteger("gx"), at.getInteger("gz")));
            }
        }
        lastGarrisonAnchorTick = nbt.getLong("lastGarrisonAnchorTick");

        if (nbt.hasKey("stats")) {
            stats.readFromNBT(nbt.getCompoundTag("stats"));
        }
        if (nbt.hasKey("expansion")) {
            expansionManager.readFromNBT(nbt.getCompoundTag("expansion"));
        }

        // Template footprints deserialization
        templateFootprints.clear();
        NBTTagList tList = nbt.getTagList("templates", net.minecraftforge.common.util.Constants.NBT.TAG_COMPOUND);
        for (int i = 0; i < tList.tagCount(); i++) {
            NBTTagCompound t = tList.getCompoundTagAt(i);
            BlockPos a = BlockPos.fromLong(t.getLong("a"));
            int w = t.getInteger("w");
            int d = t.getInteger("d");
            int h = t.getInteger("h");
            int bs = t.getInteger("bs");
            int bf = t.getInteger("bf");
            String st = t.getString("st");

            TemplateFootprint fp = new TemplateFootprint(w, d, h, bs, bf, st);
            fp.destroyed = t.getBoolean("dead");
            templateFootprints.put(a, fp);
        }
    }

    // =====================================================================
    // UTILITY METHODS
    // =====================================================================

    /**
     * Clear all structures and reset for regeneration.
     */
    public void clearStructures() {
        placedStructures.clear();
        templateFootprints.clear();
        roadBlocks.clear();
        majorIntersections.clear();
        structuresPerRing.clear();
        destroyedPlots.clear();
        districtCounts.clear();
        occupiedGridCells.clear();
        garrisonAnchors.clear();
    }
}
