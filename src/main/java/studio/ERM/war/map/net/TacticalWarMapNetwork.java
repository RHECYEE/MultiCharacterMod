package studio.ERM.war.map.net;

import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraftforge.fml.common.network.simpleimpl.IMessage;
import net.minecraftforge.fml.relauncher.Side;
import studio.ERM.EpochRunnerMod;

/**
 * Registers all war-map network packets on the shared EpochRunnerMod.network channel.
 * Call {@link #init()} once during mod preInit.
 */
public final class TacticalWarMapNetwork {

    private static int nextId = 100; // offset to avoid collisions with other packet IDs

    private TacticalWarMapNetwork() {}

    public static void init() {
        // Client -> Server
        EpochRunnerMod.network.registerMessage(
                PacketDeployBattle.Handler.class, PacketDeployBattle.class, nextId++, Side.SERVER);
        EpochRunnerMod.network.registerMessage(
                PacketSurrender.Handler.class, PacketSurrender.class, nextId++, Side.SERVER);
        EpochRunnerMod.network.registerMessage(
                C2SPacketBatchClaim.Handler.class, C2SPacketBatchClaim.class, nextId++, Side.SERVER);
        EpochRunnerMod.network.registerMessage(
                C2SRequestTerritorySync.Handler.class, C2SRequestTerritorySync.class, nextId++, Side.SERVER);
        EpochRunnerMod.network.registerMessage(
                C2SDefensePlanEdit.Handler.class, C2SDefensePlanEdit.class, nextId++, Side.SERVER);
        EpochRunnerMod.network.registerMessage(
                C2SRecruitConfirm.Handler.class, C2SRecruitConfirm.class, nextId++, Side.SERVER);
        EpochRunnerMod.network.registerMessage(
                C2SCivilPlanEdit.Handler.class, C2SCivilPlanEdit.class, nextId++, Side.SERVER);
        EpochRunnerMod.network.registerMessage(
                C2SDistrictDepotEdit.Handler.class, C2SDistrictDepotEdit.class, nextId++, Side.SERVER);
        EpochRunnerMod.network.registerMessage(
                C2SStrategicMission.Handler.class, C2SStrategicMission.class, nextId++, Side.SERVER);
        EpochRunnerMod.network.registerMessage(
                C2STradeAction.Handler.class, C2STradeAction.class, nextId++, Side.SERVER);

        // Server -> Client
        EpochRunnerMod.network.registerMessage(
                PacketDeployedBattlesSync.Handler.class, PacketDeployedBattlesSync.class, nextId++, Side.CLIENT);
        EpochRunnerMod.network.registerMessage(
                S2CTerritorySync.Handler.class, S2CTerritorySync.class, nextId++, Side.CLIENT);
        EpochRunnerMod.network.registerMessage(
                S2CStrategicSync.Handler.class, S2CStrategicSync.class, nextId++, Side.CLIENT);
        EpochRunnerMod.network.registerMessage(
                S2CDefensePlanSync.Handler.class, S2CDefensePlanSync.class, nextId++, Side.CLIENT);
        EpochRunnerMod.network.registerMessage(
                S2CCivilPlanSync.Handler.class, S2CCivilPlanSync.class, nextId++, Side.CLIENT);
        EpochRunnerMod.network.registerMessage(
                S2CTradeSync.Handler.class, S2CTradeSync.class, nextId++, Side.CLIENT);
    }

    public static void sendToServer(IMessage msg) {
        EpochRunnerMod.network.sendToServer(msg);
    }

    public static void sendTo(IMessage msg, EntityPlayerMP player) {
        EpochRunnerMod.network.sendTo(msg, player);
    }

    public static void sendToAll(IMessage msg) {
        EpochRunnerMod.network.sendToAll(msg);
    }
}
