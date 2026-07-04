package studio.ERM.strategic.civil.research;

import net.minecraft.client.gui.GuiScreen;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.util.math.BlockPos;
import studio.ERM.war.map.client.ClientResearchCache;
import studio.ERM.war.map.net.C2SResearchAction;
import studio.ERM.war.map.net.S2CResearchSync;
import studio.ERM.war.map.net.TacticalWarMapNetwork;

import java.io.IOException;

/**
 * THE RESEARCH TREE — a Civ-style, left-to-right tech tree of AW2's research nodes laid out by
 * dependency depth, with connector lines and per-status colours (locked / available / researching /
 * complete). Drag to pan. Click an AVAILABLE node to begin researching it (costs Command Bucks up
 * front; the Research district's scientists then grind the worker-time). Empty if AW2 is absent.
 */
public class GuiResearchTree extends GuiScreen {

    private static final int COL_SP = 150, ROW_SP = 34, CARD_W = 120, CARD_H = 26;

    private final BlockPos depot;
    private double panX = 0, panY = 0;
    private boolean dragging;
    private int lastX, lastY;
    private boolean centered;

    public GuiResearchTree(EntityPlayer player, BlockPos depot) {
        this.depot = depot;
    }

    @Override
    public void initGui() {
        TacticalWarMapNetwork.sendToServer(new C2SResearchAction(C2SResearchAction.REQUEST_SYNC, ""));
    }

    @Override
    public boolean doesGuiPauseGame() { return false; }

    private int nodeX(S2CResearchSync.Node n) { return (int) (30 + n.column * COL_SP + panX); }
    private int nodeY(S2CResearchSync.Node n) { return (int) (72 + n.row * ROW_SP + panY); }

    @Override
    public void drawScreen(int mouseX, int mouseY, float partialTicks) {
        drawDefaultBackground();
        S2CResearchSync s = ClientResearchCache.get();

        if (!s.aw2Present) {
            drawCenteredString(fontRenderer, "Ancient Warfare 2 research is not available.",
                    width / 2, height / 2, 0xFFFF5555);
            drawTopBar(s);
            return;
        }
        if (!centered && !s.nodes.isEmpty()) { centerOnCurrent(s); centered = true; }

        // Dependency connectors (behind the cards).
        for (S2CResearchSync.Node n : s.nodes) {
            int nx = nodeX(n), ny = nodeY(n) + CARD_H / 2;
            for (String dep : n.deps) {
                S2CResearchSync.Node d = find(s, dep);
                if (d == null) continue;
                int dx = nodeX(d) + CARD_W, dy = nodeY(d) + CARD_H / 2;
                int midX = (dx + nx) / 2;
                int col = 0x99506070;
                drawRect(Math.min(dx, midX), dy, Math.max(dx, midX) + 1, dy + 1, col);
                drawRect(midX, Math.min(dy, ny), midX + 1, Math.max(dy, ny) + 1, col);
                drawRect(Math.min(midX, nx), ny, Math.max(midX, nx) + 1, ny + 1, col);
            }
        }

        // Node cards.
        for (S2CResearchSync.Node n : s.nodes) {
            int x = nodeX(n), y = nodeY(n);
            if (x > width || x + CARD_W < 0 || y > height || y + CARD_H < 0) continue; // cull
            drawCard(n, x, y, s);
        }

        drawTopBar(s);
        drawLegend();
    }

    private void drawCard(S2CResearchSync.Node n, int x, int y, S2CResearchSync s) {
        int bg, border, text;
        switch (n.status) {
            case S2CResearchSync.COMPLETE:    bg = 0xE01B3A1B; border = 0xFF69F0AE; text = 0xFFDFFFDF; break;
            case S2CResearchSync.RESEARCHING: bg = 0xE02A2A10; border = 0xFFFFC400; text = 0xFFFFFFFF; break;
            case S2CResearchSync.AVAILABLE:   bg = 0xE0102838; border = 0xFF00E5FF; text = 0xFFFFFFFF; break;
            default:                          bg = 0xE01A1A1A; border = 0xFF444444; text = 0xFF888888; break;
        }
        drawRect(x, y, x + CARD_W, y + CARD_H, bg);
        // Researching: progress fill.
        if (n.status == S2CResearchSync.RESEARCHING && s.currentPct > 0) {
            drawRect(x, y + CARD_H - 4, x + (int) (CARD_W * s.currentPct), y + CARD_H, 0x9900AAFF);
        }
        // Border.
        drawRect(x, y, x + CARD_W, y + 1, border);
        drawRect(x, y + CARD_H - 1, x + CARD_W, y + CARD_H, border);
        drawRect(x, y, x + 1, y + CARD_H, border);
        drawRect(x + CARD_W - 1, y, x + CARD_W, y + CARD_H, border);

        fontRenderer.drawString(trim(n.name, 20), x + 4, y + 4, text);
        String sub = n.status == S2CResearchSync.COMPLETE ? "§aResearched"
                : n.status == S2CResearchSync.RESEARCHING ? ("§e" + (int) (s.currentPct * 100) + "%")
                : n.status == S2CResearchSync.AVAILABLE ? ("§b" + n.cbCost + " CB")
                : "§8Locked";
        fontRenderer.drawString(sub, x + 4, y + 15, 0xFFFFFFFF);
    }

    private void drawTopBar(S2CResearchSync s) {
        drawRect(0, 0, width, 22, 0xF00A0A12);
        drawRect(0, 22, width, 23, 0xFF69F0AE);
        fontRenderer.drawString("§bResearch Tree", 8, 7, 0xFFFFFF);
        String cur = s.currentId == null || s.currentId.isEmpty() ? "§7(nothing researching)"
                : "§eResearching: §f" + trim(nameOf(s, s.currentId), 22) + " §7" + (int) (s.currentPct * 100) + "%";
        int cw = fontRenderer.getStringWidth(net.minecraft.util.text.TextFormatting.getTextWithoutFormattingCodes(cur));
        fontRenderer.drawString(cur, (width - cw) / 2, 7, 0xFFFFFF);
        String cb = "CB: " + s.commandBucks;
        fontRenderer.drawString("§6" + cb, width - 8 - fontRenderer.getStringWidth(cb), 7, 0xFFFFFF);
    }

    private void drawLegend() {
        int y = height - 14;
        fontRenderer.drawString("§7Drag to pan · click an §bavailable §7node to research it", 8, y, 0xFFFFFF);
    }

    private void centerOnCurrent(S2CResearchSync s) {
        S2CResearchSync.Node focus = null;
        for (S2CResearchSync.Node n : s.nodes) {
            if (n.status == S2CResearchSync.RESEARCHING) { focus = n; break; }
            if (focus == null && n.status == S2CResearchSync.AVAILABLE) focus = n;
        }
        if (focus == null && !s.nodes.isEmpty()) focus = s.nodes.get(0);
        if (focus != null) {
            panX = (width / 2.0 - CARD_W / 2.0) - (30 + focus.column * COL_SP);
            panY = (height / 2.0) - (72 + focus.row * ROW_SP);
        }
    }

    private static S2CResearchSync.Node find(S2CResearchSync s, String id) {
        for (S2CResearchSync.Node n : s.nodes) if (n.id.equals(id)) return n;
        return null;
    }

    private static String nameOf(S2CResearchSync s, String id) {
        S2CResearchSync.Node n = find(s, id);
        return n != null ? n.name : id;
    }

    private static String trim(String s, int max) {
        return s.length() > max ? s.substring(0, max - 1) + "…" : s;
    }

    @Override
    protected void mouseClicked(int mouseX, int mouseY, int mouseButton) throws IOException {
        super.mouseClicked(mouseX, mouseY, mouseButton);
        if (mouseButton != 0) return;
        S2CResearchSync s = ClientResearchCache.get();
        for (S2CResearchSync.Node n : s.nodes) {
            int x = nodeX(n), y = nodeY(n);
            if (mouseX >= x && mouseX < x + CARD_W && mouseY >= y && mouseY < y + CARD_H) {
                if (n.status == S2CResearchSync.AVAILABLE) {
                    TacticalWarMapNetwork.sendToServer(new C2SResearchAction(C2SResearchAction.QUEUE, n.id));
                }
                return; // a click on a card never starts a pan
            }
        }
        dragging = true;
        lastX = mouseX;
        lastY = mouseY;
    }

    @Override
    protected void mouseClickMove(int mouseX, int mouseY, int mouseButton, long timeSinceLastClick) {
        if (dragging) {
            panX += mouseX - lastX;
            panY += mouseY - lastY;
            lastX = mouseX;
            lastY = mouseY;
        }
    }

    @Override
    protected void mouseReleased(int mouseX, int mouseY, int state) {
        dragging = false;
        super.mouseReleased(mouseX, mouseY, state);
    }
}
