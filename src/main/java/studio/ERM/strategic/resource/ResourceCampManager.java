package studio.ERM.strategic.resource;

import net.minecraft.entity.Entity;
import net.minecraft.entity.passive.EntityVillager;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.item.ItemStack;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.text.TextComponentString;
import net.minecraft.util.text.TextFormatting;
import net.minecraft.world.WorldServer;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import net.minecraftforge.fml.common.gameevent.TickEvent;
import net.minecraftforge.items.ItemHandlerHelper;
import studio.ERM.EpochRunnerMod;
import studio.ERM.strategic.Aw2Structures;
import studio.ERM.strategic.StrategicConvoy;
import studio.ERM.strategic.StrategicMapData;
import studio.ERM.strategic.StrategicObject;
import studio.ERM.strategic.civil.CivilMarker;
import studio.ERM.strategic.civil.CivilPlanData;
import studio.ERM.strategic.civil.DepotInboxData;
import studio.ERM.war.BattleManagers.entities.EntitySoldier;
import studio.ERM.war.config.SchematicCatalog;
import studio.ERM.war.districts.TileEntityDistrictMarker;
import studio.ERM.war.rival.RivalCityManager;
import studio.ERM.war.rival.RivalCityState;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.UUID;

/**
 * STRATEGIC RESOURCE EXPANSION — the frontier between civilizations. The Rival does not found new
 * cities; it establishes EXTRACTION CAMPS on discovered resource nodes, capped and slow by design,
 * and the player competes for the same map ({@link ResourceNodeData}).
 *
 *   RECON       — every eval the rival "discovers" its nearest unknown node (recon abstraction)
 *   EXPANSION   — every few Minecraft days: if camps < cap(level) and a claimable node is known,
 *                 a real {@link StrategicConvoy} departs the rival city (visible on the strategic
 *                 map, materializes on the road) and founds the camp ON ARRIVAL
 *   CONSTRUCTION— a founded camp is PENDING until its chunk loads NATURALLY, then the AW2 schematic
 *                 appears (never force-loaded; the strategic data is the truth, blocks are the
 *                 projection). No tents, ever: template via catalog + keyword fallback.
 *   PRODUCTION  — per Minecraft day into camp storage, depleting the finite reserve; exhausted
 *                 camps are abandoned and the frontier moves. PLAYER camps ship output to the
 *                 warehouse through the depot inbox.
 *   CAMP LIFE   — guards + workers materialize when a player is near, despawn when far. Killing a
 *                 rival camp's guards CAPTURES it: ownership flips and production reroutes.
 */
public final class ResourceCampManager {

    // Camp tuning now lives in config/Homosapien/camps.json (CampConfig); these are engine-cadence
    // constants only.
    private static final int PASS_INTERVAL = 200;            // 10s manager cadence
    private static final double STAFF_RANGE = 96.0;          // player within -> staff materializes
    private static final double STAFF_DESPAWN_RANGE = 140.0;

    private ResourceCampManager() {}

    private static final Random RNG = new Random();
    private static int tickCounter = 0;

    /** Transient live staff per camp uid (guards tracked separately for the capture check). */
    private static final class CampStaff {
        final List<UUID> guards = new ArrayList<>();
        final List<UUID> workers = new ArrayList<>();
    }
    private static final Map<Integer, CampStaff> STAFF = new HashMap<>();

    public static void resetTransients() { STAFF.clear(); }

    @SubscribeEvent
    public static void onWorldTick(TickEvent.WorldTickEvent e) {
        if (e.phase != TickEvent.Phase.END || e.side.isClient() || !(e.world instanceof WorldServer)) return;
        if (++tickCounter % PASS_INTERVAL != 0) return;
        WorldServer world = (WorldServer) e.world;
        try {
            ResourceNodeData data = ResourceNodeData.get(world);
            data.ensureSeeded(world);
            handleConvoyArrivals(world, data);
            reclaimStrandedClaims(world, data);
            rivalExpansion(world, data);
            construction(world, data);
            production(world, data);
            dispatchTeamsters(world, data);
            campLife(world, data);
        } catch (Throwable t) {
            EpochRunnerMod.logger.error("[Camps] pass failed (guarded)", t);
        }
    }

    // ------------------------------------------------------------------
    //  RIVAL EXPANSION
    // ------------------------------------------------------------------

    private static void rivalExpansion(WorldServer world, ResourceNodeData data) {
        long day = world.getTotalWorldTime() / 24000L;
        if (data.lastRivalEvalDay >= 0
                && day - data.lastRivalEvalDay < studio.ERM.war.config.CampConfig.data.evalEveryDays) return;
        RivalCityState city = RivalCityManager.getAnyCity(world);
        if (city == null || city.center == null) return; // no rival civilization yet
        data.lastRivalEvalDay = day;
        data.markDirty();
        int level = Math.max(1, city.level);

        // RECON: the rival's patrols have been out — it learns its nearest unknown node each eval.
        ResourceNodeData.Node scouted = data.nearestUndiscovered(city.center.getX(), city.center.getZ(), false, 1400);
        if (scouted != null) {
            scouted.rivalKnown = true;
            data.markDirty();
            EpochRunnerMod.logger.info("[Camps] rival recon discovered the " + scouted.typeName()
                    + " deposit @ " + scouted.pos.getX() + "," + scouted.pos.getZ());
        }

        // AUTO-UPGRADE: one established camp per eval grows while its reserve justifies the works
        // ("claim pressure" — the footprint + garrison + output all step with the level).
        if (studio.ERM.war.config.CampConfig.data.rivalAutoUpgrade) {
            for (ResourceNodeData.Node n : data.nodes) {
                if (n.owner != ResourceNodeData.OWNER_RIVAL || n.campState != ResourceNodeData.CAMP_BUILT) continue;
                int ceiling = Math.min(studio.ERM.war.config.CampConfig.data.maxCampLevel, 1 + level / 3);
                if (n.level >= ceiling) continue;
                if (n.remaining < n.reserve / 3) continue; // don't invest in a dying pit
                upgradeCamp(world, data, n, "rival");
                break;
            }
        }

        // EXPANSION: capped, slow, one convoy per eval.
        int cap = studio.ERM.war.config.CampConfig.maxRivalCamps(level);
        if (data.activeCamps(ResourceNodeData.OWNER_RIVAL) >= cap) return;
        ResourceNodeData.Node target = data.nearestClaimable(city.center.getX(), city.center.getZ(), false, 1400);
        if (target == null) return;
        // One convoy per node: skip if one is already en route.
        for (StrategicObject o : StrategicMapData.get(world).objects.values()) {
            if (o instanceof StrategicConvoy && ((StrategicConvoy) o).targetNodeUid == target.uid) return;
        }

        target.owner = ResourceNodeData.OWNER_RIVAL; // claimed at dispatch (reverted if the convoy dies)
        data.markDirty();
        StrategicConvoy convoy = new StrategicConvoy();
        convoy.targetNodeUid = target.uid;
        convoy.warLevel = level;
        convoy.strength = 5 + level / 3;
        convoy.route.add(new BlockPos(city.center.getX(), 0, city.center.getZ()));
        convoy.route.add(new BlockPos(target.pos.getX(), 0, target.pos.getZ()));
        convoy.x = city.center.getX() + 0.5;
        convoy.z = city.center.getZ() + 0.5;
        StrategicMapData.get(world).add(convoy);
        EpochRunnerMod.logger.info("[Camps] rival engineer convoy departed for the " + target.typeName()
                + " deposit @ " + target.pos.getX() + "," + target.pos.getZ()
                + " (" + data.activeCamps(ResourceNodeData.OWNER_RIVAL) + "/" + cap + " camps)");
    }

    /** Convoys holding at their destinations resolve their missions and retire: engineers FOUND the
     *  camp; teamsters DELIVER their cargo (player camps -> the warehouse; rival camps -> the city). */
    private static void handleConvoyArrivals(WorldServer world, ResourceNodeData data) {
        StrategicMapData map = StrategicMapData.get(world);
        for (StrategicObject o : new ArrayList<>(map.objects.values())) {
            if (!(o instanceof StrategicConvoy)) continue;
            StrategicConvoy c = (StrategicConvoy) o;
            if (!c.arrived()) continue;
            if ("BUILD_CAMP".equals(c.purpose)) {
                ResourceNodeData.Node n = data.byUid(c.targetNodeUid);
                if (c.materialized) c.dematerialize(world);
                map.remove(c.id);
                if (n == null || n.campState != ResourceNodeData.CAMP_NONE) continue;
                n.campState = ResourceNodeData.CAMP_PENDING;
                data.markDirty();
                EpochRunnerMod.logger.info("[Camps] convoy arrived — " + n.typeName()
                        + " camp founded @ " + n.pos.getX() + "," + n.pos.getZ() + " (construction begins)");
            } else if ("TEAMSTER".equals(c.purpose)) {
                if (c.materialized) c.dematerialize(world);
                map.remove(c.id);
                ResourceNodeData.Node n = data.byUid(c.targetNodeUid);
                boolean playerCargo = n != null && n.owner == ResourceNodeData.OWNER_PLAYER;
                if (c.cargoUnits > 0 && playerCargo) {
                    deliverUnitsToWarehouse(world, c.cargoType, c.cargoUnits);
                    EpochRunnerMod.logger.info("[Camps] teamster delivered " + c.cargoUnits + " "
                            + ResourceNodeData.TYPE_NAMES[Math.min(c.cargoType, ResourceNodeData.TYPE_NAMES.length - 1)]
                            + " unit(s) to the warehouse");
                } else if (c.cargoUnits > 0) {
                    // Rival cargo vanishes into the rival economy (abstract for now).
                    EpochRunnerMod.logger.info("[Camps] rival teamster brought " + c.cargoUnits
                            + " unit(s) home to the city");
                }
            }
        }
    }

    /** A node claimed at dispatch whose convoy DIED en route reverts to unclaimed (retry later). */
    private static void reclaimStrandedClaims(WorldServer world, ResourceNodeData data) {
        StrategicMapData map = StrategicMapData.get(world);
        for (ResourceNodeData.Node n : data.nodes) {
            if (n.owner != ResourceNodeData.OWNER_RIVAL || n.campState != ResourceNodeData.CAMP_NONE) continue;
            boolean enRoute = false;
            for (StrategicObject o : map.objects.values()) {
                if (o instanceof StrategicConvoy && ((StrategicConvoy) o).targetNodeUid == n.uid) { enRoute = true; break; }
            }
            if (!enRoute) {
                n.owner = ResourceNodeData.OWNER_NONE; // the expedition was lost on the road
                data.markDirty();
                EpochRunnerMod.logger.info("[Camps] the convoy to the " + n.typeName()
                        + " deposit was lost — claim released");
            }
        }
    }

    // ------------------------------------------------------------------
    //  CONSTRUCTION — the schematic appears when the world arrives
    // ------------------------------------------------------------------

    private static void construction(WorldServer world, ResourceNodeData data) {
        for (ResourceNodeData.Node n : data.nodes) {
            if (n.campState != ResourceNodeData.CAMP_PENDING) continue;
            if (!world.isBlockLoaded(n.pos, false)) continue; // waits for natural loading — no force-load
            BlockPos at = Aw2Structures.surfaceNear(world, n.pos, 4);
            if (at == null) at = Aw2Structures.surfaceNear(world, n.pos, 12);
            String tmpl = Aw2Structures.pick(SchematicCatalog.OUTPOSTS_CAMPS, null, campKeywordsFor(n.type));
            boolean placed = at != null && tmpl != null && Aw2Structures.placeRandomFacing(world, tmpl, at);
            if (placed && n.owner == ResourceNodeData.OWNER_RIVAL) {
                // Rebrand the template's ADVANCED SPAWNERS to a faction this install actually has
                // (the rival's own) — pack spawner NBT can carry unresolvable factions, the exact
                // "Ticking entity" NPE source. The join-time crash guard is the backstop.
                try {
                    String faction = studio.ERM.war.util.Aw2FactionCrashGuard.repairName();
                    if (faction != null) {
                        studio.ERM.war.util.AdvancedSpawnerFactionSwapper.swapSpawnerFactionsNear(
                                world, at, 40, -8, 40, faction);
                    }
                } catch (Throwable ignored) {}
            }
            // Even if AW2 had nothing to offer, the camp still OPERATES (strategic truth first);
            // a later pass retries placement in case packs load templates lazily.
            if (placed) {
                n.template = tmpl;
                n.campState = ResourceNodeData.CAMP_BUILT;
                data.markDirty();
                claimCampChunks(world, n); // the frontier shows on the map the moment the camp stands
                String owner = n.owner == ResourceNodeData.OWNER_PLAYER ? "Your" : "The rival's";
                for (EntityPlayer p : world.playerEntities) {
                    p.sendMessage(new TextComponentString(TextFormatting.GOLD + owner + " "
                            + n.typeName() + " extraction camp" + TextFormatting.GRAY + " has been raised at "
                            + n.pos.getX() + ", " + n.pos.getZ() + "."));
                }
            } else if (Aw2Structures.loaded().isEmpty()) {
                // AW2 absent entirely: run the camp as data-only (no structure, never a tent).
                n.campState = ResourceNodeData.CAMP_BUILT;
                data.markDirty();
                claimCampChunks(world, n);
            }
        }
        // UPGRADE STRUCTURES: levels bought while the area was unloaded appear when the world does.
        for (ResourceNodeData.Node n : data.nodes) {
            if (n.pendingStructures <= 0 || n.campState != ResourceNodeData.CAMP_BUILT) continue;
            if (!world.isBlockLoaded(n.pos, false)) continue;
            BlockPos at = Aw2Structures.surfaceNear(world, n.pos, 10 + n.level * 4);
            String tmpl = Aw2Structures.pick(SchematicCatalog.OUTPOSTS_CAMPS, null, campKeywordsFor(n.type));
            if (at != null && tmpl != null && Aw2Structures.placeRandomFacing(world, tmpl, at)) {
                n.pendingStructures--;
                data.markDirty();
                EpochRunnerMod.logger.info("[Camps] upgrade structure raised at the " + n.typeName()
                        + " camp (level " + n.level + ")");
            }
        }
    }

    /**
     * CLAIM PRESSURE: a standing camp claims chunks around itself — radius per camp level from
     * CampConfig ("configurable target chunks"). Rival camps push RIVAL territory onto the map;
     * player camps push PLAYER territory. Only NEUTRAL chunks flip (no silent land theft).
     */
    private static void claimCampChunks(WorldServer world, ResourceNodeData.Node n) {
        try {
            studio.ERM.war.world.WarWorldData war = studio.ERM.war.world.WarWorldData.get(world);
            String owner = n.owner == ResourceNodeData.OWNER_PLAYER ? "PLAYER" : "RIVAL";
            int r = studio.ERM.war.config.CampConfig.claimRadiusChunks(n.level);
            net.minecraft.util.math.ChunkPos c = new net.minecraft.util.math.ChunkPos(n.pos);
            int flipped = 0;
            for (int dx = -r; dx <= r; dx++) {
                for (int dz = -r; dz <= r; dz++) {
                    net.minecraft.util.math.ChunkPos p = new net.minecraft.util.math.ChunkPos(c.x + dx, c.z + dz);
                    if ("NEUTRAL".equals(war.getOwner(p))) { war.setOwner(p, owner); flipped++; }
                }
            }
            if (flipped > 0)
                EpochRunnerMod.logger.info("[Camps] " + owner + " camp claimed " + flipped
                        + " chunk(s) (level " + n.level + ", r=" + r + ")");
        } catch (Throwable ignored) {}
    }

    /**
     * CAMP UPGRADE — shared by /war camp grow, the rival's auto-upgrade, and (later) upgrade GUIs:
     * +1 level within the config ceiling; production, garrison, and the claim footprint all scale,
     * and one more AW2 structure is queued to physically appear when the area is loaded.
     */
    public static boolean upgradeCamp(WorldServer world, ResourceNodeData data,
                                      ResourceNodeData.Node n, String by) {
        if (n == null || !n.hasActiveCamp()) return false;
        if (n.level >= studio.ERM.war.config.CampConfig.data.maxCampLevel) return false;
        n.level++;
        n.pendingStructures++;
        data.markDirty();
        claimCampChunks(world, n);
        despawnStaff(world, n.uid); // re-materializes at the new garrison size when next approached
        EpochRunnerMod.logger.info("[Camps] " + n.typeName() + " camp upgraded to level " + n.level
                + " (by " + by + ")");
        for (EntityPlayer p : world.playerEntities) {
            p.sendMessage(new TextComponentString(TextFormatting.GOLD
                    + (n.owner == ResourceNodeData.OWNER_PLAYER ? "Your " : "The rival's ")
                    + n.typeName() + " camp" + TextFormatting.GRAY + " grew to level " + n.level + "."));
        }
        return true;
    }

    /** Template keyword pools per resource type — matched against whatever AW2 actually loaded. */
    private static String[] campKeywordsFor(int type) {
        switch (type) {
            case ResourceNodeData.IRON:
            case ResourceNodeData.COPPER:
            case ResourceNodeData.COAL:   return new String[]{"mine", "mining", "quarry", "camp", "outpost", "fort"};
            case ResourceNodeData.GOLD:   return new String[]{"mine", "vault", "camp", "outpost", "tower"};
            case ResourceNodeData.OIL:    return new String[]{"pump", "refinery", "industry", "camp", "outpost", "workshop"};
            case ResourceNodeData.TIMBER: return new String[]{"lumber", "saw", "logging", "wood", "camp", "cabin", "hut"};
            case ResourceNodeData.STONE:
            case ResourceNodeData.GRAVEL: return new String[]{"quarry", "mason", "stone", "camp", "outpost", "yard"};
            default:                      return new String[]{"camp", "outpost", "post", "hut", "house"};
        }
    }

    // ------------------------------------------------------------------
    //  PRODUCTION — finite reserves, daily output, frontier drift
    // ------------------------------------------------------------------

    private static void production(WorldServer world, ResourceNodeData data) {
        long day = world.getTotalWorldTime() / 24000L;
        for (ResourceNodeData.Node n : data.nodes) {
            if (!n.hasActiveCamp() || n.owner == ResourceNodeData.OWNER_NONE) continue;
            if (n.lastProdDay == day) continue;
            n.lastProdDay = day;
            int level = 1;
            try {
                RivalCityState city = RivalCityManager.getAnyCity(world);
                if (city != null) level = Math.max(1, city.level);
            } catch (Throwable ignored) {}
            double campScale = 1.0 + studio.ERM.war.config.CampConfig.data.productionPerCampLevel * (n.level - 1);
            long produce = Math.min(n.remaining,
                    Math.round(n.ratePerDay * (1.0 + 0.15 * (level - 1)) * campScale));
            n.remaining -= produce;
            n.storedOutput += produce;
            data.markDirty();
            // Output now travels by TEAMSTER (dispatchTeamsters) — visible logistics, raidable cargo.
            if (n.remaining <= 0) {
                n.campState = ResourceNodeData.CAMP_EXHAUSTED;
                data.markDirty();
                despawnStaff(world, n.uid);
                for (EntityPlayer p : world.playerEntities) {
                    p.sendMessage(new TextComponentString(TextFormatting.GRAY + "The " + n.typeName()
                            + " deposit at " + n.pos.getX() + ", " + n.pos.getZ()
                            + " is exhausted — the camp stands abandoned."));
                }
                EpochRunnerMod.logger.info("[Camps] " + n.typeName() + " deposit exhausted @ "
                        + n.pos.getX() + "," + n.pos.getZ() + " — frontier moves on");
            }
        }
    }

    /**
     * MILITARY TEAMSTERS — the visible logistics leg: every few days a teamster convoy departs a
     * producing camp with its stored output in the cart and hauls it home (player camps -> the
     * warehouse; rival camps -> the rival city). The cargo rides the strategic map: ambush the
     * convoy and the goods are LOST — camps are worth raiding.
     */
    private static void dispatchTeamsters(WorldServer world, ResourceNodeData data) {
        long day = world.getTotalWorldTime() / 24000L;
        studio.ERM.war.config.CampConfig.ConfigData cfg = studio.ERM.war.config.CampConfig.data;
        for (ResourceNodeData.Node n : data.nodes) {
            if (n.campState != ResourceNodeData.CAMP_BUILT || n.owner == ResourceNodeData.OWNER_NONE) continue;
            if (n.storedOutput < cfg.teamsterMinUnits) continue;
            if (n.lastTeamsterDay >= 0 && day - n.lastTeamsterDay < cfg.teamsterEveryDays) continue;

            BlockPos home = null;
            if (n.owner == ResourceNodeData.OWNER_PLAYER) {
                CivilPlanData plan = CivilPlanData.get(world);
                for (int kind : new int[]{CivilMarker.WAREHOUSE, CivilMarker.KITCHEN}) {
                    for (CivilMarker mk : plan.markers) {
                        if (mk.kind == kind && mk.hasDepot()) { home = mk.depotPos; break; }
                    }
                    if (home != null) break;
                }
            } else {
                RivalCityState city = RivalCityManager.getAnyCity(world);
                if (city != null) home = city.center;
            }
            if (home == null) continue; // nowhere to haul to yet — output keeps stacking

            // One teamster per camp at a time.
            boolean enRoute = false;
            for (StrategicObject o : StrategicMapData.get(world).objects.values()) {
                if (o instanceof StrategicConvoy && "TEAMSTER".equals(((StrategicConvoy) o).purpose)
                        && ((StrategicConvoy) o).targetNodeUid == n.uid) { enRoute = true; break; }
            }
            if (enRoute) continue;

            StrategicConvoy t = new StrategicConvoy();
            t.purpose = "TEAMSTER";
            t.targetNodeUid = n.uid;
            t.cargoType = n.type;
            t.cargoUnits = n.storedOutput;
            t.warLevel = Math.max(1, n.level + 1);
            t.strength = 3 + n.level; // driver + handlers + a level-scaled escort
            t.route.add(new BlockPos(n.pos.getX(), 0, n.pos.getZ()));
            t.route.add(new BlockPos(home.getX(), 0, home.getZ()));
            t.x = n.pos.getX() + 0.5;
            t.z = n.pos.getZ() + 0.5;
            n.storedOutput = 0;   // the goods are IN THE CART now — lose the convoy, lose the load
            n.lastTeamsterDay = day;
            data.markDirty();
            StrategicMapData.get(world).add(t);
            EpochRunnerMod.logger.info("[Camps] teamster departed the " + n.typeName() + " camp with "
                    + t.cargoUnits + " unit(s) -> " + home.getX() + "," + home.getZ());
        }
    }

    /** Teamster arrival (player cargo): units -> real items -> the warehouse depot (loaded: direct
     *  + inbox drain; unloaded: the depot inbox ledger). Called from handleConvoyArrivals. */
    private static void deliverUnitsToWarehouse(WorldServer world, int type, long units) {
        int upi = studio.ERM.war.config.CampConfig.data.unitsPerItem;
        long remaining = units;
        CivilPlanData plan = CivilPlanData.get(world);
        while (remaining >= upi) {
            int items = (int) Math.min(64, remaining / upi);
            ItemStack stack = outputItemFor(type, items);
            if (stack.isEmpty()) return;
            boolean moved = false;
            for (int kind : new int[]{CivilMarker.WAREHOUSE, CivilMarker.KITCHEN}) {
                for (CivilMarker mk : plan.markers) {
                    if (mk.kind != kind || !mk.hasDepot()) continue;
                    if (world.isBlockLoaded(mk.depotPos, false)) {
                        net.minecraft.tileentity.TileEntity te = world.getTileEntity(mk.depotPos);
                        if (!(te instanceof TileEntityDistrictMarker)) continue;
                        DepotInboxData.get(world).drainInto(mk.depotPos, ((TileEntityDistrictMarker) te).depot);
                        ItemStack left = ItemHandlerHelper.insertItemStacked(
                                ((TileEntityDistrictMarker) te).depot, stack, false);
                        int deliveredItems = items - (left.isEmpty() ? 0 : left.getCount());
                        if (deliveredItems > 0) { remaining -= (long) deliveredItems * upi; moved = true; }
                    } else if (DepotInboxData.get(world).queue(mk.depotPos, stack)) {
                        remaining -= (long) items * upi;
                        moved = true;
                    }
                    if (moved) break;
                }
                if (moved) break;
            }
            if (!moved) return; // everything full — the rest of the load is written off
        }
    }

    /** Resource type -> the delivered item (vanilla stand-ins; config lift later). */
    private static ItemStack outputItemFor(int type, int count) {
        switch (type) {
            case ResourceNodeData.IRON:   return new ItemStack(net.minecraft.init.Blocks.IRON_ORE, count);
            case ResourceNodeData.COAL:   return new ItemStack(net.minecraft.init.Items.COAL, count);
            case ResourceNodeData.COPPER: return new ItemStack(net.minecraft.init.Items.IRON_NUGGET, count);
            case ResourceNodeData.GOLD:   return new ItemStack(net.minecraft.init.Blocks.GOLD_ORE, count);
            case ResourceNodeData.OIL:    return new ItemStack(net.minecraft.init.Items.COAL, count); // crude stand-in
            case ResourceNodeData.TIMBER: return new ItemStack(net.minecraft.init.Blocks.LOG, count);
            case ResourceNodeData.STONE:  return new ItemStack(net.minecraft.init.Blocks.STONE, count);
            case ResourceNodeData.GRAVEL: return new ItemStack(net.minecraft.init.Blocks.GRAVEL, count);
            default:                      return ItemStack.EMPTY;
        }
    }

    // ------------------------------------------------------------------
    //  CAMP LIFE — staff materializes near players; capture flips ownership
    // ------------------------------------------------------------------

    private static void campLife(WorldServer world, ResourceNodeData data) {
        for (ResourceNodeData.Node n : data.nodes) {
            if (n.campState != ResourceNodeData.CAMP_BUILT || n.owner == ResourceNodeData.OWNER_NONE) continue;
            EntityPlayer near = world.getClosestPlayer(n.pos.getX() + 0.5, n.pos.getY(), n.pos.getZ() + 0.5,
                    STAFF_DESPAWN_RANGE, false);
            CampStaff staff = STAFF.get(n.uid);

            if (near == null) {
                if (staff != null) despawnStaff(world, n.uid);
                continue;
            }
            double dist = Math.sqrt(near.getDistanceSq(n.pos));

            // CAPTURE CHECK (rival camps): guards were up and are now all dead -> the player took it.
            // Only judged while the camp CHUNK IS LOADED: an unloaded guard also reads as null from
            // getEntityFromUuid, and that must never count as a kill (no capture-by-walking-away).
            if (n.owner == ResourceNodeData.OWNER_RIVAL && staff != null && !staff.guards.isEmpty()
                    && world.isBlockLoaded(n.pos, false)) {
                boolean anyAlive = false;
                for (UUID u : staff.guards) {
                    Entity g = world.getEntityFromUuid(u);
                    if (g != null && !g.isDead) { anyAlive = true; break; }
                }
                if (!anyAlive) {
                    n.owner = ResourceNodeData.OWNER_PLAYER;
                    data.markDirty();
                    despawnStaff(world, n.uid); // the rival's workers scatter
                    near.sendMessage(new TextComponentString(TextFormatting.GOLD + "Camp captured!"
                            + TextFormatting.GRAY + " The " + n.typeName()
                            + " camp works for you now — output flows to your warehouse."));
                    EpochRunnerMod.logger.info("[Camps] PLAYER CAPTURED the " + n.typeName()
                            + " camp @ " + n.pos.getX() + "," + n.pos.getZ());
                    continue;
                }
            }

            // MATERIALIZE staff when close and none are up yet.
            if (staff == null && dist <= STAFF_RANGE && world.isBlockLoaded(n.pos, false)) {
                spawnStaff(world, data, n);
            }
        }
    }

    private static void spawnStaff(WorldServer world, ResourceNodeData data, ResourceNodeData.Node n) {
        CampStaff staff = new CampStaff();
        int level = 1;
        try {
            RivalCityState city = RivalCityManager.getAnyCity(world);
            if (city != null) level = Math.max(1, city.level);
        } catch (Throwable ignored) {}

        if (n.owner == ResourceNodeData.OWNER_RIVAL) {
            int guards = 4 + level / 3 + (n.level - 1) * 2; // upgrades harden the garrison
            for (int i = 0; i < guards; i++) {
                BlockPos at = Aw2Structures.surfaceNear(world, n.pos, 6);
                if (at == null) continue;
                EntitySoldier s = new EntitySoldier(world);
                s.setLocationAndAngles(at.getX() + 0.5, at.getY() + 1.0, at.getZ() + 0.5,
                        RNG.nextFloat() * 360F, 0F);
                s.setTeam_("empire");
                s.configure(level, (i % 2 == 0) ? "RANGED" : "MELEE", "");
                try { studio.ERM.war.skins.SkinPoolManager.applySkinForRivalLevel(s, level, RNG); } catch (Throwable ignored) {}
                s.setStrategicManaged(true);
                s.getEntityData().setString("erm_camp", String.valueOf(n.uid));
                s.setMarchObjective(n.pos); // hold the camp, don't chase the horizon
                staff.guards.add(s.getUniqueID());
                world.spawnEntity(s);
            }
        }
        int workers = 2 + RNG.nextInt(2);
        for (int i = 0; i < workers; i++) {
            BlockPos at = Aw2Structures.surfaceNear(world, n.pos, 8);
            if (at == null) continue;
            EntityVillager v = new EntityVillager(world);
            v.setLocationAndAngles(at.getX() + 0.5, at.getY() + 1.0, at.getZ() + 0.5,
                    RNG.nextFloat() * 360F, 0F);
            v.getEntityData().setString("erm_camp", String.valueOf(n.uid));
            try {
                v.setHeldItem(net.minecraft.util.EnumHand.MAIN_HAND,
                        new ItemStack(net.minecraft.init.Items.IRON_PICKAXE));
            } catch (Throwable ignored) {}
            staff.workers.add(v.getUniqueID());
            world.spawnEntity(v);
        }
        STAFF.put(n.uid, staff);
        EpochRunnerMod.logger.info("[Camps] staff up at the " + n.typeName() + " camp ("
                + staff.guards.size() + " guards, " + staff.workers.size() + " workers)");
    }

    private static void despawnStaff(WorldServer world, int uid) {
        CampStaff staff = STAFF.remove(uid);
        if (staff == null) return;
        for (List<UUID> group : java.util.Arrays.asList(staff.guards, staff.workers)) {
            for (UUID u : group) {
                Entity e = world.getEntityFromUuid(u);
                if (e != null && !e.isDead) { try { e.setDead(); } catch (Throwable ignored) {} }
            }
        }
    }
}
