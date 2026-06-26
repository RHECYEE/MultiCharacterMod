package studio.ERM.war.air;

import java.util.ArrayList;
import java.util.List;
import java.util.HashMap;
import java.util.Map;
import java.util.HashSet;
import java.util.Set;

import com.flansmod.client.handlers.FlansModResourceHandler;
import com.flansmod.client.model.ModelPlane;
import com.flansmod.common.driveables.DriveableData;
import com.flansmod.common.driveables.DriveableType;
import com.flansmod.common.driveables.EntityPlane;
import com.flansmod.common.driveables.PlaneType;
import com.flansmod.common.guns.Paintjob;
import com.flansmod.common.types.InfoType;

import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.BufferBuilder;
import net.minecraft.client.renderer.GlStateManager;
import net.minecraft.client.renderer.OpenGlHelper;
import net.minecraft.client.renderer.RenderHelper;
import net.minecraft.client.renderer.Tessellator;
import net.minecraft.client.renderer.culling.ICamera;
import net.minecraft.client.renderer.entity.Render;
import net.minecraft.client.renderer.entity.RenderManager;
import net.minecraft.client.renderer.texture.TextureMap;
import net.minecraft.client.renderer.vertex.DefaultVertexFormats;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.util.ResourceLocation;
import net.minecraft.world.World;

import net.minecraftforge.client.event.RenderWorldLastEvent;
import net.minecraftforge.event.world.WorldEvent;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.fml.client.registry.IRenderFactory;
import net.minecraftforge.fml.common.eventhandler.EventPriority;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import net.minecraftforge.fml.relauncher.Side;
import net.minecraftforge.fml.relauncher.SideOnly;

import studio.ERM.EpochRunnerMod;

@SideOnly(Side.CLIENT)
public class RenderGhostAircraft extends Render<EntityGhostAircraft> {

    private static final Map<Integer, EntityPlane> dummyPlanes = new HashMap<>();
    private static final Map<Integer, String> dummyPlaneTypes = new HashMap<>();
    private static final Set<String> loggedTypeFailures = new HashSet<>();
    // Ghost ids whose dummy plane is being built off the render thread (so we don't schedule twice).
    private static final Set<Integer> pendingDummy = new HashSet<>();
    private static boolean EVENT_REGISTERED = false;

    public RenderGhostAircraft(RenderManager renderManager) {
        super(renderManager);
        this.shadowSize = 2.0F;

        if (!EVENT_REGISTERED) {
            EVENT_REGISTERED = true;
            // MinecraftForge.EVENT_BUS.register(this); // disabled (render-only class)
        }
    }

    @Override
    public void doRender(EntityGhostAircraft ghost, double x, double y, double z, float entityYaw, float partialTicks) {
        if (!renderGhostFlanModel(ghost, x, y, z, entityYaw, partialTicks)) {
            renderFallbackPlaceholder(ghost, x, y, z, entityYaw);
        }
    }

    @Override
    public boolean shouldRender(EntityGhostAircraft entity, ICamera camera, double camX, double camY, double camZ) {
        return true;
    }

    @Override
    protected ResourceLocation getEntityTexture(EntityGhostAircraft entity) {
        return TextureMap.LOCATION_MISSING_TEXTURE;
    }

    @SubscribeEvent
    public void onWorldUnload(WorldEvent.Unload event) {
        if (event.getWorld().isRemote) {
            dummyPlanes.clear();
            dummyPlaneTypes.clear();
            loggedTypeFailures.clear();
        }
    }

    // RenderWorldLastEvent handler removed. Rendering is handled via the normal entity renderer (doRender).
    // NOTE: there must be NO @SubscribeEvent annotation on this private method. A leftover one here
    // made Forge's EventSubscriberTransformer throw "Cannot apply @SubscribeEvent to private method"
    // at class-load, so RenderGhostAircraft failed with NoClassDefFoundError and airstrikes never
    // showed a real Flan plane (the renderer simply could not load). Flan 5.10.0 IS installed.
    private boolean renderGhostFlanModel(EntityGhostAircraft ghost, double x, double y, double z, float entityYaw, float pt) {
        PlaneType type = resolvePlaneType(ghost);
        if (type == null || type.model == null || !(type.model instanceof ModelPlane)) {
            logTypeFailureOnce(ghost, type);
            return false;
        }

        EntityPlane dummy = getOrCreateDummyPlane(ghost, type);
        if (dummy == null) {
            logTypeFailureOnce(ghost, type);
            return false;
        }

        syncDummyFromGhost(dummy, ghost);
        bindTexture(getPlaneTexture(dummy, type));

        GlStateManager.pushMatrix();
        GlStateManager.translate(x, y, z);

        float yaw = ghost.prevRotationYaw + (ghost.rotationYaw - ghost.prevRotationYaw) * pt;
        float pitch = ghost.getPitchAngle();
        float roll = ghost.getBankAngle();

        // FIX: Combine the model offset (-90) into the yaw rotation directly.
        // This fixes sideways flight without breaking the local axis for pitch/roll.
        GlStateManager.rotate(180F - yaw - 90F, 0F, 1F, 0F);
        GlStateManager.rotate(pitch, 0F, 0F, 1F);
        GlStateManager.rotate(roll, 1F, 0F, 0F);

        float modelScale = type.modelScale <= 0F ? 1F : type.modelScale;
        GlStateManager.scale(modelScale, modelScale, modelScale);

        try {
            ((ModelPlane) type.model).render(dummy, pt);
        } catch (Throwable t) {
            EpochRunnerMod.logger.error("[GHOST-AIR][RENDER] ModelPlane.render failed for " + ghost.getAircraftType(), t);
            GlStateManager.popMatrix();
            return false;
        }

        GlStateManager.popMatrix();
        return true;
    }

    private PlaneType resolvePlaneType(EntityGhostAircraft ghost) {
        String aircraftType = ghost.getAircraftType();
        if (aircraftType == null) return null;

        if (aircraftType.contains(":")) aircraftType = aircraftType.substring(aircraftType.indexOf(':') + 1);
        aircraftType = aircraftType.trim();
        if (aircraftType.isEmpty()) return null;

        try {
            PlaneType t = PlaneType.getPlane(aircraftType);
            if (t != null) return t;
        } catch (Throwable ignored) { }

        try {
            for (PlaneType t : PlaneType.types) {
                if (t != null && t.shortName != null && t.shortName.equalsIgnoreCase(aircraftType)) return t;
            }
        } catch (Throwable ignored) { }

        try {
            InfoType it = InfoType.getType(aircraftType);
            if (it instanceof PlaneType) return (PlaneType) it;
        } catch (Throwable ignored) { }

        try {
            if (InfoType.infoTypes != null) {
                for (InfoType it : InfoType.infoTypes.values()) {
                    if (it != null && it.shortName != null && it.shortName.equalsIgnoreCase(aircraftType) && it instanceof PlaneType) return (PlaneType) it;
                }
            }
        } catch (Throwable ignored) { }

        return null;
    }

    private ResourceLocation getPlaneTexture(EntityPlane plane, PlaneType type) {
        try {
            DriveableType dt = plane.getDriveableType();
            if (dt != null && plane.getDriveableData() != null) {
                Paintjob pj = dt.getPaintjob(plane.getDriveableData().paintjobID);
                if (pj != null) return FlansModResourceHandler.getPaintjobTexture(pj);
            }
        } catch (Throwable ignored) { }

        try {
            ResourceLocation rl = FlansModResourceHandler.getTexture(type);
            if (rl != null) return rl;
        } catch (Throwable ignored) { }

        return TextureMap.LOCATION_MISSING_TEXTURE;
    }

    private EntityPlane getOrCreateDummyPlane(EntityGhostAircraft ghost, PlaneType type) {
        final int id = ghost.getEntityId();
        final String shortName = type.shortName;

        EntityPlane existing = dummyPlanes.get(id);
        if (existing != null && shortName.equals(dummyPlaneTypes.get(id))) return existing;

        // DO NOT construct the EntityPlane here. We are inside RenderGlobal's entity-render loop, and
        // Flan's plane constructor spawns seat entities into the world -> mutating the entity list
        // while it's being iterated -> ConcurrentModificationException crash. Build the dummy off the
        // render thread (between frames) and render the fallback box until it's ready.
        if (pendingDummy.add(id)) {
            final double px = ghost.posX, py = ghost.posY, pz = ghost.posZ;
            final PlaneType ptype = type;
            Minecraft.getMinecraft().addScheduledTask(() -> {
                try {
                    World world = Minecraft.getMinecraft().world;
                    if (world != null) {
                        NBTTagCompound tag = new NBTTagCompound();
                        tag.setString("Type", shortName);
                        tag.setString("driveableType", shortName);
                        tag.setInteger("paintjobID", 0);
                        DriveableData data = new DriveableData(tag);
                        EntityPlane plane = new EntityPlane(world, px, py, pz, ptype, data);
                        removeDummySeats(plane); // we only need the plane object for the model
                        dummyPlanes.put(id, plane);
                        dummyPlaneTypes.put(id, shortName);
                    }
                } catch (Throwable t) {
                    EpochRunnerMod.logger.warn("[GHOST-AIR] dummy plane build failed for " + shortName + ": " + t.getMessage());
                }
                pendingDummy.remove(id);
            });
        }
        return null;
    }

    /** Kill the seat entities Flan's plane constructor spawns (reflective; field name varies safely). */
    private static void removeDummySeats(Object plane) {
        for (String fieldName : new String[]{"seats", "field_seats"}) {
            try {
                java.lang.reflect.Field f = com.flansmod.common.driveables.EntityDriveable.class.getDeclaredField(fieldName);
                f.setAccessible(true);
                Object seats = f.get(plane);
                if (seats instanceof Object[]) {
                    for (Object s : (Object[]) seats) {
                        if (s instanceof net.minecraft.entity.Entity) ((net.minecraft.entity.Entity) s).setDead();
                    }
                    return;
                }
            } catch (Throwable ignored) {}
        }
    }

    private void syncDummyFromGhost(EntityPlane dummy, EntityGhostAircraft ghost) {
        try {
            dummy.setPosition(ghost.posX, ghost.posY, ghost.posZ);
            dummy.prevPosX = ghost.prevPosX;
            dummy.prevPosY = ghost.prevPosY;
            dummy.prevPosZ = ghost.prevPosZ;
            dummy.prevRotationYaw = ghost.prevRotationYaw;
            dummy.prevRotationPitch = ghost.prevRotationPitch;
            dummy.rotationYaw = ghost.rotationYaw;
            dummy.rotationPitch = ghost.rotationPitch;

            if (dummy.axes != null) {
                dummy.axes.setAngles(ghost.rotationYaw, ghost.getPitchAngle(), ghost.getBankAngle());
            }

            dummy.throttle = 1.0F;
            dummy.propAngle = (ghost.ticksExisted % 360) * 0.017453292F;
        } catch (Throwable ignored) { }
    }

    private void logTypeFailureOnce(EntityGhostAircraft ghost, PlaneType type) {
        String key = String.valueOf(ghost.getAircraftType());
        if (loggedTypeFailures.contains(key)) return;
        loggedTypeFailures.add(key);

        String msg = "[GHOST-AIR][RENDER] Falling back for aircraftType='" + key + "' (PlaneType=" +
                (type == null ? "null" : type.shortName) + ", model=" + (type == null ? "null" : String.valueOf(type.model)) + ")";
        EpochRunnerMod.logger.warn(msg);
    }

    private void renderFallbackPlaceholder(EntityGhostAircraft entity, double x, double y, double z, float entityYaw) {
        GlStateManager.pushMatrix();
        GlStateManager.translate(x, y, z);
        GlStateManager.rotate(180F - entityYaw, 0.0F, 1.0F, 0.0F);
        GlStateManager.rotate(entity.getPitchAngle(), 0.0F, 0.0F, 1.0F);
        GlStateManager.rotate(entity.getBankAngle(), 1.0F, 0.0F, 0.0F);

        GlStateManager.disableTexture2D();
        GlStateManager.enableBlend();
        GlStateManager.disableCull();

        String team = entity.getMcmTeam();
        if ("PLAYER".equals(team)) GlStateManager.color(0.2F, 0.5F, 1.0F, 0.8F);
        else GlStateManager.color(1.0F, 0.3F, 0.2F, 0.8F);

        drawBox(-2, -0.5, -1, 4, 1, 2);
        drawBox(-0.5, 0, -4, 1, 0.2, 8);
        drawBox(-0.3, -0.5, 2, 0.6, 1.5, 0.3);

        GlStateManager.enableCull();
        GlStateManager.enableTexture2D();
        GlStateManager.disableBlend();
        GlStateManager.color(1F, 1F, 1F, 1F);
        GlStateManager.popMatrix();
    }

    private void drawBox(double x1, double y1, double z1, double w, double h, double d) {
        Tessellator tessellator = Tessellator.getInstance();
        BufferBuilder buffer = tessellator.getBuffer();

        double x2 = x1 + w;
        double y2 = y1 + h;
        double z2 = z1 + d;

        buffer.begin(7, DefaultVertexFormats.POSITION);
        buffer.pos(x1, y1, z1).endVertex();
        buffer.pos(x2, y1, z1).endVertex();
        buffer.pos(x2, y1, z2).endVertex();
        buffer.pos(x1, y1, z2).endVertex();
        buffer.pos(x1, y2, z2).endVertex();
        buffer.pos(x2, y2, z2).endVertex();
        buffer.pos(x2, y2, z1).endVertex();
        buffer.pos(x1, y2, z1).endVertex();
        buffer.pos(x1, y1, z1).endVertex();
        buffer.pos(x1, y2, z1).endVertex();
        buffer.pos(x2, y2, z1).endVertex();
        buffer.pos(x2, y1, z1).endVertex();
        buffer.pos(x2, y1, z2).endVertex();
        buffer.pos(x2, y2, z2).endVertex();
        buffer.pos(x1, y2, z2).endVertex();
        buffer.pos(x1, y1, z2).endVertex();
        buffer.pos(x1, y1, z2).endVertex();
        buffer.pos(x1, y2, z2).endVertex();
        buffer.pos(x1, y2, z1).endVertex();
        buffer.pos(x1, y1, z1).endVertex();
        buffer.pos(x2, y1, z1).endVertex();
        buffer.pos(x2, y2, z1).endVertex();
        buffer.pos(x2, y2, z2).endVertex();
        buffer.pos(x2, y1, z2).endVertex();
        tessellator.draw();
    }

    public static class Factory implements IRenderFactory<EntityGhostAircraft> {
        @Override
        public Render<? super EntityGhostAircraft> createRenderFor(RenderManager manager) {
            return new RenderGhostAircraft(manager);
        }
    }
    public static void updateDummy(EntityGhostAircraft ghost) {
        int id = ghost.getEntityId();
        String typeName = ghost.getAircraftType();

        if (!dummyPlanes.containsKey(id)) {
            PlaneType type = PlaneType.getPlane(typeName);
            if (type == null) return;

            NBTTagCompound tag = new NBTTagCompound();
            tag.setString("Type", type.shortName);
            DriveableData data = new DriveableData(tag);
            EntityPlane plane = new EntityPlane(ghost.world, ghost.posX, ghost.posY, ghost.posZ, type, data);

            dummyPlanes.put(id, plane);
            dummyPlaneTypes.put(id, type.shortName);
        }

        EntityPlane dummy = dummyPlanes.get(id);
        if (dummy != null) {
            dummy.posX = ghost.posX;
            dummy.posY = ghost.posY;
            dummy.posZ = ghost.posZ;
            dummy.rotationYaw = ghost.rotationYaw;
            dummy.rotationPitch = ghost.rotationPitch;
        }
    }
}