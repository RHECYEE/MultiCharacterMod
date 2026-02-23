package studio.ERM.war.BattleManagers.cards;

/**
 * A slot payload is what a puppet becomes when released.
 * Implementations are data-driven strings so the system can target many mods:
 *
 * - "aw2:soldier"
 * - "aw2:archer"
 * - "aw2:elite"
 * - "aw2:leader"
 * - "flans:vehicle:bf109"
 */
public class SlotPayload {

    private final String id;

    public SlotPayload(String id) {
        this.id = id == null ? "" : id.trim();
    }

    public String getId() {
        return id;
    }

    public boolean isEmpty() {
        return id.isEmpty();
    }

    @Override
    public String toString() {
        return id;
    }
}
