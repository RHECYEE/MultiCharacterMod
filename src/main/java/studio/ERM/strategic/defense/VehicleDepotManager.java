package studio.ERM.strategic.defense;

import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.item.ItemStack;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.text.TextComponentString;
import net.minecraft.util.text.TextFormatting;
import net.minecraft.world.WorldServer;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import net.minecraftforge.fml.common.gameevent.TickEvent;
import studio.ERM.EpochRunnerMod;
import studio.ERM.strategic.StrategicReinforcement;
import studio.ERM.strategic.civil.CivilMarker;
import studio.ERM.strategic.civil.CivilPlanData;
import studio.ERM.strategic.civil.DistrictRegistry;
import studio.ERM.war.districts.TileEntityDistrictMarker;
import studio.ERM.war.vehicle.EntityAIPilot;

import java.util.HashMap;
import java.util.Map;

/**
 * VEHICLE DEPOT MOUNTING — the AI uses Flan vehicles from STOCK: put a Flan vehicle/plane ITEM in
 * an ARMORY district's depot and the garrison "checks one out" — the item is consumed, the vehicle
 * appears at the depot, and a militia crew (EntityAIPilot, the player's side) mounts it and rallies
 * at the district. Destroyed vehicles are simply replaced from remaining stock on a later pass —
 * the depot is the motor pool.
 */
public final class VehicleDepotManager {

    private static final int PASS_INTERVAL = 600;      // 30s motor-pool check
    private static final int MAX_LIVE_PER_DISTRICT = 2;
    private static final double COUNT_RADIUS = 96.0;

    private VehicleDepotManager() {}

    private static int tickCounter = 0;
    /** district uid -> last spawn tick (small cooldown so a full depot doesn't dump its garage at once). */
    private static final Map<Integer, Long> LAST_SPAWN = new HashMap<>();

    @SubscribeEvent
    public static void onWorldTick(TickEvent.WorldTickEvent e) {
        if (e.phase != TickEvent.Phase.END || e.side.isClient() || !(e.world instanceof WorldServer)) return;
        if (++tickCounter % PASS_INTERVAL != 0) return;
        WorldServer world = (WorldServer) e.world;
        try {
            run(world);
        } catch (Throwable t) {
            EpochRunnerMod.logger.error("[MotorPool] pass failed (guarded)", t);
        }
    }

    private static void run(WorldServer world) {
        long now = world.getTotalWorldTime();
        for (CivilMarker m : CivilPlanData.get(world).markers) {
            if (m.isRoad() || m.kind != CivilMarker.ARMORY || !m.hasDepot()) continue;
            if (!world.isBlockLoaded(m.depotPos, false)) continue;
            if (now - LAST_SPAWN.getOrDefault(m.uid, 0L) < 1200) continue; // 1 min between checkouts
            TileEntityDistrictMarker depot = DistrictRegistry.depotOf(world, m);
            if (depot == null) continue;

            // Garage cap: count live militia-crewed vehicles near the district.
            BlockPos c = m.center();
            int live = 0;
            for (EntityAIPilot p : world.getEntitiesWithinAABB(EntityAIPilot.class,
                    new net.minecraft.util.math.AxisAlignedBB(
                            c.getX() - COUNT_RADIUS, 0, c.getZ() - COUNT_RADIUS,
                            c.getX() + COUNT_RADIUS, 255, c.getZ() + COUNT_RADIUS))) {
                try { if (!p.isDead && "militia".equalsIgnoreCase(p.getMcmTeam())) live++; } catch (Throwable ignored) {}
            }
            if (live >= MAX_LIVE_PER_DISTRICT) continue;

            // Find a Flan VEHICLE/PLANE item in stock (guns are Flan items too — filter by item class).
            for (int slot = 0; slot < depot.depot.getSlots(); slot++) {
                ItemStack st = depot.depot.getStackInSlot(slot);
                if (st.isEmpty()) continue;
                String cls = st.getItem().getClass().getName().toLowerCase();
                if (!cls.contains("itemvehicle") && !cls.contains("itemplane")) continue;
                String shortName = StrategicReinforcement.flanShortNameOf(st);
                if (shortName.isEmpty()) continue;

                depot.depot.extractItem(slot, 1, false); // check it out of the motor pool
                try {
                    BlockPos at = world.getTopSolidOrLiquidBlock(
                            new BlockPos(m.depotPos.getX() + 3, 64, m.depotPos.getZ() + 3));
                    EntityAIPilot pilot = new EntityAIPilot(world);
                    pilot.setLocationAndAngles(at.getX() + 0.5, at.getY() + 1.0, at.getZ() + 0.5,
                            world.rand.nextFloat() * 360F, 0F);
                    pilot.setVehicleType(shortName);
                    pilot.setMcmTeam("militia");
                    pilot.setRallyPoint(c); // holds at the district; defense orders can retask it
                    world.spawnEntity(pilot);
                    LAST_SPAWN.put(m.uid, now);
                    EpochRunnerMod.logger.info("[MotorPool] armory #" + (m.uid & 0xFFFF)
                            + " mounted a '" + shortName + "' from depot stock");
                    for (EntityPlayer pl : world.playerEntities) {
                        pl.sendMessage(new TextComponentString(TextFormatting.GRAY
                                + "Your armory crew mounted a " + TextFormatting.AQUA + shortName
                                + TextFormatting.GRAY + " from the depot."));
                    }
                } catch (Throwable t) {
                    EpochRunnerMod.logger.warn("[MotorPool] mount failed for '" + shortName + "': " + t);
                }
                break; // one checkout per district per pass
            }
        }
    }
}
