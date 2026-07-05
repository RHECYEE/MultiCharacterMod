package studio.ERM.war.map.client;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Gui;
import net.minecraft.client.gui.GuiScreen;
import net.minecraft.util.text.TextFormatting;
import studio.ERM.strategic.civil.StrategicMissionManager;
import studio.ERM.war.map.net.C2SMissionDispatch;
import studio.ERM.war.map.net.TacticalWarMapNetwork;

import java.util.ArrayList;
import java.util.List;

/**
 * MISSION DISPATCH — assign PLAYER-PROXY CHARACTERS (skilled, never die) and CIVILIAN escorts (do the
 * work, but are at risk) to a strategic mission at the map target. A live RISK readout reacts to the
 * mission's danger and the party's competence (character count + their role skill for the mission).
 */
public class GuiMissionDispatch extends GuiScreen {

    private static final int W = 232, H = 196;
    private final int targetX, targetZ;

    private int mission = 0;
    private int characters = 0;
    private int citizens = 3;
    private int rosterSize = 0;

    private final List<int[]> hits = new ArrayList<>(); // {x1,y1,x2,y2, action} — action: 0..5 mission, 100 char-,101 char+,110 cit-,111 cit+,200 launch
    private int panelL, panelT;

    public GuiMissionDispatch(int targetX, int targetZ) {
        this.targetX = targetX;
        this.targetZ = targetZ;
    }

    @Override
    public void initGui() {
        panelL = (width - W) / 2;
        panelT = (height - H) / 2;
        try {
            rosterSize = co.runed.multicharacter.MultiCharacterMod.getCharacterManager()
                    .getCharacters(mc.player.getUniqueID()).size();
        } catch (Throwable t) { rosterSize = 0; }
        characters = Math.min(characters, rosterSize);
    }

    @Override
    public boolean doesGuiPauseGame() { return false; }

    /** Client-side risk estimate: sum the top-N characters' skill for this mission (mirrors server). */
    private int charSkillUnits() {
        int units = 0;
        try {
            List<co.runed.multicharacter.character.Character> roster =
                    co.runed.multicharacter.MultiCharacterMod.getCharacterManager()
                            .getCharacters(mc.player.getUniqueID());
            int take = Math.max(0, Math.min(characters, roster.size()));
            for (int i = 0; i < take; i++) {
                int best = 1;
                if (roster.get(i).getRoles() != null) {
                    for (String role : roster.get(i).getRoles()) {
                        best = Math.max(best, StrategicMissionManager.charSkillBonus(role, mission));
                    }
                }
                units += best;
            }
        } catch (Throwable ignored) {}
        return units;
    }

    @Override
    public void drawScreen(int mouseX, int mouseY, float partialTicks) {
        drawDefaultBackground();
        hits.clear();
        int L = panelL, T = panelT;
        Gui.drawRect(L, T, L + W, T + H, 0xF00E0E16);
        Gui.drawRect(L, T, L + W, T + 1, 0xFF69F0AE);

        fontRenderer.drawString(TextFormatting.GOLD + "Mission Dispatch", L + 8, T + 6, 0xFFFFFF);
        fontRenderer.drawString(TextFormatting.GRAY + "Target " + targetX + ", " + targetZ,
                L + W - 8 - fontRenderer.getStringWidth("Target " + targetX + ", " + targetZ), T + 6, 0xFFFFFF);

        // Mission list.
        int y = T + 20;
        for (int i = 0; i < StrategicMissionManager.NAMES.length; i++) {
            boolean sel = i == mission;
            Gui.drawRect(L + 6, y, L + W - 6, y + 13, sel ? 0xFF1565C0 : (mouseIn(mouseX, mouseY, L + 6, y, L + W - 6, y + 13) ? 0xCC2C3A47 : 0x9910101E));
            fontRenderer.drawString((sel ? TextFormatting.WHITE : TextFormatting.GRAY) + StrategicMissionManager.NAMES[i],
                    L + 10, y + 3, 0xFFFFFF);
            hits.add(new int[]{L + 6, y, L + W - 6, y + 13, i});
            y += 15;
        }

        // Party rows.
        y += 2;
        drawStepper(L, y, "Characters", characters + "/" + rosterSize, 100, 101, mouseX, mouseY);
        y += 16;
        drawStepper(L, y, "Citizens", String.valueOf(citizens), 110, 111, mouseX, mouseY);
        y += 20;

        // Risk + reward.
        double risk = StrategicMissionManager.computeRisk(mission, charSkillUnits(), citizens);
        int pct = (int) Math.round(risk * 100);
        String riskColor = pct >= 60 ? "§c" : pct >= 30 ? "§e" : "§a";
        fontRenderer.drawString("Risk to escorts: " + riskColor + pct + "%", L + 8, y, 0xFFFFFF);
        y += 11;
        fontRenderer.drawString(TextFormatting.GRAY + "Reward: " + StrategicMissionManager.REWARDS[mission], L + 8, y, 0xFFFFFF);
        y += 11;
        fontRenderer.drawString(TextFormatting.DARK_GRAY + "Characters never die; escorts may be lost.", L + 8, y, 0xFFFFFF);

        // Launch button.
        int bx1 = L + W - 74, bx2 = L + W - 8, by1 = T + H - 20, by2 = T + H - 6;
        Gui.drawRect(bx1, by1, bx2, by2, 0xFF1B5E20);
        fontRenderer.drawString(TextFormatting.WHITE + "DISPATCH", bx1 + 8, by1 + 3, 0xFFFFFF);
        hits.add(new int[]{bx1, by1, bx2, by2, 200});

        super.drawScreen(mouseX, mouseY, partialTicks);
    }

    private void drawStepper(int L, int y, String label, String value, int minusAction, int plusAction, int mx, int my) {
        fontRenderer.drawString(TextFormatting.GRAY + label, L + 8, y + 2, 0xFFFFFF);
        int mnx = L + 120, pnx = L + 168;
        Gui.drawRect(mnx, y, mnx + 12, y + 12, 0xFF37474F);
        fontRenderer.drawString("-", mnx + 4, y + 2, 0xFFFF8A80);
        fontRenderer.drawString(value, L + 138, y + 2, 0xFFFFD54F);
        Gui.drawRect(pnx, y, pnx + 12, y + 12, 0xFF37474F);
        fontRenderer.drawString("+", pnx + 4, y + 2, 0xFF69F0AE);
        hits.add(new int[]{mnx, y, mnx + 12, y + 12, minusAction});
        hits.add(new int[]{pnx, y, pnx + 12, y + 12, plusAction});
    }

    private static boolean mouseIn(int mx, int my, int x1, int y1, int x2, int y2) {
        return mx >= x1 && mx < x2 && my >= y1 && my < y2;
    }

    @Override
    protected void mouseClicked(int mouseX, int mouseY, int mouseButton) throws java.io.IOException {
        super.mouseClicked(mouseX, mouseY, mouseButton);
        if (mouseButton != 0) return;
        for (int[] h : hits) {
            if (mouseX < h[0] || mouseX >= h[2] || mouseY < h[1] || mouseY >= h[3]) continue;
            int a = h[4];
            if (a >= 0 && a < StrategicMissionManager.NAMES.length) mission = a;
            else if (a == 100) characters = Math.max(0, characters - 1);
            else if (a == 101) characters = Math.min(rosterSize, characters + 1);
            else if (a == 110) citizens = Math.max(0, citizens - 1);
            else if (a == 111) citizens = Math.min(16, citizens + 1);
            else if (a == 200) {
                TacticalWarMapNetwork.sendToServer(new C2SMissionDispatch(mission, characters, citizens, targetX, targetZ));
                Minecraft.getMinecraft().displayGuiScreen(null);
            }
            return;
        }
    }
}
