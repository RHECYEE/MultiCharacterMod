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
            // Check if entity has custom fallback
            if (entity instanceof ISkinnable) {
                return ((ISkinnable) entity).getFallbackTexture();
            }
            return FALLBACK_STEVE;
        }

        return getTextureByKey(key);
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
                if (SkinPoolConfig.debugLogging) {
                    System.err.println("[ERM-Skins] Failed to decode image: " + assetLoc);
                }
                return null;
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

            if (SkinPoolConfig.debugLogging) {
                System.out.println("[ERM-Skins] Loaded texture: " + key + " -> " + dynKey);
            }

            return dynKey;

        } catch (Throwable t) {
            if (SkinPoolConfig.debugLogging) {
                System.err.println("[ERM-Skins] Error loading texture '" + key + "': " + t.getMessage());
            }
            return null;
        }
    }

    private static String sanitizePath(String path) {
        if (path == null) return "null";
        return path.replace('\\', '/')
                   .replaceAll("[^A-Za-z0-9_\\-/]", "_");
    }
}
