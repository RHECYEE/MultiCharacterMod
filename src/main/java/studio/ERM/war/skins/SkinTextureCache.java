package studio.ERM.war.skins;

import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.client.renderer.texture.TextureManager;
import net.minecraft.client.resources.IResource;
import net.minecraft.entity.Entity;
import net.minecraft.util.ResourceLocation;
import net.minecraftforge.fml.relauncher.Side;
import net.minecraftforge.fml.relauncher.SideOnly;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.InputStream;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Client-side texture cache for the ERM Skin Pool System.
 * 
 * AW2 skins live at: assets/ancientwarfare/skin_pack/<name>.png
 * This is NOT in the standard textures/ folder, so vanilla texture loading fails.
 * 
 * This cache:
 * - Loads PNGs from non-standard asset locations
 * - Registers them as DynamicTextures
 * - Returns ResourceLocations that RenderLiving can bind normally
 * 
 * USAGE (in your renderer):
 * <pre>
 * @Override
 * protected ResourceLocation getEntityTexture(YourEntity entity) {
 *     return SkinTextureCache.getTexture(entity);
 * }
 * </pre>
 */
@SideOnly(Side.CLIENT)
public final class SkinTextureCache {

    private SkinTextureCache() {}

    private static final Map<String, ResourceLocation> CACHE = new ConcurrentHashMap<>();

    private static final ResourceLocation FALLBACK_STEVE = 
        new ResourceLocation("minecraft", "textures/entity/steve.png");

    /**
     * Get texture for any entity that has a skin assigned.
     * Works with ISkinnable entities and any entity with NBT skin data.
     * 
     * @param entity The entity
     * @return ResourceLocation to bind, or fallback texture
     */
    public static ResourceLocation getTexture(Entity entity) {
        if (entity == null) return FALLBACK_STEVE;

        // Get skin key from entity
        String key = SkinPoolManager.getSkinKey(entity);
        if (key == null || key.isEmpty()) {
            // UNCONDITIONAL (throttled): if the client sees an empty key, the DataParameter never
            // synced (or the server never assigned one) -> this is THE reason for a Steve/white blob.
            logOnce(entity.getClass().getSimpleName() + "|<empty>",
                    "[ERM-Skins] CLIENT: " + entity.getClass().getSimpleName()
                            + " has EMPTY skin key on client -> fallback "
                            + (entity instanceof ISkinnable ? "(entity custom)" : "Steve"));
            // Check if entity has custom fallback
            if (entity instanceof ISkinnable) {
                return ((ISkinnable) entity).getFallbackTexture();
            }
            return FALLBACK_STEVE;
        }

        ResourceLocation result = getTextureByKey(key);
        // UNCONDITIONAL (throttled, once per class|key): shows whether the synced key resolved to a
        // real dynamic texture or fell back to Steve. If it resolves but still renders white, the
        // problem is the PNG/model (e.g. 64x32 legacy skin on a 64x64 model), not the key pipeline.
        logOnce(entity.getClass().getSimpleName() + "|" + key,
                "[ERM-Skins] CLIENT: " + entity.getClass().getSimpleName()
                        + " key='" + key + "' -> " + result
                        + (FALLBACK_STEVE.equals(result) ? "  (FELL BACK TO STEVE)" : ""));
        return result;
    }

    private static final java.util.Set<String> LOGGED = java.util.concurrent.ConcurrentHashMap.newKeySet();

    /**
     * Log {@code msg} the first time {@code dedupeKey} is seen; throttles per-entity spam.
     * Routed through {@link studio.ERM.EpochRunnerMod#logger} (log4j) because, in this runtime,
     * {@code System.out} does NOT reach latest.log -- so the previous System.out diagnostics were
     * completely invisible and useless for debugging the white-blob soldiers.
     */
    private static void logOnce(String dedupeKey, String msg) {
        if (LOGGED.add(dedupeKey)) {
            studio.ERM.EpochRunnerMod.logger.info(msg);
        }
    }

    /**
     * Get texture by skin key directly.
     * 
     * @param key Format: "domain:folder/filenameNoExt"
     * @return ResourceLocation to bind
     */
    public static ResourceLocation getTextureByKey(String key) {
        if (key == null || key.isEmpty()) return FALLBACK_STEVE;

        // Check cache
        ResourceLocation cached = CACHE.get(key);
        if (cached != null) return cached;

        // Load and register
        ResourceLocation loaded = loadAndRegister(key);
        if (loaded == null) {
            CACHE.put(key, FALLBACK_STEVE);
            return FALLBACK_STEVE;
        }

        CACHE.put(key, loaded);
        return loaded;
    }

    /**
     * Clear the texture cache (call on resource reload).
     */
    public static void clearCache() {
        CACHE.clear();
    }

    /**
     * Pre-load textures for a pool (optional optimization).
     */
    public static void preloadPool(String poolName) {
        for (SkinPoolManager.SkinEntry entry : SkinPoolManager.getPoolContents(poolName)) {
            getTextureByKey(entry.getKey());
        }
    }

    // =========================================================================
    // INTERNAL - Texture Loading
    // =========================================================================

    private static ResourceLocation loadAndRegister(String key) {
        try {
            // Parse key: "domain:folder/filenameNoExt"
            String[] parts = key.split(":", 2);
            if (parts.length != 2) return null;

            String domain = parts[0];
            String pathNoExt = parts[1];

            // Build actual resource path (adding .png)
            ResourceLocation assetLoc = new ResourceLocation(domain, pathNoExt + ".png");

            // Load the image
            Minecraft mc = Minecraft.getMinecraft();
            IResource resource = mc.getResourceManager().getResource(assetLoc);

            BufferedImage image;
            try (InputStream in = resource.getInputStream()) {
                image = ImageIO.read(in);
            }

            if (image == null) {
                studio.ERM.EpochRunnerMod.logger.warn("[ERM-Skins] Failed to decode image: " + assetLoc
                        + " (entity will render as fallback Steve)");
                return null;
            }

            // ROOT-CAUSE FIX for "white blob" soldiers: AW2 skin_pack PNGs are classic 64x32
            // LEGACY player skins, but our renderer (RenderSkinnable) binds them to a 64x64
            // ModelBiped. On a 64x64 model the lower-body / right-arm / right-leg quads and the
            // entire second (hat/jacket) layer sample the BOTTOM half of the texture -- which does
            // not exist in a 64x32 image -- so those UVs read blank and the entity renders as a
            // solid white biped. Vanilla skin loading runs every skin through ImageBufferDownload
            // to upscale legacy 64x32 -> 64x64 (duplicating the arm/leg sections); this path
            // previously skipped that step entirely. Do the same conversion here.
            if (image.getHeight() * 2 == image.getWidth()) {
                BufferedImage converted =
                        new net.minecraft.client.renderer.ImageBufferDownload().parseUserSkin(image);
                if (converted != null) image = converted;
            }

            // Create dynamic texture
            DynamicTexture dynTex = new DynamicTexture(image);

            // Register under a unique key
            String safePath = sanitizePath(pathNoExt);
            ResourceLocation dynKey = new ResourceLocation(
                "erm_skins", 
                "dynamic/" + domain + "/" + safePath
            );

            TextureManager texMgr = mc.getTextureManager();
            texMgr.loadTexture(dynKey, dynTex);

            // UNCONDITIONAL: confirms the PNG actually decoded + registered, and prints its
            // dimensions. A 64x32 (legacy) skin rendered on a 64x64 biped model is a common cause
            // of "white/half-missing" entities even though the texture loads without error.
            studio.ERM.EpochRunnerMod.logger.info("[ERM-Skins] Loaded texture: " + key + " -> " + dynKey
                    + " (img " + image.getWidth() + "x" + image.getHeight() + ")");

            return dynKey;

        } catch (Throwable t) {
            // Unconditional: this is the client-side reason a soldier shows as a white/Steve
            // blob -- the skin key was set but its PNG could not be resolved/loaded.
            studio.ERM.EpochRunnerMod.logger.warn("[ERM-Skins] Error loading texture '" + key + "': " + t,
                    t);
            return null;
        }
    }

    private static String sanitizePath(String path) {
        if (path == null) return "null";
        return path.replace('\\', '/')
                   .replaceAll("[^A-Za-z0-9_\\-/]", "_");
    }
}
