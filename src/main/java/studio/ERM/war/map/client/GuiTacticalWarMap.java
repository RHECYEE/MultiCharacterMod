package studio.ERM.war.map.client;

import net.minecraft.block.material.MapColor;
import net.minecraft.block.state.IBlockState;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Gui;
import net.minecraft.client.gui.GuiScreen;
import net.minecraft.client.gui.ScaledResolution;
import net.minecraft.client.renderer.GlStateManager;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.ChunkPos;
import net.minecraft.util.text.TextFormatting;
import net.minecraft.world.World;
import net.minecraft.world.chunk.Chunk;
import studio.ERM.war.WarClaimHandler;
import studio.ERM.war.map.net.C2SPacketBatchClaim;
import studio.ERM.war.map.net.C2SRequestTerritorySync;
import studio.ERM.war.map.net.TacticalWarMapNetwork;

import java.io.IOException;
import java.util.*;

/**
 * Full-screen tactical war map with territory claiming interface.
 *
 * Controls:
 *   - Left-click drag: Pan the map
 *   - Mouse wheel: Zoom in/out
 *   - Shift + Left-click drag: Select area to CLAIM territory
 *   - Shift + Right-click drag: Select area to UNCLAIM territory
 *   - Right-click: Context menu (deploy battle, recenter, copy coords)
 *   - ESC: Close
 *
 * Territory is visualized as colored chunk overlays with frontline borders.
 */
public class GuiTacticalWarMap extends GuiScreen {

    // ==================== View State ====================
    private int viewCenterX; // world X at center of canvas
    private int viewCenterZ; // world Z at center of canvas

    private static final int[] ZOOM_LEVELS = {2, 4, 8, 16, 32}; // blocks per pixel
    private int zoomIndex = 2; // default 8 bpp

    private int canvasSize = 512;
    private int canvasLeft, canvasTop;

    // Layout constants used by initGui() to keep the map + sidebar on-screen at any GUI scale.
    private static final int SIDEBAR_WIDTH = 92; // right-hand stats/controls/legend column
    private static final int LEFT_MARGIN = 12;
    private static final int RIGHT_MARGIN = 8;

    // ==================== Pan Dragging ====================
    private boolean panning = false;
    private int panStartMouseX, panStartMouseY;
    private int panStartCenterX, panStartCenterZ;
    private static final int MAX_PAN_RADIUS = 8192;

    // ==================== Territory Claim Selection ====================
    private boolean claimDragging = false;
    private boolean unclaimDragging = false;
    private int selectStartMouseX, selectStartMouseY;
    private int selectCurrentMouseX, selectCurrentMouseY;

    /** Pending chunks highlighted during current drag (preview). */
    private final Set<ChunkPos> pendingSelection = new HashSet<>();

    // ==================== Context Menu ====================
    private final GuiBattleDeployMenu deployMenu = new GuiBattleDeployMenu();

    // ==================== Map TABS: Claims / Civilian / Military ====================
    // The war features (defense planning, FALL BACK, battle deployment, military traffic) live on
    // their own MILITARY tab so the base map stays clean. CLAIMS = territory + claiming; CIVILIAN =
    // the civilian economy in motion (traders/carts/couriers). Selection persists across opens.
    private static final String[] TABS = {"CLAIMS", "CIVILIAN", "MILITARY"};
    private static int activeTab = 0;

    // ==================== PHASE 2: Defensive Planning (Military overlay) ====================
    // planMode: -1 = off, else the DefenseMarker type being placed. P cycles modes. In plan mode,
    // left-click places (point markers commit instantly; polylines accumulate clicks), right-click
    // commits a >=2-point polyline / discards a 1-point one / removes the nearest marker.
    private int planMode = -1;
    private final List<net.minecraft.util.math.BlockPos> planPending = new ArrayList<>();
    private static final int[] PLAN_COLORS = {
            0xFF00E5FF, // LINE cyan
            0xFFFFC400, // STRONGPOINT gold
            0xFF8D9DB6, // VEHICLE steel blue
            0xFFB750FF, // AA purple
            0xFFFFFFFF, // RALLY white
            0xFF2E7D32, // RESERVE dark green
            0xFFFF6D00, // FALLBACK orange
            0xFF76FF03, // PATROL light green
            0xFF40C4FF, // LZ sky blue
            0xFFFF8A80, // MEDICAL red-white
            0xFFFF1744  // ENGAGEMENT ZONE red
    };
    // Mouse position captured each frame for marker hover readouts.
    private int uiMouseX, uiMouseY;

    // ==================== Status Messages ====================
    private String statusMessage = "";
    private long statusExpiry = 0;

    // ==================== Colors ====================
    private static final int COLOR_PLAYER_FILL = 0x3300AA00;    // green, semi-transparent
    private static final int COLOR_RIVAL_FILL  = 0x33CC0000;    // red, semi-transparent
    private static final int COLOR_OTHER_FILL  = 0x330066CC;    // blue, semi-transparent
    private static final int COLOR_CLAIM_PREVIEW  = 0x5500FF00; // bright green preview
    private static final int COLOR_UNCLAIM_PREVIEW = 0x55FF4444;// red preview
    private static final int COLOR_BORDER_PLAYER = 0xFF00DD00;  // solid green border
    private static final int COLOR_BORDER_RIVAL  = 0xFFDD0000;  // solid red border
    private static final int COLOR_BORDER_OTHER  = 0xFF4488DD;  // solid blue border
    private static final int COLOR_SELECTION_BOX = 0xAAFFFFFF;  // white selection rectangle
    private static final int COLOR_BG = 0xFF111122;             // dark background
    private static final int COLOR_FOG = 0xFF1a1a2e;            // fog of war
    private static final int COLOR_GRID = 0x22FFFFFF;           // faint chunk grid
    private static final int COLOR_UNEXPLORED = 0xFF0E0F14;     // chunk not loaded on the client

    // ==================== Real terrain sampling ====================
    // The map now samples ACTUAL world surface blocks (their vanilla map colours + height relief)
    // instead of drawing a meaningless green hash. Sampling is comparatively expensive, so the
    // result is cached and only re-sampled when the view (center / zoom / canvas) actually changes.
    private static final int TERRAIN_PIXEL_STEP = 2;            // sample one column per 2x2 px block
    private int[] terrainColors;                                // ARGB per sample cell; 0 = unexplored
    private int terrainGridW, terrainGridH;
    private int terrainCacheCenterX, terrainCacheCenterZ, terrainCacheBpp, terrainCacheCanvas;
    private boolean terrainCacheValid = false;

    public GuiTacticalWarMap() {
    }

    @Override
    public void initGui() {
        super.initGui();

        // Size + place the canvas so it ALWAYS fits the current screen.
        //
        // The old code forced canvasSize >= 256 (Math.max(256, ...)). On higher GUI
        // scales the scaled screen can be smaller than that, which pushed canvasLeft/
        // canvasTop negative and ran the map (and its sidebar) off-screen -- the
        // "scaled wrong / doesn't fit the screen" bug. We instead derive the size from
        // the space actually available after reserving the sidebar column and margins.
        ScaledResolution sr = new ScaledResolution(mc);
        int sw = sr.getScaledWidth();
        int sh = sr.getScaledHeight();

        final int sidebarW = SIDEBAR_WIDTH;   // stats / controls / legend column on the right
        final int gap = 6;                    // gap between canvas and sidebar
        final int topMargin = 18;             // room for the title above the canvas
        final int bottomMargin = 24;          // room for zoom + mode labels below

        int availW = sw - (LEFT_MARGIN + gap + sidebarW + RIGHT_MARGIN);
        int availH = sh - (topMargin + bottomMargin);
        int size = Math.min(Math.min(availW, availH), 512);
        // Tiny-screen guard: never collapse to nothing, but don't exceed what we have.
        if (size < 64) size = Math.max(64, Math.min(availW, availH));
        canvasSize = Math.max(64, size);

        // Center the [canvas | gap | sidebar] block horizontally; clamp so nothing
        // spills off the left/top edges on small screens.
        int blockW = canvasSize + gap + sidebarW;
        canvasLeft = Math.max(LEFT_MARGIN, (sw - blockW) / 2);
        canvasTop = Math.max(topMargin, (sh - canvasSize) / 2);

        // Center on player position
        if (mc.player != null) {
            viewCenterX = (int) mc.player.posX;
            viewCenterZ = (int) mc.player.posZ;

            // Request territory sync from server
            TacticalWarMapNetwork.sendToServer(new C2SRequestTerritorySync());
            // ...and the defensive plan for the Military overlay.
            TacticalWarMapNetwork.sendToServer(studio.ERM.war.map.net.C2SDefensePlanEdit.requestSync());
            // Real-logger trace: confirms the GUI constructed + sent its sync request. If we see
            // this but never see the server-side "C2SRequestTerritorySync received", the C2S packet
            // is not reaching the server (channel/registration), which would also explain why
            // claiming "does nothing".
            studio.ERM.EpochRunnerMod.logger.info("[ERM-Map] initGui: GUI open, requested territory sync (canvas="
                    + canvasSize + " center=" + viewCenterX + "," + viewCenterZ + ")");
        }
    }

    @Override
    public boolean doesGuiPauseGame() {
        return false;
    }

    // ==================== Coordinate Conversion ====================

    private int getBlocksPerPixel() {
        return ZOOM_LEVELS[Math.max(0, Math.min(zoomIndex, ZOOM_LEVELS.length - 1))];
    }

    /** Convert screen pixel to world coordinates. */
    private int[] screenToWorld(int screenX, int screenY) {
        int bpp = getBlocksPerPixel();
        int half = canvasSize / 2;
        int relX = screenX - canvasLeft - half;
        int relY = screenY - canvasTop - half;
        return new int[]{
                viewCenterX + (relX * bpp),
                viewCenterZ + (relY * bpp)
        };
    }

    /** Convert world coordinates to screen pixel. Returns null if off-canvas. */
    private int[] worldToScreen(int worldX, int worldZ) {
        int bpp = getBlocksPerPixel();
        if (bpp == 0) return null;
        int half = canvasSize / 2;
        int sx = canvasLeft + half + ((worldX - viewCenterX) / bpp);
        int sy = canvasTop + half + ((worldZ - viewCenterZ) / bpp);
        return new int[]{sx, sy};
    }

    private boolean isOnCanvas(int mouseX, int mouseY) {
        return mouseX >= canvasLeft && mouseX < canvasLeft + canvasSize
                && mouseY >= canvasTop && mouseY < canvasTop + canvasSize;
    }

    // ==================== Mouse Input ====================

    @Override
    protected void mouseClicked(int mouseX, int mouseY, int mouseButton) throws IOException {
        // Deploy menu intercepts first
        if (deployMenu.isOpen()) {
            if (deployMenu.handleLeftClick(mouseX, mouseY)) return;
            deployMenu.close();
        }

        // TAB BAR (top-centre of the canvas): Claims / Civilian / Military.
        if (mouseButton == 0) {
            for (int i = 0; i < TABS.length; i++) {
                int[] b = tabBounds(i);
                if (mouseX >= b[0] && mouseX < b[2] && mouseY >= b[1] && mouseY < b[3]) {
                    if (activeTab != i && i != 2) { commitPendingPolyline(); planMode = -1; }
                    activeTab = i;
                    setStatus(TextFormatting.AQUA + TABS[i] + " tab"
                            + (i == 2 ? TextFormatting.GRAY + "  (P = plan markers, right-click = deploy)" : ""));
                    return;
                }
            }
        }

        // THE FALL BACK BUTTON (Military tab, top-left): one easy-to-hit toggle that swings every
        // defender from the primary lines to the fallback lines (and back).
        if (activeTab == 2 && mouseButton == 0 && isOnFallbackButton(mouseX, mouseY)) {
            boolean now = !studio.ERM.war.map.client.ClientDefensePlanCache.isFallbackActive();
            TacticalWarMapNetwork.sendToServer(studio.ERM.war.map.net.C2SDefensePlanEdit.setFallback(now));
            setStatus(now ? TextFormatting.RED + "FALL BACK! Defenders withdrawing to fallback lines."
                          : TextFormatting.GREEN + "Stand fast — defenders returning to primary lines.");
            return;
        }

        // CLEAR ALL (Military tab, beside FALL BACK): wipe the whole defensive plan.
        if (activeTab == 2 && mouseButton == 0 && isOnClearAllButton(mouseX, mouseY)) {
            planPending.clear();
            TacticalWarMapNetwork.sendToServer(studio.ERM.war.map.net.C2SDefensePlanEdit.clearAll());
            setStatus(TextFormatting.YELLOW + "Defensive plan cleared.");
            return;
        }

        if (!isOnCanvas(mouseX, mouseY)) {
            super.mouseClicked(mouseX, mouseY, mouseButton);
            return;
        }

        // DEFENSIVE PLANNING placement (Military tab, P-key mode). Left-click places; point markers
        // commit instantly, polylines accumulate. Right-click commits a >=2-point polyline, discards a
        // fragment, or removes the marker nearest the click.
        if (activeTab == 2 && planMode >= 0) {
            int[] w = screenToWorld(mouseX, mouseY);
            if (mouseButton == 0) {
                studio.ERM.strategic.defense.DefenseMarker probe = new studio.ERM.strategic.defense.DefenseMarker();
                probe.type = planMode;
                if (probe.isPolyline()) {
                    planPending.add(new net.minecraft.util.math.BlockPos(w[0], 0, w[1]));
                    // Engagement zone = exactly 2 clicks (centre, then radius edge) -> auto-commit.
                    if (planMode == studio.ERM.strategic.defense.DefenseMarker.ENGAGEMENT_ZONE
                            && planPending.size() >= 2) {
                        commitPendingPolyline();
                    }
                } else {
                    probe.points.add(new net.minecraft.util.math.BlockPos(w[0], 0, w[1]));
                    TacticalWarMapNetwork.sendToServer(studio.ERM.war.map.net.C2SDefensePlanEdit.add(probe));
                    setStatus(TextFormatting.GREEN + studio.ERM.strategic.defense.DefenseMarker.nameOf(planMode) + " placed.");
                }
            } else if (mouseButton == 1) {
                if (planPending.size() >= 2) {
                    commitPendingPolyline();
                } else if (!planPending.isEmpty()) {
                    planPending.clear();
                    setStatus(TextFormatting.GRAY + "Pending line discarded.");
                } else {
                    TacticalWarMapNetwork.sendToServer(
                            studio.ERM.war.map.net.C2SDefensePlanEdit.removeNearest(w[0], w[1]));
                    setStatus(TextFormatting.YELLOW + "Removed nearest marker.");
                }
            }
            return;
        }

        // TROOP ASSIGNMENT on existing markers (Military tab, planning off): left-click a node/line +1,
        // right-click -1, SHIFT+left-click assigns all unassigned units. Falls through to pan / deploy
        // when no marker is under the cursor.
        if (activeTab == 2 && planMode < 0) {
            studio.ERM.strategic.defense.DefenseMarker hit = markerAt(mouseX, mouseY);
            if (hit != null && hit.isAssignable()) {
                net.minecraft.util.math.BlockPos c = hit.center();
                if (mouseButton == 0 && GuiScreen.isShiftKeyDown()) {
                    TacticalWarMapNetwork.sendToServer(
                            studio.ERM.war.map.net.C2SDefensePlanEdit.assignAll(c.getX(), c.getZ()));
                    setStatus(TextFormatting.GREEN + "All unassigned units -> "
                            + studio.ERM.strategic.defense.DefenseMarker.nameOf(hit.type) + ".");
                } else if (mouseButton == 0) {
                    TacticalWarMapNetwork.sendToServer(
                            studio.ERM.war.map.net.C2SDefensePlanEdit.adjust(c.getX(), c.getZ(), +1));
                } else if (mouseButton == 1) {
                    TacticalWarMapNetwork.sendToServer(
                            studio.ERM.war.map.net.C2SDefensePlanEdit.adjust(c.getX(), c.getZ(), -1));
                }
                return;
            }
        }

        if (GuiScreen.isShiftKeyDown() && activeTab == 0) { // claiming lives on the CLAIMS tab
            // Shift + left-click: start claim selection
            if (mouseButton == 0) {
                claimDragging = true;
                unclaimDragging = false;
                selectStartMouseX = mouseX;
                selectStartMouseY = mouseY;
                selectCurrentMouseX = mouseX;
                selectCurrentMouseY = mouseY;
                pendingSelection.clear();
                updateSelectionPreview();
                return;
            }
            // Shift + right-click: start unclaim selection
            if (mouseButton == 1) {
                unclaimDragging = true;
                claimDragging = false;
                selectStartMouseX = mouseX;
                selectStartMouseY = mouseY;
                selectCurrentMouseX = mouseX;
                selectCurrentMouseY = mouseY;
                pendingSelection.clear();
                updateSelectionPreview();
                return;
            }
        }

        // Normal left-click: start panning
        if (mouseButton == 0) {
            panning = true;
            panStartMouseX = mouseX;
            panStartMouseY = mouseY;
            panStartCenterX = viewCenterX;
            panStartCenterZ = viewCenterZ;
            return;
        }

        // Right-click: battle-deploy context menu (a WAR feature -> Military tab only)
        if (mouseButton == 1 && activeTab == 2) {
            int[] world = screenToWorld(mouseX, mouseY);
            int cp = WarMapClientStats.getCommandPoints();
            int era = WarMapClientStats.getEra();
            deployMenu.open(mouseX, mouseY, world[0], world[1], cp, era);
        }
    }

    @Override
    protected void mouseClickMove(int mouseX, int mouseY, int clickedMouseButton, long timeSinceLastClick) {
        // Claim/unclaim drag
        if (claimDragging || unclaimDragging) {
            selectCurrentMouseX = mouseX;
            selectCurrentMouseY = mouseY;
            updateSelectionPreview();
            return;
        }

        // Panning
        if (panning) {
            int bpp = getBlocksPerPixel();
            int dx = mouseX - panStartMouseX;
            int dy = mouseY - panStartMouseY;
            viewCenterX = panStartCenterX - (dx * bpp);
            viewCenterZ = panStartCenterZ - (dy * bpp);

            // Clamp pan distance from player
            if (mc.player != null) {
                int px = (int) mc.player.posX;
                int pz = (int) mc.player.posZ;
                int ddx = viewCenterX - px;
                int ddz = viewCenterZ - pz;
                double dist = Math.sqrt(ddx * (double) ddx + ddz * (double) ddz);
                if (dist > MAX_PAN_RADIUS) {
                    double scale = MAX_PAN_RADIUS / dist;
                    viewCenterX = px + (int) (ddx * scale);
                    viewCenterZ = pz + (int) (ddz * scale);
                }
            }
        }
    }

    @Override
    protected void mouseReleased(int mouseX, int mouseY, int state) {
        // Finish claim drag
        if (claimDragging) {
            claimDragging = false;
            if (!pendingSelection.isEmpty()) {
                List<ChunkPos> chunks = new ArrayList<>(pendingSelection);
                TacticalWarMapNetwork.sendToServer(new C2SPacketBatchClaim(false, chunks));
                setStatus(TextFormatting.YELLOW + "Claiming " + chunks.size() + " chunks...");
            }
            pendingSelection.clear();
            return;
        }

        // Finish unclaim drag
        if (unclaimDragging) {
            unclaimDragging = false;
            if (!pendingSelection.isEmpty()) {
                List<ChunkPos> chunks = new ArrayList<>(pendingSelection);
                TacticalWarMapNetwork.sendToServer(new C2SPacketBatchClaim(true, chunks));
                setStatus(TextFormatting.YELLOW + "Unclaiming " + chunks.size() + " chunks...");
            }
            pendingSelection.clear();
            return;
        }

        // End panning
        if (panning) {
            panning = false;
        }

        super.mouseReleased(mouseX, mouseY, state);
    }

    @Override
    public void handleMouseInput() throws IOException {
        super.handleMouseInput();
        int scroll = org.lwjgl.input.Mouse.getEventDWheel();
        if (scroll != 0) {
            if (deployMenu.isOpen()) {
                deployMenu.handleScroll(scroll);
                return;
            }
            // Zoom
            if (scroll > 0 && zoomIndex > 0) {
                zoomIndex--;
            } else if (scroll < 0 && zoomIndex < ZOOM_LEVELS.length - 1) {
                zoomIndex++;
            }
        }
    }

    @Override
    protected void keyTyped(char typedChar, int keyCode) throws IOException {
        if (keyCode == 1) { // ESC
            mc.displayGuiScreen(null);
            return;
        }
        // 'R' to recenter on player
        if (typedChar == 'r' || typedChar == 'R') {
            if (mc.player != null) {
                viewCenterX = (int) mc.player.posX;
                viewCenterZ = (int) mc.player.posZ;
            }
        }
        // 'P' cycles the defensive-PLANNING mode: off -> Line -> Strongpoint -> Vehicle -> AA -> Rally
        // -> Reserve -> Fallback Line -> Patrol Route -> off. Cycling commits any pending polyline.
        // Planning is a MILITARY-tab feature: pressing P elsewhere jumps to that tab first.
        if (typedChar == 'p' || typedChar == 'P') {
            if (activeTab != 2) {
                activeTab = 2;
                setStatus(TextFormatting.AQUA + "MILITARY tab" + TextFormatting.GRAY + " — press P again to start planning.");
                return;
            }
            commitPendingPolyline();
            planMode = (planMode >= studio.ERM.strategic.defense.DefenseMarker.NAMES.length - 1) ? -1 : planMode + 1;
            setStatus(planMode < 0
                    ? TextFormatting.GRAY + "Planning OFF"
                    : TextFormatting.AQUA + "PLAN: " + studio.ERM.strategic.defense.DefenseMarker.nameOf(planMode)
                      + TextFormatting.GRAY + "  (click to place, right-click removes/commits, P = next)");
        }
    }

    /** Send an accumulated polyline (>=2 points) to the server as a plan marker; discard fragments. */
    private void commitPendingPolyline() {
        if (planPending.size() >= 2 && planMode >= 0) {
            studio.ERM.strategic.defense.DefenseMarker m = new studio.ERM.strategic.defense.DefenseMarker();
            m.type = planMode;
            m.points.addAll(planPending);
            TacticalWarMapNetwork.sendToServer(studio.ERM.war.map.net.C2SDefensePlanEdit.add(m));
            setStatus(TextFormatting.GREEN + studio.ERM.strategic.defense.DefenseMarker.nameOf(planMode) + " placed.");
        }
        planPending.clear();
    }

    // ==================== Selection Preview ====================

    /** Compute which chunks fall within the drag rectangle. */
    private void updateSelectionPreview() {
        pendingSelection.clear();

        int x1 = Math.min(selectStartMouseX, selectCurrentMouseX);
        int y1 = Math.min(selectStartMouseY, selectCurrentMouseY);
        int x2 = Math.max(selectStartMouseX, selectCurrentMouseX);
        int y2 = Math.max(selectStartMouseY, selectCurrentMouseY);

        // Convert corners to world coords
        int[] topLeft = screenToWorld(x1, y1);
        int[] botRight = screenToWorld(x2, y2);

        // Convert to chunk range
        int chunkMinX = topLeft[0] >> 4;
        int chunkMinZ = topLeft[1] >> 4;
        int chunkMaxX = botRight[0] >> 4;
        int chunkMaxZ = botRight[1] >> 4;

        // Clamp to max 256 chunks for safety
        int totalChunks = (chunkMaxX - chunkMinX + 1) * (chunkMaxZ - chunkMinZ + 1);
        if (totalChunks > 256) {
            // Shrink selection to fit
            setStatus(TextFormatting.RED + "Selection too large! Max 256 chunks.");
            return;
        }

        for (int cx = chunkMinX; cx <= chunkMaxX; cx++) {
            for (int cz = chunkMinZ; cz <= chunkMaxZ; cz++) {
                pendingSelection.add(new ChunkPos(cx, cz));
            }
        }
    }

    // ==================== Rendering ====================

    @Override
    public void drawScreen(int mouseX, int mouseY, float partialTicks) {
        // Full-screen dark background
        drawDefaultBackground();

        // Canvas background
        Gui.drawRect(canvasLeft - 1, canvasTop - 1, canvasLeft + canvasSize + 1, canvasTop + canvasSize + 1, 0xFF333355);
        Gui.drawRect(canvasLeft, canvasTop, canvasLeft + canvasSize, canvasTop + canvasSize, COLOR_BG);

        // Enable scissor to clip inside canvas
        enableCanvasScissor();

        // Draw terrain placeholder (colored biome-ish grid)
        drawTerrainBackground();

        // Draw territory overlay
        drawTerritoryOverlay();

        // Draw pending claim/unclaim preview
        if ((claimDragging || unclaimDragging) && !pendingSelection.isEmpty()) {
            drawSelectionPreview();
        }

        // Draw selection rectangle outline
        if (claimDragging || unclaimDragging) {
            drawSelectionRectangle();
        }

        uiMouseX = mouseX;
        uiMouseY = mouseY;

        // Draw the DEFENSIVE PLAN (Military tab only) under the traffic + player markers.
        if (activeTab == 2) drawDefensePlan();

        // LIVE UNIT DOTS (Military tab): every loaded friendly soldier + red enemy dots.
        if (activeTab == 2) drawUnitDots();

        // Strategic traffic (filtered per tab: civilian on CIVILIAN, military on MILITARY).
        if (activeTab != 0) drawStrategicMarkers();

        // "ENEMY CAMP GATHERING HERE" siege alert (all tabs -- the player must never miss it).
        drawSiegeAlert();

        // Draw player marker
        drawPlayerMarker();

        // The FALL BACK + CLEAR ALL buttons and planning-mode readout (Military tab only).
        if (activeTab == 2) drawFallbackButton();

        // The military side panel: units assigned X/Y + control reminders (Military tab only).
        if (activeTab == 2) drawMilitaryPanel();

        // The Claims / Civilian / Military tab bar (always).
        drawTabs();

        disableCanvasScissor();

        // Draw UI chrome (borders, labels, stats)
        drawUIChrome(mouseX, mouseY);

        // Draw deploy menu
        deployMenu.draw(mouseX, mouseY, fontRenderer);

        // Draw status message
        drawStatusMessage();

        super.drawScreen(mouseX, mouseY, partialTicks);
    }

    private void drawTerrainBackground() {
        int bpp = getBlocksPerPixel();

        // Base fill: anything we can't sample (no client world / unloaded chunks) reads as
        // "unexplored" rather than a green void.
        Gui.drawRect(canvasLeft, canvasTop, canvasLeft + canvasSize, canvasTop + canvasSize, COLOR_UNEXPLORED);

        World world = mc.world;
        if (world != null) {
            if (!terrainCacheValid
                    || terrainCacheCenterX != viewCenterX || terrainCacheCenterZ != viewCenterZ
                    || terrainCacheBpp != bpp || terrainCacheCanvas != canvasSize) {
                rebuildTerrainCache(world, bpp);
            }
            renderTerrainCache();
        }

        // Faint chunk grid for orientation (only when chunks are big enough on-screen to read).
        drawChunkGrid(bpp);
    }

    /**
     * Re-sample the visible world into {@link #terrainColors}. Each cell takes the surface block's
     * vanilla map colour (so grass is green, water blue, sand tan, stone grey, paths/roads/rails
     * show through, etc.) and shades it by height relative to the cell to the north for relief --
     * the same technique vanilla maps use. Only loaded client chunks can be sampled; everything else
     * stays "unexplored". Comparatively expensive, so this runs only when the view changes.
     */
    private void rebuildTerrainCache(World world, int bpp) {
        int step = TERRAIN_PIXEL_STEP;
        int gw = (canvasSize + step - 1) / step;
        int gh = gw; // canvas is square
        int[] colors = new int[gw * gh];
        int[] heights = new int[gw * gh];
        int half = canvasSize / 2;
        BlockPos.MutableBlockPos mp = new BlockPos.MutableBlockPos();

        try {
            // Pass 1 — base map colour + surface height per cell.
            for (int gy = 0; gy < gh; gy++) {
                for (int gx = 0; gx < gw; gx++) {
                    int idx = gy * gw + gx;
                    int px = gx * step + step / 2;
                    int py = gy * step + step / 2;
                    int wx = viewCenterX + (px - half) * bpp;
                    int wz = viewCenterZ + (py - half) * bpp;

                    Chunk chunk = world.getChunkProvider().getLoadedChunk(wx >> 4, wz >> 4);
                    if (chunk == null) { colors[idx] = 0; heights[idx] = -1; continue; }

                    int top = chunk.getHeightValue(wx & 15, wz & 15);
                    int rgb = 0, surfaceY = -1;
                    for (int i = 0, y = top - 1; i < 8 && y > 0; i++, y--) {
                        mp.setPos(wx, y, wz);
                        IBlockState st = chunk.getBlockState(mp);
                        MapColor m = st.getMapColor(world, mp);
                        if (m != null && m != MapColor.AIR) { rgb = m.colorValue; surfaceY = y; break; }
                    }
                    colors[idx] = rgb;      // 0 => unexplored / no colour found
                    heights[idx] = surfaceY;
                }
            }

            // Pass 2 — relief shading vs. the north neighbour, and bake in full alpha.
            for (int gy = 0; gy < gh; gy++) {
                for (int gx = 0; gx < gw; gx++) {
                    int idx = gy * gw + gx;
                    int rgb = colors[idx];
                    if (rgb == 0) continue; // leave unexplored as 0
                    int h = heights[idx];
                    int hN = (gy > 0) ? heights[idx - gw] : h;
                    int shade = 1;
                    if (h >= 0 && hN >= 0) {
                        if (h > hN) shade = 2;
                        else if (h < hN) shade = 0;
                    }
                    colors[idx] = shadeColor(rgb, shade);
                }
            }
        } catch (Throwable t) {
            // Never let terrain sampling crash the map; fall back to a flat unexplored canvas.
            java.util.Arrays.fill(colors, 0);
        }

        terrainColors = colors;
        terrainGridW = gw;
        terrainGridH = gh;
        terrainCacheCenterX = viewCenterX;
        terrainCacheCenterZ = viewCenterZ;
        terrainCacheBpp = bpp;
        terrainCacheCanvas = canvasSize;
        terrainCacheValid = true;
    }

    private void renderTerrainCache() {
        if (terrainColors == null) return;
        int step = TERRAIN_PIXEL_STEP;
        int right = canvasLeft + canvasSize;
        int bottom = canvasTop + canvasSize;
        for (int gy = 0; gy < terrainGridH; gy++) {
            int sy = canvasTop + gy * step;
            if (sy >= bottom) break;
            int ey = Math.min(sy + step, bottom);
            int rowBase = gy * terrainGridW;
            for (int gx = 0; gx < terrainGridW; gx++) {
                int c = terrainColors[rowBase + gx];
                if (c == 0) continue; // unexplored -> show the base fill
                int sx = canvasLeft + gx * step;
                if (sx >= right) break;
                Gui.drawRect(sx, sy, Math.min(sx + step, right), ey, c);
            }
        }
    }

    /** Faint chunk-boundary grid, drawn only when a chunk spans enough pixels to read cleanly. */
    private void drawChunkGrid(int bpp) {
        int chunkPx = 16 / bpp;
        if (chunkPx < 8) return;
        int half = canvasSize / 2;
        int worldLeft = viewCenterX - (half * bpp);
        int worldTop = viewCenterZ - (half * bpp);
        int worldRight = viewCenterX + (half * bpp);
        int worldBottom = viewCenterZ + (half * bpp);

        int firstX = Math.floorDiv(worldLeft, 16) * 16;
        for (int wx = firstX; wx <= worldRight; wx += 16) {
            int sx = canvasLeft + half + (wx - viewCenterX) / bpp;
            if (sx < canvasLeft || sx >= canvasLeft + canvasSize) continue;
            Gui.drawRect(sx, canvasTop, sx + 1, canvasTop + canvasSize, COLOR_GRID);
        }
        int firstZ = Math.floorDiv(worldTop, 16) * 16;
        for (int wz = firstZ; wz <= worldBottom; wz += 16) {
            int sy = canvasTop + half + (wz - viewCenterZ) / bpp;
            if (sy < canvasTop || sy >= canvasTop + canvasSize) continue;
            Gui.drawRect(canvasLeft, sy, canvasLeft + canvasSize, sy + 1, COLOR_GRID);
        }
    }

    /** Shade a base map-colour RGB by relief level: 0 = in shadow, 1 = flat, 2 = lit. */
    private static int shadeColor(int rgb, int shade) {
        int mul = (shade == 0) ? 185 : (shade == 2 ? 255 : 220);
        int r = (((rgb >> 16) & 0xFF) * mul) / 255;
        int g = (((rgb >> 8) & 0xFF) * mul) / 255;
        int b = ((rgb & 0xFF) * mul) / 255;
        return 0xFF000000 | (r << 16) | (g << 8) | b;
    }

    private void drawTerritoryOverlay() {
        Map<ChunkPos, String> snapshot = ClientTerritoryCache.getSnapshot();
        if (snapshot.isEmpty()) return;

        int bpp = getBlocksPerPixel();
        int chunkPixelSize = Math.max(1, 16 / bpp);
        String playerId = mc.player != null ? mc.player.getUniqueID().toString() : "";

        // First pass: fill
        for (Map.Entry<ChunkPos, String> entry : snapshot.entrySet()) {
            ChunkPos cp = entry.getKey();
            String owner = entry.getValue();
            if ("NEUTRAL".equals(owner)) continue;

            int chunkWorldX = cp.x << 4;
            int chunkWorldZ = cp.z << 4;
            int[] scr = worldToScreen(chunkWorldX, chunkWorldZ);
            if (scr == null) continue;

            int sx = scr[0];
            int sy = scr[1];
            int ex = sx + chunkPixelSize;
            int ey = sy + chunkPixelSize;

            if (ex < canvasLeft || sx >= canvasLeft + canvasSize) continue;
            if (ey < canvasTop || sy >= canvasTop + canvasSize) continue;

            int fillColor = colorForOwnerFill(owner, playerId);
            Gui.drawRect(sx, sy, ex, ey, fillColor);
        }

        // Second pass: borders (frontline effect - only draw edges where neighbor differs)
        if (chunkPixelSize >= 3) {
            for (Map.Entry<ChunkPos, String> entry : snapshot.entrySet()) {
                ChunkPos cp = entry.getKey();
                String owner = entry.getValue();
                if ("NEUTRAL".equals(owner)) continue;

                int chunkWorldX = cp.x << 4;
                int chunkWorldZ = cp.z << 4;
                int[] scr = worldToScreen(chunkWorldX, chunkWorldZ);
                if (scr == null) continue;

                int sx = scr[0];
                int sy = scr[1];
                int ex = sx + chunkPixelSize;
                int ey = sy + chunkPixelSize;

                if (ex < canvasLeft || sx >= canvasLeft + canvasSize) continue;
                if (ey < canvasTop || sy >= canvasTop + canvasSize) continue;

                int borderColor = colorForOwnerBorder(owner, playerId);

                // Check neighbors - only draw border if neighbor has different owner
                String northOwner = snapshot.getOrDefault(new ChunkPos(cp.x, cp.z - 1), "NEUTRAL");
                String southOwner = snapshot.getOrDefault(new ChunkPos(cp.x, cp.z + 1), "NEUTRAL");
                String westOwner = snapshot.getOrDefault(new ChunkPos(cp.x - 1, cp.z), "NEUTRAL");
                String eastOwner = snapshot.getOrDefault(new ChunkPos(cp.x + 1, cp.z), "NEUTRAL");

                if (!owner.equals(northOwner)) {
                    Gui.drawRect(sx, sy, ex, sy + 1, borderColor); // top
                }
                if (!owner.equals(southOwner)) {
                    Gui.drawRect(sx, ey - 1, ex, ey, borderColor); // bottom
                }
                if (!owner.equals(westOwner)) {
                    Gui.drawRect(sx, sy, sx + 1, ey, borderColor); // left
                }
                if (!owner.equals(eastOwner)) {
                    Gui.drawRect(ex - 1, sy, ex, ey, borderColor); // right
                }
            }
        }
    }

    private void drawSelectionPreview() {
        int bpp = getBlocksPerPixel();
        int chunkPixelSize = Math.max(1, 16 / bpp);
        int previewColor = claimDragging ? COLOR_CLAIM_PREVIEW : COLOR_UNCLAIM_PREVIEW;

        for (ChunkPos cp : pendingSelection) {
            int chunkWorldX = cp.x << 4;
            int chunkWorldZ = cp.z << 4;
            int[] scr = worldToScreen(chunkWorldX, chunkWorldZ);
            if (scr == null) continue;

            int sx = scr[0];
            int sy = scr[1];
            int ex = sx + chunkPixelSize;
            int ey = sy + chunkPixelSize;

            if (ex < canvasLeft || sx >= canvasLeft + canvasSize) continue;
            if (ey < canvasTop || sy >= canvasTop + canvasSize) continue;

            Gui.drawRect(sx, sy, ex, ey, previewColor);
        }
    }

    private void drawSelectionRectangle() {
        int x1 = Math.min(selectStartMouseX, selectCurrentMouseX);
        int y1 = Math.min(selectStartMouseY, selectCurrentMouseY);
        int x2 = Math.max(selectStartMouseX, selectCurrentMouseX);
        int y2 = Math.max(selectStartMouseY, selectCurrentMouseY);

        // Draw dashed rectangle outline
        int color = claimDragging ? 0xAA00FF00 : 0xAAFF4444;
        Gui.drawRect(x1, y1, x2, y1 + 1, color); // top
        Gui.drawRect(x1, y2 - 1, x2, y2, color); // bottom
        Gui.drawRect(x1, y1, x1 + 1, y2, color); // left
        Gui.drawRect(x2 - 1, y1, x2, y2, color); // right

        // Chunk count label
        String label = (claimDragging ? "CLAIM: " : "UNCLAIM: ") + pendingSelection.size() + " chunks";
        if (claimDragging) {
            int cost = pendingSelection.size() * WarClaimHandler.CLAIM_COST_BASE;
            label += " (" + cost + " CP)";
        }
        fontRenderer.drawStringWithShadow(label, x1 + 2, y1 - 10, claimDragging ? 0xFF00FF00 : 0xFFFF4444);
    }

    // ==================== PHASE 2: defensive-plan drawing ====================

    /** Draw every plan marker: polylines as coloured lines with endpoint dots, points as coloured
     *  squares with the marker's initial. The pending (uncommitted) polyline draws semi-transparent. */
    private void drawDefensePlan() {
        java.util.List<studio.ERM.strategic.defense.DefenseMarker> markers =
                studio.ERM.war.map.client.ClientDefensePlanCache.markers();
        for (studio.ERM.strategic.defense.DefenseMarker m : markers) {
            int color = PLAN_COLORS[Math.max(0, Math.min(m.type, PLAN_COLORS.length - 1))];
            if (m.type == studio.ERM.strategic.defense.DefenseMarker.ENGAGEMENT_ZONE && m.points.size() >= 2) {
                drawZoneCircle(m, color); // kill-zone circle: centre + radius edge
            } else {
                drawPlanPolyline(m.points, color, false);
            }
            if (!m.isPolyline() && !m.points.isEmpty()) {
                int[] scr = worldToScreen(m.points.get(0).getX(), m.points.get(0).getZ());
                if (scr != null && onCanvasPoint(scr)) {
                    Gui.drawRect(scr[0] - 3, scr[1] - 3, scr[0] + 4, scr[1] + 4, color);
                    fontRenderer.drawStringWithShadow(
                            studio.ERM.strategic.defense.DefenseMarker.nameOf(m.type).substring(0, 1),
                            scr[0] + 5, scr[1] - 3, color);
                }
            }
            // HOVER READOUT: troops assigned to this marker (adjust with L/R/Shift+L click).
            if (m.isAssignable()) {
                net.minecraft.util.math.BlockPos c = m.center();
                int[] scr = worldToScreen(c.getX(), c.getZ());
                if (scr != null && onCanvasPoint(scr)) {
                    int dx = uiMouseX - scr[0], dy = uiMouseY - scr[1];
                    if (dx * dx + dy * dy <= 100) { // within 10 px
                        String label = m.assigned + " assigned";
                        int tw = fontRenderer.getStringWidth(label);
                        Gui.drawRect(scr[0] - tw / 2 - 2, scr[1] - 22, scr[0] + tw / 2 + 2, scr[1] - 11, 0xCC000000);
                        fontRenderer.drawStringWithShadow(label, scr[0] - tw / 2f, scr[1] - 20, 0xFFFFD54F);
                    }
                }
            }
        }
        // Pending polyline preview (what you're mid-drawing).
        if (planMode >= 0 && !planPending.isEmpty()) {
            int color = (PLAN_COLORS[Math.max(0, Math.min(planMode, PLAN_COLORS.length - 1))] & 0x00FFFFFF) | 0x88000000;
            drawPlanPolyline(planPending, color, true);
        }
    }

    /** The marker (any type) whose centre is within ~9 px of the mouse, else null. */
    private studio.ERM.strategic.defense.DefenseMarker markerAt(int mx, int my) {
        for (studio.ERM.strategic.defense.DefenseMarker m
                : studio.ERM.war.map.client.ClientDefensePlanCache.markers()) {
            net.minecraft.util.math.BlockPos c = m.center();
            int[] scr = worldToScreen(c.getX(), c.getZ());
            if (scr == null) continue;
            int dx = mx - scr[0], dy = my - scr[1];
            if (dx * dx + dy * dy <= 81) return m;
        }
        return null;
    }

    /** Draw an engagement-zone circle (centre = point 0, radius = distance to point 1). */
    private void drawZoneCircle(studio.ERM.strategic.defense.DefenseMarker m, int color) {
        net.minecraft.util.math.BlockPos c = m.points.get(0);
        double r = Math.max(4.0, Math.min(64.0,
                Math.hypot(m.points.get(1).getX() - c.getX(), m.points.get(1).getZ() - c.getZ())));
        int[] prev = null;
        for (int i = 0; i <= 24; i++) {
            double a = i * (Math.PI * 2 / 24);
            int wx = (int) Math.round(c.getX() + Math.cos(a) * r);
            int wz = (int) Math.round(c.getZ() + Math.sin(a) * r);
            int[] scr = worldToScreen(wx, wz);
            if (scr == null) { prev = null; continue; }
            if (prev != null) drawMapLine(prev[0], prev[1], scr[0], scr[1], color);
            prev = scr;
        }
        int[] cs = worldToScreen(c.getX(), c.getZ());
        if (cs != null && onCanvasPoint(cs)) {
            Gui.drawRect(cs[0] - 1, cs[1] - 1, cs[0] + 2, cs[1] + 2, color);
            fontRenderer.drawStringWithShadow("KZ", cs[0] + 4, cs[1] - 3, color);
        }
    }

    /** Live unit dots: green = the player's army (each loaded soldier), red = enemies. */
    private void drawUnitDots() {
        int[] fd = studio.ERM.war.map.client.ClientStrategicCache.friendlyDots();
        for (int i = 0; i + 1 < fd.length; i += 2) {
            int[] scr = worldToScreen(fd[i], fd[i + 1]);
            if (scr == null || !onCanvasPoint(scr)) continue;
            Gui.drawRect(scr[0] - 1, scr[1] - 1, scr[0] + 1, scr[1] + 1, 0xFF00E676);
        }
        int[] ed = studio.ERM.war.map.client.ClientStrategicCache.enemyDots();
        for (int i = 0; i + 1 < ed.length; i += 2) {
            int[] scr = worldToScreen(ed[i], ed[i + 1]);
            if (scr == null || !onCanvasPoint(scr)) continue;
            Gui.drawRect(scr[0] - 1, scr[1] - 1, scr[0] + 1, scr[1] + 1, 0xFFFF1744);
        }
    }

    /** The "enemy camp gathering here" siege alert: a pulsing red beacon + label at the battle site. */
    private void drawSiegeAlert() {
        if (!studio.ERM.war.map.client.ClientStrategicCache.siegeActive()) return;
        int[] scr = worldToScreen(studio.ERM.war.map.client.ClientStrategicCache.siegeX(),
                studio.ERM.war.map.client.ClientStrategicCache.siegeZ());
        if (scr == null || !onCanvasPoint(scr)) return;
        // Pulse so the eye is drawn to it.
        int pulse = (int) ((System.currentTimeMillis() / 90) % 8);
        int s = 4 + (pulse < 4 ? pulse : 8 - pulse);
        Gui.drawRect(scr[0] - s, scr[1] - s, scr[0] + s + 1, scr[1] + s + 1, 0x66FF1744);
        Gui.drawRect(scr[0] - 2, scr[1] - 2, scr[0] + 3, scr[1] + 3, 0xFFFF1744);
        String label = "ENEMY CAMP GATHERING HERE";
        int tw = fontRenderer.getStringWidth(label);
        Gui.drawRect(scr[0] - tw / 2 - 2, scr[1] - 22, scr[0] + tw / 2 + 2, scr[1] - 11, 0xCC330000);
        fontRenderer.drawStringWithShadow(label, scr[0] - tw / 2f, scr[1] - 20, 0xFFFF5252);
    }

    /** Military side panel (right-inside the canvas): units assigned X/Y + the control reminders. */
    private void drawMilitaryPanel() {
        int x0 = canvasLeft + canvasSize - 96;
        int y0 = canvasTop + 18;
        int assigned = 0;
        for (studio.ERM.strategic.defense.DefenseMarker m
                : studio.ERM.war.map.client.ClientDefensePlanCache.markers()) {
            if (m.isAssignable()) assigned += m.assigned;
        }
        int have = studio.ERM.war.map.client.ClientStrategicCache.friendlyCount();
        String[] lines = {
                TextFormatting.GOLD + "Units assigned: " + assigned + "/" + have,
                TextFormatting.GRAY + "P: cycle marker type",
                TextFormatting.GRAY + "Click node: +1 troop",
                TextFormatting.GRAY + "R-click node: -1 troop",
                TextFormatting.GRAY + "Shift+Click: assign all",
                TextFormatting.GRAY + "R-click line: finish it",
                TextFormatting.GRAY + "R-click map: deploy",
                TextFormatting.GRAY + "Zone: centre, then edge",
        };
        int h = lines.length * 10 + 6;
        Gui.drawRect(x0 - 3, y0 - 3, x0 + 95, y0 + h - 3, 0x99000000);
        for (int i = 0; i < lines.length; i++) {
            fontRenderer.drawStringWithShadow(lines[i], x0, y0 + i * 10, 0xFFFFFFFF);
        }
    }

    private boolean onCanvasPoint(int[] scr) {
        return scr[0] >= canvasLeft && scr[0] < canvasLeft + canvasSize
                && scr[1] >= canvasTop && scr[1] < canvasTop + canvasSize;
    }

    private void drawPlanPolyline(java.util.List<net.minecraft.util.math.BlockPos> pts, int color, boolean pending) {
        int[] prev = null;
        for (net.minecraft.util.math.BlockPos p : pts) {
            int[] scr = worldToScreen(p.getX(), p.getZ());
            if (scr == null) { prev = null; continue; }
            if (onCanvasPoint(scr)) Gui.drawRect(scr[0] - 1, scr[1] - 1, scr[0] + 2, scr[1] + 2, color);
            if (prev != null) drawMapLine(prev[0], prev[1], scr[0], scr[1], color);
            prev = scr;
        }
    }

    /** GL line between two screen points (the map's polylines are diagonal; drawRect can't do that). */
    private void drawMapLine(int x1, int y1, int x2, int y2, int argb) {
        float a = (argb >>> 24) / 255F, r = ((argb >> 16) & 0xFF) / 255F,
                g = ((argb >> 8) & 0xFF) / 255F, b = (argb & 0xFF) / 255F;
        GlStateManager.disableTexture2D();
        GlStateManager.enableBlend();
        GlStateManager.glLineWidth(2.0F);
        net.minecraft.client.renderer.Tessellator tess = net.minecraft.client.renderer.Tessellator.getInstance();
        net.minecraft.client.renderer.BufferBuilder buf = tess.getBuffer();
        buf.begin(org.lwjgl.opengl.GL11.GL_LINES,
                net.minecraft.client.renderer.vertex.DefaultVertexFormats.POSITION_COLOR);
        buf.pos(x1, y1, 0).color(r, g, b, a).endVertex();
        buf.pos(x2, y2, 0).color(r, g, b, a).endVertex();
        tess.draw();
        GlStateManager.disableBlend();
        GlStateManager.enableTexture2D();
        GlStateManager.color(1F, 1F, 1F, 1F);
    }

    private int[] tabBounds(int i) {
        int w = 58, h = 12, gap = 2;
        int total = TABS.length * w + (TABS.length - 1) * gap;
        int x0 = canvasLeft + (canvasSize - total) / 2 + i * (w + gap);
        int y0 = canvasTop + 3;
        return new int[]{x0, y0, x0 + w, y0 + h};
    }

    /** The Claims / Civilian / Military tab bar (top-centre of the canvas). */
    private void drawTabs() {
        for (int i = 0; i < TABS.length; i++) {
            int[] b = tabBounds(i);
            boolean act = (i == activeTab);
            Gui.drawRect(b[0], b[1], b[2], b[3], act ? 0xEE1565C0 : 0xAA10101E);
            Gui.drawRect(b[0], b[3] - 1, b[2], b[3], act ? 0xFFFFFFFF : 0xFF000000);
            String t = TABS[i];
            int tw = fontRenderer.getStringWidth(t);
            fontRenderer.drawStringWithShadow(t, b[0] + (b[2] - b[0] - tw) / 2f, b[1] + 2,
                    act ? 0xFFFFFFFF : 0xFF9E9E9E);
        }
    }

    private int[] fallbackButtonBounds() {
        // Below the tab bar on the left so the two never overlap.
        return new int[]{canvasLeft + 4, canvasTop + 18, canvasLeft + 4 + 78, canvasTop + 18 + 14};
    }

    private boolean isOnFallbackButton(int mx, int my) {
        int[] b = fallbackButtonBounds();
        return mx >= b[0] && mx < b[2] && my >= b[1] && my < b[3];
    }

    private int[] clearAllButtonBounds() {
        int[] f = fallbackButtonBounds();
        return new int[]{f[2] + 4, f[1], f[2] + 4 + 58, f[3]};
    }

    private boolean isOnClearAllButton(int mx, int my) {
        int[] b = clearAllButtonBounds();
        return mx >= b[0] && mx < b[2] && my >= b[1] && my < b[3];
    }

    /** The one-click FALL BACK toggle, the CLEAR ALL button, and the planning-mode readout. */
    private void drawFallbackButton() {
        boolean fb = studio.ERM.war.map.client.ClientDefensePlanCache.isFallbackActive();
        int[] b = fallbackButtonBounds();
        Gui.drawRect(b[0], b[1], b[2], b[3], fb ? 0xEEB71C1C : 0xEE263238);
        Gui.drawRect(b[0], b[1], b[2], b[1] + 1, 0xFFFFFFFF);
        Gui.drawRect(b[0], b[3] - 1, b[2], b[3], 0xFF000000);
        String label = fb ? "FALLING BACK!" : "FALL BACK";
        int tw = fontRenderer.getStringWidth(label);
        fontRenderer.drawStringWithShadow(label, b[0] + (b[2] - b[0] - tw) / 2f, b[1] + 3, fb ? 0xFFFFCDD2 : 0xFFFF5252);

        int[] cb = clearAllButtonBounds();
        Gui.drawRect(cb[0], cb[1], cb[2], cb[3], 0xEE263238);
        Gui.drawRect(cb[0], cb[1], cb[2], cb[1] + 1, 0xFFFFFFFF);
        Gui.drawRect(cb[0], cb[3] - 1, cb[2], cb[3], 0xFF000000);
        String cl = "CLEAR ALL";
        int ctw = fontRenderer.getStringWidth(cl);
        fontRenderer.drawStringWithShadow(cl, cb[0] + (cb[2] - cb[0] - ctw) / 2f, cb[1] + 3, 0xFFFFC107);

        if (planMode >= 0) {
            fontRenderer.drawStringWithShadow(
                    "PLAN: " + studio.ERM.strategic.defense.DefenseMarker.nameOf(planMode)
                            + (planPending.isEmpty() ? "" : " (" + planPending.size() + " pts)"),
                    b[0], b[3] + 3, 0xFF00E5FF);
        }
    }

    // PHASE 2 traffic-overlay icons -- REAL AW2 art (AW2 is a hard dependency, its assets are loadable):
    // a coin for trader caravans, a combat order for military patrols.
    private static final net.minecraft.util.ResourceLocation ICON_STRAT_TRADER =
            new net.minecraft.util.ResourceLocation("ancientwarfare", "textures/items/npc/coin.png");
    private static final net.minecraft.util.ResourceLocation ICON_STRAT_PATROL =
            new net.minecraft.util.ResourceLocation("ancientwarfare", "textures/items/npc/combat_order.png");

    /**
     * Draw every strategic object (patrols, trader caravans...) as a live icon on the map -- the
     * "watch the civilization operating" overlay. Positions stream from the server every 2s
     * ({@link studio.ERM.war.map.net.S2CStrategicSync}); a green corner dot marks objects that are
     * MATERIALIZED (physically in the world) right now, and squads show their remaining strength.
     */
    private void drawStrategicMarkers() {
        java.util.List<studio.ERM.war.map.net.S2CStrategicSync.Data> objs =
                studio.ERM.war.map.client.ClientStrategicCache.snapshot();
        if (objs.isEmpty()) return;
        for (studio.ERM.war.map.net.S2CStrategicSync.Data d : objs) {
            // Tab filter: the CIVILIAN tab shows the economy (traders/carts/couriers); the MILITARY tab
            // shows military traffic (patrols/convoys). New archetypes default to military.
            boolean civilian = "trader".equals(d.type);
            if (activeTab == 1 && !civilian) continue;
            if (activeTab == 2 && civilian) continue;
            int[] scr = worldToScreen(d.x, d.z);
            if (scr == null) continue;
            int sx = scr[0], sy = scr[1];
            if (sx < canvasLeft || sx >= canvasLeft + canvasSize) continue;
            if (sy < canvasTop || sy >= canvasTop + canvasSize) continue;

            net.minecraft.util.ResourceLocation icon =
                    "trader".equals(d.type) ? ICON_STRAT_TRADER : ICON_STRAT_PATROL;
            GlStateManager.color(1F, 1F, 1F, 1F);
            GlStateManager.enableBlend();
            mc.getTextureManager().bindTexture(icon);
            Gui.drawScaledCustomSizeModalRect(sx - 5, sy - 5, 0, 0, 16, 16, 10, 10, 16F, 16F);
            GlStateManager.disableBlend();

            if (d.live) Gui.drawRect(sx + 3, sy - 6, sx + 6, sy - 3, 0xFF00FF55); // green = physically live
            if (d.strength > 1) {
                fontRenderer.drawStringWithShadow(String.valueOf(d.strength), sx + 6, sy, 0xFFFFFFFF);
            }
        }
        GlStateManager.color(1F, 1F, 1F, 1F);
    }

    private void drawPlayerMarker() {
        if (mc.player == null) return;

        int px = (int) mc.player.posX;
        int pz = (int) mc.player.posZ;
        int[] scr = worldToScreen(px, pz);
        if (scr == null) return;

        int sx = scr[0];
        int sy = scr[1];

        if (sx < canvasLeft || sx >= canvasLeft + canvasSize) return;
        if (sy < canvasTop || sy >= canvasTop + canvasSize) return;

        // Draw a small white diamond for the player
        Gui.drawRect(sx - 1, sy - 3, sx + 2, sy + 4, 0xFFFFFFFF);
        Gui.drawRect(sx - 3, sy - 1, sx + 4, sy + 2, 0xFFFFFFFF);
        Gui.drawRect(sx, sy - 2, sx + 1, sy + 3, 0xFF00CCFF);
        Gui.drawRect(sx - 2, sy, sx + 3, sy + 1, 0xFF00CCFF);
    }

    private void drawUIChrome(int mouseX, int mouseY) {
        ScaledResolution sr = new ScaledResolution(mc);
        int sw = sr.getScaledWidth();

        // Title
        String title = TextFormatting.GOLD + "" + TextFormatting.BOLD + "TACTICAL MAP";
        int titleWidth = fontRenderer.getStringWidth(title);
        fontRenderer.drawStringWithShadow(title, (sw - titleWidth) / 2, canvasTop - 14, 0xFFFFFFFF);

        // Zoom level
        String zoomStr = TextFormatting.GRAY + "Zoom: " + TextFormatting.WHITE + getBlocksPerPixel() + " bpp";
        fontRenderer.drawStringWithShadow(zoomStr, canvasLeft, canvasTop + canvasSize + 4, 0xFFFFFFFF);

        // World coordinates at cursor
        if (isOnCanvas(mouseX, mouseY)) {
            int[] world = screenToWorld(mouseX, mouseY);
            String coords = TextFormatting.GRAY + "X: " + TextFormatting.WHITE + world[0]
                    + TextFormatting.GRAY + "  Z: " + TextFormatting.WHITE + world[1];
            int coordsWidth = fontRenderer.getStringWidth(coords);
            fontRenderer.drawStringWithShadow(coords, canvasLeft + canvasSize - coordsWidth,
                    canvasTop + canvasSize + 4, 0xFFFFFFFF);
        }

        // Sidebar stats
        int sideX = canvasLeft + canvasSize + 6;
        int sideY = canvasTop;

        fontRenderer.drawStringWithShadow(TextFormatting.GOLD + "" + TextFormatting.BOLD + "Stats", sideX, sideY, 0xFFFFFFFF);
        sideY += 14;

        Integer cp = WarMapClientStats.getCommandPointsOrNull();
        fontRenderer.drawStringWithShadow(TextFormatting.GRAY + "CP: " + TextFormatting.GOLD +
                (cp != null ? cp : "?"), sideX, sideY, 0xFFFFFFFF);
        sideY += 12;

        Integer era = WarMapClientStats.getEraOrNull();
        fontRenderer.drawStringWithShadow(TextFormatting.GRAY + "Era: " + TextFormatting.AQUA +
                (era != null ? era : "?"), sideX, sideY, 0xFFFFFFFF);
        sideY += 12;

        Integer ad = WarMapClientStats.getAirDefenseOrNull();
        fontRenderer.drawStringWithShadow(TextFormatting.GRAY + "Air Def: " + TextFormatting.GREEN +
                (ad != null ? ad + "%" : "?"), sideX, sideY, 0xFFFFFFFF);
        sideY += 20;

        // Controls help
        fontRenderer.drawStringWithShadow(TextFormatting.GOLD + "" + TextFormatting.BOLD + "Controls", sideX, sideY, 0xFFFFFFFF);
        sideY += 14;
        fontRenderer.drawStringWithShadow(TextFormatting.GRAY + "Drag: Pan", sideX, sideY, 0xFFFFFFFF);
        sideY += 10;
        fontRenderer.drawStringWithShadow(TextFormatting.GRAY + "Scroll: Zoom", sideX, sideY, 0xFFFFFFFF);
        sideY += 10;
        fontRenderer.drawStringWithShadow(TextFormatting.GREEN + "Shift+Drag:", sideX, sideY, 0xFFFFFFFF);
        sideY += 10;
        fontRenderer.drawStringWithShadow(TextFormatting.GREEN + "  Claim Land", sideX, sideY, 0xFFFFFFFF);
        sideY += 10;
        fontRenderer.drawStringWithShadow(TextFormatting.RED + "Shift+RClick:", sideX, sideY, 0xFFFFFFFF);
        sideY += 10;
        fontRenderer.drawStringWithShadow(TextFormatting.RED + "  Unclaim Land", sideX, sideY, 0xFFFFFFFF);
        sideY += 10;
        fontRenderer.drawStringWithShadow(TextFormatting.GRAY + "R: Recenter", sideX, sideY, 0xFFFFFFFF);
        sideY += 10;
        fontRenderer.drawStringWithShadow(TextFormatting.GRAY + "RClick: Deploy", sideX, sideY, 0xFFFFFFFF);
        sideY += 10;
        fontRenderer.drawStringWithShadow(TextFormatting.GRAY + "ESC: Close", sideX, sideY, 0xFFFFFFFF);

        // Legend
        sideY += 20;
        fontRenderer.drawStringWithShadow(TextFormatting.GOLD + "" + TextFormatting.BOLD + "Legend", sideX, sideY, 0xFFFFFFFF);
        sideY += 14;
        Gui.drawRect(sideX, sideY, sideX + 8, sideY + 8, 0xFF00AA00);
        fontRenderer.drawStringWithShadow(TextFormatting.GREEN + " Your Land", sideX + 10, sideY, 0xFFFFFFFF);
        sideY += 12;
        Gui.drawRect(sideX, sideY, sideX + 8, sideY + 8, 0xFFCC0000);
        fontRenderer.drawStringWithShadow(TextFormatting.RED + " Rival Land", sideX + 10, sideY, 0xFFFFFFFF);
        sideY += 12;
        Gui.drawRect(sideX, sideY, sideX + 8, sideY + 8, 0xFF4488DD);
        fontRenderer.drawStringWithShadow(TextFormatting.BLUE + " Other Land", sideX + 10, sideY, 0xFFFFFFFF);

        // Mode indicator if shift held
        if (GuiScreen.isShiftKeyDown()) {
            String mode = TextFormatting.YELLOW + "" + TextFormatting.BOLD + "[TERRITORY MODE] "
                    + TextFormatting.RESET + TextFormatting.GRAY + "Drag to select chunks";
            int modeWidth = fontRenderer.getStringWidth(mode);
            fontRenderer.drawStringWithShadow(mode, (sw - modeWidth) / 2, canvasTop + canvasSize + 16, 0xFFFFFFFF);
        }
    }

    private void drawStatusMessage() {
        if (statusMessage.isEmpty() || System.currentTimeMillis() > statusExpiry) {
            statusMessage = "";
            return;
        }

        ScaledResolution sr = new ScaledResolution(mc);
        int sw = sr.getScaledWidth();
        int msgWidth = fontRenderer.getStringWidth(statusMessage);
        fontRenderer.drawStringWithShadow(statusMessage, (sw - msgWidth) / 2, canvasTop - 26, 0xFFFFFFFF);
    }

    // ==================== Scissor (canvas clipping) ====================

    private void enableCanvasScissor() {
        ScaledResolution sr = new ScaledResolution(mc);
        int scale = sr.getScaleFactor();
        int realX = canvasLeft * scale;
        int realY = (sr.getScaledHeight() - canvasTop - canvasSize) * scale;
        int realW = canvasSize * scale;
        int realH = canvasSize * scale;

        GlStateManager.pushMatrix();
        org.lwjgl.opengl.GL11.glEnable(org.lwjgl.opengl.GL11.GL_SCISSOR_TEST);
        org.lwjgl.opengl.GL11.glScissor(realX, realY, realW, realH);
    }

    private void disableCanvasScissor() {
        org.lwjgl.opengl.GL11.glDisable(org.lwjgl.opengl.GL11.GL_SCISSOR_TEST);
        GlStateManager.popMatrix();
    }

    // ==================== Helpers ====================

    private int colorForOwnerFill(String owner, String playerId) {
        if (playerId.equals(owner) || "PLAYER".equals(owner)) return COLOR_PLAYER_FILL;
        if ("RIVAL".equals(owner)) return COLOR_RIVAL_FILL;
        return COLOR_OTHER_FILL;
    }

    private int colorForOwnerBorder(String owner, String playerId) {
        if (playerId.equals(owner) || "PLAYER".equals(owner)) return COLOR_BORDER_PLAYER;
        if ("RIVAL".equals(owner)) return COLOR_BORDER_RIVAL;
        return COLOR_BORDER_OTHER;
    }

    private void setStatus(String msg) {
        statusMessage = msg;
        statusExpiry = System.currentTimeMillis() + 3000;
    }
}
