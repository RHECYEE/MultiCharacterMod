package studio.ERM.war.BattleManagers.core;

import java.util.ArrayList;
import java.util.List;

/**
 * Small deterministic weighted picker (no java.util.Random allocations).
 */
public final class WeightedPicker<T> {

    private static final class Entry<E> {
        final E value;
        final int weight;
        Entry(E value, int weight) {
            this.value = value;
            this.weight = weight;
        }
    }

    private final List<Entry<T>> entries = new ArrayList<>();
    private int totalWeight = 0;

    public void add(T value, int weight) {
        if (weight <= 0 || value == null) return;
        entries.add(new Entry<>(value, weight));
        totalWeight += weight;
    }

    public T pick(long seed) {
        if (entries.isEmpty() || totalWeight <= 0) return null;

        // Xorshift* style mixing
        long x = seed + 0x9E3779B97F4A7C15L;
        x ^= (x >>> 12);
        x ^= (x << 25);
        x ^= (x >>> 27);
        long r = (x * 2685821657736338717L);

        int roll = (int) (Math.abs(r) % totalWeight);

        int acc = 0;
        for (Entry<T> e : entries) {
            acc += e.weight;
            if (roll < acc) return e.value;
        }
        return entries.get(entries.size() - 1).value;
    }
}
