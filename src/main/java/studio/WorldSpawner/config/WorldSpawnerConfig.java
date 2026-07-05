package studio.WorldSpawner.config;

public class WorldSpawnerConfig {

    private static final WorldSpawnerConfig INSTANCE = new WorldSpawnerConfig();

    public static WorldSpawnerConfig getInstance() {
        return INSTANCE;
    }

    public static void load() {}

    /** Load spawner settings from disk. Placeholder until config IO is wired up. */
    public void loadFromDisk() {
        load();
    }
}
