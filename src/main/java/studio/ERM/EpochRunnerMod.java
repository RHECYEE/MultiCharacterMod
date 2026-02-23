package studio.ERM;

import net.minecraft.block.Block;
import net.minecraft.creativetab.CreativeTabs;
import net.minecraft.item.Item;
import net.minecraft.item.ItemBlock;
import net.minecraft.util.ResourceLocation;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.RegistryEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.common.SidedProxy;
import net.minecraftforge.fml.common.event.FMLInitializationEvent;
import net.minecraftforge.fml.common.event.FMLPreInitializationEvent;
import net.minecraftforge.fml.common.event.FMLServerStartingEvent;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import net.minecraftforge.fml.common.network.NetworkRegistry;
import net.minecraftforge.fml.common.network.simpleimpl.SimpleNetworkWrapper;
import net.minecraftforge.fml.common.registry.EntityRegistry;
import net.minecraftforge.fml.common.registry.GameRegistry;
import net.minecraftforge.fml.relauncher.Side;
import org.apache.logging.log4j.Logger;

import studio.ERM.war.*;
import studio.ERM.war.config.*;
import studio.ERM.war.districts.*;
import studio.ERM.war.items.*;
import studio.ERM.war.entities.EntityModernCitizen;
import studio.ERM.war.network.WarPacketHandler;
import studio.ERM.items.ItemProtector;
import studio.ERM.handlers.*;

@Mod(modid = EpochRunnerMod.MODID, name = "Multi Character", version = EpochRunnerMod.VERSION, dependencies = "required-after:ancientwarfarenpc")
public class EpochRunnerMod {
    public static final String MODID = "homosapien";
    public static final String VERSION = "10";

    @Mod.Instance(MODID)
    public static EpochRunnerMod instance;
    public static Logger logger;
    public static SimpleNetworkWrapper network;

    // RESTORED: This allows InvasionHandler and SleepBlocker to function
    public static studio.ERM.handlers.InvasionHandler invasionHandlerInstance;

    @SidedProxy(clientSide = "studio.ERM.proxy.ClientProxy", serverSide = "studio.ERM.proxy.CommonProxy")
    public static studio.ERM.proxy.CommonProxy proxy;

    public static Item entity_protector, sabotage_fixer, camp_setter, modern_citizen_item;
    public static Block citizen_bed, district_marker, scaffold;
    
    // Job assignment items
    public static Item hammer, multimeter, blueprint, command_buck, gold_wrench;

    @Mod.EventHandler
    public void preInit(FMLPreInitializationEvent event) {
        logger = event.getModLog();
        logger.info("[NUCLEAR-LOG] Starting Pre-Init for " + MODID);

        try {
            network = NetworkRegistry.INSTANCE.newSimpleChannel("epoch_net");

            // CRITICAL: Register tile entities before blocks that use them
            GameRegistry.registerTileEntity(studio.ERM.war.districts.TileEntityPowerDistrict.class, 
                new ResourceLocation(MODID, "power_district"));
            GameRegistry.registerTileEntity(studio.ERM.war.districts.TileEntityDistrictMarker.class, 
                new ResourceLocation(MODID, "district_marker"));

            // Register entity with proper tracking range
            EntityRegistry.registerModEntity(new ResourceLocation(MODID, "modern_citizen"),
                    studio.ERM.war.entities.EntityModernCitizen.class, "modern_citizen", 0, instance, 80, 3, true, 0x964B00, 0xFFFFFF);

            co.runed.multicharacter.MultiCharacterMod.preInit(event);

            proxy.preInit(event);
            
            logger.info("[NUCLEAR-LOG] Pre-Init COMPLETE");
        } catch (Exception e) {
            logger.error("[NUCLEAR-LOG] CRITICAL FAILURE IN PRE-INIT", e);
            throw e;
        }
    }

    @Mod.EventHandler
    public void init(FMLInitializationEvent event) {
        try {
            // INITIALIZE: This variable must exist before EventBus registration
            invasionHandlerInstance = new studio.ERM.handlers.InvasionHandler();

            MinecraftForge.EVENT_BUS.register(new studio.ERM.handlers.TerritoryOverlayHandler());
            MinecraftForge.EVENT_BUS.register(new studio.ERM.handlers.WarTensionManager());
            MinecraftForge.EVENT_BUS.register(new studio.ERM.war.WarRepairHandler());
            MinecraftForge.EVENT_BUS.register(new studio.ERM.handlers.WarAmbushTracker());
            MinecraftForge.EVENT_BUS.register(new studio.ERM.handlers.WarTriggerHandler());
            MinecraftForge.EVENT_BUS.register(invasionHandlerInstance);
            MinecraftForge.EVENT_BUS.register(new studio.ERM.handlers.SleepBlocker());
            MinecraftForge.EVENT_BUS.register(new studio.ERM.handlers.WorldBackupManager());
            MinecraftForge.EVENT_BUS.register(new studio.ERM.handlers.ProtectionHandler());

            co.runed.multicharacter.MultiCharacterMod.init(event);
            proxy.init(event);
            
            logger.info("[NUCLEAR-LOG] Init COMPLETE");
        } catch (Exception e) {
            logger.error("[NUCLEAR-LOG] CRITICAL FAILURE IN INIT", e);
        }
    }

    @Mod.EventHandler
    public void serverLoad(FMLServerStartingEvent event) {
        // CRITICAL: Register commands here
        event.registerServerCommand(new studio.ERM.war.CommandWar());
        logger.info("[NUCLEAR-LOG] Command /war registered");
        
        co.runed.multicharacter.MultiCharacterMod.serverStarting(event);
    }

    @Mod.EventBusSubscriber(modid = MODID)
    public static class RegistrationHandler {
        @SubscribeEvent
        public static void registerBlocks(RegistryEvent.Register<Block> event) {
            district_marker = new studio.ERM.war.districts.BlockDistrictMarker().setRegistryName("district_marker").setTranslationKey(MODID + ".district_marker");
            citizen_bed = new studio.ERM.war.districts.BlockCitizenBed().setRegistryName("citizen_bed").setTranslationKey(MODID + ".citizen_bed");
            scaffold = new studio.ERM.war.districts.BlockScaffold().setRegistryName("scaffold").setTranslationKey(MODID + ".scaffold");

            event.getRegistry().registerAll(citizen_bed, district_marker, scaffold);
            logger.info("[NUCLEAR-LOG] Blocks registered: citizen_bed, district_marker, scaffold");
        }

        @SubscribeEvent
        public static void registerItems(RegistryEvent.Register<Item> event) {
            entity_protector = new studio.ERM.items.ItemProtector().setRegistryName("entity_protector").setTranslationKey(MODID + ".entity_protector");
            sabotage_fixer = new studio.ERM.war.items.ItemSabotageFixer().setRegistryName("sabotage_fixer").setTranslationKey(MODID + ".sabotage_fixer");
            camp_setter = new studio.ERM.war.items.ItemCampSetter().setRegistryName("camp_setter").setTranslationKey(MODID + ".camp_setter");
            modern_citizen_item = new studio.ERM.war.items.ItemModernCitizen().setRegistryName("modern_citizen_item").setTranslationKey(MODID + ".modern_citizen_item");
            
            // Job assignment items - simple items that assign jobs when given to citizens
            hammer = new Item().setRegistryName("hammer").setTranslationKey(MODID + ".hammer").setCreativeTab(CreativeTabs.TOOLS).setMaxStackSize(1);
            multimeter = new Item().setRegistryName("multimeter").setTranslationKey(MODID + ".multimeter").setCreativeTab(CreativeTabs.TOOLS).setMaxStackSize(1);
            blueprint = new Item().setRegistryName("blueprint").setTranslationKey(MODID + ".blueprint").setCreativeTab(CreativeTabs.MISC).setMaxStackSize(1);
            command_buck = new Item().setRegistryName("command_buck").setTranslationKey(MODID + ".command_buck").setCreativeTab(CreativeTabs.COMBAT).setMaxStackSize(1);
            gold_wrench = new Item().setRegistryName("gold_wrench").setTranslationKey(MODID + ".gold_wrench").setCreativeTab(CreativeTabs.TOOLS).setMaxStackSize(1).setMaxDamage(128);

            event.getRegistry().registerAll(entity_protector, sabotage_fixer, camp_setter, modern_citizen_item,
                hammer, multimeter, blueprint, command_buck, gold_wrench);

            event.getRegistry().register(new ItemBlock(citizen_bed).setRegistryName("citizen_bed"));
            event.getRegistry().register(new ItemBlock(district_marker).setRegistryName("district_marker"));
            event.getRegistry().register(new ItemBlock(scaffold).setRegistryName("scaffold"));
            
            logger.info("[NUCLEAR-LOG] Items registered: all items including job items (hammer, multimeter, blueprint, command_buck, gold_wrench)");
        }
    }
}
