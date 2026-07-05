package studio.ERM.strategic;

import net.minecraft.entity.Entity;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.WorldServer;
import studio.ERM.war.BattleManagers.entities.EntitySoldier;

import java.util.ArrayList;
import java.util.UUID;

/**
 * PHASE 2 archetype #4 — the ENGINEER CONVOY: a work crew with an armed escort and a supply cart,
 * dispatched from a settlement to a strategic resource node to raise an extraction camp. One-way
 * route (loopRoute=false); when it arrives and HOLDS at the site, ResourceCampManager notices,
 * begins construction, and retires the convoy. Same generic-group data as every other strategic
 * object — only the mission profile differs (the unified patrol-framework doctrine).
 */
public class StrategicConvoy extends StrategicObject {

    /** What this convoy does at its destination. BUILD_CAMP is the first mission profile. */
    public String purpose = "BUILD_CAMP";
    /** The resource node this convoy is heading for (ResourceNodeData uid; 0 = none). */
    public int targetNodeUid = 0;
    public int warLevel = 3;
    public String team = "empire";

    public StrategicConvoy() {
        loopRoute = false;   // one-way: hold at the destination until the camp manager retires us
        speed = 1.9;         // a laden crawl, slower than a patrol
        strength = 5;        // 3 engineers + 2 escorts by default
    }

    @Override
    public String typeId() { return "convoy"; }

    @Override
    public String label() { return "Engineer convoy (" + strength + ") -> node #" + targetNodeUid; }

    /** True when this one-way convoy is holding at (or within ~4 blocks of) its final waypoint. */
    public boolean arrived() {
        if (route.isEmpty() || loopRoute) return false;
        if (routeIndex < route.size() - 1) return false;
        BlockPos last = route.get(route.size() - 1);
        double dx = (last.getX() + 0.5) - x, dz = (last.getZ() + 0.5) - z;
        return dx * dx + dz * dz <= 5.0 * 5.0;
    }

    @Override
    protected void spawnEntities(WorldServer world, BlockPos at, float yawDeg) {
        double fx = Math.cos(facing), fz = Math.sin(facing);
        double px = -fz, pz = fx;
        int n = Math.max(1, strength);
        java.util.Random rng = new java.util.Random(id.getMostSignificantBits() ^ world.getTotalWorldTime());
        for (int i = 0; i < n; i++) {
            double lat = ((i % 2 == 0) ? 0.9 : -0.9);
            double back = (i / 2) * 1.8;
            double sx = at.getX() + 0.5 + px * lat - fx * back;
            double sz = at.getZ() + 0.5 + pz * lat - fz * back;
            BlockPos g = surface(world, sx, sz);
            EntitySoldier s = new EntitySoldier(world);
            s.setLocationAndAngles(sx, g.getY() + 1.0, sz, yawDeg, 0F);
            s.setTeam_(team);
            // First two are the armed ESCORT; the rest are the WORK CREW (melee kit, tool in hand).
            s.configure(warLevel, (i < 2) ? "RANGED" : "MELEE", "");
            if (i >= 2) {
                try {
                    s.setHeldItem(net.minecraft.util.EnumHand.MAIN_HAND,
                            new net.minecraft.item.ItemStack(net.minecraft.init.Items.IRON_PICKAXE));
                    s.setDropChance(net.minecraft.inventory.EntityEquipmentSlot.MAINHAND, 0f);
                } catch (Throwable ignored) {}
            }
            try { studio.ERM.war.skins.SkinPoolManager.applySkinForRivalLevel(s, warLevel, rng); } catch (Throwable ignored) {}
            s.setStrategicManaged(true);
            s.getEntityData().setString("erm_strategic", id.toString());
            BlockPos wp = currentWaypoint();
            if (wp != null) s.setMarchObjective(surface(world, wp.getX() + 0.5, wp.getZ() + 0.5));
            entityIds.add(s.getUniqueID());
            world.spawnEntity(s);
        }
        // The supply cart trundles behind the crew (AW2 chest cart; skipped cleanly when AW2 is absent).
        try {
            Entity cart = studio.ERM.war.BattleManagers.core.ChestCartHelper.createChestCart(world);
            if (cart != null) {
                double cx = at.getX() + 0.5 - fx * 3.0, cz = at.getZ() + 0.5 - fz * 3.0;
                BlockPos cg = surface(world, cx, cz);
                cart.setLocationAndAngles(cx, cg.getY() + 1.0, cz, yawDeg, 0F);
                cart.getEntityData().setString("erm_strategic", id.toString());
                entityIds.add(cart.getUniqueID());
                world.spawnEntity(cart);
            }
        } catch (Throwable ignored) {}
    }

    @Override
    public void driveLoaded(WorldServer world) {
        BlockPos wp = currentWaypoint();
        if (wp == null) return;
        BlockPos goal = surface(world, wp.getX() + 0.5, wp.getZ() + 0.5);
        Entity lead = null;
        int alive = 0;
        for (UUID u : new ArrayList<>(entityIds)) {
            Entity e = world.getEntityFromUuid(u);
            if (e == null || e.isDead) continue;
            if (e instanceof EntitySoldier) {
                alive++;
                if (lead == null) lead = e;
                ((EntitySoldier) e).setMarchObjective(goal);
            } else {
                // The cart: faux-pull it along ~3 blocks behind the lead (same discipline as the trader).
                if (lead != null) {
                    double hx = lead.posX - Math.cos(facing) * 3.0, hz = lead.posZ - Math.sin(facing) * 3.0;
                    double dx = hx - e.posX, dz = hz - e.posZ;
                    double d = Math.sqrt(dx * dx + dz * dz);
                    if (d > 24) {
                        BlockPos snap = surface(world, hx, hz);
                        e.setPositionAndUpdate(hx, snap.getY() + 1.0, hz);
                    } else if (d > 0.6) {
                        double step = Math.min(0.45, d);
                        BlockPos gy = surface(world, e.posX + dx / d * step, e.posZ + dz / d * step);
                        e.setPosition(e.posX + dx / d * step, gy.getY() + 1.0, e.posZ + dz / d * step);
                        e.motionX = e.motionY = e.motionZ = 0;
                    }
                }
            }
        }
        strength = alive; // an ambushed convoy that loses its crew dies on the map too
        if (lead != null) {
            x = lead.posX;
            z = lead.posZ;
            double dx = (goal.getX() + 0.5) - lead.posX, dz = (goal.getZ() + 0.5) - lead.posZ;
            if (dx * dx + dz * dz < 16.0) advanceWaypoint();
            else facing = Math.atan2(dz, dx);
        }
    }

    @Override
    public NBTTagCompound writeToNBT(NBTTagCompound tag) {
        super.writeToNBT(tag);
        tag.setString("purpose", purpose);
        tag.setInteger("node", targetNodeUid);
        tag.setInteger("warLevel", warLevel);
        tag.setString("team", team);
        return tag;
    }

    @Override
    public void readFromNBT(NBTTagCompound tag) {
        super.readFromNBT(tag);
        purpose = tag.getString("purpose").isEmpty() ? "BUILD_CAMP" : tag.getString("purpose");
        targetNodeUid = tag.getInteger("node");
        warLevel = Math.max(1, Math.min(10, tag.getInteger("warLevel")));
        team = tag.getString("team").isEmpty() ? "empire" : tag.getString("team");
        loopRoute = false;
    }
}
