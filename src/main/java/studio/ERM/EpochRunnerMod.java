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
import studio.ERM.items.ItemProtector;
import studio.ERM.handlers.*;

@Mod(modid = EpochRunnerMod.MODID, name = "Multi Character", version = EpochRunnerMod.VERSION, dependencies = "required-after:ancientwarfarenpc")
public class EpochRunnerMod {
    public static final String MODID = "homosapien";
    public static final String VERSION = "10";

    @Mod.Instance(MODID)
    public static EpochRunnerMod instance;
    public static Logger logger;
    private static boolean loggedAdvancementSanitize = false;
    public static SimpleNetworkWrapper network;

    // RESTORED: This allows InvasionHandler and SleepBlocker to function
    public static studio.ERM.handlers.InvasionHandler invasionHandlerInstance;

    @SidedProxy(clientSide = "studio.ERM.proxy.ClientProxy", serverSide = "studio.ERM.proxy.CommonProxy")
    public static studio.ERM.proxy.CommonProxy proxy;

    public static Item entity_protector, sabotage_fixer, camp_setter, modern_citizen_item, air_target_designator;
    public static Block citizen_bed, district_marker, scaffold, assembly_seat, family_kitchen;

    // Job assignment items
    public static Item hammer, multimeter, blueprint, command_buck, gold_wrench;

    // Expertise items (referenced by ItemExpertise.createFromDistrict)
    public static Item expertise_industry, expertise_agriculture, expertise_defense, expertise_resource;

    @Mod.EventHandler
    public void preInit(FMLPreInitializationEvent event) {
        logger = event.getModLog();
        logger.info("[NUCLEAR-LOG] Starting Pre-Init for " + MODID);

        try {
            // LOAD THE WAR CONFIGS. WarMasterConfig + WarLevelsConfig are manual GSON loaders that were NEVER
            // called -> "none of the configs load" (they write defaults to config/Homosapien/ on first run).
            // (WarWeaponsConfig is a Forge @Config and auto-loads separately.)
            try {
                java.io.File cfgDir = event.getModConfigurationDirectory();
                studio.ERM.war.config.WarMasterConfig.load(cfgDir);
                studio.ERM.war.config.WarLevelsConfig.load(cfgDir);
                studio.ERM.war.config.DistrictOutputConfig.load(cfgDir);
                studio.ERM.war.config.TradePriceConfig.load(cfgDir);
                studio.ERM.war.config.SchematicCatalog.load(cfgDir);
                studio.ERM.strategic.patrol.PatrolConfig.load(cfgDir);
                studio.ERM.war.config.ResearchConfig.load(cfgDir);
                studio.ERM.war.config.FactoryConfig.load(cfgDir);
                logger.info("[Config] WarMaster + WarLevels + DistrictOutput + TradePrice + Research + Factory configs loaded from " + cfgDir);
            } catch (Throwable t) {
                logger.error("[Config] failed to load war configs", t);
            }

            network = NetworkRegistry.INSTANCE.newSimpleChannel("epoch_net");

            // Register tactical war map packets
            studio.ERM.war.map.net.TacticalWarMapNetwork.init();

            // CRITICAL: Register tile entities before blocks that use them
            GameRegistry.registerTileEntity(studio.ERM.war.districts.TileEntityPowerDistrict.class,
                new ResourceLocation(MODID, "power_district"));
            GameRegistry.registerTileEntity(studio.ERM.war.districts.TileEntityDistrictMarker.class,
                new ResourceLocation(MODID, "district_marker"));
            GameRegistry.registerTileEntity(studio.ERM.war.districts.TileEntityAssemblySeat.class,
                new ResourceLocation(MODID, "assembly_seat"));

            // Register entity with proper tracking range
            EntityRegistry.registerModEntity(new ResourceLocation(MODID, "modern_citizen"),
                    EntityModernCitizen.class, "modern_citizen", 0, instance, 80, 3, true, 0x964B00, 0xFFFFFF);

            // Air strike designator projectile + smoke marker. These were referenced by
            // ItemAirTargetDesignator but never registered, so the thrown marker never appeared
            // on the client and the strike-call particles never rendered. IDs 1/2 (0 = citizen).
            EntityRegistry.registerModEntity(new ResourceLocation(MODID, "air_designator"),
                    studio.ERM.war.items.ItemAirTargetDesignator.EntityAirDesignatorProjectile.class,
                    "air_designator", 1, instance, 64, 10, true);
            EntityRegistry.registerModEntity(new ResourceLocation(MODID, "air_smoke_marker"),
                    studio.ERM.war.items.ItemAirTargetDesignator.EntitySmokeMarker.class,
                    "air_smoke_marker", 2, instance, 80, 20, false);

            // Flan's-vehicle pilot/crew AI. It was spawned by many systems (SpawnHelper, raids,
            // RivalCity patrols/guards) but never registered, so it had no spawn egg and never
            // synced/rendered to clients. The 10-arg overload auto-creates a spawn egg (olive/black)
            // — this is the "missing pilot spawn egg". id 3 (0=citizen,1/2=air markers).
            EntityRegistry.registerModEntity(new ResourceLocation(MODID, "ai_pilot"),
                    studio.ERM.war.vehicle.EntityAIPilot.class, "ai_pilot", 3, instance, 64, 3, true, 0x4B5320, 0x1C1C1C);

            // Battle entities. These were spawned by the directors/BattleEngine and HAD client
            // renderers registered, but the ENTITIES themselves were never registered — so the
            // server never sent spawn/tracking packets and they were invisible to every client
            // (no model, no health bar). This is the root cause of "invisible debug guys",
            // "invisible siege formations", and the airstrike showing nothing. IDs 4-7.
            EntityRegistry.registerModEntity(new ResourceLocation(MODID, "soldier"),
                    studio.ERM.war.BattleManagers.entities.EntitySoldier.class, "soldier", 4, instance, 80, 3, true);
            EntityRegistry.registerModEntity(new ResourceLocation(MODID, "soldier_puppet"),
                    studio.ERM.war.BattleManagers.entities.EntitySoldierPuppet.class, "soldier_puppet", 5, instance, 80, 3, true);
            EntityRegistry.registerModEntity(new ResourceLocation(MODID, "formation_carrier"),
                    studio.ERM.war.BattleManagers.entities.EntityFormationCarrier.class, "formation_carrier", 6, instance, 80, 3, true);
            EntityRegistry.registerModEntity(new ResourceLocation(MODID, "ghost_aircraft"),
                    studio.ERM.war.air.EntityGhostAircraft.class, "ghost_aircraft", 7, instance, 160, 3, true);

            // MultiCharacterMod has instance methods - get the instance
            co.runed.multicharacter.MultiCharacterMod.getInstance().preInit(event);

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
            // RECIPE-BOOK CRASH GUARD: the "Ticking player -> sendRecipeBook NPE" crash happens when a
            // recipe in the registry has a null registry name (its SPacketRecipeBook serialization NPEs
            // on getRegistryName().toString()). Registered recipes SHOULD all have names, but some packs/
            // mods slip a null-name one in; assign a fallback so it can never crash the recipe book.
            try {
                int fixed = 0;
                for (net.minecraft.item.crafting.IRecipe r : net.minecraft.item.crafting.CraftingManager.REGISTRY) {
                    if (r != null && r.getRegistryName() == null) {
                        r.setRegistryName(new net.minecraft.util.ResourceLocation(MODID, "nullname_recipe_fix_" + (fixed++)));
                    }
                }
                if (fixed > 0) logger.warn("[RecipeGuard] assigned fallback names to " + fixed
                        + " null-name recipe(s) -- this was the sendRecipeBook crash source.");
                else logger.info("[RecipeGuard] no null-name recipes in the registry (crash is likely a bad advancement->recipe ref).");
            } catch (Throwable t) { logger.warn("[RecipeGuard] scan failed: " + t); }

            // INITIALIZE: This variable must exist before EventBus registration
            invasionHandlerInstance = new studio.ERM.handlers.InvasionHandler();

            MinecraftForge.EVENT_BUS.register(new studio.ERM.handlers.TerritoryOverlayHandler());
            MinecraftForge.EVENT_BUS.register(new studio.ERM.handlers.WarTensionManager());
            MinecraftForge.EVENT_BUS.register(new studio.ERM.war.WarRepairHandler());
            MinecraftForge.EVENT_BUS.register(new studio.ERM.handlers.WarAmbushTracker());
            MinecraftForge.EVENT_BUS.register(new studio.ERM.handlers.WarTriggerHandler());
            // Disable friendly fire within the war army (two sides only: RIVAL + PLAYER).
            MinecraftForge.EVENT_BUS.register(new studio.ERM.handlers.WarFriendlyFireHandler());
            MinecraftForge.EVENT_BUS.register(invasionHandlerInstance);
            MinecraftForge.EVENT_BUS.register(new studio.ERM.handlers.SleepBlocker());
            // WorldBackupManager has private constructor - register class for static @SubscribeEvent if needed
            // MinecraftForge.EVENT_BUS.register(WorldBackupManager.class);
            MinecraftForge.EVENT_BUS.register(new studio.ERM.handlers.ProtectionHandler());

            // CRITICAL FIX: the battle engine's ONLY server-tick driver. Every comment in the
            // BattleManagers code calls DeployedBattleTicker "the one authoritative path" that
            // calls BattleEngine.tick() each server tick -- but it was never actually registered,
            // so BattleEngine.tick() NEVER ran. Result: phased battles (siege, etc.) froze in their
            // start() state forever -- no phase advance, no bombardment, no engineer push, no surge,
            // no troop release from staging carriers. (Debug battles only appeared to work because
            // their carriers self-tick orbit/release in EntityFormationCarrier.onUpdate.) Registering
            // it here brings the whole phased-battle system to life.
            MinecraftForge.EVENT_BUS.register(new studio.ERM.war.BattleManagers.deployed.DeployedBattleTicker());
            logger.info("[BattleManagers] DeployedBattleTicker registered (battle engine tick driver is LIVE)");

            // CRITICAL FIX: the Flan-gun infantry AI injector. It listens for EntityJoinWorldEvent and
            // attaches find-ammo + gun-attack AI to any matched NPC holding a gun -- but it was never
            // registered, so NO entity ever received gun AI (the "lost our custom infantry ammo logic"
            // regression). With this, ranged soldiers/citizens equipped with Flan guns actually shoot.
            MinecraftForge.EVENT_BUS.register(new co.runed.multicharacter.handlers.AIInjectionHandler());
            logger.info("[MCM] AIInjectionHandler registered (Flan-gun infantry AI is LIVE)");

            // /war heat in-world debug board (temporary heat/target particle visualization).
            // Static @SubscribeEvent -> register the CLASS, not an instance.
            MinecraftForge.EVENT_BUS.register(studio.ERM.war.strategy.WarHeatDebug.class);
            logger.info("[War] WarHeatDebug registered (/war heat visual board is LIVE)");

            // CRITICAL FIX: the air-operations tick driver. AirStrikeController.tick() was never called,
            // so surge waves + aircraft missions were dead -- "never saw a single aircraft".
            MinecraftForge.EVENT_BUS.register(studio.ERM.war.air.AirStrikeController.class);
            logger.info("[War] AirStrikeController tick registered (air operations are LIVE)");

            // PHASE 2 -- the strategic world simulation tick loop (Base module). Static @SubscribeEvent
            // -> register the CLASS. Without this the whole strategic map is dead (the classic
            // unregistered-handler failure), so log it loudly.
            MinecraftForge.EVENT_BUS.register(studio.ERM.strategic.StrategicSimulator.class);
            logger.info("[Strategic] simulator registered (strategic map is LIVE)");

            // PHASE 2 -- the defensive-plan Military AI (staffs the player's map-drawn plan with the
            // available guards: strongpoints/lines/reserves in war, patrol routes in peace).
            MinecraftForge.EVENT_BUS.register(studio.ERM.strategic.defense.DefensePlanExecutor.class);
            logger.info("[Strategic] defense-plan executor registered");

            // DISTRICT LABOR — the civilian mirror: drawn districts hijack AW2 worker npcs and
            // reorder their tasks (work spots inside the polygon -> yields into the depot).
            MinecraftForge.EVENT_BUS.register(studio.ERM.strategic.civil.DistrictWorkExecutor.class);
            logger.info("[Strategic] district work executor registered (district labor is LIVE)");

            // BED AUTO-ASSIGNMENT — housing ownership: citizens claim persistent Residential/Barracks
            // beds (scored indoor/lit/reachable), repaired when beds break or districts change.
            MinecraftForge.EVENT_BUS.register(studio.ERM.strategic.civil.BedAssignmentManager.class);
            logger.info("[Strategic] bed assignment manager registered (housing ownership is LIVE)");

            // ROADS — majority-block survey + condition grading + visible builder auto-repair.
            // Without this the Civilian tab's roads are decoration (the unregistered-handler failure).
            MinecraftForge.EVENT_BUS.register(studio.ERM.strategic.civil.RoadNetworkManager.class);
            logger.info("[Strategic] road network manager registered (roads are LIVE)");

            // PHASE 3 — settlement-wide courier logistics: depot IN/OUT templates -> shortages/
            // surpluses -> jobs -> hired couriers walking cargo depot-to-depot (Warehouse = hub).
            MinecraftForge.EVENT_BUS.register(studio.ERM.strategic.civil.logistics.LogisticsManager.class);
            logger.info("[Strategic] logistics manager registered (couriers are LIVE)");

            // THE GENERIC PATROL ENGINE — wild level-0 threats now, nation/rival patrols, convoy
            // escorts and scout parties later. Config-driven (patrols.json); unregistered = dead.
            MinecraftForge.EVENT_BUS.register(studio.ERM.strategic.patrol.PatrolFramework.class);
            logger.info("[Strategic] patrol framework registered (the wilds are LIVE)");

            // NATION STATES — the lightweight second AI civilization: static settlements + noise
            // (patrols/traders). Spawned in a ring by /war rival city; tier-2 towns at rival L2.
            MinecraftForge.EVENT_BUS.register(studio.ERM.strategic.nation.NationStateManager.class);
            logger.info("[Strategic] nation states registered (the world is INHABITED)");

            // Strategic Missions (Civilian map right-click): timed parties -> deliver a haul to a depot.
            MinecraftForge.EVENT_BUS.register(studio.ERM.strategic.civil.StrategicMissionManager.class);
            logger.info("[Strategic] mission manager registered (hunting party is LIVE)");

            // Trade Depot shipments: buy = timed import delivery, sell = timed export payout + market decay.
            MinecraftForge.EVENT_BUS.register(studio.ERM.strategic.civil.trade.TradeShipmentManager.class);
            logger.info("[Strategic] trade shipment manager registered (Trade Depot is LIVE)");

            // Research: scientists in a Research district grind the player's queued AW2 node to completion.
            MinecraftForge.EVENT_BUS.register(studio.ERM.strategic.civil.research.ResearchManager.class);
            logger.info("[Strategic] research manager registered (Research tree is LIVE)");

            // Factory: assembly seats inside a Factory district craft their recipes on a timer.
            MinecraftForge.EVENT_BUS.register(studio.ERM.strategic.civil.factory.FactoryManager.class);
            logger.info("[Strategic] factory manager registered (assembly seats are LIVE)");

            // MODERN-WAR SOUNDBOARD — layered AW2 sound recipes for modern weapons (its tick scheduler
            // drives the delayed layers; every cue no-ops cleanly if AW2 is absent).
            MinecraftForge.EVENT_BUS.register(studio.ERM.war.sound.WarSoundboard.class);
            logger.info("[War] soundboard registered (AW2 sound recipes are LIVE)");

            // PHASE 2 -- container GUIs (the RECRUIT loadout screen).
            net.minecraftforge.fml.common.network.NetworkRegistry.INSTANCE.registerGuiHandler(
                    instance, new studio.ERM.strategic.defense.ErmGuiHandler());
            logger.info("[Strategic] gui handler registered (recruit screen)");

            // CRITICAL FIX: populate the battle-director registry. It was never initialized, so the
            // map's "Deploy Battle" menu listed ZERO battle types (empty dropdown) and any registry
            // lookup returned nothing. init() is idempotent and self-guards on `initialized`.
            studio.ERM.war.BattleManagers.directors.BattleDirectorRegistry.init();
            logger.info("[BattleManagers] BattleDirectorRegistry initialized ("
                    + studio.ERM.war.BattleManagers.directors.BattleDirectorRegistry.getAll().size()
                    + " battle types available in the deploy menu)");

            // MultiCharacterMod has instance methods - get the instance
            co.runed.multicharacter.MultiCharacterMod.getInstance().init(event);
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

        // CRITICAL CRASH FIX: this pack has advancement(s) that reward a recipe which no longer
        // exists. When such an advancement completes (ANY inventory change can do it), vanilla calls
        // EntityPlayerMP.unlockRecipes with a null recipe and ForgeHooks.sendRecipeBook NPEs ->
        // "Ticking player" server crash. We can't edit pack data, so strip the dangling recipe
        // rewards from every advancement at server start. This permanently kills that recurring crash.
        sanitizeBrokenAdvancementRecipes(event.getServer());

        // WorldSpawner: load config + register its command. Routed through this @Mod.EventHandler
        // because FML lifecycle events cannot be handled via @SubscribeEvent on the Forge bus.
        studio.WorldSpawner.WorldSpawnerModule.onServerStarting(event);

        // MultiCharacterMod uses @EventHandler for serverStart, call instance method
        co.runed.multicharacter.MultiCharacterMod.getInstance().serverStart(event);
    }

    /**
     * Remove recipe rewards that point at non-existent recipes from every loaded advancement. Such a
     * dangling reward makes vanilla/Forge NPE in ForgeHooks.sendRecipeBook when the advancement is
     * granted, crashing the server ("Ticking player"). Done reflectively because AdvancementRewards
     * stores its recipe list in a private final field.
     */
    public static void sanitizeBrokenAdvancementRecipes(net.minecraft.server.MinecraftServer server) {
        if (server == null) return;
        try {
            // AdvancementRewards.recipes is private final ResourceLocation[]. Its runtime name differs
            // between the dev (deobf "recipes") and shipped (SRG "field_192117_d") environments, so try
            // both. SRG confirmed from MCP stable_39 fields.csv.
            java.lang.reflect.Field recipesField = null;
            for (String n : new String[]{"field_192117_d", "recipes"}) {
                try { recipesField = net.minecraft.advancements.AdvancementRewards.class.getDeclaredField(n); break; }
                catch (NoSuchFieldException ignored) {}
            }
            if (recipesField == null) {
                logger.warn("[Antigrief] Could not locate AdvancementRewards.recipes field; recipe-book crash guard inactive.");
                return;
            }
            recipesField.setAccessible(true);

            int scanned = 0, fixedAdvancements = 0, droppedRecipes = 0;
            for (net.minecraft.advancements.Advancement adv : server.getAdvancementManager().getAdvancements()) {
                scanned++;
                net.minecraft.advancements.AdvancementRewards rewards = adv.getRewards();
                if (rewards == null) continue;
                Object val = recipesField.get(rewards);
                if (!(val instanceof net.minecraft.util.ResourceLocation[])) continue;
                net.minecraft.util.ResourceLocation[] recipes = (net.minecraft.util.ResourceLocation[]) val;
                if (recipes.length == 0) continue;

                // STRIP ALL recipe rewards (not just dangling ones). The crash ALSO fires for recipes that
                // resolve but have a null/blank registry name -- sendRecipeBook NPEs on name.toString(),
                // which the old dangling-only filter missed (and is exactly the crash that recurred). The
                // advancement recipe-book auto-unlock is purely cosmetic, so clearing every recipe reward
                // permanently kills the "Ticking player -> sendRecipeBook NPE" crash for good.
                recipesField.set(rewards, new net.minecraft.util.ResourceLocation[0]);
                fixedAdvancements++;
                droppedRecipes += recipes.length;
            }
            // Log once, the first time we actually scan a populated advancement set, so it's clear the
            // guard ran (and whether anything was dangling).
            if (scanned > 0 && !loggedAdvancementSanitize) {
                loggedAdvancementSanitize = true;
                logger.info("[Antigrief] Advancement recipe-book guard: scanned " + scanned
                        + " advancement(s), fixed " + fixedAdvancements + " with " + droppedRecipes
                        + " missing recipe reward(s).");
            }
        } catch (Throwable t) {
            logger.warn("[Antigrief] Advancement recipe sanitize failed (recipe-book crash may persist): " + t);
        }
    }

    @Mod.EventBusSubscriber(modid = MODID)
    public static class RegistrationHandler {
        @SubscribeEvent
        public static void registerBlocks(RegistryEvent.Register<Block> event) {
            district_marker = new studio.ERM.war.districts.BlockDistrictMarker().setRegistryName("district_marker").setTranslationKey(MODID + ".district_marker");
            citizen_bed = new studio.ERM.war.districts.BlockCitizenBed().setRegistryName("citizen_bed").setTranslationKey(MODID + ".citizen_bed");
            scaffold = new studio.ERM.war.districts.BlockScaffold().setRegistryName("scaffold").setTranslationKey(MODID + ".scaffold");
            assembly_seat = new studio.ERM.war.districts.BlockAssemblySeat().setRegistryName("assembly_seat").setTranslationKey(MODID + ".assembly_seat");
            family_kitchen = new studio.ERM.war.districts.BlockFamilyKitchen().setRegistryName("family_kitchen").setTranslationKey(MODID + ".family_kitchen");

            event.getRegistry().registerAll(citizen_bed, district_marker, scaffold, assembly_seat, family_kitchen);
            logger.info("[NUCLEAR-LOG] Blocks registered: citizen_bed, district_marker, scaffold, assembly_seat, family_kitchen");
        }

        @SubscribeEvent
        public static void registerItems(RegistryEvent.Register<Item> event) {
            // ItemProtector extends ItemBase, whose constructor already calls setRegistryName("homosapien","entity_protector").
            // Re-setting the registry name here would double-set and crash mod loading, so only the translation key is applied.
            entity_protector = new studio.ERM.items.ItemProtector().setTranslationKey(MODID + ".entity_protector");
            sabotage_fixer = new studio.ERM.war.items.ItemSabotageFixer().setRegistryName("sabotage_fixer").setTranslationKey(MODID + ".sabotage_fixer");
            camp_setter = new studio.ERM.war.items.ItemCampSetter().setRegistryName("camp_setter").setTranslationKey(MODID + ".camp_setter");
            modern_citizen_item = new studio.ERM.war.items.ItemModernCitizen().setRegistryName("modern_citizen_item").setTranslationKey(MODID + ".modern_citizen_item");

            // Player-callable air strike item. Was implemented but never registered, which is why
            // all of the player's airstrike items were missing from the game.
            air_target_designator = new studio.ERM.war.items.ItemAirTargetDesignator().setRegistryName("air_target_designator").setTranslationKey(MODID + ".air_target_designator");

            // Job assignment items - simple items that assign jobs when given to citizens
            hammer = new Item().setRegistryName("hammer").setTranslationKey(MODID + ".hammer").setCreativeTab(CreativeTabs.TOOLS).setMaxStackSize(1);
            multimeter = new Item().setRegistryName("multimeter").setTranslationKey(MODID + ".multimeter").setCreativeTab(CreativeTabs.TOOLS).setMaxStackSize(1);
            blueprint = new Item().setRegistryName("blueprint").setTranslationKey(MODID + ".blueprint").setCreativeTab(CreativeTabs.MISC).setMaxStackSize(1);
            command_buck = new Item().setRegistryName("command_buck").setTranslationKey(MODID + ".command_buck").setCreativeTab(CreativeTabs.COMBAT).setMaxStackSize(1);
            gold_wrench = new Item().setRegistryName("gold_wrench").setTranslationKey(MODID + ".gold_wrench").setCreativeTab(CreativeTabs.TOOLS).setMaxStackSize(1).setMaxDamage(128);

            // Expertise items
            expertise_industry = new studio.ERM.war.items.ItemExpertise(studio.ERM.war.items.ItemExpertise.ExpertiseKind.INDUSTRY).setRegistryName("expertise_industry").setTranslationKey(MODID + ".expertise_industry");
            expertise_agriculture = new studio.ERM.war.items.ItemExpertise(studio.ERM.war.items.ItemExpertise.ExpertiseKind.AGRICULTURE).setRegistryName("expertise_agriculture").setTranslationKey(MODID + ".expertise_agriculture");
            expertise_defense = new studio.ERM.war.items.ItemExpertise(studio.ERM.war.items.ItemExpertise.ExpertiseKind.DEFENSE).setRegistryName("expertise_defense").setTranslationKey(MODID + ".expertise_defense");
            expertise_resource = new studio.ERM.war.items.ItemExpertise(studio.ERM.war.items.ItemExpertise.ExpertiseKind.RESOURCE).setRegistryName("expertise_resource").setTranslationKey(MODID + ".expertise_resource");

            event.getRegistry().registerAll(entity_protector, sabotage_fixer, camp_setter, modern_citizen_item,
                air_target_designator,
                hammer, multimeter, blueprint, command_buck, gold_wrench,
                expertise_industry, expertise_agriculture, expertise_defense, expertise_resource);

            event.getRegistry().register(new ItemBlock(citizen_bed).setRegistryName("citizen_bed"));
            event.getRegistry().register(new ItemBlock(district_marker).setRegistryName("district_marker"));
            event.getRegistry().register(new ItemBlock(scaffold).setRegistryName("scaffold"));
            event.getRegistry().register(new ItemBlock(assembly_seat).setRegistryName("assembly_seat"));
            event.getRegistry().register(new ItemBlock(family_kitchen).setRegistryName("family_kitchen"));

            logger.info("[NUCLEAR-LOG] Items registered: all items including job items (hammer, multimeter, blueprint, command_buck, gold_wrench)");
        }
    }
}
