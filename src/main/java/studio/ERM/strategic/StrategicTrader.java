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
    // The cart's config cargo has been rolled+loaded once for this caravan's lifetime (persisted, so a
    // robbed cart never restocks itself by dematerializing and coming back).
    private boolean cargoFilled = false;

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
        if (cart == null) cart = studio.ERM.war.BattleManagers.core.ChestCartHelper.createChestCart(world);
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
            tameCartPace((EntityLiving) cart);
        }
        // Stock the cart from the config cargo table -- ONCE per caravan (the flag persists, so a player
        // who robs it and walks away doesn't find it magically restocked on re-materialization).
        if (!cargoFilled) {
            cargoFilled = true;
            fillCartCargo(world, cart);
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
        // to a hitch point ~2.4 behind the merchant each tick (step-CLAMPED lerp + kill residual motion,
        // so the tow can never fling it at silly speeds). A LIVING cart (the mule) normally follows its
        // leash -- but it also has its own legs and AI, which is how it "ran away 6x faster than the
        // guys": shepherd it -- re-leash if the leash popped, and rein it back to the hitch if it strays.
        if (cart != null) {
            double hx = trader.posX - Math.cos(facing) * 2.4;
            double hz = trader.posZ - Math.sin(facing) * 2.4;
            if (!(cart instanceof EntityLiving)) {
                double sx = (hx - cart.posX) * 0.22, sz = (hz - cart.posZ) * 0.22;
                double slen = Math.sqrt(sx * sx + sz * sz);
                if (slen > 0.45) { sx = sx / slen * 0.45; sz = sz / slen * 0.45; } // walking-pace tow, always
                double nx = cart.posX + sx, nz = cart.posZ + sz;
                BlockPos cg = surface(world, nx, nz);
                float cartYaw = (float) Math.toDegrees(facing) - 90F;
                cart.setPositionAndRotation(nx, cg.getY() + 0.05, nz, cartYaw, 0F);
                cart.motionX = 0; cart.motionY = 0; cart.motionZ = 0;
            } else {
                EntityLiving live = (EntityLiving) cart;
                try {
                    if (live.getLeashHolder() != trader) live.setLeashHolder(trader, true); // leash popped -> re-hitch
                } catch (Throwable ignored) {}
                double dCart = live.getDistance(trader);
                if (dCart > 24.0) { // hopelessly separated (materialization edge/panic burst): snap to the hitch
                    BlockPos cg = surface(world, hx, hz);
                    live.setPositionAndRotation(hx, cg.getY() + 0.1, hz, (float) Math.toDegrees(facing) - 90F, 0F);
                    live.getNavigator().clearPath();
                } else if (dCart > 6.0) { // straying: rein it back toward the hitch at a walking pace
                    double sx = (hx - live.posX), sz = (hz - live.posZ);
                    double slen = Math.max(0.001, Math.sqrt(sx * sx + sz * sz));
                    live.setPositionAndRotation(live.posX + sx / slen * 0.3, live.posY, live.posZ + sz / slen * 0.3,
                            (float) Math.toDegrees(Math.atan2(sz, sx)) - 90F, 0F);
                    live.motionX *= 0.4; live.motionZ *= 0.4;
                }
            }
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
     * A LIVING cart (chest mule / config entity) must move at CARAVAN pace, not its own: clamp its walk
     * speed below the merchant's and strip the flighty AI (panic when hurt, run-around) that made it bolt
     * "6x faster than the guys" and abandon the caravan. The leash + driveLoaded shepherd do the rest.
     */
    private static void tameCartPace(EntityLiving cart) {
        try {
            net.minecraft.entity.ai.attributes.IAttributeInstance ms =
                    cart.getEntityAttribute(net.minecraft.entity.SharedMonsterAttributes.MOVEMENT_SPEED);
            if (ms != null && ms.getBaseValue() > 0.16) ms.setBaseValue(0.16);
        } catch (Throwable ignored) {}
        try {
            java.util.List<net.minecraft.entity.ai.EntityAITasks.EntityAITaskEntry> drop = new ArrayList<>();
            for (net.minecraft.entity.ai.EntityAITasks.EntityAITaskEntry e : cart.tasks.taskEntries) {
                if (e == null || e.action == null) continue;
                String n = e.action.getClass().getSimpleName().toLowerCase();
                if (n.contains("panic") || n.contains("runaround") || n.contains("avoidentity")) drop.add(e);
            }
            for (net.minecraft.entity.ai.EntityAITasks.EntityAITaskEntry e : drop) cart.tasks.removeTask(e.action);
        } catch (Throwable ignored) {}
    }

    /** Roll the config cargo table (WarLevelsConfig traffic.cartCargo) into the cart's inventory. */
    private void fillCartCargo(WorldServer world, Entity cart) {
        java.util.List<studio.ERM.war.config.WarLevelsConfig.CartCargoEntry> table =
                studio.ERM.war.config.WarLevelsConfig.trafficCartCargo();
        if (cart == null || table.isEmpty()) return;
        java.util.Random rng = new java.util.Random(id.getMostSignificantBits() ^ world.getTotalWorldTime());
        int stocked = 0;
        for (studio.ERM.war.config.WarLevelsConfig.CartCargoEntry e : table) {
            if (e == null || e.itemId == null || e.itemId.trim().isEmpty()) continue;
            if (rng.nextDouble() >= e.chance) continue;
            net.minecraft.item.ItemStack stack = parseItemStack(e.itemId.trim(),
                    e.minCount + rng.nextInt(e.maxCount - e.minCount + 1));
            if (stack.isEmpty()) continue;
            net.minecraft.item.ItemStack rest =
                    studio.ERM.war.BattleManagers.core.ChestCartHelper.insertIntoCart(cart, stack);
            if (rest.getCount() < stack.getCount() || rest.isEmpty()) stocked++;
        }
        if (stocked > 0)
            EpochRunnerMod.logger.info("[Strategic] trader cart stocked with " + stocked + " cargo roll(s)");
    }

    /** "modid:name" or "modid:name@meta" -> an ItemStack of {@code count} (EMPTY when the id is unknown). */
    private static net.minecraft.item.ItemStack parseItemStack(String id, int count) {
        try {
            int meta = 0;
            String name = id;
            int at = id.indexOf('@');
            if (at > 0) {
                name = id.substring(0, at);
                try { meta = Integer.parseInt(id.substring(at + 1)); } catch (NumberFormatException ignored) {}
            }
            net.minecraft.item.Item item = net.minecraft.item.Item.getByNameOrId(name);
            if (item == null) return net.minecraft.item.ItemStack.EMPTY;
            return new net.minecraft.item.ItemStack(item, Math.max(1, count), Math.max(0, meta));
        } catch (Throwable t) {
            return net.minecraft.item.ItemStack.EMPTY;
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
        tag.setBoolean("cargoFilled", cargoFilled);
        return tag;
    }

    @Override
    public void readFromNBT(NBTTagCompound tag) {
        super.readFromNBT(tag);
        level = Math.max(1, Math.min(10, tag.getInteger("level") == 0 ? 3 : tag.getInteger("level")));
        escorts = Math.max(0, tag.getInteger("escorts"));
        cargoFilled = tag.getBoolean("cargoFilled");
    }
}
