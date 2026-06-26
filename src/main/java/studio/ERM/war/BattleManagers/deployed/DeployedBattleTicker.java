package studio.ERM.war.BattleManagers.deployed;

import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.world.World;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import net.minecraftforge.fml.common.gameevent.TickEvent;
import studio.ERM.war.BattleManagers.core.BattleEngine;
import studio.ERM.war.BattleManagers.directors.BattleDirectorEntry;
import studio.ERM.war.BattleManagers.directors.BattleDirectorRegistry;
import studio.ERM.war.map.net.TacticalWarMapNetwork;
import studio.ERM.war.map.net.PacketDeployedBattlesSync;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Pushes deployed battle markers to clients so the tactical map can render waypoints.
 *
 * This is registered on the Forge EVENT_BUS (see EpochRunnerMod) and ticks on the server.
 * It only syncs summary data, not director internals.
 */
public final class DeployedBattleTicker {

    // Instance tick counter (one instance registered on EVENT_BUS)
    private int tickCounter = 0;

    public DeployedBattleTicker() {
    }

    @SubscribeEvent
    public void onServerTick(TickEvent.ServerTickEvent event) {
        if (event == null || event.phase != TickEvent.Phase.END) return;

        // Core loop: proximity trigger + active battle startup.
        // This MUST run every tick so "approach radius" battles reliably start.
        try {
            for (World world : net.minecraftforge.common.DimensionManager.getWorlds()) {
                if (world == null || world.isRemote) continue;

                // Authoritative battle engine tick: the ONE server-side path that drives active
                // directors every tick, independent of any spawned anchor entity. The old
                // EntityBattleDirectorAnchor path stalled battles whenever its chunk unloaded.
                BattleEngine engine = BattleEngine.get(world);
                if (engine != null) {
                    engine.tick();
                }

                DeployedBattleManager manager = DeployedBattleManager.get(world);
                if (manager != null) {
                    manager.tick(world);
                }
            }
        } catch (Throwable ignored) {
        }

        tickCounter++;
        // Sync cadence: every 40 ticks (~2 seconds). Keep this light.
        if (tickCounter >= 40) {
            tickCounter = 0;
            try {
                syncToAllClients();
            } catch (Throwable ignored) {
            }
        }
    }

    /**
     * Sync all active battles to players in all loaded worlds (server-side).
     */
    public static void syncToAllClients() {
        for (World world : net.minecraftforge.common.DimensionManager.getWorlds()) {
            if (world == null || world.isRemote) continue;

            DeployedBattleManager manager = DeployedBattleManager.get(world);
            if (manager == null) continue;

            List<DeployedBattle> battles = manager.getActiveBattles();
            if (battles.isEmpty()) continue;

            for (EntityPlayerMP player : world.getPlayers(EntityPlayerMP.class, p -> true)) {
                syncToPlayerInternal(player, battles);
            }
        }
    }

    /**
     * Force sync to a specific player (e.g., when they open the map).
     */
    public static void syncToPlayer(EntityPlayerMP player) {
        if (player == null || player.world == null || player.world.isRemote) return;

        DeployedBattleManager manager = DeployedBattleManager.get(player.world);
        if (manager == null) return;

        List<DeployedBattle> battles = manager.getActiveBattles();
        syncToPlayerInternal(player, battles);
    }

    private static void syncToPlayerInternal(EntityPlayerMP player, List<DeployedBattle> battles) {
        if (player == null || battles == null) return;

        List<PacketDeployedBattlesSync.DeployedBattleData> dataList = new ArrayList<>();
        UUID playerUuid = player.getUniqueID();

        for (DeployedBattle battle : battles) {
            PacketDeployedBattlesSync.DeployedBattleData data = new PacketDeployedBattlesSync.DeployedBattleData();
            data.id = battle.getId().toString();
            data.directorId = battle.getDirectorId();
            data.ownerName = battle.getOwnerName();
            data.worldX = battle.getX();
            data.worldZ = battle.getZ();
            data.triggered = battle.isTriggered();
            data.isOwner = battle.getOwnerUuid().equals(playerUuid);

            String siteName = battle.getBattleSiteName();
            if (siteName != null && !siteName.trim().isEmpty()) {
                data.displayName = siteName;
            } else {
                BattleDirectorEntry entry = BattleDirectorRegistry.get(battle.getDirectorId());
                data.displayName = entry != null ? entry.getDisplayName() : battle.getDirectorId();
            }

            dataList.add(data);
        }

        TacticalWarMapNetwork.sendTo(new PacketDeployedBattlesSync(dataList), player);
    }
}
