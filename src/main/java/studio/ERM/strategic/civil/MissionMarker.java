package studio.ERM.strategic.civil;

import net.minecraft.util.math.BlockPos;
import net.minecraft.world.WorldServer;
import studio.ERM.strategic.StrategicObject;

/**
 * A pure MAP ICON for an in-flight strategic mission: it travels from the settlement out to the
 * objective and back while the mission runs, then is removed when the expedition returns (owned by
 * {@link StrategicMissionManager}). It NEVER materializes into entities — the escorts are abstract;
 * this is just the "expedition en route" dot the player watches cross the strategic map. Transient by
 * design ({@link studio.ERM.strategic.StrategicMapData} skips saving it, so a restart cancels it in
 * lock-step with the mission timer).
 */
public class MissionMarker extends StrategicObject {

    public String missionName = "Expedition";

    public MissionMarker() {
        this.faction = "PLAYER";
        this.loopRoute = false; // origin -> objective -> home, then hold (removed on return)
        this.speed = 3.2;
        this.strength = 1;
    }

    @Override public String typeId() { return "mission"; }
    @Override public String label() { return missionName; }

    // Always a map marker: an expedition icon must stay on the map even when the player is nearby,
    // so materialize() is a deliberate no-op (never flips to loaded / never spawns entities).
    @Override public void materialize(WorldServer world) { /* no-op */ }
    @Override protected void spawnEntities(WorldServer world, BlockPos at, float yawDeg) { }
    @Override public void driveLoaded(WorldServer world) { }
}
