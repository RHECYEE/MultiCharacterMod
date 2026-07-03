package studio.ERM.war.BattleManagers.entities;

import net.minecraft.init.Items;
import net.minecraft.inventory.EntityEquipmentSlot;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.JsonToNBT;
import net.minecraft.nbt.NBTTagCompound;
import studio.ERM.war.config.WarWeaponsConfig;
import co.runed.multicharacter.compat.ModItems;

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

        // LEVEL 10 — modern infantry. EVERY soldier (whatever its card role) carries an
        // attachment-kitted Flan Uzi plus spare magazines so the injected gun AI keeps firing, with
        // modern armour. This overrides the medieval melee/bow loadout entirely. The gun AI
        // (AIInjectionHandler) recognises the held gun and makes them shoot at standoff range.
        if (warLevel >= 10) {
            // EVERY L10 soldier carries a MODERN GUN -- never a bow/medieval weapon. Prefer the kitted Uzi;
            // if that item isn't in the pack fall back to the configured L10 ranged gun, then known modern
            // rifles -- so we NEVER drop through to the medieval/bow loadout (the "arrows at L10" bug, which
            // happened whenever flansmod:uzi was absent and execution fell into the role switch -> bow).
            ItemStack gun = makeAttachmentUzi();
            if (gun.isEmpty()) gun = createStack(safeGet(WarWeaponsConfig.rangedWeapons, 9));
            if (gun.isEmpty()) gun = createStack("flansmod:m16a4");
            if (gun.isEmpty()) gun = createStack("flansmod:m4");
            if (!gun.isEmpty()) {
                soldier.setItemStackToSlot(EntityEquipmentSlot.MAINHAND, gun);
                if (ModItems.MAG_25 != null) {
                    soldier.setItemStackToSlot(EntityEquipmentSlot.OFFHAND, new ItemStack(ModItems.MAG_25, 4));
                }
                applyArmor(soldier, idx); // KSK kit (applied to all troops now)
                for (EntityEquipmentSlot slot : EntityEquipmentSlot.values()) {
                    soldier.setDropChance(slot, 0.0F);
                }
                return;
            }
        }

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
                // Mounted knight: sword + SHIELD at L1-9 (the L10 override above gives the modern Uzi).
                weaponId = safeGet(WarWeaponsConfig.meleeWeapons, idx);
                offhandId = safeGet(WarWeaponsConfig.shieldwallOffhand, idx);
                if (offhandId == null || "none".equalsIgnoreCase(offhandId)) offhandId = "minecraft:shield";
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
        applyKskArmor(soldier);
    }

    private static boolean loggedKskMissing = false;

    /**
     * Equip the Flan KSK kit on ANY living unit (soldiers AND the formation puppets -- the puppets are
     * the bulk of what the player sees, and they had NO armour, which is why "no one has any armor").
     * Falls back to vanilla IRON if KSK isn't installed, so there is ALWAYS visible armour.
     */
    public static void applyKskArmor(net.minecraft.entity.EntityLivingBase e) {
        if (e == null) return;
        ItemStack h = createStack("flansmod:kskhelmet"), c = createStack("flansmod:kskbody"),
                  l = createStack("flansmod:kskpants"),  f = createStack("flansmod:kskboots");
        if (h.isEmpty() && c.isEmpty() && l.isEmpty() && f.isEmpty()) {
            if (!loggedKskMissing) {
                loggedKskMissing = true;
                studio.ERM.EpochRunnerMod.logger.warn("[Loadout] flansmod KSK armour not found -> using vanilla iron. "
                        + "Check the exact registry names if you want KSK.");
            }
            h = new ItemStack(net.minecraft.init.Items.IRON_HELMET);
            c = new ItemStack(net.minecraft.init.Items.IRON_CHESTPLATE);
            l = new ItemStack(net.minecraft.init.Items.IRON_LEGGINGS);
            f = new ItemStack(net.minecraft.init.Items.IRON_BOOTS);
        }
        if (!h.isEmpty()) e.setItemStackToSlot(EntityEquipmentSlot.HEAD,  h);
        if (!c.isEmpty()) e.setItemStackToSlot(EntityEquipmentSlot.CHEST, c);
        if (!l.isEmpty()) e.setItemStackToSlot(EntityEquipmentSlot.LEGS,  l);
        if (!f.isEmpty()) e.setItemStackToSlot(EntityEquipmentSlot.FEET,  f);
        try {
            // setDropChance is EntityLiving-only; e is typed EntityLivingBase. Soldiers/puppets ARE
            // EntityLiving (players/armor-stands aren't, and have no equipment-drop chance anyway).
            if (e instanceof net.minecraft.entity.EntityLiving) {
                net.minecraft.entity.EntityLiving el = (net.minecraft.entity.EntityLiving) e;
                for (EntityEquipmentSlot s : EntityEquipmentSlot.values()) el.setDropChance(s, 0.0F);
            }
        } catch (Throwable ignored) {}
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

    /** A flansmod:uzi kitted with the requested attachments + ammo NBT, or EMPTY if Flan's absent. */
    private static ItemStack makeAttachmentUzi() {
        Item uzi = Item.getByNameOrId("flansmod:uzi");
        if (uzi == null) return ItemStack.EMPTY;
        ItemStack stack = new ItemStack(uzi);
        try {
            NBTTagCompound tag = JsonToNBT.getTagFromJson(
                    "{attachments:{barrel:{},grip:{},generic_0:{},scope:{},stock:{}},ammo:[{}]}");
            stack.setTagCompound(tag);
        } catch (Throwable ignored) {
            // Bad NBT shouldn't deny the soldier its gun -- ship the plain uzi.
        }
        return stack;
    }
}
