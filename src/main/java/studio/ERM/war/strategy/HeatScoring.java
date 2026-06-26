package studio.ERM.war.strategy;

import net.minecraft.block.state.IBlockState;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.util.ResourceLocation;

/**
 * Turns a block / tile-entity into strategic heat by registry-name + TE-class-name matching. This is
 * the deliberately-simple "first version" the design calls for (mod adapters can replace it later).
 *
 * The single most valuable signal is a tile entity the player chose to PROTECT with the protection
 * stick -- that is the player telling us "this is important" -- so protected blocks get a large bonus.
 */
public final class HeatScoring {

    private HeatScoring() {}

    /** Add one block / tile-entity's heat to the chunk. {@code protectedByStick} = under the protector. */
    public static void score(StrategicChunk c, IBlockState state, TileEntity te, boolean protectedByStick) {
        String name = "";
        try {
            ResourceLocation rn = state.getBlock().getRegistryName();
            if (rn != null) name = rn.toString().toLowerCase();
        } catch (Throwable ignored) {}
        String teName = (te != null) ? te.getClass().getSimpleName().toLowerCase() : "";

        // --- The player explicitly protected this: highest-value base signal. ---
        if (protectedByStick) c.structuralHeat += 200;

        boolean matched = false;

        // --- Storage (incl. modded mass storage) ---
        if (contains(teName, "drawer") || contains(name, "drawer")) {
            if (contains(name, "controller")) add(c, "storage", 200); else add(c, "storage", 20); matched = true;
        }
        if (contains(teName, "medrive", "drive", "interface", "storagebus") || contains(name, "drive", "me_")) {
            add(c, "storage", 250); add(c, "machine", 80); matched = true;
        }
        if (contains(teName, "diskdrive") || contains(name, "disk_drive")) { add(c, "storage", 250); matched = true; }
        if (contains(name, "chest") || contains(teName, "chest")) { add(c, "storage", 25); matched = true; }
        if (contains(name, "barrel", "crate") || contains(teName, "barrel")) { add(c, "storage", 20); matched = true; }

        // --- Machine / automation cores ---
        if (contains(teName, "controller") && contains(name, "appliedenergistics", "ae2", "refinedstorage", "rs_")) {
            add(c, "machine", 500); add(c, "storage", 200); matched = true;
        } else if (contains(name, "controller") && contains(name, "refinedstorage")) {
            add(c, "machine", 400); add(c, "storage", 200); matched = true;
        }
        if (contains(name, "furnace") || contains(teName, "furnace")) { add(c, "machine", 15); matched = true; }
        if (contains(name, "machine", "assembler", "processor", "pulverizer", "smelter", "centrifuge",
                "reactor", "turbine", "crusher", "factory")) { add(c, "machine", 100); matched = true; }

        // --- Power ---
        if (contains(name, "generator", "dynamo", "capacitor", "energycell", "battery", "solar", "reactor")) {
            add(c, "power", 150); matched = true;
        }

        // --- Living ---
        if (contains(name, "bed") || contains(teName, "bed")) { add(c, "living", 100); matched = true; }
        if (contains(name, "beacon") || contains(teName, "beacon")) { add(c, "structural", 300); matched = true; }
        if (contains(name, "enchanting_table") || contains(teName, "enchant")) { add(c, "structural", 80); matched = true; }
        if (contains(name, "brewing_stand") || contains(teName, "brewing")) { add(c, "structural", 50); matched = true; }
        if (contains(name, "anvil")) { add(c, "structural", 20); matched = true; }
        if (contains(name, "crafting_table", "workbench")) { add(c, "structural", 10); matched = true; }

        // --- Entrances ---
        if (contains(name, "iron_door")) { add(c, "entrance", 40); matched = true; }
        else if (contains(name, "door")) { add(c, "entrance", 20); matched = true; }
        if (contains(name, "fence_gate", "gate")) { add(c, "entrance", 15); matched = true; }

        // --- Defense ---
        if (contains(name, "turret", "cannon", "sentry")) { add(c, "defense", 200); matched = true; }

        // Any other tile entity in a base is at least weakly structural.
        if (!matched && te != null) c.structuralHeat += 8;
    }

    private static void add(StrategicChunk c, String cat, double v) {
        switch (cat) {
            case "storage":   c.storageHeat += v;   c.structuralHeat += v; break;
            case "machine":   c.machineHeat += v;   c.structuralHeat += v; break;
            case "power":     c.powerHeat += v;     c.structuralHeat += v; break;
            case "living":    c.livingHeat += v;    c.structuralHeat += v; break;
            case "entrance":  c.entranceHeat += v;  c.structuralHeat += v * 0.5; break;
            case "defense":   c.defenseHeat += v;   c.structuralHeat += v; break;
            case "logistics": c.logisticsHeat += v; c.structuralHeat += v; break;
            default:          c.structuralHeat += v; break;
        }
    }

    private static boolean contains(String hay, String... needles) {
        if (hay == null || hay.isEmpty()) return false;
        for (String n : needles) if (hay.contains(n)) return true;
        return false;
    }
}
