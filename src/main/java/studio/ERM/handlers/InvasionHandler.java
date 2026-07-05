package studio.ERM.handlers;

import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.init.Items;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.util.text.TextComponentString;
import net.minecraft.world.World;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import net.minecraftforge.fml.common.gameevent.TickEvent;
import net.minecraftforge.fml.common.gameevent.TickEvent.WorldTickEvent;
import net.minecraftforge.fml.relauncher.Side;
import net.minecraftforge.fml.relauncher.SideOnly;
import studio.ERM.EpochRunnerMod;
import studio.ERM.war.WarRaidSpawner;
import studio.ERM.war.config.WarLevelsConfig;
import studio.ERM.war.config.WarMasterConfig;

import java.util.Random;

/**
 * Invasion scheduler/runner.
 *
 * This is intentionally lightweight: invasions are implemented as a sequence of raid-like waves.
 *
 * Configuration is now split across the canonical configs:
 *  - WarMasterConfig: scheduling + sacrifice rules + sleep blocking
 *  - WarLevelsConfig: per-level wave counts + units per wave
 */
public class InvasionHandler {

    private static final Random RANDOM = new Random();

    private static boolean registered = false;

    private static boolean invasionActive = false;
    private static int wavesRemaining = 0;
    private static int waveCooldownTicks = 0;
    private static long nextInvasionWorldTime = -1L;

    /**
     * Call once from mod init.
     */
    public static void init() {
        if (!registered) {
            MinecraftForge.EVENT_BUS.register(new InvasionHandler());
            registered = true;
        }
    }

    public static boolean isInvasionActive() {
        return invasionActive;
    }

    public static boolean shouldBlockSleep() {
        return invasionActive && WarMasterConfig.get().invasion.blockSleepingDuringInvasion;
    }

    /**
     * Used by commands / debugging.
     */
    public static void forceStartInvasion(World world, EntityPlayer player) {
        if (world == null || world.isRemote) return;

        int level = resolveLevelForPlayer(player);
        startInvasion(world, player, level);
    }

    /**
     * Used by sacrifice system to postpone the next scheduled invasion.
     */
    public static void postponeNextInvasion(World world, int extraDays) {
        if (world == null || world.isRemote) return;

        long now = world.getWorldTime();
        long extra = Math.max(0L, (long) extraDays) * 24000L;
        if (nextInvasionWorldTime < 0L) nextInvasionWorldTime = now + extra;
        else nextInvasionWorldTime += extra;
    }

    // ======================================================================
    // TICK
    // ======================================================================

    @SubscribeEvent
    public void onWorldTick(WorldTickEvent event) {
        if (event.phase != TickEvent.Phase.END) return;

        World world = event.world;
        if (world == null || world.isRemote) return;

        // Initialize next schedule if not set
        if (nextInvasionWorldTime < 0L) {
            scheduleNext(world);
        }

        if (!invasionActive) {
            // Check schedule
            if (world.getWorldTime() >= nextInvasionWorldTime) {
                EntityPlayer target = world.getClosestPlayer(0.0D, 0.0D, 0.0D, 999999.0D, false);
                if (target != null) {
                    int level = resolveLevelForPlayer(target);
                    startInvasion(world, target, level);
                } else {
                    // No players loaded; push schedule forward a bit
                    nextInvasionWorldTime = world.getWorldTime() + 24000L;
                }
            }
            return;
        }

        // Invasion active: run waves
        if (wavesRemaining <= 0) {
            endInvasion(world);
            scheduleNext(world);
            return;
        }

        if (waveCooldownTicks > 0) {
            waveCooldownTicks--;
            return;
        }

        EntityPlayer target = world.getClosestPlayer(0.0D, 0.0D, 0.0D, 999999.0D, false);
        if (target == null) {
            // If no players are present, pause waves without ending the invasion.
            waveCooldownTicks = 20 * 10;
            return;
        }

        int level = resolveLevelForPlayer(target);
        spawnWave(world, target, level);
        wavesRemaining--;
        waveCooldownTicks = 20 * 30; // 30s between waves
    }

    // ======================================================================
    // CORE
    // ======================================================================

    private static void startInvasion(World world, EntityPlayer player, int level) {
        if (invasionActive) return;

        WarLevelsConfig.LevelData lvl = WarLevelsConfig.getLevel(level);
        if (lvl == null) return;

        int minWaves = Math.max(1, lvl.invasion.minWaves);
        int maxWaves = Math.max(minWaves, lvl.invasion.maxWaves);

        wavesRemaining = minWaves + RANDOM.nextInt((maxWaves - minWaves) + 1);
        waveCooldownTicks = 20 * 5; // 5s warmup

        invasionActive = true;

        if (player != null) {
            player.sendMessage(new TextComponentString("§cAn invasion begins. Waves incoming: " + wavesRemaining));
        }
        EpochRunnerMod.logger.info("[INVASION] Started invasion for level " + level + " with " + wavesRemaining + " waves.");
    }

    private static void endInvasion(World world) {
        invasionActive = false;
        wavesRemaining = 0;
        waveCooldownTicks = 0;

        EpochRunnerMod.logger.info("[INVASION] Ended invasion.");
    }

    private static void spawnWave(World world, EntityPlayer player, int level) {
        WarLevelsConfig.LevelData lvl = WarLevelsConfig.getLevel(level);
        if (lvl == null) return;

        int minUnits = Math.max(1, lvl.invasion.unitsPerWaveMin);
        int maxUnits = Math.max(minUnits, lvl.invasion.unitsPerWaveMax);
        int count = minUnits + RANDOM.nextInt((maxUnits - minUnits) + 1);

        // Reuse the raid spawner as the wave spawner.
        WarRaidSpawner.startRaid(world, player.getPosition(), level, count, player);

        if (player != null) {
            player.sendMessage(new TextComponentString("§6Invasion wave approaching (" + count + " units)."));
        }
    }

    private static void scheduleNext(World world) {
        int minDays = WarMasterConfig.get().invasion.minDaysBetweenInvasions;
        int maxDays = WarMasterConfig.get().invasion.maxDaysBetweenInvasions;
        if (maxDays < minDays) maxDays = minDays;

        int days = minDays;
        if (maxDays > minDays) days = minDays + RANDOM.nextInt((maxDays - minDays) + 1);

        nextInvasionWorldTime = world.getWorldTime() + (long) days * 24000L;

        EpochRunnerMod.logger.info("[INVASION] Next invasion scheduled in " + days + " days (worldTime=" + nextInvasionWorldTime + ").");
    }

    private static int resolveLevelForPlayer(EntityPlayer player) {
        // TODO: Replace this with your actual era/level progression system.
        // For now we estimate based on experience level, clamped to valid WarLevelsConfig levels.
        if (player == null) return 1;
        int est = Math.max(1, player.experienceLevel / 5 + 1);
        return Math.max(1, Math.min(WarLevelsConfig.getMaxDefinedLevel(), est));
    }

    // ======================================================================
    // COMMAND/UTILITY HOOKS
    // ======================================================================

    /**
     * Enqueue a simple narrator message for strike/invasion events.
     * This is kept as a utility so other systems can call it without
     * depending on any client-only HUD code.
     */
    public static void queueNarratorTask(EntityPlayer player, net.minecraft.util.math.BlockPos pos, int type, int durationTicks) {
        if (player == null) return;
        String msg;
        if (type == 2) msg = "§6Incoming!";
        else if (type == 3) msg = "§cHeavy incoming!";
        else msg = "§7Event triggered.";
        player.sendMessage(new TextComponentString(msg));
    }

    /**
     * Attempt to pay the configured sacrifice to postpone the next invasion.
     */
    public static void attemptSacrificeSkip(EntityPlayer player) {
        if (player == null) return;
        World world = player.world;
        if (world == null || world.isRemote) return;

        String id = WarMasterConfig.get().invasion.sacrificeItemId;
        int count = WarMasterConfig.get().invasion.sacrificeItemCount;
        if (id == null || id.trim().isEmpty() || count <= 0) {
            player.sendMessage(new TextComponentString("§cNo sacrifice item is configured."));
            return;
        }
        Item item = Item.getByNameOrId(id);
        if (item == null) {
            player.sendMessage(new TextComponentString("§cInvalid sacrifice item id: " + id));
            return;
        }

        int remaining = count;
        for (int i = 0; i < player.inventory.getSizeInventory() && remaining > 0; i++) {
            ItemStack stack = player.inventory.getStackInSlot(i);
            if (stack.isEmpty()) continue;
            if (stack.getItem() != item) continue;
            int take = Math.min(remaining, stack.getCount());
            stack.shrink(take);
            remaining -= take;
            if (stack.getCount() <= 0) player.inventory.setInventorySlotContents(i, ItemStack.EMPTY);
        }
        player.inventory.markDirty();
        if (remaining > 0) {
            player.sendMessage(new TextComponentString("§cYou need " + count + "x " + id + " to postpone the invasion."));
            return;
        }

        // Postpone by a fixed 1 day (configurable later if you want).
        postponeNextInvasion(world, 1);
        player.sendMessage(new TextComponentString("§aThe next invasion has been postponed."));
    }

    /** Force an invasion start on the overworld for the nearest player. */
    public static void forceInvasionStart() {
        World world = net.minecraftforge.fml.common.FMLCommonHandler.instance().getMinecraftServerInstance() != null
                ? net.minecraftforge.fml.common.FMLCommonHandler.instance().getMinecraftServerInstance().getWorld(0)
                : null;
        if (world == null) return;
        EntityPlayer player = world.getClosestPlayer(0.0D, 0.0D, 0.0D, 999999.0D, false);
        if (player == null) return;
        forceStartInvasion(world, player);
    }

    /** Force-cancel the current invasion and reschedule. */
    public static void forceInvasionSkip() {
        World world = net.minecraftforge.fml.common.FMLCommonHandler.instance().getMinecraftServerInstance() != null
                ? net.minecraftforge.fml.common.FMLCommonHandler.instance().getMinecraftServerInstance().getWorld(0)
                : null;
        if (world == null || world.isRemote) return;
        if (invasionActive) {
            endInvasion(world);
        }
        scheduleNext(world);
    }

    // ======================================================================
    // CLIENT HOOKS (optional)
    // ======================================================================

    @SideOnly(Side.CLIENT)
    public static ItemStack getSacrificeStack() {
        String id = WarMasterConfig.get().invasion.sacrificeItemId;
        int count = WarMasterConfig.get().invasion.sacrificeItemCount;

        Item item = Item.getByNameOrId(id);
        if (item == null) item = Items.DIAMOND;
        return new ItemStack(item, Math.max(1, count));
    }
}
