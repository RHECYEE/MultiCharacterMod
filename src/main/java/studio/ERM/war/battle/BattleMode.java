package studio.ERM.war.battle;

/**
 * Available battle modes/game types
 */
public enum BattleMode {
    DEBUG("debug", "Debug Circle", "§7Spawns a single orbiting formation for testing"),
    ASSAULT("assault", "Assault", "§cWaves of enemies assault your position"),
    DEFENSE("defense", "Defense", "§bDefend a point from enemy waves"),
    SKIRMISH("skirmish", "Skirmish", "§eQuick encounter - eliminate all enemies"),
    SIEGE("siege", "Siege", "§6Prolonged battle with reinforcements");

    public final String id;
    public final String displayName;
    public final String description;

    BattleMode(String id, String displayName, String description) {
        this.id = id;
        this.displayName = displayName;
        this.description = description;
    }

    public static BattleMode fromId(String id) {
        if (id == null) return null;
        for (BattleMode mode : values()) {
            if (mode.id.equalsIgnoreCase(id)) {
                return mode;
            }
        }
        return null;
    }

    public static String getAllModeIds() {
        StringBuilder sb = new StringBuilder();
        for (BattleMode mode : values()) {
            if (sb.length() > 0) sb.append(", ");
            sb.append(mode.id);
        }
        return sb.toString();
    }
}