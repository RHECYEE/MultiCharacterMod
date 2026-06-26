package studio.ERM.war.client;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.FontRenderer;
import net.minecraft.client.gui.Gui;
import net.minecraft.client.gui.ScaledResolution;
import net.minecraft.client.renderer.GlStateManager;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraftforge.client.event.RenderGameOverlayEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import net.minecraftforge.fml.relauncher.Side;
import net.minecraftforge.fml.relauncher.SideOnly;
import studio.ERM.EpochRunnerMod;
import studio.ERM.handlers.WarTensionManager;
import studio.ERM.war.world.WarWorldData;
import studio.ERM.war.rival.RivalCityManager;
import studio.ERM.war.air.AirStrikeController;

/**
 * War Status HUD Overlay
 *
 * Displays persistent war information on the screen:
 * - Current tension level
 * - Command Points
 * - Active battles
 * - Air defense status
 * - Territory summary
 */
@SideOnly(Side.CLIENT)
@Mod.EventBusSubscriber(modid = EpochRunnerMod.MODID, value = Side.CLIENT)
public class WarStatusHUD extends Gui {

    private static boolean hudEnabled = false; // DISABLED - scoreboard is the primary display now
    private static HudPosition position = HudPosition.TOP_RIGHT;
    private static float hudScale = 1.0f;
    private static int hudOpacity = 180;

    // Cached data (updated periodically)
    private static int cachedTension = 0;
    private static int cachedCP = 0;
    private static int cachedEra = 1;
    private static int cachedBattles = 0;
    private static int cachedAirDefense = 0;
    private static int cachedPlayerChunks = 0;
    private static int cachedRivalChunks = 0;
    private static int cachedRivalLevel = 0;
    private static int cachedAirStrikes = 0;
    private static long lastUpdate = 0;

    public enum HudPosition {
        TOP_LEFT,
        TOP_RIGHT,
        BOTTOM_LEFT,
        BOTTOM_RIGHT
    }

    @SubscribeEvent
    public static void onRenderOverlay(RenderGameOverlayEvent.Post event) {
        if (!hudEnabled) return;
        if (event.getType() != RenderGameOverlayEvent.ElementType.ALL) return;

        Minecraft mc = Minecraft.getMinecraft();
        if (mc.player == null || mc.world == null) return;
        if (mc.gameSettings.showDebugInfo) return; // Hide when F3 is open

        // Update cached data every second
        if (System.currentTimeMillis() - lastUpdate > 1000) {
            updateCachedData(mc.player);
            lastUpdate = System.currentTimeMillis();
        }

        renderHUD(mc, event.getResolution());
    }

    private static void updateCachedData(EntityPlayer player) {
        try {
            WarWorldData data = WarWorldData.get(player.world);
            if (data != null) {
                WarWorldData.FactionStats stats = data.getStats("PLAYER");
                cachedTension = (int) stats.tension;
                cachedCP = stats.commandPoints;
                cachedEra = stats.era;
                cachedAirDefense = stats.airDefense;

                // Count territories
                java.util.Map<net.minecraft.util.math.ChunkPos, String> chunks = data.getAllChunkOwners();
                cachedPlayerChunks = 0;
                cachedRivalChunks = 0;
                for (String owner : chunks.values()) {
                    if ("PLAYER".equals(owner)) cachedPlayerChunks++;
                    else if ("RIVAL".equals(owner)) cachedRivalChunks++;
                }
            }

            // Battle count from BattleManager
            cachedBattles = studio.ERM.war.battle.BattleManager.getActiveBattleCount();

            // Rival city level
            if (RivalCityManager.isInitialized()) {
                cachedRivalLevel = RivalCityManager.getRivalCityLevel();
            }

            // Air strikes
            cachedAirStrikes = AirStrikeController.getActiveStrikeCount();

        } catch (Exception e) {
            // Ignore errors during data fetch
        }
    }

    private static void renderHUD(Minecraft mc, ScaledResolution res) {
        FontRenderer font = mc.fontRenderer;

        int width = res.getScaledWidth();
        int height = res.getScaledHeight();

        // Calculate position
        int x, y;
        int boxWidth = 120;
        int boxHeight = 100;
        int padding = 5;

        switch (position) {
            case TOP_LEFT:
                x = padding;
                y = padding;
                break;
            case TOP_RIGHT:
                x = width - boxWidth - padding;
                y = padding;
                break;
            case BOTTOM_LEFT:
                x = padding;
                y = height - boxHeight - padding;
                break;
            case BOTTOM_RIGHT:
            default:
                x = width - boxWidth - padding;
                y = height - boxHeight - padding;
                break;
        }

        GlStateManager.pushMatrix();
        GlStateManager.scale(hudScale, hudScale, 1.0f);

        // Scale position
        x = (int)(x / hudScale);
        y = (int)(y / hudScale);

        // Draw background
        drawRect(x - 2, y - 2, x + boxWidth + 2, y + boxHeight + 2,
                (hudOpacity << 24) | 0x000000);

        // Draw border based on tension
        int borderColor = getTensionColor(cachedTension);
        drawRect(x - 2, y - 2, x + boxWidth + 2, y, borderColor);

        // Title
        font.drawStringWithShadow("§l⚔ WAR STATUS", x + 2, y + 2, 0xFFFFFF);
        y += 14;

        // Era
        font.drawStringWithShadow("Era: §e" + cachedEra, x + 2, y, 0xAAAAAA);
        y += 10;

        // Tension bar
        String tensionLabel = "Tension: ";
        String tensionTier = WarTensionManager.getTensionTier(cachedTension);
        int tensionColor = WarTensionManager.getTensionColor(cachedTension);
        font.drawStringWithShadow(tensionLabel, x + 2, y, 0xAAAAAA);

        // Draw tension bar
        int barX = x + font.getStringWidth(tensionLabel) + 2;
        int barWidth = 50;
        int barHeight = 6;
        drawRect(barX, y + 2, barX + barWidth, y + 2 + barHeight, 0xFF333333);
        drawRect(barX, y + 2, barX + (barWidth * cachedTension / 100), y + 2 + barHeight, tensionColor);
        font.drawStringWithShadow(cachedTension + "%", barX + barWidth + 3, y, tensionColor);
        y += 12;

        // Command Points
        font.drawStringWithShadow("CP: §6" + cachedCP, x + 2, y, 0xAAAAAA);
        y += 10;

        // Air Defense - fix color code formatting
        String adColorCode = cachedAirDefense > 70 ? "a" : (cachedAirDefense > 30 ? "e" : "c"); // green/yellow/red
        font.drawStringWithShadow("Air Def: §" + adColorCode + cachedAirDefense + "%",
                x + 2, y, 0xAAAAAA);
        y += 10;

        // Territory
        font.drawStringWithShadow("Territory: §a" + cachedPlayerChunks + " §7vs §c" + cachedRivalChunks,
                x + 2, y, 0xAAAAAA);
        y += 10;

        // Active Battles
        if (cachedBattles > 0) {
            font.drawStringWithShadow("§c⚔ " + cachedBattles + " Active Battle" + (cachedBattles > 1 ? "s" : ""),
                    x + 2, y, 0xFF8800);
            y += 10;
        }

        // Air Strikes
        if (cachedAirStrikes > 0) {
            font.drawStringWithShadow("§c✈ " + cachedAirStrikes + " Air Strike" + (cachedAirStrikes > 1 ? "s" : ""),
                    x + 2, y, 0xFF0000);
            y += 10;
        }

        // Rival Level
        if (cachedRivalLevel > 0) {
            font.drawStringWithShadow("Rival: §cLvl " + cachedRivalLevel, x + 2, y, 0xAAAAAA);
        }

        GlStateManager.popMatrix();
    }

    private static int getTensionColor(int tension) {
        if (tension >= 80) return 0xFFFF0000; // Red
        if (tension >= 60) return 0xFFFF8800; // Orange
        if (tension >= 40) return 0xFFFFFF00; // Yellow
        if (tension >= 20) return 0xFF88FF00; // Yellow-Green
        return 0xFF00FF00; // Green
    }

    // === CONFIGURATION ===

    public static void toggleHUD() {
        hudEnabled = !hudEnabled;
    }

    public static boolean isHUDEnabled() {
        return hudEnabled;
    }

    public static void setPosition(HudPosition pos) {
        position = pos;
    }

    public static void setScale(float scale) {
        hudScale = Math.max(0.5f, Math.min(2.0f, scale));
    }

    public static void setOpacity(int opacity) {
        hudOpacity = Math.max(0, Math.min(255, opacity));
    }

    public static void cyclePosition() {
        HudPosition[] positions = HudPosition.values();
        int next = (position.ordinal() + 1) % positions.length;
        position = positions[next];
    }
}