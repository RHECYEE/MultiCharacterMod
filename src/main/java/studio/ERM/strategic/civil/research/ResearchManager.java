package studio.ERM.strategic.civil.research;

import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.text.TextComponentString;
import net.minecraft.util.text.TextFormatting;
import net.minecraft.world.WorldServer;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import net.minecraftforge.fml.common.gameevent.TickEvent;
import studio.ERM.EpochRunnerMod;
import studio.ERM.strategic.civil.CivilMarker;
import studio.ERM.strategic.civil.CivilPlanData;
import studio.ERM.strategic.civil.DistrictWorkExecutor;
import studio.ERM.war.config.ResearchConfig;
import studio.ERM.war.world.WarWorldData;

/**
 * RESEARCH LABOR — the Research district's assigned scientists generate research points that eat down
 * the player's currently-queued node; when a node's worker-time cost is met it's granted to AW2
 * (unlocking its recipes). Queueing a node costs Command Bucks up front (both knobs in
 * {@link ResearchConfig}; AW2's item requirements are scrubbed). One node at a time, per player.
 */
public final class ResearchManager {

    private ResearchManager() {}

    // ---- queue (from the GUI packet) ----

    public static void queue(WorldServer world, EntityPlayerMP player, String id) {
        ResearchTree.Node node = ResearchTree.byId(id);
        if (node == null) { msg(player, TextFormatting.RED + "Unknown research."); return; }
        String name = player.getName();
        if (ResearchTree.hasCompleted(world, name, id)) {
            msg(player, TextFormatting.YELLOW + node.name + " is already researched.");
            return;
        }
        if (!ResearchTree.depsMet(world, name, node)) {
            msg(player, TextFormatting.RED + "Prerequisites for " + node.name + " aren't complete.");
            return;
        }
        int cost = ResearchConfig.cbCost(id, node.aw2Time);
        WarWorldData data = WarWorldData.get(world);
        WarWorldData.FactionStats stats = data.getStats(player.getUniqueID().toString());
        if (!player.isCreative() && stats.commandPoints < cost) {
            msg(player, TextFormatting.RED + "Not enough Command Bucks to begin " + node.name
                    + " (need " + cost + ", have " + stats.commandPoints + ").");
            return;
        }
        if (!player.isCreative()) { stats.commandPoints -= cost; data.markDirty(); }

        ErmResearchData rd = ErmResearchData.get(world);
        ErmResearchData.Progress prog = rd.forPlayer(name);
        prog.current = id;
        prog.points = 0;
        rd.markDirty();
        int secs = ResearchConfig.employeeSeconds(id, node.aw2Time);
        msg(player, TextFormatting.GREEN + "Researching " + node.name + " (-" + cost + " CB). "
                + "Assign scientists to a Research district — " + secs + " worker-seconds to complete.");
    }

    // ---- accrual tick ----

    @SubscribeEvent
    public static void onWorldTick(TickEvent.WorldTickEvent e) {
        if (e.phase != TickEvent.Phase.END || e.side.isClient() || !(e.world instanceof WorldServer)) return;
        WorldServer world = (WorldServer) e.world;
        if (world.getTotalWorldTime() % 20 != 0) return; // once per second

        CivilPlanData plan = CivilPlanData.get(world);
        ErmResearchData rd = ErmResearchData.get(world);
        boolean dirty = false;

        for (CivilMarker m : plan.markers) {
            if (m.isRoad() || m.kind != CivilMarker.RESEARCH || !m.hasDepot()) continue;
            int workers = DistrictWorkExecutor.assignedTo(m.uid);
            if (workers <= 0) continue;
            BlockPos c = m.center();
            EntityPlayer p = world.getClosestPlayer(c.getX(), 64, c.getZ(), 256, false);
            if (p == null) continue;
            ErmResearchData.Progress prog = rd.forPlayer(p.getName());
            if (prog.current == null || prog.current.isEmpty()) continue;

            ResearchTree.Node node = ResearchTree.byId(prog.current);
            if (node == null) { prog.current = ""; dirty = true; continue; }
            prog.points += workers * ResearchConfig.pointsPerWorkerPerSecond();
            dirty = true;

            int need = ResearchConfig.employeeSeconds(prog.current, node.aw2Time);
            if (prog.points >= need) {
                ResearchTree.grant(world, p.getName(), prog.current);
                p.sendMessage(new TextComponentString(TextFormatting.GOLD
                        + "Research complete: " + node.name + "! Its recipes are unlocked."));
                EpochRunnerMod.logger.info("[Research] " + p.getName() + " completed " + prog.current);
                prog.current = "";
                prog.points = 0;
            }
        }
        if (dirty) rd.markDirty();
    }

    private static void msg(EntityPlayer p, String s) {
        if (p != null) p.sendMessage(new TextComponentString(s));
    }
}
