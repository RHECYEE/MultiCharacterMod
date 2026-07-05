package studio.ERM.strategic.patrol;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import studio.ERM.EpochRunnerMod;

import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.Reader;
import java.io.Writer;
import java.util.ArrayList;
import java.util.List;

/**
 * PATROL DEFINITIONS (config/Homosapien/patrols.json) — the data half of the generic patrol
 * engine. Every roaming group in the world is one of these rows: wild pre-civilization threats
 * (RIVAL level 0), medieval AW2 patrols, late-game Flan's infantry, nation-state patrols, convoy
 * escorts, scout parties. Modpacks add dinosaurs/sharks/bandits/mutants by adding rows — entity
 * ids that don't resolve at runtime are skipped, never fatal (the output-table doctrine).
 */
public final class PatrolConfig {

    private static final String CONFIG_FILE = "Homosapien/patrols.json";
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    public static class PatrolDef {
        public String name = "";
        /** What this row is for — shown in logs only. */
        public String note = "";
        /** "land" | "water" | "air" — decides spawn-spot validation + wander style. */
        public String type = "land";
        /** Entity registry names; repeats raise their share of the group ("modid:name"). */
        public List<String> entities = new ArrayList<>();
        public int groupMin = 2, groupMax = 4;
        /** Ticks between spawn ATTEMPTS of this def (per world). */
        public int spawnIntervalTicks = 3600;
        /** Concurrent live patrols of this def. */
        public int maxActive = 2;
        /** Spawn ring around a random player: never closer than min, never past max. */
        public int minPlayerDist = 48, maxPlayerDist = 160;
        /** Lowercase biome-name substrings; empty = any biome. */
        public List<String> biomeContains = new ArrayList<>();
        /** Dimension ids; empty = overworld only (0). */
        public List<Integer> dimensions = new ArrayList<>();
        /** RIVAL-level gate: level 0 rows are the WILD pre-civilization threats. */
        public int rivalLevelMin = 0, rivalLevelMax = 99;
        public int weight = 1;
    }

    public static class ConfigData {
        public List<PatrolDef> patrols = defaults();
        /** Hard cap on live patrols per world across ALL defs. */
        public int globalMaxActive = 8;
        /** Members farther than this x maxPlayerDist from every player despawn (cleanup). */
        public double despawnDistanceFactor = 2.5;

        void sanitize() {
            if (patrols == null) patrols = defaults();
            patrols.removeIf(p -> p == null || p.entities == null || p.entities.isEmpty());
            for (PatrolDef p : patrols) {
                if (p.type == null) p.type = "land";
                if (p.groupMin < 1) p.groupMin = 1;
                if (p.groupMax < p.groupMin) p.groupMax = p.groupMin;
                if (p.spawnIntervalTicks < 200) p.spawnIntervalTicks = 200;
                if (p.maxActive < 1) p.maxActive = 1;
                if (p.minPlayerDist < 16) p.minPlayerDist = 16;
                if (p.maxPlayerDist <= p.minPlayerDist) p.maxPlayerDist = p.minPlayerDist + 32;
                if (p.biomeContains == null) p.biomeContains = new ArrayList<>();
                if (p.dimensions == null) p.dimensions = new ArrayList<>();
                if (p.weight < 1) p.weight = 1;
            }
            if (globalMaxActive < 1) globalMaxActive = 8;
            if (despawnDistanceFactor < 1) despawnDistanceFactor = 2.5;
        }
    }

    public static ConfigData data = new ConfigData();

    private PatrolConfig() {}

    /** LEVEL 0 wild defaults + gated examples for AW2/Flan rows (skipped if the mods differ). */
    private static List<PatrolDef> defaults() {
        List<PatrolDef> d = new ArrayList<>();

        PatrolDef bandits = new PatrolDef();
        bandits.name = "wild_bandits";
        bandits.note = "Level 0 pre-civilization raiders. Replace entities with dinosaurs/mutants per pack.";
        bandits.entities.add("minecraft:zombie");
        bandits.entities.add("minecraft:zombie");
        bandits.entities.add("minecraft:skeleton");
        bandits.groupMin = 3; bandits.groupMax = 5;
        bandits.rivalLevelMax = 2;
        d.add(bandits);

        PatrolDef wolves = new PatrolDef();
        wolves.name = "wild_wolfpack";
        wolves.note = "Roaming wildlife.";
        wolves.entities.add("minecraft:wolf");
        wolves.groupMin = 3; wolves.groupMax = 6;
        wolves.biomeContains.add("forest");
        wolves.biomeContains.add("taiga");
        wolves.rivalLevelMax = 99;
        wolves.spawnIntervalTicks = 6000;
        d.add(wolves);

        PatrolDef sharks = new PatrolDef();
        sharks.name = "wild_sharks";
        sharks.note = "Water threat — swap for shark mobs in packs that have them.";
        sharks.type = "water";
        sharks.entities.add("minecraft:guardian");
        sharks.groupMin = 2; sharks.groupMax = 3;
        sharks.biomeContains.add("ocean");
        sharks.rivalLevelMax = 99;
        sharks.spawnIntervalTicks = 6000;
        d.add(sharks);

        PatrolDef aw2 = new PatrolDef();
        aw2.name = "aw2_soldier_patrol";
        aw2.note = "Medieval formation once civilization begins — fix the ids to your AW2 npc registry names.";
        aw2.entities.add("ancientwarfarenpc:bandit_soldier");
        aw2.entities.add("ancientwarfarenpc:bandit_soldier");
        aw2.entities.add("ancientwarfarenpc:bandit_cavalry");
        aw2.groupMin = 4; aw2.groupMax = 6;
        aw2.rivalLevelMin = 1; aw2.rivalLevelMax = 5;
        d.add(aw2);

        PatrolDef nation = new PatrolDef();
        nation.name = "nation_guard_patrol";
        nation.note = "Nation-state guards dispatched from their settlements (spawnAt-anchored). "
                + "Swap villagers for your AW2 guard npc ids for armed patrols.";
        nation.entities.add("minecraft:villager");
        nation.entities.add("minecraft:villager");
        nation.groupMin = 2; nation.groupMax = 4;
        nation.maxActive = 4;
        nation.rivalLevelMin = 0;
        d.add(nation);

        PatrolDef flans = new PatrolDef();
        flans.name = "flans_infantry_patrol";
        flans.note = "Modern infantry at high tech — fix the ids to your Flan's content-pack names.";
        flans.entities.add("flansmod:soldier");
        flans.groupMin = 3; flans.groupMax = 5;
        flans.rivalLevelMin = 6;
        d.add(flans);

        return d;
    }

    public static void load(File configDir) {
        File file = new File(configDir, CONFIG_FILE);
        File parent = file.getParentFile();
        if (parent != null && !parent.exists()) parent.mkdirs();
        if (!file.exists()) {
            data = new ConfigData();
            save(configDir);
            logInfo("[PATROLS] Generated " + CONFIG_FILE);
            return;
        }
        try (Reader reader = new FileReader(file)) {
            ConfigData loaded = GSON.fromJson(reader, ConfigData.class);
            if (loaded == null) {
                data = new ConfigData();
                save(configDir);
            } else {
                loaded.sanitize();
                data = loaded;
                logInfo("[PATROLS] Loaded " + CONFIG_FILE + " (" + data.patrols.size() + " defs)");
            }
        } catch (Exception e) {
            data = new ConfigData();
            save(configDir);
            logInfo("[PATROLS] Failed to load " + CONFIG_FILE + " — regenerated defaults");
        }
    }

    public static void save(File configDir) {
        File file = new File(configDir, CONFIG_FILE);
        try (Writer writer = new FileWriter(file)) {
            GSON.toJson(data, writer);
        } catch (Exception ignored) {}
    }

    private static void logInfo(String msg) {
        if (EpochRunnerMod.logger != null) EpochRunnerMod.logger.info(msg);
    }
}
