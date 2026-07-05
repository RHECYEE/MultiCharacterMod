package studio.ERM.war.strategy;

import net.minecraft.entity.Entity;
import net.minecraft.entity.item.EntityArmorStand;
import net.minecraft.util.EnumParticleTypes;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;
import net.minecraft.world.WorldServer;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import net.minecraftforge.fml.common.gameevent.TickEvent;

import java.util.ArrayList;
import java.util.List;

/**
 * Temporary in-world debug overlay for the heat map / siege / engineers. Renders for a fixed duration:
 *   BEAM  -- a thick coloured vertical column (objective / target / a crew).
 *   BOX   -- a coloured cube outline around an obstacle (size = half-extent).
 *   LINE  -- a coloured particle line between two points (the planned route).
 *   CROSS -- a red X (a REJECTED choice / tool).
 *   Label -- floating text via an invisible armour stand (objective / obstacle tool / reasoning).
 *
 * Registered on the Forge event bus by {@code EpochRunnerMod.init()} (static handler -> register class).
 */
public final class WarHeatDebug {

    private WarHeatDebug() {}

    public enum Kind { BEAM, BOX, LINE, CROSS }

    public static final class Marker {
        public final Kind kind;
        public final BlockPos a, b;
        public final float r, g, bl;
        public final int size;
        private Marker(Kind kind, BlockPos a, BlockPos b, float r, float g, float bl, int size) {
            this.kind = kind; this.a = a; this.b = b; this.r = r; this.g = g; this.bl = bl; this.size = size;
        }
        /** Backward-compatible: a plain (pos, r,g,b, height) marker is a BEAM. */
        public Marker(BlockPos pos, float r, float g, float bl, int height) {
            this(Kind.BEAM, pos, null, r, g, bl, Math.max(1, height));
        }
        public static Marker beam(BlockPos p, float r, float g, float bl, int h) { return new Marker(Kind.BEAM, p, null, r, g, bl, Math.max(1, h)); }
        public static Marker box(BlockPos p, float r, float g, float bl, int half) { return new Marker(Kind.BOX, p, null, r, g, bl, Math.max(1, half)); }
        public static Marker line(BlockPos a, BlockPos b, float r, float g, float bl) { return new Marker(Kind.LINE, a, b, r, g, bl, 1); }
        public static Marker cross(BlockPos p, int arm) { return new Marker(Kind.CROSS, p, null, 1f, 0f, 0f, Math.max(1, arm)); }
    }

    public static final class Label {
        public final BlockPos pos; public final String text;
        public Label(BlockPos pos, String text) { this.pos = pos; this.text = text; }
    }

    private static final List<Marker> MARKERS = new ArrayList<>();
    private static final List<Integer> LABEL_IDS = new ArrayList<>();
    private static long expireTick = -1L;
    private static int dim = 0;

    public static void show(World world, List<Marker> markers, int durationTicks) {
        show(world, markers, null, durationTicks);
    }

    /** Show markers (+ optional floating labels) in {@code world} for {@code durationTicks} (20 = 1s). */
    public static void show(World world, List<Marker> markers, List<Label> labels, int durationTicks) {
        synchronized (MARKERS) {
            killLabels(world);
            MARKERS.clear();
            if (markers != null) MARKERS.addAll(markers);
            dim = world.provider.getDimension();
            expireTick = world.getTotalWorldTime() + durationTicks;
            if (labels != null && world instanceof WorldServer) {
                for (Label l : labels) {
                    try {
                        EntityArmorStand st = new EntityArmorStand(world, l.pos.getX() + 0.5, l.pos.getY(), l.pos.getZ() + 0.5);
                        st.setCustomNameTag(l.text);
                        st.setAlwaysRenderNameTag(true);
                        st.setInvisible(true);
                        st.setNoGravity(true);
                        st.setSilent(true);
                        st.setEntityInvulnerable(true);
                        world.spawnEntity(st);
                        LABEL_IDS.add(st.getEntityId());
                    } catch (Throwable ignored) {}
                }
            }
        }
    }

    public static void clear(World world) {
        synchronized (MARKERS) { MARKERS.clear(); killLabels(world); expireTick = -1L; }
    }

    private static void killLabels(World world) {
        for (int id : LABEL_IDS) {
            try { Entity e = world.getEntityByID(id); if (e != null) e.setDead(); } catch (Throwable ignored) {}
        }
        LABEL_IDS.clear();
    }

    @SubscribeEvent
    public static void onWorldTick(TickEvent.WorldTickEvent e) {
        if (e.phase != TickEvent.Phase.END || e.world.isRemote) return;
        if (e.world.provider.getDimension() != dim) return;
        synchronized (MARKERS) {
            if (MARKERS.isEmpty() && LABEL_IDS.isEmpty()) return;
            if (e.world.getTotalWorldTime() >= expireTick) { MARKERS.clear(); killLabels(e.world); return; }
            if (e.world.getTotalWorldTime() % 4 != 0) return;
            if (!(e.world instanceof WorldServer)) return;
            WorldServer ws = (WorldServer) e.world;
            for (Marker m : MARKERS) {
                try { render(ws, m); } catch (Throwable ignored) {}
            }
        }
    }

    private static void render(WorldServer ws, Marker m) {
        switch (m.kind) {
            case BEAM: {
                for (int h = 0; h < m.size; h++) {
                    double y = m.a.getY() + 1.0 + h;
                    dust(ws, m.a.getX() + 0.5, y, m.a.getZ() + 0.5, m.r, m.g, m.bl);
                    dust(ws, m.a.getX() + 0.15, y, m.a.getZ() + 0.15, m.r, m.g, m.bl);
                    dust(ws, m.a.getX() + 0.85, y, m.a.getZ() + 0.85, m.r, m.g, m.bl);
                }
                break;
            }
            case BOX: {
                int s = m.size;
                double cx = m.a.getX() + 0.5, cy = m.a.getY() + 0.5, cz = m.a.getZ() + 0.5;
                for (int i = -s; i <= s; i++) {
                    dust(ws, cx + i, cy - s, cz - s, m.r, m.g, m.bl); dust(ws, cx + i, cy - s, cz + s, m.r, m.g, m.bl);
                    dust(ws, cx + i, cy + s, cz - s, m.r, m.g, m.bl); dust(ws, cx + i, cy + s, cz + s, m.r, m.g, m.bl);
                    dust(ws, cx - s, cy + i, cz - s, m.r, m.g, m.bl); dust(ws, cx - s, cy + i, cz + s, m.r, m.g, m.bl);
                    dust(ws, cx + s, cy + i, cz - s, m.r, m.g, m.bl); dust(ws, cx + s, cy + i, cz + s, m.r, m.g, m.bl);
                    dust(ws, cx - s, cy - s, cz + i, m.r, m.g, m.bl); dust(ws, cx + s, cy - s, cz + i, m.r, m.g, m.bl);
                    dust(ws, cx - s, cy + s, cz + i, m.r, m.g, m.bl); dust(ws, cx + s, cy + s, cz + i, m.r, m.g, m.bl);
                }
                break;
            }
            case LINE: {
                double dx = m.b.getX() - m.a.getX(), dy = m.b.getY() - m.a.getY(), dz = m.b.getZ() - m.a.getZ();
                int steps = (int) Math.max(1, Math.sqrt(dx * dx + dy * dy + dz * dz));
                for (int i = 0; i <= steps; i++) {
                    double t = (double) i / steps;
                    dust(ws, m.a.getX() + 0.5 + dx * t, m.a.getY() + 1.2 + dy * t, m.a.getZ() + 0.5 + dz * t, m.r, m.g, m.bl);
                }
                break;
            }
            case CROSS: {
                int s = m.size;
                double cx = m.a.getX() + 0.5, cy = m.a.getY() + 2.0, cz = m.a.getZ() + 0.5;
                for (int i = -s; i <= s; i++) {
                    dust(ws, cx + i, cy + i, cz, 1f, 0f, 0f);
                    dust(ws, cx + i, cy - i, cz, 1f, 0f, 0f);
                    dust(ws, cx, cy + i, cz + i, 1f, 0f, 0f);
                    dust(ws, cx, cy - i, cz + i, 1f, 0f, 0f);
                }
                break;
            }
        }
    }

    private static void dust(WorldServer ws, double x, double y, double z, float r, float g, float b) {
        // Redstone-dust colour hack: count=0 makes the client read (dx,dy,dz) as RGB; 0 red renders full red.
        ws.spawnParticle(EnumParticleTypes.REDSTONE, x, y, z, 0, Math.max(0.001f, r), g, b, 1.0D);
    }
}
