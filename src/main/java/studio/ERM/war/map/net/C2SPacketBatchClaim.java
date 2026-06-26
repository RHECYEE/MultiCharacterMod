package studio.ERM.war.map.net;

import io.netty.buffer.ByteBuf;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.util.math.ChunkPos;
import net.minecraft.util.text.TextComponentString;
import net.minecraft.util.text.TextFormatting;
import net.minecraftforge.fml.common.network.simpleimpl.IMessage;
import net.minecraftforge.fml.common.network.simpleimpl.IMessageHandler;
import net.minecraftforge.fml.common.network.simpleimpl.MessageContext;
import studio.ERM.war.WarClaimHandler;

import java.util.ArrayList;
import java.util.List;

/**
 * Client -> Server: Batch claim or unclaim a set of chunks from the tactical map.
 * Sent when player shift-drags (claim) or shift-right-clicks (unclaim) on the map.
 */
public class C2SPacketBatchClaim implements IMessage {

    private static final int MAX_CHUNKS_PER_PACKET = 256;

    private boolean unclaim; // false = claim, true = unclaim
    private List<ChunkPos> chunks = new ArrayList<>();

    public C2SPacketBatchClaim() {}

    public C2SPacketBatchClaim(boolean unclaim, List<ChunkPos> chunks) {
        this.unclaim = unclaim;
        this.chunks = chunks != null ? chunks : new ArrayList<>();
    }

    @Override
    public void fromBytes(ByteBuf buf) {
        unclaim = buf.readBoolean();
        int count = buf.readInt();
        // Clamp to prevent abuse
        count = Math.min(count, MAX_CHUNKS_PER_PACKET);
        chunks.clear();
        for (int i = 0; i < count; i++) {
            int cx = buf.readInt();
            int cz = buf.readInt();
            chunks.add(new ChunkPos(cx, cz));
        }
    }

    @Override
    public void toBytes(ByteBuf buf) {
        buf.writeBoolean(unclaim);
        int count = Math.min(chunks.size(), MAX_CHUNKS_PER_PACKET);
        buf.writeInt(count);
        for (int i = 0; i < count; i++) {
            ChunkPos cp = chunks.get(i);
            buf.writeInt(cp.x);
            buf.writeInt(cp.z);
        }
    }

    public static class Handler implements IMessageHandler<C2SPacketBatchClaim, IMessage> {
        @Override
        public IMessage onMessage(C2SPacketBatchClaim message, MessageContext ctx) {
            EntityPlayerMP player = ctx.getServerHandler().player;
            // Real-logger trace: proves a Shift+drag claim/unclaim packet actually reached the
            // server. If the user reports "claiming does nothing" and this line is ABSENT, the C2S
            // packet never arrived (most likely the user left-dragged without holding Shift, which
            // only pans -- claim REQUIRES Shift+drag).
            studio.ERM.EpochRunnerMod.logger.info("[ERM-Map] SERVER: C2SPacketBatchClaim from "
                    + player.getName() + " unclaim=" + message.unclaim + " chunks=" + message.chunks.size());
            player.getServerWorld().addScheduledTask(() -> {
                if (message.unclaim) {
                    int count = WarClaimHandler.batchUnclaim(player, message.chunks);
                    if (count > 0) {
                        player.sendMessage(new TextComponentString(
                                TextFormatting.YELLOW + "Unclaimed " + count + " chunks."));
                    }
                } else {
                    WarClaimHandler.BatchClaimResult result = WarClaimHandler.batchClaim(player, message.chunks);
                    if (result.claimed > 0) {
                        player.sendMessage(new TextComponentString(
                                TextFormatting.GREEN + "Claimed " + result.claimed + " chunks. ("
                                        + TextFormatting.GOLD + result.totalCost + " CP" + TextFormatting.GREEN + ")"));
                    }
                    if (result.failed > 0) {
                        if (result.failedCp > 0) {
                            // Most common cause: not enough Command Points. Say so explicitly and
                            // tell them how to get more, instead of a mystifying "could not claim".
                            player.sendMessage(new TextComponentString(
                                    TextFormatting.RED + "" + result.failedCp + " chunk(s) need "
                                            + WarClaimHandler.CLAIM_COST_BASE + " CP each — you have "
                                            + TextFormatting.GOLD + result.availableCp + " CP"
                                            + TextFormatting.RED + ". Earn CP from districts or use "
                                            + TextFormatting.YELLOW + "/war cp <amount>" + TextFormatting.RED + "."));
                        }
                        int otherFailed = result.failed - result.failedCp;
                        if (otherFailed > 0) {
                            player.sendMessage(new TextComponentString(
                                    TextFormatting.RED + "" + otherFailed
                                            + " chunk(s) could not be claimed (rival or another player's land)."));
                        }
                    }
                }
                // Sync territory back to the player
                WarClaimHandler.syncTerritoryToPlayer(player);
            });
            return null;
        }
    }
}
