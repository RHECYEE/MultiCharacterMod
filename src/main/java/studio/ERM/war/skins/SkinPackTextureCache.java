package studio.ERM.war.skins;

import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.client.renderer.texture.TextureManager;
import net.minecraft.client.resources.IResource;
import net.minecraft.util.ResourceLocation;

import studio.ERM.war.entities.EntityModularCitizen;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.InputStream;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * AW2 skin pack compatibility layer.
 *
 * Your skins live at:
 *   src/main/resources/assets/ancientwarfare/skin_pack/<name>.png
 *
 * Vanilla entity rendering expects textures under assets/<domain>/textures/**,
 * which would normally break those resources.
 *
 * This cache loads the PNG from that non-textures folder and registers it as a DynamicTexture
 * so RenderLiving can bind it like a normal texture.
 */
public final class SkinPackTextureCache {

    private SkinPackTextureCache() {}

    private static final Map<String, ResourceLocation> CACHE = new ConcurrentHashMap<>();

    private static final ResourceLocation FALLBACK = new ResourceLocation("minecraft", "textures/entity/steve.png");

    public static ResourceLocation getOrCreateTexture(EntityModularCitizen citizen) {
        if (citizen == null) return FALLBACK;

        String key = citizen.getTexturePathNoExt(); // e.g. ancientwarfare:skin_pack/xoltec_woman_5
        if (key == null || key.trim().isEmpty()) return FALLBACK;

        ResourceLocation existing = CACHE.get(key);
        if (existing != null) return existing;

        ResourceLocation created = tryLoadSkinToDynamic(key);
        if (created == null) {
            CACHE.put(key, FALLBACK);
            return FALLBACK;
        }

        CACHE.put(key, created);
        return created;
    }

    private static ResourceLocation tryLoadSkinToDynamic(String key) {
        try {
            // key is "domain:pathNoExt"
            String[] parts = key.split(":", 2);
            if (parts.length != 2) return null;

            String domain = parts[0];
            String pathNoExt = parts[1];

            // Your resources are stored in assets/<domain>/skin_pack/<name>.png (NOT in textures/)
            // so we request it directly.
            ResourceLocation raw = new ResourceLocation(domain, pathNoExt + ".png");

            Minecraft mc = Minecraft.getMinecraft();
            IResource res = mc.getResourceManager().getResource(raw);

            BufferedImage image;
            try (InputStream in = res.getInputStream()) {
                image = ImageIO.read(in);
            }
            if (image == null) return null;

            DynamicTexture dyn = new DynamicTexture(image);
            TextureManager tm = mc.getTextureManager();

            // Register under a safe namespace so it can be bound normally.
            ResourceLocation dynKey = new ResourceLocation("homosapien", "dynskins/" + domain + "/" + sanitize(pathNoExt));
            tm.loadTexture(dynKey, dyn);
            return dynKey;
        } catch (Throwable t) {
            return null;
        }
    }

    private static String sanitize(String s) {
        if (s == null) return "null";
        return s.replace('\\', '/').replaceAll("[^A-Za-z0-9_\\-/]", "_");
    }
}
