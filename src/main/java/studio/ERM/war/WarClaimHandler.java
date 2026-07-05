package studio.ERM.war;

import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.util.math.ChunkPos;
import studio.ERM.war.map.net.S2CTerritorySync;
import studio.ERM.war.map.net.TacticalWarMapNetwork;
import studio.ERM.war.world.WarWorldData;

import java.util.List;
import java.util.Map;

/**
 * Server-side handler for territory claim / unclaim requests.
 * All mutations go through WarWorldData for persistence.
 */
public class WarClaimHandler {

    /** Base CP cost per chunk claim. */
    public static final int CLAIM_COST_BASE = 100;

    /**
     * One-time cold-start grant. CP is normally earned from owned rival-city districts, but a
     * brand-new faction owns no land and has 0 CP, so it could never afford its FIRST claim --
     * the "claiming does nothing" complaint. We grant this the first time a landless, CP-less
     * player tries to claim (see {@link #batchClaim}). ~10 chunks' worth.
     */
    public static final int STARTER_CP = 1000;

    public static class BatchClaimResult {
        public int claimed;
        public int failed;
        public int totalCost;
        public int failedCp;     // of `failed`, how many failed purely due to insufficient CP
        public int availableCp;  // player's CP after the operation (for clear messaging)

        public BatchClaimResult(int claimed, int failed, int totalCost) {
            this.claimed = claimed;
            this.failed = failed;
            this.totalCost = totalCost;
        }
    }

    /**
     * Attempt to claim a batch of chunks for the given player.
     * Deducts CP per chunk. Skips chunks the player already owns,
     * rival territory, or if they can't afford.
     */
    public static BatchClaimResult batchClaim(EntityPlayerMP player, List<ChunkPos> chunks) {
        if (player == null || chunks == null || chunks.isEmpty()) {
            return new BatchClaimResult(0, 0, 0);
        }

        WarWorldData data = WarWorldData.get(player.world);
        if (data == null) return new BatchClaimResult(0, chunks.size(), 0);

        String playerId = player.getUniqueID().toString();
        WarWorldData.FactionStats stats = data.getStats(playerId);

        // Cold-start bootstrap: a landless, CP-less faction can't earn CP (it comes from owned
        // districts), so it could never make its first claim. Grant a one-time starter pool the
        // first time such a player tries to claim. Keyed off persisted state (no territory + no
        // CP), so it can't re-trigger once they own land, and needs no extra NBT field.
        if (stats.commandPoints < CLAIM_COST_BASE && data.countClaimsForOwner(playerId) == 0) {
            stats.commandPoints += STARTER_CP;
            data.markDirty();
        }

        int claimed = 0;
        int failed = 0;
        int failedCp = 0;
        int totalCost = 0;

        for (ChunkPos cp : chunks) {
            String currentOwner = data.getOwner(cp);

            // Skip if player already owns this chunk
            if (playerId.equals(currentOwner)) {
                continue; // not a failure, just redundant
            }

            // Cannot claim rival territory directly
            if ("RIVAL".equals(currentOwner)) {
                failed++;
                continue;
            }

            // Cannot override another player's claim
            if (!"NEUTRAL".equals(currentOwner) && !"PLAYER".equals(currentOwner)) {
                failed++;
                continue;
            }

            // Check affordability
            if (stats.commandPoints < CLAIM_COST_BASE) {
                failed++;
                failedCp++;
                continue;
            }

            // Claim it
            stats.commandPoints -= CLAIM_COST_BASE;
            data.setOwner(cp, playerId);
            claimed++;
            totalCost += CLAIM_COST_BASE;
        }

        if (claimed > 0) {
            data.markDirty();
            // CLAIM-DRIVEN RIVAL GROWTH: every chunk the player claims feeds the rival's growth
            // bank (30 claims -> one 25-chunk growth surge; both configurable in war_levels.json).
            try {
                studio.ERM.war.rival.RivalCityManager.onPlayerClaimedChunks(player.world, player, claimed);
            } catch (Throwable t) {
                studio.ERM.EpochRunnerMod.logger.error("[ERM-Map] rival growth credit failed", t);
            }
        }

        BatchClaimResult result = new BatchClaimResult(claimed, failed, totalCost);
        result.failedCp = failedCp;
        result.availableCp = stats.commandPoints;
        return result;
    }

    /**
     * Unclaim a batch of chunks. Only unclaims chunks the player owns.
     * Returns the number of chunks actually unclaimed.
     */
    public static int batchUnclaim(EntityPlayerMP player, List<ChunkPos> chunks) {
        if (player == null || chunks == null || chunks.isEmpty()) return 0;

        WarWorldData data = WarWorldData.get(player.world);
        if (data == null) return 0;

        String playerId = player.getUniqueID().toString();
        int count = 0;

        for (ChunkPos cp : chunks) {
            String owner = data.getOwner(cp);
            if (playerId.equals(owner)) {
                data.setOwner(cp, "NEUTRAL");
                count++;
            }
        }

        if (count > 0) {
            data.markDirty();
        }
        return count;
    }

    /**
     * Send the full territory snapshot to one player, along with that player's live
     * war HUD stats (piggybacked on the same packet — see {@link S2CTerritorySync}).
     *
     * Stats are read from the UUID-keyed faction bucket: the same bucket that
     * {@link #batchClaim} spends CP from and that WarTensionManager accrues tension
     * into, so the map mirrors exactly what the player can actually spend and feel
     * in-world (the legacy "PLAYER" bucket read by the old scoreboard HUD is a
     * separate, stale set of values).
     */
    public static void syncTerritoryToPlayer(EntityPlayerMP player) {
        WarWorldData data = WarWorldData.get(player.world);
        if (data == null) return;

        Map<ChunkPos, String> snapshot = data.getAllChunkOwners();

        WarWorldData.FactionStats stats = data.getStats(player.getUniqueID().toString());
        int cp = stats.commandPoints;
        int airDef = stats.airDefense;
        int era = stats.era;
        int tension = (int) stats.tension;

        boolean battleActive = false;
        try {
            studio.ERM.war.BattleManagers.core.BattleEngine engine =
                    studio.ERM.war.BattleManagers.core.BattleEngine.get(player.world);
            battleActive = engine != null && engine.hasActiveBattle();
        } catch (Throwable ignored) {}

        TacticalWarMapNetwork.sendTo(
                new S2CTerritorySync(snapshot, cp, airDef, era, tension, battleActive), player);
        // Real-logger trace: shows exactly what the server sent the client. A snapshot size of 0 on
        // a fresh world is EXPECTED (no claims yet) and is NOT itself the "map shows nothing" bug --
        // the textured terrain still renders. This pairs with the client-side received-count log to
        // confirm the full round-trip and to reveal claim persistence (size should grow after a claim).
        studio.ERM.EpochRunnerMod.logger.info("[ERM-Map] SERVER: syncTerritoryToPlayer -> sent "
                + snapshot.size() + " chunks, cp=" + cp + ", era=" + era + " to " + player.getName());
    }

    /**
     * Check if a given position belongs to the player.
     */
    public static boolean isPlayerTerritory(WarWorldData data, ChunkPos pos, String playerId) {
        String owner = data.getOwner(pos);
        return playerId.equals(owner);
    }

    /**
     * Count total claims for a given owner.
     */
    public static int countPlayerClaims(WarWorldData data, String playerId) {
        return data.countClaimsForOwner(playerId);
    }
}
