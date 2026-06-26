package studio.ERM.war;

import net.minecraft.block.state.IBlockState;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.init.Blocks;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.ChunkPos;
import net.minecraft.world.World;
import net.minecraftforge.event.world.ExplosionEvent;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import net.minecraftforge.fml.common.gameevent.TickEvent;
import studio.ERM.EpochRunnerMod;
import studio.ERM.war.world.WarWorldData;

import java.util.ArrayList;
import java.util.List;

/**
 * ANTIGRIEF repair system.
 *
 * The whole point of claiming land is that war can DAMAGE it but never permanently grief it: any
 * block destroyed by an explosion inside CLAIMED player territory is intercepted here, its original
 * state is stored for repair ({@link WarWorldData#addRepairOrder}), and a PASSABLE scaffold marker is
 * left in its place. The {@code /war repair} command and the builder citizen restore the originals
 * from that record. Outside claimed land (rival / neutral ground) explosions behave normally.
 *
 * This catches ALL explosions -- TNT, creepers, vehicle/airstrike ordnance, even the nuke -- by
 * editing the explosion's affected-blocks list before vanilla removes them. (The siege director
 * routes its own manual breaches through an equivalent claim-aware path directly.)
 *
 * Registered on the Forge EVENT_BUS in EpochRunnerMod.init().
 */
public class WarRepairHandler {

    // Run-once guard for the advancement recipe-book crash sanitize (see onWorldTick).
    private static boolean sanitizedAdvancements = false;

    /**
     * Most reliable trigger for the advancement recipe-book crash guard: a player just logged in, so
     * the advancement list is fully loaded. Idempotent (re-running finds nothing to fix), so running
     * it on every login is harmless and guarantees it happens before the player can trigger the crash.
     */
    @SubscribeEvent
    public void onPlayerLogin(net.minecraftforge.fml.common.gameevent.PlayerEvent.PlayerLoggedInEvent event) {
        try {
            if (event.player != null && event.player.world != null && !event.player.world.isRemote) {
                sanitizedAdvancements = true;
                EpochRunnerMod.sanitizeBrokenAdvancementRecipes(event.player.world.getMinecraftServer());
            }
        } catch (Throwable ignored) {}
    }

    @SubscribeEvent
    public void onExplosionDetonate(ExplosionEvent.Detonate event) {
        World world = event.getWorld();
        if (world == null || world.isRemote) return;
        if (EpochRunnerMod.scaffold == null) return;

        WarWorldData data = WarWorldData.get(world);
        if (data == null) return;

        List<BlockPos> affected = event.getAffectedBlocks();
        if (affected == null || affected.isEmpty()) return;

        List<BlockPos> handled = new ArrayList<>();
        for (BlockPos pos : affected) {
            try {
                // Protector-stick blocks are absolutely indestructible -- leave them entirely to
                // ProtectionHandler (which also strips them from this explosion); never scaffold them.
                if (studio.ERM.handlers.ProtectionHandler.isProtected(world, pos)) continue;
                if (!isClaimedLand(data, pos)) continue;
                if (world.isAirBlock(pos)) { handled.add(pos); continue; }

                IBlockState st = world.getBlockState(pos);
                if (st.getBlockHardness(world, pos) < 0) { handled.add(pos); continue; } // bedrock

                // Preserve the original for repair, drop a passable scaffold marker, and take this
                // block out of the explosion so vanilla doesn't also blow it away.
                data.addRepairOrder(pos.toImmutable(), st);
                world.setBlockState(pos, EpochRunnerMod.scaffold.getDefaultState(), 2);
                handled.add(pos);
            } catch (Throwable ignored) {}
        }

        if (!handled.isEmpty()) {
            affected.removeAll(handled);
        }
    }

    /**
     * Fire-spread antigrief: claimed land does not burn. Every second, around each player, any FIRE
     * block sitting in a claimed chunk is snuffed out. Bounded + throttled, and it only scans the Y
     * column when that column's chunk is actually claimed, so unclaimed terrain costs almost nothing.
     */
    @SubscribeEvent
    public void onWorldTick(TickEvent.WorldTickEvent event) {
        if (event.phase != TickEvent.Phase.END) return;
        World world = event.world;
        if (world == null || world.isRemote) return;

        // Strip dangling recipe rewards (the ForgeHooks.sendRecipeBook "Ticking player" crash). The
        // global advancement list is populated AFTER the first ticks, so we must wait until it is
        // actually non-empty before sanitizing -- otherwise we scan nothing, set the guard, and never
        // retry (which is exactly what let the crash through). Retry every tick until loaded.
        if (!sanitizedAdvancements) {
            net.minecraft.server.MinecraftServer srv = world.getMinecraftServer();
            if (srv != null) {
                try {
                    if (srv.getAdvancementManager().getAdvancements().iterator().hasNext()) {
                        sanitizedAdvancements = true;
                        EpochRunnerMod.sanitizeBrokenAdvancementRecipes(srv);
                    }
                } catch (Throwable ignored) {}
            }
        }

        if ((world.getTotalWorldTime() % 20L) != 0L) return;

        WarWorldData data = WarWorldData.get(world);
        if (data == null) return;

        final int r = 18, hy = 6;
        BlockPos.MutableBlockPos mp = new BlockPos.MutableBlockPos();
        for (EntityPlayer p : world.playerEntities) {
            if (p == null) continue;
            int cx = (int) Math.floor(p.posX), cy = (int) Math.floor(p.posY), cz = (int) Math.floor(p.posZ);
            for (int dx = -r; dx <= r; dx++) {
                for (int dz = -r; dz <= r; dz++) {
                    int x = cx + dx, z = cz + dz;
                    if (!isClaimedLand(data, new BlockPos(x, cy, z))) continue; // only claimed columns
                    for (int dy = -hy; dy <= hy; dy++) {
                        mp.setPos(x, cy + dy, z);
                        try {
                            if (world.getBlockState(mp).getBlock() == Blocks.FIRE) {
                                world.setBlockToAir(mp);
                            }
                        } catch (Throwable ignored) {}
                    }
                }
            }
        }
    }

    private static boolean isClaimedLand(WarWorldData data, BlockPos pos) {
        try {
            String owner = data.getOwner(new ChunkPos(pos));
            return owner != null && !"NEUTRAL".equals(owner) && !"RIVAL".equals(owner);
        } catch (Throwable t) {
            return false;
        }
    }
}
