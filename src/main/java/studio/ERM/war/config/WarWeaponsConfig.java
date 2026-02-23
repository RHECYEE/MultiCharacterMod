package studio.ERM.war.config;

import net.minecraftforge.common.config.Config;
import net.minecraftforge.common.config.ConfigManager;
import net.minecraftforge.fml.client.event.ConfigChangedEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;

/**
 * Configurable weapon and armor loadouts for battle soldiers.
 *
 * Each entry is a comma-separated list of item registry names, one per level (L1–L10).
 * You can use any valid item registry name, including modded items.
 *
 * Example (config/erm_weapons.cfg):
 *   S:melee_weapons <
 *       minecraft:wooden_sword
 *       minecraft:stone_sword
 *       minecraft:iron_sword
 *       ...
 *   >
 *
 * Roles:
 *   MELEE     — standard sword/axe fighters
 *   RANGED    — bow/gun users
 *   HEAVY     — axe bearers
 *   SHIELDWALL — spear + shield
 *   CAVALRY   — melee, one-handed
 *   SPECIAL   — elite melee + shield
 *
 * Offhand entries use "none" for empty slot.
 * Armor entries use "none" for unarmored slot.
 */
@Config(modid = "erm", name = "erm_weapons")
@Config.LangKey("config.erm.weapons")
public class WarWeaponsConfig {

    // ═══════════════════════════════════════════════════════
    //  MELEE weapons — L1 through L10
    // ═══════════════════════════════════════════════════════

    @Config.Comment({
        "Main-hand weapon for MELEE role, one entry per level (L1–L10).",
        "Use valid item registry names: 'minecraft:iron_sword', 'ancientwarfarenpc:iron_spear', etc."
    })
    @Config.Name("melee_weapons")
    public static String[] meleeWeapons = {
        /* L1  */ "minecraft:wooden_sword",
        /* L2  */ "minecraft:stone_sword",
        /* L3  */ "minecraft:iron_sword",
        /* L4  */ "minecraft:iron_axe",
        /* L5  */ "minecraft:golden_sword",
        /* L6  */ "minecraft:iron_sword",
        /* L7  */ "minecraft:diamond_sword",
        /* L8  */ "minecraft:diamond_axe",
        /* L9  */ "minecraft:diamond_sword",
        /* L10 */ "minecraft:diamond_sword"
    };

    // ═══════════════════════════════════════════════════════
    //  RANGED weapons — L1 through L10
    // ═══════════════════════════════════════════════════════

    @Config.Comment({
        "Main-hand weapon for RANGED role, one entry per level (L1–L10).",
        "Use 'minecraft:bow' for vanilla bow, or a Flan's gun like 'flansmod:ak47'."
    })
    @Config.Name("ranged_weapons")
    public static String[] rangedWeapons = {
        /* L1  */ "minecraft:bow",
        /* L2  */ "minecraft:bow",
        /* L3  */ "minecraft:bow",
        /* L4  */ "minecraft:bow",
        /* L5  */ "minecraft:bow",
        /* L6  */ "minecraft:bow",
        /* L7  */ "minecraft:bow",
        /* L8  */ "flansmod:ak47",
        /* L9  */ "flansmod:ak47",
        /* L10 */ "flansmod:m4"
    };

    // ═══════════════════════════════════════════════════════
    //  HEAVY weapons — L1 through L10
    // ═══════════════════════════════════════════════════════

    @Config.Comment({
        "Main-hand weapon for HEAVY role, one entry per level (L1–L10)."
    })
    @Config.Name("heavy_weapons")
    public static String[] heavyWeapons = {
        /* L1  */ "minecraft:wooden_axe",
        /* L2  */ "minecraft:stone_axe",
        /* L3  */ "minecraft:iron_axe",
        /* L4  */ "minecraft:iron_axe",
        /* L5  */ "minecraft:iron_axe",
        /* L6  */ "minecraft:diamond_axe",
        /* L7  */ "minecraft:diamond_axe",
        /* L8  */ "minecraft:diamond_sword",
        /* L9  */ "minecraft:diamond_sword",
        /* L10 */ "minecraft:diamond_sword"
    };

    // ═══════════════════════════════════════════════════════
    //  SHIELDWALL weapons — L1 through L10
    // ═══════════════════════════════════════════════════════

    @Config.Comment({
        "Main-hand weapon for SHIELDWALL role (spear / polearm), one entry per level (L1–L10).",
        "Uses AW2 spears by default, but you can replace with any item."
    })
    @Config.Name("shieldwall_weapons")
    public static String[] shieldwallWeapons = {
        /* L1  */ "ancientwarfarenpc:wooden_spear",
        /* L2  */ "ancientwarfarenpc:stone_spear",
        /* L3  */ "ancientwarfarenpc:iron_spear",
        /* L4  */ "ancientwarfarenpc:iron_spear",
        /* L5  */ "ancientwarfarenpc:iron_spear",
        /* L6  */ "ancientwarfarenpc:iron_spear",
        /* L7  */ "ancientwarfarenpc:diamond_spear",
        /* L8  */ "ancientwarfarenpc:diamond_spear",
        /* L9  */ "ancientwarfarenpc:diamond_spear",
        /* L10 */ "ancientwarfarenpc:diamond_spear"
    };

    @Config.Comment({
        "Off-hand item for SHIELDWALL role (shield), one entry per level (L1–L10).",
        "Use 'none' for an empty off-hand slot."
    })
    @Config.Name("shieldwall_offhand")
    public static String[] shieldwallOffhand = {
        /* L1  */ "ancientwarfarenpc:wooden_shield",
        /* L2  */ "ancientwarfarenpc:wooden_shield",
        /* L3  */ "ancientwarfarenpc:iron_shield",
        /* L4  */ "ancientwarfarenpc:iron_shield",
        /* L5  */ "ancientwarfarenpc:iron_shield",
        /* L6  */ "ancientwarfarenpc:iron_shield",
        /* L7  */ "ancientwarfarenpc:iron_shield",
        /* L8  */ "ancientwarfarenpc:diamond_shield",
        /* L9  */ "ancientwarfarenpc:diamond_shield",
        /* L10 */ "ancientwarfarenpc:diamond_shield"
    };

    // ═══════════════════════════════════════════════════════
    //  ARMOR — by level (HEAD, CHEST, LEGS, FEET)
    //  Use "none" for unarmored slot.
    // ═══════════════════════════════════════════════════════

    @Config.Comment({
        "Helmet item per level (L1–L10). Use 'none' for no helmet."
    })
    @Config.Name("armor_head")
    public static String[] armorHead = {
        /* L1  */ "none",
        /* L2  */ "minecraft:leather_helmet",
        /* L3  */ "minecraft:chainmail_helmet",
        /* L4  */ "minecraft:chainmail_helmet",
        /* L5  */ "minecraft:iron_helmet",
        /* L6  */ "minecraft:iron_helmet",
        /* L7  */ "minecraft:iron_helmet",
        /* L8  */ "minecraft:diamond_helmet",
        /* L9  */ "minecraft:diamond_helmet",
        /* L10 */ "minecraft:diamond_helmet"
    };

    @Config.Comment({
        "Chestplate item per level (L1–L10). Use 'none' for no chestplate."
    })
    @Config.Name("armor_chest")
    public static String[] armorChest = {
        /* L1  */ "none",
        /* L2  */ "minecraft:leather_chestplate",
        /* L3  */ "minecraft:leather_chestplate",
        /* L4  */ "minecraft:chainmail_chestplate",
        /* L5  */ "minecraft:chainmail_chestplate",
        /* L6  */ "minecraft:iron_chestplate",
        /* L7  */ "minecraft:iron_chestplate",
        /* L8  */ "minecraft:iron_chestplate",
        /* L9  */ "minecraft:diamond_chestplate",
        /* L10 */ "minecraft:diamond_chestplate"
    };

    @Config.Comment({
        "Leggings item per level (L1–L10). Use 'none' for no leggings."
    })
    @Config.Name("armor_legs")
    public static String[] armorLegs = {
        /* L1  */ "minecraft:leather_leggings",
        /* L2  */ "minecraft:leather_leggings",
        /* L3  */ "minecraft:chainmail_leggings",
        /* L4  */ "minecraft:chainmail_leggings",
        /* L5  */ "minecraft:iron_leggings",
        /* L6  */ "minecraft:iron_leggings",
        /* L7  */ "minecraft:iron_leggings",
        /* L8  */ "minecraft:diamond_leggings",
        /* L9  */ "minecraft:diamond_leggings",
        /* L10 */ "minecraft:diamond_leggings"
    };

    @Config.Comment({
        "Boots item per level (L1–L10). Use 'none' for no boots."
    })
    @Config.Name("armor_feet")
    public static String[] armorFeet = {
        /* L1  */ "minecraft:leather_boots",
        /* L2  */ "minecraft:leather_boots",
        /* L3  */ "minecraft:leather_boots",
        /* L4  */ "minecraft:chainmail_boots",
        /* L5  */ "minecraft:iron_boots",
        /* L6  */ "minecraft:iron_boots",
        /* L7  */ "minecraft:iron_boots",
        /* L8  */ "minecraft:iron_boots",
        /* L9  */ "minecraft:diamond_boots",
        /* L10 */ "minecraft:diamond_boots"
    };

    // ═══════════════════════════════════════════════════════
    //  MELEE off-hand shield unlock level
    // ═══════════════════════════════════════════════════════

    @Config.Comment({
        "At what war level MELEE soldiers gain a vanilla shield in their off-hand.",
        "Set to 11 to disable shields entirely."
    })
    @Config.Name("melee_shield_unlock_level")
    @Config.RangeInt(min = 1, max = 11)
    public static int meleeShieldUnlockLevel = 4;

    /**
     * Force a config reload.
     */
    public static void sync() {
        ConfigManager.sync("erm", Config.Type.INSTANCE);
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
