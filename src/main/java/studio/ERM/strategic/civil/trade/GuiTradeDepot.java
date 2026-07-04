package studio.ERM.strategic.civil.trade;

import net.minecraft.client.gui.inventory.GuiContainer;
import net.minecraft.client.renderer.GlStateManager;
import net.minecraft.client.renderer.RenderHelper;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.item.ItemStack;
import net.minecraft.util.math.BlockPos;
import studio.ERM.war.config.TradePriceConfig;
import studio.ERM.war.map.client.ClientTradeCache;
import studio.ERM.war.map.net.C2STradeAction;
import studio.ERM.war.map.net.S2CTradeSync;
import studio.ERM.war.map.net.TacticalWarMapNetwork;

import java.util.ArrayList;
import java.util.List;

/**
 * THE TRADE DEPOT — a four-tab market screen (Buy / Sell / Market / Agreements) driven by the
 * {@link S2CTradeSync} snapshot. Buy orders and sell exports are packets; goods move as timed
 * shipments (see {@link TradeShipmentManager}). Everything is drawn in {@link #drawScreen} with
 * absolute screen coordinates so the click hit-boxes line up exactly with what's rendered.
 */
public class GuiTradeDepot extends GuiContainer {

    private static final String[] TABS = {"Buy", "Sell", "Market", "Agreements"};
    private static final int W = 256, H = 208;
    private static final int ROW_H = 15, VISIBLE = 9;

    private final BlockPos depot;
    private int tab = 0;
    private int scroll = 0;
    private final List<Hit> hits = new ArrayList<>();

    private static final class Hit {
        final int x1, y1, x2, y2, op, count; final String id;
        Hit(int x1, int y1, int x2, int y2, int op, int count, String id) {
            this.x1 = x1; this.y1 = y1; this.x2 = x2; this.y2 = y2; this.op = op; this.count = count; this.id = id;
        }
        boolean in(int mx, int my) { return mx >= x1 && mx < x2 && my >= y1 && my < y2; }
    }

    public GuiTradeDepot(EntityPlayer player, BlockPos depot) {
        super(new ContainerTradeDepot(player, depot));
        this.depot = depot;
        this.xSize = W;
        this.ySize = H;
    }

    @Override
    public void initGui() {
        super.initGui();
        requestSync();
    }

    private void requestSync() {
        TacticalWarMapNetwork.sendToServer(new C2STradeAction(C2STradeAction.REQUEST_SYNC, "", 0, depot));
    }

    @Override
    protected void drawGuiContainerBackgroundLayer(float partialTicks, int mouseX, int mouseY) {
        // Panel only; all content is drawn in drawScreen (absolute coords).
        drawRect(guiLeft, guiTop, guiLeft + W, guiTop + H, 0xF00E0E16);
        drawRect(guiLeft, guiTop, guiLeft + W, guiTop + 1, 0xFF69F0AE);
    }

    @Override
    protected void drawGuiContainerForegroundLayer(int mouseX, int mouseY) { /* content is in drawScreen */ }

    @Override
    public void drawScreen(int mouseX, int mouseY, float partialTicks) {
        drawDefaultBackground();
        super.drawScreen(mouseX, mouseY, partialTicks);

        hits.clear();
        S2CTradeSync s = ClientTradeCache.get();
        int L = guiLeft, T = guiTop;

        fontRenderer.drawString("Trade Depot", L + 8, T + 6, 0x69F0AE);
        String cb = "CB: " + s.commandBucks + "   Rival Lv " + s.rivalLevel;
        fontRenderer.drawString(cb, L + W - 6 - fontRenderer.getStringWidth(cb), T + 6, 0xFFD54F);

        int tabW = 56;
        for (int i = 0; i < TABS.length; i++) {
            int tx = L + 6 + i * (tabW + 2);
            boolean sel = i == tab;
            drawRect(tx, T + 16, tx + tabW, T + 28, sel ? 0xFF1565C0 : 0xAA202030);
            if (sel) drawRect(tx, T + 27, tx + tabW, T + 28, 0xFFFFFFFF);
            int tw = fontRenderer.getStringWidth(TABS[i]);
            fontRenderer.drawString(TABS[i], tx + (tabW - tw) / 2, T + 19, sel ? 0xFFFFFF : 0xB0BEC5);
            hits.add(new Hit(tx, T + 16, tx + tabW, T + 28, -1, i, null));
        }

        int top = T + 32;
        switch (tab) {
            case 0: drawBuy(s, L, top); break;
            case 1: drawSell(s, L, top); break;
            case 2: drawMarket(s, L, top); break;
            default: drawAgreements(s, L, top); break;
        }
    }

    private void drawBuy(S2CTradeSync s, int L, int top) {
        List<Object> lines = new ArrayList<>();
        String cat = null;
        for (S2CTradeSync.Row r : s.rows) {
            if (!r.category.equals(cat)) { cat = r.category; lines.add(cat); }
            lines.add(r);
        }
        int y = top, shown = 0;
        for (int i = scroll; i < lines.size() && shown < VISIBLE; i++, shown++) {
            Object o = lines.get(i);
            if (o instanceof String) {
                fontRenderer.drawString("§e" + o, L + 8, y + 2, 0xFFC400);
            } else {
                S2CTradeSync.Row r = (S2CTradeSync.Row) o;
                icon(r.id, L + 8, y);
                fontRenderer.drawString(trimName(r.id, 18), L + 28, y + 3, r.available ? 0xE0E0E0 : 0x777777);
                String price = fmt(r.price) + " CB";
                fontRenderer.drawString(price, L + 172 - fontRenderer.getStringWidth(price), y + 3, 0xFFD54F);
                if (r.available) {
                    int bx1 = L + 178, bx2 = L + 214;
                    drawRect(bx1, y, bx2, y + 12, 0xFF1B5E20);
                    fontRenderer.drawString("Buy", bx1 + 10, y + 2, 0xFFFFFF);
                    hits.add(new Hit(bx1, y, bx2, y + 12, C2STradeAction.BUY, 1, r.id));
                } else {
                    fontRenderer.drawString("Lv " + r.minLevel, L + 182, y + 2, 0xFF8A80);
                }
            }
            y += ROW_H;
        }
        scrollHint(L, lines.size());
        fontRenderer.drawString("§7Buy = 1 (Shift = 16). Merchants deliver on a delay.",
                L + 8, guiTop + H - 12, 0x808080);
    }

    private void drawSell(S2CTradeSync s, int L, int top) {
        List<S2CTradeSync.Row> stocked = new ArrayList<>();
        for (S2CTradeSync.Row r : s.rows) if (r.stored > 0) stocked.add(r);
        int y = top, shown = 0;
        if (stocked.isEmpty()) {
            fontRenderer.drawString("§7No surplus in connected warehouses.", L + 8, y + 4, 0x808080);
        }
        int[] amts = {64, 256, -1};
        String[] labels = {"64", "256", "All"};
        for (int i = scroll; i < stocked.size() && shown < VISIBLE; i++, shown++) {
            S2CTradeSync.Row r = stocked.get(i);
            icon(r.id, L + 8, y);
            fontRenderer.drawString(trimName(r.id, 12), L + 28, y + 3, 0xE0E0E0);
            String meta = r.stored + " @ " + fmt(r.price);
            fontRenderer.drawString(meta, L + 128 - fontRenderer.getStringWidth(meta), y + 3, 0xFFD54F);
            for (int b = 0; b < 3; b++) {
                int bx1 = L + 132 + b * 40, bx2 = bx1 + 36;
                drawRect(bx1, y, bx2, y + 12, 0xFF37474F);
                int lw = fontRenderer.getStringWidth(labels[b]);
                fontRenderer.drawString(labels[b], bx1 + (36 - lw) / 2, y + 2, 0xFFFFFF);
                int count = amts[b] == -1 ? r.stored : Math.min(amts[b], r.stored);
                hits.add(new Hit(bx1, y, bx2, y + 12, C2STradeAction.SELL, count, r.id));
            }
            y += ROW_H;
        }
        scrollHint(L, stocked.size());
        fontRenderer.drawString("§7CB paid when the shipment leaves the settlement.",
                L + 8, guiTop + H - 12, 0x808080);
    }

    private void drawMarket(S2CTradeSync s, int L, int top) {
        fontRenderer.drawString("§7Item", L + 28, top, 0x808080);
        fontRenderer.drawString("§7Price", L + 150, top, 0x808080);
        fontRenderer.drawString("§7Demand", L + 200, top, 0x808080);
        int y = top + 12, shown = 0;
        for (int i = scroll; i < s.rows.size() && shown < VISIBLE; i++, shown++) {
            S2CTradeSync.Row r = s.rows.get(i);
            icon(r.id, L + 8, y);
            fontRenderer.drawString(trimName(r.id, 20), L + 28, y + 3, 0xE0E0E0);
            fontRenderer.drawString(fmt(r.price), L + 150, y + 3, 0xFFD54F);
            fontRenderer.drawString(demandColor(r.demand) + r.demand, L + 200, y + 3, 0xFFFFFF);
            y += ROW_H;
        }
        scrollHint(L, s.rows.size());
        fontRenderer.drawString("§7Prices fall as you flood the market; they recover over time.",
                L + 8, guiTop + H - 12, 0x808080);
    }

    private void drawAgreements(S2CTradeSync s, int L, int top) {
        int y = top;
        for (S2CTradeSync.Agreement a : s.agreements) {
            drawRect(L + 6, y, L + W - 6, y + 44, 0x66202030);
            fontRenderer.drawString("§b" + a.name, L + 12, y + 3, 0x80D8FF);
            fontRenderer.drawString("§7Status: §f" + a.status, L + 12, y + 15, 0xFFFFFF);
            fontRenderer.drawString("§7Imports: §f" + a.imports, L + 12, y + 26, 0xFFFFFF);
            fontRenderer.drawString("§7Exports: §f" + a.exports, L + 12, y + 37, 0xFFFFFF);
            y += 48;
        }
        fontRenderer.drawString("§7Full diplomacy (agreements affect price/availability) is upcoming.",
                L + 8, guiTop + H - 12, 0x808080);
    }

    private void scrollHint(int L, int total) {
        if (total > VISIBLE) fontRenderer.drawString("§7scroll ▲▼", L + W - 56, guiTop + 20, 0x808080);
    }

    private void icon(String id, int x, int y) {
        ItemStack st = TradePriceConfig.resolve(id);
        if (st.isEmpty()) return;
        RenderHelper.enableGUIStandardItemLighting();
        mc.getRenderItem().renderItemIntoGUI(st, x, y);
        RenderHelper.disableStandardItemLighting();
        GlStateManager.disableLighting();
    }

    private String trimName(String id, int max) {
        ItemStack st = TradePriceConfig.resolve(id);
        String n = st.isEmpty() ? id : st.getDisplayName();
        return n.length() > max ? n.substring(0, max - 1) + "…" : n;
    }

    private static String fmt(float v) { return String.format("%.1f", v); }

    private static String demandColor(String d) {
        switch (d) {
            case "Very High": return "§a";
            case "High": return "§2";
            case "Medium": return "§e";
            default: return "§c";
        }
    }

    @Override
    protected void mouseClicked(int mouseX, int mouseY, int mouseButton) throws java.io.IOException {
        super.mouseClicked(mouseX, mouseY, mouseButton);
        if (mouseButton != 0) return;
        for (Hit h : new ArrayList<>(hits)) {
            if (!h.in(mouseX, mouseY)) continue;
            if (h.op == -1) { tab = h.count; scroll = 0; return; }
            int count = h.count;
            if (h.op == C2STradeAction.BUY && isShiftKeyDown()) count = 16;
            TacticalWarMapNetwork.sendToServer(new C2STradeAction(h.op, h.id, count, depot));
            return; // server replies with a fresh sync
        }
    }

    @Override
    public void handleMouseInput() throws java.io.IOException {
        super.handleMouseInput();
        int dw = org.lwjgl.input.Mouse.getEventDWheel();
        if (dw > 0 && scroll > 0) scroll--;
        else if (dw < 0) scroll++;
    }

    @Override
    public boolean doesGuiPauseGame() { return false; }
}
