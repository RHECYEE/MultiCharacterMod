package studio.ERM.strategic.resource;

import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.nbt.NBTTagList;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;
import net.minecraft.world.biome.Biome;
import net.minecraft.world.storage.WorldSavedData;
import studio.ERM.EpochRunnerMod;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/**
 * STRATEGIC RESOURCE NODES — the long-term economic potential of the region, existing ONLY in the
 * strategic layer (no ore blocks; a node is a ledger entry the camps mine down). Seeded once per
 * world from the world seed (biome-weighted), finite by design: exhausting a deposit abandons its
 * camp and pushes the frontier elsewhere. The player and the Rival compete over these.
 *
 *   discovery — playerKnown via SURVEY missions; rivalKnown via the rival's periodic recon
 *   camps     — campState NONE -> PENDING (convoy arrived / mission done; schematic not yet placed
 *               because the chunk is unloaded) -> BUILT (schematic placed the moment the area
 *               loads naturally — never force-loaded) -> EXHAUSTED (reserve gone, abandoned)
 *   output    — production accrues into storedOutput per Minecraft day; player camps deliver to
 *               the warehouse (via the depot inbox), rival camps hold for teamster collection
 */
public class ResourceNodeData extends WorldSavedData {

    private static final String KEY = "erm_resource_nodes";

    // Resource types.
    public static final int IRON = 0, COAL = 1, COPPER = 2, GOLD = 3, OIL = 4, TIMBER = 5, STONE = 6, GRAVEL = 7;
    public static final String[] TYPE_NAMES = {"Iron", "Coal", "Copper", "Gold", "Oil", "Timber", "Stone", "Gravel"};

    // Ownership.
    public static final int OWNER_NONE = 0, OWNER_PLAYER = 1, OWNER_RIVAL = 2;
    // Camp lifecycle.
    public static final int CAMP_NONE = 0, CAMP_PENDING = 1, CAMP_BUILT = 2, CAMP_EXHAUSTED = 3;

    public static class Node {
        public int uid;
        public int type;
        public BlockPos pos;
        public long reserve;        // total units at seed time
        public long remaining;
        public int ratePerDay;      // units/day at level 1 staffing
        public int difficulty;      // 1..3 — survey difficulty
        public boolean playerKnown, rivalKnown;
        public int owner = OWNER_NONE;
        public int campState = CAMP_NONE;
        public long storedOutput;   // mined units awaiting collection/delivery
        public String template = ""; // the AW2 schematic chosen for this camp (once built)
        public long lastProdDay = -1;

        public String typeName() { return TYPE_NAMES[Math.max(0, Math.min(type, TYPE_NAMES.length - 1))]; }
        public boolean hasActiveCamp() { return campState == CAMP_PENDING || campState == CAMP_BUILT; }
    }

    public final List<Node> nodes = new ArrayList<>();
    public long lastRivalEvalDay = -1;
    private int nextUid = 1;

    public ResourceNodeData() { super(KEY); }
    public ResourceNodeData(String name) { super(name); }

    public static ResourceNodeData get(World world) {
        ResourceNodeData data = (ResourceNodeData) world.getPerWorldStorage().getOrLoadData(ResourceNodeData.class, KEY);
        if (data == null) {
            data = new ResourceNodeData();
            world.getPerWorldStorage().setData(KEY, data);
        }
        return data;
    }

    // ------------------------------------------------------------------
    //  SEEDING — once per world, deterministic from the world seed
    // ------------------------------------------------------------------

    /** Seed the node field on first touch: a ring band around world spawn, biome-weighted types. */
    public void ensureSeeded(World world) {
        if (!nodes.isEmpty()) return;
        Random rng = new Random(world.getSeed() ^ 0x5EED_CA3F5L);
        BlockPos spawn = world.getSpawnPoint();
        int want = 14;
        for (int i = 0; i < want; i++) {
            double ang = rng.nextDouble() * Math.PI * 2;
            int dist = 300 + rng.nextInt(600);
            int x = spawn.getX() + (int) (Math.cos(ang) * dist);
            int z = spawn.getZ() + (int) (Math.sin(ang) * dist);
            Node n = new Node();
            n.uid = nextUid++;
            n.pos = new BlockPos(x, 64, z);
            n.type = rollType(world, n.pos, rng);
            n.difficulty = 1 + rng.nextInt(3);
            n.ratePerDay = 40 + rng.nextInt(60);
            n.reserve = 2000L + rng.nextInt(8000);
            n.remaining = n.reserve;
            nodes.add(n);
        }
        markDirty();
        EpochRunnerMod.logger.info("[Resources] seeded " + nodes.size() + " strategic resource node(s)");
    }

    /** Biome-weighted type roll (biome provider only — never loads chunks). */
    private static int rollType(World world, BlockPos pos, Random rng) {
        try {
            Biome b = world.getBiomeProvider().getBiome(pos);
            String name = b != null && b.getBiomeName() != null ? b.getBiomeName().toLowerCase() : "";
            if (name.contains("forest") || name.contains("taiga") || name.contains("jungle"))
                return rng.nextInt(3) == 0 ? IRON : TIMBER;
            if (name.contains("desert") || name.contains("mesa") || name.contains("swamp"))
                return rng.nextInt(3) == 0 ? GOLD : OIL;
            if (name.contains("hill") || name.contains("mountain") || name.contains("extreme"))
                return new int[]{IRON, COAL, STONE, COPPER}[rng.nextInt(4)];
            if (name.contains("river") || name.contains("beach") || name.contains("ocean"))
                return GRAVEL;
        } catch (Throwable ignored) {}
        return new int[]{IRON, COAL, COPPER, GOLD, TIMBER, STONE, GRAVEL}[rng.nextInt(7)];
    }

    // ------------------------------------------------------------------
    //  QUERIES
    // ------------------------------------------------------------------

    public Node byUid(int uid) {
        for (Node n : nodes) if (n.uid == uid) return n;
        return null;
    }

    /** Nearest node not yet known to {@code forPlayer ? the player : the rival}, within maxDist of (x,z). */
    public Node nearestUndiscovered(int x, int z, boolean forPlayer, double maxDist) {
        Node best = null;
        double bd = maxDist * maxDist;
        for (Node n : nodes) {
            if (forPlayer ? n.playerKnown : n.rivalKnown) continue;
            double d = n.pos.distanceSq(x, n.pos.getY(), z);
            if (d < bd) { bd = d; best = n; }
        }
        return best;
    }

    /** Nearest discovered, unowned, unexhausted node within maxDist — a camp candidate. */
    public Node nearestClaimable(int x, int z, boolean forPlayer, double maxDist) {
        Node best = null;
        double bd = maxDist * maxDist;
        for (Node n : nodes) {
            if (!(forPlayer ? n.playerKnown : n.rivalKnown)) continue;
            if (n.owner != OWNER_NONE || n.campState == CAMP_EXHAUSTED || n.remaining <= 0) continue;
            double d = n.pos.distanceSq(x, n.pos.getY(), z);
            if (d < bd) { bd = d; best = n; }
        }
        return best;
    }

    public int activeCamps(int owner) {
        int c = 0;
        for (Node n : nodes) if (n.owner == owner && n.hasActiveCamp()) c++;
        return c;
    }

    // ------------------------------------------------------------------
    //  NBT
    // ------------------------------------------------------------------

    @Override
    public void readFromNBT(NBTTagCompound nbt) {
        nodes.clear();
        nextUid = Math.max(1, nbt.getInteger("nextUid"));
        lastRivalEvalDay = nbt.hasKey("lastEval") ? nbt.getLong("lastEval") : -1;
        NBTTagList list = nbt.getTagList("nodes", 10);
        for (int i = 0; i < list.tagCount(); i++) {
            NBTTagCompound t = list.getCompoundTagAt(i);
            Node n = new Node();
            n.uid = t.getInteger("uid");
            n.type = t.getInteger("type");
            n.pos = BlockPos.fromLong(t.getLong("pos"));
            n.reserve = t.getLong("reserve");
            n.remaining = t.getLong("remaining");
            n.ratePerDay = t.getInteger("rate");
            n.difficulty = Math.max(1, t.getInteger("diff"));
            n.playerKnown = t.getBoolean("pk");
            n.rivalKnown = t.getBoolean("rk");
            n.owner = t.getInteger("owner");
            n.campState = t.getInteger("camp");
            n.storedOutput = t.getLong("stored");
            n.template = t.getString("tmpl");
            n.lastProdDay = t.hasKey("prodDay") ? t.getLong("prodDay") : -1;
            nodes.add(n);
        }
    }

    @Override
    public NBTTagCompound writeToNBT(NBTTagCompound nbt) {
        nbt.setInteger("nextUid", nextUid);
        nbt.setLong("lastEval", lastRivalEvalDay);
        NBTTagList list = new NBTTagList();
        for (Node n : nodes) {
            NBTTagCompound t = new NBTTagCompound();
            t.setInteger("uid", n.uid);
            t.setInteger("type", n.type);
            t.setLong("pos", n.pos.toLong());
            t.setLong("reserve", n.reserve);
            t.setLong("remaining", n.remaining);
            t.setInteger("rate", n.ratePerDay);
            t.setInteger("diff", n.difficulty);
            t.setBoolean("pk", n.playerKnown);
            t.setBoolean("rk", n.rivalKnown);
            t.setInteger("owner", n.owner);
            t.setInteger("camp", n.campState);
            t.setLong("stored", n.storedOutput);
            t.setString("tmpl", n.template == null ? "" : n.template);
            t.setLong("prodDay", n.lastProdDay);
            list.appendTag(t);
        }
        nbt.setTag("nodes", list);
        return nbt;
    }
}
