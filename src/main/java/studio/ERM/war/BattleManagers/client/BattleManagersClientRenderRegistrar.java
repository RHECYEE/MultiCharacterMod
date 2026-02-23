package studio.ERM.war.BattleManagers.client;

import net.minecraftforge.client.event.ModelRegistryEvent;
import net.minecraftforge.fml.client.registry.RenderingRegistry;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import net.minecraftforge.fml.relauncher.Side;
import net.minecraftforge.fml.relauncher.SideOnly;

import studio.ERM.war.BattleManagers.entities.EntityFormationCarrier;
import studio.ERM.war.BattleManagers.entities.EntitySoldier;
import studio.ERM.war.BattleManagers.entities.EntitySoldierPuppet;
import studio.ERM.war.entities.render.RenderSkinnable;

/**
 * v8 — Client-only renderer registration for BattleManagers entities.
 *
 * Added: EntitySoldier rendering with RenderSkinnable (biped + skin pool + held items).
 * RenderBiped automatically renders held items in mainhand/offhand via HeldItemLayer.
 */
@SideOnly(Side.CLIENT)
@Mod.EventBusSubscriber(value = Side.CLIENT)
public final class BattleManagersClientRenderRegistrar {

    private static boolean registered = false;

    private BattleManagersClientRenderRegistrar() {}

    @SubscribeEvent
    public static void onModelRegistry(ModelRegistryEvent event) {
        if (registered) return;
        registered = true;

        // Soldier puppets: biped model + dynamic skin pool textures (visual only, no items)
        RenderingRegistry.registerEntityRenderingHandler(EntitySoldierPuppet.class, RenderSkinnable::new);

        // Formation carrier: biped for debug visibility
        RenderingRegistry.registerEntityRenderingHandler(EntityFormationCarrier.class, RenderSkinnable::new);

        // EntitySoldier: biped + skin pool + held items (swords, bows, shields render automatically)
        RenderingRegistry.registerEntityRenderingHandler(EntitySoldier.class, RenderSkinnable::new);
    }
}
