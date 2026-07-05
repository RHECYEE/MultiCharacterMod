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

    // Tuning (config lift later — grouped here deliberately).
    private static final int PASS_INTERVAL = 200;            // 10s manager cadence
    private static final int EVAL_EVERY_DAYS = 6;            // rival expansion evaluation period
    private static final int BASE_MAX_CAMPS = 3;             // + rivalLevel/3
    private static final double STAFF_RANGE = 96.0;          // player within -> staff materializes
    private static final double STAFF_DESPAWN_RANGE = 140.0;
    private static final int UNITS_PER_ITEM = 8;             // strategic units -> one delivered item

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
        if (data.lastRivalEvalDay >= 0 && day - data.lastRivalEvalDay < EVAL_EVERY_DAYS) return;
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

        // EXPANSION: capped, slow, one convoy per eval.
        int cap = BASE_MAX_CAMPS + level / 3;
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

    /** A BUILD_CAMP convoy holding at its destination founds the camp and retires. */
    private static void handleConvoyArrivals(WorldServer world, ResourceNodeData data) {
        StrategicMapData map = StrategicMapData.get(world);
        for (StrategicObject o : new ArrayList<>(map.objects.values())) {
            if (!(o instanceof StrategicConvoy)) continue;
            StrategicConvoy c = (StrategicConvoy) o;
            if (!"BUILD_CAMP".equals(c.purpose) || !c.arrived()) continue;
            ResourceNodeData.Node n = data.byUid(c.targetNodeUid);
            if (c.materialized) c.dematerialize(world);
            map.remove(c.id);
            if (n == null || n.campState != ResourceNodeData.CAMP_NONE) continue;
            n.campState = ResourceNodeData.CAMP_PENDING;
            data.markDirty();
            EpochRunnerMod.logger.info("[Camps] convoy arrived — " + n.typeName()
                    + " camp founded @ " + n.pos.getX() + "," + n.pos.getZ() + " (construction begins)");
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
            // Even if AW2 had nothing to offer, the camp still OPERATES (strategic truth first);
            // a later pass retries placement in case packs load templates lazily.
            if (placed) {
                n.template = tmpl;
                n.campState = ResourceNodeData.CAMP_BUILT;
                data.markDirty();
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
            }
        }
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
            long produce = Math.min(n.remaining, Math.round(n.ratePerDay * (1.0 + 0.15 * (level - 1))));
            n.remaining -= produce;
            n.storedOutput += produce;
            data.markDirty();

            if (n.owner == ResourceNodeData.OWNER_PLAYER && n.storedOutput >= UNITS_PER_ITEM) {
                deliverPlayerOutput(world, n);
            }
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

    /** Player camp output -> real items -> the warehouse depot (loaded: direct; unloaded: the inbox). */
    private static void deliverPlayerOutput(WorldServer world, ResourceNodeData.Node n) {
        int items = (int) Math.min(64, n.storedOutput / UNITS_PER_ITEM);
        if (items <= 0) return;
        ItemStack stack = outputItemFor(n.type, items);
        if (stack.isEmpty()) return;
        CivilPlanData plan = CivilPlanData.get(world);
        for (int kind : new int[]{CivilMarker.WAREHOUSE, CivilMarker.KITCHEN}) {
            for (CivilMarker mk : plan.markers) {
                if (mk.kind != kind || !mk.hasDepot()) continue;
                if (world.isBlockLoaded(mk.depotPos, false)) {
                    net.minecraft.tileentity.TileEntity te = world.getTileEntity(mk.depotPos);
                    if (!(te instanceof TileEntityDistrictMarker)) continue;
                    DepotInboxData.get(world).drainInto(mk.depotPos, ((TileEntityDistrictMarker) te).depot);
                    ItemStack left = ItemHandlerHelper.insertItemStacked(
                            ((TileEntityDistrictMarker) te).depot, stack, false);
                    // Deduct EXACTLY what landed in the chest — a partial fit must not ship again
                    // tomorrow (that would duplicate the inserted portion). The rest waits at camp.
                    int deliveredItems = items - (left.isEmpty() ? 0 : left.getCount());
                    if (deliveredItems > 0) n.storedOutput -= (long) deliveredItems * UNITS_PER_ITEM;
                    return;
                } else if (DepotInboxData.get(world).queue(mk.depotPos, stack)) {
                    n.storedOutput -= (long) items * UNITS_PER_ITEM; // queued in full to the ledger
                    return;
                } else {
                    return; // inbox guard full: output waits at the camp
                }
            }
        }
        // No warehouse anywhere: output simply accumulates at the camp until one exists.
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
            int guards = 4 + level / 3;
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
