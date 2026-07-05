package studio.ERM.war.entities;

import net.minecraft.entity.EntityCreature;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.util.ResourceLocation;
import net.minecraft.world.World;
import studio.ERM.war.skins.ISkinnable;
import studio.ERM.war.skins.SkinPoolManager;

import java.util.Random;

/**
 * EXAMPLE: How to make an entity work with the ERM Skin Pool System.
 * 
 * This shows the minimal integration needed. Just implement ISkinnable
 * and call SkinPoolManager.applySkinFromPool() when the entity spawns.
 */
public class ExampleSkinnableEntity extends EntityCreature implements ISkinnable {

    // Store the skin key (domain:folder/filenameNoExt)
    private String skinKey = "";

    public ExampleSkinnableEntity(World worldIn) {
        super(worldIn);
    }

    // =========================================================================
    // ISkinnable Implementation
    // =========================================================================

    @Override
    public String getSkinKey() {
        return this.skinKey;
    }

    @Override
    public void setSkinKey(String key) {
        this.skinKey = (key != null) ? key : "";
    }

    @Override
    public String getDefaultPoolName() {
        // This entity pulls from the "soldiers" pool by default.
        // Modpack makers can override this in config via entityPools.
        return "soldiers";
    }

    @Override
    public ResourceLocation getFallbackTexture() {
        // Custom fallback if no skin is assigned
        return new ResourceLocation("minecraft", "textures/entity/steve.png");
    }

    @Override
    public void onSkinApplied(String skinKey) {
        // Optional: Called after skin is set. 
        // Good place to mark entity for re-render sync if needed.
    }

    // =========================================================================
    // Apply Skin on Spawn
    // =========================================================================

    /**
     * Call this when the entity first spawns (not on world load).
     * You might call it from onInitialSpawn() or a custom spawn method.
     */
    public void initializeSkin(Random rand) {
        // Only apply if no skin already set (e.g., from NBT on load)
        if (!SkinPoolManager.hasSkin(this)) {
            // Use the entity's default pool
            SkinPoolManager.applySkinFromPool(this, rand);
            
            // Or specify a pool explicitly:
            // SkinPoolManager.applySkinFromPool(this, "pirates", rand);
            
            // Or use the legacy rival level system:
            // SkinPoolManager.applySkinForRivalLevel(this, 5, rand);
        }
    }

    // =========================================================================
    // NBT Persistence
    // =========================================================================

    @Override
    public void writeEntityToNBT(NBTTagCompound compound) {
        super.writeEntityToNBT(compound);
        compound.setString("SkinKey", this.skinKey);
    }

    @Override
    public void readEntityFromNBT(NBTTagCompound compound) {
        super.readEntityFromNBT(compound);
        this.skinKey = compound.getString("SkinKey");
    }
}
