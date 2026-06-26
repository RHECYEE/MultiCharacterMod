package studio.ERM.war.map.net;

import io.netty.buffer.ByteBuf;
import net.minecraft.client.Minecraft;
import net.minecraft.util.math.ChunkPos;
import net.minecraftforge.fml.common.network.ByteBufUtils;
import net.minecraftforge.fml.common.network.simpleimpl.IMessage;
import net.minecraftforge.fml.common.network.simpleimpl.IMessageHandler;
import net.minecraftforge.fml.common.network.simpleimpl.MessageContext;
import studio.ERM.war.map.client.ClientTerritoryCache;
import studio.ERM.war.map.client.WarMapClientStats;

import java.util.HashMap;
import java.util.Map;

/**
 * Server -> Client: Full snapshot of territory ownership PLUS the player's live
 * war HUD stats (command points, air defense, era, tension, battle-active).
 *
 * Sent when the map opens and after any batch claim/unclaim. The stats are
 * piggybacked here (rather than on a separate intel packet) so that the map's
 * sidebar values are always consistent with the territory it is showing, and
 * arrive on exactly the same triggers that already work. This is what populates
 * {@link WarMapClientStats}; without it the sidebar shows "?" forever.
 */
public class S2CTerritorySync implements IMessage {

    private Map<ChunkPos, String> territory = new HashMap<>();

    // Piggybacked HUD stats (see WarMapClientStats.applyFromSnapshot)
    private int commandPoints = 0;
    private int airDefense = 0;
    private int era = 1;
    private int tension = 0;
    private boolean battleActive = false;

    public S2CTerritorySync() {}

    /** Territory-only convenience ctor; stats default to neutral values. */
    public S2CTerritorySync(Map<ChunkPos, String> territory) {
        this(territory, 0, 0, 1, 0, false);
    }

    public S2CTerritorySync(Map<ChunkPos, String> territory,
                            int commandPoints, int airDefense, int era, int tension, boolean battleActive) {
        this.territory = territory != null ? territory : new HashMap<>();
        this.commandPoints = commandPoints;
        this.airDefense = airDefense;
        this.era = era;
        this.tension = tension;
        this.battleActive = battleActive;
    }

    @Override
    public void fromBytes(ByteBuf buf) {
        territory.clear();
        int count = buf.readInt();
        for (int i = 0; i < count; i++) {
            int cx = buf.readInt();
            int cz = buf.readInt();
            String owner = ByteBufUtils.readUTF8String(buf);
            territory.put(new ChunkPos(cx, cz), owner);
        }
        commandPoints = buf.readInt();
        airDefense = buf.readInt();
        era = buf.readInt();
        tension = buf.readInt();
        battleActive = buf.readBoolean();
    }

    @Override
    public void toBytes(ByteBuf buf) {
        buf.writeInt(territory.size());
        for (Map.Entry<ChunkPos, String> entry : territory.entrySet()) {
            buf.writeInt(entry.getKey().x);
            buf.writeInt(entry.getKey().z);
            ByteBufUtils.writeUTF8String(buf, entry.getValue());
        }
        buf.writeInt(commandPoints);
        buf.writeInt(airDefense);
        buf.writeInt(era);
        buf.writeInt(tension);
        buf.writeBoolean(battleActive);
    }

    public static class Handler implements IMessageHandler<S2CTerritorySync, IMessage> {
        @Override
        public IMessage onMessage(S2CTerritorySync message, MessageContext ctx) {
            // Real-logger trace (client side): confirms the snapshot completed the round-trip and
            // reached the client cache. If the server logs "sent N chunks" but this never appears,
            // the S2C packet is being lost (channel/side mismatch).
            studio.ERM.EpochRunnerMod.logger.info("[ERM-Map] CLIENT: received S2CTerritorySync -> "
                    + message.territory.size() + " chunks, cp=" + message.commandPoints);
            Minecraft.getMinecraft().addScheduledTask(() -> {
                ClientTerritoryCache.updateFromServer(message.territory);
                WarMapClientStats.applyFromSnapshot(
                        message.commandPoints,
                        message.airDefense,
                        message.era,
                        message.tension,
                        message.battleActive);
            });
            return null;
        }
    }
}
