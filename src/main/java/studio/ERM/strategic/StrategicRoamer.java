package studio.ERM.strategic;

import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityList;
import net.minecraft.entity.EntityLiving;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.util.ResourceLocation;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.WorldServer;
import studio.ERM.strategic.patrol.PatrolConfig;

import java.util.ArrayList;
import java.util.Random;
import java.util.UUID;

/**
 * PHASE 2 archetype #5 — the ROAMER: a PERSISTENT strategic group whose COMPOSITION comes from a
 * {@link PatrolConfig} def row (the same config the transient patrol framework reads). This is the
 * unification the Strategic Background Life design calls for: nation-state patrols, ambient
 * travelers, hunting parties — one shared code path (persistence, dynamic loading, movement),
 * only the def/mission differs. Walks its circuit by math while unloaded, materializes as the
 * def's entities when a player approaches, takes real casualties back onto the map.
 */
public class StrategicRoamer extends StrategicObject {

    /** The PatrolConfig def naming this group's entity composition. */
    public String defName = "";
    /** Display faction for logs/encounters (e.g. "NATION:Noggville"). */
    public String displayName = "Patrol";

    public StrategicRoamer() {
        speed = 2.2;
        strength = 3;
        faction = "NATION";
    }

    @Override
    public String typeId() { return "roamer"; }

    @Override
    public String label() { return displayName + " (" + strength + ")"; }

    @Override
    protected void spawnEntities(WorldServer world, BlockPos at, float yawDeg) {
        PatrolConfig.PatrolDef def = null;
        for (PatrolConfig.PatrolDef d : PatrolConfig.data.patrols) {
            if (d.name.equals(defName)) { def = d; break; }
        }
        if (def == null || def.entities.isEmpty()) return; // def edited away: silent (object retires on 0 strength)
        Random rng = new Random(id.getMostSignificantBits() ^ world.getTotalWorldTime());
        double fx = Math.cos(facing), fz = Math.sin(facing);
        double px = -fz, pz = fx;
        int n = Math.max(1, strength);
        for (int i = 0; i < n; i++) {
            String eid = def.entities.get(rng.nextInt(def.entities.size()));
            Entity ent;
            try { ent = EntityList.createEntityByIDFromName(new ResourceLocation(eid), world); }
            catch (Throwable t) { ent = null; }
            if (ent == null) continue;
            double lat = ((i % 2 == 0) ? 1.0 : -1.0);
            double back = (i / 2) * 1.8;
            double sx = at.getX() + 0.5 + px * lat - fx * back;
            double sz = at.getZ() + 0.5 + pz * lat - fz * back;
            BlockPos g = surface(world, sx, sz);
            ent.setLocationAndAngles(sx, g.getY() + 1.0, sz, yawDeg, 0F);
            if (ent instanceof EntityLiving) {
                try {
                    ((EntityLiving) ent).onInitialSpawn(world.getDifficultyForLocation(g), null);
                    ((EntityLiving) ent).enablePersistence(); // the MAP owns this life, not the despawn rules
                } catch (Throwable ignored) {}
            }
            ent.getEntityData().setString("erm_strategic", id.toString());
            try {
                studio.ERM.war.skins.SkinPoolManager.applySkinForRivalLevel(ent,
                        studio.ERM.strategic.civil.DistrictRegistry.rivalLevel(world), rng);
            } catch (Throwable ignored) {}
            entityIds.add(ent.getUniqueID()); // BEFORE spawn: the orphan sweep checks membership
            world.spawnEntity(ent);
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
            if (e == null || e.isDead) continue;
            alive++;
            if (lead == null) lead = e;
            if (e instanceof EntityLiving) navToward((EntityLiving) e, world, goal, 0.85);
        }
        strength = alive;
        if (lead != null) {
            x = lead.posX;
            z = lead.posZ;
            double dx = (goal.getX() + 0.5) - lead.posX, dz = (goal.getZ() + 0.5) - lead.posZ;
            if (dx * dx + dz * dz < 25.0) advanceWaypoint();
            else facing = Math.atan2(dz, dx);
        }
    }

    @Override
    public NBTTagCompound writeToNBT(NBTTagCompound tag) {
        super.writeToNBT(tag);
        tag.setString("def", defName);
        tag.setString("disp", displayName);
        return tag;
    }

    @Override
    public void readFromNBT(NBTTagCompound tag) {
        super.readFromNBT(tag);
        defName = tag.getString("def");
        displayName = tag.getString("disp").isEmpty() ? "Patrol" : tag.getString("disp");
    }
}
