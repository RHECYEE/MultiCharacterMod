package studio.ERM.war.air;

import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.util.DamageSource;
import net.minecraft.util.EnumParticleTypes;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.text.TextComponentString;
import net.minecraft.util.text.TextFormatting;
import net.minecraft.world.WorldServer;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import net.minecraftforge.fml.common.gameevent.TickEvent;
import studio.ERM.EpochRunnerMod;
import studio.ERM.strategic.civil.CivilMarker;
import studio.ERM.strategic.civil.CivilPlanData;
import studio.ERM.strategic.civil.DistrictRegistry;
import studio.ERM.strategic.civil.DistrictWorkExecutor;
import studio.ERM.war.districts.TileEntityAssemblySeat;
import studio.ERM.war.districts.TileEntityDistrictMarker;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

/**
 * THE AIR DEFENSE NET — Radar Operator + AA Battery districts, manned through the FACTORY CHAIR
 * mechanic (assembly seats inside the polygon; seats dictate the district's employees; a seat is
 * manned when enough workers are assigned).
 *
 *   RADAR   — manned radar seats DETECT enemy aircraft (range grows per manned seat) and put them
 *             on the map as green contact bars; a staffed radar posts WARNING messages when
 *             contacts enter the net. (Radar LEVELS come later — seats are the dial today.)
 *   AA      — manned battery seats throw FLAK at detected aircraft: visible airbursts walking
 *             around the target, with hit chance driven by the settlement's AA SCORE.
 *   AA SCORE— manned radar seats x2 + manned AA seats x1: radar guides the guns, so operators
 *             raise the hit likelihood exactly as the design calls for.
 *
 * The damage source is plain non-explosive "erm_flak": EntityGhostAircraft's friendly-fire guard
 * accepts it (it only blocks its own side + anonymous EXPLOSIONS), and its aaSource() name hook
 * doubles AA damage. Registered on the Forge EVENT_BUS in EpochRunnerMod.
 */
public final class AirDefenseManager {

    private static final int PASS_INTERVAL = 20;         // 1s station tick
    private static final int WARN_EVERY = 15 * 20;       // radar warning cadence while contacts exist
    private static final int FLAK_EVERY = 40;            // each battery volleys every 2s
    private static final double BASE_RADAR_RANGE = 160.0;
    private static final double RANGE_PER_SEAT = 40.0;
    private static final double FLAK_RANGE = 140.0;
    private static final Random RNG = new Random();

    private AirDefenseManager() {}

    private static int tickCounter = 0;
    private static final Map<Integer, Long> LAST_WARN = new HashMap<>();
    private static final Map<Integer, Long> LAST_FLAK = new HashMap<>();

    // Published state (read by the civil-plan sync for the map + sidebar).
    private static volatile boolean radarActive = false;
    private static volatile int aaScore = 0;

    public static boolean isRadarActive() { return radarActive; }
    public static int getAaScore() { return aaScore; }

    @SubscribeEvent
    public static void onWorldTick(TickEvent.WorldTickEvent e) {
        if (e.phase != TickEvent.Phase.END || e.side.isClient() || !(e.world instanceof WorldServer)) return;
        if (++tickCounter % PASS_INTERVAL != 0) return;
        WorldServer world = (WorldServer) e.world;
        try {
            run(world);
        } catch (Throwable t) {
            EpochRunnerMod.logger.error("[AirDefense] pass failed (guarded)", t);
        }
    }

    private static void run(WorldServer world) {
        int mannedRadar = 0, mannedAA = 0;
        List<CivilMarker> radars = new ArrayList<>();
        List<CivilMarker> batteries = new ArrayList<>();

        for (CivilMarker m : CivilPlanData.get(world).markers) {
            if (m.isRoad() || (m.kind != CivilMarker.RADAR && m.kind != CivilMarker.AA_BATTERY)) continue;
            if (!m.hasDepot()) continue;
            int seats = countSeats(world, m);
            // SEATS DICTATE EMPLOYEES (the factory doctrine): desired workers track the chairs.
            TileEntityDistrictMarker depot = DistrictRegistry.depotOf(world, m);
            if (depot != null && seats > 0 && depot.getDesiredWorkers() != seats) depot.setDesiredWorkers(seats);
            int manned = Math.min(seats, DistrictWorkExecutor.assignedTo(m.uid));
            if (manned <= 0) continue;
            if (m.kind == CivilMarker.RADAR) { mannedRadar += manned; radars.add(m); }
            else { mannedAA += manned; batteries.add(m); }
        }

        aaScore = mannedRadar * 2 + mannedAA;
        radarActive = mannedRadar > 0;
        if (!radarActive && batteries.isEmpty()) return;

        // DETECTION: every enemy ghost aircraft within any radar's net (or, blind-firing without
        // radar, within an AA battery's own short horizon — guns can still shoot what they see).
        List<EntityGhostAircraft> contacts = new ArrayList<>();
        double radarRange = BASE_RADAR_RANGE + RANGE_PER_SEAT * mannedRadar;
        for (net.minecraft.entity.Entity ent : world.loadedEntityList) {
            if (!(ent instanceof EntityGhostAircraft) || ent.isDead) continue;
            boolean seen = false;
            for (CivilMarker r : radars) {
                BlockPos c = r.center();
                double dx = ent.posX - c.getX(), dz = ent.posZ - c.getZ();
                if (dx * dx + dz * dz <= radarRange * radarRange) { seen = true; break; }
            }
            if (!seen) {
                for (CivilMarker b : batteries) {
                    BlockPos c = b.center();
                    double dx = ent.posX - c.getX(), dz = ent.posZ - c.getZ();
                    if (dx * dx + dz * dz <= FLAK_RANGE * FLAK_RANGE * 0.5) { seen = true; break; } // eyes only
                }
            }
            if (seen) contacts.add((EntityGhostAircraft) ent);
        }
        if (contacts.isEmpty()) return;

        // RADAR WARNING: a staffed radar calls the raid (throttled).
        int dim = world.provider.getDimension();
        long now = world.getTotalWorldTime();
        if (radarActive && now - LAST_WARN.getOrDefault(dim, 0L) >= WARN_EVERY) {
            LAST_WARN.put(dim, now);
            for (EntityPlayer p : world.playerEntities) {
                p.sendMessage(new TextComponentString(TextFormatting.GREEN + "[RADAR] "
                        + TextFormatting.YELLOW + contacts.size() + " enemy aircraft on the scope!"
                        + (mannedAA > 0 ? TextFormatting.GRAY + " AA batteries engaging." : "")));
            }
        }

        // FLAK: each manned battery volleys at its nearest contact in range.
        if (batteries.isEmpty() || now - LAST_FLAK.getOrDefault(dim, 0L) < FLAK_EVERY) return;
        LAST_FLAK.put(dim, now);
        double hitChance = Math.min(0.75, 0.12 + 0.04 * aaScore); // AA SCORE drives accuracy
        for (CivilMarker b : batteries) {
            BlockPos c = b.center();
            BlockPos gun = world.getTopSolidOrLiquidBlock(new BlockPos(c.getX(), 64, c.getZ()));
            EntityGhostAircraft target = null;
            double bd = FLAK_RANGE * FLAK_RANGE;
            for (EntityGhostAircraft a : contacts) {
                double dx = a.posX - c.getX(), dz = a.posZ - c.getZ();
                double d = dx * dx + dz * dz;
                if (d < bd) { bd = d; target = a; }
            }
            if (target == null) continue;

            boolean hit = RNG.nextDouble() < hitChance;
            // Airburst: ON the airframe when the roll hits; a near-miss puff walking around it when not.
            double spread = hit ? 1.5 : 6.0 + RNG.nextDouble() * 10.0;
            double bx = target.posX + (RNG.nextDouble() - 0.5) * spread;
            double by = target.posY + (RNG.nextDouble() - 0.5) * spread * 0.6;
            double bz = target.posZ + (RNG.nextDouble() - 0.5) * spread;
            world.spawnParticle(EnumParticleTypes.EXPLOSION_LARGE, bx, by, bz, 1, 0.0D, 0.0D, 0.0D, 0.0D);
            world.spawnParticle(EnumParticleTypes.CLOUD, bx, by, bz, 8, 0.6D, 0.4D, 0.6D, 0.01D);
            studio.ERM.war.sound.WarSoundboard.flakBurst(world,
                    gun.getX() + 0.5, gun.getY() + 1.0, gun.getZ() + 0.5, bx, by, bz);
            if (hit) {
                float dmg = (float) (60.0 + aaScore * 8.0)
                        * (float) studio.ERM.war.config.WeaponClassConfig.mult(
                                studio.ERM.war.config.WeaponClassConfig.CLASS_AA,
                                studio.ERM.war.config.WeaponClassConfig.TARGET_AIR);
                target.attackEntityFrom(new DamageSource("erm_flak"), dmg);
                EpochRunnerMod.logger.info("[AirDefense] FLAK HIT " + target.getName()
                        + " for " + (int) dmg + " (aaScore=" + aaScore + ")");
            }
        }
    }

    /** Assembly seats inside a polygon, via the loaded TE list (cheap — seats are rare tiles). */
    private static int countSeats(WorldServer world, CivilMarker m) {
        int n = 0;
        for (TileEntity te : world.loadedTileEntityList) {
            if (!(te instanceof TileEntityAssemblySeat)) continue;
            BlockPos p = te.getPos();
            if (m.contains(p.getX(), p.getZ())) n++;
        }
        return n;
    }
}
