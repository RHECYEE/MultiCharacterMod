package studio.ERM.strategic.civil.evac;

import net.minecraft.entity.EntityCreature;
import net.minecraft.util.text.TextComponentString;
import net.minecraft.util.text.TextFormatting;
import net.minecraft.world.World;
import net.minecraft.world.WorldServer;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import net.minecraftforge.fml.common.gameevent.TickEvent;
import studio.ERM.EpochRunnerMod;
import studio.ERM.strategic.civil.DistrictWorkExecutor;

import java.util.HashSet;
import java.util.Set;

/**
 * THE EVACUATION ORDER — the civilian equivalent of the military Fallback. While a dimension is
 * evacuating, every player-owned CIVILIAN worker drops what it's doing and walks to the nearest
 * Evacuation Point (via the injected {@link EntityAIEvacuate} task, priority 0, which overrides work
 * + citizen-life through the movement mutex). MILITARY citizens (combat NPCs) are never injected, so
 * they ignore the order and keep following military commands. When the order is lifted the task goes
 * inert and normal jobs/logistics/schedules resume with no manual reassignment.
 */
public final class EvacuationManager {

    private EvacuationManager() {}

    private static final Set<Integer> EVACUATING = new HashSet<>();

    public static boolean isEvacuating(World world) {
        return world != null && EVACUATING.contains(world.provider.getDimension());
    }

    public static void setEvacuating(WorldServer world, boolean on) {
        int dim = world.provider.getDimension();
        boolean changed = on ? EVACUATING.add(dim) : EVACUATING.remove(dim);
        if (!changed) return;
        String msg = on ? TextFormatting.RED + "EVACUATION ORDERED — civilians are moving to safety."
                        : TextFormatting.GREEN + "All clear — civilians returning to their work.";
        for (net.minecraft.entity.player.EntityPlayer p : world.playerEntities) {
            p.sendMessage(new TextComponentString(msg));
        }
        EpochRunnerMod.logger.info("[Evac] dimension " + dim + (on ? " EVACUATING" : " all-clear"));
    }

    @SubscribeEvent
    public static void onWorldTick(TickEvent.WorldTickEvent e) {
        if (e.phase != TickEvent.Phase.END || e.side.isClient() || !(e.world instanceof WorldServer)) return;
        WorldServer world = (WorldServer) e.world;
        if (world.getTotalWorldTime() % 20 != 0 || !isEvacuating(world)) return;

        // Ensure every loaded player-owned civilian worker carries the evac task (combat NPCs excluded).
        for (Object o : world.loadedEntityList) {
            if (o instanceof EntityCreature && DistrictWorkExecutor.isAnyWorker((EntityCreature) o)) {
                ensureEvacTask((EntityCreature) o);
            }
        }
    }

    private static void ensureEvacTask(EntityCreature npc) {
        for (net.minecraft.entity.ai.EntityAITasks.EntityAITaskEntry en : npc.tasks.taskEntries) {
            if (en.action instanceof EntityAIEvacuate) return;
        }
        npc.tasks.addTask(0, new EntityAIEvacuate(npc));
    }
}
