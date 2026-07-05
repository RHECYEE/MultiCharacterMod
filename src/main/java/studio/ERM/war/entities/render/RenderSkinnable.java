package studio.ERM.war.entities.render;

import net.minecraft.client.model.ModelBiped;
import net.minecraft.client.model.ModelPlayer;
import net.minecraft.client.renderer.entity.RenderBiped;
import net.minecraft.client.renderer.entity.RenderManager;
import net.minecraft.client.renderer.entity.layers.LayerBipedArmor;
import net.minecraft.entity.EntityLiving;
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
public class RenderSkinnable<T extends EntityLiving> extends RenderBiped<T> {

    public RenderSkinnable(RenderManager manager) {
        // ModelPlayer (not plain ModelBiped): the AW2 skin-pack PNGs are 64x64 "modern" skins whose
        // detail lives partly on the second/overlay layer (jacket, sleeves, trouser, hat) and which
        // use separate left-arm/left-leg UVs. ModelBiped renders none of that, so those skins showed
        // see-through gaps and mirrored limbs. ModelPlayer renders the full overlay + per-limb UVs.
        super(manager, new ModelPlayer(0.0F, false), 0.5F);
        // RenderBiped in 1.12.2 adds LayerCustomHead/Elytra/HeldItem but NOT an armor layer (that lives
        // on RenderPlayer). Without this the troops' equipped armor -- including Flan KSK ItemTeamArmour,
        // which is a standard ItemArmor whose getArmorModel/getArmorTexture LayerBipedArmor routes through
        // ForgeHooksClient -- is never drawn. Adding it makes vanilla + Flan content-pack armor render.
        this.addLayer(new LayerBipedArmor(this));
    }

    public RenderSkinnable(RenderManager manager, ModelBiped model, float shadowSize) {
        super(manager, model, shadowSize);
        this.addLayer(new LayerBipedArmor(this));
    }

    @Override
    protected ResourceLocation getEntityTexture(T entity) {
        // The cache handles everything: ISkinnable check, NBT fallback, texture loading
        return SkinTextureCache.getTexture(entity);
    }
}
