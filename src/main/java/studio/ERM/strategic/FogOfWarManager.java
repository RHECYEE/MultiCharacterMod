package studio.ERM.strategic;

import net.minecraft.entity.EntityCreature;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.world.WorldServer;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import net.minecraftforge.fml.common.gameevent.TickEvent;
import studio.ERM.EpochRunnerMod;
import studio.ERM.strategic.civil.DistrictWorkExecutor;
import studio.ERM.strategic.defense.Aw2Npc;

/**
 * PRESENCE CHARTING — feeds {@link ExploredMapData}: every few seconds the player charts a wide
 * ring around themselves, and every player-owned citizen/soldier charts a small one around its
 * work. Your people extend your map while you're elsewhere — which is exactly why the chart can be
 * OUT OF DATE: it records that someone was there, not what's true now.
 */
public final class FogOfWarManager {

    private static final int PASS_INTERVAL = 100;   // 5s charting cadence
    private static final int PLAYER_RADIUS = 6;     // chunks around each player
    private static final int CITIZEN_RADIUS = 2;    // chunks around each owned citizen/soldier

    private FogOfWarManager() {}

    private static int tickCounter = 0;

    @SubscribeEvent
    public static void onWorldTick(TickEvent.WorldTickEvent e) {
        if (e.phase != TickEvent.Phase.END || e.side.isClient() || !(e.world instanceof WorldServer)) return;
        if (++tickCounter % PASS_INTERVAL != 0) return;
        WorldServer world = (WorldServer) e.world;
        try {
            ExploredMapData data = ExploredMapData.get(world);
            long now = world.getTotalWorldTime();
            for (EntityPlayer p : world.playerEntities) {
                data.markAround(p.getPosition(), PLAYER_RADIUS, now);
            }
            for (Object o : world.loadedEntityList) {
                if (!(o instanceof EntityCreature)) continue;
                EntityCreature c = (EntityCreature) o;
                if (c.isDead) continue;
                if (DistrictWorkExecutor.isAnyWorker(c) || Aw2Npc.isPlayerOwnedCombat(c)) {
                    data.markAround(c.getPosition(), CITIZEN_RADIUS, now);
                }
            }
        } catch (Throwable t) {
            EpochRunnerMod.logger.error("[Fog] charting pass failed (guarded)", t);
        }
    }
}
