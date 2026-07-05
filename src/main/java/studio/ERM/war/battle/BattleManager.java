package studio.ERM.war.battle;

import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.server.MinecraftServer;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.ChunkPos;
import net.minecraft.world.World;
import net.minecraftforge.fml.common.FMLCommonHandler;
import studio.ERM.war.BattleManagers.core.BattleEngine;

import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

/**
 * COMPATIBILITY LAYER
 *
 * Old code imports studio.ERM.war.battle.BattleManager.
 * New battles are run by BattleManagers. This adapter provides the
 * small surface old UI/scoreboard code expects.
 */
public final class BattleManager {

    /**
     * BattleSite represents a location where battles can take place
     */
    public static final class BattleSite {
        public final String id;
        public final ChunkPos chunk;
        public final BlockPos center;
        public final String name;
        public final int level;
        public boolean active;
        public studio.ERM.war.BattleManagers.api.BattleMode currentMode;

        public BattleSite(World world, ChunkPos chunk, String name, int level) {
            int dim = world.provider.getDimension();
            this.id = "dim" + dim + "_c" + chunk.x + "_" + chunk.z;
            this.chunk = chunk;
            this.center = new BlockPos((chunk.x << 4) + 8, 64, (chunk.z << 4) + 8);
            this.name = name;
            this.level = level;
            this.active = false;
            this.currentMode = null;
        }
    }

    private static final Map<String, BattleSite> SITES_BY_ID = new HashMap<>();
    private static final Map<Long, String> SITE_BY_CHUNK_KEY = new HashMap<>();
    private static BattleSite currentActiveSite = null;

    private BattleManager() {}

    /**
     * Creates a new battle site at the specified chunk
     */
    public static BattleSite createBattleSite(World world, ChunkPos center, String name, int level) {
        if (world == null || world.isRemote) return null;

        BattleSite site = new BattleSite(world, center, name, level);
        SITES_BY_ID.put(site.id, site);
        SITE_BY_CHUNK_KEY.put(chunkKey(center), site.id);
        return site;
    }

    /**
     * Gets a battle site by chunk position
     */
    public static BattleSite getBattleSite(ChunkPos chunk) {
        String id = SITE_BY_CHUNK_KEY.get(chunkKey(chunk));
        if (id == null) return null;
        return SITES_BY_ID.get(id);
    }

    /**
     * Returns true if there's an active battle
     */
    public static boolean hasActiveBattle() {
        return currentActiveSite != null && currentActiveSite.active;
    }

    /**
     * Gets all battle sites
     */
    public static Collection<BattleSite> getAllBattleSites() {
        return Collections.unmodifiableCollection(SITES_BY_ID.values());
    }

    /**
     * Starts a battle at the given site
     */
    public static boolean startBattle(World world, BattleSite site, EntityPlayer initiator) {
        if (world == null || world.isRemote || site == null) return false;
        if (hasActiveBattle()) return false;

        BattleEngine engine = BattleEngine.get(world);
        if (engine.hasActiveBattle()) return false;

        // Start the battle using the debug director for now
        engine.startDebugCircleBattle(initiator, site.center, "ShieldWall");

        site.active = true;
        currentActiveSite = site;
        return true;
    }

    /**
     * Starts a battle with specified mode and wave configuration
     */
    public static boolean startBattle(World world, BattleSite site, EntityPlayer initiator, 
                                       studio.ERM.war.BattleManagers.api.BattleMode mode, int waves) {
        if (world == null || world.isRemote || site == null) return false;
        if (hasActiveBattle()) return false;

        BattleEngine engine = BattleEngine.get(world);
        if (engine.hasActiveBattle()) return false;

        // Get or create the appropriate director based on mode
        studio.ERM.war.BattleManagers.api.IBattleDirector director = null;
        studio.ERM.war.BattleManagers.cards.UnitCard card = 
            studio.ERM.war.BattleManagers.cards.UnitCardRegistry.get("ShieldWall");
        if (card == null) {
            card = studio.ERM.war.BattleManagers.cards.UnitCardRegistry.getDefault();
        }

        if (mode != null && card != null) {
            switch (mode) {
                case DEBUG:
                    director = new studio.ERM.war.BattleManagers.directors.DebugCircleDirector(card);
                    break;
                case ASSAULT:
                    director = new studio.ERM.war.BattleManagers.directors.AssaultDirector(card);
                    break;
                case DEFENSE:
                    director = new studio.ERM.war.BattleManagers.directors.DefensiveStandDirector(card);
                    break;
                case SKIRMISH:
                    director = new studio.ERM.war.BattleManagers.directors.SkirmishDirector(card);
                    break;
                case SIEGE:
                    director = new studio.ERM.war.BattleManagers.directors.SiegeDirector(card);
                    break;
            }
        }

        if (director == null) {
            // Fallback to debug circle
            engine.startDebugCircleBattle(initiator, site.center, "ShieldWall");
        } else {
            engine.startBattle(director, initiator, site.center);
        }

        site.active = true;
        site.currentMode = mode;
        currentActiveSite = site;
        return true;
    }

    /**
     * Force ends the current battle
     */
    public static void endBattle(World world) {
        if (world == null || world.isRemote) return;
        
        BattleEngine engine = BattleEngine.get(world);
        engine.endBattle();
        
        if (currentActiveSite != null) {
            currentActiveSite.active = false;
            currentActiveSite.currentMode = null;
            currentActiveSite = null;
        }
    }

    /**
     * Player surrenders the current battle
     */
    public static void surrender(World world, EntityPlayer player) {
        if (world == null || world.isRemote) return;
        
        BattleEngine engine = BattleEngine.get(world);
        if (currentActiveSite != null) {
            engine.surrender(player, currentActiveSite.center);
            currentActiveSite.active = false;
            currentActiveSite.currentMode = null;
            currentActiveSite = null;
        }
    }

    /**
     * Returns the currently active battle site
     */
    public static BattleSite getActiveSite() {
        return currentActiveSite;
    }

    /**
     * Returns how many active battles exist across loaded server worlds.
     *
     * Your rule: one battle at a time per player, but server may have multiple dims loaded.
     * BattleEngine is world-scoped, so we count active per world.
     */
    public static int getActiveBattleCount() {
        MinecraftServer server = FMLCommonHandler.instance().getMinecraftServerInstance();
        if (server == null) return 0;

        int count = 0;
        for (World w : server.worlds) {
            if (w == null) continue;
            if (w.isRemote) continue;
            if (BattleEngine.get(w).hasActiveBattle()) {
                count++;
            }
        }
        return count;
    }

    /**
     * Resets all battle sites
     */
    public static void reset() {
        SITES_BY_ID.clear();
        SITE_BY_CHUNK_KEY.clear();
        currentActiveSite = null;
    }

    private static long chunkKey(ChunkPos c) {
        return (((long) c.x) << 32) ^ (c.z & 0xffffffffL);
    }
}
