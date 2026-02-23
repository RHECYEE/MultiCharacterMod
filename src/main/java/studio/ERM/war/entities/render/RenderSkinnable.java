package studio.ERM.war.entities.render;

import net.minecraft.client.model.ModelBiped;
import net.minecraft.client.renderer.entity.RenderBiped;
import net.minecraft.client.renderer.entity.RenderManager;
import net.minecraft.entity.Entity;
import net.minecraft.util.ResourceLocation;
import net.minecraftforge.fml.relauncher.Side;
import net.minecraftforge.fml.relauncher.SideOnly;

import studio.ERM.war.skins.SkinTextureCache;

/**
 * EXAMPLE: Generic renderer for any ISkinnable entity.
 * 
 * This single renderer class can handle ANY entity that uses the skin pool system.
 * Just register it with your entity class in your client proxy.
 * 
 * Usage in ClientProxy:
 * <pre>
 * RenderingRegistry.registerEntityRenderingHandler(
 *     YourEntity.class, 
 *     RenderSkinnable::new
 * );
 * </pre>
 */
@SideOnly(Side.CLIENT)
public class RenderSkinnable<T extends Entity> extends RenderBiped<T> {

    public RenderSkinnable(RenderManager manager) {
        super(manager, new ModelBiped(0.0F), 0.5F);
    }

    public RenderSkinnable(RenderManager manager, ModelBiped model, float shadowSize) {
        super(manager, model, shadowSize);
    }

    @Override
    protected ResourceLocation getEntityTexture(T entity) {
        // The cache handles everything: ISkinnable check, NBT fallback, texture loading
        return SkinTextureCache.getTexture(entity);
    }
}
