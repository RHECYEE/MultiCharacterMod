package studio.ERM.war.BattleManagers.core;

import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.text.TextComponentString;
import net.minecraft.world.BossInfo;
import net.minecraft.world.BossInfoServer;
import net.minecraft.world.World;
import net.minecraftforge.common.MinecraftForge;
import studio.ERM.EpochRunnerMod;
import studio.ERM.war.BattleManagers.api.BattleOutcome;
import studio.ERM.war.BattleManagers.api.BattleResolvedEvent;
import studio.ERM.war.BattleManagers.api.IBattleDirector;
import studio.ERM.war.BattleManagers.api.IPhasedBattleDirector;
import studio.ERM.war.BattleManagers.cards.UnitCard;
import studio.ERM.war.BattleManagers.cards.UnitCardRegistry;
import studio.ERM.war.BattleManagers.directors.DebugCircleDirector;
import studio.ERM.war.BattleManagers.directors.EntityBattleDirectorAnchor;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;

/**
 * World-scoped battle engine router.
 *
 * Not persisted. If the world unloads, all active state is lost (by design).
 *
 * This engine is ticked by a spawned EntityBattleDirectorAnchor during battles.
 */
public final class BattleEngine {

    private static final Map<World, BattleEngine> BY_WORLD = new WeakHashMap<>();

    public static BattleEngine get(World world) {
        if (world == null) return null;
        synchronized (BY_WORLD) {
            BattleEngine e = BY_WORLD.get(world);
            if (e == null) {
                e = new BattleEngine(world);
                BY_WORLD.put(world, e);
            }
            return e;
        }
    }

    private final World world;

    private IBattleDirector activeDirector;
    private EntityBattleDirectorAnchor anchor;
    private EntityPlayer activator;
    private BlockPos activeSite;

    // Bossbar UI (only shown for phased directors)
    private BossInfoServer bossbar;
    private final List<EntityPlayerMP> bossbarPlayers = new ArrayList<>();
    private int bossbarRetargetCooldown = 0;

    private BattleEngine(World world) {
        this.world = world;
    }

    public boolean hasActiveBattle() {
        return activeDirector != null;
    }

    public IBattleDirector getActiveDirector() {
        return activeDirector;
    }

    /**
     * Compatibility wrapper used by older call sites.
     * Starts DebugCircleDirector with card resolved by name.
     */
    public void startDebugCircleBattle(EntityPlayer player, BlockPos pos, String cardName) {
        if (world.isRemote) return;
        if (player == null || pos == null) return;
        if (activeDirector != null) return;

        UnitCard card = UnitCardRegistry.get(cardName);
        if (card == null) card = UnitCardRegistry.getDefault();
        IBattleDirector d = new DebugCircleDirector(card);
        startBattle(d, player, pos);
    }

    /**
     * Compatibility wrapper used by command debug tooling.
     * Spawns a formation carrier using the debug director but does not orbit (formation only).
     *
     * NOTE: Your existing director set does not currently include a "formation only" director.
     * We map it to debug circle (still valid for load + release testing) to keep builds green.
     */
    public void startDebugFormationOnly(EntityPlayer player, BlockPos pos, String cardName) {
        // For now this is equivalent to debug circle. If you want a pure "hold position" director,
        // we can add DebugFormationDirector later.
        startDebugCircleBattle(player, pos, cardName);
    }

    /**
     * Convenience helper used in earlier code paths.
     */
    public void startDebugCircle(EntityPlayer player, BlockPos pos) {
        startDebugCircleBattle(player, pos, "default");
    }

    public void startBattle(IBattleDirector director, EntityPlayer player, BlockPos battleSite) {
        if (world.isRemote) return;
        if (director == null || player == null || battleSite == null) return;

        if (activeDirector != null) {
            EpochRunnerMod.logger.warn("[BattleManagers] Tried to start battle while another is active.");
            return;
        }

        this.activeDirector = director;
        this.activator = player;
        this.activeSite = battleSite;

        ensureAnchor(battleSite);

        try {
            activeDirector.start(world, player, battleSite);
        } catch (Throwable t) {
            EpochRunnerMod.logger.error("[BattleManagers] Director start error: " + t.getMessage());
            try { activeDirector.stop(world); } catch (Throwable ignored) {}
            activeDirector = null;
            activator = null;
            activeSite = null;
            destroyBossbar();
            cleanupAnchorIfOrphaned();
            return;
        }

        ensureBossbarIfNeeded();

        EpochRunnerMod.logger.info("[BattleManagers] Battle started: " + safeDirectorId(activeDirector));
    }

    public void tick() {
        if (world.isRemote) return;

        if (activeDirector == null) {
            destroyBossbar();
            cleanupAnchorIfOrphaned();
            return;
        }

        try {
            activeDirector.tick(world);
        } catch (Throwable t) {
            EpochRunnerMod.logger.error("[BattleManagers] Director tick error: " + t.getMessage());
            try { activeDirector.stop(world); } catch (Throwable ignored) {}
            activeDirector = null;
            activator = null;
            activeSite = null;
            destroyBossbar();
            cleanupAnchorIfOrphaned();
            return;
        }

        updateBossbarIfNeeded();

        if (activeDirector.isFinished()) {
            // Every director reports its own resolved outcome via IBattleDirector.getOutcome().
            BattleOutcome outcome = activeDirector.getOutcome();

            // Emit resolved event here so map/manager systems can react.
            postResolvedEvent(outcome);

            stopBattleInternal();
        }
    }

    public void endBattle() {
        if (world.isRemote) return;
        stopBattleInternal();
        EpochRunnerMod.logger.info("[BattleManagers] Battle force-ended");
    }

    public void surrender(EntityPlayer player, BlockPos battleSite) {
        if (world.isRemote) return;
        if (activeDirector == null) return;

        // Post resolution and hard-stop the battle.
        MinecraftForge.EVENT_BUS.post(new BattleResolvedEvent(
                world,
                player,
                battleSite,
                safeDirectorId(activeDirector),
                BattleOutcome.SURRENDERED,
                0,
                0
        ));

        // For legacy debug directors, allow them to run their own cleanup hook.
        if (activeDirector instanceof DebugCircleDirector) {
            try {
                ((DebugCircleDirector) activeDirector).forceResolve(BattleOutcome.SURRENDERED);
            } catch (Throwable ignored) {
            }
        }

        stopBattleInternal();
    }

    private void postResolvedEvent(BattleOutcome outcome) {
        try {
            MinecraftForge.EVENT_BUS.post(new BattleResolvedEvent(
                    world,
                    activator,
                    activeSite,
                    safeDirectorId(activeDirector),
                    outcome == null ? BattleOutcome.ABORTED : outcome,
                    0,
                    0
            ));
        } catch (Throwable ignored) {}
    }

    private void stopBattleInternal() {
        if (world.isRemote) return;

        if (activeDirector != null) {
            try {
                activeDirector.stop(world);
            } catch (Throwable ignored) {}
            activeDirector = null;
        }

        activator = null;
        activeSite = null;

        destroyBossbar();
        cleanupAnchorIfOrphaned();
    }

    private void cleanupAnchorIfOrphaned() {
        if (anchor != null) {
            if (anchor.isDead) {
                anchor = null;
            } else if (activeDirector == null) {
                anchor.setDead();
                anchor = null;
            }
        }
    }

    private void ensureAnchor(BlockPos pos) {
        if (anchor != null && !anchor.isDead) return;

        anchor = new EntityBattleDirectorAnchor(world);
        anchor.setPosition(pos.getX() + 0.5, pos.getY() + 2.0, pos.getZ() + 0.5);
        anchor.bindToEngine();
        world.spawnEntity(anchor);

        EpochRunnerMod.logger.info("[BattleManagers] Anchor spawned for battle ticking.");
    }

    private void ensureBossbarIfNeeded() {
        if (activeDirector == null) {
            return;
        }
        if (bossbar != null) {
            return;
        }

        bossbar = new BossInfoServer(new TextComponentString("Battle"), BossInfo.Color.RED, BossInfo.Overlay.PROGRESS);
        bossbar.setVisible(true);
        bossbarPlayers.clear();
        bossbarRetargetCooldown = 0;
    }

    private void destroyBossbar() {
        if (bossbar == null) return;

        // Remove known players (BossInfoServer has no removeAllPlayers in 1.12)
        for (EntityPlayerMP p : bossbarPlayers) {
            try { bossbar.removePlayer(p); } catch (Throwable ignored) {}
        }
        bossbarPlayers.clear();

        try { bossbar.setVisible(false); } catch (Throwable ignored) {}
        bossbar = null;
    }

    private void updateBossbarIfNeeded() {
        if (bossbar == null) return;
        final IPhasedBattleDirector phased = (activeDirector instanceof IPhasedBattleDirector)
                ? (IPhasedBattleDirector) activeDirector
                : null;

        // Refresh membership periodically (players can join/leave range)
        bossbarRetargetCooldown++;
        if (bossbarRetargetCooldown >= 10) {
            bossbarRetargetCooldown = 0;

            for (EntityPlayerMP p : bossbarPlayers) {
                try { bossbar.removePlayer(p); } catch (Throwable ignored) {}
            }
            bossbarPlayers.clear();

            if (activator instanceof EntityPlayerMP) {
                bossbar.addPlayer((EntityPlayerMP) activator);
                bossbarPlayers.add((EntityPlayerMP) activator);
            }

            for (EntityPlayerMP p : world.getPlayers(EntityPlayerMP.class, x -> true)) {
                if (p == null || p.isDead) continue;
                if (activator != null && p.getUniqueID().equals(activator.getUniqueID())) continue;

                if (activeSite != null) {
                    double dx = p.posX - (activeSite.getX() + 0.5);
                    double dz = p.posZ - (activeSite.getZ() + 0.5);
                    double distSq = dx * dx + dz * dz;
                    if (distSq <= (256 * 256)) {
                        bossbar.addPlayer(p);
                        bossbarPlayers.add(p);
                    }
                }
            }
        }

        String label;
        if (phased != null) {
            label = safe(phased.getPhaseLabel());
            if (label.isEmpty()) {
                label = phased.getBattlePhase() == IPhasedBattleDirector.BattlePhase.DEPLOYING ? "Deploying" : "Combat";
            }
        } else {
            // Non-phased directors: always show a simple combat indicator.
            label = "Combat";
        }

        String siteName = "";
        if (activeSite != null) siteName = " @ " + activeSite.getX() + ", " + activeSite.getZ();

        bossbar.setName(new TextComponentString(label + siteName));

        float pct = 1.0F;
        if (phased != null && phased.getBattlePhase() == IPhasedBattleDirector.BattlePhase.DEPLOYING) {
            float p = phased.getDeployProgress();
            if (p < 0F) p = 0F;
            if (p > 1F) p = 1F;
            pct = p;
        }
        bossbar.setPercent(pct);
        bossbar.setVisible(true);
    }

    private static String safeDirectorId(IBattleDirector d) {
        try {
            if (d != null) return d.getDirectorId();
        } catch (Throwable ignored) {}
        return "unknown";
    }

    private static String safe(String s) {
        return s == null ? "" : s;
    }
}
