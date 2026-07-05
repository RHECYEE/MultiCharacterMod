package studio.ERM.strategic;

import net.minecraft.entity.Entity;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.WorldServer;
import studio.ERM.war.BattleManagers.entities.EntitySoldier;

import java.util.ArrayList;
import java.util.UUID;

/**
 * PHASE 2 archetype #1 — a marching military PATROL.
 *
 * The proof-of-loop for the whole strategic simulation: it exists on the map, walks its circuit by
 * pure math while unloaded, MATERIALIZES as a column of {@link EntitySoldier}s (facing the travel
 * direction, mid-march) when the player approaches, keeps marching its route via the director-march
 * AI, takes real casualties (captured back to map strength), and DEMATERIALIZES when the player
 * leaves. Reuses the proven EntitySoldier march/ladder/combat behavior — no new unit AI.
 */
public class StrategicPatrol extends StrategicObject {

    public int warLevel = 3;
    public String team = "empire";

    public StrategicPatrol() {}

    @Override
    public String typeId() { return "patrol"; }

    @Override
    public String label() {
        return (("empire".equalsIgnoreCase(team)) ? "Rival patrol" : (team + " patrol")) + " (" + strength + ")";
    }

    @Override
    protected void spawnEntities(WorldServer world, BlockPos at, float yawDeg) {
        // A rough 2-wide marching column behind the lead position, facing along the route.
        double fx = Math.cos(facing), fz = Math.sin(facing);   // forward
        double px = -fz, pz = fx;                              // lateral
        int n = Math.max(1, strength);
        java.util.Random rng = new java.util.Random(id.getMostSignificantBits() ^ world.getTotalWorldTime());
        for (int i = 0; i < n; i++) {
            double lat = ((i % 2 == 0) ? 0.9 : -0.9);
            double back = (i / 2) * 1.7;
            double sx = at.getX() + 0.5 + px * lat - fx * back;
            double sz = at.getZ() + 0.5 + pz * lat - fz * back;
            BlockPos g = surface(world, sx, sz);
            EntitySoldier s = new EntitySoldier(world);
            s.setLocationAndAngles(sx, g.getY() + 1.0, sz, yawDeg, 0F);
            s.setTeam_(team);
            s.configure(warLevel, (i % 3 == 2) ? "RANGED" : "MELEE", "");
            try { studio.ERM.war.skins.SkinPoolManager.applySkinForRivalLevel(s, warLevel, rng); } catch (Throwable ignored) {}
            s.setStrategicManaged(true);                                  // lifecycle owned by the map, no idle-despawn
            s.getEntityData().setString("erm_strategic", id.toString());  // orphan-sweep membership tag
            // Arm the director-march guard IMMEDIATELY (before the first driveLoaded tick), so a fresh
            // patrol marches instead of instantly aggroing a distant player.
            BlockPos wp = currentWaypoint();
            if (wp != null) s.setMarchObjective(surface(world, wp.getX() + 0.5, wp.getZ() + 0.5));
            entityIds.add(s.getUniqueID()); // record BEFORE spawn: entity-join sweep checks membership
            world.spawnEntity(s);
        }
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
            if (e instanceof EntitySoldier && !e.isDead) {
                alive++;
                if (lead == null) lead = e;
                ((EntitySoldier) e).setMarchObjective(goal);
            }
        }
        strength = alive; // real casualties flow back onto the map
        if (lead != null) {
            x = lead.posX;
            z = lead.posZ;
            double dx = (goal.getX() + 0.5) - lead.posX, dz = (goal.getZ() + 0.5) - lead.posZ;
            if (dx * dx + dz * dz < 16.0) advanceWaypoint();  // lead reached the waypoint -> next leg
            else facing = Math.atan2(dz, dx);
        }
    }

    @Override
    public NBTTagCompound writeToNBT(NBTTagCompound tag) {
        super.writeToNBT(tag);
        tag.setInteger("warLevel", warLevel);
        tag.setString("team", team);
        return tag;
    }

    @Override
    public void readFromNBT(NBTTagCompound tag) {
        super.readFromNBT(tag);
        warLevel = Math.max(1, Math.min(10, tag.getInteger("warLevel")));
        team = tag.getString("team").isEmpty() ? "empire" : tag.getString("team");
    }
}
