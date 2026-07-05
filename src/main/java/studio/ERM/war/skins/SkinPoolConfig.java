package studio.ERM.war.skins;

import net.minecraftforge.common.config.Config;
import net.minecraftforge.common.config.ConfigManager;
import net.minecraftforge.fml.client.event.ConfigChangedEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;

/**
 * Configuration for the ERM Skin Pool System.
 * 
 * Modpack makers can define custom skin pools by adding entries to the pools map.
 * Each pool is a list of PREFIXES that match skin filenames from the AW2 skin_pack.
 * 
 * Example config/erm_skins.cfg:
 * <pre>
 * S:pools <
 *     soldiers=buffloka_,zimba_,smingol_
 *     civilians=villager_,farmer_,merchant_
 *     pirates=pirate_,buccaneer_,corsair_
 *     undead=zombie_,skeleton_,vampire_,undead_
 *     all=*
 * >
 * </pre>
 * 
 * Special prefix "*" matches ALL skins in the pack.
 * 
 * Entity type mappings let you assign default pools to entity classes:
 * <pre>
 * S:entityPools <
 *     studio.ERM.war.entities.EntityModularCitizen=soldiers
 *     studio.ERM.war.vehicle.EntityAIPilot=pilots
 *     studio.ERM.war.vehicle.EntityTankOperator=soldiers
 * >
 * </pre>
 */
@Config(modid = "erm", name = "erm_skins", category = "")
@Config.LangKey("config.erm.skins")
public class SkinPoolConfig {

    @Config.Comment({
        "Skin pool definitions.",
        "Format: poolName=prefix1,prefix2,prefix3",
        "Use '*' as a prefix to include ALL skins.",
        "Use 'exact:filename.png' to match exact filenames.",
        "Example: soldiers=buffloka_,zimba_,exact:special_soldier.png"
    })
    @Config.Name("pools")
    public static String[] pools = {
        // Default pools matching the old rival level system
        "tribal=buffloka_",
        "warriors=zimba_",
        "raiders=smingol_,sealsker_",
        "aztec=xoltec_",
        "knights=witchbane_",
        "african=shakayana_",
        "samurai=zamurai_",
        "viking=vyncan_",
        "pirates=pirate_",
        "undead=undead_,vampire_,zombie_,skeleton_",
        
        // Convenience pools
        "soldiers=buffloka_,zimba_,smingol_,sealsker_,xoltec_,witchbane_,shakayana_,zamurai_,vyncan_",
        "civilians=villager_,farmer_,merchant_,peasant_,citizen_",
        "all=*"
    };

    @Config.Comment({
        "Map entity class names to default pool names.",
        "Format: fully.qualified.ClassName=poolName",
        "Entities can override this in code, but this provides pack-level defaults."
    })
    @Config.Name("entityPools")
    public static String[] entityPools = {
        "studio.ERM.war.entities.EntityModularCitizen=soldiers",
        "studio.ERM.war.entities.EntityModernCitizen=soldiers",
        "studio.ERM.war.vehicle.EntityAIPilot=soldiers",
        "studio.ERM.war.vehicle.EntityTankOperator=soldiers"
    };

    @Config.Comment({
        "Rival level to pool mappings for the legacy rival system.",
        "Format: level=poolName",
        "Levels 1-10 are supported."
    })
    @Config.Name("rivalLevelPools")
    public static String[] rivalLevelPools = {
        "1=tribal",
        "2=warriors",
        "3=raiders",
        "4=aztec",
        "5=knights",
        "6=african",
        "7=samurai",
        "8=viking",
        "9=pirates",
        "10=undead"
    };

    @Config.Comment("Fallback pool if a requested pool doesn't exist or is empty.")
    @Config.Name("fallbackPool")
    public static String fallbackPool = "soldiers";

    @Config.Comment({
        "Domain where AW2 skins are located.",
        "Default: ancientwarfare"
    })
    @Config.Name("skinDomain")
    public static String skinDomain = "ancientwarfare";

    @Config.Comment({
        "Folder within the domain where skins are stored.",
        "Default: skin_pack"
    })
    @Config.Name("skinFolder")
    public static String skinFolder = "skin_pack";

    @Config.Comment({
        "Path to the skin_pack.meta file (relative to assets).",
        "Leave empty to use folder scanning instead."
    })
    @Config.Name("metaFilePath")
    public static String metaFilePath = "/assets/ancientwarfare/skin_pack/skin_pack.meta";

    @Config.Comment("Enable debug logging for skin assignment.")
    @Config.Name("debugLogging")
    public static boolean debugLogging = false;

    @Config.Comment({
        "Additional skin domains to scan (besides the main skinDomain).",
        "Format: domain:folder",
        "Example: mymod:custom_skins"
    })
    @Config.Name("additionalSkinSources")
    public static String[] additionalSkinSources = {};

    /**
     * Force a config reload and rebuild the pool manager.
     */
    public static void sync() {
        ConfigManager.sync("erm", Config.Type.INSTANCE);
        SkinPoolManager.rebuildPools();
    }

    @Mod.EventBusSubscriber(modid = "erm")
    public static class ConfigSyncHandler {
        @SubscribeEvent
        public static void onConfigChanged(ConfigChangedEvent.OnConfigChangedEvent event) {
            if ("erm".equals(event.getModID())) {
                sync();
            }
        }
    }
}
