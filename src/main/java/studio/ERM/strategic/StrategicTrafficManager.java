package studio.ERM.strategic;

import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.WorldServer;
import studio.ERM.EpochRunnerMod;
import studio.ERM.war.rival.RivalCityManager;
import studio.ERM.war.rival.RivalCityState;

import java.util.HashSet;
import java.util.Set;

/**
 * PHASE 2 — STRATEGIC TRAFFIC GENERATION. Every civilization continuously generates civilian and
 * military movement; the player only ever observes a small part of it.
 *
 * Each rival city keeps a level-scaled quota of live traffic on the strategic map:
 *   PATROLS  — soldier circuits ringing the city (1 + level/3);
 *   TRADERS  — caravans ping-ponging trade routes out to the frontier (1 + level/4).
 * The quota is re-checked periodically (config traffic.ensureIntervalSeconds) and topped up ONE object
 * per type per check, so traffic ramps in gently instead of popping in as a fleet. Destroyed traffic
 * (a wiped patrol, a murdered merchant) stays destroyed until the city "raises" a replacement at the
 * next check — raiding routes has a visible, lasting effect.
 *
 * Anchoring: the nearest rival city to each player (bounded work; traffic only matters where it can
 * ever be seen, and unloaded objects far from everyone still tick from previous visits).
 */
public final class StrategicTrafficManager {

    private StrategicTrafficManager() {}

    public static void ensure(WorldServer world) {
        if (!studio.ERM.war.config.WarLevelsConfig.trafficEnabled()) return;
        StrategicMapData data = StrategicMapData.get(world);
        Set<String> done = new HashSet<>();

        for (EntityPlayer p : world.playerEntities) {
            if (p == null || p.isDead) continue;
            RivalCityState city;
            try { city = RivalCityManager.getNearestCity(world, p.getPosition()); }
            catch (Throwable t) { continue; }
            if (city == null || city.center == null) continue;
            BlockPos c = city.center;
            if (p.getDistanceSq(c) > 700 * 700) continue; // too far to ever matter
            String key = "city:" + (c.getX() >> 4) + "," + (c.getZ() >> 4);
            if (!done.add(key)) continue; // one top-up per city per pass

            int lvl = Math.max(1, city.level);
            double density = studio.ERM.war.config.WarLevelsConfig.trafficDensity();
            int patrolCap = Math.max(0, (int) Math.round((1 + lvl / 3.0) * density));
            int traderCap = Math.max(0, (int) Math.round((1 + lvl / 4.0) * density));

            int patrols = 0, traders = 0;
            for (StrategicObject o : data.objects.values()) {
                if (!key.equals(o.homeKey)) continue;
                if (o instanceof StrategicPatrol) patrols++;
                else if (o instanceof StrategicTrader) traders++;
            }

            if (patrols < patrolCap) { data.add(makePatrol(world, c, lvl, key)); logRaise(world, "patrol", key, lvl); }
            if (traders < traderCap) { data.add(makeTrader(world, c, lvl, key)); logRaise(world, "trader", key, lvl); }
        }
    }

    /** A soldier circuit ringing the city (radius varies per patrol so routes don't overlap). */
    private static StrategicPatrol makePatrol(WorldServer world, BlockPos center, int lvl, String key) {
        StrategicPatrol p = new StrategicPatrol();
        p.homeKey = key;
        p.warLevel = lvl;
        p.strength = Math.min(8, 3 + lvl / 2);
        int r = 40 + world.rand.nextInt(28);
        double a0 = world.rand.nextDouble() * Math.PI * 2;
        for (int i = 0; i < 8; i++) {
            double a = a0 + i * (Math.PI * 2 / 8);
            p.route.add(new BlockPos(center.getX() + (int) Math.round(Math.cos(a) * r), 0,
                    center.getZ() + (int) Math.round(Math.sin(a) * r)));
        }
        // Enter the circuit at a random point so freshly-raised patrols aren't all synchronized.
        p.routeIndex = world.rand.nextInt(p.route.size());
        BlockPos start = p.route.get(p.routeIndex);
        p.x = start.getX() + 0.5;
        p.z = start.getZ() + 0.5;
        return p;
    }

    /** A caravan ping-ponging a trade route from the city gate out to a frontier point. */
    private static StrategicTrader makeTrader(WorldServer world, BlockPos center, int lvl, String key) {
        StrategicTrader t = new StrategicTrader();
        t.homeKey = key;
        t.level = lvl;
        t.escorts = (lvl >= 3) ? 2 : 0;
        double a = world.rand.nextDouble() * Math.PI * 2;
        int near = 24 + world.rand.nextInt(12);
        int far = 160 + world.rand.nextInt(90);
        t.route.add(new BlockPos(center.getX() + (int) Math.round(Math.cos(a) * near), 0,
                center.getZ() + (int) Math.round(Math.sin(a) * near)));
        t.route.add(new BlockPos(center.getX() + (int) Math.round(Math.cos(a) * far), 0,
                center.getZ() + (int) Math.round(Math.sin(a) * far)));
        BlockPos start = t.route.get(0);
        t.x = start.getX() + 0.5;
        t.z = start.getZ() + 0.5;
        return t;
    }

    private static void logRaise(WorldServer world, String kind, String key, int lvl) {
        EpochRunnerMod.logger.info("[Strategic] traffic raised: " + kind + " (L" + lvl + ") for " + key);
    }
}
