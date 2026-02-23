package studio.ERM.war;

import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.scoreboard.IScoreCriteria;
import net.minecraft.scoreboard.Score;
import net.minecraft.scoreboard.ScoreObjective;
import net.minecraft.scoreboard.Scoreboard;
import net.minecraft.server.MinecraftServer;
import net.minecraft.util.text.TextComponentString;
import net.minecraft.util.text.TextFormatting;
import net.minecraft.world.World;
import net.minecraftforge.fml.common.FMLCommonHandler;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import net.minecraftforge.fml.common.gameevent.TickEvent;
import studio.ERM.EpochRunnerMod;
import studio.ERM.war.rival.RivalCityManager;
import studio.ERM.war.world.WarWorldData;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

/**
 * WAR SCOREBOARD MANAGER
 *
 * Displays strategic war status.
 *
 * IMPORTANT:
 * - Does NOT hard-depend on battle package classes (reflection used) to prevent compile breaks when battle code moves.
 * - Exposes cached values for client HUD / tactical map panels (CP, Air Defense, etc.).
 */
@Mod.EventBusSubscriber(modid = EpochRunnerMod.MODID)
public class WarScoreboardManager {

    private static final String OBJECTIVE_NAME = "warstatus";
    private static final String DISPLAY_NAME = "§l⚔ War Status";

    private static final Set<UUID> enabledPlayers = new HashSet<>();

    private static int updateTicks = 0;
    private static final int UPDATE_INTERVAL = 20;

    private static boolean globalEnabled = true;

    // Cached values from the most recent scoreboard update (server-side truth)
    private static volatile int cachedTension = 0;
    private static volatile int cachedCP = 0;
    private static volatile int cachedEra = 1;
    private static volatile int cachedAirDefense = 0;
    private static volatile int cachedPlayerChunks = 0;
    private static volatile int cachedRivalLevel = 0;

    @SubscribeEvent
    public static void onServerTick(TickEvent.ServerTickEvent event) {
        if (!globalEnabled) return;
        if (event.phase != TickEvent.Phase.END) return;

        updateTicks++;
        if (updateTicks < UPDATE_INTERVAL) return;
        updateTicks = 0;

        MinecraftServer server = FMLCommonHandler.instance().getMinecraftServerInstance();
        if (server == null) return;

        World world = server.getWorld(0);
        if (world == null) return;

        for (EntityPlayerMP player : server.getPlayerList().getPlayers()) {
            if (isScoreboardEnabled(player)) {
                updatePlayerScoreboard(world, player);
            }
        }
    }

    public static void toggleScoreboard(EntityPlayerMP player) {
        UUID playerId = player.getUniqueID();

        if (enabledPlayers.contains(playerId)) {
            disableScoreboard(player);
            player.sendMessage(new TextComponentString("§7Scoreboard disabled. Use the HUD for status."));
        } else {
            globalEnabled = true;
            enableScoreboard(player);
            player.sendMessage(new TextComponentString("§aScoreboard enabled."));
        }
    }

    public static void enableScoreboard(EntityPlayerMP player) {
        UUID playerId = player.getUniqueID();
        Scoreboard scoreboard = player.getWorldScoreboard();

        ScoreObjective existing = scoreboard.getObjective(OBJECTIVE_NAME);
        if (existing != null) {
            scoreboard.removeObjective(existing);
        }

        ScoreObjective objective = scoreboard.addScoreObjective(OBJECTIVE_NAME, IScoreCriteria.DUMMY);
        objective.setDisplayName(DISPLAY_NAME);
        scoreboard.setObjectiveInDisplaySlot(1, objective);

        enabledPlayers.add(playerId);
        updatePlayerScoreboard(player.world, player);

        EpochRunnerMod.logger.info("[SCOREBOARD] Enabled for " + player.getName());
    }

    public static void disableScoreboard(EntityPlayerMP player) {
        UUID playerId = player.getUniqueID();
        Scoreboard scoreboard = player.getWorldScoreboard();

        ScoreObjective existing = scoreboard.getObjective(OBJECTIVE_NAME);
        if (existing != null) {
            scoreboard.removeObjective(existing);
        }

        scoreboard.setObjectiveInDisplaySlot(1, null);
        enabledPlayers.remove(playerId);

        EpochRunnerMod.logger.info("[SCOREBOARD] Disabled for " + player.getName());
    }

    public static boolean isScoreboardEnabled(EntityPlayerMP player) {
        return enabledPlayers.contains(player.getUniqueID());
    }

    private static void updatePlayerScoreboard(World world, EntityPlayerMP player) {
        Scoreboard scoreboard = player.getWorldScoreboard();
        ScoreObjective objective = scoreboard.getObjective(OBJECTIVE_NAME);

        if (objective == null) {
            enableScoreboard(player);
            return;
        }

        WarWorldData data = WarWorldData.get(world);
        WarWorldData.FactionStats stats = data.getStats("PLAYER");

        clearObjectiveScores(scoreboard, objective);

        int score = 15;

        float tension = stats.tension;
        String tensionTier = WarTensionManager.getTensionTier((int) tension);
        String tensionColor = getTensionColorCode(tension);
        setScore(scoreboard, objective, tensionColor + "Tension: " + (int) tension + "%", score--);
        setScore(scoreboard, objective, "  " + tensionColor + "[" + tensionTier + "]", score--);

        setScore(scoreboard, objective, " ", score--);

        int activeBattles = getActiveBattleCountSafe();
        String warState = activeBattles > 0
                ? TextFormatting.RED + "⚔ AT WAR (" + activeBattles + " battles)"
                : TextFormatting.GREEN + "☮ Peace";
        setScore(scoreboard, objective, warState, score--);

        setScore(scoreboard, objective, "  ", score--);

        int airDef = stats.airDefense;
        String adColor = airDef > 70 ? "§a" : (airDef > 30 ? "§e" : "§c");
        setScore(scoreboard, objective, adColor + "Air Defense: " + airDef + "%", score--);

        setScore(scoreboard, objective, "   ", score--);

        int playerChunks = countChunks(data, "PLAYER");
        int rivalChunks = countChunks(data, "RIVAL");
        setScore(scoreboard, objective, "§aYour Territory: " + playerChunks, score--);
        setScore(scoreboard, objective, "§cRival Territory: " + rivalChunks, score--);

        setScore(scoreboard, objective, "    ", score--);

        setScore(scoreboard, objective, "§6CP: " + stats.commandPoints, score--);
        setScore(scoreboard, objective, "§7Era: " + stats.era, score--);

        int rivalLevel = RivalCityManager.getRivalCityLevel();
        if (rivalLevel > 0) {
            setScore(scoreboard, objective, "§cRival Level: " + rivalLevel, score--);
        }

        // Cache values for other UIs
        cachedTension = (int) tension;
        cachedCP = stats.commandPoints;
        cachedEra = stats.era;
        cachedAirDefense = airDef;
        cachedPlayerChunks = playerChunks;
        cachedRivalLevel = rivalLevel;
    }

    /**
     * Avoids hard dependency on any specific battle system class.
     * Tries a few likely class+method pairs and returns 0 if nothing is present.
     */
    private static int getActiveBattleCountSafe() {
        // 1) studio.ERM.war.battle.BattleManager.getActiveBattleCount()
        Integer v = tryStaticIntCall("studio.ERM.war.battle.BattleManager", "getActiveBattleCount");
        if (v != null) return v;

        // 2) studio.ERM.war.battle.BattleManager.hasActiveBattle() (boolean)
        Boolean b = tryStaticBooleanCall("studio.ERM.war.battle.BattleManager", "hasActiveBattle");
        if (b != null) return b ? 1 : 0;

        // 3) studio.ERM.war.battle.WarBattleSystem.hasActiveBattle() (boolean)
        b = tryStaticBooleanCall("studio.ERM.war.battle.WarBattleSystem", "hasActiveBattle");
        if (b != null) return b ? 1 : 0;

        return 0;
    }

    private static Integer tryStaticIntCall(String className, String methodName) {
        try {
            Class<?> c = Class.forName(className);
            Method m = c.getDeclaredMethod(methodName);
            m.setAccessible(true);
            Object r = m.invoke(null);
            if (r instanceof Integer) {
                return (Integer) r;
            }
            if (r instanceof Number) {
                return ((Number) r).intValue();
            }
        } catch (Throwable ignored) {
        }
        return null;
    }

    private static Boolean tryStaticBooleanCall(String className, String methodName) {
        try {
            Class<?> c = Class.forName(className);
            Method m = c.getDeclaredMethod(methodName);
            m.setAccessible(true);
            Object r = m.invoke(null);
            if (r instanceof Boolean) {
                return (Boolean) r;
            }
        } catch (Throwable ignored) {
        }
        return null;
    }

    private static void setScore(Scoreboard scoreboard, ScoreObjective objective, String name, int value) {
        if (name.length() > 40) name = name.substring(0, 40);
        Score score = scoreboard.getOrCreateScore(name, objective);
        score.setScorePoints(value);
    }

    private static void clearObjectiveScores(Scoreboard scoreboard, ScoreObjective objective) {
        Collection<Score> scores = scoreboard.getSortedScores(objective);
        for (Score score : new ArrayList<>(scores)) {
            scoreboard.removeObjectiveFromEntity(score.getPlayerName(), objective);
        }
    }

    private static int countChunks(WarWorldData data, String owner) {
        int count = 0;
        for (String o : data.getTerritoryMap().values()) {
            if (owner.equals(o)) count++;
        }
        return count;
    }

    private static String getTensionColorCode(float tension) {
        if (tension >= WarTensionManager.CRISIS_THRESHOLD) return "§4";
        if (tension >= WarTensionManager.AIRSTRIKE_THRESHOLD) return "§c";
        if (tension >= WarTensionManager.BATTLE_THRESHOLD) return "§6";
        if (tension >= WarTensionManager.RAID_THRESHOLD) return "§e";
        return "§a";
    }

    public static void enableGlobal() {
        globalEnabled = true;
    }

    public static void disableGlobal() {
        globalEnabled = false;
        enabledPlayers.clear();
    }

    public static int getCachedTension() { return cachedTension; }
    public static int getCachedCP() { return cachedCP; }
    public static int getCachedEra() { return cachedEra; }
    public static int getCachedAirDefense() { return cachedAirDefense; }
    public static int getCachedPlayerChunks() { return cachedPlayerChunks; }
    public static int getCachedRivalLevel() { return cachedRivalLevel; }
}
