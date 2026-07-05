package studio.ERM.war.air;

import java.util.HashMap;
import java.util.Map;

public class AirDoctrine {

    public enum AircraftRole {
        RECON,           // Early warning, harassment
        FIGHTER,         // Air superiority, strafing
        BOMBER,          // Carpet bombing, area denial
        CAS,             // Close air support, ground attack
        STRIKE,          // Multi-role strike fighter
        ATTACK_HELI,     // Precision helicopter attack
        GUNSHIP,         // Suppression helicopter
        TRANSPORT,       // Troop deployment
        SPECIAL_OPS      // Fast insert/extract
    }

    public enum AttackPattern {
        LAZY_PASS,       // Wide, slow passes (biplanes)
        STRAFE_RUN,      // Fast strafing passes (fighters)
        CARPET_BOMB,     // High altitude bomb run (bombers)
        GUN_RUN,         // Long sustained gun attack (CAS)
        ROCKET_STRAFE,   // Guns + rockets (strike)
        ORBIT_ATTACK,    // Circling attack pattern (helicopters)
        HOVER_STRIKE,    // Hover and engage (attack heli)
        LAND_DEPLOY,     // Land / hover and deploy troops
        FAST_INSERT      // Quick touch-and-go deploy
    }

    public static class AircraftProfile {
        public final String vehicleId;
        public final AircraftRole role;
        public final AttackPattern attackPattern;

        public final int minAltitude;
        public final int maxAltitude;
        public final float speedMultiplier;

        public final int fireRate;
        public final int burstLength;
        public final int passCount;

        public final boolean usesMissiles;
        public final boolean usesRockets;
        public final boolean usesBombs;

        public final int passengerCount;
        public final String era;
        public final int threatLevel;

        public AircraftProfile(String vehicleId, AircraftRole role, AttackPattern pattern,
                               int minAlt, int maxAlt, float speed, int fireRate, int burst,
                               int passes, boolean missiles, boolean rockets, boolean bombs,
                               int passengers, String era, int threat) {
            this.vehicleId = vehicleId;
            this.role = role;
            this.attackPattern = pattern;
            this.minAltitude = minAlt;
            this.maxAltitude = maxAlt;
            this.speedMultiplier = speed;
            this.fireRate = fireRate;
            this.burstLength = burst;
            this.passCount = passes;
            this.usesMissiles = missiles;
            this.usesRockets = rockets;
            this.usesBombs = bombs;
            this.passengerCount = passengers;
            this.era = era;
            this.threatLevel = threat;
        }
    }

    private static final Map<String, AircraftProfile> PROFILES = new HashMap<>();

    static {
        // === WW1 / EARLY AVIATION ===
        PROFILES.put("flansmod:biplane", new AircraftProfile(
                "flansmod:biplane", AircraftRole.RECON, AttackPattern.LAZY_PASS,
                20, 45, 0.85f, 18, 20, 2, false, false, false, 1, "WW1", 1
        ));
        PROFILES.put("flansmod:fokker", new AircraftProfile(
                "flansmod:fokker", AircraftRole.FIGHTER, AttackPattern.STRAFE_RUN,
                35, 60, 1.05f, 10, 18, 4, false, false, false, 1, "WW1", 2
        ));
        PROFILES.put("flansmod:camel", new AircraftProfile(
                "flansmod:camel", AircraftRole.FIGHTER, AttackPattern.STRAFE_RUN,
                35, 60, 1.05f, 10, 18, 4, false, false, false, 1, "WW1", 2
        ));

        // === WW2 ===
        PROFILES.put("flansmod:bf109", new AircraftProfile(
                "flansmod:bf109", AircraftRole.FIGHTER, AttackPattern.STRAFE_RUN,
                55, 85, 1.25f, 8, 14, 6, false, false, false, 1, "WW2", 4
        ));
        PROFILES.put("flansmod:spitfire", new AircraftProfile(
                "flansmod:spitfire", AircraftRole.FIGHTER, AttackPattern.STRAFE_RUN,
                55, 85, 1.25f, 8, 14, 6, false, false, false, 1, "WW2", 4
        ));
        PROFILES.put("flansmod:mustang", new AircraftProfile(
                "flansmod:mustang", AircraftRole.FIGHTER, AttackPattern.STRAFE_RUN,
                60, 90, 1.30f, 8, 14, 6, false, false, false, 1, "WW2", 4
        ));
        PROFILES.put("flansmod:yak9", new AircraftProfile(
                "flansmod:yak9", AircraftRole.FIGHTER, AttackPattern.STRAFE_RUN,
                55, 85, 1.25f, 8, 14, 6, false, false, false, 1, "WW2", 4
        ));
        PROFILES.put("flansmod:zero", new AircraftProfile(
                "flansmod:zero", AircraftRole.FIGHTER, AttackPattern.STRAFE_RUN,
                50, 80, 1.20f, 9, 16, 6, false, false, false, 1, "WW2", 4
        ));

        // Bombers
        PROFILES.put("flansmod:lancaster", new AircraftProfile(
                "flansmod:lancaster", AircraftRole.BOMBER, AttackPattern.CARPET_BOMB,
                110, 150, 0.95f, 0, 0, 1, false, false, true, 4, "WW2", 7
        ));
        // Modern heavy bomber (Modern Warfare pack ShortName "B52").
        PROFILES.put("flansmod:b52", new AircraftProfile(
                "flansmod:b52", AircraftRole.BOMBER, AttackPattern.CARPET_BOMB,
                130, 175, 1.0f, 0, 0, 1, false, false, true, 5, "MODERN", 9
        ));

        // === Modern ===
        PROFILES.put("flansmod:a10", new AircraftProfile(
                "flansmod:a10", AircraftRole.CAS, AttackPattern.GUN_RUN,
                70, 110, 0.95f, 6, 30, 4, false, false, true, 1, "MODERN", 8
        ));
        PROFILES.put("flansmod:su25", new AircraftProfile(
                "flansmod:su25", AircraftRole.CAS, AttackPattern.GUN_RUN,
                80, 120, 1.05f, 6, 30, 4, false, true, true, 1, "MODERN", 8
        ));
        PROFILES.put("flansmod:tornado", new AircraftProfile(
                "flansmod:tornado", AircraftRole.STRIKE, AttackPattern.ROCKET_STRAFE,
                95, 140, 1.35f, 6, 26, 3, false, true, true, 1, "MODERN", 9
        ));
        PROFILES.put("flansmod:f22", new AircraftProfile(
                "flansmod:f22", AircraftRole.FIGHTER, AttackPattern.STRAFE_RUN,
                120, 170, 1.85f, 6, 16, 8, true, false, false, 1, "MODERN", 10
        ));

        // Helicopters
        PROFILES.put("flansmod:apache", new AircraftProfile(
                "flansmod:apache", AircraftRole.ATTACK_HELI, AttackPattern.HOVER_STRIKE,
                25, 60, 0.55f, 4, 30, 999, true, true, false, 2, "MODERN", 9
        ));
        PROFILES.put("flansmod:apacheah64", new AircraftProfile(
                "flansmod:apacheah64", AircraftRole.ATTACK_HELI, AttackPattern.HOVER_STRIKE,
                25, 60, 0.55f, 4, 30, 999, true, true, false, 2, "MODERN", 9
        ));
        PROFILES.put("flansmod:cobra", new AircraftProfile(
                "flansmod:cobra", AircraftRole.ATTACK_HELI, AttackPattern.HOVER_STRIKE,
                25, 60, 0.55f, 5, 28, 999, true, true, false, 2, "MODERN", 8
        ));
        PROFILES.put("flansmod:hind", new AircraftProfile(
                "flansmod:hind", AircraftRole.GUNSHIP, AttackPattern.ORBIT_ATTACK,
                30, 70, 0.50f, 5, 26, 999, true, true, true, 6, "MODERN", 8
        ));
        PROFILES.put("flansmod:tiger", new AircraftProfile(
                "flansmod:tiger", AircraftRole.ATTACK_HELI, AttackPattern.HOVER_STRIKE,
                25, 60, 0.55f, 5, 28, 999, true, true, false, 2, "MODERN", 8
        ));
        // Modern Warfare's Tiger attack helicopter actually has ShortName "EC665"
        // (the file is Tiger.txt but ShortName=EC665), so "tiger" alone never resolves.
        PROFILES.put("flansmod:ec665", new AircraftProfile(
                "flansmod:ec665", AircraftRole.ATTACK_HELI, AttackPattern.HOVER_STRIKE,
                25, 60, 0.55f, 5, 28, 999, true, true, false, 2, "MODERN", 8
        ));

        // Transports
        PROFILES.put("flansmod:chinook", new AircraftProfile(
                "flansmod:chinook", AircraftRole.TRANSPORT, AttackPattern.LAND_DEPLOY,
                40, 80, 0.50f, 0, 0, 0, false, false, false, 20, "MODERN", 6
        ));
        PROFILES.put("flansmod:blackhawk", new AircraftProfile(
                "flansmod:blackhawk", AircraftRole.TRANSPORT, AttackPattern.LAND_DEPLOY,
                35, 70, 0.55f, 0, 0, 0, false, false, false, 10, "MODERN", 6
        ));
        PROFILES.put("flansmod:littlebird", new AircraftProfile(
                "flansmod:littlebird", AircraftRole.SPECIAL_OPS, AttackPattern.FAST_INSERT,
                10, 30, 1.0f, 8, 40, 2, false, true, false, 4, "MODERN", 7
        ));

        // Huey (generic utility) -> treat as transport so summonvehicle uses deploy/orbit behavior cleanly
        PROFILES.put("flansmod:huey", new AircraftProfile(
                "flansmod:huey", AircraftRole.TRANSPORT, AttackPattern.LAND_DEPLOY,
                40, 70, 0.5f, 12, 60, 0, false, false, false, 8, "MODERN", 4
        ));
    }

    /**
     * Get the profile for a specific aircraft.
     */
    public static AircraftProfile getProfile(String vehicleId) {
        // Try exact match first
        if (PROFILES.containsKey(vehicleId)) {
            return PROFILES.get(vehicleId);
        }

        String lowerName = vehicleId.toLowerCase();
        for (Map.Entry<String, AircraftProfile> entry : PROFILES.entrySet()) {
            if (lowerName.contains(entry.getKey().replace("flansmod:", ""))) {
                return entry.getValue();
            }
        }

        // Default fallback
        return new AircraftProfile(
                vehicleId, AircraftRole.RECON, AttackPattern.LAZY_PASS,
                40, 60, 1.0f, 14, 18, 2, false, false, false, 1, "UNKNOWN", 1
        );
    }
}
