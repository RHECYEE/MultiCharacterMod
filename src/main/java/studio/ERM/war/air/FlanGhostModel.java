package studio.ERM.war.air;

import com.flansmod.client.handlers.FlansModResourceHandler;
import com.flansmod.client.model.ModelPlane;
import com.flansmod.common.driveables.CollisionBox;
import com.flansmod.common.driveables.DriveableData;
import com.flansmod.common.driveables.DriveablePart;
import com.flansmod.common.driveables.DriveableType;
import com.flansmod.common.driveables.EntityDriveable;
import com.flansmod.common.driveables.EntityPlane;
import com.flansmod.common.driveables.EnumDriveablePart;
import com.flansmod.common.driveables.PlaneType;
import com.flansmod.common.guns.Paintjob;
import com.flansmod.common.parts.EnumPartCategory;
import com.flansmod.common.parts.PartType;
import com.flansmod.common.types.InfoType;

import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.GlStateManager;
import net.minecraft.client.renderer.texture.TextureMap;
import net.minecraft.entity.Entity;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.util.ResourceLocation;
import net.minecraft.world.World;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import net.minecraftforge.fml.common.gameevent.TickEvent;
import net.minecraftforge.fml.relauncher.Side;
import net.minecraftforge.fml.relauncher.SideOnly;

import studio.ERM.EpochRunnerMod;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * ISOLATED real-Flan-model renderer for ghost aircraft. Kept SEPARATE from the registered renderer
 * ({@link RenderGhostAircraftSafe}) so that if any Flan class here fails to load/resolve, only THIS
 * class fails -- the box renderer (no Flan imports) still draws, so aircraft are never invisible.
 *
 * The CME blocker is solved: the dummy {@link EntityPlane} (needed only as the model's render context)
 * is built in a client TICK (phase START), OUTSIDE RenderGlobal's entity iteration, with COMPLETE NBT
 * (Type + Engine + per-part health) like the working tank build -- an incomplete DriveableData was why
 * the model never rendered. Planes AND helicopters are Flan {@code PlaneType}, so both draw.
 */
@SideOnly(Side.CLIENT)
public final class FlanGhostModel {

    private FlanGhostModel() {}

    private static final Map<Integer, EntityPlane> dummies = new HashMap<>();
    private static final Map<Integer, String> dummyTypes = new HashMap<>();
    private static final Set<Integer> pending = new HashSet<>();
    private static boolean registered = false;

    /** Draw the real model if its dummy is ready; otherwise request a build and return false (-> box). */
    public static boolean render(EntityGhostAircraft ghost, double x, double y, double z, float pt) {
        ensureRegistered();
        PlaneType type = resolve(ghost);
        if (type == null || type.model == null || !(type.model instanceof ModelPlane)) return false;

        int id = ghost.getEntityId();
        EntityPlane dummy = dummies.get(id);
        if (dummy == null || !type.shortName.equals(dummyTypes.get(id))) {
            pending.add(id);   // build it next tick (off the render loop)
            return false;      // box this frame
        }

        syncDummy(dummy, ghost);
        bindTexture(getTexture(dummy, type));

        GlStateManager.pushMatrix();
        GlStateManager.translate(x, y, z);
        float yaw = ghost.prevRotationYaw + (ghost.rotationYaw - ghost.prevRotationYaw) * pt;
        GlStateManager.rotate(180F - yaw - 90F, 0F, 1F, 0F);
        GlStateManager.rotate(ghost.getPitchAngle(), 0F, 0F, 1F);
        GlStateManager.rotate(ghost.getBankAngle(), 1F, 0F, 0F);
        float sc = type.modelScale <= 0F ? 1F : type.modelScale;
        GlStateManager.scale(sc, sc, sc);
        try {
            ((ModelPlane) type.model).render(dummy, pt);
        } catch (Throwable t) {
            GlStateManager.popMatrix();
            return false;
        }
        GlStateManager.popMatrix();
        return true;
    }

    private static void ensureRegistered() {
        if (registered) return;
        registered = true;
        try { MinecraftForge.EVENT_BUS.register(FlanGhostModel.class); } catch (Throwable ignored) {}
    }

    /** Build pending dummy planes at TICK START -- outside the render loop, so seat-spawn can't CME. */
    @SubscribeEvent
    public static void onClientTick(TickEvent.ClientTickEvent e) {
        if (e.phase != TickEvent.Phase.START || pending.isEmpty()) return;
        World world = Minecraft.getMinecraft().world;
        if (world == null) { pending.clear(); return; }
        for (Integer id : new ArrayList<>(pending)) {
            pending.remove(id);
            try {
                Entity ent = world.getEntityByID(id);
                if (!(ent instanceof EntityGhostAircraft)) continue;
                PlaneType type = resolve((EntityGhostAircraft) ent);
                if (type == null) continue;
                EntityPlane plane = buildDummy(world, (EntityGhostAircraft) ent, type);
                if (plane != null) { dummies.put(id, plane); dummyTypes.put(id, type.shortName); }
            } catch (Throwable t) {
                EpochRunnerMod.logger.warn("[GHOST-AIR] dummy build failed for id " + id + ": " + t.getMessage());
            }
        }
        // forget dummies whose ghost is gone
        for (Integer id : new ArrayList<>(dummies.keySet())) {
            if (world.getEntityByID(id) == null) { dummies.remove(id); dummyTypes.remove(id); }
        }
    }

    /** COMPLETE driveable data (Type + Engine + per-part health), as the working tank build does. */
    private static EntityPlane buildDummy(World world, EntityGhostAircraft ghost, PlaneType type) {
        NBTTagCompound tag = new NBTTagCompound();
        tag.setString("Type", type.shortName);
        String engine = "rotaryEngine";
        try {
            if (PartType.parts != null) {
                for (PartType p : PartType.parts) {
                    if (p.category == EnumPartCategory.ENGINE) { engine = p.shortName; break; }
                }
            }
        } catch (Throwable ignored) {}
        tag.setString("Engine", engine);
        tag.setInteger("paintjobID", 0);

        DriveableData data = new DriveableData(tag, type.numCargoSlots);
        try {
            for (EnumDriveablePart part : EnumDriveablePart.values()) {
                CollisionBox box = type.health.get(part);
                if (box != null) {
                    DriveablePart dp = new DriveablePart(part, box);
                    dp.health = dp.maxHealth;
                    dp.dead = false;
                    data.parts.put(part, dp);
                }
            }
        } catch (Throwable ignored) {}

        EntityPlane plane = new EntityPlane(world, ghost.posX, ghost.posY, ghost.posZ, type, data);
        removeSeats(plane); // only the plane object is needed for the model; kill its seat entities
        return plane;
    }

    private static void syncDummy(EntityPlane dummy, EntityGhostAircraft ghost) {
        try {
            dummy.setPosition(ghost.posX, ghost.posY, ghost.posZ);
            dummy.rotationYaw = ghost.rotationYaw;
            dummy.rotationPitch = ghost.rotationPitch;
            if (dummy.axes != null) dummy.axes.setAngles(ghost.rotationYaw, ghost.getPitchAngle(), ghost.getBankAngle());
            dummy.throttle = 1.0F;
            dummy.propAngle = (ghost.ticksExisted % 360) * 0.017453292F;
        } catch (Throwable ignored) {}
    }

    private static PlaneType resolve(EntityGhostAircraft ghost) {
        String name = ghost.getAircraftType();
        if (name == null) return null;
        if (name.contains(":")) name = name.substring(name.indexOf(':') + 1);
        name = name.trim();
        if (name.isEmpty()) return null;
        try { PlaneType t = PlaneType.getPlane(name); if (t != null) return t; } catch (Throwable ignored) {}
        try {
            InfoType it = InfoType.getType(name);
            if (it instanceof PlaneType) return (PlaneType) it;
        } catch (Throwable ignored) {}
        try {
            if (InfoType.infoTypes != null) {
                for (InfoType it : InfoType.infoTypes.values()) {
                    if (it instanceof PlaneType && it.shortName != null && it.shortName.equalsIgnoreCase(name)) return (PlaneType) it;
                }
            }
        } catch (Throwable ignored) {}
        return null;
    }

    private static ResourceLocation getTexture(EntityPlane plane, PlaneType type) {
        try {
            DriveableType dt = plane.getDriveableType();
            if (dt != null && plane.getDriveableData() != null) {
                Paintjob pj = dt.getPaintjob(plane.getDriveableData().paintjobID);
                if (pj != null) return FlansModResourceHandler.getPaintjobTexture(pj);
            }
        } catch (Throwable ignored) {}
        try {
            ResourceLocation rl = FlansModResourceHandler.getTexture(type);
            if (rl != null) return rl;
        } catch (Throwable ignored) {}
        return TextureMap.LOCATION_MISSING_TEXTURE;
    }

    private static void bindTexture(ResourceLocation rl) {
        try { Minecraft.getMinecraft().getTextureManager().bindTexture(rl); } catch (Throwable ignored) {}
    }

    private static void removeSeats(Object plane) {
        for (String fieldName : new String[]{"seats", "field_seats"}) {
            try {
                java.lang.reflect.Field f = EntityDriveable.class.getDeclaredField(fieldName);
                f.setAccessible(true);
                Object seats = f.get(plane);
                if (seats instanceof Object[]) {
                    for (Object s : (Object[]) seats) {
                        if (s instanceof Entity) ((Entity) s).setDead();
                    }
                    return;
                }
            } catch (Throwable ignored) {}
        }
    }
}
