package studio.ERM.strategic.civil;

import net.minecraft.init.Items;
import net.minecraft.item.ItemStack;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.text.TextComponentString;
import net.minecraft.util.text.TextFormatting;
import net.minecraft.world.WorldServer;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import net.minecraftforge.fml.common.gameevent.TickEvent;
import net.minecraftforge.items.ItemHandlerHelper;
import studio.ERM.EpochRunnerMod;
import studio.ERM.war.districts.TileEntityDistrictMarker;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/**
 * STRATEGIC MISSIONS — parties dispatched from the Civilian map (right-click, Inspect mode) to a
 * target. They run on a timer and deliver their haul to the settlement. Transient by design (a
 * restart cancels in-flight missions); "for now the only mission is a Hunting Party."
 *
 * Hunting Party: dispatched to the cursor, returns after ~60s with 2-16 raw porkchops, delivered to
 * the nearest KITCHEN depot (else WAREHOUSE, else dropped at the launch player).
 */
public final class StrategicMissionManager {

    public static final int HUNTING_PARTY = 0;

    private static final int RETURN_TICKS = 60 * 20; // ~60s round trip
    private static final Random RNG = new Random();

    private StrategicMissionManager() {}

    private static final List<Mission> MISSIONS = new ArrayList<>();

    private static final class Mission {
        final int dim, type, x, z;
        final long completeTick;
        final java.util.UUID player;
        Mission(int dim, int type, int x, int z, long completeTick, java.util.UUID player) {
            this.dim = dim; this.type = type; this.x = x; this.z = z;
            this.completeTick = completeTick; this.player = player;
        }
    }

    /** Launch a mission from the map. Called on the server from the packet handler. */
    public static void launch(WorldServer world, int type, int x, int z, java.util.UUID player) {
        MISSIONS.add(new Mission(world.provider.getDimension(), type, x, z,
                world.getTotalWorldTime() + RETURN_TICKS, player));
        net.minecraft.entity.player.EntityPlayer p = world.getPlayerEntityByUUID(player);
        if (p != null) {
            p.sendMessage(new TextComponentString(TextFormatting.GREEN
                    + "Hunting party dispatched to " + x + ", " + z + ". Expected back in ~1 minute."));
        }
        EpochRunnerMod.logger.info("[Mission] hunting party launched to " + x + "," + z);
    }

    @SubscribeEvent
    public static void onWorldTick(TickEvent.WorldTickEvent e) {
        if (e.phase != TickEvent.Phase.END || e.side.isClient() || !(e.world instanceof WorldServer)) return;
        if (MISSIONS.isEmpty()) return;
        WorldServer world = (WorldServer) e.world;
        long now = world.getTotalWorldTime();
        for (Mission m : new ArrayList<>(MISSIONS)) {
            if (m.dim != world.provider.getDimension() || now < m.completeTick) continue;
            MISSIONS.remove(m);
            complete(world, m);
        }
    }

    private static void complete(WorldServer world, Mission m) {
        if (m.type == HUNTING_PARTY) {
            int count = 2 + RNG.nextInt(15); // 2-16 raw porkchops
            ItemStack haul = new ItemStack(Items.PORKCHOP, count);
            BlockPos where = deliver(world, haul);
            net.minecraft.entity.player.EntityPlayer p = world.getPlayerEntityByUUID(m.player);
            if (p != null) {
                String dest = where != null ? ("delivered to the depot at " + where.getX() + ", " + where.getZ())
                        : "dropped at your feet (no kitchen/warehouse depot found)";
                p.sendMessage(new TextComponentString(TextFormatting.GOLD
                        + "Hunting party returned with " + count + " raw porkchops — " + dest + "."));
                if (where == null) {
                    net.minecraft.inventory.InventoryHelper.spawnItemStack(world,
                            p.posX, p.posY, p.posZ, haul);
                }
            }
            EpochRunnerMod.logger.info("[Mission] hunting party returned with " + count + " porkchops");
        }
    }

    /** Insert the haul into the nearest KITCHEN depot, else WAREHOUSE; return the depot pos or null. */
    private static BlockPos deliver(WorldServer world, ItemStack haul) {
        CivilPlanData plan = CivilPlanData.get(world);
        for (int kind : new int[]{CivilMarker.KITCHEN, CivilMarker.WAREHOUSE}) {
            for (CivilMarker mk : plan.markers) {
                if (mk.kind != kind || !mk.hasDepot()) continue;
                TileEntityDistrictMarker depot = DistrictRegistry.depotOf(world, mk);
                if (depot == null) continue;
                ItemStack left = ItemHandlerHelper.insertItemStacked(depot.depot, haul.copy(), false);
                if (left.isEmpty()) return mk.depotPos;
            }
        }
        return null;
    }
}
