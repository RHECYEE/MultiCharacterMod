package studio.ERM.war.air;

/**
 * Helper for airstrike profiles.
 * Provides strike profiles based on aircraft type for AirStrikeController usage.
 */
public class WarAirstrikeHelper {

    public static class StrikeProfile {
        public double altitude = 80.0;
        public double speed = 2.0;
        public EntityGhostAircraft.MissionType mission = EntityGhostAircraft.MissionType.BOMBING_RUN;

        public StrikeProfile() {}

        public StrikeProfile(double altitude, double speed, EntityGhostAircraft.MissionType mission) {
            this.altitude = altitude;
            this.speed = speed;
            this.mission = mission;
        }
    }

    /**
     * Returns a strike profile for a given aircraft type.
     * Falls back to a default bombing run profile if type is unknown.
     */
    public static StrikeProfile getProfile(String aircraftType) {
        if (aircraftType == null) return new StrikeProfile();

        String lower = aircraftType.toLowerCase();

        // Heavy bombers -> high-altitude carpet bombing. (b52 = Modern Warfare's heavy bomber)
        if (lower.contains("bomber") || lower.contains("b17") || lower.contains("b24")
                || lower.contains("b52") || lower.contains("lancaster")) {
            return new StrikeProfile(120.0, 1.5, EntityGhostAircraft.MissionType.BOMBING_RUN);
        }
        // Attack helicopters -> hover and engage. (apacheah64 contains "apache"; ec665 = MW Tiger)
        if (lower.contains("apache") || lower.contains("cobra") || lower.contains("tiger")
                || lower.contains("ec665")) {
            return new StrikeProfile(45.0, 0.8, EntityGhostAircraft.MissionType.HOVER_STRIKE);
        }
        if (lower.contains("hind")) {
            return new StrikeProfile(50.0, 0.7, EntityGhostAircraft.MissionType.ORBIT_ATTACK);
        }
        if (lower.contains("huey") || lower.contains("blackhawk") || lower.contains("chinook") || lower.contains("littlebird")) {
            return new StrikeProfile(55.0, 0.8, EntityGhostAircraft.MissionType.CAS_LOITER);
        }
        // CAS jets (A10/SU25) -> low fast gun runs. STRAFING (not BOMBING_RUN) so they actually
        // fire: the doctrine GUN_RUN pattern gives them 0 bombs, so a bombing run would do nothing.
        if (lower.contains("a10") || lower.contains("su25") || lower.contains("warthog")) {
            return new StrikeProfile(75.0, 1.7, EntityGhostAircraft.MissionType.STRAFING);
        }
        // Fighters + modern strike jets -> strafing passes. (tornado/f22/p51 included so they
        // engage the ground instead of falling through to an empty bombing run)
        if (lower.contains("fighter") || lower.contains("bf109") || lower.contains("spitfire")
                || lower.contains("mustang") || lower.contains("p51") || lower.contains("yak9")
                || lower.contains("zero") || lower.contains("tornado") || lower.contains("f22")) {
            return new StrikeProfile(70.0, 2.5, EntityGhostAircraft.MissionType.STRAFING);
        }
        if (lower.contains("interceptor") || lower.contains("me262") || lower.contains("jet")) {
            return new StrikeProfile(90.0, 3.0, EntityGhostAircraft.MissionType.INTERCEPTION);
        }

        return new StrikeProfile();
    }
}
