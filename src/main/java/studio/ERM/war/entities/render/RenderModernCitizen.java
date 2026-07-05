package studio.ERM.war.entities.render;

import net.minecraft.client.model.ModelBiped;
import net.minecraft.client.model.ModelPlayer;
import net.minecraft.client.renderer.entity.RenderBiped;
import net.minecraft.client.renderer.entity.RenderManager;
import net.minecraft.util.ResourceLocation;
import net.minecraftforge.fml.relauncher.Side;
import net.minecraftforge.fml.relauncher.SideOnly;

import studio.ERM.war.entities.EntityModernCitizen;
import studio.ERM.war.skins.SkinTextureCache;

/**
 * Renderer for EntityModernCitizen.
 *
 * Was an empty file, so the citizen had no registered renderer and fell back to the
 * missing-texture/default biped. This now mirrors RenderModularCitizen/RenderSkinnable:
 * a biped model whose texture is resolved through SkinTextureCache, which reads the
 * citizen's synced ISkinnable skin key and loads the matching AW2 skin-pack PNG.
 */
@SideOnly(Side.CLIENT)
public class RenderModernCitizen extends RenderBiped<EntityModernCitizen> {

    // ModelPlayer renders the full 64x64 skin (overlay/second layer + separate left limbs); a plain
    // ModelBiped left those areas unrendered, which is why citizens looked transparent / glitchy.
    private static final ModelBiped MODEL = new ModelPlayer(0.0F, false);

    public RenderModernCitizen(RenderManager renderManagerIn) {
        super(renderManagerIn, MODEL, 0.5F);
    }

    @Override
    protected ResourceLocation getEntityTexture(EntityModernCitizen entity) {
        return SkinTextureCache.getTexture(entity);
    }
}
