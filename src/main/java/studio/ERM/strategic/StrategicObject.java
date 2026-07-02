package studio.ERM.strategic;

import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.WorldServer;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * PHASE 2 — THE STRATEGIC WORLD SIMULATION (Base module).
 *
 * A StrategicObject is the AUTHORITATIVE representation of a moving world actor (patrol, trader,
 * convoy, courier, proxy...). It exists on the strategic map FIRST and continuously tracks its
 * position, route, task, faction and strength. It never simply disappears — it TRANSITIONS between
 * the strategic simulation (unloaded: advanced by pure math in {@link #tickUnloaded}) and physical
 * Minecraft entities (loaded: {@link #materialize} spawns them near the expected position facing the
 * travel direction; {@link #dematerialize} captures their state back onto the map).
 *
 * Core rule (the user's Phase 2 spec): visible world = physical simulation; unloaded world =
 * strategic simulation. The goal is the ILLUSION the world never stops — when the player arrives
 * they witness an already-functioning civilization, not one that begins existing.
 */
public abstract class StrategicObject {

    public UUID id = UUID.randomUUID();
    public String faction = "RIVAL";

    // Strategic position (Y is irrelevant on the map; it's resolved from the terrain on materialize).
    public double x, z;
    // Direction of travel in radians (entities face this on materialize).
    public double facing = 0.0;

    // The route: a chain of waypoints (Y ignored). Loops by default (a patrol circuit).
    public final List<BlockPos> route = new ArrayList<>();
    public int routeIndex = 0;
    public boolean loopRoute = true;

    // Strategic movement speed in blocks/second while unloaded (a marching pace ~2-3).
    public double speed = 2.4;

    // Members remaining (soldiers in the patrol / crew in the convoy). 0 => the object is destroyed.
    public int strength = 4;

    // Loaded-state: true while represented by live entities; their UUIDs for capture/cleanup.
    public boolean materialized = false;
    public final List<UUID> entityIds = new ArrayList<>();

    /** Stable type key for save/load factory dispatch (e.g. "patrol"). */
    public abstract String typeId();

    /** One-line human label for logs/debug chat (e.g. "Rival patrol (4)"). */
    public abstract String label();

    /** Spawn this object's live entities at {@code at}, facing {@code yawDeg}, and record their UUIDs
     *  in {@link #entityIds} BEFORE each spawn (the orphan sweep checks membership on entity-join). */
    protected abstract void spawnEntities(WorldServer world, BlockPos at, float yawDeg);

    /** Per-second steering while materialized: refresh each live entity's orders toward the current
     *  waypoint, recapture {@link #x}/{@link #z} from the lead entity, and update {@link #strength}. */
    public abstract void driveLoaded(WorldServer world);

    // ── UNLOADED SIMULATION ─────────────────────────────────────────────────────────────────────

    /** Advance along the route by pure math (no chunks touched). Called ~once a second while unloaded. */
    public void tickUnloaded(double dtSeconds) {
        if (route.isEmpty()) return;
        BlockPos wp = route.get(Math.min(routeIndex, route.size() - 1));
        double dx = (wp.getX() + 0.5) - x, dz = (wp.getZ() + 0.5) - z;
        double d = Math.sqrt(dx * dx + dz * dz);
        double step = speed * dtSeconds;
        if (d <= Math.max(2.0, step)) {
            advanceWaypoint();
        } else {
            x += dx / d * step;
            z += dz / d * step;
            facing = Math.atan2(dz, dx);
        }
    }

    protected void advanceWaypoint() {
        if (route.isEmpty()) return;
        if (routeIndex + 1 < route.size()) routeIndex++;
        else if (loopRoute) routeIndex = 0;
        // else: hold at the final waypoint (a delivery/one-way route).
    }

    public BlockPos currentWaypoint() {
        if (route.isEmpty()) return null;
        return route.get(Math.min(routeIndex, route.size() - 1));
    }

    // ── LOAD / UNLOAD TRANSITIONS ───────────────────────────────────────────────────────────────

    /** Materialize into live entities near the expected strategic position, facing the travel direction. */
    public void materialize(WorldServer world) {
        BlockPos at = surface(world, x, z);
        materialized = true;
        entityIds.clear();
        float yawDeg = (float) Math.toDegrees(facing) - 90.0F;
        spawnEntities(world, at, yawDeg);
    }

    /** Capture state back onto the map and remove the live entities. Progress is PRESERVED. */
    public void dematerialize(WorldServer world) {
        boolean captured = false;
        for (UUID u : new ArrayList<>(entityIds)) {
            net.minecraft.entity.Entity e = world.getEntityFromUuid(u);
            if (e == null || e.isDead) continue;
            if (!captured) { x = e.posX; z = e.posZ; captured = true; } // lead entity fixes the map position
            try { e.setDead(); } catch (Throwable ignored) {}
        }
        entityIds.clear();
        materialized = false;
    }

    /** Ground level in a column (top solid/liquid), matching the codebase's spawn convention. */
    protected static BlockPos surface(WorldServer world, double x, double z) {
        try {
            return world.getTopSolidOrLiquidBlock(new BlockPos((int) Math.floor(x), 64, (int) Math.floor(z)));
        } catch (Throwable t) {
            return new BlockPos((int) Math.floor(x), 70, (int) Math.floor(z));
        }
    }

    // ── PERSISTENCE ─────────────────────────────────────────────────────────────────────────────

    public NBTTagCompound writeToNBT(NBTTagCompound tag) {
        tag.setString("type", typeId());
        tag.setUniqueId("id", id);
        tag.setString("faction", faction);
        tag.setDouble("x", x);
        tag.setDouble("z", z);
        tag.setDouble("facing", facing);
        tag.setDouble("speed", speed);
        tag.setInteger("strength", strength);
        tag.setInteger("routeIndex", routeIndex);
        tag.setBoolean("loop", loopRoute);
        int[] flat = new int[route.size() * 2];
        for (int i = 0; i < route.size(); i++) {
            flat[i * 2] = route.get(i).getX();
            flat[i * 2 + 1] = route.get(i).getZ();
        }
        tag.setIntArray("route", flat);
        // materialized deliberately NOT persisted as true: entity ids are stale across a reload, so
        // every object recovers as UNLOADED at its last captured position (the orphan sweep removes
        // any chunk-saved stragglers when their chunks load again).
        return tag;
    }

    public void readFromNBT(NBTTagCompound tag) {
        try { id = tag.getUniqueId("id"); } catch (Throwable ignored) {}
        if (id == null) id = UUID.randomUUID();
        faction = tag.getString("faction");
        x = tag.getDouble("x");
        z = tag.getDouble("z");
        facing = tag.getDouble("facing");
        speed = tag.getDouble("speed") > 0 ? tag.getDouble("speed") : 2.4;
        strength = Math.max(0, tag.getInteger("strength"));
        routeIndex = Math.max(0, tag.getInteger("routeIndex"));
        loopRoute = !tag.hasKey("loop") || tag.getBoolean("loop");
        route.clear();
        int[] flat = tag.getIntArray("route");
        for (int i = 0; i + 1 < flat.length; i += 2) route.add(new BlockPos(flat[i], 0, flat[i + 1]));
        if (routeIndex >= route.size()) routeIndex = 0;
        materialized = false;
        entityIds.clear();
    }
}
