package studio.ERM.war.battle;

import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.ChunkPos;
import net.minecraft.world.World;
import studio.ERM.EpochRunnerMod;
import studio.ERM.war.BattleManagers.cards.UnitCardRegistry;
import studio.ERM.war.BattleManagers.core.BattleEngine;
import studio.ERM.war.rival.RivalFactionStats;

import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

/**
 * COMPATIBILITY LAYER
 *
 * Old war code expects WarBattleSystem static battle-site routing.
 * New battle runtime is BattleManagers (director + formation carriers).
 *
 * This class keeps the old API stable while delegating execution.
 */
public final class WarBattleSystem {

    public enum SiteState {
        DORMANT,
        ACTIVE,
        RESOLVED
    }

    /**
     * Represents control status of an objective
     */
    public static final class Control {
        public final String display;
        public final int percent;

        public Control(String display, int percent) {
            this.display = display;
            this.percent = percent;
        }

        public static Control neutral() {
            return new Control("Neutral", 50);
        }

        public static Control playerControlled() {
            return new Control("Player Controlled", 100);
        }

        public static Control rivalControlled() {
            return new Control("Rival Controlled", 0);
        }
    }

    public static final class BattleSite {
        public final String id;
        public final ChunkPos chunk;
        public final BlockPos center;
        public final String name;
        public final int level;
        public SiteState state;
        public Control flagControl;
        public Control barracksControl;

        public BattleSite(World world, ChunkPos chunk, String name, int level) {
            int dim = world.provider.getDimension();
            this.id = "dim" + dim + "_c" + chunk.x + "_" + chunk.z;
            this.chunk = chunk;
            this.center = new BlockPos((chunk.x << 4) + 8, 64, (chunk.z << 4) + 8);
            this.name = name;
            this.level = level;
            this.state = SiteState.DORMANT;
            this.flagControl = Control.neutral();
            this.barracksControl = Control.neutral();
        }
    }

    public static final class ActiveBattle {
        public final BattleSite site;

        public ActiveBattle(BattleSite site) {
            this.site = site;
        }
    }

    private static final Map<String, BattleSite> SITES_BY_ID = new HashMap<>();
    private static final Map<Long, String> SITE_BY_CHUNK_KEY = new HashMap<>();
    private static ActiveBattle CURRENT;

    private WarBattleSystem() {}

    public static BattleSite createBattleSite(World world, ChunkPos center, String name, int level) {
        if (world == null || world.isRemote) return null;

        BattleSite site = new BattleSite(world, center, name, level);
        SITES_BY_ID.put(site.id, site);
        SITE_BY_CHUNK_KEY.put(chunkKey(center), site.id);
        return site;
    }

    public static BattleSite getBattleSite(ChunkPos chunk) {
        String id = SITE_BY_CHUNK_KEY.get(chunkKey(chunk));
        if (id == null) return null;
        return SITES_BY_ID.get(id);
    }

    public static boolean hasActiveBattle() {
        return CURRENT != null;
    }

    public static ActiveBattle getCurrentBattle() {
        return CURRENT;
    }

    public static Collection<BattleSite> getAllSites() {
        return Collections.unmodifiableCollection(SITES_BY_ID.values());
    }

    public static void tickBattles(World world, long worldTick) {
        if (world == null || world.isRemote) return;

        // BattleManagers anchor ticks the engine, but this call remains safe and ensures progress
        // if other tick paths ever change.
        BattleEngine.get(world).tick();

        if (CURRENT != null) {
            // If engine ended, clear active battle marker.
            if (!BattleEngine.get(world).hasActiveBattle()) {
                CURRENT.site.state = SiteState.RESOLVED;
                CURRENT = null;
            }
        }
    }

    /**
     * Compatibility signature (older call sites).
     * Attempts to find a nearby player; if none, it cannot start.
     */
    public static boolean startBattle(World world, BattleSite site, RivalFactionStats factionStats) {
        EntityPlayer p = world != null ? world.getClosestPlayer(site.center.getX() + 0.5, site.center.getY() + 0.5, site.center.getZ() + 0.5, 256.0, false) : null;
        return startBattle(world, site, factionStats, p);
    }

    /**
     * Preferred signature: we pass the initiator so BattleManagers can message/debug.
     */
    public static boolean startBattle(World world, BattleSite site, RivalFactionStats factionStats, EntityPlayer initiator) {
        if (world == null || world.isRemote) return false;
        if (site == null) return false;

        if (initiator == null) {
            EpochRunnerMod.logger.warn("[WarBattleSystem] Cannot start battle: no initiating player available.");
            return false;
        }

        // One battle at a time per world (your design). If already running, reject.
        BattleEngine engine = BattleEngine.get(world);
        if (engine.hasActiveBattle()) {
            return false;
        }

        // Choose a unit card. Prefer ShieldWall if present.
        String cardName;
        if (UnitCardRegistry.get("ShieldWall") != null) {
            cardName = "ShieldWall";
        } else if (!UnitCardRegistry.all().isEmpty()) {
            cardName = UnitCardRegistry.all().keySet().iterator().next();
        } else {
            initiator.sendMessage(new net.minecraft.util.text.TextComponentString("§cNo UnitCards registered. Cannot start battle."));
            return false;
        }

        // For now, start the proven director (debug circle) that exercises carriers + puppets.
        // You can later swap this to real battle mode directors (capture/demolition/etc).
        engine.startDebugCircleBattle(initiator, site.center, cardName);

        site.state = SiteState.ACTIVE;
        CURRENT = new ActiveBattle(site);
        return true;
    }

    public static void reset() {
        SITES_BY_ID.clear();
        SITE_BY_CHUNK_KEY.clear();
        CURRENT = null;
    }

    /**
     * Force ends the current battle
     */
    public static void forceEndBattle(World world) {
        if (world == null || world.isRemote) return;

        BattleEngine engine = BattleEngine.get(world);
        engine.endBattle();

        if (CURRENT != null) {
            CURRENT.site.state = SiteState.RESOLVED;
            CURRENT = null;
        }

        EpochRunnerMod.logger.info("[WarBattleSystem] Battle force-ended");
    }

    private static long chunkKey(ChunkPos c) {
        return (((long) c.x) << 32) ^ (c.z & 0xffffffffL);
    }
}