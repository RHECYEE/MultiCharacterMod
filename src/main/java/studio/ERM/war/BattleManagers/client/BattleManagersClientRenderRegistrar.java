package studio.ERM.war.BattleManagers.client;

import net.minecraftforge.client.event.ModelRegistryEvent;
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

        // Airstrike aircraft: crash-proof placeholder box. RenderGhostAircraft draws the real Flan
        // model but only by building a live Flan EntityPlane, whose constructor spawns seat entities
        // into the world -> ConcurrentModificationException when it happens during RenderGlobal's
        // entity iteration. Deferring via Minecraft.addScheduledTask does NOT help: that method runs
        // the task immediately when called from the client (render) thread. Real plane models need a
        // render path that never constructs a live EntityPlane (the archived working version). Box =
        // visible aircraft, zero crash, until that path is restored.
        safeRegister("EntityGhostAircraft", () ->
                RenderingRegistry.registerEntityRenderingHandler(
                        studio.ERM.war.air.EntityGhostAircraft.class,
                        new studio.ERM.war.air.RenderGhostAircraftSafe.Factory()));
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
