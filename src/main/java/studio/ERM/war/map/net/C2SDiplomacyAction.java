package studio.ERM.war.map.net;

import io.netty.buffer.ByteBuf;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.util.math.ChunkPos;
import net.minecraft.util.text.TextComponentString;
import net.minecraft.util.text.TextFormatting;
import net.minecraft.world.WorldServer;
import net.minecraftforge.fml.common.network.ByteBufUtils;
import net.minecraftforge.fml.common.network.simpleimpl.IMessage;
import net.minecraftforge.fml.common.network.simpleimpl.IMessageHandler;
import net.minecraftforge.fml.common.network.simpleimpl.MessageContext;
import studio.ERM.strategic.nation.NationDiplomacyData;
import studio.ERM.strategic.nation.NationStateData;
import studio.ERM.war.world.WarWorldData;

import java.util.Map;

/**
 * DIPLOMACY with a Nation State, from the claim-map dropdown. Three simple actions:
 *   SEND ENVOY            — open relations (free).
 *   BUY CLAIM             — pay to cede the nation's territory (its chunks become NEUTRAL to claim).
 *   PURCHASE TRADE AGREEMENT — pay to unlock commerce (becomes a Trade Partner).
 */
public class C2SDiplomacyAction implements IMessage {

    public static final int SEND_ENVOY = 0, BUY_CLAIM = 1, BUY_TRADE = 2;

    // Command-Buck costs (flat for now).
    private static final int CLAIM_COST = 300;
    private static final int TRADE_COST = 250;

    private int action;
    private String nation = "";

    public C2SDiplomacyAction() {}

    public C2SDiplomacyAction(int action, String nation) {
        this.action = action; this.nation = nation == null ? "" : nation;
    }

    @Override
    public void fromBytes(ByteBuf buf) {
        action = buf.readByte();
        nation = ByteBufUtils.readUTF8String(buf);
    }

    @Override
    public void toBytes(ByteBuf buf) {
        buf.writeByte(action);
        ByteBufUtils.writeUTF8String(buf, nation);
    }

    public static class Handler implements IMessageHandler<C2SDiplomacyAction, IMessage> {
        @Override
        public IMessage onMessage(C2SDiplomacyAction msg, MessageContext ctx) {
            EntityPlayerMP player = ctx.getServerHandler().player;
            ((WorldServer) player.world).addScheduledTask(() -> handle(msg, player));
            return null;
        }

        private static void handle(C2SDiplomacyAction msg, EntityPlayerMP player) {
            WorldServer world = (WorldServer) player.world;
            NationStateData nations = NationStateData.get(world);
            boolean exists = false;
            for (NationStateData.Nation n : nations.nations) if (n.name.equals(msg.nation)) { exists = true; break; }
            if (!exists) { msg(player, TextFormatting.RED + "That nation is no longer here."); return; }

            NationDiplomacyData diplo = NationDiplomacyData.get(world);
            NationDiplomacyData.Relation rel = diplo.forNation(msg.nation);

            switch (msg.action) {
                case SEND_ENVOY:
                    if (rel.status >= NationDiplomacyData.CONTACTED) {
                        msg(player, TextFormatting.YELLOW + "You are already in contact with " + msg.nation + ".");
                    } else {
                        rel.status = NationDiplomacyData.CONTACTED;
                        diplo.markDirty();
                        msg(player, TextFormatting.GREEN + "Envoy sent to " + msg.nation
                                + " — diplomatic relations are open.");
                    }
                    break;

                case BUY_CLAIM: {
                    if (rel.claimBought) { msg(player, TextFormatting.YELLOW + msg.nation
                            + "'s claim is already yours to settle."); break; }
                    if (!charge(world, player, CLAIM_COST)) break;
                    // Cede the nation's territory: its chunks become NEUTRAL (the player may now claim them).
                    WarWorldData war = WarWorldData.get(world);
                    String tag = "NATION:" + msg.nation;
                    int ceded = 0;
                    for (Map.Entry<ChunkPos, String> e : new java.util.HashMap<>(war.getAllChunkOwners()).entrySet()) {
                        if (tag.equals(e.getValue())) { war.setOwner(e.getKey(), "NEUTRAL"); ceded++; }
                    }
                    rel.claimBought = true;
                    if (rel.status < NationDiplomacyData.CONTACTED) rel.status = NationDiplomacyData.CONTACTED;
                    diplo.markDirty();
                    msg(player, TextFormatting.GOLD + "Bought " + msg.nation + "'s claim (-" + CLAIM_COST
                            + " CB) — " + ceded + " chunk(s) ceded. Reopen the map to claim them.");
                    break;
                }

                case BUY_TRADE:
                    if (rel.tradeAgreement) { msg(player, TextFormatting.YELLOW
                            + "You already have a trade agreement with " + msg.nation + "."); break; }
                    if (!charge(world, player, TRADE_COST)) break;
                    rel.tradeAgreement = true;
                    rel.status = NationDiplomacyData.TRADE_PARTNER;
                    diplo.markDirty();
                    msg(player, TextFormatting.GOLD + "Trade agreement signed with " + msg.nation
                            + " (-" + TRADE_COST + " CB) — commerce is open.");
                    break;
                default: break;
            }
        }

        private static boolean charge(WorldServer world, EntityPlayerMP player, int cost) {
            if (player.isCreative()) return true;
            WarWorldData data = WarWorldData.get(world);
            WarWorldData.FactionStats stats = data.getStats(player.getUniqueID().toString());
            if (stats.commandPoints < cost) {
                msg(player, TextFormatting.RED + "Not enough Command Bucks (need " + cost
                        + ", have " + stats.commandPoints + ").");
                return false;
            }
            stats.commandPoints -= cost;
            data.markDirty();
            return true;
        }

        private static void msg(EntityPlayerMP p, String s) {
            p.sendMessage(new TextComponentString(s));
        }
    }
}
