package studio.ERM.war.BattleManagers.entities;

import net.minecraft.init.Items;
import net.minecraft.inventory.EntityEquipmentSlot;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import studio.ERM.war.config.WarWeaponsConfig;

/**
 * Equips EntitySoldier instances from the configurable WarWeaponsConfig.
 *
 * All weapon and armor tables live in config/erm_weapons.cfg.
 * This class reads those tables and applies them to the soldier.
 *
 * Roles:
 *   MELEE     — sword/axe + optional shield at higher levels
 *   RANGED    — bow or Flan's gun
 *   HEAVY     — heavy axe/sword
 *   SHIELDWALL — spear + shield (AW2 items by default)
 *   CAVALRY   — melee weapon, one-handed
 *   SPECIAL   — best melee + shield
 */
public final class SoldierLoadout {

    private SoldierLoadout() {}

    /**
     * Equip a soldier with the appropriate loadout for their level and role.
     * All items are read from WarWeaponsConfig so modpack makers can configure them.
     *
     * @param soldier  The entity to equip
     * @param warLevel 1–10
     * @param role     "MELEE", "RANGED", "HEAVY", "SPECIAL", "SHIELDWALL", "CAVALRY"
     */
    public static void equip(EntitySoldier soldier, int warLevel, String role) {
        int idx = Math.max(0, Math.min(9, warLevel - 1));
        role = (role != null) ? role.toUpperCase() : "MELEE";

        String weaponId;
        String offhandId = null;

        switch (role) {
            case "RANGED":
                weaponId = safeGet(WarWeaponsConfig.rangedWeapons, idx);
                break;
            case "HEAVY":
                weaponId = safeGet(WarWeaponsConfig.heavyWeapons, idx);
                break;
            case "SHIELDWALL":
                weaponId = safeGet(WarWeaponsConfig.shieldwallWeapons, idx);
                offhandId = safeGet(WarWeaponsConfig.shieldwallOffhand, idx);
                break;
            case "CAVALRY":
                weaponId = safeGet(WarWeaponsConfig.meleeWeapons, idx);
                break;
            case "SPECIAL":
                // Special gets one tier higher melee + shield
                weaponId = safeGet(WarWeaponsConfig.meleeWeapons, Math.min(9, idx + 1));
                offhandId = safeGet(WarWeaponsConfig.shieldwallOffhand, idx);
                break;
            default: // MELEE
                weaponId = safeGet(WarWeaponsConfig.meleeWeapons, idx);
                break;
        }

        // Main hand
        ItemStack weapon = createStack(weaponId);
        if (!weapon.isEmpty()) {
            soldier.setItemStackToSlot(EntityEquipmentSlot.MAINHAND, weapon);
        }

        // Off hand
        if (offhandId != null && !"none".equalsIgnoreCase(offhandId)) {
            ItemStack offhand = createStack(offhandId);
            if (!offhand.isEmpty()) {
                soldier.setItemStackToSlot(EntityEquipmentSlot.OFFHAND, offhand);
            }
        } else if (role.equals("MELEE") && warLevel >= WarWeaponsConfig.meleeShieldUnlockLevel) {
            soldier.setItemStackToSlot(EntityEquipmentSlot.OFFHAND, new ItemStack(Items.SHIELD));
        }

        // Armor
        applyArmor(soldier, idx);

        // No drops
        for (EntityEquipmentSlot slot : EntityEquipmentSlot.values()) {
            soldier.setDropChance(slot, 0.0F);
        }
    }

    private static void applyArmor(EntitySoldier soldier, int idx) {
        String head  = safeGet(WarWeaponsConfig.armorHead,  idx);
        String chest = safeGet(WarWeaponsConfig.armorChest, idx);
        String legs  = safeGet(WarWeaponsConfig.armorLegs,  idx);
        String feet  = safeGet(WarWeaponsConfig.armorFeet,  idx);

        if (head  != null && !"none".equalsIgnoreCase(head))  { ItemStack s = createStack(head);  if (!s.isEmpty()) soldier.setItemStackToSlot(EntityEquipmentSlot.HEAD,  s); }
        if (chest != null && !"none".equalsIgnoreCase(chest)) { ItemStack s = createStack(chest); if (!s.isEmpty()) soldier.setItemStackToSlot(EntityEquipmentSlot.CHEST, s); }
        if (legs  != null && !"none".equalsIgnoreCase(legs))  { ItemStack s = createStack(legs);  if (!s.isEmpty()) soldier.setItemStackToSlot(EntityEquipmentSlot.LEGS,  s); }
        if (feet  != null && !"none".equalsIgnoreCase(feet))  { ItemStack s = createStack(feet);  if (!s.isEmpty()) soldier.setItemStackToSlot(EntityEquipmentSlot.FEET,  s); }
    }

    // ══════════════════════════════════════════════
    //  ROLE MAPPING (from UnitCard names)
    // ══════════════════════════════════════════════

    /** Maps a UnitCard name to a loadout role string. */
    public static String roleFromCardName(String cardName) {
        if (cardName == null) return "MELEE";
        switch (cardName.toLowerCase()) {
            case "shieldwall":     return "SHIELDWALL";
            case "skirmishline":   return "RANGED";
            case "phalanx":        return "SHIELDWALL";
            case "heavyinfantry":  return "HEAVY";
            case "lightcavalry":   return "CAVALRY";
            case "siegeunit":      return "HEAVY";
            case "elitesquad":     return "SPECIAL";
            case "mixedcompany":   return "MELEE";
            case "vehicleplatoon": return "HEAVY";
            case "scoutteam":      return "RANGED";
            default:               return "MELEE";
        }
    }

    /** Maps a UnitRole enum name to a loadout role string. */
    public static String roleFromUnitRole(String unitRole) {
        if (unitRole == null) return "MELEE";
        switch (unitRole.toUpperCase()) {
            case "MELEE":   return "MELEE";
            case "RANGED":  return "RANGED";
            case "HEAVY":   return "HEAVY";
            case "SPECIAL": return "SPECIAL";
            default:        return "MELEE";
        }
    }

    // ══════════════════════════════════════════════
    //  HELPERS
    // ══════════════════════════════════════════════

    private static String safeGet(String[] arr, int idx) {
        if (arr == null || arr.length == 0) return null;
        return arr[Math.max(0, Math.min(arr.length - 1, idx))];
    }

    private static ItemStack createStack(String registryName) {
        if (registryName == null || registryName.isEmpty() || "none".equalsIgnoreCase(registryName))
            return ItemStack.EMPTY;
        try {
            Item item = Item.getByNameOrId(registryName);
            if (item != null) return new ItemStack(item);
        } catch (Throwable ignored) {}
        return ItemStack.EMPTY;
    }
}
