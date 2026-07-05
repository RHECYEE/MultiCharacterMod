package studio.ERM.strategic.civil;

import net.minecraft.block.BlockBed;
import net.minecraft.block.state.IBlockState;
import net.minecraft.entity.EntityCreature;
import net.minecraft.entity.EntityLivingBase;
import net.minecraft.util.EnumFacing;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;
import net.minecraft.world.WorldServer;
import net.minecraftforge.event.entity.living.LivingDeathEvent;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import net.minecraftforge.fml.common.gameevent.TickEvent;
import studio.ERM.EpochRunnerMod;
import studio.ERM.strategic.defense.Aw2Npc;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * RESIDENTIAL / BARRACKS BED AUTO-ASSIGNMENT. Housing finally has OWNERSHIP: every player-owned AW2
 * citizen gets a persistent bed of its class, so the settlement reads as a place people actually live.
 *
 *   scan     — bed HEAD blocks inside Residential + Barracks polygons (bounded, CivilStats-style)
 *   classify — Residential beds = civilian (AW2 "worker" and other non-combat citizens);
 *              Barracks beds = military (player-owned combat npcs)
 *   assign   — each unhoused loaded citizen claims the best free bed of its class: prefer INDOOR
 *              (solid roof), LIT (light level), and REACHABLE (an open stand spot beside the bed),
 *              with distance as the tiebreak
 *   persist  — by NPC UUID + bed position ({@link BedAssignmentData}, world saved data)
 *   repair   — a claim drops when its bed breaks, leaves its polygon, or the polygon's kind changes;
 *              a citizen's death frees its bed immediately (LivingDeathEvent). An UNLOADED citizen
 *              keeps its home -- beds don't churn just because the player walked away.
 *
 * Registered on the Forge EVENT_BUS in EpochRunnerMod (next to DistrictWorkExecutor -- a handler that
 * isn't registered is a dead subsystem).
 */
public final class BedAssignmentManager {

    private static final int PASS_INTERVAL = 600;   // ticks between passes (~30s)
    private static final int MAX_COLUMNS = 8192;    // polygon scan guard (same rail as CivilStats)
    private static final int Y_WINDOW = 12;
    private static final int MAX_NEW_CLAIMS_PER_PASS = 12;

    private BedAssignmentManager() {}

    private static int passCounter = 0;

    @SubscribeEvent
    public static void onWorldTick(TickEvent.WorldTickEvent e) {
        if (e.phase != TickEvent.Phase.END || e.side.isClient() || !(e.world instanceof WorldServer)) return;
        if (++passCounter % PASS_INTERVAL != 0) return;
        WorldServer world = (WorldServer) e.world;

        // SLEEP TAXES: each dawn every housed citizen pays up (see collectTaxes).
        collectTaxes(world);

        // Ensure every loaded player-owned citizen carries the citizen-life task (night bed-seeking
        // + hunger) — WORKERS unconditionally, and SOLDIERS too: Barracks is military housing, so
        // off-duty soldiers sleep there. The task itself refuses to move a soldier who currently
        // holds a defense-plan order (or a live target) — posts are never deserted for a bed.
        for (Object o : world.loadedEntityList) {
            if (!(o instanceof EntityCreature)) continue;
            EntityCreature c = (EntityCreature) o;
            if (DistrictWorkExecutor.isAnyWorker(c) || Aw2Npc.isPlayerOwnedCombat(c)) {
                DistrictWorkExecutor.ensureLifeTask(c);
            }
        }

        List<CivilMarker> housing = new ArrayList<>();
        for (CivilMarker m : CivilPlanData.get(world).markers) {
            if (!m.isRoad() && (m.kind == CivilMarker.RESIDENTIAL || m.kind == CivilMarker.BARRACKS))
                housing.add(m);
        }
        BedAssignmentData data = BedAssignmentData.get(world);
        if (housing.isEmpty()) {
            // No housing districts at all -> every claim is stale.
            if (!data.claims.isEmpty()) { data.claims.clear(); data.markDirty(); }
            return;
        }

        // ---- scan: bed HEAD -> military? (Barracks=true, Residential=false) ----
        Map<BlockPos, Boolean> beds = scanBeds(world, housing);

        // ---- repair: drop claims whose bed vanished, left its polygon, or changed class ----
        int dropped = 0;
        for (Map.Entry<UUID, BedAssignmentData.Claim> en : new ArrayList<>(data.claims.entrySet())) {
            Boolean mil = beds.get(en.getValue().bed);
            if (mil == null || mil != en.getValue().military) {
                data.remove(en.getKey());
                dropped++;
            }
        }
        if (dropped > 0)
            EpochRunnerMod.logger.info("[Beds] repaired " + dropped + " stale claim(s) (bed broke or district changed)");

        // ---- assign: house every loaded, unhoused citizen in the best free bed of its class ----
        Map<BlockPos, Boolean> free = new HashMap<>(beds);
        for (BedAssignmentData.Claim c : data.claims.values()) free.remove(c.bed);
        if (free.isEmpty()) return;

        int made = 0;
        for (Object o : world.loadedEntityList) {
            if (made >= MAX_NEW_CLAIMS_PER_PASS) break;
            if (!(o instanceof EntityCreature)) continue;
            EntityCreature npc = (EntityCreature) o;
            if (npc.isDead || data.claims.containsKey(npc.getUniqueID())) continue;
            Boolean wantsMilitary = housingClassOf(npc);
            if (wantsMilitary == null) continue;

            BlockPos best = null;
            double bestScore = -Double.MAX_VALUE;
            for (Map.Entry<BlockPos, Boolean> bed : free.entrySet()) {
                if (bed.getValue() != wantsMilitary) continue;
                double score = scoreBed(world, bed.getKey())
                        - Math.sqrt(npc.getDistanceSq(bed.getKey())) / 64.0; // distance is only the tiebreak
                if (score > bestScore) { bestScore = score; best = bed.getKey(); }
            }
            if (best == null) continue;
            data.put(npc.getUniqueID(), best, wantsMilitary);
            free.remove(best);
            made++;
            EpochRunnerMod.logger.info("[Beds] " + npc.getName() + " (" + Aw2Npc.fullType(npc) + ") assigned a "
                    + (wantsMilitary ? "BARRACKS" : "RESIDENTIAL") + " bed @ "
                    + best.getX() + "," + best.getY() + "," + best.getZ());
        }
    }

    private static final Map<Integer, Long> LAST_TAX_DAY = new HashMap<>();

    /**
     * SLEEP TAXES — every citizen with a claimed bed generates Command Bucks each night slept
     * (config taxPerSleep), multiplied by how well-fed they are: a citizen who hasn't eaten loses
     * up to (1 - taxHungerMin) of their contribution. UNLOADED citizens keep their bed and pay a
     * simulated-meal rate (0.75) — the settlement earns even when the player is off at war.
     * Credited at dawn to the (single-player) settlement owner's faction bucket.
     */
    private static void collectTaxes(WorldServer world) {
        int dim = world.provider.getDimension();
        long day = world.getTotalWorldTime() / 24000L;
        Long last = LAST_TAX_DAY.get(dim);
        if (last == null) { LAST_TAX_DAY.put(dim, day); return; }
        if (day <= last || !world.isDaytime()) return;
        LAST_TAX_DAY.put(dim, day);

        BedAssignmentData data = BedAssignmentData.get(world);
        if (data.claims.isEmpty() || world.playerEntities.isEmpty()) return;
        double per = studio.ERM.war.config.TradePriceConfig.data.taxPerSleep;
        double floor = studio.ERM.war.config.TradePriceConfig.data.taxHungerMin;
        long now = world.getTotalWorldTime();
        double total = 0;
        int housed = 0;
        for (Map.Entry<UUID, BedAssignmentData.Claim> en : data.claims.entrySet()) {
            net.minecraft.entity.Entity ent = world.getEntityFromUuid(en.getKey());
            double fed;
            if (ent instanceof EntityCreature) {
                long fedUntil = ((EntityCreature) ent).getEntityData().getLong("erm_fed_until");
                // Fed recently = full rate; every missed day halves toward the floor.
                fed = fedUntil >= now ? 1.0
                        : Math.max(floor, 1.0 - ((now - fedUntil) / 24000.0) * 0.5);
            } else {
                fed = 0.75; // unloaded: simulated meals
            }
            total += per * fed;
            housed++;
        }
        if (housed == 0 || total < 1) return;
        net.minecraft.entity.player.EntityPlayer owner = world.playerEntities.get(0);
        studio.ERM.war.world.WarWorldData wd = studio.ERM.war.world.WarWorldData.get(world);
        wd.getStats(owner.getUniqueID().toString()).commandPoints += (int) Math.floor(total);
        wd.markDirty();
        owner.sendMessage(new net.minecraft.util.text.TextComponentString(
                net.minecraft.util.text.TextFormatting.GOLD + "Taxes collected: +" + (int) Math.floor(total)
                + " CB from " + housed + " housed citizen" + (housed == 1 ? "" : "s") + "."));
        EpochRunnerMod.logger.info("[Beds] taxes: +" + (int) Math.floor(total) + " CB from " + housed);
    }

    /** A citizen's death frees its bed immediately. */
    @SubscribeEvent
    public static void onCitizenDeath(LivingDeathEvent e) {
        EntityLivingBase dead = e.getEntityLiving();
        if (dead == null || dead.world == null || dead.world.isRemote) return;
        if (!Aw2Npc.isAw2Npc(dead)) return;
        BedAssignmentData data = BedAssignmentData.get(dead.world);
        if (data.claims.containsKey(dead.getUniqueID())) {
            data.remove(dead.getUniqueID());
            EpochRunnerMod.logger.info("[Beds] " + dead.getName() + " died -> bed freed");
        }
    }

    /** null = not a housed citizen; false = civilian (Residential); true = military (Barracks). */
    private static Boolean housingClassOf(EntityCreature npc) {
        if (Aw2Npc.allegiance(npc) != Aw2Npc.Allegiance.PLAYER_OWNED) return null;
        if (Aw2Npc.isPlayerOwnedCombat(npc)) return Boolean.TRUE;
        String type = Aw2Npc.type(npc);
        if (type == null || type.isEmpty()) return null;
        return Boolean.FALSE; // worker / any other non-combat player-owned citizen
    }

    /** Bed HEAD blocks inside the housing polygons -> military flag. Same bounded column scan as CivilStats. */
    private static Map<BlockPos, Boolean> scanBeds(World world, List<CivilMarker> housing) {
        Map<BlockPos, Boolean> beds = new HashMap<>();
        int columns = 0;
        outer:
        for (CivilMarker m : housing) {
            boolean military = (m.kind == CivilMarker.BARRACKS);
            int minX = Integer.MAX_VALUE, maxX = Integer.MIN_VALUE;
            int minZ = Integer.MAX_VALUE, maxZ = Integer.MIN_VALUE;
            for (BlockPos p : m.points) {
                minX = Math.min(minX, p.getX()); maxX = Math.max(maxX, p.getX());
                minZ = Math.min(minZ, p.getZ()); maxZ = Math.max(maxZ, p.getZ());
            }
            if (minX > maxX) continue;
            for (int x = minX; x <= maxX; x++) {
                for (int z = minZ; z <= maxZ; z++) {
                    if (!m.contains(x, z)) continue;
                    if (++columns > MAX_COLUMNS) break outer;
                    if (!world.isBlockLoaded(new BlockPos(x, 64, z), false)) continue;
                    int surfaceY = world.getHeight(x, z);
                    int lo = Math.max(0, surfaceY - Y_WINDOW), hi = surfaceY + Y_WINDOW;
                    for (int y = lo; y <= hi; y++) {
                        BlockPos p = new BlockPos(x, y, z);
                        IBlockState st = world.getBlockState(p);
                        if (st.getBlock() instanceof BlockBed
                                && st.getValue(BlockBed.PART) == BlockBed.EnumPartType.HEAD) {
                            beds.put(p, military);
                        }
                    }
                }
            }
        }
        return beds;
    }

    /** Bed quality: indoor (solid roof overhead) + lit + reachable (an open stand spot beside it). */
    private static double scoreBed(World world, BlockPos bed) {
        double score = 0;
        // INDOOR: any solid block within 8 above = under a roof.
        boolean roofed = false;
        for (int dy = 1; dy <= 8 && !roofed; dy++) {
            IBlockState st = world.getBlockState(bed.up(dy));
            if (st.getMaterial().isSolid()) roofed = true;
        }
        if (roofed) score += 4.0;
        // LIT: mobs can't spawn beside a lit bed -- "safe".
        if (world.getLightFromNeighbors(bed.up()) >= 8) score += 2.0;
        // REACHABLE: at least one horizontal neighbour a citizen can stand in (2 air over solid).
        for (EnumFacing f : EnumFacing.HORIZONTALS) {
            BlockPos side = bed.offset(f);
            if (world.isAirBlock(side) && world.isAirBlock(side.up())
                    && world.getBlockState(side.down()).getMaterial().isSolid()) {
                score += 3.0;
                break;
            }
        }
        return score;
    }
}
