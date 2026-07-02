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
 * Command-Buck fee, CONSUME the loadout, dispatch a {@link StrategicReinforcement} that MARCHES (or
 * DRIVES, for vehicle contracts) from far away to the rally point. Also carries the tiny OPEN request.
 * Kinds: 0 permanent squad, 1 mercenary squad, 2 permanent VEHICLE (Flan item in the Hand slot; cost
 * from the per-ShortName config map; crew gear from the rest of the loadout).
 */
public class C2SRecruitConfirm implements IMessage {

    private boolean openOnly = false;
    private int kind = 1;
    private int count = 1;

    public C2SRecruitConfirm() {}

    public C2SRecruitConfirm(int kind, int count) {
        this.kind = kind;
        this.count = count;
    }

    public static C2SRecruitConfirm openRequest() {
        C2SRecruitConfirm p = new C2SRecruitConfirm();
        p.openOnly = true;
        return p;
    }

    @Override
    public void fromBytes(ByteBuf buf) {
        openOnly = buf.readBoolean();
        kind = buf.readByte();
        count = Math.max(1, Math.min(16, buf.readByte()));
    }

    @Override
    public void toBytes(ByteBuf buf) {
        buf.writeBoolean(openOnly);
        buf.writeByte(kind);
        buf.writeByte(count);
    }

    public static class Handler implements IMessageHandler<C2SRecruitConfirm, IMessage> {
        @Override
        public IMessage onMessage(C2SRecruitConfirm msg, MessageContext ctx) {
            EntityPlayerMP player = ctx.getServerHandler().player;
            ((WorldServer) player.world).addScheduledTask(() -> handle(msg, player));
            return null;
        }

        private static void handle(C2SRecruitConfirm msg, EntityPlayerMP player) {
            if (msg.openOnly) {
                player.openGui(studio.ERM.EpochRunnerMod.instance,
                        studio.ERM.strategic.defense.ErmGuiHandler.GUI_RECRUIT,
                        player.world, 0, 0, 0);
                return;
            }
            if (!(player.openContainer instanceof ContainerRecruit)) return;
            ContainerRecruit c = (ContainerRecruit) player.openContainer;
            WorldServer world = (WorldServer) player.world;

            int kind = Math.max(0, Math.min(2, msg.kind));
            int squad = (kind == 2) ? 1 : Math.max(1, Math.min(8, msg.count));

            // VEHICLE contracts require a Flan vehicle item in the Hand slot; cost keys off its ShortName.
            String vehicleShortName = "";
            int unitCost;
            if (kind == 2) {
                vehicleShortName = StrategicReinforcement.flanShortNameOf(c.loadout.getStackInSlot(0));
                if (vehicleShortName.isEmpty()) {
                    player.sendMessage(new TextComponentString(TextFormatting.RED
                            + "Vehicle contract needs a Flan vehicle item in the Hand slot."));
                    return;
                }
                unitCost = WarLevelsConfig.recruitVehicleCost(vehicleShortName);
            } else {
                unitCost = (kind == 0) ? WarLevelsConfig.recruitPermanentCost() : WarLevelsConfig.recruitMercCost();
            }
            int cost = unitCost * squad;

            WarWorldData data = WarWorldData.get(world);
            WarWorldData.FactionStats stats = data.getStats(player.getUniqueID().toString());
            if (!player.isCreative() && stats.commandPoints < cost) {
                player.sendMessage(new TextComponentString(TextFormatting.RED
                        + "Not enough Command Points (need " + cost + ", have " + stats.commandPoints + ")."));
                return;
            }
            if (!player.isCreative()) { stats.commandPoints -= cost; data.markDirty(); }

            // The loadout is the KIT PATTERN: the Command Bucks pay for soldier + gear copies, and the
            // original items STAY in the slots so the player can hammer CONFIRM repeatedly for more
            // squads (they get everything back on close). Recruited copies NEVER drop on death, so
            // there is no duplication economy. The one exception: a VEHICLE contract consumes the
            // actual vehicle item -- that specific vehicle is what gets delivered.
            NBTTagList gear = new NBTTagList();
            for (int i = 0; i < c.loadout.getSizeInventory(); i++) {
                ItemStack st = c.loadout.getStackInSlot(i);
                gear.appendTag(st.isEmpty() ? new NBTTagCompound() : st.writeToNBT(new NBTTagCompound()));
            }
            if (kind == 2) c.loadout.decrStackSize(0, 1);

            // Rally = the plan's RALLY marker if present, else where the player stands.
            DefensePlanData plan = DefensePlanData.get(world);
            BlockPos rally = player.getPosition();
            for (DefenseMarker m : plan.markers) {
                if (m.type == DefenseMarker.RALLY && !m.points.isEmpty()) { rally = m.points.get(0); break; }
            }

            // The delivery begins far away: the player can literally watch it arrive.
            double ang = world.rand.nextDouble() * Math.PI * 2;
            int dist = WarLevelsConfig.recruitArrivalDistance();
            BlockPos origin = new BlockPos(
                    rally.getX() + (int) Math.round(Math.cos(ang) * dist), 0,
                    rally.getZ() + (int) Math.round(Math.sin(ang) * dist));

            StrategicReinforcement r = new StrategicReinforcement();
            r.contract = kind;
            r.gear = gear;
            r.strength = squad;
            r.vehicleShortName = vehicleShortName;
            if (kind == 2) r.speed = 5.5; // vehicles drive in faster than boots march
            r.route.add(origin);
            r.route.add(new BlockPos(rally.getX(), 0, rally.getZ()));
            r.routeIndex = 1; // marching leg: origin -> rally
            r.x = origin.getX() + 0.5;
            r.z = origin.getZ() + 0.5;
            StrategicMapData.get(world).add(r);

            int etaMin = Math.max(1, (int) Math.ceil(dist / r.speed / 60.0));
            // Screen stays OPEN: hammer CONFIRM for more squads (each confirm = a new contract).
            String what = (kind == 2) ? ("Vehicle delivery (" + vehicleShortName + ")")
                    : (kind == 0 ? "Recruit column" : "Mercenary convoy") + (squad > 1 ? " x" + squad : "");
            player.sendMessage(new TextComponentString(TextFormatting.GOLD + "Contract accepted. "
                    + what + " arriving in approximately " + etaMin + " minute" + (etaMin == 1 ? "" : "s") + "."
                    + TextFormatting.GRAY + "  (-" + cost + " CB)"));
            studio.ERM.EpochRunnerMod.logger.info("[Recruit] contract kind=" + kind + " squad=" + squad
                    + (vehicleShortName.isEmpty() ? "" : " vehicle=" + vehicleShortName)
                    + " by " + player.getName() + " origin=" + origin + " rally=" + rally + " eta=" + etaMin + "m");
        }
    }
}
