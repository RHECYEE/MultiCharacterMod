package studio.ERM.war.map.client;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.FontRenderer;
import net.minecraft.client.gui.Gui;
import net.minecraft.client.gui.ScaledResolution;
import net.minecraft.scoreboard.Score;
import net.minecraft.scoreboard.ScoreObjective;
import net.minecraft.scoreboard.ScorePlayerTeam;
import net.minecraft.scoreboard.Scoreboard;
import net.minecraft.util.text.TextFormatting;
import studio.ERM.war.BattleManagers.directors.BattleDirectorEntry;
import studio.ERM.war.BattleManagers.directors.BattleDirectorRegistry;
import studio.ERM.war.map.net.PacketDeployBattle;
import studio.ERM.war.map.net.TacticalWarMapNetwork;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * GUI popup menu for deploying battles on the war map.
 *
 * Shows available battle directors with their CP costs.
 * Player can select one to deploy at the clicked location.
 */
public class GuiBattleDeployMenu {

    private boolean open = false;
    private int screenX;
    private int screenY;
    private int worldX;
    private int worldZ;

    private int width = 180;
    private int height = 0;
    private int scrollOffset = 0;
    private int maxVisibleEntries = 6;

    private final List<MenuEntry> entries = new ArrayList<>();
    private int hoveredIndex = -1;

    // Player stats (cached from sidebar/client)
    private int playerCp = 0;
    private int playerEra = 1;

    private static final int HEADER_HEIGHT = 32; // Title + coordinates
    private static final int ENTRY_HEIGHT = 28;
    private static final int FOOTER_HEIGHT = 20;

    private static final Pattern INT_PATTERN = Pattern.compile("(\\d+)");
    private static final Pattern CP_PATTERN = Pattern.compile("(?i)\\bcp\\b");
    private static final Pattern ERA_PATTERN = Pattern.compile("(?i)\\bera\\b");

    public GuiBattleDeployMenu() {
    }

    /**
     * Open the menu at the given screen position for the given world coordinates.
     *
     * NOTE: Callers may not have CP/Era synchronized at click-time. This GUI will
     * attempt to resolve missing/zero values from the sidebar scoreboard as a fallback.
     */
    public void open(int screenX, int screenY, int worldX, int worldZ, int playerCp, int playerEra) {
        this.screenX = screenX;
        this.screenY = screenY;
        this.worldX = worldX;
        this.worldZ = worldZ;
        this.playerCp = Math.max(0, playerCp);
        this.playerEra = Math.max(1, playerEra);
        this.scrollOffset = 0;
        this.hoveredIndex = -1;

        // Fallback: if the caller isn't passing real values (common if the overlay updates asynchronously),
        // try to extract CP/Era from the sidebar scoreboard lines.
        if (this.playerCp <= 0 || this.playerEra <= 0) {
            PlayerStats resolved = resolvePlayerStatsFromSidebar();
            if (this.playerCp <= 0) {
                this.playerCp = Math.max(0, resolved.cp);
            }
            if (this.playerEra <= 0) {
                this.playerEra = Math.max(1, resolved.era);
            }
        }

        buildEntries();

        // Calculate height based on entries
        int visibleEntries = Math.min(entries.size(), maxVisibleEntries);
        this.height = HEADER_HEIGHT + (visibleEntries * ENTRY_HEIGHT) + FOOTER_HEIGHT;

        this.open = true;
    }

    private void buildEntries() {
        entries.clear();

        // Do NOT request directors filtered by CP from the registry, because:
        // 1) CP may be unknown/zero client-side momentarily
        // 2) special-case "debug" directors should remain visible even when CP is low
        // Request "available" by era with effectively infinite CP, then compute affordability locally.
        List<BattleDirectorEntry> available = BattleDirectorRegistry.getAvailable(playerEra, Integer.MAX_VALUE);

        for (BattleDirectorEntry entry : available) {
            boolean meetsLevel = playerEra >= entry.getMinLevel();
            int effectiveCost = getEffectiveCpCost(entry);
            boolean canAfford = playerCp >= effectiveCost;

            entries.add(new MenuEntry(entry, canAfford, meetsLevel, effectiveCost));
        }
    }

    private int getEffectiveCpCost(BattleDirectorEntry entry) {
        if (entry == null) return 0;

        // Debug circle (and any other explicitly debug-named director) is intended to be free.
        // We do this client-side to keep the UX consistent even if the registry entry has a non-zero cost.
        String id = safeLower(entry.getId());
        String name = safeLower(entry.getDisplayName());
        if (id.contains("debug") || id.contains("circle") && id.contains("debug") || name.contains("debug circle") || name.contains("debug")) {
            return 0;
        }

        return Math.max(0, entry.getCpCost());
    }

    public void close() {
        this.open = false;
    }

    public boolean isOpen() {
        return open;
    }

    /**
     * Handle mouse scroll for long lists.
     */
    public void handleScroll(int delta) {
        if (!open) return;

        int maxScroll = Math.max(0, entries.size() - maxVisibleEntries);
        if (delta > 0) {
            scrollOffset = Math.max(0, scrollOffset - 1);
        } else if (delta < 0) {
            scrollOffset = Math.min(maxScroll, scrollOffset + 1);
        }
    }

    /**
     * Handle left click - returns true if click was consumed.
     */
    public boolean handleLeftClick(int mouseX, int mouseY) {
        if (!open) return false;

        // Calculate adjusted menu position (keep on screen)
        int[] adjusted = getAdjustedPosition();
        int x0 = adjusted[0];
        int y0 = adjusted[1];

        // Check if click is outside menu
        if (mouseX < x0 || mouseX >= x0 + width || mouseY < y0 || mouseY >= y0 + height) {
            return false;
        }

        // Header area (close button)
        if (mouseY < y0 + HEADER_HEIGHT) {
            int closeX = x0 + width - 16;
            if (mouseX >= closeX) {
                close();
                return true;
            }
            return true; // Click on header but not close
        }

        // Entry area only (ignore footer clicks)
        int entryStartY = y0 + HEADER_HEIGHT;
        int visibleCount = Math.min(entries.size() - scrollOffset, maxVisibleEntries);
        int entryEndY = entryStartY + (visibleCount * ENTRY_HEIGHT);

        if (mouseY >= entryEndY) {
            return true; // Footer / separator area; consume click but do nothing
        }

        int relY = mouseY - entryStartY;
        int idx = scrollOffset + (relY / ENTRY_HEIGHT);

        if (idx >= 0 && idx < entries.size()) {
            MenuEntry entry = entries.get(idx);

            if (entry.canAfford && entry.meetsLevel) {
                // Deploy the battle!
                deployBattle(entry.director.getId());
                close();
            }
            return true;
        }

        return true; // Click was in menu area
    }

    private void deployBattle(String directorId) {
        // Send packet to server
        TacticalWarMapNetwork.sendToServer(new PacketDeployBattle(directorId, worldX, worldZ));
    }

    /**
     * Draw the menu.
     */
    public void draw(int mouseX, int mouseY, FontRenderer font) {
        if (!open) return;

        // Refresh CP/Era live while the menu is open, so it reflects command changes (e.g., /war cp add).
        int liveCp = WarMapClientStats.getCommandPoints();
        int liveEra = WarMapClientStats.getEra();

        boolean changed = false;
        if (liveCp >= 0 && liveCp != this.playerCp) {
            this.playerCp = Math.max(0, liveCp);
            changed = true;
        }
        if (liveEra >= 1 && liveEra != this.playerEra) {
            this.playerEra = Math.max(1, liveEra);
            changed = true;
        }

        if (changed) {
            buildEntries();
            int visibleEntries = Math.min(entries.size(), maxVisibleEntries);
            this.height = HEADER_HEIGHT + (visibleEntries * ENTRY_HEIGHT) + FOOTER_HEIGHT;
        }

        int[] adjusted = getAdjustedPosition();
        int x0 = adjusted[0];
        int y0 = adjusted[1];

        // Background
        Gui.drawRect(x0, y0, x0 + width, y0 + height, 0xEE1a1a2e);

        // Border
        Gui.drawRect(x0, y0, x0 + width, y0 + 1, 0xFF3d3d5c);
        Gui.drawRect(x0, y0 + height - 1, x0 + width, y0 + height, 0xFF3d3d5c);
        Gui.drawRect(x0, y0, x0 + 1, y0 + height, 0xFF3d3d5c);
        Gui.drawRect(x0 + width - 1, y0, x0 + width, y0 + height, 0xFF3d3d5c);

        // Header
        String title = TextFormatting.GOLD + "" + TextFormatting.BOLD + "Deploy Battle";
        font.drawString(title, x0 + 6, y0 + 5, 0xFFFFFFFF);

        // Close button
        String closeBtn = TextFormatting.GRAY + "[X]";
        font.drawString(closeBtn, x0 + width - 18, y0 + 5, 0xFFFFFFFF);

        // Coordinates
        String coords = TextFormatting.GRAY + "Location: " + TextFormatting.WHITE + worldX + ", " + worldZ;
        font.drawString(coords, x0 + 6, y0 + 18, 0xFFFFFFFF);

        // Separator
        Gui.drawRect(x0 + 4, y0 + 30, x0 + width - 4, y0 + 31, 0xFF3d3d5c);

        // Entries
        int entryStartY = y0 + HEADER_HEIGHT;
        int visibleCount = Math.min(entries.size() - scrollOffset, maxVisibleEntries);

        hoveredIndex = -1;

        for (int i = 0; i < visibleCount; i++) {
            int idx = scrollOffset + i;
            if (idx >= entries.size()) break;

            MenuEntry entry = entries.get(idx);
            int ey = entryStartY + (i * ENTRY_HEIGHT);

            // Check hover
            boolean hover = mouseX >= x0 && mouseX < x0 + width &&
                    mouseY >= ey && mouseY < ey + ENTRY_HEIGHT;
            if (hover) hoveredIndex = idx;

            // Background for entry
            int bgColor = hover ? 0x44ffffff : 0x22ffffff;
            if (!entry.canAfford || !entry.meetsLevel) {
                bgColor = hover ? 0x44ff4444 : 0x22ff4444;
            }
            Gui.drawRect(x0 + 2, ey, x0 + width - 2, ey + ENTRY_HEIGHT - 1, bgColor);

            // Entry name
            TextFormatting nameColor = (entry.canAfford && entry.meetsLevel)
                    ? TextFormatting.WHITE : TextFormatting.DARK_GRAY;
            font.drawString(nameColor + entry.director.getDisplayName(), x0 + 8, ey + 3, 0xFFFFFFFF);

            // CP cost display (debug can be FREE)
            if (entry.effectiveCpCost <= 0) {
                String freeText = TextFormatting.AQUA + "FREE";
                int w = font.getStringWidth(freeText);
                font.drawString(freeText, x0 + width - w - 8, ey + 3, 0xFFFFFFFF);
            } else {
                TextFormatting cpColor = entry.canAfford ? TextFormatting.GREEN : TextFormatting.RED;
                String cpText = cpColor + "" + entry.effectiveCpCost + " CB";
                int cpWidth = font.getStringWidth(cpText);
                font.drawString(cpText, x0 + width - cpWidth - 8, ey + 3, 0xFFFFFFFF);
            }

            // Description (truncated)
            String desc = entry.director.getDescription();
            if (desc == null) desc = "";
            if (desc.length() > 30) desc = desc.substring(0, 28) + "...";
            font.drawString(TextFormatting.GRAY + desc, x0 + 8, ey + 14, 0xFFFFFFFF);

            // Level requirement indicator
            if (!entry.meetsLevel) {
                String lvlReq = TextFormatting.RED + "(Era " + entry.director.getMinLevel() + "+)";
                font.drawString(lvlReq, x0 + width - font.getStringWidth(lvlReq) - 8, ey + 14, 0xFFFFFFFF);
            }
        }

        // Scroll indicators
        if (scrollOffset > 0) {
            font.drawString(TextFormatting.YELLOW + "▲ More", x0 + width / 2 - 20, entryStartY - 10, 0xFFFFFFFF);
        }
        if (scrollOffset + maxVisibleEntries < entries.size()) {
            int bottomY = entryStartY + (visibleCount * ENTRY_HEIGHT);
            font.drawString(TextFormatting.YELLOW + "▼ More", x0 + width / 2 - 20, bottomY + 2, 0xFFFFFFFF);
        }

        // Footer - CP display
        int footerY = y0 + height - 18;
        Gui.drawRect(x0 + 4, footerY - 2, x0 + width - 4, footerY - 1, 0xFF3d3d5c);
        String cpDisplay = TextFormatting.GRAY + "Your CB: " + TextFormatting.GOLD + playerCp;
        font.drawString(cpDisplay, x0 + 6, footerY + 2, 0xFFFFFFFF);

        String eraDisplay = TextFormatting.GRAY + "Era: " + TextFormatting.AQUA + playerEra;
        int eraWidth = font.getStringWidth(eraDisplay);
        font.drawString(eraDisplay, x0 + width - eraWidth - 8, footerY + 2, 0xFFFFFFFF);
    }

    private int[] getAdjustedPosition() {
        ScaledResolution sr = new ScaledResolution(Minecraft.getMinecraft());
        int screenW = sr.getScaledWidth();
        int screenH = sr.getScaledHeight();

        int x0 = screenX;
        int y0 = screenY;

        // Keep on screen
        if (x0 + width > screenW - 4) {
            x0 = Math.max(4, screenW - width - 4);
        }
        if (y0 + height > screenH - 4) {
            y0 = Math.max(4, screenH - height - 4);
        }

        return new int[]{x0, y0};
    }

    private static String safeLower(String s) {
        return s == null ? "" : s.toLowerCase();
    }

    private static String stripFormatting(String s) {
        if (s == null) return "";
        return TextFormatting.getTextWithoutFormattingCodes(s);
    }

    private static int tryExtractInt(String s) {
        if (s == null) return -1;
        Matcher m = INT_PATTERN.matcher(s);
        if (!m.find()) return -1;
        try {
            return Integer.parseInt(m.group(1));
        } catch (NumberFormatException ignored) {
            return -1;
        }
    }

    private static PlayerStats resolvePlayerStatsFromSidebar() {
        Minecraft mc = Minecraft.getMinecraft();
        if (mc == null || mc.player == null || mc.world == null) {
            return new PlayerStats(0, 1);
        }

        Scoreboard sb = mc.world.getScoreboard();
        if (sb == null) {
            return new PlayerStats(0, 1);
        }

        // Slot 1 is SIDEBAR in 1.12.x
        ScoreObjective obj = sb.getObjectiveInDisplaySlot(1);
        if (obj == null) {
            return new PlayerStats(0, 1);
        }

        Collection<Score> scores;
        try {
            scores = sb.getSortedScores(obj);
        } catch (Throwable t) {
            return new PlayerStats(0, 1);
        }

        int bestCp = -1;
        int bestEra = -1;

        for (Score score : scores) {
            if (score == null) continue;

            String rawName = score.getPlayerName();
            if (rawName == null) continue;

            // Scoreboard lines may be team-formatted; resolve to the displayed line.
            String rendered = rawName;
            ScorePlayerTeam team = sb.getPlayersTeam(rawName);
            if (team != null) {
                rendered = team.formatString(rawName);
            }

            String clean = stripFormatting(rendered).trim();
            if (clean.isEmpty()) continue;

            String lower = clean.toLowerCase();

            // Prefer explicit "CP" token matches, but also accept lines like "Command Points: 123"
            if (CP_PATTERN.matcher(lower).find() || lower.contains("command point")) {
                int v = tryExtractInt(lower);
                if (v >= 0) bestCp = Math.max(bestCp, v);
            }

            if (ERA_PATTERN.matcher(lower).find() || lower.contains("level") && lower.contains("era")) {
                int v = tryExtractInt(lower);
                if (v >= 0) bestEra = Math.max(bestEra, v);
            }
        }

        return new PlayerStats(bestCp >= 0 ? bestCp : 0, bestEra >= 1 ? bestEra : 1);
    }

    private static final class PlayerStats {
        final int cp;
        final int era;

        PlayerStats(int cp, int era) {
            this.cp = cp;
            this.era = era;
        }
    }

    /**
     * Internal class representing a menu entry.
     */
    private static class MenuEntry {
        final BattleDirectorEntry director;
        final boolean canAfford;
        final boolean meetsLevel;
        final int effectiveCpCost;

        MenuEntry(BattleDirectorEntry director, boolean canAfford, boolean meetsLevel, int effectiveCpCost) {
            this.director = director;
            this.canAfford = canAfford;
            this.meetsLevel = meetsLevel;
            this.effectiveCpCost = Math.max(0, effectiveCpCost);
        }
    }
}
