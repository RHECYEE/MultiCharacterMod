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
 *   - Military/Civilian tabs: pick a tool from the Icon+Name list LEFT of the canvas
 *     (mirrors the stats sidebar; replaces the old P-key tool cycle)
 *   - ESC: Close
 *
 * Territory is visualized as colored chunk overlays with frontline borders.
 */
public class GuiTacticalWarMap extends GuiScreen {

    // ==================== View State ====================
    private int viewCenterX; // world X at center of canvas
    private int viewCenterZ; // world Z at center of canvas

    // Deep sub-block zoom (down to 0.0125 bpp = 80 screen px per block) for placing districts and
    // markers with true detail; 1 bpp is the block-precision level, 32 the strategic overview.
    private static final double[] ZOOM_LEVELS = {0.0125, 0.025, 0.05, 0.1, 0.25, 0.5, 1, 2, 4, 8, 16, 32};
    private int zoomIndex = 9; // default 8 bpp

    private int canvasSize = 512;
    private int canvasLeft, canvasTop;

    // Layout constants used by initGui() to keep the map + side columns on-screen at any GUI scale.
    private static final int SIDEBAR_WIDTH = 92; // right-hand stats/controls/legend column
    private static final int TOOLBAR_WIDTH = 92; // left-hand tool list column (Military/Civilian tabs)
    private static final int LEFT_MARGIN = 12;
    private static final int RIGHT_MARGIN = 8;

    // ==================== Pan Dragging ====================
    private boolean panning = false;
    // AUTO-FOLLOW: the map surveys around the player and keeps them centred as they move. Manual
    // panning turns it off; pressing R (recenter) turns it back on.
    private boolean autoFollow = true;
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
    // planMode: -1 = Troop Allocation (edit markers), else the DefenseMarker type being placed.
    // Selected from the tool list LEFT of the canvas (replaces the old P-key cycle). In plan mode,
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
    // ==================== CIVILIAN infrastructure drawing (Civilian tab) ====================
    // civilMode: -1 = Inspect, else the CivilMarker KIND being drawn. A road is infrastructure,
    // not a district: it draws as an open POLYLINE (right-click commits >=2 points). Districts are
    // closed POLYGONS that generate jobs + logistics (right-click commits >=3 corners).
    private int civilMode = -1;
    private final List<net.minecraft.util.math.BlockPos> civilPending = new ArrayList<>();
    // Throttle for the Civilian tab's live re-sync (road conditions / courier jobs / stats).
    private long lastCivilSyncReq = 0;
    // Kind-indexed colors (see CivilMarker): road tan, then one hue per district type.
    private static final int[] CIVIL_COLORS = {
            0xFFD2A24C, // ROAD tan
            0xFF66BB6A, // RESIDENTIAL green
            0xFF9E9D24, // BARRACKS olive
            0xFFFFB300, // WAREHOUSE amber
            0xFF90A4AE, // ARMORY steel
            0xFFFF7043, // KITCHEN orange
            0xFFEF5350, // HOSPITAL red
            0xFF8D6E63, // FACTORY industrial brown
            0xFF7E57C2, // RESEARCH purple
            0xFF26A69A, // TRADE DEPOT teal
            0xFF29B6F6, // FISHING light blue
            0xFF33691E, // LUMBER deep green
            0xFFD4E157, // FARM lime
            0xFF757575, // MINING gray
            0xFF6D4C41  // HUNTING dark brown
    };

    // Mouse position captured each frame for marker hover readouts.
    private int uiMouseX, uiMouseY;
    // Bottom of the sidebar chrome (Stats/Controls/Legend) -- the military panel anchors BELOW it.
    private int sidebarEndY = 0;

    // ==================== Marker PROPERTIES PANEL ====================
    // Clicking a marker (Military tab, planning off) opens this panel: units / priority / formation /
    // flags / assign-all / delete. Tracked by uid so server syncs never leave it pointing at a stale
    // object. This replaces raw click-counting as the primary editing surface.
    private int panelUid = -1;
    private int panelX, panelY;

    // CIVILIAN district panel (Civilian tab, Inspect tool): Workers X/Y +/- (shift=10) + delete.
    private int civPanelUid = -1;
    private int civPanelX, civPanelY;

    // STRATEGIC MISSION MENU (Civilian tab, Inspect mode, right-click): dispatch a party to the
    // cursor. The PRUNED list: Search for Minerals IS the surveying (it charts deposits), Scout
    // Route explores, Establish Camp founds an AW2 extraction camp on a charted deposit.
    // MISSION_TYPES maps each row to its server-side StrategicMissionManager type constant.
    private boolean missionMenuOpen = false;
    private int missionMenuX, missionMenuY, missionWorldX, missionWorldZ;
    private static final String[] MISSION_NAMES = {
            "Hunting Party", "Search for Minerals", "Scout Route", "Establish Camp"};
    private static final int[] MISSION_TYPES = {0, 2, 4, 6};

    // NATION DIPLOMACY DROPDOWN (Claims tab): click a nation's land to open Send Envoy / Buy Claim /
    // Purchase Trade Agreement.
    private boolean diploMenuOpen = false;
    private int diploMenuX, diploMenuY;
    private String diploNation = "";
    private static final String[] DIPLO_NAMES = {"Send Envoy", "Buy Claim", "Purchase Trade Agreement"};
    private static final int DIPLO_W = 132;

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
    private int terrainCacheCenterX, terrainCacheCenterZ, terrainCacheCanvas;
    private double terrainCacheBpp;
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

        int availW = sw - (LEFT_MARGIN + TOOLBAR_WIDTH + gap + gap + sidebarW + RIGHT_MARGIN);
        int availH = sh - (topMargin + bottomMargin);
        int size = Math.min(Math.min(availW, availH), 512);
        // Tiny-screen guard: never collapse to nothing, but don't exceed what we have.
        if (size < 64) size = Math.max(64, Math.min(availW, availH));
        canvasSize = Math.max(64, size);

        // Center the [tool list | gap | canvas | gap | sidebar] block horizontally; clamp so
        // nothing spills off the left/top edges on small screens.
        int blockW = TOOLBAR_WIDTH + gap + canvasSize + gap + sidebarW;
        canvasLeft = Math.max(LEFT_MARGIN + TOOLBAR_WIDTH + gap, (sw - blockW) / 2 + TOOLBAR_WIDTH + gap);
        canvasTop = Math.max(topMargin, (sh - canvasSize) / 2);

        // Center on player position
        if (mc.player != null) {
            viewCenterX = (int) mc.player.posX;
            viewCenterZ = (int) mc.player.posZ;

            // Request territory sync from server
            TacticalWarMapNetwork.sendToServer(new C2SRequestTerritorySync());
            // ...and the defensive plan for the Military overlay.
            TacticalWarMapNetwork.sendToServer(studio.ERM.war.map.net.C2SDefensePlanEdit.requestSync());
            // ...and the civilian infrastructure (roads + districts) for the Civilian tab.
            TacticalWarMapNetwork.sendToServer(studio.ERM.war.map.net.C2SCivilPlanEdit.requestSync());
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

    private double getBlocksPerPixel() {
        return ZOOM_LEVELS[Math.max(0, Math.min(zoomIndex, ZOOM_LEVELS.length - 1))];
    }

    /** Convert screen pixel to world coordinates. */
    private int[] screenToWorld(int screenX, int screenY) {
        double bpp = getBlocksPerPixel();
        int half = canvasSize / 2;
        int relX = screenX - canvasLeft - half;
        int relY = screenY - canvasTop - half;
        return new int[]{
                viewCenterX + (int) Math.floor(relX * bpp),
                viewCenterZ + (int) Math.floor(relY * bpp)
        };
    }

    /** Convert world coordinates to screen pixel. Returns null if off-canvas. */
    private int[] worldToScreen(int worldX, int worldZ) {
        double bpp = getBlocksPerPixel();
        if (bpp <= 0) return null;
        int half = canvasSize / 2;
        int sx = canvasLeft + half + (int) ((worldX - viewCenterX) / bpp);
        int sy = canvasTop + half + (int) ((worldZ - viewCenterZ) / bpp);
        return new int[]{sx, sy};
    }

    private boolean isOnCanvas(int mouseX, int mouseY) {
        return mouseX >= canvasLeft && mouseX < canvasLeft + canvasSize
                && mouseY >= canvasTop && mouseY < canvasTop + canvasSize;
    }

    // ==================== Mouse Input ====================

    @Override
    protected void mouseClicked(int mouseX, int mouseY, int mouseButton) throws IOException {
        // NATION DIPLOMACY DROPDOWN eats clicks while open.
        if (diploMenuOpen) {
            int row = diploRowAt(mouseX, mouseY);
            if (mouseButton == 0 && row >= 0) {
                TacticalWarMapNetwork.sendToServer(new studio.ERM.war.map.net.C2SDiplomacyAction(row, diploNation));
                setStatus(TextFormatting.AQUA + DIPLO_NAMES[row] + " → " + diploNation + "…");
            }
            diploMenuOpen = false;
            return;
        }

        // Deploy menu intercepts first
        // STRATEGIC MISSION MENU eats clicks while open.
        if (missionMenuOpen) {
            int row = missionRowAt(mouseX, mouseY);
            if (mouseButton == 0 && row >= 0) {
                TacticalWarMapNetwork.sendToServer(new studio.ERM.war.map.net.C2SStrategicMission(
                        MISSION_TYPES[row], missionWorldX, missionWorldZ));
                setStatus(TextFormatting.GREEN + MISSION_NAMES[row] + " dispatched to "
                        + missionWorldX + ", " + missionWorldZ + ".");
            }
            missionMenuOpen = false;
            return;
        }

        if (deployMenu.isOpen()) {
            if (deployMenu.handleLeftClick(mouseX, mouseY)) return;
            deployMenu.close();
        }

        // TAB BAR (top-centre of the canvas): Claims / Civilian / Military.
        if (mouseButton == 0) {
            for (int i = 0; i < TABS.length; i++) {
                int[] b = tabBounds(i);
                if (mouseX >= b[0] && mouseX < b[2] && mouseY >= b[1] && mouseY < b[3]) {
                    if (activeTab != i) {
                        // Leaving a drawing tab commits (or discards) whatever is mid-draw.
                        if (activeTab == 2) { commitPendingPolyline(); planMode = -1; panelUid = -1; }
                        if (activeTab == 1) { commitPendingCivil(); civilMode = -1; civPanelUid = -1; }
                    }
                    activeTab = i;
                    setStatus(TextFormatting.AQUA + TABS[i] + " tab"
                            + (i == 2 ? TextFormatting.GRAY + "  (pick a tool on the left, right-click = deploy)"
                            : i == 1 ? TextFormatting.GRAY + "  (draw roads + districts with the left tools)" : ""));
                    return;
                }
            }
        }

        // EVACUATE button (Civilian tab sidebar): toggle the civilian evacuation.
        if (activeTab == 1 && mouseButton == 0 && isOnEvacButton(mouseX, mouseY)) {
            TacticalWarMapNetwork.sendToServer(new studio.ERM.war.map.net.C2SEvacuationToggle());
            boolean now = !studio.ERM.war.map.client.ClientStrategicCache.evacActive();
            setStatus(now ? TextFormatting.RED + "EVACUATION ordered — civilians moving to shelter."
                          : TextFormatting.GREEN + "Evacuation stood down.");
            return;
        }

        // TOOL LIST (left of the canvas, Military + Civilian tabs): click a row to select that
        // tool. Row 0 is the tab's "no tool" mode (Troop Allocation / Inspect); switching tools
        // commits anything mid-draw, same as the old P-cycle did.
        if (mouseButton == 0 && (activeTab == 1 || activeTab == 2)) {
            int row = toolbarRowAt(mouseX, mouseY);
            if (row != -1) {
                if (activeTab == 2) {
                    commitPendingPolyline();
                    panelUid = -1; // picking a tool ends any marker-properties editing
                    planMode = row - 1;
                    setStatus(planMode < 0
                            ? TextFormatting.GOLD + "TOOL: Troop Allocation" + TextFormatting.GRAY
                              + "  (click a marker to edit its units/formation)"
                            : TextFormatting.AQUA + "TOOL: "
                              + studio.ERM.strategic.defense.DefenseMarker.nameOf(planMode)
                              + TextFormatting.GRAY + "  (click to place, right-click removes/commits)");
                } else {
                    commitPendingCivil();
                    civPanelUid = -1; // picking a tool ends any district editing
                    civilMode = row - 1;
                    setStatus(civilMode < 0
                            ? TextFormatting.GOLD + "TOOL: Inspect" + TextFormatting.GRAY
                              + "  (click a district to manage workers)"
                            : TextFormatting.AQUA + "TOOL: "
                              + studio.ERM.strategic.civil.CivilMarker.nameOf(civilMode)
                              + TextFormatting.GRAY + (civilMode == studio.ERM.strategic.civil.CivilMarker.ROAD
                                ? "  (click waypoints, right-click finishes the road)"
                                : "  (click corners, right-click closes the district)"));
                }
                return;
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
            panelUid = -1;
            TacticalWarMapNetwork.sendToServer(studio.ERM.war.map.net.C2SDefensePlanEdit.clearAll());
            setStatus(TextFormatting.YELLOW + "Defensive plan cleared.");
            return;
        }

        // RECRUIT (Military tab): open the loadout/contract screen (server opens the container GUI).
        if (activeTab == 2 && mouseButton == 0 && isOnRecruitButton(mouseX, mouseY)) {
            TacticalWarMapNetwork.sendToServer(studio.ERM.war.map.net.C2SRecruitConfirm.openRequest());
            return;
        }

        // MARKER PROPERTIES PANEL: while open it eats clicks; clicking outside closes it.
        if (activeTab == 2 && panelUid != -1) {
            if (handlePanelClick(mouseX, mouseY)) return;
            panelUid = -1;
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

        // CIVILIAN drawing (Civilian tab, tool selected): left-click adds a waypoint/corner;
        // right-click commits (road >=2 pts, district >=3), discards a fragment, or removes the
        // nearest road/district when nothing is pending.
        if (activeTab == 1 && civilMode >= 0) {
            int[] w = screenToWorld(mouseX, mouseY);
            if (mouseButton == 0) {
                if (civilPending.size() >= 100) {
                    setStatus(TextFormatting.RED + "Too many points — right-click to finish.");
                } else {
                    civilPending.add(new net.minecraft.util.math.BlockPos(w[0], 0, w[1]));
                }
            } else if (mouseButton == 1) {
                boolean road = civilMode == studio.ERM.strategic.civil.CivilMarker.ROAD;
                if (civilPending.size() >= (road ? 2 : 3)) {
                    commitPendingCivil();
                } else if (!civilPending.isEmpty()) {
                    civilPending.clear();
                    setStatus(TextFormatting.GRAY + "Pending " + (road ? "road" : "district") + " discarded.");
                } else {
                    TacticalWarMapNetwork.sendToServer(
                            studio.ERM.war.map.net.C2SCivilPlanEdit.removeNearest(w[0], w[1]));
                    setStatus(TextFormatting.YELLOW + "Removed nearest road/district.");
                }
            }
            return;
        }

        // CIVILIAN DISTRICT PANEL: while open it eats clicks; clicking outside closes it.
        if (activeTab == 1 && civilMode < 0 && civPanelUid != -1) {
            if (handleCivPanelClick(mouseX, mouseY)) return;
            civPanelUid = -1;
            return;
        }

        // DEPOSIT ICONS (Civilian tab, Inspect mode, LEFT-click): clicking a charted camp-site icon
        // opens the mission menu PRE-TARGETED at the deposit — Establish Camp is one click away.
        if (activeTab == 1 && civilMode < 0 && mouseButton == 0) {
            studio.ERM.war.map.net.S2CCivilPlanSync.Deposit dep = depositIconAt(mouseX, mouseY);
            if (dep != null) {
                missionWorldX = dep.x;
                missionWorldZ = dep.z;
                missionMenuX = Math.min(mouseX, canvasLeft + canvasSize - 96);
                missionMenuY = Math.min(mouseY, canvasTop + canvasSize - 40);
                missionMenuOpen = true;
                setStatus(TextFormatting.GOLD + dep.name + " deposit" + TextFormatting.GRAY
                        + " @ " + dep.x + ", " + dep.z + " — dispatch Establish Camp to claim it.");
                return;
            }
        }

        // STRATEGIC MISSION (Civilian tab, Inspect mode, RIGHT-click): open the mission menu at the
        // cursor instead of deleting (deletion lives on the district panel's DELETE button now).
        if (activeTab == 1 && civilMode < 0 && mouseButton == 1) {
            int[] w = screenToWorld(mouseX, mouseY);
            missionWorldX = w[0];
            missionWorldZ = w[1];
            missionMenuX = Math.min(mouseX, canvasLeft + canvasSize - 96);
            missionMenuY = Math.min(mouseY, canvasTop + canvasSize - 40);
            missionMenuOpen = true;
            return;
        }

        // INSPECT (Civilian tab, no drawing tool): click a district to open its panel (Workers +/-,
        // delete); roads just print a readout. Falls through to panning when nothing is under it.
        if (activeTab == 1 && civilMode < 0 && mouseButton == 0) {
            studio.ERM.strategic.civil.CivilMarker hit = civilMarkerAt(mouseX, mouseY);
            if (hit != null) {
                if (hit.isRoad()) {
                    net.minecraft.util.math.BlockPos c = hit.center();
                    setStatus(TextFormatting.AQUA + "Road" + TextFormatting.GRAY + "  "
                            + hit.points.size() + " pts, centre " + c.getX() + ", " + c.getZ());
                } else {
                    civPanelUid = hit.uid;
                    civPanelX = Math.min(mouseX, canvasLeft + canvasSize - 124);
                    civPanelY = Math.min(mouseY, canvasTop + canvasSize - 92);
                }
                return;
            }
        }

        // MARKER CLICK -> PROPERTIES PANEL (Military tab, planning off): units / priority / formation /
        // flags / assign-all / delete live in the panel. Falls through to pan / deploy when no marker
        // is under the cursor.
        if (activeTab == 2 && planMode < 0 && mouseButton == 0) {
            studio.ERM.strategic.defense.DefenseMarker hit = markerAt(mouseX, mouseY);
            if (hit != null) {
                panelUid = hit.uid;
                panelX = Math.min(mouseX, canvasLeft + canvasSize - 124);
                panelY = Math.min(mouseY, canvasTop + canvasSize - 104);
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

        // CLAIMS tab: a plain left-click on a NATION STATE's land opens the diplomacy dropdown.
        if (activeTab == 0 && mouseButton == 0 && !GuiScreen.isShiftKeyDown() && isOnCanvas(mouseX, mouseY)) {
            int[] w = screenToWorld(mouseX, mouseY);
            String owner = studio.ERM.war.map.client.ClientTerritoryCache.getOwner(
                    new net.minecraft.util.math.ChunkPos(w[0] >> 4, w[1] >> 4));
            if (owner != null && owner.startsWith("NATION:")) {
                diploNation = owner.substring("NATION:".length());
                diploMenuX = Math.min(mouseX, canvasLeft + canvasSize - DIPLO_W);
                diploMenuY = Math.min(mouseY, canvasTop + canvasSize - 60);
                diploMenuOpen = true;
                return;
            }
        }

        // Normal left-click: start panning (turns off auto-follow until the next recenter).
        if (mouseButton == 0) {
            panning = true;
            autoFollow = false;
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
            double bpp = getBlocksPerPixel();
            int dx = mouseX - panStartMouseX;
            int dy = mouseY - panStartMouseY;
            viewCenterX = panStartCenterX - (int) Math.round(dx * bpp);
            viewCenterZ = panStartCenterZ - (int) Math.round(dy * bpp);

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
        // 'R' to recenter on player AND re-enable auto-follow.
        if (typedChar == 'r' || typedChar == 'R') {
            if (mc.player != null) {
                viewCenterX = (int) mc.player.posX;
                viewCenterZ = (int) mc.player.posZ;
                autoFollow = true;
            }
        }
        // (The old 'P' tool cycle is gone: tools are picked from the Icon+Name list LEFT of the map.)
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

    /** Send an accumulated road (>=2 pts) or district polygon (>=3 corners) to the server; the
     *  server validates territory rules and answers with a fresh sync. Fragments are discarded. */
    private void commitPendingCivil() {
        if (civilMode >= 0) {
            int min = (civilMode == studio.ERM.strategic.civil.CivilMarker.ROAD) ? 2 : 3;
            if (civilPending.size() >= min) {
                studio.ERM.strategic.civil.CivilMarker m = new studio.ERM.strategic.civil.CivilMarker();
                m.kind = civilMode;
                m.points.addAll(civilPending);
                TacticalWarMapNetwork.sendToServer(studio.ERM.war.map.net.C2SCivilPlanEdit.add(m));
                setStatus(TextFormatting.GREEN + studio.ERM.strategic.civil.CivilMarker.nameOf(civilMode)
                        + (m.isRoad() ? "" : " district") + " submitted.");
            }
        }
        civilPending.clear();
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
        // AUTO-FOLLOW: keep the view centred on the player (survey around them) unless they've panned.
        if (autoFollow && !panning && mc.player != null) {
            viewCenterX = (int) mc.player.posX;
            viewCenterZ = (int) mc.player.posZ;
        }

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

        // CIVILIAN INFRASTRUCTURE (Civilian tab): roads + district polygons under the traffic dots.
        // While the tab is open, re-request the civil snapshot every 2s so road conditions, worker
        // counts and courier jobs stay LIVE (the packet is small; this is the strategic feed).
        if (activeTab == 1 && System.currentTimeMillis() - lastCivilSyncReq > 2000) {
            lastCivilSyncReq = System.currentTimeMillis();
            TacticalWarMapNetwork.sendToServer(studio.ERM.war.map.net.C2SCivilPlanEdit.requestSync());
        }
        if (activeTab == 1) drawCivilPlan();

        // CITIZEN DOTS (Civilian tab): every loaded civilian worker as a white fleck — the city's
        // life, visible at a glance (mirror of the Military tab's green soldier dots).
        if (activeTab == 1) drawCitizenDots();

        // CHARTED DEPOSITS (Civilian tab): diamond camp-site icons from survey charts — click one
        // (Inspect mode) to dispatch Establish Camp straight onto the deposit.
        if (activeTab == 1) drawDepositIcons();

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

        // Civilian tab's active-tool readout (same spot the Military tab uses for its buttons).
        if (activeTab == 1) drawCivilToolReadout();

        // The Claims / Civilian / Military tab bar (always).
        drawTabs();

        disableCanvasScissor();

        // Draw UI chrome (borders, labels, stats)
        drawUIChrome(mouseX, mouseY);

        // The LEFT tool list (Military + Civilian tabs) — mirrors the stats sidebar on the right.
        if (activeTab != 0) drawToolbar(mouseX, mouseY);

        // The military side panel: units assigned X/Y + control reminders. Anchors BELOW the sidebar
        // chrome (drawn after it so sidebarEndY is current-frame accurate -- no more Legend overlap).
        if (activeTab == 2) drawMilitaryPanel();
        if (activeTab == 1) drawEvacPanel(mouseX, mouseY);

        // Draw deploy menu
        deployMenu.draw(mouseX, mouseY, fontRenderer);
        drawMissionMenu(mouseX, mouseY);
        drawDiploMenu(mouseX, mouseY);

        // Marker properties panel (drawn on top of everything on the Military tab).
        if (activeTab == 2 && panelUid != -1) drawMarkerPanel();
        // District panel (Civilian tab, Inspect tool).
        if (activeTab == 1 && civilMode < 0 && civPanelUid != -1) drawCivPanel();

        // Draw status message
        drawStatusMessage();

        super.drawScreen(mouseX, mouseY, partialTicks);
    }

    private void drawTerrainBackground() {
        double bpp = getBlocksPerPixel();

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
    private void rebuildTerrainCache(World world, double bpp) {
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
                    int wx = viewCenterX + (int) Math.floor((px - half) * bpp);
                    int wz = viewCenterZ + (int) Math.floor((py - half) * bpp);

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
    private void drawChunkGrid(double bpp) {
        int chunkPx = (int) Math.round(16 / bpp);
        if (chunkPx < 8) return;
        int half = canvasSize / 2;
        int worldLeft = viewCenterX - (int) Math.ceil(half * bpp);
        int worldTop = viewCenterZ - (int) Math.ceil(half * bpp);
        int worldRight = viewCenterX + (int) Math.ceil(half * bpp);
        int worldBottom = viewCenterZ + (int) Math.ceil(half * bpp);

        int firstX = Math.floorDiv(worldLeft, 16) * 16;
        for (int wx = firstX; wx <= worldRight; wx += 16) {
            int sx = canvasLeft + half + (int) ((wx - viewCenterX) / bpp);
            if (sx < canvasLeft || sx >= canvasLeft + canvasSize) continue;
            Gui.drawRect(sx, canvasTop, sx + 1, canvasTop + canvasSize, COLOR_GRID);
        }
        int firstZ = Math.floorDiv(worldTop, 16) * 16;
        for (int wz = firstZ; wz <= worldBottom; wz += 16) {
            int sy = canvasTop + half + (int) ((wz - viewCenterZ) / bpp);
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

        double bpp = getBlocksPerPixel();
        int chunkPixelSize = Math.max(1, (int) Math.round(16 / bpp));
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
        double bpp = getBlocksPerPixel();
        int chunkPixelSize = Math.max(1, (int) Math.round(16 / bpp));
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
            label += " (" + cost + " CB)";
        }
        fontRenderer.drawStringWithShadow(label, x1 + 2, y1 - 10, claimDragging ? 0xFF00FF00 : 0xFFFF4444);
    }

    // ==================== PHASE 2: defensive-plan drawing ====================

    /** Draw every plan marker: polylines as coloured lines with endpoint dots, points as coloured
     *  squares with the marker's initial. The pending (uncommitted) polyline draws semi-transparent. */
    private void drawDefensePlan() {
        initIconStacks();
        java.util.List<studio.ERM.strategic.defense.DefenseMarker> markers =
                studio.ERM.war.map.client.ClientDefensePlanCache.markers();
        for (studio.ERM.strategic.defense.DefenseMarker m : markers) {
            int color = PLAN_COLORS[Math.max(0, Math.min(m.type, PLAN_COLORS.length - 1))];
            if (m.type == studio.ERM.strategic.defense.DefenseMarker.ENGAGEMENT_ZONE && m.points.size() >= 2) {
                drawZoneCircle(m, color); // kill-zone circle: centre + radius edge
            } else {
                drawPlanPolyline(m.points, color, false);
            }
            // Every marker gets its ITEM icon (the icon table): points at the point, polylines at centre.
            net.minecraft.util.math.BlockPos ic = m.center();
            int[] iscr = worldToScreen(ic.getX(), ic.getZ());
            if (iscr != null && onCanvasPoint(iscr)) {
                drawItemIcon(markerIconStacks[Math.max(0, Math.min(m.type, markerIconStacks.length - 1))],
                        iscr[0], iscr[1], OUTLINE_FRIENDLY);
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

    /** The siege alert: a small pulsing red-outlined BANNER icon at the ENEMY CAMP itself (the army's
     *  staging platform, not the target) -- the full label only appears on hover, so it's precise
     *  without shouting over the whole map. */
    private void drawSiegeAlert() {
        if (!studio.ERM.war.map.client.ClientStrategicCache.siegeActive()) return;
        initIconStacks();
        int[] scr = worldToScreen(studio.ERM.war.map.client.ClientStrategicCache.siegeX(),
                studio.ERM.war.map.client.ClientStrategicCache.siegeZ());
        if (scr == null || !onCanvasPoint(scr)) return;
        // Small pulsing ring so the eye finds it without it dominating the map.
        int pulse = (int) ((System.currentTimeMillis() / 120) % 6);
        int s = 7 + (pulse < 3 ? pulse : 6 - pulse);
        Gui.drawRect(scr[0] - s, scr[1] - s, scr[0] + s + 1, scr[1] - s + 1, 0x88FF1744);
        Gui.drawRect(scr[0] - s, scr[1] + s, scr[0] + s + 1, scr[1] + s + 1, 0x88FF1744);
        Gui.drawRect(scr[0] - s, scr[1] - s, scr[0] - s + 1, scr[1] + s + 1, 0x88FF1744);
        Gui.drawRect(scr[0] + s, scr[1] - s, scr[0] + s + 1, scr[1] + s + 1, 0x88FF1744);
        drawItemIcon(icEnemyCamp, scr[0], scr[1], OUTLINE_RIVAL);
        // Hover: the precise readout.
        int dx = uiMouseX - scr[0], dy = uiMouseY - scr[1];
        if (dx * dx + dy * dy <= 144) {
            String label = "Enemy camp: " + studio.ERM.war.map.client.ClientStrategicCache.siegeX()
                    + ", " + studio.ERM.war.map.client.ClientStrategicCache.siegeZ();
            int tw = fontRenderer.getStringWidth(label);
            Gui.drawRect(scr[0] - tw / 2 - 2, scr[1] - 22, scr[0] + tw / 2 + 2, scr[1] - 11, 0xCC330000);
            fontRenderer.drawStringWithShadow(label, scr[0] - tw / 2f, scr[1] - 20, 0xFFFF5252);
        }
    }

    /** Military side panel: units assigned X/Y + control reminders. Drawn in the SIDEBAR column
     *  (bottom-anchored, right of the map) so the canvas itself stays clean. */
    private void drawMilitaryPanel() {
        int assigned = 0;
        for (studio.ERM.strategic.defense.DefenseMarker m
                : studio.ERM.war.map.client.ClientDefensePlanCache.markers()) {
            if (m.isAssignable()) assigned += m.assigned;
        }
        int have = studio.ERM.war.map.client.ClientStrategicCache.friendlyCount();
        String[] lines = {
                TextFormatting.GOLD + "Military",
                TextFormatting.YELLOW + "Assigned: " + assigned + "/" + have,
                TextFormatting.GRAY + "Tools: list on left",
                TextFormatting.GRAY + "Click map to use",
                TextFormatting.GRAY + "R-click to confirm",
                TextFormatting.GRAY + "R-click map: battle",
        };
        int h = lines.length * 10 + 8;
        int x0 = canvasLeft + canvasSize + 6;                  // the sidebar column
        int y0 = (sidebarEndY > 0 ? sidebarEndY : canvasTop + 240) + 8; // BELOW the Legend, never over it
        Gui.drawRect(x0 - 3, y0 - 3, x0 + SIDEBAR_WIDTH - 6, y0 + h - 3, 0x99000000);
        for (int i = 0; i < lines.length; i++) {
            fontRenderer.drawStringWithShadow(lines[i], x0, y0 + i * 10, 0xFFFFFFFF);
        }
    }

    /** Charted resource deposits as DIAMOND icons: white = charted, gold = camp pending, green =
     *  your camp, red = the rival's camp, gray = exhausted. First letter of the resource beside it. */
    private void drawDepositIcons() {
        for (studio.ERM.war.map.net.S2CCivilPlanSync.Deposit d
                : studio.ERM.war.map.client.ClientCivilPlanCache.deposits()) {
            int[] s = worldToScreen(d.x, d.z);
            if (s == null) continue;
            int sx = s[0], sy = s[1];
            if (sx < canvasLeft + 5 || sx >= canvasLeft + canvasSize - 5
                    || sy < canvasTop + 5 || sy >= canvasTop + canvasSize - 5) continue;
            int color = d.state == 2 ? 0xFF40FF60 : d.state == 3 ? 0xFFFF5050
                    : d.state == 1 ? 0xFFFFC940 : d.state == 4 ? 0xFF909090 : 0xFFFFFFFF;
            for (int i = -4; i <= 4; i++) { // filled diamond via shrinking strips
                int half = 4 - Math.abs(i);
                Gui.drawRect(sx - half, sy + i, sx + half + 1, sy + i + 1, color);
            }
            Gui.drawRect(sx, sy, sx + 1, sy + 1, 0xFF202020); // centre dot so it reads as a site
            if (!d.name.isEmpty()) {
                fontRenderer.drawStringWithShadow(d.name.substring(0, 1), sx + 6, sy - 4, color);
            }
        }
    }

    /** The deposit icon under the cursor (5px pick radius), or null. */
    private studio.ERM.war.map.net.S2CCivilPlanSync.Deposit depositIconAt(int mouseX, int mouseY) {
        for (studio.ERM.war.map.net.S2CCivilPlanSync.Deposit d
                : studio.ERM.war.map.client.ClientCivilPlanCache.deposits()) {
            int[] s = worldToScreen(d.x, d.z);
            if (s == null) continue;
            if (Math.abs(mouseX - s[0]) <= 5 && Math.abs(mouseY - s[1]) <= 5) return d;
        }
        return null;
    }

    /** Sidebar EVACUATE button bounds (Civilian tab), below the chrome column. */
    private int[] evacButtonBounds() {
        int x0 = canvasLeft + canvasSize + 6;
        int y0 = (sidebarEndY > 0 ? sidebarEndY : canvasTop + 240) + 8;
        return new int[]{x0 - 3, y0 - 3, x0 + SIDEBAR_WIDTH - 6, y0 + 25};
    }

    private boolean isOnEvacButton(int mx, int my) {
        int[] b = evacButtonBounds();
        return mx >= b[0] && mx < b[2] && my >= b[1] && my < b[3];
    }

    /** The Civilian-tab EVACUATION toggle: red when an evacuation is under way. */
    private void drawEvacPanel(int mouseX, int mouseY) {
        boolean evac = studio.ERM.war.map.client.ClientStrategicCache.evacActive();
        int[] b = evacButtonBounds();
        boolean hover = isOnEvacButton(mouseX, mouseY);
        int bg = evac ? 0xEEB71C1C : (hover ? 0xEE37474F : 0xEE263238);
        Gui.drawRect(b[0], b[1], b[2], b[3], bg);
        Gui.drawRect(b[0], b[1], b[2], b[1] + 1, 0xFFFFFFFF);
        Gui.drawRect(b[0], b[3] - 1, b[2], b[3], 0xFF000000);
        String label = evac ? "EVACUATING!" : "EVACUATE";
        int tw = fontRenderer.getStringWidth(label);
        fontRenderer.drawStringWithShadow(label, b[0] + (b[2] - b[0] - tw) / 2f, b[1] + 4,
                evac ? 0xFFFFCDD2 : 0xFFFF8A80);
        String sub = evac ? "click to stand down" : "send civilians to shelter";
        int sw = fontRenderer.getStringWidth(sub);
        fontRenderer.drawStringWithShadow(TextFormatting.GRAY + sub, b[0] + (b[2] - b[0] - sw) / 2f,
                b[1] + 15, 0xFFAAAAAA);
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
        drawMapLine(x1, y1, x2, y2, argb, 2.0F);
    }

    private void drawMapLine(int x1, int y1, int x2, int y2, int argb, float width) {
        float a = (argb >>> 24) / 255F, r = ((argb >> 16) & 0xFF) / 255F,
                g = ((argb >> 8) & 0xFF) / 255F, b = (argb & 0xFF) / 255F;
        GlStateManager.disableTexture2D();
        GlStateManager.enableBlend();
        GlStateManager.glLineWidth(width);
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

    private int[] recruitButtonBounds() {
        int[] c = clearAllButtonBounds();
        return new int[]{c[2] + 4, c[1], c[2] + 4 + 54, c[3]};
    }

    private boolean isOnRecruitButton(int mx, int my) {
        int[] b = recruitButtonBounds();
        return mx >= b[0] && mx < b[2] && my >= b[1] && my < b[3];
    }

    // ==================== Marker properties panel ====================

    private studio.ERM.strategic.defense.DefenseMarker panelMarker() {
        if (panelUid == -1) return null;
        for (studio.ERM.strategic.defense.DefenseMarker m
                : studio.ERM.war.map.client.ClientDefensePlanCache.markers()) {
            if (m.uid == panelUid) return m;
        }
        return null;
    }

    /** Panel geometry: {x0, y0} with fixed 122x102 body; row i at y0 + 14 + i*14. */
    private int[] panelOrigin() {
        return new int[]{panelX, panelY};
    }

    private static boolean in(int mx, int my, int x0, int y0, int x1, int y1) {
        return mx >= x0 && mx < x1 && my >= y0 && my < y1;
    }

    /** Handle a click inside the properties panel. Returns false when the click was outside it. */
    private boolean handlePanelClick(int mx, int my) {
        studio.ERM.strategic.defense.DefenseMarker m = panelMarker();
        if (m == null) { panelUid = -1; return true; }
        int[] o = panelOrigin();
        int x0 = o[0], y0 = o[1];
        if (!in(mx, my, x0, y0, x0 + 122, y0 + 104)) return false; // outside -> caller closes

        int step = GuiScreen.isShiftKeyDown() ? 10 : 1;
        // Close X
        if (in(mx, my, x0 + 110, y0 + 2, x0 + 120, y0 + 12)) { panelUid = -1; return true; }
        // Units - / +
        if (m.isAssignable() && in(mx, my, x0 + 52, y0 + 16, x0 + 64, y0 + 26)) {
            m.assigned = Math.max(1, m.assigned - step); sendUpdate(m); return true;
        }
        if (m.isAssignable() && in(mx, my, x0 + 92, y0 + 16, x0 + 104, y0 + 26)) {
            m.assigned = Math.min(255, m.assigned + step); sendUpdate(m); return true;
        }
        // Priority - / +
        if (in(mx, my, x0 + 52, y0 + 30, x0 + 64, y0 + 40)) {
            m.priority = Math.max(0, m.priority - step * 5); sendUpdate(m); return true;
        }
        if (in(mx, my, x0 + 92, y0 + 30, x0 + 104, y0 + 40)) {
            m.priority = Math.min(100, m.priority + step * 5); sendUpdate(m); return true;
        }
        // Formation < / >
        int nF = studio.ERM.strategic.defense.DefenseMarker.FORMATIONS.length;
        if (in(mx, my, x0 + 34, y0 + 44, x0 + 46, y0 + 54)) {
            m.formation = (m.formation + nF - 1) % nF; sendUpdate(m); return true;
        }
        if (in(mx, my, x0 + 106, y0 + 44, x0 + 118, y0 + 54)) {
            m.formation = (m.formation + 1) % nF; sendUpdate(m); return true;
        }
        // Toggles
        if (in(mx, my, x0 + 4, y0 + 58, x0 + 60, y0 + 68)) {
            m.allowVehicles = !m.allowVehicles; sendUpdate(m); return true;
        }
        if (in(mx, my, x0 + 64, y0 + 58, x0 + 118, y0 + 68)) {
            m.reservePosition = !m.reservePosition; sendUpdate(m); return true;
        }
        // Assign all
        if (m.isAssignable() && in(mx, my, x0 + 4, y0 + 72, x0 + 118, y0 + 82)) {
            TacticalWarMapNetwork.sendToServer(
                    studio.ERM.war.map.net.C2SDefensePlanEdit.assignAll(m.uid));
            setStatus(TextFormatting.GREEN + "All unassigned units assigned.");
            return true;
        }
        // Delete
        if (in(mx, my, x0 + 4, y0 + 86, x0 + 118, y0 + 96)) {
            TacticalWarMapNetwork.sendToServer(
                    studio.ERM.war.map.net.C2SDefensePlanEdit.deleteUid(m.uid));
            panelUid = -1;
            setStatus(TextFormatting.YELLOW + "Marker deleted.");
            return true;
        }
        return true; // click inside the panel body: consume
    }

    private void sendUpdate(studio.ERM.strategic.defense.DefenseMarker m) {
        TacticalWarMapNetwork.sendToServer(studio.ERM.war.map.net.C2SDefensePlanEdit.update(m));
    }

    private void drawMarkerPanel() {
        studio.ERM.strategic.defense.DefenseMarker m = panelMarker();
        if (m == null) { panelUid = -1; return; }
        int[] o = panelOrigin();
        int x0 = o[0], y0 = o[1];
        int color = PLAN_COLORS[Math.max(0, Math.min(m.type, PLAN_COLORS.length - 1))];

        Gui.drawRect(x0, y0, x0 + 122, y0 + 104, 0xF2101018);
        Gui.drawRect(x0, y0, x0 + 122, y0 + 13, 0xFF1F1F2E);
        Gui.drawRect(x0, y0, x0 + 122, y0 + 1, color);
        fontRenderer.drawStringWithShadow(
                studio.ERM.strategic.defense.DefenseMarker.nameOf(m.type), x0 + 4, y0 + 3, color);
        fontRenderer.drawStringWithShadow("x", x0 + 112, y0 + 3, 0xFFFF5252);

        if (m.isAssignable()) {
            fontRenderer.drawStringWithShadow("Units:", x0 + 4, y0 + 17, 0xFFCCCCCC);
            fontRenderer.drawStringWithShadow("-", x0 + 56, y0 + 17, 0xFFFF8A80);
            fontRenderer.drawStringWithShadow(String.valueOf(m.assigned), x0 + 72, y0 + 17, 0xFFFFD54F);
            fontRenderer.drawStringWithShadow("+", x0 + 95, y0 + 17, 0xFF69F0AE);
        } else {
            fontRenderer.drawStringWithShadow("(not staffable)", x0 + 4, y0 + 17, 0xFF777777);
        }
        fontRenderer.drawStringWithShadow("Prio:", x0 + 4, y0 + 31, 0xFFCCCCCC);
        fontRenderer.drawStringWithShadow("-", x0 + 56, y0 + 31, 0xFFFF8A80);
        fontRenderer.drawStringWithShadow(String.valueOf(m.priority), x0 + 70, y0 + 31, 0xFFFFD54F);
        fontRenderer.drawStringWithShadow("+", x0 + 95, y0 + 31, 0xFF69F0AE);
        fontRenderer.drawStringWithShadow("Form:", x0 + 4, y0 + 45, 0xFFCCCCCC);
        fontRenderer.drawStringWithShadow("<", x0 + 37, y0 + 45, 0xFFFF8A80);
        String fName = studio.ERM.strategic.defense.DefenseMarker.FORMATIONS[
                Math.max(0, Math.min(m.formation, studio.ERM.strategic.defense.DefenseMarker.FORMATIONS.length - 1))];
        fontRenderer.drawStringWithShadow(fName, x0 + 50, y0 + 45, 0xFFFFD54F);
        fontRenderer.drawStringWithShadow(">", x0 + 109, y0 + 45, 0xFF69F0AE);
        fontRenderer.drawStringWithShadow((m.allowVehicles ? "[x] " : "[ ] ") + "Vehicles",
                x0 + 4, y0 + 59, m.allowVehicles ? 0xFF69F0AE : 0xFF888888);
        fontRenderer.drawStringWithShadow((m.reservePosition ? "[x] " : "[ ] ") + "Reserve",
                x0 + 64, y0 + 59, m.reservePosition ? 0xFF69F0AE : 0xFF888888);
        Gui.drawRect(x0 + 4, y0 + 72, x0 + 118, y0 + 82, 0xFF263238);
        fontRenderer.drawStringWithShadow("ASSIGN ALL UNASSIGNED", x0 + 8, y0 + 73, 0xFF80D8FF);
        Gui.drawRect(x0 + 4, y0 + 86, x0 + 118, y0 + 96, 0xFF3E1010);
        fontRenderer.drawStringWithShadow("DELETE MARKER", x0 + 27, y0 + 87, 0xFFFF5252);
    }

    // ==================== CIVILIAN DISTRICT PANEL ====================

    private studio.ERM.strategic.civil.CivilMarker civPanelMarker() {
        if (civPanelUid == -1) return null;
        for (studio.ERM.strategic.civil.CivilMarker m
                : studio.ERM.war.map.client.ClientCivilPlanCache.markers()) {
            if (m.uid == civPanelUid && !m.isRoad()) return m;
        }
        return null;
    }

    /** District panel click: Workers -/+ (shift = 10) via the depot, or delete. */
    private boolean handleCivPanelClick(int mx, int my) {
        studio.ERM.strategic.civil.CivilMarker m = civPanelMarker();
        if (m == null) { civPanelUid = -1; return true; }
        int x0 = civPanelX, y0 = civPanelY;
        if (!in(mx, my, x0, y0, x0 + 122, y0 + 92)) return false; // outside -> caller closes

        int step = GuiScreen.isShiftKeyDown() ? 10 : 1;
        // Close X
        if (in(mx, my, x0 + 110, y0 + 2, x0 + 120, y0 + 12)) { civPanelUid = -1; return true; }

        // Workers - / + (only when a depot is bound — worker count lives on the depot tile).
        if (m.depotPos != null) {
            if (in(mx, my, x0 + 52, y0 + 30, x0 + 64, y0 + 40)) { editWorkers(m, -step); return true; }
            if (in(mx, my, x0 + 92, y0 + 30, x0 + 104, y0 + 40)) { editWorkers(m, step); return true; }
        }
        // Delete district
        if (in(mx, my, x0 + 4, y0 + 74, x0 + 118, y0 + 86)) {
            net.minecraft.util.math.BlockPos c = m.center();
            TacticalWarMapNetwork.sendToServer(
                    studio.ERM.war.map.net.C2SCivilPlanEdit.removeNearest(c.getX(), c.getZ()));
            civPanelUid = -1;
            setStatus(TextFormatting.YELLOW + "District deleted.");
            return true;
        }
        return true; // inside the panel body: consume
    }

    private void editWorkers(studio.ERM.strategic.civil.CivilMarker m, int delta) {
        TacticalWarMapNetwork.sendToServer(
                new studio.ERM.war.map.net.C2SDistrictDepotEdit(m.depotPos, delta));
        // Optimistic local echo; the follow-up sync request lands the authoritative value.
        m.desiredWorkers = Math.max(0, Math.min(64, m.desiredWorkers + delta));
        TacticalWarMapNetwork.sendToServer(studio.ERM.war.map.net.C2SCivilPlanEdit.requestSync());
    }

    private void drawCivPanel() {
        studio.ERM.strategic.civil.CivilMarker m = civPanelMarker();
        if (m == null) { civPanelUid = -1; return; }
        int x0 = civPanelX, y0 = civPanelY;
        int color = CIVIL_COLORS[Math.max(0, Math.min(m.kind, CIVIL_COLORS.length - 1))];

        Gui.drawRect(x0, y0, x0 + 122, y0 + 92, 0xF2101018);
        Gui.drawRect(x0, y0, x0 + 122, y0 + 13, 0xFF1F1F2E);
        Gui.drawRect(x0, y0, x0 + 122, y0 + 1, color);
        fontRenderer.drawStringWithShadow(
                studio.ERM.strategic.civil.CivilMarker.nameOf(m.kind) + " District", x0 + 4, y0 + 3, color);
        fontRenderer.drawStringWithShadow("x", x0 + 112, y0 + 3, 0xFFFF5252);

        // Depot status.
        boolean depot = m.depotPos != null;
        fontRenderer.drawStringWithShadow(depot
                        ? TextFormatting.GREEN + "Depot bound"
                        : TextFormatting.YELLOW + "No depot placed",
                x0 + 4, y0 + 17, 0xFFFFFFFF);

        // Workers X / Y with -/+.
        if (depot) {
            fontRenderer.drawStringWithShadow("Workers:", x0 + 4, y0 + 31, 0xFFCCCCCC);
            fontRenderer.drawStringWithShadow("-", x0 + 56, y0 + 31, 0xFFFF8A80);
            String wc = m.assignedWorkers + "/" + m.desiredWorkers;
            fontRenderer.drawStringWithShadow(wc, x0 + 70, y0 + 31, 0xFFFFD54F);
            fontRenderer.drawStringWithShadow("+", x0 + 95, y0 + 31, 0xFF69F0AE);
            fontRenderer.drawStringWithShadow(TextFormatting.DARK_GRAY + "shift ±10",
                    x0 + 4, y0 + 45, 0xFF777777);
        } else {
            fontRenderer.drawStringWithShadow(TextFormatting.GRAY + "Place a District Marker",
                    x0 + 4, y0 + 31, 0xFFAAAAAA);
            fontRenderer.drawStringWithShadow(TextFormatting.GRAY + "block inside, then tag it",
                    x0 + 4, y0 + 43, 0xFFAAAAAA);
        }

        Gui.drawRect(x0 + 4, y0 + 74, x0 + 118, y0 + 86, 0xFF3E1010);
        fontRenderer.drawStringWithShadow("DELETE DISTRICT", x0 + 22, y0 + 76, 0xFFFF5252);
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

        int[] rb = recruitButtonBounds();
        Gui.drawRect(rb[0], rb[1], rb[2], rb[3], 0xEE1B3A1B);
        Gui.drawRect(rb[0], rb[1], rb[2], rb[1] + 1, 0xFFFFFFFF);
        Gui.drawRect(rb[0], rb[3] - 1, rb[2], rb[3], 0xFF000000);
        String rl = "RECRUIT";
        int rtw = fontRenderer.getStringWidth(rl);
        fontRenderer.drawStringWithShadow(rl, rb[0] + (rb[2] - rb[0] - rtw) / 2f, rb[1] + 3, 0xFF69F0AE);

        // ALWAYS show the active tool -- "Troop Allocation" (edit markers) is the default tool, so the
        // player knows clicking a marker opens its properties rather than feeling like an empty mode.
        String tool = (planMode < 0)
                ? "TOOL: Troop Allocation"
                : "TOOL: " + studio.ERM.strategic.defense.DefenseMarker.nameOf(planMode)
                  + (planPending.isEmpty() ? "" : " (" + planPending.size() + " pts)");
        fontRenderer.drawStringWithShadow(tool, b[0], b[3] + 3, planMode < 0 ? 0xFFFFD54F : 0xFF00E5FF);
    }

    /** White citizen dots: the player's civilian workers, live on the Civilian tab. */
    private void drawCitizenDots() {
        int[] cd = studio.ERM.war.map.client.ClientStrategicCache.citizenDots();
        for (int i = 0; i + 1 < cd.length; i += 2) {
            int[] scr = worldToScreen(cd[i], cd[i + 1]);
            if (scr == null || !onCanvasPoint(scr)) continue;
            Gui.drawRect(scr[0] - 1, scr[1] - 1, scr[0] + 1, scr[1] + 1, 0xFFFFFFFF);
        }
    }

    /** Civilian tab's active-tool readout (top-left of the canvas, under the tab bar). */
    private void drawCivilToolReadout() {
        String tool = (civilMode < 0)
                ? "TOOL: Inspect"
                : "TOOL: " + studio.ERM.strategic.civil.CivilMarker.nameOf(civilMode)
                  + (civilPending.isEmpty() ? "" : " (" + civilPending.size() + " pts)");
        fontRenderer.drawStringWithShadow(tool, canvasLeft + 4, canvasTop + 20,
                civilMode < 0 ? 0xFFFFD54F : 0xFF00E5FF);
    }

    // ==================== LEFT TOOL LIST (Military + Civilian tabs) ====================
    // The tools moved from the old P-key cycle to a selectable Icon+Name list LEFT of the canvas,
    // mirroring the stats sidebar on the right. Row 0 is each tab's "no tool" mode (Troop
    // Allocation / Inspect); every other row maps to DefenseMarker type / CivilMarker kind (row-1).

    private static final String[] MIL_TOOL_NAMES = {
            "Troop Alloc", "Def. Line", "Strongpoint", "Vehicle Pos", "AA Battery", "Rally Point",
            "Reserve", "Fallback Ln", "Patrol Route", "Heli LZ", "Medical", "Engage Zone" };
    private static final String[] CIV_TOOL_NAMES = {
            // Hunting is NOT a district — it's a Strategic Mission (civilian-map right-click). Quarry
            // replaces Mining. The row index still maps to the CivilMarker kind (civilMode = row - 1),
            // so this list must stay a prefix of the kind order and Hunting (the last kind) drops off.
            "Inspect", "Road", "Residential", "Barracks", "Warehouse", "Armory",
            "Kitchen", "Hospital", "Factory", "Research", "Trade Depot",
            "Fishing", "Lumber", "Farm", "Quarry" };
    private static net.minecraft.item.ItemStack[] milToolIcons, civToolIcons;

    private int toolbarX() {
        return canvasLeft - 6 - TOOLBAR_WIDTH;
    }

    // ==================== Strategic Mission menu ====================

    private static final int MISSION_W = 92, MISSION_ROW_H = 14;

    private int missionRowAt(int mx, int my) {
        if (!missionMenuOpen) return -1;
        int y0 = missionMenuY + 12;
        if (mx < missionMenuX || mx >= missionMenuX + MISSION_W) return -1;
        int row = (my - y0) / MISSION_ROW_H;
        return (row >= 0 && row < MISSION_NAMES.length) ? row : -1;
    }

    private void drawMissionMenu(int mouseX, int mouseY) {
        if (!missionMenuOpen) return;
        int x0 = missionMenuX, y0 = missionMenuY;
        int h = 12 + MISSION_NAMES.length * MISSION_ROW_H + 2;
        Gui.drawRect(x0, y0, x0 + MISSION_W, y0 + h, 0xF2101018);
        Gui.drawRect(x0, y0, x0 + MISSION_W, y0 + 11, 0xFF1F3A1F);
        Gui.drawRect(x0, y0, x0 + MISSION_W, y0 + 1, 0xFF69F0AE);
        fontRenderer.drawStringWithShadow("Strategic Mission", x0 + 3, y0 + 2, 0xFF69F0AE);
        int hover = missionRowAt(mouseX, mouseY);
        for (int i = 0; i < MISSION_NAMES.length; i++) {
            int ry = y0 + 12 + i * MISSION_ROW_H;
            Gui.drawRect(x0 + 1, ry, x0 + MISSION_W - 1, ry + MISSION_ROW_H - 1,
                    (i == hover) ? 0xCC2C3A47 : 0x9910101E);
            fontRenderer.drawStringWithShadow(MISSION_NAMES[i], x0 + 5, ry + 3, 0xFFE0E0E0);
        }
    }

    // ==================== Nation diplomacy dropdown ====================

    private int diploRowAt(int mx, int my) {
        if (!diploMenuOpen) return -1;
        int y0 = diploMenuY + 12;
        if (mx < diploMenuX || mx >= diploMenuX + DIPLO_W) return -1;
        int row = (my - y0) / MISSION_ROW_H;
        return (row >= 0 && row < DIPLO_NAMES.length) ? row : -1;
    }

    private void drawDiploMenu(int mouseX, int mouseY) {
        if (!diploMenuOpen) return;
        int x0 = diploMenuX, y0 = diploMenuY;
        int h = 12 + DIPLO_NAMES.length * MISSION_ROW_H + 2;
        Gui.drawRect(x0, y0, x0 + DIPLO_W, y0 + h, 0xF2101018);
        Gui.drawRect(x0, y0, x0 + DIPLO_W, y0 + 11, 0xFF1F2E3A);
        Gui.drawRect(x0, y0, x0 + DIPLO_W, y0 + 1, 0xFF80D8FF);
        fontRenderer.drawStringWithShadow(TextFormatting.AQUA + trimTo(diploNation, 20), x0 + 3, y0 + 2, 0xFF80D8FF);
        int hover = diploRowAt(mouseX, mouseY);
        for (int i = 0; i < DIPLO_NAMES.length; i++) {
            int ry = y0 + 12 + i * MISSION_ROW_H;
            Gui.drawRect(x0 + 1, ry, x0 + DIPLO_W - 1, ry + MISSION_ROW_H - 1,
                    (i == hover) ? 0xCC2C3A47 : 0x9910101E);
            fontRenderer.drawStringWithShadow(DIPLO_NAMES[i], x0 + 5, ry + 3, 0xFFE0E0E0);
        }
    }

    private static String trimTo(String s, int max) {
        return s != null && s.length() > max ? s.substring(0, max - 1) + "…" : (s == null ? "" : s);
    }

    private String[] toolNames() {
        return activeTab == 2 ? MIL_TOOL_NAMES : CIV_TOOL_NAMES;
    }

    /** The tool-list row under the mouse (0-based), or -1. Rows start below the "Tools" header. */
    private int toolbarRowAt(int mx, int my) {
        int tx = toolbarX();
        if (mx < tx || mx >= tx + TOOLBAR_WIDTH - 2) return -1;
        int y0 = canvasTop + 14;
        if (my < y0) return -1;
        int row = (my - y0) / 14;
        return (row >= 0 && row < toolNames().length) ? row : -1;
    }

    /** The selectable Icon+Name tool list, drawn in the LEFT column (mirror of the stats sidebar). */
    private void drawToolbar(int mouseX, int mouseY) {
        initIconStacks();
        String[] names = toolNames();
        net.minecraft.item.ItemStack[] icons = (activeTab == 2) ? milToolIcons : civToolIcons;
        int selected = ((activeTab == 2) ? planMode : civilMode) + 1;
        int tx = toolbarX();

        fontRenderer.drawStringWithShadow(TextFormatting.GOLD + "" + TextFormatting.BOLD + "Tools",
                tx, canvasTop, 0xFFFFFFFF);
        int hover = toolbarRowAt(mouseX, mouseY);
        for (int i = 0; i < names.length; i++) {
            int ry = canvasTop + 14 + i * 14;
            int bg = (i == selected) ? 0xEE1565C0 : (i == hover ? 0xCC2C3A47 : 0x9910101E);
            Gui.drawRect(tx, ry, tx + TOOLBAR_WIDTH - 2, ry + 13, bg);
            if (i == selected) Gui.drawRect(tx, ry, tx + 2, ry + 13, 0xFFFFFFFF);
            if (icons != null && i < icons.length) drawItemIcon(icons[i], tx + 9, ry + 6, 0);
            fontRenderer.drawStringWithShadow(names[i], tx + 17, ry + 3,
                    (i == selected) ? 0xFFFFFFFF : 0xFFB0BEC5);
        }
    }

    // ==================== CIVILIAN plan drawing ====================

    /** Roads as cased polylines tinted by their DETECTED material + condition, districts as
     *  translucent polygons with outline + icon + name, live courier jobs as moving gold lines. */
    private void drawCivilPlan() {
        initIconStacks();
        for (studio.ERM.strategic.civil.CivilMarker m
                : studio.ERM.war.map.client.ClientCivilPlanCache.markers()) {
            int color = CIVIL_COLORS[Math.max(0, Math.min(m.kind, CIVIL_COLORS.length - 1))];
            if (m.isRoad()) {
                drawRoad(m);
            } else {
                drawDistrictPolygon(m.points, color, false);
                net.minecraft.util.math.BlockPos c = m.center();
                int[] scr = worldToScreen(c.getX(), c.getZ());
                if (scr != null && onCanvasPoint(scr)) {
                    drawItemIcon(civToolIcons[Math.min(m.kind + 1, civToolIcons.length - 1)],
                            scr[0], scr[1], 0);
                    // A district without a bound District Marker block cannot hire ANY workers —
                    // say so on the map instead of leaving "workers free but idle" a mystery.
                    String label = studio.ERM.strategic.civil.CivilMarker.nameOf(m.kind)
                            + (m.hasDepot() ? "" : " §c⚠ no depot");
                    int tw = fontRenderer.getStringWidth(label);
                    fontRenderer.drawStringWithShadow(label, scr[0] - tw / 2f, scr[1] + 8, color);
                }
            }
        }
        // Pending shape preview (semi-transparent); districts show their closing edge live.
        if (civilMode >= 0 && !civilPending.isEmpty()) {
            int color = (CIVIL_COLORS[Math.max(0, Math.min(civilMode, CIVIL_COLORS.length - 1))]
                    & 0x00FFFFFF) | 0x88000000;
            if (civilMode == studio.ERM.strategic.civil.CivilMarker.ROAD) {
                drawRoadLine(civilPending, color, true);
            } else {
                drawDistrictPolygon(civilPending, color, true);
            }
        }
        // LIVE COURIER JOBS: src -> dst freight lines with a travelling dot (the logistics pulse).
        drawCourierJobs();

        // Hover readout: what is under the cursor (districts by containment, roads near the line).
        // Roads read out their DETECTED material + condition ("Road — Gravel, Good").
        studio.ERM.strategic.civil.CivilMarker hover = civilMarkerAt(uiMouseX, uiMouseY);
        if (hover != null) {
            String label;
            if (hover.isRoad()) {
                label = "Road";
                int seg = roadSegmentAt(hover, uiMouseX, uiMouseY);
                if (seg != -1 && seg < hover.segCondition.length) {
                    String mat = seg < hover.segLabel.length && hover.segLabel[seg] != null
                            && !hover.segLabel[seg].isEmpty() ? hover.segLabel[seg] : null;
                    int cond = hover.segCondition[seg];
                    label = (mat != null ? mat + " Road" : "Road") + " — "
                            + studio.ERM.strategic.civil.CivilMarker.CONDITION_NAMES[
                                    Math.max(0, Math.min(cond,
                                    studio.ERM.strategic.civil.CivilMarker.CONDITION_NAMES.length - 1))];
                }
            } else {
                label = studio.ERM.strategic.civil.CivilMarker.nameOf(hover.kind) + " District";
            }
            int tw = fontRenderer.getStringWidth(label);
            Gui.drawRect(uiMouseX + 6, uiMouseY - 12, uiMouseX + tw + 12, uiMouseY - 1, 0xCC000000);
            fontRenderer.drawStringWithShadow(label, uiMouseX + 9, uiMouseY - 10, 0xFFFFD54F);
        }
    }

    /** Live courier jobs as thin freight lines: gray while waiting for a courier, gold when one
     *  is on the move (with a travelling dot src -> dst). Hover a line for the cargo readout. */
    private void drawCourierJobs() {
        java.util.List<studio.ERM.war.map.net.S2CCivilPlanSync.JobLine> jobs =
                studio.ERM.war.map.client.ClientCivilPlanCache.jobs();
        if (jobs.isEmpty()) return;
        String hoverLabel = null;
        for (int i = 0; i < jobs.size(); i++) {
            studio.ERM.war.map.net.S2CCivilPlanSync.JobLine j = jobs.get(i);
            int[] s = worldToScreen(j.sx, j.sz);
            int[] d = worldToScreen(j.dx, j.dz);
            if (s == null || d == null) continue;
            boolean shipment = j.state == 3; // trade cart: teal, REAL progress
            boolean assigned = j.state != 0; // CourierJob.STATE_PENDING
            int color = shipment ? 0xCC26A69A : assigned ? 0xCCFFC107 : 0x66B0BEC5;
            drawMapLine(s[0], s[1], d[0], d[1], color, assigned ? 2.0F : 1.0F);
            if (assigned) {
                // The travelling dot: real progress for shipments, shared-clock loop for couriers.
                double t = (shipment && j.progress >= 0) ? j.progress / 100.0
                        : ((System.currentTimeMillis() / 30 + i * 33) % 100) / 100.0;
                int px = (int) (s[0] + (d[0] - s[0]) * t);
                int py = (int) (s[1] + (d[1] - s[1]) * t);
                if (onCanvasPoint(new int[]{px, py})) {
                    Gui.drawRect(px - 2, py - 2, px + 3, py + 3, shipment ? 0xFF80CBC4 : 0xFFFFE082);
                }
            }
            if (hoverLabel == null
                    && pointSegDistSq(uiMouseX, uiMouseY, s[0], s[1], d[0], d[1]) <= 9) {
                hoverLabel = j.label;
            }
        }
        if (hoverLabel != null && !hoverLabel.isEmpty()) {
            int tw = fontRenderer.getStringWidth(hoverLabel);
            Gui.drawRect(uiMouseX + 6, uiMouseY + 2, uiMouseX + tw + 12, uiMouseY + 13, 0xCC101800);
            fontRenderer.drawStringWithShadow(hoverLabel, uiMouseX + 9, uiMouseY + 4, 0xFFFFC107);
        }
    }

    /** Per-segment road rendering: each stretch tinted by its DETECTED majority block, styled by
     *  condition — solid when kept, fading as it degrades, red-scarred casing when DESTROYED. */
    private void drawRoad(studio.ERM.strategic.civil.CivilMarker m) {
        m.ensureSegArrays();
        int fallback = CIVIL_COLORS[0];
        for (int i = 0; i < m.segmentCount(); i++) {
            net.minecraft.util.math.BlockPos a = m.points.get(i), b = m.points.get(i + 1);
            int[] s = worldToScreen(a.getX(), a.getZ());
            int[] d = worldToScreen(b.getX(), b.getZ());
            if (s == null || d == null) continue;
            int cond = i < m.segCondition.length ? m.segCondition[i]
                    : studio.ERM.strategic.civil.CivilMarker.COND_UNSAMPLED;
            int color = (i < m.segColor.length && m.segColor[i] != 0) ? m.segColor[i] : fallback;
            if (cond == studio.ERM.strategic.civil.CivilMarker.COND_DESTROYED) {
                // Destroyed: dark scar with red hazard ticks — reads as impassable at a glance.
                drawMapLine(s[0], s[1], d[0], d[1], 0xCC4E1010, 4.0F);
                drawMapLine(s[0], s[1], d[0], d[1], 0x99FF1744, 1.5F);
            } else {
                int alpha = cond == studio.ERM.strategic.civil.CivilMarker.COND_POOR ? 0x88
                        : cond == studio.ERM.strategic.civil.CivilMarker.COND_FAIR ? 0xCC : 0xFF;
                drawMapLine(s[0], s[1], d[0], d[1], 0xCC3E2723, 4.0F);
                drawMapLine(s[0], s[1], d[0], d[1], (color & 0x00FFFFFF) | (alpha << 24), 2.0F);
            }
        }
        // Waypoint dots on top.
        for (net.minecraft.util.math.BlockPos p : m.points) {
            int[] scr = worldToScreen(p.getX(), p.getZ());
            if (scr != null && onCanvasPoint(scr)) {
                Gui.drawRect(scr[0] - 1, scr[1] - 1, scr[0] + 2, scr[1] + 2, fallback);
            }
        }
    }

    /** The road segment index nearest the mouse (within ~5 px), or -1. */
    private int roadSegmentAt(studio.ERM.strategic.civil.CivilMarker m, int mx, int my) {
        int best = -1;
        double bd = 25;
        for (int i = 0; i < m.segmentCount(); i++) {
            int[] s = worldToScreen(m.points.get(i).getX(), m.points.get(i).getZ());
            int[] d = worldToScreen(m.points.get(i + 1).getX(), m.points.get(i + 1).getZ());
            if (s == null || d == null) continue;
            double dist = pointSegDistSq(mx, my, s[0], s[1], d[0], d[1]);
            if (dist < bd) { bd = dist; best = i; }
        }
        return best;
    }

    /** A road: dark casing under a tan centre line (reads as infrastructure), waypoint dots. */
    private void drawRoadLine(java.util.List<net.minecraft.util.math.BlockPos> pts, int color, boolean pending) {
        int casing = pending ? 0x66201510 : 0xCC3E2723;
        int[] prev = null;
        for (net.minecraft.util.math.BlockPos p : pts) {
            int[] scr = worldToScreen(p.getX(), p.getZ());
            if (scr == null) { prev = null; continue; }
            if (prev != null) drawMapLine(prev[0], prev[1], scr[0], scr[1], casing, 4.0F);
            prev = scr;
        }
        prev = null;
        for (net.minecraft.util.math.BlockPos p : pts) {
            int[] scr = worldToScreen(p.getX(), p.getZ());
            if (scr == null) { prev = null; continue; }
            if (onCanvasPoint(scr)) Gui.drawRect(scr[0] - 1, scr[1] - 1, scr[0] + 2, scr[1] + 2, color);
            if (prev != null) drawMapLine(prev[0], prev[1], scr[0], scr[1], color, 2.0F);
            prev = scr;
        }
    }

    /** A district polygon: translucent fill (triangle fan from the centroid) + solid outline with
     *  corner dots. The closing edge draws whenever there are >=3 corners, so pending polygons show
     *  their final shape live. Concave shapes may over-fill slightly; the outline stays exact. */
    private void drawDistrictPolygon(java.util.List<net.minecraft.util.math.BlockPos> pts, int color, boolean pending) {
        int n = pts.size();
        if (n >= 3) {
            long cx = 0, cz = 0;
            for (net.minecraft.util.math.BlockPos p : pts) { cx += p.getX(); cz += p.getZ(); }
            int[] cs = worldToScreen((int) (cx / n), (int) (cz / n));
            int fill = (color & 0x00FFFFFF) | (pending ? 0x22000000 : 0x3A000000);
            float a = (fill >>> 24) / 255F, r = ((fill >> 16) & 0xFF) / 255F,
                    g = ((fill >> 8) & 0xFF) / 255F, b = (fill & 0xFF) / 255F;
            GlStateManager.disableTexture2D();
            GlStateManager.enableBlend();
            GlStateManager.tryBlendFuncSeparate(
                    GlStateManager.SourceFactor.SRC_ALPHA, GlStateManager.DestFactor.ONE_MINUS_SRC_ALPHA,
                    GlStateManager.SourceFactor.ONE, GlStateManager.DestFactor.ZERO);
            net.minecraft.client.renderer.Tessellator tess = net.minecraft.client.renderer.Tessellator.getInstance();
            net.minecraft.client.renderer.BufferBuilder buf = tess.getBuffer();
            buf.begin(org.lwjgl.opengl.GL11.GL_TRIANGLE_FAN,
                    net.minecraft.client.renderer.vertex.DefaultVertexFormats.POSITION_COLOR);
            buf.pos(cs[0], cs[1], 0).color(r, g, b, a).endVertex();
            for (int i = 0; i <= n; i++) {
                net.minecraft.util.math.BlockPos p = pts.get(i % n);
                int[] scr = worldToScreen(p.getX(), p.getZ());
                buf.pos(scr[0], scr[1], 0).color(r, g, b, a).endVertex();
            }
            tess.draw();
            GlStateManager.disableBlend();
            GlStateManager.enableTexture2D();
            GlStateManager.color(1F, 1F, 1F, 1F);
        }
        // Outline: consecutive edges, plus the closing edge once a polygon exists.
        int[] prev = null, first = null;
        for (net.minecraft.util.math.BlockPos p : pts) {
            int[] scr = worldToScreen(p.getX(), p.getZ());
            if (scr == null) { prev = null; continue; }
            if (first == null) first = scr;
            if (onCanvasPoint(scr)) Gui.drawRect(scr[0] - 1, scr[1] - 1, scr[0] + 2, scr[1] + 2, color);
            if (prev != null) drawMapLine(prev[0], prev[1], scr[0], scr[1], color, 2.0F);
            prev = scr;
        }
        if (n >= 3 && prev != null && first != null) {
            drawMapLine(prev[0], prev[1], first[0], first[1], color, 2.0F);
        }
    }

    /** The civil marker under the mouse: districts by polygon containment, roads within ~4 px of
     *  any segment. Iterates in reverse so the most recently drawn shape wins overlaps. */
    private studio.ERM.strategic.civil.CivilMarker civilMarkerAt(int mx, int my) {
        java.util.List<studio.ERM.strategic.civil.CivilMarker> list =
                studio.ERM.war.map.client.ClientCivilPlanCache.markers();
        for (int k = list.size() - 1; k >= 0; k--) {
            studio.ERM.strategic.civil.CivilMarker m = list.get(k);
            if (m.isRoad()) {
                int[] prev = null;
                for (net.minecraft.util.math.BlockPos p : m.points) {
                    int[] scr = worldToScreen(p.getX(), p.getZ());
                    if (scr == null) { prev = null; continue; }
                    if (prev != null && pointSegDistSq(mx, my, prev[0], prev[1], scr[0], scr[1]) <= 16) return m;
                    prev = scr;
                }
            } else if (m.points.size() >= 3 && pointInPolygon(mx, my, m.points)) {
                return m;
            }
        }
        return null;
    }

    /** Squared distance from point (px,py) to segment (x1,y1)-(x2,y2), all in screen pixels. */
    private static double pointSegDistSq(int px, int py, int x1, int y1, int x2, int y2) {
        double dx = x2 - x1, dy = y2 - y1;
        double len2 = dx * dx + dy * dy;
        double t = len2 <= 0 ? 0 : Math.max(0, Math.min(1, ((px - x1) * dx + (py - y1) * dy) / len2));
        double qx = x1 + t * dx - px, qy = y1 + t * dy - py;
        return qx * qx + qy * qy;
    }

    /** Ray-cast containment test in SCREEN space against the polygon's projected vertices. */
    private boolean pointInPolygon(int mx, int my, java.util.List<net.minecraft.util.math.BlockPos> pts) {
        boolean in = false;
        int n = pts.size();
        for (int i = 0, j = n - 1; i < n; j = i++) {
            int[] a = worldToScreen(pts.get(i).getX(), pts.get(i).getZ());
            int[] b = worldToScreen(pts.get(j).getX(), pts.get(j).getZ());
            if (a == null || b == null) continue;
            if ((a[1] > my) != (b[1] > my)
                    && mx < (double) (b[0] - a[0]) * (my - a[1]) / (double) (b[1] - a[1]) + a[0]) {
                in = !in;
            }
        }
        return in;
    }

    // ==================== ITEM-BASED map icons (the icon table) ====================
    // Allegiance colours: green = yours, red = rival, yellow = neutral trade, blue = allied (later).
    private static final int OUTLINE_FRIENDLY = 0xAA00E676;
    private static final int OUTLINE_RIVAL = 0xAAFF1744;
    private static final int OUTLINE_NEUTRAL = 0xAAFFEB3B;

    private static net.minecraft.item.ItemStack[] markerIconStacks;
    private static net.minecraft.item.ItemStack icTrader, icPatrol, icSquad, icVehicle, icEnemyCamp;

    private static void initIconStacks() {
        if (markerIconStacks != null) return;
        markerIconStacks = new net.minecraft.item.ItemStack[]{
                new net.minecraft.item.ItemStack(net.minecraft.init.Items.IRON_SWORD),      // LINE
                new net.minecraft.item.ItemStack(net.minecraft.init.Items.SHIELD),          // STRONGPOINT
                new net.minecraft.item.ItemStack(net.minecraft.init.Items.MINECART),        // VEHICLE
                new net.minecraft.item.ItemStack(net.minecraft.init.Items.BOW),             // AA
                new net.minecraft.item.ItemStack(net.minecraft.init.Items.BANNER),          // RALLY
                new net.minecraft.item.ItemStack(net.minecraft.init.Items.BED),             // RESERVE
                new net.minecraft.item.ItemStack(net.minecraft.init.Items.ARROW),           // FALLBACK
                new net.minecraft.item.ItemStack(net.minecraft.init.Items.LEATHER_BOOTS),   // PATROL ROUTE
                new net.minecraft.item.ItemStack(
                        net.minecraft.item.Item.getItemFromBlock(net.minecraft.init.Blocks.SLIME_BLOCK)), // LZ
                new net.minecraft.item.ItemStack(net.minecraft.init.Items.POTIONITEM),      // MEDICAL
                new net.minecraft.item.ItemStack(
                        net.minecraft.item.Item.getItemFromBlock(net.minecraft.init.Blocks.TNT))          // ZONE
        };
        icTrader = new net.minecraft.item.ItemStack(net.minecraft.init.Items.EMERALD);
        icPatrol = new net.minecraft.item.ItemStack(net.minecraft.init.Items.IRON_SWORD);
        icSquad = new net.minecraft.item.ItemStack(net.minecraft.init.Items.IRON_SWORD);
        icVehicle = new net.minecraft.item.ItemStack(net.minecraft.init.Items.MINECART);
        icEnemyCamp = new net.minecraft.item.ItemStack(net.minecraft.init.Items.BANNER);

        // LEFT tool-list icons. Military row 0 = Troop Allocation, then the marker types above.
        milToolIcons = new net.minecraft.item.ItemStack[MIL_TOOL_NAMES.length];
        milToolIcons[0] = new net.minecraft.item.ItemStack(net.minecraft.init.Items.NAME_TAG);
        System.arraycopy(markerIconStacks, 0, milToolIcons, 1, markerIconStacks.length);
        // Civilian row 0 = Inspect, row 1 = Road, then the district kinds (CivilMarker order).
        civToolIcons = new net.minecraft.item.ItemStack[]{
                new net.minecraft.item.ItemStack(net.minecraft.init.Items.COMPASS),          // Inspect
                new net.minecraft.item.ItemStack(net.minecraft.item.Item.getItemFromBlock(
                        net.minecraft.init.Blocks.GRASS_PATH)),                              // Road
                new net.minecraft.item.ItemStack(net.minecraft.init.Items.BED),             // Residential
                new net.minecraft.item.ItemStack(net.minecraft.init.Items.IRON_CHESTPLATE), // Barracks
                new net.minecraft.item.ItemStack(net.minecraft.item.Item.getItemFromBlock(
                        net.minecraft.init.Blocks.CHEST)),                                   // Warehouse
                new net.minecraft.item.ItemStack(net.minecraft.init.Items.IRON_SWORD),      // Armory
                new net.minecraft.item.ItemStack(net.minecraft.init.Items.BREAD),           // Kitchen
                new net.minecraft.item.ItemStack(net.minecraft.init.Items.GOLDEN_APPLE),    // Hospital
                new net.minecraft.item.ItemStack(net.minecraft.item.Item.getItemFromBlock(
                        net.minecraft.init.Blocks.FURNACE)),                                 // Factory
                new net.minecraft.item.ItemStack(net.minecraft.init.Items.BOOK),            // Research
                new net.minecraft.item.ItemStack(net.minecraft.init.Items.EMERALD),         // Trade Depot
                new net.minecraft.item.ItemStack(net.minecraft.init.Items.FISHING_ROD),     // Fishing
                new net.minecraft.item.ItemStack(net.minecraft.init.Items.IRON_AXE),        // Lumber
                new net.minecraft.item.ItemStack(net.minecraft.init.Items.WHEAT),           // Farm
                new net.minecraft.item.ItemStack(net.minecraft.init.Items.IRON_PICKAXE)     // Mining
                // Hunting removed — it's a Strategic Mission now, not a district.
        };
    }

    /** Draw a real ITEM icon (10x10) centred at (cx,cy) with an optional allegiance outline. */
    private void drawItemIcon(net.minecraft.item.ItemStack stack, int cx, int cy, int outline) {
        if (stack == null || stack.isEmpty()) return;
        if (outline != 0) {
            Gui.drawRect(cx - 6, cy - 6, cx + 7, cy - 5, outline);
            Gui.drawRect(cx - 6, cy + 6, cx + 7, cy + 7, outline);
            Gui.drawRect(cx - 6, cy - 6, cx - 5, cy + 7, outline);
            Gui.drawRect(cx + 6, cy - 6, cx + 7, cy + 7, outline);
        }
        GlStateManager.pushMatrix();
        GlStateManager.translate(cx - 5, cy - 5, 0);
        GlStateManager.scale(0.625F, 0.625F, 1F);
        net.minecraft.client.renderer.RenderHelper.enableGUIStandardItemLighting();
        mc.getRenderItem().renderItemAndEffectIntoGUI(stack, 0, 0);
        net.minecraft.client.renderer.RenderHelper.disableStandardItemLighting();
        GlStateManager.popMatrix();
        GlStateManager.disableLighting();
        GlStateManager.color(1F, 1F, 1F, 1F);
    }

    /**
     * Draw every strategic object as a live ITEM icon: emerald = trader caravan (neutral yellow),
     * iron sword = patrol (rival red), sword/minecart = your incoming reinforcements (green). A green
     * corner dot marks objects physically materialized right now; squads show remaining strength.
     */
    private void drawStrategicMarkers() {
        initIconStacks();
        java.util.List<studio.ERM.war.map.net.S2CStrategicSync.Data> objs =
                studio.ERM.war.map.client.ClientStrategicCache.snapshot();
        if (objs.isEmpty()) return;
        for (studio.ERM.war.map.net.S2CStrategicSync.Data d : objs) {
            // Tab filter: CIVILIAN = the economy (traders/carts); MILITARY = military traffic +
            // reinforcement deliveries. New archetypes default to military.
            boolean civilian = "trader".equals(d.type);
            if (activeTab == 1 && !civilian) continue;
            if (activeTab == 2 && civilian) continue;
            int[] scr = worldToScreen(d.x, d.z);
            if (scr == null) continue;
            int sx = scr[0], sy = scr[1];
            if (sx < canvasLeft || sx >= canvasLeft + canvasSize) continue;
            if (sy < canvasTop || sy >= canvasTop + canvasSize) continue;

            net.minecraft.item.ItemStack icon;
            int outline;
            if ("trader".equals(d.type)) {
                icon = icTrader; outline = OUTLINE_NEUTRAL;
            } else if ("reinforcement".equals(d.type)) {
                icon = (d.label != null && d.label.startsWith("Vehicle")) ? icVehicle : icSquad;
                outline = OUTLINE_FRIENDLY;
            } else {
                icon = icPatrol; outline = OUTLINE_RIVAL;
            }
            drawItemIcon(icon, sx, sy, outline);

            if (d.live) Gui.drawRect(sx + 4, sy - 7, sx + 7, sy - 4, 0xFF00FF55); // green = physically live
            if (d.strength > 1) {
                fontRenderer.drawStringWithShadow(String.valueOf(d.strength), sx + 7, sy + 1, 0xFFFFFFFF);
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

        // FACING ARROW: a yellow line + tip pointing the way the player is looking (MC forward vector
        // = (-sin yaw, cos yaw); on the map +Z is screen-down, +X screen-right).
        double yaw = Math.toRadians(mc.player.rotationYaw);
        double dirX = -Math.sin(yaw), dirZ = Math.cos(yaw);
        int tx = sx + (int) Math.round(dirX * 12), ty = sy + (int) Math.round(dirZ * 12);
        drawMapLine(sx, sy, tx, ty, 0xFFFFDD00, 2.0F);
        Gui.drawRect(tx - 2, ty - 2, tx + 2, ty + 2, 0xFFFFDD00);

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

        // Zoom level ("0.5 bpp" at the new close-up level; whole numbers stay clean).
        double bppNow = getBlocksPerPixel();
        String bppLabel = (bppNow == Math.floor(bppNow)) ? String.valueOf((int) bppNow) : String.valueOf(bppNow);
        String zoomStr = TextFormatting.GRAY + "Zoom: " + TextFormatting.WHITE + bppLabel + " bpp";
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
        fontRenderer.drawStringWithShadow(TextFormatting.GRAY + "CB: " + TextFormatting.GOLD +
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
        sidebarEndY = sideY + 14; // where the chrome column ENDS -- the military panel anchors below this

        // CIVILIAN tab: labor + housing supply under the Legend (populated by S2CCivilPlanSync).
        if (activeTab == 1) {
            sideY += 22;
            fontRenderer.drawStringWithShadow(TextFormatting.GOLD + "" + TextFormatting.BOLD
                    + "Settlement", sideX, sideY, 0xFFFFFFFF);
            sideY += 13;
            int aw = studio.ERM.war.map.client.ClientCivilPlanCache.availWorkers();
            int tw = studio.ERM.war.map.client.ClientCivilPlanCache.totalWorkers();
            int ab = studio.ERM.war.map.client.ClientCivilPlanCache.availBeds();
            int tb = studio.ERM.war.map.client.ClientCivilPlanCache.totalBeds();
            fontRenderer.drawStringWithShadow(TextFormatting.GRAY + "Workers free: "
                    + (aw > 0 ? TextFormatting.GREEN : TextFormatting.YELLOW) + aw
                    + TextFormatting.GRAY + " / " + tw, sideX, sideY, 0xFFFFFFFF);
            sideY += 12;
            fontRenderer.drawStringWithShadow(TextFormatting.GRAY + "Beds free: "
                    + TextFormatting.AQUA + ab + TextFormatting.GRAY + " / " + tb, sideX, sideY, 0xFFFFFFFF);
            sideY += 12;
            int jobs = studio.ERM.war.map.client.ClientCivilPlanCache.jobs().size();
            fontRenderer.drawStringWithShadow(TextFormatting.GRAY + "Courier jobs: "
                    + (jobs > 0 ? TextFormatting.GOLD : TextFormatting.DARK_GRAY) + jobs,
                    sideX, sideY, 0xFFFFFFFF);
            sidebarEndY = sideY + 14;
        }

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
