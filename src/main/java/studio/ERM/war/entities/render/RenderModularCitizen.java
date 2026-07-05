package studio.ERM.war.entities.render;

import net.minecraft.client.model.ModelBiped;
import net.minecraft.client.renderer.GlStateManager;
import net.minecraft.client.renderer.entity.RenderBiped;
import net.minecraft.client.renderer.entity.RenderManager;
import net.minecraft.util.ResourceLocation;
import net.minecraftforge.fml.relauncher.Side;
import net.minecraftforge.fml.relauncher.SideOnly;

import studio.ERM.war.entities.EntityModularCitizen;
import studio.ERM.war.skins.SkinTextureCache;

/**
 * Renderer for EntityModularCitizen.
 * 
 * UPDATED: Now uses SkinTextureCache which works with the new pool system.
 * This is a drop-in replacement - just changed one line in getEntityTexture().
 */
@SideOnly(Side.CLIENT)
public class RenderModularCitizen extends RenderBiped<EntityModularCitizen> {

    private static final ModelBiped MODEL = new ModelBiped(0.0F);

    public RenderModularCitizen(RenderManager renderManagerIn) {
        super(renderManagerIn, MODEL, 0.5F);
    }

    @Override
    protected ResourceLocation getEntityTexture(EntityModularCitizen entity) {
        // NEW: Use the unified texture cache
        // This handles ISkinnable entities, NBT fallback, and dynamic texture loading
        return SkinTextureCache.getTexture(entity);
    }

    @Override
    protected void applyRotations(EntityModularCitizen entityLiving, float ageInTicks, float rotationYaw, float partialTicks) {
        if (entityLiving != null && entityLiving.isSleepingPose()) {
            GlStateManager.translate(0.0F, 0.2F, 0.0F);
            GlStateManager.rotate(90.0F, 0.0F, 0.0F, 1.0F);
            GlStateManager.rotate(90.0F, 1.0F, 0.0F, 0.0F);
            return;
        }
        super.applyRotations(entityLiving, ageInTicks, rotationYaw, partialTicks);
    }
}
