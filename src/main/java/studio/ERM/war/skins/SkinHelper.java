package studio.ERM.war.skins;

import net.minecraft.entity.Entity;
import net.minecraft.nbt.NBTTagCompound;

import javax.annotation.Nullable;
import java.util.Random;

/**
 * Helper class for entities that can't directly implement ISkinnable.
 * 
 * Use this when your entity extends a class you don't control (like EntitySkeleton)
 * and you can't add the interface. The skin data is stored entirely in NBT.
 * 
 * USAGE:
 * <pre>
 * public class EntityAIPilot extends EntityCreature {
 *     
 *     // In spawn/init:
 *     public void initializeSkin(int rivalLevel, Random rand) {
 *         SkinHelper.applyIfMissing(this, rivalLevel, rand);
 *     }
 *     
 *     // In your renderer:
 *     // return SkinTextureCache.getTexture(entity);
 *     // (The texture cache reads from NBT automatically)
 * }
 * </pre>
 */
public final class SkinHelper {

    private SkinHelper() {}

    /**
     * Apply a skin from a pool if the entity doesn't have one yet.
     * 
     * @param entity The entity
     * @param poolName Pool name from config
     * @param rand Random source
     * @return true if skin was applied
     */
    public static boolean applyIfMissing(Entity entity, String poolName, @Nullable Random rand) {
        if (entity == null) return false;
        if (SkinPoolManager.hasSkin(entity)) return false;
        
        return SkinPoolManager.applySkinFromPool(entity, poolName, rand);
    }

    /**
     * Apply a skin based on rival level if the entity doesn't have one.
     * 
     * @param entity The entity
     * @param rivalLevel Level 1-10
     * @param rand Random source
     * @return true if skin was applied
     */
    public static boolean applyIfMissing(Entity entity, int rivalLevel, @Nullable Random rand) {
        if (entity == null) return false;
        if (SkinPoolManager.hasSkin(entity)) return false;
        
        return SkinPoolManager.applySkinForRivalLevel(entity, rivalLevel, rand);
    }

    /**
     * Force apply a skin from a pool (even if one exists).
     */
    public static boolean forceApply(Entity entity, String poolName, @Nullable Random rand) {
        if (entity == null) return false;
        return SkinPoolManager.applySkinFromPool(entity, poolName, rand);
    }

    /**
     * Get the skin key from an entity's NBT.
     * 
     * @return Skin key or null if none
     */
    @Nullable
    public static String getSkinKey(Entity entity) {
        return SkinPoolManager.getSkinKey(entity);
    }

    /**
     * Check if entity has a skin.
     */
    public static boolean hasSkin(Entity entity) {
        return SkinPoolManager.hasSkin(entity);
    }

    /**
     * Manually set a skin key on an entity.
     * Use this for custom skin assignment logic.
     * 
     * @param entity The entity
     * @param key Format: "domain:folder/filenameNoExt"
     */
    public static void setSkinKey(Entity entity, String key) {
        if (entity == null) return;
        
        NBTTagCompound data = entity.getEntityData();
        data.setString(SkinPoolManager.NBT_SKIN_KEY, key != null ? key : "");
        
        if (key != null && !key.isEmpty()) {
            // Also set the full path
            data.setString(SkinPoolManager.NBT_SKIN_FULL, key + ".png");
        }
    }

    /**
     * Copy skin from one entity to another.
     * Useful for cloning/spawning related entities.
     */
    public static void copySkin(Entity source, Entity target) {
        if (source == null || target == null) return;
        
        String key = getSkinKey(source);
        if (key != null && !key.isEmpty()) {
            setSkinKey(target, key);
        }
    }

    /**
     * Clear the skin from an entity.
     */
    public static void clearSkin(Entity entity) {
        if (entity == null) return;
        
        if (entity instanceof ISkinnable) {
            ((ISkinnable) entity).setSkinKey("");
        }
        
        NBTTagCompound data = entity.getEntityData();
        data.removeTag(SkinPoolManager.NBT_SKIN_KEY);
        data.removeTag(SkinPoolManager.NBT_SKIN_FULL);
        data.removeTag(SkinPoolManager.NBT_SKIN_POOL);
    }
}
