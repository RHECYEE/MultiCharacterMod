package studio.ERM.strategic;

import net.minecraft.entity.Entity;
import net.minecraft.inventory.EntityEquipmentSlot;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.nbt.NBTTagList;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.WorldServer;
import studio.ERM.EpochRunnerMod;
import studio.ERM.war.BattleManagers.entities.EntitySoldier;

import java.util.ArrayList;
import java.util.UUID;

/**
 * PHASE 2 — a RECRUITED REINFORCEMENT marching to its rally point. Purchased units never appear
 * instantly: the contract spawns this strategic object far away (config arrivalDistanceBlocks) and it
 * WALKS to the rally — on the map while unloaded, a real marching soldier when the player watches.
 *
 * On ARRIVAL the soldier is finalized into the player's forces (team "militia", conscriptable by the
 * defense plan): PERMANENT recruits stay forever; MERCENARY contracts stamp an expiry
 * (erm_merc_expiry) and the company departs when it lapses or is dismissed.
 */
public class StrategicReinforcement extends StrategicObject {

    public int contract = 1; // 0 = permanent recruitment, 1 = mercenary contract, 2 = permanent vehicle
    public NBTTagList gear = new NBTTagList(); // the loadout the player built (slot-indexed ItemStacks)
    public String vehicleShortName = "";       // vehicle contracts: the Flan ShortName being delivered

    // Stuck-recovery bookkeeping for VEHICLE deliveries (transient — recomputed after any reload):
    // Flan hull physics grinds into trees/slopes on raw terrain. Sampled every ~3s: no progress
    // while far from the goal earns a strike and an 8-block unstick hop toward the goal; repeated
    // strikes park the hull beside the rally instead ("takes forever / gets stuck in terrain").
    private double stallX, stallZ;
    private int stallStrikes = 0;
    private long stallSampleTick = 0;

    public StrategicReinforcement() {
        speed = 2.6;
        strength = 1;
        loopRoute = false; // one-way march to the rally
        faction = "militia";
    }

    @Override
    public String typeId() { return "reinforcement"; }

    @Override
    public String label() {
        if (contract == 2) return "Vehicle delivery (" + vehicleShortName + ")";
        return (contract == 0 ? "Recruit column" : "Mercenary convoy") + " (" + strength + ")";
    }

    @Override
    protected void spawnEntities(WorldServer world, BlockPos at, float yawDeg) {
        BlockPos g = surface(world, at.getX() + 0.5, at.getZ() + 0.5);

        // PERMANENT VEHICLE contract: the delivery is a militia-crewed Flan vehicle that DRIVES in.
        if (contract == 2) {
            try {
                studio.ERM.war.vehicle.EntityAIPilot pilot = new studio.ERM.war.vehicle.EntityAIPilot(world);
                pilot.setLocationAndAngles(at.getX() + 0.5, g.getY() + 1.0, at.getZ() + 0.5, yawDeg, 0F);
                pilot.setVehicleType(vehicleShortName);
                pilot.setMcmTeam("militia");
                pilot.setDeliveryMode(true);
                applyGearTo(pilot);
                pilot.getEntityData().setString("erm_strategic", id.toString());
                BlockPos wp = currentWaypoint();
                if (wp != null) pilot.setRallyPoint(surface(world, wp.getX() + 0.5, wp.getZ() + 0.5));
                entityIds.add(pilot.getUniqueID());
                world.spawnEntity(pilot);
            } catch (Throwable t) {
                EpochRunnerMod.logger.warn("[Recruit] vehicle delivery spawn failed: " + t);
            }
            return;
        }

        // SQUAD contracts: `strength` soldiers in a marching column, each wearing the built kit.
        java.util.Random rng = new java.util.Random(id.getLeastSignificantBits());
        double fx = Math.cos(facing), fz = Math.sin(facing);
        double px = -fz, pz = fx;
        int n = Math.max(1, strength);
        for (int i = 0; i < n; i++) {
            double lat = (i % 2 == 0) ? 0.9 : -0.9;
            double back = (i / 2) * 1.7;
            double sx = at.getX() + 0.5 + px * lat - fx * back;
            double sz = at.getZ() + 0.5 + pz * lat - fz * back;
            BlockPos sg = surface(world, sx, sz);
            EntitySoldier s = new EntitySoldier(world);
            s.setLocationAndAngles(sx, sg.getY() + 1.0, sz, yawDeg, 0F);
            s.setTeam_("militia");
            s.configure(3, "MELEE", "");
            try { studio.ERM.war.skins.SkinPoolManager.applySkinForRivalLevel(s, 3, rng); } catch (Throwable ignored) {}
            applyGear(s);
            s.setStrategicManaged(true);
            s.getEntityData().setString("erm_strategic", id.toString());
            BlockPos wp = currentWaypoint();
            if (wp != null) s.setMarchObjective(surface(world, wp.getX() + 0.5, wp.getZ() + 0.5));
            entityIds.add(s.getUniqueID());
            world.spawnEntity(s);
        }
    }

    /** Dress any living entity (the vehicle's crew look) in the built kit. */
    private void applyGearTo(net.minecraft.entity.EntityLiving e) {
        EntityEquipmentSlot[] slots = {
                EntityEquipmentSlot.MAINHAND, EntityEquipmentSlot.OFFHAND,
                EntityEquipmentSlot.HEAD, EntityEquipmentSlot.CHEST,
                EntityEquipmentSlot.LEGS, EntityEquipmentSlot.FEET };
        // Slot 0 is the VEHICLE ITEM on a vehicle contract -- skip it, start at offhand.
        for (int i = 1; i < gear.tagCount() && i < slots.length; i++) {
            try {
                ItemStack st = new ItemStack(gear.getCompoundTagAt(i));
                if (!st.isEmpty()) e.setItemStackToSlot(slots[i], st);
            } catch (Throwable ignored) {}
        }
    }

    /** True when a Flan item is a PLANE or HELICOPTER (Flan models both as PlaneType). Vehicle
     *  recruit contracts are ground-only: an aircraft "delivery" can't drive to the rally, so the
     *  contract must REFUSE the item instead of consuming it. */
    public static boolean flanIsAircraft(ItemStack stack) {
        if (stack == null || stack.isEmpty()) return false;
        try {
            Object item = stack.getItem();
            if (!item.getClass().getName().toLowerCase().contains("flansmod")) return false;
            Object type = item.getClass().getField("type").get(item);
            for (Class<?> c = (type == null) ? null : type.getClass(); c != null; c = c.getSuperclass()) {
                if ("PlaneType".equals(c.getSimpleName())) return true;
            }
            return false;
        } catch (Throwable t) {
            return false;
        }
    }

    /** Resolve a Flan vehicle/plane ITEM's ShortName reflectively ("" when it isn't one). */
    public static String flanShortNameOf(ItemStack stack) {
        if (stack == null || stack.isEmpty()) return "";
        try {
            Object item = stack.getItem();
            if (!item.getClass().getName().toLowerCase().contains("flansmod")) return "";
            Object type = item.getClass().getField("type").get(item);
            if (type == null) return "";
            Object shortName = type.getClass().getField("shortName").get(type);
            return shortName != null ? shortName.toString() : "";
        } catch (Throwable t) {
            return "";
        }
    }

    /** Dress the recruit in the exact loadout the player built (0 main, 1 off, 2-5 armor head->feet). */
    private void applyGear(EntitySoldier s) {
        EntityEquipmentSlot[] slots = {
                EntityEquipmentSlot.MAINHAND, EntityEquipmentSlot.OFFHAND,
                EntityEquipmentSlot.HEAD, EntityEquipmentSlot.CHEST,
                EntityEquipmentSlot.LEGS, EntityEquipmentSlot.FEET };
        for (int i = 0; i < gear.tagCount() && i < slots.length; i++) {
            try {
                ItemStack st = new ItemStack(gear.getCompoundTagAt(i));
                if (!st.isEmpty()) s.setItemStackToSlot(slots[i], st);
            } catch (Throwable ignored) {}
        }
    }

    @Override
    public void driveLoaded(WorldServer world) {
        BlockPos wp = currentWaypoint();
        if (wp == null) { strength = 0; return; }
        BlockPos goal = surface(world, wp.getX() + 0.5, wp.getZ() + 0.5);

        // VEHICLE delivery: the militia pilot drives its hull to the rally via its own rally logic.
        if (contract == 2) {
            Entity lead = null;
            for (UUID u : new ArrayList<>(entityIds)) {
                Entity e = world.getEntityFromUuid(u);
                if (e instanceof studio.ERM.war.vehicle.EntityAIPilot && !e.isDead) { lead = e; break; }
            }
            strength = (lead != null) ? 1 : 0;
            if (lead == null) return; // destroyed en route
            try {
                ((studio.ERM.war.vehicle.EntityAIPilot) lead).setRallyPoint(goal);
                ((studio.ERM.war.vehicle.EntityAIPilot) lead).setDeliveryMode(true); // flat-out, every pass
            } catch (Throwable ignored) {}
            x = lead.posX;
            z = lead.posZ;
            double vdx = (goal.getX() + 0.5) - lead.posX, vdz = (goal.getZ() + 0.5) - lead.posZ;

            // STUCK RECOVERY: the hull (the pilot's ride, or the pilot if it hasn't mounted yet).
            Entity hull = lead.getRidingEntity() != null ? lead.getRidingEntity() : lead;
            long now = world.getTotalWorldTime();
            if (stallSampleTick == 0) {
                stallSampleTick = now; stallX = hull.posX; stallZ = hull.posZ;
            } else if (now - stallSampleTick >= 60) {
                double moved = (hull.posX - stallX) * (hull.posX - stallX)
                        + (hull.posZ - stallZ) * (hull.posZ - stallZ);
                double goalDistSq = vdx * vdx + vdz * vdz;
                if (moved < 2.25 && goalDistSq > 400.0) { // <1.5 blocks in 3s, >20 blocks out
                    stallStrikes++;
                    double d = Math.sqrt(goalDistSq);
                    double ux = vdx / d, uz = vdz / d;
                    BlockPos to = (stallStrikes >= 4)
                            ? surface(world, goal.getX() + 0.5 - ux * 10.0, goal.getZ() + 0.5 - uz * 10.0)
                            : surface(world, hull.posX + ux * 8.0, hull.posZ + uz * 8.0);
                    try {
                        hull.setPositionAndUpdate(to.getX() + 0.5, to.getY() + 1.0, to.getZ() + 0.5);
                        if (hull != lead) lead.setPositionAndUpdate(to.getX() + 0.5, to.getY() + 1.0, to.getZ() + 0.5);
                        EpochRunnerMod.logger.info("[Recruit] delivery unstick hop (strike " + stallStrikes
                                + ") -> " + to.getX() + "," + to.getZ());
                    } catch (Throwable ignored) {}
                    if (stallStrikes >= 4) stallStrikes = 0; // parked beside the rally; next pass arrives
                } else if (moved >= 2.25) {
                    stallStrikes = 0; // making progress again
                }
                stallSampleTick = now; stallX = hull.posX; stallZ = hull.posZ;
            }
            vdx = (goal.getX() + 0.5) - hull.posX;
            vdz = (goal.getZ() + 0.5) - hull.posZ;
            // Generous 16-block arrival: Flan hull driving is imprecise; the delivery PARKS nearby
            // rather than fussing at the exact block (the "takes forever to show up" feel).
            if (vdx * vdx + vdz * vdz < 256.0 && routeIndex >= route.size() - 1) {
                finalizeArrival(world);
            } else if (vdx * vdx + vdz * vdz < 256.0) {
                advanceWaypoint();
            } else {
                facing = Math.atan2(vdz, vdx);
            }
            return;
        }

        EntitySoldier lead = null;
        int alive = 0;
        for (UUID u : new ArrayList<>(entityIds)) {
            Entity e = world.getEntityFromUuid(u);
            if (e instanceof EntitySoldier && !e.isDead) {
                alive++;
                if (lead == null) lead = (EntitySoldier) e;
                ((EntitySoldier) e).setMarchObjective(goal);
            }
        }
        strength = alive;
        if (lead == null) return; // killed en route -- the contract dies on the road
        x = lead.posX;
        z = lead.posZ;
        double dx = (goal.getX() + 0.5) - lead.posX, dz = (goal.getZ() + 0.5) - lead.posZ;
        boolean lastLeg = routeIndex >= route.size() - 1;
        if (dx * dx + dz * dz < 16.0) {
            if (lastLeg) { finalizeArrival(world); return; }
            advanceWaypoint();
        } else {
            facing = Math.atan2(dz, dx);
        }
    }

    /** ARRIVAL: hand the delivery over to the player's forces and leave the strategic map. */
    private void finalizeArrival(WorldServer world) {
        for (UUID u : new ArrayList<>(entityIds)) {
            Entity e = world.getEntityFromUuid(u);
            if (e == null || e.isDead) continue;
            e.getEntityData().removeTag("erm_strategic"); // no longer map-owned: it LIVES here now
            if (e instanceof EntitySoldier) {
                EntitySoldier s = (EntitySoldier) e;
                s.setStrategicManaged(false);
                s.setMarchObjective(null);
                if (contract == 1) {
                    long days = studio.ERM.war.config.WarLevelsConfig.recruitMercDays();
                    s.getEntityData().setLong("erm_merc_expiry", world.getTotalWorldTime() + days * 24000L);
                }
            }
            // A delivered VEHICLE keeps its rally point: it HOLDS the rally as a defensive emplacement
            // (turret live) until the plan's vehicle positions learn to re-station it.
        }
        entityIds.clear();
        materialized = false;
        strength = 0; // the simulator prunes the (now-empty) map object
        StrategicMapData.get(world).remove(id);
        EpochRunnerMod.logger.info("[Recruit] " + label() + " ARRIVED at " + (int) x + "," + (int) z);
        net.minecraft.entity.player.EntityPlayer p = world.getClosestPlayer(x, 80, z, 200, false);
        if (p != null) {
            String msgTxt;
            if (contract == 2) msgTxt = "Your " + vehicleShortName + " has been delivered to the rally point.";
            else if (contract == 0) msgTxt = "Your recruits have arrived at the rally point and joined your forces.";
            else msgTxt = "The mercenary company has arrived at the rally point. Contract: "
                        + studio.ERM.war.config.WarLevelsConfig.recruitMercDays() + " days.";
            p.sendMessage(new net.minecraft.util.text.TextComponentString(
                    net.minecraft.util.text.TextFormatting.GREEN + msgTxt));
        }
    }

    @Override
    public NBTTagCompound writeToNBT(NBTTagCompound tag) {
        super.writeToNBT(tag);
        tag.setInteger("contract", contract);
        tag.setTag("gear", gear);
        tag.setString("vehicle", vehicleShortName == null ? "" : vehicleShortName);
        return tag;
    }

    @Override
    public void readFromNBT(NBTTagCompound tag) {
        super.readFromNBT(tag);
        contract = tag.getInteger("contract");
        gear = tag.getTagList("gear", 10);
        vehicleShortName = tag.getString("vehicle");
    }
}
