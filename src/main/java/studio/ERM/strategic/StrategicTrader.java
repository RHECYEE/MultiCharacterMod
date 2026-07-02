package studio.ERM.strategic;

import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityList;
import net.minecraft.entity.EntityLiving;
import net.minecraft.entity.passive.EntityMule;
import net.minecraft.entity.passive.EntityVillager;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.util.ResourceLocation;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.WorldServer;
import studio.ERM.EpochRunnerMod;
import studio.ERM.war.BattleManagers.entities.EntitySoldier;

import java.util.ArrayList;
import java.util.UUID;

/**
 * PHASE 2 archetype #2 — a TRADER CARAVAN (the first civilian traffic).
 *
 * A merchant walking a trade route between two anchors with a CHEST CART in tow and (at higher levels)
 * a couple of armed escorts. Strategic while unloaded; when the player approaches it materializes
 * mid-journey: the trader out front, the cart behind, escorts flanking — already walking its route.
 *
 * Entity resolution (soft dependencies, never hard-fails):
 *   trader — tries the AW2 trader NPC ids reflectively, falls back to a vanilla villager;
 *   cart   — the config entity id (WarLevelsConfig traffic.cartEntityId, so any mod's cart works),
 *            falling back to a LEASHED CHEST MULE (reads instantly as a pack caravan);
 *   escort — our own EntitySoldier march AI (level >= 3 routes get 2 guards).
 *
 * The caravan dies with its MERCHANT: escorts fight, but if the trader is killed the route is wiped
 * from the map (raiding trade routes has a real strategic effect).
 */
public class StrategicTrader extends StrategicObject {

    /** Candidate AW2 trader entity ids, tried in order before the villager fallback. */
    private static final String[] AW2_TRADER_IDS = { "faction.trader", "trader", "npc.trader" };

    public int level = 3;
    public int escorts = 0;

    public StrategicTrader() {
        speed = 1.8;      // a walking merchant pace while unloaded
        strength = 1;
        loopRoute = true; // a 2-point route ping-pongs A <-> B forever
    }

    @Override
    public String typeId() { return "trader"; }

    @Override
    public String label() { return "Trader caravan (" + (1 + Math.max(0, escorts)) + ")"; }

    @Override
    protected void spawnEntities(WorldServer world, BlockPos at, float yawDeg) {
        double fx = Math.cos(facing), fz = Math.sin(facing);
        double px = -fz, pz = fx;

        // 1) THE MERCHANT — AW2 trader if the pack provides one, else a villager.
        EntityLiving trader = tryCreate(world, AW2_TRADER_IDS);
        if (trader == null) trader = new EntityVillager(world);
        BlockPos tg = surface(world, at.getX() + 0.5, at.getZ() + 0.5);
        trader.setLocationAndAngles(at.getX() + 0.5, tg.getY() + 1.0, at.getZ() + 0.5, yawDeg, 0F);
        trader.enablePersistence();
        adopt(world, trader);

        // 2) THE CHEST CART. Priority: the config entity id (any mod's cart) -> the REAL AW2 chest cart
        // (ancientwarfarevehicle:vehicle + VehicleType "chest_cart", spawned reflectively so the vehicle
        // module stays a soft dependency) -> a leashed chest mule. AW2 carts aren't living entities, so
        // the merchant FAUX-PULLS them: driveLoaded drags the cart along behind him every tick.
        Entity cart = tryCreateAny(world, splitIds(studio.ERM.war.config.WarLevelsConfig.trafficCartId()));
        if (cart == null) cart = tryCreateAw2Cart(world);
        if (cart == null) {
            EntityMule mule = new EntityMule(world);
            mule.setChested(true);
            mule.setHorseTamed(true);
            cart = mule;
        }
        double cx = at.getX() + 0.5 - fx * 2.4, cz = at.getZ() + 0.5 - fz * 2.4;
        BlockPos cg = surface(world, cx, cz);
        cart.setLocationAndAngles(cx, cg.getY() + 1.0, cz, yawDeg, 0F);
        if (cart instanceof EntityLiving) ((EntityLiving) cart).enablePersistence();
        adopt(world, cart);
        if (cart instanceof EntityLiving) {
            try { ((EntityLiving) cart).setLeashHolder(trader, true); } catch (Throwable ignored) {}
        }

        // 3) ESCORTS — flanking guards reusing the proven soldier march AI.
        java.util.Random rng = new java.util.Random(id.getLeastSignificantBits());
        for (int i = 0; i < Math.max(0, escorts); i++) {
            double lat = (i % 2 == 0) ? 2.2 : -2.2;
            double sx = at.getX() + 0.5 + px * lat - fx * 1.2;
            double sz = at.getZ() + 0.5 + pz * lat - fz * 1.2;
            BlockPos sg = surface(world, sx, sz);
            EntitySoldier s = new EntitySoldier(world);
            s.setLocationAndAngles(sx, sg.getY() + 1.0, sz, yawDeg, 0F);
            s.setTeam_("empire");
            s.configure(level, "MELEE", "");
            try { studio.ERM.war.skins.SkinPoolManager.applySkinForRivalLevel(s, level, rng); } catch (Throwable ignored) {}
            s.setStrategicManaged(true);
            s.getEntityData().setString("erm_strategic", id.toString());
            BlockPos wp = currentWaypoint();
            if (wp != null) s.setMarchObjective(surface(world, wp.getX() + 0.5, wp.getZ() + 0.5));
            entityIds.add(s.getUniqueID());
            world.spawnEntity(s);
        }
    }

    @Override
    public void driveLoaded(WorldServer world) {
        BlockPos wp = currentWaypoint();
        if (wp == null) return;
        BlockPos goal = surface(world, wp.getX() + 0.5, wp.getZ() + 0.5);

        EntityLiving trader = null;
        Entity cart = null;
        int escortsAlive = 0, idx = 0;
        for (UUID u : new ArrayList<>(entityIds)) {
            Entity e = world.getEntityFromUuid(u);
            boolean alive = e != null && !e.isDead;
            if (idx == 0) {                       // slot 0 = the merchant
                if (alive && e instanceof EntityLiving) trader = (EntityLiving) e;
            } else if (idx == 1) {                // slot 1 = the chest cart
                if (alive) cart = e;
            } else if (alive && e instanceof EntitySoldier) {
                escortsAlive++;
                ((EntitySoldier) e).setMarchObjective(goal);
            }
            idx++;
        }
        escorts = escortsAlive;

        // The caravan lives and dies with its merchant.
        if (trader == null) { strength = 0; return; }
        strength = 1 + escortsAlive;

        // Called EVERY tick while materialized; throttle the pathfind, not the tow.
        if (trader.ticksExisted % 15 == 0 || trader.getNavigator().noPath()) {
            navToward(trader, world, goal, 1.0);
        }

        // FAUX-PULL the cart: a non-living cart (the AW2 chest cart) has no leash physics, so drag it
        // to a hitch point ~2.4 behind the merchant each tick (smooth lerp + kill residual motion so the
        // vehicle physics doesn't fight the tow). A living cart (the mule) follows its leash instead.
        if (cart != null && !(cart instanceof EntityLiving)) {
            double hx = trader.posX - Math.cos(facing) * 2.4;
            double hz = trader.posZ - Math.sin(facing) * 2.4;
            double nx = cart.posX + (hx - cart.posX) * 0.22;
            double nz = cart.posZ + (hz - cart.posZ) * 0.22;
            BlockPos cg = surface(world, nx, nz);
            float cartYaw = (float) Math.toDegrees(facing) - 90F;
            cart.setPositionAndRotation(nx, cg.getY() + 0.05, nz, cartYaw, 0F);
            cart.motionX = 0; cart.motionY = 0; cart.motionZ = 0;
        }

        x = trader.posX;
        z = trader.posZ;
        double dx = (goal.getX() + 0.5) - trader.posX, dz = (goal.getZ() + 0.5) - trader.posZ;
        if (dx * dx + dz * dz < 25.0) advanceWaypoint();
        else facing = Math.atan2(dz, dx);
    }

    /** Adopt a non-soldier caravan entity: tag + track + spawn (uuid recorded BEFORE spawn for the sweep). */
    private void adopt(WorldServer world, Entity e) {
        e.getEntityData().setString("erm_strategic", id.toString());
        entityIds.add(e.getUniqueID());
        world.spawnEntity(e);
    }

    /** Try a list of entity ids ("mod:name" or bare AW2 npc names) and return the first LIVING one. */
    private static EntityLiving tryCreate(WorldServer world, String[] ids) {
        Entity e = tryCreateAny(world, ids);
        return (e instanceof EntityLiving) ? (EntityLiving) e : null;
    }

    /** Same, but any Entity (the cart config can point at non-living cart/vehicle entities). */
    private static Entity tryCreateAny(WorldServer world, String[] ids) {
        if (ids == null) return null;
        for (String raw : ids) {
            if (raw == null || raw.trim().isEmpty()) continue;
            String s = raw.trim();
            ResourceLocation rl = s.contains(":") ? new ResourceLocation(s)
                    : new ResourceLocation("ancientwarfarenpc", s);
            try {
                Entity e = EntityList.createEntityByIDFromName(rl, world);
                if (e != null) return e;
            } catch (Throwable ignored) {}
        }
        return null;
    }

    /**
     * The REAL AW2 chest cart: spawn ancientwarfarevehicle:vehicle and apply the "chest_cart"
     * VehicleType reflectively (same soft-dependency pattern as the siege machines). Returns null if
     * the AW2 vehicle module isn't installed.
     */
    private static Entity tryCreateAw2Cart(WorldServer world) {
        try {
            Entity v = EntityList.createEntityByIDFromName(
                    new ResourceLocation("ancientwarfarevehicle", "vehicle"), world);
            if (v == null) return null;
            Class<?> vtClass = Class.forName("net.shadowmage.ancientwarfare.vehicle.entity.types.VehicleType");
            java.lang.reflect.Field f = vtClass.getDeclaredField("vehicleTypes");
            f.setAccessible(true);
            Object reg = f.get(null);
            Iterable<?> all;
            if (reg instanceof Object[]) all = java.util.Arrays.asList((Object[]) reg);
            else if (reg instanceof Iterable) all = (Iterable<?>) reg;
            else if (reg instanceof java.util.Map) all = ((java.util.Map<?, ?>) reg).values();
            else return null;
            Object cartType = null;
            for (Object vt : all) {
                if (vt == null) continue;
                try {
                    Object cn = vt.getClass().getMethod("getConfigName").invoke(vt);
                    if (cn != null && cn.toString().toLowerCase().contains("cart")) { cartType = vt; break; }
                } catch (Throwable ignored) {}
            }
            if (cartType == null) return null;
            Class<?> iVehicleType = Class.forName("net.shadowmage.ancientwarfare.vehicle.entity.IVehicleType");
            v.getClass().getMethod("setVehicleType", iVehicleType, int.class).invoke(v, cartType, 0);
            return v;
        } catch (Throwable t) {
            EpochRunnerMod.logger.info("[Strategic] AW2 chest cart unavailable (" + t.getClass().getSimpleName()
                    + ") -> mule fallback");
            return null;
        }
    }

    private static String[] splitIds(String csv) {
        if (csv == null || csv.trim().isEmpty()) return new String[0];
        return csv.split(",");
    }

    @Override
    public NBTTagCompound writeToNBT(NBTTagCompound tag) {
        super.writeToNBT(tag);
        tag.setInteger("level", level);
        tag.setInteger("escorts", escorts);
        return tag;
    }

    @Override
    public void readFromNBT(NBTTagCompound tag) {
        super.readFromNBT(tag);
        level = Math.max(1, Math.min(10, tag.getInteger("level") == 0 ? 3 : tag.getInteger("level")));
        escorts = Math.max(0, tag.getInteger("escorts"));
    }
}
