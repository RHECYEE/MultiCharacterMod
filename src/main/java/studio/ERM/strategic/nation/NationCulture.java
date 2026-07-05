package studio.ERM.strategic.nation;

import java.util.Random;

/**
 * THE SIX NATION-STATE CULTURES — architecture + naming identity. Each culture maps to the AW2
 * nation packs in the schematic catalog so its buildings instantly read as "someone else":
 * Plains = Empire oak/cobble villages, Desert = Sarkonid sandstone, Northern = Norska longhouses,
 * Mountain = Dwarven stone keeps, Coastal = the coastal packs' docks, Tribal = tents and totems.
 * A rare humorous name can surface in any culture.
 */
public enum NationCulture {

    PLAINS("Plains Kingdom",
            new String[]{"AWNationEmpire", "AWNationEmpireMod"},
            new String[]{"Kingdom of Oakshire", "Birchmoor", "Cobblehold", "Wheatmere", "Hayford",
                    "Millstone", "Redstone Reach", "Riverbrick", "Stonefield", "Villagia", "Oakhelm", "Mossgate"}),
    DESERT("Desert Sultanate",
            new String[]{"AWNationSarkonid"},
            new String[]{"Sandspire", "Red Mesa", "Terracotta Dominion", "Dunewatch", "Cactus Emirate",
                    "Scorpion Sands", "Badlands Union", "Sunstone", "Oasis Reach", "Drywater"}),
    NORTHERN("Northern Kingdom",
            new String[]{"AWNationNorska"},
            new String[]{"Spruceholm", "Frostfjord", "Snowhall", "Icewood", "Pinewatch", "Wolffjord",
                    "North Timber", "Frozen Reach", "Taigard", "White Spruce"}),
    MOUNTAIN("Mountain Kingdom",
            new String[]{"AWNationDwarf"},
            new String[]{"Ironpeak", "Stonehome", "Deepdelve", "Bedrock Hold", "Granite Crown",
                    "Black Pick", "Diamond Reach", "High Crag", "Orewatch", "Cliffhall"}),
    COASTAL("Coastal Republic",
            new String[]{"AWNationNorskaCoastal", "AWNationNoggCoastal", "AWNationEmpireCoastal"},
            new String[]{"Codhaven", "Salmon Bay", "Tidewatch", "Driftwood", "Harborstone", "Seabrick",
                    "Coral Reach", "Anchor Point", "Squidport", "Lighthouse Isle"}),
    TRIBAL("Tribal Confederation",
            new String[]{"AWNationGuildMerchant", "AWNationNogg"},
            new String[]{"Great Plains Confederacy", "Campfire Circle", "Buffalo Ridge", "Smoke Valley",
                    "Birch Camp", "Tall Grass Nation", "Firekeeper Tribe", "Cedar Hollow",
                    "Elk Crossing", "Whispering Pines"});

    /** Rare names that can surface in ANY culture (~5% of rolls). */
    private static final String[] HUMOROUS = {
            "Kingdom of Dirt", "The Great Cobble Empire", "The Order of the Crafting Table",
            "Furnace Republic", "The Torch Union", "Society of Very Square Builders",
            "Kingdom of Stackia", "The Redstone League", "The Block Federation", "Nation of Infinite Chests"};

    public final String displayName;
    /** Schematic-catalog nation packs whose buildings this culture draws from. */
    public final String[] packs;
    public final String[] names;

    NationCulture(String displayName, String[] packs, String[] names) {
        this.displayName = displayName;
        this.packs = packs;
        this.names = names;
    }

    public String rollName(Random rng) {
        if (rng.nextInt(20) == 0) return HUMOROUS[rng.nextInt(HUMOROUS.length)];
        return names[rng.nextInt(names.length)];
    }
}
