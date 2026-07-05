package studio.ERM.war.sound;

import net.minecraft.block.material.Material;
import net.minecraft.util.ResourceLocation;
import net.minecraft.util.SoundCategory;
import net.minecraft.util.SoundEvent;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;
import net.minecraftforge.common.DimensionManager;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import net.minecraftforge.fml.common.gameevent.TickEvent;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Random;

/**
 * THE MODERN-WAR SOUNDBOARD: modern weapon audio built as RECIPES layered from Ancient Warfare 2's
 * medieval sound library. The doctrine: modern firepower is heard as IMPACTS, not muzzle sounds -- an
 * A10 run or a tank shell is ram-hits + stone-breaks + delayed shock layers, not a "gunshot". Every
 * recipe = START + BURST + IMPACT + DISTANT RESPONSE with randomized pitch/volume, and impact voices
 * pick by the TARGET's block material (stone/iron/wood/soft ground).
 *
 * All AW2 sound events resolve lazily from the registry and cache; if AW2 is absent (or a name drifts)
 * every call is a clean no-op -- the war never crashes over audio. Sub-second layer timing runs through
 * a tick scheduler (one tick = 50ms; "25-35ms spacing" = two cues per tick).
 *
 * Registered on the Forge EVENT_BUS in EpochRunnerMod (the scheduler is a @SubscribeEvent server tick).
 */
public final class WarSoundboard {

    private WarSoundboard() {}

    private static final String VEH = "ancientwarfarevehicle:";
    private static final String STR = "ancientwarfarestructure:";
    private static final String NPC = "ancientwarfarenpc:";

    private static final Random R = new Random();

    // ------------------------------------------------------------------
    //  Resolution + scheduling
    // ------------------------------------------------------------------

    /** Lazily resolved AW2 sound events; a miss caches null so an absent AW2 costs one lookup, ever. */
    private static final Map<String, SoundEvent> CACHE = new HashMap<>();

    private static SoundEvent snd(String id) {
        if (CACHE.containsKey(id)) return CACHE.get(id);
        SoundEvent ev = null;
        try { ev = SoundEvent.REGISTRY.getObject(new ResourceLocation(id)); } catch (Throwable ignored) {}
        CACHE.put(id, ev);
        return ev;
    }

    private static final class Cue {
        final int dim; final double x, y, z; final String id; final float vol, pitch; final int due;
        Cue(int dim, double x, double y, double z, String id, float vol, float pitch, int due) {
            this.dim = dim; this.x = x; this.y = y; this.z = z;
            this.id = id; this.vol = vol; this.pitch = pitch; this.due = due;
        }
    }

    private static final List<Cue> QUEUE = new ArrayList<>();
    private static final int QUEUE_CAP = 768; // runaway guard: past this, extra layers just drop
    private static int serverTick = 0;

    @SubscribeEvent
    public static void onServerTick(TickEvent.ServerTickEvent e) {
        if (e.phase != TickEvent.Phase.END) return;
        serverTick++;
        if (QUEUE.isEmpty()) return;
        Iterator<Cue> it = QUEUE.iterator();
        while (it.hasNext()) {
            Cue c = it.next();
            if (serverTick < c.due) continue;
            it.remove();
            try {
                World w = DimensionManager.getWorld(c.dim);
                if (w == null) continue;
                SoundEvent ev = snd(c.id);
                if (ev != null) w.playSound(null, c.x, c.y, c.z, ev, SoundCategory.HOSTILE, c.vol, c.pitch);
            } catch (Throwable ignored) {}
        }
    }

    /** Play {@code id} at (x,y,z) after {@code delay} ticks (0 = immediately, no queue). */
    private static void at(World w, double x, double y, double z, String id, float vol, float pitch, int delay) {
        if (w == null || w.isRemote) return;
        if (delay <= 0) {
            SoundEvent ev = snd(id);
            if (ev != null) {
                try { w.playSound(null, x, y, z, ev, SoundCategory.HOSTILE, vol, pitch); } catch (Throwable ignored) {}
            }
            return;
        }
        if (QUEUE.size() >= QUEUE_CAP) return;
        QUEUE.add(new Cue(w.provider.getDimension(), x, y, z, id, vol, pitch, serverTick + delay));
    }

    private static float rr(float lo, float hi) { return lo + R.nextFloat() * (hi - lo); }

    /** The material-matched IMPACT voice: iron/wood/stone ram hits, soft ground = a bolt thudding in. */
    private static String ramFor(World w, double x, double y, double z) {
        try {
            BlockPos p = new BlockPos(x, y, z);
            Material m = w.getBlockState(p).getMaterial();
            if (!m.isSolid()) m = w.getBlockState(p.down()).getMaterial();
            if (m == Material.IRON || m == Material.ANVIL) return VEH + "battering_ram_hit_iron";
            if (m == Material.WOOD) return VEH + "battering_ram_hit_wood";
            if (m == Material.ROCK || m == Material.GLASS || m == Material.ICE || m == Material.PACKED_ICE)
                return VEH + "battering_ram_hit_stone";
        } catch (Throwable ignored) {}
        return VEH + "ballista_bolt_hit_ground";
    }

    // ------------------------------------------------------------------
    //  TANKS
    // ------------------------------------------------------------------

    /** Main-gun report at the muzzle. weight: 0 = light/autocannon, 1 = medium, 2 = heavy/MBT. */
    public static void tankCannon(World w, double x, double y, double z, int weight) {
        switch (weight) {
            case 2:
                at(w, x, y, z, VEH + "giant_trebuchet_launch", 5.0f, rr(0.60f, 0.70f), 0);
                at(w, x, y, z, STR + "gong_large", 2.0f, 0.45f, 1);
                at(w, x, y, z, VEH + "battering_ram_hit_iron", 2.5f, 0.70f, 2);
                break;
            case 1:
                at(w, x, y, z, VEH + "trebuchet_launch", 4.0f, rr(0.75f, 0.85f), 0);
                at(w, x, y, z, VEH + "battering_ram_hit_iron", 2.5f, 0.75f, 1);
                at(w, x, y, z, VEH + "vehicle_moving", 1.0f, 0.5f, 2); // short recoil rumble
                break;
            default:
                at(w, x, y, z, VEH + "ballista_launch", 3.0f, rr(0.90f, 1.00f), 0);
                at(w, x, y, z, VEH + "battering_ram_hit_iron", 2.0f, 0.75f, 1);
                break;
        }
    }

    /** Shell landing: explosion voice + 3-5 material-matched debris ticks. */
    public static void shellImpact(World w, double x, double y, double z, boolean heavy) {
        if (heavy) at(w, x, y, z, STR + "volcano_explosion", 4.0f, rr(0.85f, 0.95f), 0);
        else       at(w, x, y, z, STR + "wizard_explosion_2", 3.5f, rr(1.00f, 1.15f), 0);
        int debris = 3 + R.nextInt(3);
        for (int i = 0; i < debris; i++)
            at(w, x, y, z, ramFor(w, x, y, z), 1.5f, rr(0.85f, 1.05f), 1 + R.nextInt(9));
    }

    // ------------------------------------------------------------------
    //  AIRCRAFT
    // ------------------------------------------------------------------

    /** A10 run opener at the aircraft: the airframe hum swells (Doppler-ish high pitch). */
    public static void gau8Approach(World w, double x, double y, double z) {
        at(w, x, y, z, VEH + "vehicle_moving", 3.0f, 1.5f, 0);
        at(w, x, y, z, VEH + "ballista_launch", 2.0f, 0.6f, 2); // the low ripping cue
    }

    /** One strafe-line point: rapid ram ticks walking the ground (2/tick ~= the 25-35ms BRRRT spacing);
     *  every 4th round a near-miss thud; the last point gets the payoff explosion. */
    public static void gau8Impact(World w, double x, double y, double z, int seq, boolean last) {
        int delay = 4 + seq / 2;
        at(w, x, y, z, ramFor(w, x, y, z), 1.6f, rr(0.85f, 1.25f), delay);
        if (seq % 4 == 3)
            at(w, x, y, z, VEH + "ballista_bolt_hit_ground", 1.3f, rr(0.65f, 0.80f), delay + 1);
        if (last)
            at(w, x, y, z, STR + "wizard_explosion_2", 3.0f, rr(0.95f, 1.10f), delay + 3);
    }

    /** Jet pass near the player: whoosh in, sonic crack (low gong), airframe, whoosh out. */
    public static void jetFlyby(World w, double x, double y, double z) {
        at(w, x, y, z, NPC + "teleport_in", 3.0f, 1.2f, 0);
        at(w, x, y, z, STR + "gong_large", 3.0f, 0.5f, 4);
        at(w, x, y, z, VEH + "vehicle_moving", 2.5f, 1.6f, 6);
        at(w, x, y, z, NPC + "teleport_out", 3.0f, 1.1f, 14);
    }

    /** ONE second of rotor chop -- the caller re-pulses every 20 ticks while the heli lives.
     *  Light = fast high drums; heavy transport = slow low drums + a faint gong when close overhead. */
    public static void heliRotorPulse(World w, double x, double y, double z, boolean heavy) {
        float base = heavy ? 0.75f : 1.25f;
        at(w, x, y, z, VEH + "vehicle_moving", 2.0f, base, 0);
        int interval = heavy ? 5 : 3; // 250ms vs 150ms chop
        for (int t = 0; t < 20; t += interval)
            at(w, x, y, z, STR + "barbarian_drums_slow_short", 1.2f, rr(base - 0.1f, base + 0.1f), t);
        if (heavy) at(w, x, y, z, STR + "gong_large", 0.6f, 0.4f, 2);
    }

    /** Heli rocket pair leaving the rails. */
    public static void heliRocketFire(World w, double x, double y, double z) {
        at(w, x, y, z, VEH + "ballista_launch", 2.5f, rr(0.95f, 1.05f), 0);
        at(w, x, y, z, VEH + "ballista_launch", 2.5f, rr(0.95f, 1.05f), 2);
        at(w, x, y, z, NPC + "teleport_out", 1.5f, 1.4f, 3); // rocket whoosh away
    }

    /** FLAK: the battery's launch thump at the gun + the shell cracking in the sky a beat later. */
    public static void flakBurst(World w, double gx, double gy, double gz, double ax, double ay, double az) {
        at(w, gx, gy, gz, VEH + "ballista_launch", 3.0f, rr(0.70f, 0.80f), 0);
        at(w, ax, ay, az, STR + "wizard_explosion_2", 3.5f, rr(1.15f, 1.35f), 3 + R.nextInt(3));
        at(w, ax, ay, az, VEH + "battering_ram_hit_iron", 1.2f, rr(1.2f, 1.4f), 6); // shrapnel ping
    }

    /** Rocket landing: small sharp blast + debris. */
    public static void rocketImpact(World w, double x, double y, double z) {
        at(w, x, y, z, STR + "wizard_explosion_2", 3.0f, rr(1.05f, 1.20f), 1);
        at(w, x, y, z, ramFor(w, x, y, z), 1.4f, rr(0.85f, 1.00f), 3);
        at(w, x, y, z, ramFor(w, x, y, z), 1.2f, rr(0.85f, 1.00f), 6);
    }

    /** Bomb leaving the bay. big = strategic bomber weight. */
    public static void bombRelease(World w, double x, double y, double z, boolean big) {
        at(w, x, y, z, big ? VEH + "giant_trebuchet_launch" : VEH + "trebuchet_launch", 3.0f, rr(0.95f, 1.05f), 0);
    }

    /** The bomb's ground shock, scheduled to land WITH the TNT fuse: boom + gong shockwave + debris. */
    public static void bombShockwave(World w, double x, double y, double z, int delayTicks) {
        at(w, x, y, z, STR + "volcano_explosion", 4.5f, rr(0.90f, 1.00f), delayTicks);
        at(w, x, y, z, STR + "gong_large", 3.5f, 0.5f, delayTicks + 2);
        for (int i = 0; i < 4; i++)
            at(w, x, y, z, VEH + "battering_ram_hit_stone", 1.5f, rr(0.80f, 1.00f), delayTicks + 4 + R.nextInt(10));
    }

    // ------------------------------------------------------------------
    //  DESTRUCTION
    // ------------------------------------------------------------------

    /** Structural collapse: one gate-break, then a rain of stone/wood hits with the pitch drifting DOWN. */
    public static void buildingCollapse(World w, double x, double y, double z, boolean big) {
        at(w, x, y, z, big ? STR + "iron_gate_break" : STR + "wooden_gate_break", big ? 3.0f : 2.5f, rr(0.9f, 1.0f), 0);
        int hits = big ? 16 : 8;
        int span = big ? 60 : 30;
        for (int i = 0; i < hits; i++) {
            float prog = i / (float) hits;
            String voice = (R.nextInt(3) == 0) ? VEH + "battering_ram_hit_wood" : VEH + "battering_ram_hit_stone";
            at(w, x, y, z, voice, 1.8f, 1.15f - prog * 0.45f, 2 + (int) (prog * span) + R.nextInt(4));
        }
    }

    /** Catapult boulder landing: dirt thud + stone cracks (the launch keeps AW2's own catapult voice). */
    public static void catapultImpact(World w, double x, double y, double z) {
        at(w, x, y, z, VEH + "ballista_bolt_hit_ground", 3.0f, rr(0.75f, 0.85f), 0);
        at(w, x, y, z, VEH + "battering_ram_hit_stone", 2.0f, rr(0.85f, 0.95f), 1);
        at(w, x, y, z, VEH + "battering_ram_hit_stone", 1.5f, rr(0.75f, 0.85f), 3);
    }

    // ------------------------------------------------------------------
    //  INFANTRY
    // ------------------------------------------------------------------

    /** Rifle report: a sharp high ballista snap (replaces the blaze-hurt placeholder). */
    public static void rifleSnap(World w, double x, double y, double z) {
        at(w, x, y, z, VEH + "ballista_launch", 1.2f, rr(1.4f, 1.8f), 0);
    }

    /** Round striking a soldier/creature. */
    public static void bulletHitEntity(World w, double x, double y, double z) {
        at(w, x, y, z, VEH + "ballista_bolt_hit_entity", 1.0f, rr(1.1f, 1.3f), 1);
    }

    // ------------------------------------------------------------------
    //  SIEGE / RTS CUES
    // ------------------------------------------------------------------

    /** War is coming: drums at the victim's base, a horn from the hills, the church bell, the gong. */
    public static void raidWarning(World w, double x, double y, double z) {
        at(w, x, y, z, STR + "barbarian_drums_warning", 8.0f, 1.0f, 0);
        at(w, x, y, z, STR + "horn_1", 8.0f, 1.0f, 12);
        at(w, x, y, z, STR + "empire_church_bell", 8.0f, 1.0f, 30);
        at(w, x, y, z, STR + "gong_large", 6.0f, 0.8f, 55);
    }

    /** Phase-change gong at the staging line (cycles gongs_1..6 by phase index). */
    public static void phaseCue(World w, double x, double y, double z, int idx) {
        int n = 1 + Math.floorMod(idx, 6);
        at(w, x, y, z, STR + "gongs_" + n, 5.0f, 1.0f, 0);
    }

    /** The surge: a charge horn over the field. */
    public static void chargeHorn(World w, double x, double y, double z) {
        at(w, x, y, z, STR + "horn_5", 8.0f, 1.0f, 0);
        at(w, x, y, z, STR + "barbarian_drums_warning", 5.0f, 1.1f, 8);
    }

    /** The army marches out with the spoils: a long horn + drums fading into the distance. */
    public static void armyDeparts(World w, double x, double y, double z) {
        at(w, x, y, z, STR + "horn_3", 7.0f, 0.9f, 0);
        at(w, x, y, z, STR + "barbarian_drums_distant", 5.0f, 0.9f, 20);
        at(w, x, y, z, STR + "barbarian_drums_distant", 3.5f, 0.85f, 70);
    }

    /** District created / depot bound: the claim-flag flourish + a coin clink. */
    public static void districtCreated(World w, double x, double y, double z) {
        at(w, x, y, z, STR + "protection_flag_claim", 1.5f, 1.0f, 0);
        at(w, x, y, z, STR + "coin_stack_interact", 1.2f, 1.0f, 4);
    }
}
