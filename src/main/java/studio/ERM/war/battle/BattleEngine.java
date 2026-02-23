package studio.ERM.war.battle;

import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;
import studio.ERM.EpochRunnerMod;
import studio.ERM.war.BattleManagers.api.BattleOutcome;
import studio.ERM.war.BattleManagers.api.BattleResolvedEvent;
import studio.ERM.war.BattleManagers.api.IBattleDirector;
import studio.ERM.war.BattleManagers.cards.UnitCard;
import studio.ERM.war.BattleManagers.cards.UnitCardRegistry;
import studio.ERM.war.BattleManagers.directors.DebugCircleDirector;
import studio.ERM.war.BattleManagers.directors.EntityBattleDirectorAnchor;
import studio.ERM.war.BattleManagers.entities.EntityFormationCarrier;
import net.minecraftforge.common.MinecraftForge;

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
        BattleEngine e = BY_WORLD.get(world);
        if (e == null) {
            e = new BattleEngine(world);
            BY_WORLD.put(world, e);
        }
        return e;
    }

    private final World world;

    private IBattleDirector activeDirector;
    private EntityBattleDirectorAnchor anchor;

    private BattleEngine(World world) {
        this.world = world;
    }

    public boolean hasActiveBattle() {
        return activeDirector != null && !activeDirector.isFinished();
    }

    /**
     * Returns the currently active battle director, or null if no battle is active.
     */
    public IBattleDirector getActiveDirector() {
        return activeDirector;
    }

    public void startDebugFormationOnly(EntityPlayer player, BlockPos pos, String unitCardName) {
        if (world.isRemote) return;

        UnitCard card = UnitCardRegistry.get(unitCardName);
        if (card == null) {
            player.sendMessage(new net.minecraft.util.text.TextComponentString("§cUnknown UnitCard: §f" + unitCardName));
            player.sendMessage(new net.minecraft.util.text.TextComponentString("§eAvailable: §f" + UnitCardRegistry.all().keySet()));
            return;
        }

        EntityFormationCarrier carrier = new EntityFormationCarrier(world);
        carrier.setPosition(pos.getX() + 0.5, pos.getY(), pos.getZ() + 0.5);
        carrier.configureFromCard(card, 1);
        world.spawnEntity(carrier);

        ensureAnchor(player, pos);
    }

    public void startDebugCircleBattle(EntityPlayer player, BlockPos center, String unitCardName) {
        if (world.isRemote) return;

        if (hasActiveBattle()) {
            player.sendMessage(new net.minecraft.util.text.TextComponentString("§cA battle is already active in this world."));
            return;
        }

        UnitCard card = UnitCardRegistry.get(unitCardName);
        if (card == null) {
            player.sendMessage(new net.minecraft.util.text.TextComponentString("§cUnknown UnitCard: §f" + unitCardName));
            player.sendMessage(new net.minecraft.util.text.TextComponentString("§eAvailable: §f" + UnitCardRegistry.all().keySet()));
            return;
        }

        DebugCircleDirector director = new DebugCircleDirector(card);
        this.activeDirector = director;

        ensureAnchor(player, center);
        director.start(world, player, center);
    }

    /**
     * Start a battle using any IBattleDirector.
     * This is the main entry point for deployed battles from the map GUI.
     * 
     * @param director The battle director to use
     * @param player The player activating the battle
     * @param battleSite The location where the battle takes place
     * @return true if battle started successfully
     */
    public boolean startBattle(IBattleDirector director, EntityPlayer player, BlockPos battleSite) {
        if (world.isRemote) return false;

        if (hasActiveBattle()) {
            player.sendMessage(new net.minecraft.util.text.TextComponentString("§cA battle is already active in this world."));
            return false;
        }

        if (director == null) {
            player.sendMessage(new net.minecraft.util.text.TextComponentString("§cInvalid battle director."));
            return false;
        }

        this.activeDirector = director;
        ensureAnchor(player, battleSite);
        director.start(world, player, battleSite);

        EpochRunnerMod.logger.info("[BattleManagers] Started battle: " + director.getDirectorId() + " at " + battleSite);
        return true;
    }

    public void tick() {
        if (world.isRemote) return;

        try {
            if (activeDirector != null) {
                activeDirector.tick(world);
                if (activeDirector.isFinished()) {
                    activeDirector.stop(world);
                    activeDirector = null;
                }
            }

            // If nothing is running and the anchor exists, remove it.
            if (activeDirector == null && anchor != null && !anchor.isDead) {
                anchor.setDead();
                anchor = null;
            }
        } catch (Throwable t) {
            EpochRunnerMod.logger.error("[BattleManagers] Engine tick error: " + t.getMessage());
            t.printStackTrace();

            // Hard abort
            if (activeDirector != null) {
                try {
                    activeDirector.stop(world);
                } catch (Throwable ignored) {}
                activeDirector = null;
            }
            if (anchor != null && !anchor.isDead) {
                anchor.setDead();
                anchor = null;
            }
        }
    }

    /**
     * Force ends the current battle
     */
    public void endBattle() {
        if (world.isRemote) return;
        
        if (activeDirector != null) {
            try {
                activeDirector.stop(world);
            } catch (Throwable ignored) {}
            activeDirector = null;
        }
        
        if (anchor != null && !anchor.isDead) {
            anchor.setDead();
            anchor = null;
        }
        
        EpochRunnerMod.logger.info("[BattleManagers] Battle force-ended");
    }

    public void surrender(EntityPlayer player, BlockPos battleSite) {
        if (world.isRemote) return;
        if (activeDirector == null) return;

        // Director implementations should interpret this by ending immediately.
        if (activeDirector instanceof DebugCircleDirector) {
            ((DebugCircleDirector) activeDirector).forceResolve(BattleOutcome.SURRENDERED);
        }

        MinecraftForge.EVENT_BUS.post(new BattleResolvedEvent(
                world,
                player,
                battleSite,
                activeDirector.getDirectorId(),
                BattleOutcome.SURRENDERED,
                0,
                0
        ));
    }

    private void ensureAnchor(EntityPlayer player, BlockPos pos) {
        if (anchor != null && !anchor.isDead) return;

        anchor = new EntityBattleDirectorAnchor(world);
        anchor.setPosition(pos.getX() + 0.5, pos.getY() + 2.0, pos.getZ() + 0.5);
        anchor.bindToEngine();
        world.spawnEntity(anchor);

        EpochRunnerMod.logger.info("[BattleManagers] Anchor spawned for debug/battle ticking.");
    }
}