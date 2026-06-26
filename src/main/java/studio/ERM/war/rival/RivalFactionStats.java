package studio.ERM.war.rival;

import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;
import studio.ERM.EpochRunnerMod;

import java.util.*;

/**
 * RIVAL FACTION STATS
 *
 * The three authoritative stats for rival civilizations:
 * - Population: manpower + bodies on the map
 * - Industry: logistics + hardware (gates vehicles, not NPC count)
 * - Morale: will to fight (most dynamic stat)
 *
 * These stats are consumed by battles, affected by structure destruction,
 * and determine the rival's behavior and capabilities.
 */
public class RivalFactionStats {

    // ===== STAT CAPS =====
    public static final int MAX_POPULATION = 10000;
    public static final int MAX_INDUSTRY = 10000;
    public static final int MAX_MORALE = 100;

    // ===== BASE VALUES PER LEVEL =====
    private static final int[] BASE_POPULATION = {100, 250, 500, 800, 1200, 1800, 2500, 3500, 5000, 7500};
    private static final int[] BASE_INDUSTRY = {50, 150, 350, 600, 1000, 1500, 2200, 3200, 4500, 6500};

    // ===== CURRENT STATS =====
    private int population;
    private int maxPopulation;
    private int industry;
    private int maxIndustry;
    private int morale;  // 0-100

    private int level = 1;
    private String factionId;

    // Recovery tracking
    private long lastRecoveryTick = 0;
    private static final int RECOVERY_INTERVAL = 1200; // 1 minute

    // ===== STRUCTURE CONTRIBUTION TRACKING =====
    private final Map<StructureType, Integer> structureCounts = new EnumMap<>(StructureType.class);
    /**
     * No-arg constructor for compatibility.
     */
    public RivalFactionStats() {
        this("unknown");
    }
    /**
     * Structure types and their stat effects
     */
    public enum StructureType {
        SKYSCRAPER(2, 1, 2),       // Pop++, Industry+, Morale++
        COOLING_TOWER(1, 3, 1),    // Pop+, Industry+++, Morale+
        FACTORY(1, 3, 1),          // Pop+, Industry+++, Morale+
        SILO_STORAGE(1, 2, 2),     // Pop+, Industry++, Morale++
        ANTENNA_RADAR(0, 1, 1),    // Pop-, Industry+, Morale+
        HOUSING(2, 0, 1),          // Pop++, Industry-, Morale+
        FARM(1, 1, 1),             // Pop+, Industry+, Morale+
        MILITARY_FORT(0, 1, 2),    // Pop-, Industry+, Morale++
        BARRACKS(1, 2, 2),         // Pop+, Industry++, Morale++
        WATCHTOWER(0, 1, 1),       // Pop-, Industry+, Morale+
        TOWN(2, 1, 1),             // Pop++, Industry+, Morale+
        OUTPOST(0, 1, 1);          // Pop-, Industry+, Morale+

        public final int populationBonus;
        public final int industryBonus;
        public final int moraleBonus;

        StructureType(int pop, int ind, int mor) {
            this.populationBonus = pop;
            this.industryBonus = ind;
            this.moraleBonus = mor;
        }
    }
    private int threatLevel = 0;

    /**
     * Get current population.
     */
    public int getPopulation() {
        return this.population;
    }

    /**
     * Set population value.
     */
    public void setPopulation(int pop) {
        this.population = Math.max(0, pop);
    }

    /**
     * Get current threat level (0-4).
     */
    public int getThreatLevel() {
        return this.threatLevel;
    }

    /**
     * Set threat level.
     */
    public void setThreatLevel(int level) {
        this.threatLevel = Math.max(0, Math.min(4, level));
    }

    /**
     * Destruction effects (negative, applied when structure destroyed)
     */
    public static class DestructionEffect {
        public final int populationLoss;
        public final int industryLoss;
        public final int moraleLoss;

        public DestructionEffect(int pop, int ind, int mor) {
            this.populationLoss = pop;
            this.industryLoss = ind;
            this.moraleLoss = mor;
        }
    }

    private static final Map<StructureType, DestructionEffect> DESTRUCTION_EFFECTS = new EnumMap<>(StructureType.class);
    static {
        DESTRUCTION_EFFECTS.put(StructureType.SKYSCRAPER, new DestructionEffect(150, 50, 8));
        DESTRUCTION_EFFECTS.put(StructureType.COOLING_TOWER, new DestructionEffect(30, 200, 6));
        DESTRUCTION_EFFECTS.put(StructureType.FACTORY, new DestructionEffect(50, 200, 4));
        DESTRUCTION_EFFECTS.put(StructureType.SILO_STORAGE, new DestructionEffect(80, 100, 6));
        DESTRUCTION_EFFECTS.put(StructureType.ANTENNA_RADAR, new DestructionEffect(0, 50, 3));
        DESTRUCTION_EFFECTS.put(StructureType.HOUSING, new DestructionEffect(100, 20, 5));
        DESTRUCTION_EFFECTS.put(StructureType.FARM, new DestructionEffect(40, 40, 3));
        DESTRUCTION_EFFECTS.put(StructureType.MILITARY_FORT, new DestructionEffect(20, 80, 7));
        DESTRUCTION_EFFECTS.put(StructureType.BARRACKS, new DestructionEffect(60, 100, 8));
        DESTRUCTION_EFFECTS.put(StructureType.WATCHTOWER, new DestructionEffect(10, 30, 4));
    }

    // ===== CONSTRUCTOR =====

    public RivalFactionStats(String factionId) {
        this.factionId = factionId;
        this.level = 1;
        initializeForLevel(1);
    }

    public void initializeForLevel(int level) {
        this.level = Math.max(1, Math.min(10, level));
        int idx = this.level - 1;

        this.maxPopulation = BASE_POPULATION[idx];
        this.maxIndustry = BASE_INDUSTRY[idx];
        this.population = maxPopulation;
        this.industry = maxIndustry;
        this.morale = 75; // Start with good morale

        structureCounts.clear();
    }

    // ===== STRUCTURE MANAGEMENT =====

    /**
     * Called when a structure is built/added to the faction
     */
    public void onStructureBuilt(StructureType type) {
        structureCounts.merge(type, 1, Integer::sum);
        recalculateMaxStats();
    }

    /**
     * Called when a structure is destroyed
     */
    public void onStructureDestroyed(StructureType type) {
        int count = structureCounts.getOrDefault(type, 0);
        if (count > 0) {
            structureCounts.put(type, count - 1);
        }

        DestructionEffect effect = DESTRUCTION_EFFECTS.get(type);
        if (effect != null) {
            population = Math.max(0, population - effect.populationLoss);
            industry = Math.max(0, industry - effect.industryLoss);
            morale = Math.max(0, morale - effect.moraleLoss);
            recalculateMaxStats();

            EpochRunnerMod.logger.info("[FACTION] " + factionId + " lost " + type +
                    " - Pop:" + effect.populationLoss + " Ind:" + effect.industryLoss +
                    " Mor:" + effect.moraleLoss);
        }
    }

    /**
     * Recalculate max stats based on structures
     */
    private void recalculateMaxStats() {
        int idx = Math.max(0, Math.min(9, level - 1));
        int basePop = BASE_POPULATION[idx];
        int baseInd = BASE_INDUSTRY[idx];

        int bonusPop = 0;
        int bonusInd = 0;
        int bonusMorale = 0;

        for (Map.Entry<StructureType, Integer> entry : structureCounts.entrySet()) {
            StructureType type = entry.getKey();
            int count = entry.getValue();

            bonusPop += type.populationBonus * count * 25;
            bonusInd += type.industryBonus * count * 30;
            bonusMorale += type.moraleBonus * count;
        }

        maxPopulation = Math.min(MAX_POPULATION, basePop + bonusPop);
        maxIndustry = Math.min(MAX_INDUSTRY, baseInd + bonusInd);

        population = Math.min(population, maxPopulation);
        industry = Math.min(industry, maxIndustry);
    }

    // ===== BATTLE CONSUMPTION =====

    /**
     * Consume stats for a battle
     * @param casualties Number of units lost
     * @param vehiclesLost Number of vehicles destroyed
     * @param wasDefeat Did the faction lose?
     */
    public void consumeForBattle(int casualties, int vehiclesLost, boolean wasDefeat) {
        population = Math.max(0, population - casualties);
        industry = Math.max(0, industry - (vehiclesLost * 100));

        if (wasDefeat) {
            morale = Math.max(0, morale - 15);
        } else {
            morale = Math.min(100, morale + 5);
        }

        EpochRunnerMod.logger.info("[FACTION] " + factionId + " battle consumption - " +
                "Casualties:" + casualties + " Vehicles:" + vehiclesLost + " Defeat:" + wasDefeat);
    }

    /**
     * Consume stats for a raid (boolean success version)
     */
    public void consumeForRaid(boolean wasSuccessful, int lootValue) {
        if (wasSuccessful) {
            morale = Math.min(100, morale + 3);
            industry = Math.min(maxIndustry, industry + lootValue / 10);
        } else {
            morale = Math.max(0, morale - 5);
        }
    }

    /**
     * Consume stats for a raid (casualties version - used by RivalCityManager)
     */
    public void consumeForRaid(int casualties, int vehiclesLost) {
        population = Math.max(0, population - casualties);
        industry = Math.max(0, industry - (vehiclesLost * 50));
        // Raiding is offensive, slight morale boost if committing
        morale = Math.min(100, morale + 2);
    }

    // ===== TICK & RECOVERY =====

    /**
     * Called every tick to handle recovery (World version)
     */
    public void tick(World world) {
        updateRecovery(world.getTotalWorldTime());
    }

    /**
     * Called periodically to handle recovery (tick version - for RivalCityManager)
     */
    public void updateRecovery(long currentTick) {
        if (currentTick - lastRecoveryTick >= RECOVERY_INTERVAL) {
            lastRecoveryTick = currentTick;

            // Population recovers slowly
            if (population < maxPopulation) {
                int recoveryRate = getPopulationRecoveryRate();
                population = Math.min(maxPopulation, population + recoveryRate);
            }

            // Industry recovers faster
            if (industry < maxIndustry) {
                int recoveryRate = getIndustryRecoveryRate();
                industry = Math.min(maxIndustry, industry + recoveryRate);
            }

            // Morale naturally trends toward 60 (neutral)
            if (morale < 60) {
                morale = Math.min(60, morale + 1);
            } else if (morale > 80 && getTotalStructureCount() < level * 3) {
                morale = Math.max(60, morale - 1);
            }
        }
    }

    private int getPopulationRecoveryRate() {
        int housingCount = structureCounts.getOrDefault(StructureType.HOUSING, 0);
        int farmCount = structureCounts.getOrDefault(StructureType.FARM, 0);

        int baseRate = 5 + (housingCount * 2) + (farmCount);
        float moraleMultiplier = morale / 100f;

        return (int)(baseRate * moraleMultiplier);
    }

    private int getIndustryRecoveryRate() {
        int factoryCount = structureCounts.getOrDefault(StructureType.FACTORY, 0);
        int coolingCount = structureCounts.getOrDefault(StructureType.COOLING_TOWER, 0);

        int baseRate = 10 + (factoryCount * 5) + (coolingCount * 3);
        float populationMultiplier = (float)population / maxPopulation;

        return (int)(baseRate * populationMultiplier);
    }

    private int getTotalStructureCount() {
        return structureCounts.values().stream().mapToInt(Integer::intValue).sum();
    }

    // ===== CAPABILITY CHECKS =====

    /**
     * Get the vehicle tier this faction can field
     * Industry gates vehicles, not NPC count
     */
    public int getVehicleTier() {
        if (industry < 100) return 0;
        if (industry < 300) return 1;
        if (industry < 600) return 2;
        if (industry < 1000) return 3;
        if (industry < 1500) return 4;
        if (industry < 2200) return 5;
        if (industry < 3200) return 6;
        if (industry < 4500) return 7;
        if (industry < 6000) return 8;
        return 9;
    }

    
    /**
     * Industry tier used for growth scaling and template density decisions.
     * Derived from vehicle tier to avoid duplicated threshold logic.
     * Returns 1..10.
     */
    public int getIndustryTier() {
        return getVehicleTier() + 1;
    }

/**
     * Get max active NPCs for battles
     */
    public int getMaxBattleNPCs() {
        return Math.max(10, population / 20);
    }

    /**
     * Get reinforcement wave size
     */
    public int getReinforcementWaveSize() {
        return Math.max(3, population / 50);
    }

    /**
     * Get reinforcement delay in ticks
     * Low industry = slower waves
     */
    public int getReinforcementDelay() {
        int baseDelay = 400; // 20 seconds
        float industryMultiplier = 1.0f + (1.0f - ((float)industry / maxIndustry));
        return (int)(baseDelay * industryMultiplier);
    }

    /**
     * Check if faction will commit vehicles to battle
     * High morale + high industry = vehicles
     */
    public boolean willCommitVehicles() {
        return morale >= 50 && industry >= 500;
    }

    /**
     * Check if faction can initiate battles
     */
    public boolean canInitiateBattle() {
        return population > maxPopulation * 0.25 && morale >= 30;
    }

    /**
     * Check if faction should seek peace
     */
    public boolean shouldSeekPeace() {
        return morale < 20 || population < maxPopulation * 0.15;
    }

    /**
     * Get aggression modifier based on morale
     */
    public float getAggressionModifier() {
        if (morale >= 80) return 1.5f;
        if (morale >= 60) return 1.2f;
        if (morale >= 40) return 1.0f;
        if (morale >= 20) return 0.7f;
        return 0.4f;
    }

    /**
     * Get retreat chance for units
     */
    public float getRetreatChance() {
        if (morale >= 80) return 0.05f;
        if (morale >= 60) return 0.10f;
        if (morale >= 40) return 0.20f;
        if (morale >= 20) return 0.40f;
        return 0.70f;
    }

    // ===== LEVEL UP =====

    public void levelUp() {
        if (level >= 10) return;
        level++;

        int idx = level - 1;
        int oldMaxPop = maxPopulation;
        int oldMaxInd = maxIndustry;

        maxPopulation = BASE_POPULATION[idx];
        maxIndustry = BASE_INDUSTRY[idx];

        // Add bonus from level up
        population = Math.min(maxPopulation, population + (maxPopulation - oldMaxPop) / 2);
        industry = Math.min(maxIndustry, industry + (maxIndustry - oldMaxInd) / 2);
        morale = Math.min(100, morale + 10);

        recalculateMaxStats();

        EpochRunnerMod.logger.info("[FACTION] " + factionId + " leveled up to " + level);
    }

    // ===== NBT =====

    public NBTTagCompound writeToNBT(NBTTagCompound nbt) {
        nbt.setString("factionId", factionId);
        nbt.setInteger("level", level);
        nbt.setInteger("population", population);
        nbt.setInteger("maxPopulation", maxPopulation);
        nbt.setInteger("industry", industry);
        nbt.setInteger("maxIndustry", maxIndustry);
        nbt.setInteger("morale", morale);
        nbt.setLong("lastRecoveryTick", lastRecoveryTick);
        nbt.setInteger("threatLevel", threatLevel);
        NBTTagCompound structures = new NBTTagCompound();
        for (Map.Entry<StructureType, Integer> entry : structureCounts.entrySet()) {
            structures.setInteger(entry.getKey().name(), entry.getValue());
        }
        nbt.setTag("structures", structures);

        return nbt;
    }

    public void readFromNBT(NBTTagCompound nbt) {
        factionId = nbt.getString("factionId");
        level = nbt.getInteger("level");
        population = nbt.getInteger("population");
        maxPopulation = nbt.getInteger("maxPopulation");
        industry = nbt.getInteger("industry");
        maxIndustry = nbt.getInteger("maxIndustry");
        morale = nbt.getInteger("morale");
        lastRecoveryTick = nbt.getLong("lastRecoveryTick");

        // Fix: ternary must return a value, not a statement
        threatLevel = nbt.hasKey("threatLevel")
                ? nbt.getInteger("threatLevel")
                : 0; // or whatever your intended default is

        // This must be its own statement
        structureCounts.clear();

        if (nbt.hasKey("structures")) {
            NBTTagCompound structures = nbt.getCompoundTag("structures");
            for (StructureType type : StructureType.values()) {
                if (structures.hasKey(type.name())) {
                    structureCounts.put(type, structures.getInteger(type.name()));
                }
            }
        }
    }


    // ===== GETTERS =====

    public String getFactionId() { return factionId; }
    public int getLevel() { return level; }
    public int getMaxPopulation() { return maxPopulation; }
    public int getIndustry() { return industry; }
    public int getMaxIndustry() { return maxIndustry; }
    public int getMorale() { return morale; }

    public float getPopulationPercent() { return (float)population / maxPopulation; }
    public float getIndustryPercent() { return (float)industry / maxIndustry; }

    public int getStructureCount(StructureType type) {
        return structureCounts.getOrDefault(type, 0);
    }

    /**
     * Get status summary for display
     */
    public String[] getStatusLines() {
        return new String[] {
                "§e=== " + factionId + " (Level " + level + ") ===",
                "§cPopulation: §f" + population + "/" + maxPopulation + " (" +
                        String.format("%.0f", getPopulationPercent() * 100) + "%)",
                "§6Industry: §f" + industry + "/" + maxIndustry + " (" +
                        String.format("%.0f", getIndustryPercent() * 100) + "%)",
                "§aMorale: §f" + morale + "%",
                "§7Vehicle Tier: §f" + getVehicleTier(),
                "§7Can Battle: " + (canInitiateBattle() ? "§aYes" : "§cNo"),
                "§7Seeking Peace: " + (shouldSeekPeace() ? "§aYes" : "§cNo")
        };
    }
}