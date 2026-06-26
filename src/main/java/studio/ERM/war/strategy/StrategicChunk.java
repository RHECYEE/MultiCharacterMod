package studio.ERM.war.strategy;

/**
 * One chunk's strategic heat profile -- the chunk-scale unit the Siege Director reasons about BEFORE
 * it ever looks at individual blocks. Heat answers "this chunk matters / is probably storage / is
 * defended / is a good breach candidate" cheaply enough for 1.12.2.
 *
 * This is the foundation of {@link WarHeatMap}; see the strategic-heatmap design memory for the full
 * category list, scoring table, decay rules and the director's chunk->core->perimeter->breach flow.
 */
public class StrategicChunk {

    public final int chunkX, chunkZ;

    // Top-level heat buckets.
    public double structuralHeat; // "a base is here" -- ~no decay
    public double activityHeat;   // player behaviour -- moderate decay
    public double combatHeat;     // violence / threat -- slow decay
    public double siegeHeat;      // battlefield damage -- created during an attack

    // Sub-categories (feed the classification).
    public double storageHeat, machineHeat, powerHeat, livingHeat, entranceHeat, defenseHeat, logisticsHeat;

    public long lastScannedTime;
    public Classification classification = Classification.COLD;

    public StrategicChunk(int chunkX, int chunkZ) {
        this.chunkX = chunkX;
        this.chunkZ = chunkZ;
    }

    public double totalHeat() {
        return structuralHeat + activityHeat + combatHeat + siegeHeat;
    }

    public enum Classification {
        COLD, APPROACH, PERIMETER, LIVING, STORAGE, MACHINE, POWER, DEFENSE, CORE, LOGISTICS,
        CONTESTED, BREACHED, OCCUPIED
    }

    /** Derive a strategic role from the heat profile. */
    public void classify() {
        double total = totalHeat();
        if (total < 30) { classification = Classification.COLD; return; }
        if (combatHeat > 250) { classification = Classification.CONTESTED; return; }
        if (siegeHeat > 200) { classification = Classification.BREACHED; return; }

        // CORE = a chunk holding a strong COMBINATION of the things that make a base a base.
        int kinds = (storageHeat > 0 ? 1 : 0) + (machineHeat > 0 ? 1 : 0) + (livingHeat > 0 ? 1 : 0);
        if (storageHeat + machineHeat + livingHeat > 400 && kinds >= 2) {
            classification = Classification.CORE; return;
        }
        if (defenseHeat > 150) { classification = Classification.DEFENSE; return; }

        // Otherwise the dominant sub-category.
        double[] cats = { storageHeat, machineHeat, powerHeat, livingHeat, logisticsHeat };
        Classification[] map = { Classification.STORAGE, Classification.MACHINE, Classification.POWER,
                Classification.LIVING, Classification.LOGISTICS };
        int best = -1; double bestVal = 0;
        for (int i = 0; i < cats.length; i++) if (cats[i] > bestVal) { bestVal = cats[i]; best = i; }
        classification = (best >= 0 && bestVal > 0) ? map[best] : Classification.PERIMETER;
    }

    public long key() { return chunkKey(chunkX, chunkZ); }

    public static long chunkKey(int cx, int cz) {
        return ((long) cx & 0xFFFFFFFFL) | (((long) cz & 0xFFFFFFFFL) << 32);
    }

    @Override
    public String toString() {
        return "[" + chunkX + "," + chunkZ + "] " + classification + " (heat=" + (int) totalHeat()
                + " struct=" + (int) structuralHeat + " stor=" + (int) storageHeat
                + " mach=" + (int) machineHeat + " pow=" + (int) powerHeat
                + " live=" + (int) livingHeat + " def=" + (int) defenseHeat + ")";
    }
}
