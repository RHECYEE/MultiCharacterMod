package studio.ERM.war.map.net;

import io.netty.buffer.ByteBuf;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.nbt.NBTTagList;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.text.TextComponentString;
import net.minecraft.util.text.TextFormatting;
import net.minecraft.world.WorldServer;
import net.minecraftforge.fml.common.network.simpleimpl.IMessage;
import net.minecraftforge.fml.common.network.simpleimpl.IMessageHandler;
import net.minecraftforge.fml.common.network.simpleimpl.MessageContext;
import studio.ERM.strategic.StrategicMapData;
import studio.ERM.strategic.StrategicReinforcement;
import studio.ERM.strategic.defense.ContainerRecruit;
import studio.ERM.strategic.defense.DefenseMarker;
import studio.ERM.strategic.defense.DefensePlanData;
import studio.ERM.war.config.WarLevelsConfig;
import studio.ERM.war.world.WarWorldData;

/**
 * PHASE 2 — confirm a recruitment contract from the open {@link ContainerRecruit}: charge the
 * Command-Buck fee, CONSUME the loadout gear, and dispatch a {@link StrategicReinforcement} that
 * MARCHES from far away to the player's rally point ("Contract accepted... arriving in ~N minutes").
 * Also carries the tiny OPEN request (openGui must originate server-side).
 */
public class C2SRecruitConfirm implements IMessage {

    private boolean openOnly = false;
    private boolean mercenary = true;

    public C2SRecruitConfirm() {}

    public C2SRecruitConfirm(boolean mercenary) {
        this.mercenary = mercenary;
    }

    public static C2SRecruitConfirm openRequest() {
        C2SRecruitConfirm p = new C2SRecruitConfirm();
        p.openOnly = true;
        return p;
    }

    @Override
    public void fromBytes(ByteBuf buf) {
        openOnly = buf.readBoolean();
        mercenary = buf.readBoolean();
    }

    @Override
    public void toBytes(ByteBuf buf) {
        buf.writeBoolean(openOnly);
        buf.writeBoolean(mercenary);
    }

    public static class Handler implements IMessageHandler<C2SRecruitConfirm, IMessage> {
        @Override
        public IMessage onMessage(C2SRecruitConfirm msg, MessageContext ctx) {
            EntityPlayerMP player = ctx.getServerHandler().player;
            ((WorldServer) player.world).addScheduledTask(() -> {
                if (msg.openOnly) {
                    player.openGui(studio.ERM.EpochRunnerMod.instance,
                            studio.ERM.strategic.defense.ErmGuiHandler.GUI_RECRUIT,
                            player.world, 0, 0, 0);
                    return;
                }
                if (!(player.openContainer instanceof ContainerRecruit)) return;
                ContainerRecruit c = (ContainerRecruit) player.openContainer;
                WorldServer world = (WorldServer) player.world;

                int cost = msg.mercenary ? WarLevelsConfig.recruitMercCost() : WarLevelsConfig.recruitPermanentCost();
                WarWorldData data = WarWorldData.get(world);
                WarWorldData.FactionStats stats = data.getStats(player.getUniqueID().toString());
                if (!player.isCreative() && stats.commandPoints < cost) {
                    player.sendMessage(new TextComponentString(TextFormatting.RED
                            + "Not enough Command Points (need " + cost + ", have " + stats.commandPoints + ")."));
                    return;
                }
                if (!player.isCreative()) { stats.commandPoints -= cost; data.markDirty(); }

                // CONSUME the loadout the player built (these items ARE the recruit's kit now).
                NBTTagList gear = new NBTTagList();
                for (int i = 0; i < c.loadout.getSizeInventory(); i++) {
                    ItemStack st = c.loadout.removeStackFromSlot(i);
                    gear.appendTag(st.isEmpty() ? new NBTTagCompound() : st.writeToNBT(new NBTTagCompound()));
                }

                // Rally = the plan's RALLY marker if present, else where the player stands.
                DefensePlanData plan = DefensePlanData.get(world);
                BlockPos rally = player.getPosition();
                for (DefenseMarker m : plan.markers) {
                    if (m.type == DefenseMarker.RALLY && !m.points.isEmpty()) { rally = m.points.get(0); break; }
                }

                // The march begins far away (config): the player can literally watch them arrive.
                double ang = world.rand.nextDouble() * Math.PI * 2;
                int dist = WarLevelsConfig.recruitArrivalDistance();
                BlockPos origin = new BlockPos(
                        rally.getX() + (int) Math.round(Math.cos(ang) * dist), 0,
                        rally.getZ() + (int) Math.round(Math.sin(ang) * dist));

                StrategicReinforcement r = new StrategicReinforcement();
                r.contract = msg.mercenary ? 1 : 0;
                r.gear = gear;
                r.route.add(origin);
                r.route.add(new BlockPos(rally.getX(), 0, rally.getZ()));
                r.routeIndex = 1; // marching leg: origin -> rally
                r.x = origin.getX() + 0.5;
                r.z = origin.getZ() + 0.5;
                StrategicMapData.get(world).add(r);

                int etaMin = Math.max(1, (int) Math.ceil(dist / r.speed / 60.0));
                player.closeContainer();
                player.sendMessage(new TextComponentString(TextFormatting.GOLD + "Contract accepted. "
                        + (msg.mercenary ? "Mercenary convoy" : "Recruit column")
                        + " arriving in approximately " + etaMin + " minute" + (etaMin == 1 ? "" : "s") + "."
                        + TextFormatting.GRAY + "  (-" + cost + " CB)"));
                studio.ERM.EpochRunnerMod.logger.info("[Recruit] contract " + (msg.mercenary ? "MERC" : "PERM")
                        + " by " + player.getName() + " origin=" + origin + " rally=" + rally + " eta=" + etaMin + "m");
            });
            return null;
        }
    }
}
