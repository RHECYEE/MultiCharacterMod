package co.runed.multicharacter;

public class ModConfig {

    public static final WeaponSettings weapons = new WeaponSettings();
    public static final VehicleCombatSettings vehicleCombat = new VehicleCombatSettings();

    /** Comma-separated registry names used to arm spawned pilots/passengers. */
    public static final class WeaponSettings {
        public String pilotWeapons = "minecraft:iron_sword";
        public String passengerWeapons = "minecraft:bow";
    }

    /** Tunable values for AI-driven vehicle weapon behavior. */
    public static final class VehicleCombatSettings {
        public int machineGunFireRate = 4;
        public float mainGunDamage = 30.0F;

        public float modernTankExplosion = 5.0F;
        public float heavyTankExplosion = 4.0F;
        public float mediumTankExplosion = 3.0F;
        public float lightVehicleExplosion = 2.0F;
        public float artilleryExplosion = 3.5F;
    }
}
