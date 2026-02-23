package studio.ERM.war.skins;

import net.minecraft.util.ResourceLocation;

/**
 * Interface for entities that support the ERM skin pool system.
 * 
 * Implement this on any entity that should be skinnable via the config-driven pool system.
 * The framework handles texture caching, NBT persistence, and pool lookups automatically.
 * 
 * Usage in your entity:
 * <pre>
 * public class MyEntity extends EntityCreature implements ISkinnable {
 *     private String skinKey = "";
 *     
 *     @Override public String getSkinKey() { return skinKey; }
 *     @Override public void setSkinKey(String key) { this.skinKey = key; }
 *     @Override public String getDefaultPoolName() { return "soldiers"; }
 *     
 *     // In your spawn/init logic:
 *     // SkinPoolManager.applySkinFromPool(this, "soldiers", rand);
 * }
 * </pre>
 */
public interface ISkinnable {

    /**
     * Get the current skin key (e.g., "ancientwarfare:skin_pack/xoltec_woman_5").
     * This is the full domain:path WITHOUT file extension.
     * 
     * @return The skin key, or empty string if none assigned.
     */
    String getSkinKey();

    /**
     * Set the skin key. Called by the framework when assigning a skin.
     * You should persist this in NBT.
     * 
     * @param key The skin key (domain:pathNoExt format)
     */
    void setSkinKey(String key);

    /**
     * The default pool name this entity draws from if no specific pool is requested.
     * This should match a pool name defined in the config.
     * 
     * @return Pool name (e.g., "soldiers", "civilians", "pirates")
     */
    String getDefaultPoolName();

    /**
     * Optional: Override to provide entity-specific fallback texture.
     * Default returns Steve.
     */
    default ResourceLocation getFallbackTexture() {
        return new ResourceLocation("minecraft", "textures/entity/steve.png");
    }

    /**
     * Optional: Called after a skin is successfully applied.
     * Override to trigger re-renders or sync to clients.
     */
    default void onSkinApplied(String skinKey) {
        // Default: no-op
    }
}
