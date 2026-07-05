package studio.ERM.war.BattleManagers.client;

import net.minecraft.client.renderer.BufferBuilder;
import net.minecraft.client.renderer.GlStateManager;
import net.minecraft.client.renderer.Tessellator;
import net.minecraft.client.renderer.entity.Render;
import net.minecraft.client.renderer.entity.RenderManager;
import net.minecraft.client.renderer.vertex.DefaultVertexFormats;
import net.minecraft.entity.Entity;
import net.minecraft.util.ResourceLocation;
import net.minecraftforge.fml.relauncher.Side;
import net.minecraftforge.fml.relauncher.SideOnly;
import org.lwjgl.opengl.GL11;

/**
 * Replaces Flan's default white-cube bullet render with a scaled glowing TRACER streak.
 *
 * The "little white cube" the user wanted gone is Flan's {@code EntityBullet} ENTITY (not an item -- an
 * item-model hijack cannot touch it), so the only fix is a custom entity renderer. This one is fully
 * self-contained: no texture asset required. It draws a motion-aligned quad streak (bright core + faint
 * tail) so rounds read as visible tracers in flight, plus a tiny bright head so slow/near-stationary
 * rounds still show. Additive blending + disabled lighting make it glow regardless of time of day.
 *
 * Registered (override) for com.flansmod.common.guns.EntityBullet in the client render registrar.
 */
@SideOnly(Side.CLIENT)
public class RenderBulletTracer extends Render<Entity> {

    public RenderBulletTracer(RenderManager manager) {
        super(manager);
    }

    @Override
    protected ResourceLocation getEntityTexture(Entity entity) {
        return null; // untextured -- we draw colored geometry
    }

    @Override
    public boolean shouldRender(Entity entity, net.minecraft.client.renderer.culling.ICamera camera,
                                double camX, double camY, double camZ) {
        return true; // tiny fast entities cull badly on their AABB; always attempt
    }

    @Override
    public void doRender(Entity entity, double x, double y, double z, float entityYaw, float partialTicks) {
        // IRL effect: most rounds are a small DARK bullet; every ~5th is a glowing TRACER. The old renderer
        // drew a bright WHITE head-dot on EVERY round (the "lots of white cubes"). Use the entity id as the
        // shot counter (bullets spawn sequentially, so id%5 lands ~every 5th round).
        boolean tracer = (entity.getEntityId() % 5 == 0);

        GlStateManager.pushMatrix();
        GlStateManager.translate(x, y, z);
        GlStateManager.disableLighting();
        GlStateManager.disableTexture2D();
        GlStateManager.enableBlend();
        GlStateManager.depthMask(false);

        Tessellator tess = Tessellator.getInstance();
        BufferBuilder buf = tess.getBuffer();

        if (tracer) {
            GlStateManager.blendFunc(GL11.GL_SRC_ALPHA, GL11.GL_ONE); // additive glow

            double mx = entity.motionX, my = entity.motionY, mz = entity.motionZ;
            double speed = Math.sqrt(mx * mx + my * my + mz * mz);
            double len = Math.min(2.4, Math.max(0.6, speed * 1.7));
            double bx, by, bz;
            if (speed > 1.0e-4) { bx = -mx / speed * len; by = -my / speed * len; bz = -mz / speed * len; }
            else { bx = 0; by = 0; bz = -len; }

            float ry = this.renderManager.playerViewY;
            double half = 0.06;
            double px = Math.cos(Math.toRadians(ry)) * half;
            double pz = Math.sin(Math.toRadians(ry)) * half;

            // Bright yellow-white head fading to transparent orange tail.
            buf.begin(GL11.GL_QUADS, DefaultVertexFormats.POSITION_COLOR);
            buf.pos(px, 0, pz).color(1.0f, 0.95f, 0.6f, 0.9f).endVertex();
            buf.pos(-px, 0, -pz).color(1.0f, 0.95f, 0.6f, 0.9f).endVertex();
            buf.pos(bx - px, by, bz - pz).color(1.0f, 0.5f, 0.1f, 0.0f).endVertex();
            buf.pos(bx + px, by, bz + pz).color(1.0f, 0.5f, 0.1f, 0.0f).endVertex();
            tess.draw();

            double h = 0.10;
            buf.begin(GL11.GL_QUADS, DefaultVertexFormats.POSITION_COLOR);
            buf.pos(-h, -h, 0).color(1.0f, 1.0f, 0.85f, 1.0f).endVertex();
            buf.pos(h, -h, 0).color(1.0f, 1.0f, 0.85f, 1.0f).endVertex();
            buf.pos(h, h, 0).color(1.0f, 1.0f, 0.85f, 1.0f).endVertex();
            buf.pos(-h, h, 0).color(1.0f, 1.0f, 0.85f, 1.0f).endVertex();
            tess.draw();
        } else {
            // Normal round: a small DARK billboard. NOT additive (black + additive = invisible); normal alpha.
            GlStateManager.blendFunc(GL11.GL_SRC_ALPHA, GL11.GL_ONE_MINUS_SRC_ALPHA);
            GlStateManager.rotate(-this.renderManager.playerViewY, 0.0F, 1.0F, 0.0F);
            GlStateManager.rotate(this.renderManager.playerViewX, 1.0F, 0.0F, 0.0F);
            double h = 0.07;
            buf.begin(GL11.GL_QUADS, DefaultVertexFormats.POSITION_COLOR);
            buf.pos(-h, -h, 0).color(0.04f, 0.04f, 0.04f, 1.0f).endVertex();
            buf.pos(h, -h, 0).color(0.04f, 0.04f, 0.04f, 1.0f).endVertex();
            buf.pos(h, h, 0).color(0.04f, 0.04f, 0.04f, 1.0f).endVertex();
            buf.pos(-h, h, 0).color(0.04f, 0.04f, 0.04f, 1.0f).endVertex();
            tess.draw();
        }

        GlStateManager.depthMask(true);
        GlStateManager.disableBlend();
        GlStateManager.enableTexture2D();
        GlStateManager.enableLighting();
        GlStateManager.color(1.0f, 1.0f, 1.0f, 1.0f);
        GlStateManager.popMatrix();
    }
}
