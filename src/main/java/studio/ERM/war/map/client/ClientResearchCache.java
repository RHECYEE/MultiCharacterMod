package studio.ERM.war.map.client;

import studio.ERM.war.map.net.S2CResearchSync;

/** Client-side latest research-tree snapshot, read by GuiResearchTree. */
public final class ClientResearchCache {

    private static volatile S2CResearchSync latest = new S2CResearchSync();

    private ClientResearchCache() {}

    public static void update(S2CResearchSync sync) {
        latest = sync != null ? sync : new S2CResearchSync();
    }

    public static S2CResearchSync get() {
        return latest;
    }
}
