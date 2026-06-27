package studio.ERM.war.strategy;

import net.minecraft.util.EnumParticleTypes;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;
import net.minecraft.world.WorldServer;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import net.minecraftforge.fml.common.gameevent.TickEvent;

import java.util.ArrayList;
import java.util.List;

/**
 * Temporary in-world particle visualization of the strategic heat map + the chosen siege target, shown
 * for a fixed duration after {@code /war heat}. Each scanned chunk gets a coloured vertical bar at its
 * centre (colour = classification, height = heat); the chosen TARGET gets a tall bright beacon; the
 * defender's protected blocks are flagged. Lets the player SEE what the siege is targeting and why.
 *
 * Registered on the Forge event bus by {@code EpochRunnerMod.init()}.
 */
public final class WarHeatDebug {

    private WarHeatDebug() {}

    public static final class Marker {
        public final BlockPos pos;
        public final float r, g, b;
        public final int height;
        public Marker(BlockPos pos, float r, float g, float b, int height) {
            this.pos = pos; this.r = r; this.g = g; this.b = b; this.height = Math.max(1, height);
        }
    }

    private static final List<Marker> MARKERS = new ArrayList<>();
    private static long expireTick = -1L;
    private static int dim = 0;

    /** Show {@code markers} in {@code world} for {@code durationTicks} (20 ticks = 1s). */
    public static void show(World world, List<Marker> markers, int durationTicks) {
        synchronized (MARKERS) {
            MARKERS.clear();
            MARKERS.addAll(markers);
            dim = world.provider.getDimension();
            expireTick = world.getTotalWorldTime() + durationTicks;
        }
    }

    public static void clear() {
        synchronized (MARKERS) { MARKERS.clear(); expireTick = -1L; }
    }

    @SubscribeEvent
    public static void onWorldTick(TickEvent.WorldTickEvent e) {
        if (e.phase != TickEvent.Phase.END || e.world.isRemote) return;
        if (e.world.provider.getDimension() != dim) return;
        synchronized (MARKERS) {
            if (MARKERS.isEmpty()) return;
            if (e.world.getTotalWorldTime() >= expireTick) { MARKERS.clear(); return; }
            if (e.world.getTotalWorldTime() % 4 != 0) return; // ~5 refreshes/sec is plenty
            if (!(e.world instanceof WorldServer)) return;
            WorldServer ws = (WorldServer) e.world;
            for (Marker m : MARKERS) {
                // Redstone-dust particle colour hack: count=0 makes the client read (dx,dy,dz) as RGB.
                // A 0 red channel renders as full red, so floor it to a tiny epsilon.
                float rr = Math.max(0.001f, m.r);
                for (int h = 0; h < m.height; h++) {
                    ws.spawnParticle(EnumParticleTypes.REDSTONE,
                            m.pos.getX() + 0.5, m.pos.getY() + 1.0 + h, m.pos.getZ() + 0.5,
                            0, rr, m.g, m.b, 1.0D);
                }
            }
        }
    }
}
