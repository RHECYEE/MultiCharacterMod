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

    public int contract = 1; // 0 = permanent recruitment, 1 = mercenary contract
    public NBTTagList gear = new NBTTagList(); // the loadout the player built (slot-indexed ItemStacks)

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
        return (contract == 0 ? "Recruit column" : "Mercenary convoy") + " (" + strength + ")";
    }

    @Override
    protected void spawnEntities(WorldServer world, BlockPos at, float yawDeg) {
        java.util.Random rng = new java.util.Random(id.getLeastSignificantBits());
        EntitySoldier s = new EntitySoldier(world);
        BlockPos g = surface(world, at.getX() + 0.5, at.getZ() + 0.5);
        s.setLocationAndAngles(at.getX() + 0.5, g.getY() + 1.0, at.getZ() + 0.5, yawDeg, 0F);
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

    /** ARRIVAL: hand the soldier over to the player's forces and leave the strategic map. */
    private void finalizeArrival(WorldServer world) {
        for (UUID u : new ArrayList<>(entityIds)) {
            Entity e = world.getEntityFromUuid(u);
            if (!(e instanceof EntitySoldier) || e.isDead) continue;
            EntitySoldier s = (EntitySoldier) e;
            s.getEntityData().removeTag("erm_strategic"); // no longer map-owned: it LIVES here now
            s.setStrategicManaged(false);
            s.setMarchObjective(null);
            if (contract != 0) {
                long days = studio.ERM.war.config.WarLevelsConfig.recruitMercDays();
                s.getEntityData().setLong("erm_merc_expiry", world.getTotalWorldTime() + days * 24000L);
            }
        }
        entityIds.clear();
        materialized = false;
        strength = 0; // the simulator prunes the (now-empty) map object
        StrategicMapData.get(world).remove(id);
        EpochRunnerMod.logger.info("[Recruit] " + label() + " ARRIVED at " + (int) x + "," + (int) z);
        net.minecraft.entity.player.EntityPlayer p = world.getClosestPlayer(x, 80, z, 200, false);
        if (p != null) {
            p.sendMessage(new net.minecraft.util.text.TextComponentString(
                    net.minecraft.util.text.TextFormatting.GREEN + (contract == 0
                            ? "Your recruits have arrived at the rally point and joined your forces."
                            : "The mercenary company has arrived at the rally point. Contract: "
                              + studio.ERM.war.config.WarLevelsConfig.recruitMercDays() + " days.")));
        }
    }

    @Override
    public NBTTagCompound writeToNBT(NBTTagCompound tag) {
        super.writeToNBT(tag);
        tag.setInteger("contract", contract);
        tag.setTag("gear", gear);
        return tag;
    }

    @Override
    public void readFromNBT(NBTTagCompound tag) {
        super.readFromNBT(tag);
        contract = tag.getInteger("contract");
        gear = tag.getTagList("gear", 10);
    }
}
