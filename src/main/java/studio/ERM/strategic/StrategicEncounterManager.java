package studio.ERM.strategic;

import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.util.text.TextComponentString;
import net.minecraft.util.text.TextFormatting;
import net.minecraft.world.WorldServer;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import net.minecraftforge.fml.common.gameevent.TickEvent;
import studio.ERM.EpochRunnerMod;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

/**
 * LOW-INTENSITY ENCOUNTERS — the strategic map's ambient friction. When two UNLOADED strategic
 * groups pass close on the map, something small may happen: a rival patrol shakes down a nation
 * trader, opposing patrols skirmish, a teamster convoy gets bloodied. Usually nothing happens at
 * all — per the design, these mostly just make the roads feel alive (the player hears rumors in
 * chat now and then; casualties/cargo losses are REAL and persist on the map).
 *
 * Materialized groups are skipped: when the player is present, real entities interact for real.
 */
public final class StrategicEncounterManager {

    private static final int PASS_INTERVAL = 600;       // 30s sweep
    private static final double MEET_RANGE = 40.0;      // "passing on the road"
    private static final long PAIR_COOLDOWN = 6000;     // 5 min before the same pair can meet again
    private static final Random RNG = new Random();

    private StrategicEncounterManager() {}

    private static int tickCounter = 0;
    /** pair-key -> world tick of the last encounter (so overlapping groups don't grind each other). */
    private static final Map<Long, Long> RECENT = new HashMap<>();

    public static void resetTransients() { RECENT.clear(); }

    @SubscribeEvent
    public static void onWorldTick(TickEvent.WorldTickEvent e) {
        if (e.phase != TickEvent.Phase.END || e.side.isClient() || !(e.world instanceof WorldServer)) return;
        if (++tickCounter % PASS_INTERVAL != 0) return;
        WorldServer world = (WorldServer) e.world;
        try {
            sweep(world);
        } catch (Throwable t) {
            EpochRunnerMod.logger.error("[Encounters] sweep failed (guarded)", t);
        }
    }

    private static void sweep(WorldServer world) {
        List<StrategicObject> objs = new ArrayList<>(StrategicMapData.get(world).objects.values());
        if (objs.size() < 2) return;
        long now = world.getTotalWorldTime();
        RECENT.entrySet().removeIf(en -> now - en.getValue() > PAIR_COOLDOWN);

        for (int i = 0; i < objs.size(); i++) {
            StrategicObject a = objs.get(i);
            if (a.materialized || a.strength <= 0) continue;
            for (int j = i + 1; j < objs.size(); j++) {
                StrategicObject b = objs.get(j);
                if (b.materialized || b.strength <= 0) continue;
                double dx = a.x - b.x, dz = a.z - b.z;
                if (dx * dx + dz * dz > MEET_RANGE * MEET_RANGE) continue;
                long key = pairKey(a, b);
                if (RECENT.containsKey(key)) continue;
                RECENT.put(key, now);
                resolveMeeting(world, a, b);
            }
        }
    }

    private static long pairKey(StrategicObject a, StrategicObject b) {
        long ha = a.id.getMostSignificantBits(), hb = b.id.getMostSignificantBits();
        return ha < hb ? ha * 31 + hb : hb * 31 + ha;
    }

    private static boolean hostileToEachOther(StrategicObject a, StrategicObject b) {
        boolean aRival = a.faction == null || a.faction.startsWith("RIVAL");
        boolean bRival = b.faction == null || b.faction.startsWith("RIVAL");
        return aRival != bRival; // rival vs nation/neutral traffic; nations don't fight each other (yet)
    }

    private static void resolveMeeting(WorldServer world, StrategicObject a, StrategicObject b) {
        if (!hostileToEachOther(a, b)) return;    // friendly pass: patrol meets trader, nothing happens
        if (RNG.nextInt(100) >= 35) return;       // most hostile passes ALSO come to nothing (by design)

        StrategicObject rival = (a.faction == null || a.faction.startsWith("RIVAL")) ? a : b;
        StrategicObject other = rival == a ? b : a;
        String rumor;

        if (other instanceof StrategicTrader) {
            // Shakedown: the caravan pays in goods (the cart never restocks) or blood (an escort).
            if (RNG.nextBoolean()) {
                ((StrategicTrader) other).robCargo();
                rumor = "a caravan was robbed on the road";
            } else {
                other.strength = Math.max(0, other.strength - 1);
                rumor = "a caravan lost an escort to raiders";
            }
        } else if (other instanceof StrategicConvoy && "TEAMSTER".equals(((StrategicConvoy) other).purpose)) {
            other.strength = Math.max(0, other.strength - (1 + RNG.nextInt(2)));
            rumor = other.strength <= 0
                    ? "a teamster convoy vanished with its whole load"
                    : "a teamster convoy was ambushed but fought through";
        } else {
            // Patrol-on-patrol skirmish: both sides bleed a little.
            rival.strength = Math.max(0, rival.strength - 1);
            other.strength = Math.max(0, other.strength - 1);
            rumor = "patrols clashed on the frontier";
        }

        EpochRunnerMod.logger.info("[Encounters] " + rival.label() + " met " + other.label() + " -> " + rumor);
        if (RNG.nextInt(3) == 0) { // the player only HEARS about some of it
            for (EntityPlayer p : world.playerEntities) {
                p.sendMessage(new TextComponentString(TextFormatting.DARK_GRAY + "Rumor: " + rumor + "."));
            }
        }
    }
}
