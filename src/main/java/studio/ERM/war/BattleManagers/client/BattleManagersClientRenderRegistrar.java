package studio.ERM.war.BattleManagers.client;

import net.minecraft.block.Block;
import net.minecraft.client.renderer.block.model.ModelResourceLocation;
import net.minecraft.item.Item;
import net.minecraftforge.client.event.ModelRegistryEvent;
import net.minecraftforge.client.model.ModelLoader;
import net.minecraftforge.fml.client.registry.RenderingRegistry;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import net.minecraftforge.fml.relauncher.Side;
import net.minecraftforge.fml.relauncher.SideOnly;

import studio.ERM.EpochRunnerMod;
import studio.ERM.war.BattleManagers.entities.EntityFormationCarrier;
import studio.ERM.war.BattleManagers.entities.EntitySoldier;
import studio.ERM.war.BattleManagers.entities.EntitySoldierPuppet;
import studio.ERM.war.entities.EntityModernCitizen;
import studio.ERM.war.entities.render.RenderModernCitizen;
import studio.ERM.war.entities.render.RenderSkinnable;

/**
 * v8 — Client-only renderer registration for BattleManagers entities.
 *
 * Added: EntitySoldier rendering with RenderSkinnable (biped + skin pool + held items).
 * RenderBiped automatically renders held items in mainhand/offhand via HeldItemLayer.
 *
 * NOTE: the modid below is REQUIRED. This jar bundles two @Mod classes (multicharacter +
 * homosapien); a bare {@code @Mod.EventBusSubscriber(value = CLIENT)} with no modid cannot be
 * attributed to either, so Forge logs "Could not determine owning mod for @EventBusSubscriber"
 * (twice -- once per mod) and the subscriber registration is unreliable. Pinning it to homosapien
 * makes the ModelRegistryEvent handler fire deterministically so these entities actually get their
 * renderers instead of falling back to an untextured/white biped.
 */
@SideOnly(Side.CLIENT)
@Mod.EventBusSubscriber(modid = EpochRunnerMod.MODID, value = Side.CLIENT)
public final class BattleManagersClientRenderRegistrar {

    private static boolean registered = false;

    private BattleManagersClientRenderRegistrar() {}

    @SubscribeEvent
    public static void onModelRegistry(ModelRegistryEvent event) {
        if (registered) return;
        registered = true;

        // ITEM MODELS. In 1.12.2 every item MUST get setCustomModelResourceLocation or it renders as the
        // purple/black missing-model cube -- assets in the jar are NOT enough on their own. NONE of the
        // homosapien items were ever bound (ClientProxy.registerModels was left empty), which is why EVERY
        // custom item showed missing-texture in-game (only the spawn egg survived -- Forge auto-handles it).
        // Bind each to its <modid>:<registryName>#inventory model; most parent vanilla textures so they
        // render immediately once bound.
        bindItemModel(EpochRunnerMod.entity_protector);
        bindItemModel(EpochRunnerMod.sabotage_fixer);
        bindItemModel(EpochRunnerMod.camp_setter);
        bindItemModel(EpochRunnerMod.modern_citizen_item);
        bindItemModel(EpochRunnerMod.air_target_designator);
        bindItemModel(EpochRunnerMod.hammer);
        bindItemModel(EpochRunnerMod.multimeter);
        bindItemModel(EpochRunnerMod.blueprint);
        bindItemModel(EpochRunnerMod.command_buck);
        bindItemModel(EpochRunnerMod.gold_wrench);
        bindItemModel(EpochRunnerMod.expertise_industry);
        bindItemModel(EpochRunnerMod.expertise_agriculture);
        bindItemModel(EpochRunnerMod.expertise_defense);
        bindItemModel(EpochRunnerMod.expertise_resource);
        bindBlockItemModel(EpochRunnerMod.citizen_bed);
        bindBlockItemModel(EpochRunnerMod.district_marker);
        // Scaffold is an invisible passable marker block, so it has no real model -> its ITEM showed the
        // purple missing-model cube in JEI/creative. Hijack the vanilla GLASS model so it reads as a clean
        // glass block icon instead. (It was never in the bind list at all.)
        try {
            net.minecraft.item.Item scaffoldItem = net.minecraft.item.Item.getItemFromBlock(EpochRunnerMod.scaffold);
            if (scaffoldItem != null) {
                ModelLoader.setCustomModelResourceLocation(scaffoldItem, 0,
                        new ModelResourceLocation("minecraft:glass", "inventory"));
            }
        } catch (Throwable t) {
            EpochRunnerMod.logger.warn("[BattleManagers] scaffold->glass model bind failed: " + t);
        }

        // Each registration is guarded independently: RenderGhostAircraft references several
        // Flan-mod classes directly, and in some packs one of those is not resolvable at load time
        // (NoClassDefFoundError). Previously this method never actually ran (its @EventBusSubscriber
        // had no modid), so that latent failure was hidden; once the modid was fixed, an UNguarded
        // ghost-aircraft registration crashed the whole game during init -- taking the critical
        // soldier/citizen renderers down with it. Per-registration try/catch keeps one fragile
        // renderer from aborting init or blocking the others.

        // Soldier puppets: biped model + dynamic skin pool textures (visual only, no items)
        safeRegister("EntitySoldierPuppet", () ->
                RenderingRegistry.registerEntityRenderingHandler(EntitySoldierPuppet.class, RenderSkinnable::new));

        // Formation carrier: biped for debug visibility
        safeRegister("EntityFormationCarrier", () ->
                RenderingRegistry.registerEntityRenderingHandler(EntityFormationCarrier.class, RenderSkinnable::new));

        // EntitySoldier: biped + skin pool + held items (swords, bows, shields render automatically)
        safeRegister("EntitySoldier", () ->
                RenderingRegistry.registerEntityRenderingHandler(EntitySoldier.class, RenderSkinnable::new));

        // Player-spawned modern citizen: biped + AW2 skin-pool textures via SkinTextureCache.
        safeRegister("EntityModernCitizen", () ->
                RenderingRegistry.registerEntityRenderingHandler(EntityModernCitizen.class, RenderModernCitizen::new));

        // Flan's-vehicle pilot/crew (now ISkinnable + registered with a spawn egg): biped + AW2
        // skin-pool texture. Without this the registered pilot would render as a default biped.
        safeRegister("EntityAIPilot", () ->
                RenderingRegistry.registerEntityRenderingHandler(
                        studio.ERM.war.vehicle.EntityAIPilot.class, RenderSkinnable::new));

        // Airstrike aircraft: the BOX renderer is the REGISTERED one because it has ZERO Flan imports,
        // so it can never fail to class-load -> aircraft are always visible. It ATTEMPTS the real Flan
        // model via an isolated FlanGhostModel.render() guarded by try/catch -- if that Flan-importing
        // class fails to load or render, it silently falls back to the box. Registering the real renderer
        // directly (last attempt) risked total invisibility if its Flan classes failed at render time.
        safeRegister("EntityGhostAircraft", () ->
                RenderingRegistry.registerEntityRenderingHandler(
                        studio.ERM.war.air.EntityGhostAircraft.class,
                        new studio.ERM.war.air.RenderGhostAircraftSafe.Factory()));
    }

    /** Bind one item to its {@code <modid>:<registryName>#inventory} model. Guarded so a missing/empty
     *  model JSON only leaves THAT item as the missing-model cube instead of aborting the whole pass. */
    private static void bindItemModel(Item item) {
        if (item == null || item.getRegistryName() == null) return;
        try {
            ModelLoader.setCustomModelResourceLocation(item, 0,
                    new ModelResourceLocation(item.getRegistryName(), "inventory"));
        } catch (Throwable t) {
            EpochRunnerMod.logger.warn("[BattleManagers] item model bind failed for "
                    + item.getRegistryName() + ": " + t);
        }
    }

    /** Bind the ItemBlock form of a block to its inventory model. */
    private static void bindBlockItemModel(Block block) {
        if (block == null) return;
        bindItemModel(Item.getItemFromBlock(block));
    }

    /** Run one renderer registration, swallowing any Throwable (incl. NoClassDefFoundError). */
    private static void safeRegister(String label, Runnable registration) {
        try {
            registration.run();
        } catch (Throwable t) {
            EpochRunnerMod.logger.error("[BattleManagers] Renderer registration failed for "
                    + label + " (entity will be invisible/default): " + t, t);
        }
    }
}
