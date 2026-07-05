package studio.ERM.war.BattleManagers.directors;

import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.text.TextComponentString;
import net.minecraft.util.text.TextFormatting;
import net.minecraft.world.World;
import net.minecraft.world.WorldServer;
import studio.ERM.EpochRunnerMod;
import studio.ERM.strategic.resource.ResourceNodeData;
import studio.ERM.war.BattleManagers.api.BattleOutcome;
import studio.ERM.war.BattleManagers.api.IBattleDirector;
import studio.ERM.war.BattleManagers.entities.EntitySoldier;
import studio.ERM.war.rival.RivalCityManager;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.UUID;

/**
 * PLAYER-CREATED OFFENSIVE BATTLE — the player takes the fight to the rival's forward presence.
 * NOT a siege: a short, sharp tactical assault (minutes, not an hour).
 *
 *   ATTACK RIVAL CAMP (outpostScale=false): must be deployed ON a rival extraction camp — the
 *   garrison turns out (level-scaled), workers scatter; wipe the defenders to CAPTURE the camp
 *   (ownership flips, production reroutes to the player's warehouse — no rebuilding).
 *
 *   ATTACK OUTPOST/CITY (outpostScale=true): deployable at any rival-held spot; a heavier
 *   defense with one mid-fight reinforcement wave; victory CLAIMS a small area (3x3 chunks) for
 *   the player — the territorial bite the design calls for.
 *
 * The RESOLVE BAR: defenders remaining is pushed to the attacker's action bar every few seconds —
 * enough units defeated on one side ends the battle.
 */
public class CampAssaultDirector implements IBattleDirector {

    private final boolean outpostScale;
    private static final Random RNG = new Random();

    private EntityPlayer activator;
    private BlockPos site;
    private ResourceNodeData.Node targetNode; // null for outpost assaults
    private final List<UUID> defenders = new ArrayList<>();
    private int initialDefenders = 0;
    private boolean reinforced = false;
    private int tickAge = 0;
    private boolean finished = false;
    private BattleOutcome outcome = BattleOutcome.ABORTED;

    private static final int MAX_DURATION_TICKS = 8 * 60 * 20; // 8 minutes, then the assault is called off

    public CampAssaultDirector(boolean outpostScale) { this.outpostScale = outpostScale; }

    @Override
    public void start(World world, EntityPlayer activator, BlockPos battleSite) {
        this.activator = activator;
        if (world.isRemote) return;
        WorldServer ws = (WorldServer) world;
        int y = Math.max(62, world.getTopSolidOrLiquidBlock(
                new BlockPos(battleSite.getX(), 64, battleSite.getZ())).getY());
        this.site = new BlockPos(battleSite.getX(), y, battleSite.getZ());
        int rivalLevel = Math.max(1, RivalCityManager.getRivalCityLevel());

        if (!outpostScale) {
            // CAMP ASSAULT needs an actual rival camp under the marker.
            ResourceNodeData nodes = ResourceNodeData.get(world);
            ResourceNodeData.Node best = null;
            double bd = 64.0 * 64.0;
            for (ResourceNodeData.Node n : nodes.nodes) {
                if (n.owner != ResourceNodeData.OWNER_RIVAL || !n.hasActiveCamp()) continue;
                double d = n.pos.distanceSq(site);
                if (d < bd) { bd = d; best = n; }
            }
            if (best == null) {
                activator.sendMessage(new TextComponentString(TextFormatting.RED
                        + "No rival camp here — deploy Attack Rival Camp ON a rival extraction camp."));
                forceResolve(BattleOutcome.ABORTED);
                return;
            }
            targetNode = best;
            this.site = new BlockPos(best.pos.getX(),
                    world.getTopSolidOrLiquidBlock(new BlockPos(best.pos.getX(), 64, best.pos.getZ())).getY(),
                    best.pos.getZ());
        }

        int count = outpostScale
                ? 10 + rivalLevel * 2
                : 4 + rivalLevel / 3 + (targetNode != null ? (targetNode.level - 1) * 3 + targetNode.level : 1);
        spawnDefenderRing(ws, count, rivalLevel);
        initialDefenders = defenders.size();
        if (initialDefenders == 0) { // nothing spawned (pathological terrain) — don't soft-lock
            forceResolve(BattleOutcome.VICTORY);
            grantReward(ws);
            return;
        }
        activator.sendMessage(new TextComponentString(TextFormatting.GOLD
                + (outpostScale ? "ASSAULT ON THE OUTPOST" : "ASSAULT ON THE " + targetNode.typeName().toUpperCase() + " CAMP")
                + TextFormatting.GRAY + " — defeat all " + initialDefenders + " defenders!"));
        EpochRunnerMod.logger.info("[Assault] started (" + (outpostScale ? "outpost" : "camp") + ", "
                + initialDefenders + " defenders) @ " + site.getX() + "," + site.getZ());
    }

    private void spawnDefenderRing(WorldServer world, int count, int rivalLevel) {
        for (int i = 0; i < count; i++) {
            double ang = Math.PI * 2 * i / count;
            double r = 6 + RNG.nextInt(8);
            int x = site.getX() + (int) (Math.cos(ang) * r);
            int z = site.getZ() + (int) (Math.sin(ang) * r);
            int y;
            try {
                y = world.getTopSolidOrLiquidBlock(new BlockPos(x, 64, z)).getY();
            } catch (Throwable t) { continue; }
            EntitySoldier s = new EntitySoldier(world);
            s.setLocationAndAngles(x + 0.5, y + 1.0, z + 0.5, RNG.nextFloat() * 360F, 0F);
            s.setTeam_("empire");
            s.configure(rivalLevel, (i % 2 == 0) ? "RANGED" : "MELEE", "");
            try { studio.ERM.war.skins.SkinPoolManager.applySkinForRivalLevel(s, rivalLevel, RNG); } catch (Throwable ignored) {}
            s.setMarchObjective(site); // hold the position; the march guard keeps them from roaming
            if (activator != null) s.setAttackTarget(activator);
            defenders.add(s.getUniqueID());
            world.spawnEntity(s);
        }
    }

    @Override
    public void tick(World world) {
        if (world.isRemote || finished) return;
        WorldServer ws = (WorldServer) world;
        tickAge++;
        if (activator == null || activator.isDead) { forceResolve(BattleOutcome.DEFEAT); return; }
        if (tickAge > MAX_DURATION_TICKS) { forceResolve(BattleOutcome.DEFEAT); cleanupDefenders(ws); return; }
        if (tickAge % 20 != 0) return;

        int alive = 0;
        for (UUID u : defenders) {
            net.minecraft.entity.Entity e = ws.getEntityFromUuid(u);
            if (e != null && !e.isDead) alive++;
        }

        // THE RESOLVE BAR: live defender count on the attacker's action bar.
        if (tickAge % 60 == 0 && activator instanceof net.minecraft.entity.player.EntityPlayerMP) {
            int pct = initialDefenders > 0 ? (100 * (initialDefenders - alive) / initialDefenders) : 100;
            ((net.minecraft.entity.player.EntityPlayerMP) activator).sendStatusMessage(
                    new TextComponentString(TextFormatting.RED + "Defenders " + alive + "/" + initialDefenders
                            + TextFormatting.GOLD + "  |  Assault " + pct + "%"), true);
        }

        // OUTPOST reinforcement wave at half strength — one counterpunch, then it's a race.
        if (outpostScale && !reinforced && alive > 0 && alive * 2 <= initialDefenders) {
            reinforced = true;
            int wave = Math.max(2, initialDefenders / 3);
            int before = defenders.size();
            spawnDefenderRing(ws, wave, Math.max(1, RivalCityManager.getRivalCityLevel()));
            initialDefenders += defenders.size() - before;
            if (activator != null) activator.sendMessage(new TextComponentString(
                    TextFormatting.RED + "Enemy reinforcements arrive!"));
        }

        if (alive == 0) {
            forceResolve(BattleOutcome.VICTORY);
            grantReward(ws);
        }
    }

    /** Victory spoils: camp assault FLIPS the camp; outpost assault CLAIMS 3x3 chunks for the player. */
    private void grantReward(WorldServer world) {
        try {
            if (targetNode != null) {
                ResourceNodeData data = ResourceNodeData.get(world);
                targetNode.owner = ResourceNodeData.OWNER_PLAYER;
                data.markDirty();
                if (activator != null) activator.sendMessage(new TextComponentString(
                        TextFormatting.GOLD + "Camp captured! " + TextFormatting.GRAY + "The "
                        + targetNode.typeName() + " camp works for you now — no rebuilding required."));
            } else {
                studio.ERM.war.world.WarWorldData war = studio.ERM.war.world.WarWorldData.get(world);
                net.minecraft.util.math.ChunkPos c = new net.minecraft.util.math.ChunkPos(site);
                int taken = 0;
                for (int dx = -1; dx <= 1; dx++) {
                    for (int dz = -1; dz <= 1; dz++) {
                        war.setOwner(new net.minecraft.util.math.ChunkPos(c.x + dx, c.z + dz), "PLAYER");
                        taken++;
                    }
                }
                if (activator != null) activator.sendMessage(new TextComponentString(
                        TextFormatting.GOLD + "Outpost taken! " + TextFormatting.GRAY
                        + taken + " chunks claimed for your banner."));
            }
        } catch (Throwable t) {
            EpochRunnerMod.logger.warn("[Assault] reward grant failed: " + t);
        }
    }

    private void cleanupDefenders(WorldServer world) {
        for (UUID u : defenders) {
            net.minecraft.entity.Entity e = world.getEntityFromUuid(u);
            if (e != null && !e.isDead) e.setDead();
        }
    }

    @Override public boolean isFinished() { return finished; }

    @Override
    public void stop(World world) {
        // Victory leaves the field as-won; a called-off assault (timeout/death) already despawned its
        // defenders in tick. Nothing else to unwind — no camp/terrain edits were made.
    }

    @Override public String getDirectorId() { return outpostScale ? "attack_outpost" : "attack_camp"; }
    public void forceResolve(BattleOutcome o) { this.outcome = o == null ? BattleOutcome.ABORTED : o; this.finished = true; }
    @Override public BattleOutcome getOutcome() { return outcome; }
}
