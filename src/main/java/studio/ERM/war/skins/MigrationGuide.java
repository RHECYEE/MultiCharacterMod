package studio.ERM.war.skins;

/**
 * ============================================================================
 * ERM SKIN POOL SYSTEM - MIGRATION GUIDE
 * ============================================================================
 * 
 * This guide shows how to update your existing entities to use the new
 * config-driven skin pool system.
 * 
 * QUICK START:
 * 1. Implement ISkinnable on your entity
 * 2. Add NBT persistence for the skin key
 * 3. Call SkinPoolManager.applySkinFromPool() on spawn
 * 4. Update your renderer to use SkinTextureCache.getTexture()
 * 
 * ============================================================================
 * EXAMPLE: Updating EntityModularCitizen
 * ============================================================================
 * 
 * BEFORE (your current code):
 * <pre>
 * public class EntityModularCitizen extends EntityCreature {
 *     private String texturePath = "";
 *     
 *     public String getTexturePathNoExt() { return texturePath; }
 *     public void setTexturePathNoExt(String path) { this.texturePath = path; }
 * }
 * </pre>
 * 
 * AFTER (with ISkinnable):
 * <pre>
 * public class EntityModularCitizen extends EntityCreature implements ISkinnable {
 *     private String skinKey = "";
 *     
 *     // ISkinnable implementation
 *     @Override public String getSkinKey() { return skinKey; }
 *     @Override public void setSkinKey(String key) { this.skinKey = key; }
 *     @Override public String getDefaultPoolName() { return "soldiers"; }
 *     
 *     // Legacy compatibility (if other code calls these)
 *     public String getTexturePathNoExt() { return skinKey; }
 *     public void setTexturePathNoExt(String path) { this.skinKey = path; }
 *     
 *     // In your spawn logic:
 *     @Override
 *     public IEntityLivingData onInitialSpawn(DifficultyInstance d, IEntityLivingData data) {
 *         data = super.onInitialSpawn(d, data);
 *         if (!SkinPoolManager.hasSkin(this)) {
 *             SkinPoolManager.applySkinFromPool(this, this.rand);
 *         }
 *         return data;
 *     }
 * }
 * </pre>
 * 
 * ============================================================================
 * EXAMPLE: Updating EntityAIPilot (non-ISkinnable approach)
 * ============================================================================
 * 
 * If you don't want to implement ISkinnable, you can still use the system
 * via NBT. The SkinPoolManager writes to entity.getEntityData() automatically.
 * 
 * <pre>
 * public class EntityAIPilot extends EntityCreature {
 *     
 *     // In your spawn/init:
 *     public void initializeSkin(int rivalLevel, Random rand) {
 *         if (!SkinPoolManager.hasSkin(this)) {
 *             // Option 1: Use rival level mapping
 *             SkinPoolManager.applySkinForRivalLevel(this, rivalLevel, rand);
 *             
 *             // Option 2: Use named pool
 *             // SkinPoolManager.applySkinFromPool(this, "pilots", rand);
 *         }
 *     }
 * }
 * </pre>
 * 
 * ============================================================================
 * EXAMPLE: Updating RenderModularCitizen
 * ============================================================================
 * 
 * BEFORE:
 * <pre>
 * @Override
 * protected ResourceLocation getEntityTexture(EntityModularCitizen entity) {
 *     return SkinPackTextureCache.getOrCreateTexture(entity);
 * }
 * </pre>
 * 
 * AFTER:
 * <pre>
 * @Override
 * protected ResourceLocation getEntityTexture(EntityModularCitizen entity) {
 *     return SkinTextureCache.getTexture(entity);
 * }
 * </pre>
 * 
 * ============================================================================
 * CONFIG FILE EXAMPLE (config/erm_skins.cfg)
 * ============================================================================
 * 
 * # Modpack makers can customize everything!
 * 
 * # Define custom pools
 * S:pools <
 *     # Base pools (prefix matching)
 *     tribal=buffloka_
 *     warriors=zimba_
 *     raiders=smingol_,sealsker_
 *     aztec=xoltec_
 *     knights=witchbane_
 *     african=shakayana_
 *     samurai=zamurai_
 *     viking=vyncan_
 *     pirates=pirate_
 *     undead=undead_,vampire_,zombie_,skeleton_
 *     
 *     # Combined pools
 *     soldiers=buffloka_,zimba_,smingol_,xoltec_,witchbane_
 *     civilians=villager_,farmer_,merchant_
 *     
 *     # Exact filename matching
 *     bosses=exact:boss_king.png,exact:boss_warlord.png
 *     
 *     # Everything
 *     all=*
 * >
 * 
 * # Map entity classes to default pools
 * S:entityPools <
 *     studio.ERM.war.entities.EntityModularCitizen=soldiers
 *     studio.ERM.war.entities.EntityModernCitizen=soldiers
 *     studio.ERM.war.vehicle.EntityAIPilot=soldiers
 *     studio.ERM.war.vehicle.EntityTankOperator=soldiers
 * >
 * 
 * # Legacy rival level support
 * S:rivalLevelPools <
 *     1=tribal
 *     2=warriors
 *     3=raiders
 *     4=aztec
 *     5=knights
 *     6=african
 *     7=samurai
 *     8=viking
 *     9=pirates
 *     10=undead
 * >
 * 
 * ============================================================================
 * ADDING CUSTOM SKIN SOURCES
 * ============================================================================
 * 
 * The system can load skins from multiple mods/resource packs:
 * 
 * S:additionalSkinSources <
 *     mymod:custom_skins
 *     anothermod:npc_textures
 * >
 * 
 * Each source needs a skin_pack.meta file listing the PNG filenames,
 * or you can manually populate the pool with exact: entries.
 * 
 * ============================================================================
 */
public final class MigrationGuide {
    private MigrationGuide() {}
}
