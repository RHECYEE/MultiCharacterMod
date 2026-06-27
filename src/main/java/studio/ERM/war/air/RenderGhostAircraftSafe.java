package studio.ERM.war.air;

import net.minecraft.client.renderer.BufferBuilder;
import net.minecraft.client.renderer.GlStateManager;
import net.minecraft.client.renderer.Tessellator;
import net.minecraft.client.renderer.culling.ICamera;
import net.minecraft.client.renderer.entity.Render;
import net.minecraft.client.renderer.entity.RenderManager;
import net.minecraft.client.renderer.texture.TextureMap;
import net.minecraft.client.renderer.vertex.DefaultVertexFormats;
import net.minecraft.util.ResourceLocation;
import net.minecraftforge.fml.client.registry.IRenderFactory;
import net.minecraftforge.fml.relauncher.Side;
import net.minecraftforge.fml.relauncher.SideOnly;

/**
 * Flan-free placeholder renderer for airstrike ghost aircraft.
 *
 * The full renderer ({@link RenderGhostAircraft}) draws the real Flan plane model, but it imports
 * several Flan classes directly (EntityPlane, ModelPlane, PlaneType, ...). In packs where one of
 * those classes is not resolvable at load time, loading RenderGhostAircraft throws
 * NoClassDefFoundError -- which crashed game init the moment its renderer registration actually
 * started firing (it had been silently skipped before because its registrar's @EventBusSubscriber
 * had no modid).
 *
 * This renderer has ZERO Flan dependencies: it draws a simple team-coloured airframe box. Airstrike
 * aircraft are therefore always visible (as a clear call-in marker) and can never crash the client.
 * Box geometry is copied verbatim from RenderGhostAircraft's own fallback so the visual is identical
 * to what already rendered for resolvable aircraft.
 */
@SideOnly(Side.CLIENT)
public class RenderGhostAircraftSafe extends Render<EntityGhostAircraft> {

    public RenderGhostAircraftSafe(RenderManager renderManager) {
        super(renderManager);
        this.shadowSize = 2.0F;
    }

    @Override
    public boolean shouldRender(EntityGhostAircraft entity, ICamera camera, double camX, double camY, double camZ) {
        return true;
    }

    @Override
    protected ResourceLocation getEntityTexture(EntityGhostAircraft entity) {
        return TextureMap.LOCATION_MISSING_TEXTURE;
    }

    @Override
    public void doRender(EntityGhostAircraft entity, double x, double y, double z, float entityYaw, float partialTicks) {
        // Attempt the REAL Flan model first, via the isolated FlanGhostModel (which has the Flan imports).
        // It's wrapped in try/catch so that if that class fails to load/render, this box renderer -- which
        // has ZERO Flan imports and so always loads -- still draws the aircraft. Never invisible.
        try {
            if (studio.ERM.war.air.FlanGhostModel.render(entity, x, y, z, partialTicks)) return;
        } catch (Throwable ignored) {}

        // Draw a simple team-coloured airframe. The "render nothing + drag a real Flan plane" approach
        // was abandoned because spawning a live Flan EntityPlane crashes the server (its physics tick
        // NPEs without a real pilot). This box has ZERO Flan dependencies, so airstrikes are visible
        // and the client can never crash. (Real Flan model rendering needs a path that never builds a
        // live EntityPlane -- both spawning one and constructing one in the renderer crash.)
        GlStateManager.pushMatrix();
        GlStateManager.translate(x, y, z);
        GlStateManager.rotate(180F - entityYaw, 0.0F, 1.0F, 0.0F);
        GlStateManager.rotate(entity.getPitchAngle(), 0.0F, 0.0F, 1.0F);
        GlStateManager.rotate(entity.getBankAngle(), 1.0F, 0.0F, 0.0F);

        GlStateManager.disableTexture2D();
        GlStateManager.disableLighting();
        GlStateManager.enableBlend();
        GlStateManager.disableCull();

        String team = entity.getMcmTeam();
        if ("PLAYER".equals(team)) GlStateManager.color(0.2F, 0.5F, 1.0F, 0.95F);
        else GlStateManager.color(0.80F, 0.82F, 0.88F, 0.97F); // light-grey enemy airframe

        drawBox(-2.0, -0.4, -1.0, 4.0, 0.8, 2.0);    // fuselage
        drawBox(-0.5, 0.0, -4.0, 1.0, 0.25, 8.0);    // wings
        drawBox(-0.3, -0.4, 2.0, 0.6, 1.2, 0.3);     // tail fin

        GlStateManager.enableCull();
        GlStateManager.enableLighting();
        GlStateManager.enableTexture2D();
        GlStateManager.disableBlend();
        GlStateManager.color(1.0F, 1.0F, 1.0F, 1.0F);
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
            return new RenderGhostAircraftSafe(manager);
        }
    }
}
