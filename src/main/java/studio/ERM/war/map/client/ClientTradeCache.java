package studio.ERM.war.map.client;

import studio.ERM.war.map.net.S2CTradeSync;

/** Client-side latest Trade Depot snapshot, read by GuiTradeDepot. */
public final class ClientTradeCache {

    private static volatile S2CTradeSync latest = new S2CTradeSync();

    private ClientTradeCache() {}

    public static void update(S2CTradeSync sync) {
        latest = sync != null ? sync : new S2CTradeSync();
    }

    public static S2CTradeSync get() {
        return latest;
    }
}
