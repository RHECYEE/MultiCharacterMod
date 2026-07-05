package studio.ERM.strategic.civil;

import net.minecraft.init.Items;
import net.minecraft.item.ItemStack;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.text.TextComponentString;
import net.minecraft.util.text.TextFormatting;
import net.minecraft.world.WorldServer;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import net.minecraftforge.fml.common.gameevent.TickEvent;
import net.minecraftforge.items.ItemHandlerHelper;
import studio.ERM.EpochRunnerMod;
import studio.ERM.war.districts.TileEntityDistrictMarker;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/**
 * STRATEGIC MISSIONS — expeditions dispatched from the Civilian map's Mission Dispatch GUI to a
 * target. The player assigns PLAYER-PROXY CHARACTERS (skilled, never die) and CIVILIAN escorts (do
 * the work, but are at RISK). A mission runs on a timer, then returns with a reward + a report; a
 * fraction of the escorts equal to the mission RISK may be lost (the ongoing cost of exploration).
 * Transient by design (a restart cancels in-flight missions).
 */
public final class StrategicMissionManager {

    // Mission types.
    public static final int HUNTING = 0, SURVEY = 1, MINERALS = 2, EXPLORE = 3, SCOUT = 4, ENGINEERING = 5,
            SETUP_CAMP = 6;
    public static final String[] NAMES = {
            "Hunting Expedition", "Survey Frontier", "Search for Minerals",
            "Explore Territory", "Scout Route", "Engineering Survey", "Establish Camp"};
    public static final String[] REWARDS = {
            "Raw meat for the larder", "A written survey of the area", "Ores hauled to the warehouse",
            "Exploration notes + finds", "A scouted route report", "Building materials",
            "A working extraction camp"};
    /** Base danger of each mission before any party reduces it (0..1). */
    private static final double[] BASE_RISK = {0.20, 0.28, 0.42, 0.50, 0.30, 0.24, 0.30};
    private static final int[] DURATION_SEC = {60, 120, 150, 180, 90, 120, 150};

    private static final Random RNG = new Random();

    private StrategicMissionManager() {}

    private static final List<Mission> MISSIONS = new ArrayList<>();

    private static final class Mission {
        final int dim, type, x, z, characters, citizens;
        final double risk;
        final long completeTick;
        final java.util.UUID player;
        Mission(int dim, int type, int x, int z, int characters, int citizens, double risk,
                long completeTick, java.util.UUID player) {
            this.dim = dim; this.type = type; this.x = x; this.z = z;
            this.characters = characters; this.citizens = citizens; this.risk = risk;
            this.completeTick = completeTick; this.player = player;
        }
    }

    /**
     * THE RISK CALCULATOR (authoritative, also mirrored client-side for the GUI estimate): base danger
     * minus the party's competence. Each CHARACTER cuts risk more than a citizen (they're specialists);
     * a matching-role character (see {@link #charSkillBonus}) cuts it further. More escorts spread the
     * load. Clamped so a mission is never perfectly safe nor certain death.
     */
    public static double computeRisk(int type, int characterSkillUnits, int citizens) {
        double base = BASE_RISK[Math.max(0, Math.min(type, BASE_RISK.length - 1))];
        double reduce = characterSkillUnits * 0.09 + citizens * 0.025;
        return Math.max(0.02, Math.min(0.95, base - reduce));
    }

    /** Role → skill units for a mission (attribute weighting): the right specialist matters most. */
    public static int charSkillBonus(String role, int type) {
        if (role == null) return 1;
        String r = role.toLowerCase();
        if (r.contains("soldier") || r.contains("king")) return 2;            // combat/leadership: all missions
        if (type == MINERALS && r.contains("miner")) return 3;
        if (type == ENGINEERING && r.contains("engineer")) return 3;
        if (type == SETUP_CAMP && (r.contains("engineer") || r.contains("miner"))) return 3;
        if ((type == EXPLORE || type == SCOUT || type == SURVEY) && r.contains("nomad")) return 3;
        if (type == HUNTING && (r.contains("lumber") || r.contains("miner"))) return 2;
        if (r.contains("magician")) return 2;
        return 1; // any character still helps a little
    }

    /** Legacy hunting-party entry (kept for the old packet): 3 citizens, no characters. */
    public static void launch(WorldServer world, int type, int x, int z, java.util.UUID player) {
        dispatch(world, world.getPlayerEntityByUUID(player), type, 0, 3, x, z);
    }

    /**
     * Dispatch a mission with a party. characterSkillUnits is the summed skill of the assigned
     * characters (computed by the packet handler from the roster), citizens the escort count.
     */
    public static void dispatch(WorldServer world, net.minecraft.entity.player.EntityPlayer p,
                                int type, int characterSkillUnits, int citizens, int x, int z) {
        if (type < 0 || type >= NAMES.length) return;
        double risk = computeRisk(type, characterSkillUnits, citizens);
        int dur = DURATION_SEC[type] * 20;
        MISSIONS.add(new Mission(world.provider.getDimension(), type, x, z, characterSkillUnits, citizens,
                risk, world.getTotalWorldTime() + dur, p != null ? p.getUniqueID() : null));
        if (p != null) {
            p.sendMessage(new TextComponentString(TextFormatting.GREEN + NAMES[type] + " dispatched to "
                    + x + ", " + z + " — " + citizens + " escort(s), risk "
                    + (int) Math.round(risk * 100) + "%. Back in ~" + (DURATION_SEC[type] / 60) + "m"
                    + (DURATION_SEC[type] % 60 == 0 ? "" : (DURATION_SEC[type] % 60) + "s") + "."));
        }
        EpochRunnerMod.logger.info("[Mission] " + NAMES[type] + " dispatched to " + x + "," + z
                + " (chars=" + characterSkillUnits + " citizens=" + citizens + " risk=" + risk + ")");
    }

    @SubscribeEvent
    public static void onWorldTick(TickEvent.WorldTickEvent e) {
        if (e.phase != TickEvent.Phase.END || e.side.isClient() || !(e.world instanceof WorldServer)) return;
        if (MISSIONS.isEmpty()) return;
        WorldServer world = (WorldServer) e.world;
        long now = world.getTotalWorldTime();
        for (Mission m : new ArrayList<>(MISSIONS)) {
            if (m.dim != world.provider.getDimension() || now < m.completeTick) continue;
            MISSIONS.remove(m);
            complete(world, m);
        }
    }

    private static void complete(WorldServer world, Mission m) {
        net.minecraft.entity.player.EntityPlayer p = m.player != null ? world.getPlayerEntityByUUID(m.player) : null;

        // CASUALTIES: each escort risks the mission's risk; CHARACTERS never die. Lost citizens are
        // culled from the settlement (the ongoing cost of expansion) — up to what's actually there.
        // Camp crews are exempt: the escorts who survive the road BECOME the camp's staff.
        int lost = 0;
        if (m.type != SETUP_CAMP) {
            for (int i = 0; i < m.citizens; i++) if (RNG.nextDouble() < m.risk) lost++;
            if (lost > 0) cullCitizens(world, m, lost);
        }

        // REWARD by type: hunting/minerals/engineering -> the warehouse; survey/explore/scout -> a
        // written report handed to the player (now with real DEPOSIT DISCOVERY rolls); camp -> a
        // founded extraction camp on a surveyed deposit.
        String report;
        switch (m.type) {
            case HUNTING: {
                int c = 2 + RNG.nextInt(15);
                report = depotReward(world, new ItemStack(Items.PORKCHOP, c), p, c + " raw porkchops");
                break;
            }
            case MINERALS: {
                ItemStack ore = rollOre();
                report = depotReward(world, ore, p, ore.getCount() + "x " + ore.getDisplayName());
                // "Search for Minerals" IS the surveying now (Survey Frontier/Engineering Survey are
                // retired from the menu): a successful sweep also CHARTS the nearest unknown deposit.
                studio.ERM.strategic.resource.ResourceNodeData.Node found = rollDeposit(world, m, 0.60);
                if (found != null) {
                    giveDepositChart(world, m, p, found);
                    report += "; a " + found.typeName() + " DEPOSIT was charted at "
                            + found.pos.getX() + ", " + found.pos.getZ();
                }
                break;
            }
            case ENGINEERING: {
                ItemStack mats = new ItemStack(net.minecraft.init.Blocks.STONE, 16 + RNG.nextInt(32));
                report = depotReward(world, mats, p, mats.getCount() + " building stone");
                break;
            }
            case SETUP_CAMP:
                report = establishCamp(world, m, p);
                break;
            case SURVEY:
            case EXPLORE:
            case SCOUT:
            default:
                report = givePaper(world, m, p);
                break;
        }

        if (p != null) {
            String casualty = lost > 0 ? TextFormatting.RED + " Lost " + lost + " escort(s)."
                    : TextFormatting.GRAY + " All escorts returned safely.";
            p.sendMessage(new TextComponentString(TextFormatting.GOLD + NAMES[m.type] + " returned — "
                    + report + "." + casualty));
        }
        EpochRunnerMod.logger.info("[Mission] " + NAMES[m.type] + " returned (lost " + lost + " escorts)");
    }

    private static ItemStack rollOre() {
        int r = RNG.nextInt(100);
        if (r < 3) return new ItemStack(Items.DIAMOND, 1 + RNG.nextInt(2));
        if (r < 15) return new ItemStack(Items.GOLD_INGOT, 2 + RNG.nextInt(4));
        if (r < 45) return new ItemStack(Items.IRON_INGOT, 3 + RNG.nextInt(6));
        return new ItemStack(Items.COAL, 4 + RNG.nextInt(8));
    }

    /** Deposit a reward at the warehouse; returns a human phrase for the return message. */
    private static String depotReward(WorldServer world, ItemStack haul, net.minecraft.entity.player.EntityPlayer p, String desc) {
        BlockPos where = deliver(world, haul);
        if (where != null) return desc + " delivered to the warehouse at " + where.getX() + ", " + where.getZ();
        if (p != null) net.minecraft.inventory.InventoryHelper.spawnItemStack(world, p.posX, p.posY, p.posZ, haul);
        return desc + " (no warehouse — dropped at your feet)";
    }

    /**
     * Roll REAL DEPOSIT DISCOVERY against the strategic resource-node layer: on success the nearest
     * unknown node within 240 of the mission target becomes player-known and is returned. Shared by
     * Search for Minerals (the surveyor, high odds) and Scout Route (stumbles onto deposits).
     */
    private static studio.ERM.strategic.resource.ResourceNodeData.Node rollDeposit(
            WorldServer world, Mission m, double baseChance) {
        try {
            studio.ERM.strategic.resource.ResourceNodeData nodes =
                    studio.ERM.strategic.resource.ResourceNodeData.get(world);
            nodes.ensureSeeded(world);
            studio.ERM.strategic.resource.ResourceNodeData.Node n =
                    nodes.nearestUndiscovered(m.x, m.z, true, 240);
            if (n == null) return null;
            double chance = baseChance + m.characters * 0.10 + m.citizens * 0.02
                    - 0.15 * (n.difficulty - 1);
            if (RNG.nextDouble() >= Math.max(0.05, Math.min(0.95, chance))) return null;
            n.playerKnown = true;
            nodes.markDirty();
            return n;
        } catch (Throwable t) {
            return null;
        }
    }

    /** The deposit CHART: a written book carrying the node's type/position/reserve + instructions. */
    private static void giveDepositChart(WorldServer world, Mission m,
                                         net.minecraft.entity.player.EntityPlayer p,
                                         studio.ERM.strategic.resource.ResourceNodeData.Node n) {
        ItemStack book = new ItemStack(Items.WRITTEN_BOOK);
        net.minecraft.nbt.NBTTagCompound tag = new net.minecraft.nbt.NBTTagCompound();
        tag.setString("author", "Expedition");
        tag.setString("title", n.typeName() + " deposit @ " + n.pos.getX() + "," + n.pos.getZ());
        net.minecraft.nbt.NBTTagList pages = new net.minecraft.nbt.NBTTagList();
        String body = "DEPOSIT CHART\n\n" + n.typeName() + "\nat " + n.pos.getX() + ", " + n.pos.getZ()
                + "\nEstimated reserve: ~" + n.remaining + " units"
                + "\nSurvey difficulty: " + n.difficulty + "/3"
                + "\n\nDispatch an 'Establish Camp' mission to that spot to exploit it.";
        pages.appendTag(new net.minecraft.nbt.NBTTagString("\"" + body.replace("\"", "'") + "\""));
        tag.setTag("pages", pages);
        book.setTagCompound(tag);
        if (p != null && !p.inventory.addItemStackToInventory(book)) {
            net.minecraft.inventory.InventoryHelper.spawnItemStack(world, p.posX, p.posY, p.posZ, book);
        }
    }

    /** Scout/legacy report missions return a WRITTEN paper — scouts also stumble onto deposits. */
    private static String givePaper(WorldServer world, Mission m, net.minecraft.entity.player.EntityPlayer p) {
        String finding = surveyFinding(m.type);
        String headline = "a written report was added to your inventory";
        studio.ERM.strategic.resource.ResourceNodeData.Node n =
                rollDeposit(world, m, m.type == SURVEY ? 0.55 : m.type == SCOUT ? 0.35 : 0.28);
        if (n != null) {
            finding = "DEPOSIT FOUND: " + n.typeName() + "\nat " + n.pos.getX() + ", " + n.pos.getZ()
                    + "\nEstimated reserve: ~" + n.remaining + " units"
                    + "\nSurvey difficulty: " + n.difficulty + "/3"
                    + "\n\nDispatch an 'Establish Camp' mission to that spot to exploit it.";
            headline = "a " + n.typeName() + " DEPOSIT was charted at "
                    + n.pos.getX() + ", " + n.pos.getZ();
        }

        ItemStack book = new ItemStack(Items.WRITTEN_BOOK);
        net.minecraft.nbt.NBTTagCompound tag = new net.minecraft.nbt.NBTTagCompound();
        tag.setString("author", "Expedition");
        tag.setString("title", NAMES[m.type] + " @ " + m.x + "," + m.z);
        net.minecraft.nbt.NBTTagList pages = new net.minecraft.nbt.NBTTagList();
        String body = NAMES[m.type] + "\n\nTarget: " + m.x + ", " + m.z
                + "\nEscorts: " + m.citizens + "\nRisk: " + (int) Math.round(m.risk * 100) + "%\n\n"
                + finding;
        pages.appendTag(new net.minecraft.nbt.NBTTagString("\"" + body.replace("\"", "'") + "\""));
        tag.setTag("pages", pages);
        book.setTagCompound(tag);
        if (p != null && !p.inventory.addItemStackToInventory(book)) {
            net.minecraft.inventory.InventoryHelper.spawnItemStack(world, p.posX, p.posY, p.posZ, book);
        }
        return headline;
    }

    /**
     * ESTABLISH CAMP — the player's expansion move: the crew founds an extraction camp on a
     * SURVEYED, unclaimed deposit near the mission target. The camp is PENDING until its chunk
     * loads naturally, then the AW2 schematic appears and daily output ships to the warehouse
     * (ResourceCampManager owns the rest of the lifecycle).
     */
    private static String establishCamp(WorldServer world, Mission m, net.minecraft.entity.player.EntityPlayer p) {
        try {
            studio.ERM.strategic.resource.ResourceNodeData nodes =
                    studio.ERM.strategic.resource.ResourceNodeData.get(world);
            nodes.ensureSeeded(world);
            studio.ERM.strategic.resource.ResourceNodeData.Node n = nodes.nearestClaimable(m.x, m.z, true, 80);
            if (n == null) {
                return "the crew found no surveyed, unclaimed deposit at the site (survey it first) and returned";
            }
            n.owner = studio.ERM.strategic.resource.ResourceNodeData.OWNER_PLAYER;
            n.campState = studio.ERM.strategic.resource.ResourceNodeData.CAMP_PENDING;
            nodes.markDirty();
            EpochRunnerMod.logger.info("[Mission] player founded a " + n.typeName() + " camp @ "
                    + n.pos.getX() + "," + n.pos.getZ());
            return "your " + n.typeName() + " extraction camp was founded at " + n.pos.getX() + ", "
                    + n.pos.getZ() + " — the buildings rise when you next travel there";
        } catch (Throwable t) {
            return "the founding party got lost in the paperwork (" + t.getClass().getSimpleName() + ")";
        }
    }

    private static String surveyFinding(int type) {
        String[] finds = {
                "Rich game trails to the north; good hunting grounds.",
                "Fertile flats suitable for new farmland; a river bends nearby.",
                "Ore-bearing hills flagged for a future quarry.",
                "Uncharted woodland and a ravine worth a bridge.",
                "A viable road corridor with gentle grade.",
                "Solid bedrock and clay — good foundations here."};
        return finds[Math.max(0, Math.min(type, finds.length - 1))];
    }

    /** Remove up to {@code n} of the player's modern-citizen NPCs near the settlement (mission losses). */
    private static void cullCitizens(WorldServer world, Mission m, int n) {
        int removed = 0;
        for (net.minecraft.entity.Entity e : new ArrayList<>(world.loadedEntityList)) {
            if (removed >= n) break;
            if (e instanceof studio.ERM.war.entities.EntityModernCitizen && !e.isDead) {
                e.setDead();
                removed++;
            }
        }
    }

    /**
     * Deposit the haul into the WAREHOUSE (then KITCHEN) depot — the party returns to the settlement.
     * NO FORCE-LOADING: a LOADED depot receives the goods directly (draining any earlier queued mail
     * first); an UNLOADED depot gets the delivery written into the persistent DEPOT INBOX
     * ({@link DepotInboxData}) — the district data is the ledger, and the physical chest fills the
     * moment its chunk loads naturally (TileEntityDistrictMarker drains its inbox in onLoad).
     * Returns the depot pos, or null only when there is genuinely no warehouse/kitchen depot (or
     * every loaded one is full).
     */
    private static BlockPos deliver(WorldServer world, ItemStack haul) {
        CivilPlanData plan = CivilPlanData.get(world);
        // Pass 1: loaded depots take the goods physically, right now.
        for (int kind : new int[]{CivilMarker.WAREHOUSE, CivilMarker.KITCHEN}) {
            for (CivilMarker mk : plan.markers) {
                if (mk.kind != kind || !mk.hasDepot()) continue;
                if (!world.isBlockLoaded(mk.depotPos, false)) continue;
                net.minecraft.tileentity.TileEntity te = world.getTileEntity(mk.depotPos);
                if (!(te instanceof TileEntityDistrictMarker)) continue;
                DepotInboxData.get(world).drainInto(mk.depotPos, ((TileEntityDistrictMarker) te).depot);
                ItemStack left = ItemHandlerHelper.insertItemStacked(
                        ((TileEntityDistrictMarker) te).depot, haul.copy(), false);
                if (left.isEmpty()) return mk.depotPos;
            }
        }
        // Pass 2: no loaded depot took it — write it to the first UNLOADED depot's inbox instead.
        for (int kind : new int[]{CivilMarker.WAREHOUSE, CivilMarker.KITCHEN}) {
            for (CivilMarker mk : plan.markers) {
                if (mk.kind != kind || !mk.hasDepot()) continue;
                if (world.isBlockLoaded(mk.depotPos, false)) continue;
                if (DepotInboxData.get(world).queue(mk.depotPos, haul)) return mk.depotPos;
            }
        }
        return null;
    }
}
