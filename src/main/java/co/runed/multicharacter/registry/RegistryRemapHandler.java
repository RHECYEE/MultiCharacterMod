package co.runed.multicharacter.compat;

import co.runed.multicharacter.MultiCharacterMod;
import net.minecraft.item.Item;
import net.minecraftforge.event.RegistryEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;

/**
 * Remaps legacy saved IDs to the current MultiCharacter items so old worlds load.
 * Works even when the original items were accidentally registered under minecraft:*
 */
@Mod.EventBusSubscriber(modid = MultiCharacterMod.MODID)
public class RegistryRemapHandler {

    @SubscribeEvent
    public static void onMissingItemMappings(RegistryEvent.MissingMappings<Item> event) {
        for (RegistryEvent.MissingMappings.Mapping<Item> m : event.getAllMappings()) {

            String domain = m.key.getNamespace();
            String path = m.key.getPath();

            // Old saves may have used minecraft: because registryName was set without a modid
            boolean oldDomain = "minecraft".equals(domain) || "homosapieninfantry".equals(domain);
            if (!oldDomain) continue;

            if ("homosapieninfantry_ammo".equals(path)) {
                m.remap(ModItems.INFANTRY_AMMO);
            } else if ("homosapieninfantry_mag_empty".equals(path)) {
                m.remap(ModItems.MAG_EMPTY);
            } else if ("homosapieninfantry_mag_5".equals(path)) {
                m.remap(ModItems.MAG_5);
            } else if ("homosapieninfantry_mag_10".equals(path)) {
                m.remap(ModItems.MAG_10);
            } else if ("homosapieninfantry_mag_15".equals(path)) {
                m.remap(ModItems.MAG_15);
            } else if ("homosapieninfantry_mag_25".equals(path)) {
                m.remap(ModItems.MAG_25);
            }
        }
    }
}
