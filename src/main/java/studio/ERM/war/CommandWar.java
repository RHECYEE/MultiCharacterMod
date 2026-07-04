package studio.ERM.war;

import net.minecraft.command.CommandBase;
import net.minecraft.command.CommandException;
import net.minecraft.command.ICommandSender;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.item.ItemStack;
import net.minecraft.server.MinecraftServer;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.ChunkPos;
import net.minecraft.util.math.Vec3d;
import net.minecraft.util.text.TextComponentString;
import net.minecraft.util.text.TextFormatting;
import net.minecraft.world.World;
import studio.ERM.EpochRunnerMod;
import studio.ERM.war.BattleManagers.cards.UnitCard;
import studio.ERM.war.BattleManagers.cards.UnitCardRegistry;
import studio.ERM.war.BattleManagers.core.BattleEngine;
import studio.ERM.war.BattleManagers.directors.SiegeDirector;
import studio.ERM.war.air.AirStrikeController;
import studio.ERM.war.battle.WarBattleSystem;
import studio.ERM.war.items.ItemAirTargetDesignator;
import studio.ERM.war.rival.RivalCityGenerator;
import studio.ERM.war.rival.RivalCityManager;
import studio.ERM.war.rival.RivalCitySpawner;
import studio.ERM.war.rival.RivalCityState;
import studio.ERM.war.vehicle.EntityAIPilot;
import studio.ERM.war.world.WarWorldData;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

/**
 * /war — operator/test command hub for the war system.
 *
 * Expanded from the minimal siege/debug stub to actually drive the systems that already exist
 * server-side but had no command front-end: Flan's-vehicle summoning, rival NPC/city spawning,
 * and the CP-gated territory claim system (which also feeds the tactical map's data packet).
 *
 * Subcommands:
 *   /war siege                  - start the 5-phase SiegeDirector battle at your position
 *   /war debug                  - start the DebugCircleDirector (carrier/puppet smoke test)
 *   /war stop                   - force-end the active battle
 *   /war status                 - print rival faction / battle / raid status
 *   /war airstrike [1-10]       - give yourself an Air Target Designator at the given strike level
 *   /war summon <vehicle> [n]   - spawn n enemy (RIVAL) Flan's vehicles with AI pilots in front of you
 *   /war rival guards [n] [lvl] - spawn n rival guards near you
 *   /war rival city [lvl]       - seed/generate a rival city at your position (heavy)
 *   /war rival status           - info on the nearest rival city
 *   /war claim [radius]         - claim chunks around you (costs CP); refreshes the map
 *   /war unclaim [radius]       - unclaim your chunks around you; refreshes the map
 *   /war cp <amount>            - grant yourself Command Points (so claims are affordable)
 *   /war sync                   - push the territory + stats snapshot to your client (map refresh)
 */
public class CommandWar extends CommandBase {

    @Override
    public String getName() { return "war"; }

    @Override
    public String getUsage(ICommandSender sender) {
        return "/war <siege|debug|stop|status|airstrike|summon|rival|claim|unclaim|cp|sync>";
    }

    @Override
    public int getRequiredPermissionLevel() { return 2; }

    @Override
    public List<String> getTabCompletions(MinecraftServer server, ICommandSender sender, String[] args, BlockPos pos) {
        if (args.length == 1) {
            return getListOfStringsMatchingLastWord(args,
                    "siege", "debug", "stop", "status", "airstrike",
                    "summon", "rival", "claim", "unclaim", "cp", "sync", "repair", "heat",
                    "chinook", "fastrope", "strat");
        }
        if (args.length == 2 && "rival".equalsIgnoreCase(args[0])) {
            return getListOfStringsMatchingLastWord(args, "guards", "city", "status");
        }
        if (args.length == 2 && "strat".equalsIgnoreCase(args[0])) {
            return getListOfStringsMatchingLastWord(args, "patrol", "trader", "list", "clear", "test", "testclear");
        }
        return java.util.Collections.emptyList();
    }

    @Override
    public void execute(MinecraftServer server, ICommandSender sender, String[] args) throws CommandException {
        if (args.length == 0) {
            help(sender);
            return;
        }

        String sub = args[0].toLowerCase(java.util.Locale.ROOT);
        switch (sub) {
            case "siege":   startSiege(sender);        break;
            case "debug":   startDebug(sender);        break;
            case "stop":    stop(sender);              break;
            case "status":  status(sender);            break;
            case "airstrike":
            case "designator": giveDesignator(sender, args); break;
            case "summon":  summonVehicle(sender, args); break;
            case "rival":   rival(sender, args);       break;
            case "claim":   claim(sender, args, true); break;
            case "unclaim": claim(sender, args, false); break;
            case "cp":      grantCp(sender, args);     break;
            case "sync":    syncMap(sender);           break;
            case "repair":  repair(sender, args);      break;
            case "heat":    heat(sender, args);        break;
            case "chinook": insertion(sender, "chinook");   break;
            case "strat":   strat(sender, args);            break;
            case "dismiss": {
                EntityPlayerMP p = getCommandSenderAsPlayer(sender);
                if (p.world instanceof net.minecraft.world.WorldServer) {
                    int n = studio.ERM.strategic.defense.DefensePlanExecutor
                            .dismissMercs((net.minecraft.world.WorldServer) p.world);
                    msg(sender, n > 0 ? TextFormatting.YELLOW + "Dismissed " + n + " mercenary(ies)."
                                      : TextFormatting.GRAY + "No mercenaries under contract.");
                }
                break;
            }
            case "evac": {
                EntityPlayerMP p = getCommandSenderAsPlayer(sender);
                if (p.world instanceof net.minecraft.world.WorldServer) {
                    net.minecraft.world.WorldServer ws = (net.minecraft.world.WorldServer) p.world;
                    boolean on = !(args.length >= 2 && (args[1].equalsIgnoreCase("off")
                            || args[1].equalsIgnoreCase("clear") || args[1].equalsIgnoreCase("end")));
                    studio.ERM.strategic.civil.evac.EvacuationManager.setEvacuating(ws, on);
                    if (on && studio.ERM.strategic.civil.evac.EvacuationData.get(ws).points.isEmpty()) {
                        msg(sender, TextFormatting.YELLOW
                                + "No Evacuation Points placed yet — civilians have nowhere to shelter.");
                    }
                }
                break;
            }
            case "fastrope":
            case "heli":
            case "insertion": insertion(sender, args.length >= 2 ? args[1] : "littlebird"); break;
            default:
                msg(sender, TextFormatting.RED + "Unknown subcommand: " + sub);
                help(sender);
        }
    }

    // ===== battle subcommands =====

    private void startSiege(ICommandSender sender) throws CommandException {
        EntityPlayerMP player = getCommandSenderAsPlayer(sender);
        World world = player.world;
        if (world.isRemote) return;

        BattleEngine engine = BattleEngine.get(world);
        if (engine == null) { msg(sender, TextFormatting.RED + "No battle engine for this world."); return; }
        if (engine.hasActiveBattle()) {
            msg(sender, TextFormatting.YELLOW + "A battle is already active. Use /war stop first.");
            return;
        }

        UnitCard card = UnitCardRegistry.get("ShieldWall");
        if (card == null) card = UnitCardRegistry.getDefault();
        if (card == null) { msg(sender, TextFormatting.RED + "No UnitCards registered; cannot start a siege."); return; }

        BlockPos pos = player.getPosition();
        engine.startBattle(new SiegeDirector(card), player, pos);

        if (engine.hasActiveBattle()) {
            msg(sender, TextFormatting.GREEN + "Siege started at " + posStr(pos) + " (watch the boss bar for phases).");
        } else {
            msg(sender, TextFormatting.RED + "Siege failed to start (see server log).");
        }
    }

    /**
     * /war repair [radius] — restore everything the antigrief system stored. Every block that war
     * damage replaced with a scaffold marker in claimed land is put back to its original state and
     * its repair order cleared. With no radius it repairs the WHOLE world's outstanding war damage;
     * with a radius it only repairs within that many blocks of you.
     */
    private void repair(ICommandSender sender, String[] args) throws CommandException {
        EntityPlayerMP player = getCommandSenderAsPlayer(sender);
        World world = player.world;
        if (world.isRemote) return;

        WarWorldData data = WarWorldData.get(world);
        if (data == null) { msg(sender, TextFormatting.RED + "No war data for this world."); return; }

        int radius = -1;
        if (args.length >= 2) {
            try { radius = Math.max(1, Integer.parseInt(args[1])); } catch (NumberFormatException ignored) {}
        }
        BlockPos center = player.getPosition();

        Map<BlockPos, WarWorldData.RepairOrder> repairMap = data.getRepairMap();
        if (repairMap.isEmpty()) { msg(sender, TextFormatting.YELLOW + "No war damage to repair."); return; }

        int restored = 0, skipped = 0;
        for (BlockPos pos : new ArrayList<>(repairMap.keySet())) {
            WarWorldData.RepairOrder order = repairMap.get(pos);
            if (order == null || order.originalState == null) { data.removeRepairOrder(pos); continue; }
            if (radius > 0 && center.getDistance(pos.getX(), pos.getY(), pos.getZ()) > radius) { skipped++; continue; }
            try {
                world.setBlockState(pos, order.originalState, 3);
                data.removeRepairOrder(pos);
                restored++;
            } catch (Throwable ignored) {}
        }

        msg(sender, TextFormatting.GREEN + "Repaired " + restored + " block(s)"
                + (radius > 0 ? " within " + radius + " blocks" : "")
                + (skipped > 0 ? TextFormatting.GRAY + " (" + skipped + " left out of range)" : "") + ".");
    }

    /**
     * /war heat [radiusChunks] — strategic heat-map prototype (the siege "brain" foundation). Scans
     * chunks around you, scores their tile entities + protector-stick blocks, classifies each chunk,
     * then reports the hottest base CLUSTER and its CORE (the real objective — often the storage /
     * machine room, not your bed). This is what the Siege Director will use to choose WHERE to strike.
     */
    private void heat(ICommandSender sender, String[] args) throws CommandException {
        EntityPlayerMP player = getCommandSenderAsPlayer(sender);
        World world = player.world;
        if (world.isRemote) return;

        int radius = 8;
        if (args.length >= 2) {
            try { radius = Math.max(1, Math.min(12, Integer.parseInt(args[1]))); } catch (NumberFormatException ignored) {}
        }

        // Run the EXACT targeting the SiegeDirector uses, so the board shows what the siege WILL hit.
        BlockPos here = player.getPosition();
        studio.ERM.war.strategy.SiegeTargeting.Result r =
                studio.ERM.war.strategy.SiegeTargeting.resolve(world, here, 256, 120, radius);

        BlockPos t = r.target;
        msg(sender, TextFormatting.GOLD + "=== Siege Targeting ("
                + r.scanned.size() + " chunks scanned, r=" + radius + ") ===");
        // REAL block coordinates -- not chunk coords.
        msg(sender, TextFormatting.GREEN + "TARGET: " + TextFormatting.WHITE
                + t.getX() + ", " + t.getY() + ", " + t.getZ()
                + TextFormatting.GRAY + "  (" + (int) Math.sqrt(here.distanceSq(t)) + " blocks away)");
        String why;
        switch (r.reason) {
            case "protected": why = "your PROTECTED blocks (" + r.protectedBlocks.size() + " found) -- strongest signal"; break;
            case "structure": why = "man-made STRUCTURE scan (no protected blocks nearby)"; break;
            case "heat":      why = "tile-entity HEAT core (no protected blocks / structure found)"; break;
            default:          why = "NO castle signal found -> falling back to where you stand"; break;
        }
        msg(sender, TextFormatting.YELLOW + "WHY: " + TextFormatting.WHITE + why);
        msg(sender, TextFormatting.GRAY + "Protected blocks near you: " + r.protectedBlocks.size()
                + "   |   Hottest chunk heat: " + (r.hottest != null ? (int) r.hottest.totalHeat() : 0));
        if (r.heatCore != null) {
            int hx = (r.heatCore.chunkX << 4) + 8, hz = (r.heatCore.chunkZ << 4) + 8;
            msg(sender, TextFormatting.GRAY + "Heat core would be at block " + hx + ", " + hz
                    + " (" + r.heatCore.classification + ", heat " + (int) r.heatCore.totalHeat() + ")");
        }
        if ("trigger".equals(r.reason)) {
            msg(sender, TextFormatting.RED + "No base detected -- protect-stick your walls or stand nearer the castle.");
        }
        msg(sender, TextFormatting.AQUA + "Showing the heat board for 30s: tall WHITE beam = target, "
                + "pink = your protected blocks, coloured bars = chunk heat by type.");

        // --- Build the visual board ---
        java.util.List<studio.ERM.war.strategy.WarHeatDebug.Marker> markers = new java.util.ArrayList<>();
        for (studio.ERM.war.strategy.StrategicChunk c : r.scanned) {
            if (c == null || c.totalHeat() < 30) continue; // skip cold clutter
            int bx = (c.chunkX << 4) + 8, bz = (c.chunkZ << 4) + 8;
            int by = studio.ERM.war.strategy.SiegeTargeting.surfaceY(world, bx, bz);
            float[] col = classColor(c.classification);
            int h = Math.min(12, 2 + (int) (c.totalHeat() / 80.0));
            markers.add(new studio.ERM.war.strategy.WarHeatDebug.Marker(new BlockPos(bx, by, bz), col[0], col[1], col[2], h));
        }
        // Protected blocks (sampled so we don't spam thousands of particles).
        int step = Math.max(1, r.protectedBlocks.size() / 80);
        for (int i = 0; i < r.protectedBlocks.size(); i += step) {
            BlockPos p = r.protectedBlocks.get(i);
            markers.add(new studio.ERM.war.strategy.WarHeatDebug.Marker(p, 1.0f, 0.3f, 0.6f, 2));
        }
        // The chosen TARGET: a tall bright white beacon.
        markers.add(new studio.ERM.war.strategy.WarHeatDebug.Marker(t, 1.0f, 1.0f, 1.0f, 28));
        studio.ERM.war.strategy.WarHeatDebug.show(world, markers, 30 * 20);
    }

    /** Map a chunk classification to an RGB particle colour for the heat board. */
    private static float[] classColor(studio.ERM.war.strategy.StrategicChunk.Classification cl) {
        switch (cl) {
            case CORE:      return new float[]{1.0f, 0.0f, 0.0f}; // red
            case STORAGE:   return new float[]{0.1f, 0.3f, 1.0f}; // blue
            case MACHINE:   return new float[]{1.0f, 0.5f, 0.0f}; // orange
            case POWER:     return new float[]{0.7f, 0.0f, 1.0f}; // purple
            case LIVING:    return new float[]{0.0f, 1.0f, 0.0f}; // green
            case DEFENSE:   return new float[]{1.0f, 1.0f, 0.0f}; // yellow
            case LOGISTICS: return new float[]{0.0f, 1.0f, 1.0f}; // cyan
            case PERIMETER: return new float[]{0.6f, 0.6f, 0.6f}; // grey
            default:        return new float[]{0.4f, 0.4f, 0.4f}; // faint
        }
    }

    private void startDebug(ICommandSender sender) throws CommandException {
        EntityPlayerMP player = getCommandSenderAsPlayer(sender);
        World world = player.world;
        if (world.isRemote) return;

        BattleEngine engine = BattleEngine.get(world);
        if (engine == null) { msg(sender, TextFormatting.RED + "No battle engine for this world."); return; }
        if (engine.hasActiveBattle()) {
            msg(sender, TextFormatting.YELLOW + "A battle is already active. Use /war stop first.");
            return;
        }

        engine.startDebugCircleBattle(player, player.getPosition(), "ShieldWall");
        msg(sender, TextFormatting.GREEN + "Debug circle battle started at " + posStr(player.getPosition()) + ".");
    }

    private void stop(ICommandSender sender) throws CommandException {
        World world = sender.getEntityWorld();
        if (world == null || world.isRemote) return;

        boolean was = BattleEngine.get(world) != null && BattleEngine.get(world).hasActiveBattle();
        WarBattleSystem.forceEndBattle(world);
        msg(sender, was ? TextFormatting.GREEN + "Battle force-ended."
                        : TextFormatting.GRAY + "No active battle to stop.");
    }

    private void status(ICommandSender sender) {
        World world = sender.getEntityWorld();
        if (world == null) return;

        String[] lines = WarSystemIntegration.getStatusLines(world);
        if (lines == null || lines.length == 0) {
            msg(sender, TextFormatting.GRAY + "No war activity in this dimension yet.");
            return;
        }
        for (String line : lines) {
            sender.sendMessage(new TextComponentString(line));
        }
        BattleEngine engine = BattleEngine.get(world);
        if (engine != null && engine.hasActiveBattle()) {
            msg(sender, TextFormatting.GOLD + "Battle engine: ACTIVE");
        }
    }

    private void giveDesignator(ICommandSender sender, String[] args) throws CommandException {
        EntityPlayerMP player = getCommandSenderAsPlayer(sender);
        if (EpochRunnerMod.air_target_designator == null) {
            msg(sender, TextFormatting.RED + "Air Target Designator item is not registered.");
            return;
        }

        int level = 1;
        if (args.length >= 2) {
            level = parseInt(args[1], 1, 10);
        }

        ItemStack stack = new ItemStack(EpochRunnerMod.air_target_designator);
        ItemAirTargetDesignator.setStrikeLevel(stack, level);
        if (!player.inventory.addItemStackToInventory(stack)) {
            player.dropItem(stack, false);
        }
        ItemAirTargetDesignator.StrikePackage pkg = ItemAirTargetDesignator.StrikePackage.fromLevel(level);
        msg(sender, TextFormatting.GREEN + "Gave Air Target Designator — level " + level + " (" + pkg.name + ").");
    }

    // ===== /war chinook | fastrope [heliType] — test the helicopter insertion / fast-rope =====

    /**
     * Dispatch a hostile transport helicopter that flies in and fast-ropes a KSK troop payload at a drop
     * point ~12 blocks in front of you, so the heli insertion mechanic can be tested on demand.
     * {@code /war chinook} forces a Chinook (heavy lift); {@code /war fastrope [type]} lets you pick the
     * transport (default LittleBird). Level scales with the nearest rival city.
     */
    private void insertion(ICommandSender sender, String heliType) throws CommandException {
        EntityPlayerMP player = getCommandSenderAsPlayer(sender);
        World world = player.world;
        if (world.isRemote) return;

        int level = Math.max(1, RivalCityManager.getRivalCityLevel());
        Vec3d look = player.getLookVec();
        BlockPos drop = new BlockPos(player.posX + look.x * 12.0, player.posY, player.posZ + look.z * 12.0);

        boolean ok = AirStrikeController.launchInsertion(world, drop, level, heliType);
        if (ok) {
            msg(sender, TextFormatting.GREEN + "Inbound " + heliType + " insertion (L" + level
                    + ") — fast-roping at " + posStr(drop) + ". Watch it run in and drop troops.");
        } else {
            msg(sender, TextFormatting.RED + "Insertion failed (see server log; is '" + heliType
                    + "' a loaded Flan helicopter ShortName?).");
        }
    }

    // ===== /war strat — PHASE 2 strategic-simulation debug =====

    /**
     * /war strat patrol [count] [level] — register a strategic PATROL that circuits a ring of waypoints
     * around you. It exists on the strategic map (moving by pure math while unloaded), MATERIALIZES as a
     * marching soldier column when you're within ~100 blocks, and dematerializes back to the map when you
     * leave (~140). Walk away and come back to watch the whole loop.
     * /war strat list  — print every strategic object (type, position, strength, loaded state).
     * /war strat clear — remove all strategic objects (despawning any live entities).
     */
    private void strat(ICommandSender sender, String[] args) throws CommandException {
        EntityPlayerMP player = getCommandSenderAsPlayer(sender);
        World world = player.world;
        if (world.isRemote) return;

        studio.ERM.strategic.StrategicMapData data = studio.ERM.strategic.StrategicMapData.get(world);
        String op = (args.length >= 2) ? args[1].toLowerCase(java.util.Locale.ROOT) : "list";
        switch (op) {
            case "patrol": {
                int count = (args.length >= 3) ? parseInt(args[2], 1, 12) : 4;
                int level = (args.length >= 4) ? parseInt(args[3], 1, 10) : 3;
                studio.ERM.strategic.StrategicPatrol p = new studio.ERM.strategic.StrategicPatrol();
                p.strength = count;
                p.warLevel = level;
                // An octagonal circuit around where you stand, radius ~56 -- big enough that the far side
                // is beyond dematerialize range, so walking the ring shows both transitions.
                int r = 56;
                for (int i = 0; i < 8; i++) {
                    double a = i * (Math.PI * 2 / 8);
                    p.route.add(new BlockPos(
                            player.getPosition().getX() + (int) Math.round(Math.cos(a) * r), 0,
                            player.getPosition().getZ() + (int) Math.round(Math.sin(a) * r)));
                }
                BlockPos start = p.route.get(0);
                p.x = start.getX() + 0.5;
                p.z = start.getZ() + 0.5;
                data.add(p);
                msg(sender, TextFormatting.GREEN + "Strategic patrol registered: " + count + " soldiers (L"
                        + level + "), 8-point circuit r=" + r + " around you.");
                msg(sender, TextFormatting.GRAY + "It materializes within ~100 blocks and dematerializes "
                        + "beyond ~140 — walk away and return to watch the loop. Watch [Strategic] chat lines.");
                break;
            }
            case "trader": {
                int level = (args.length >= 3) ? parseInt(args[2], 1, 10) : 3;
                studio.ERM.strategic.StrategicTrader t = new studio.ERM.strategic.StrategicTrader();
                t.level = level;
                t.escorts = (level >= 3) ? 2 : 0;
                // A trade route that passes RIGHT BY you: near point ~35 blocks one side, far point ~150
                // the other -- so you can watch it materialize, walk past with the cart, and ping-pong.
                double a = Math.toRadians(player.rotationYaw + 90.0);
                BlockPos near = new BlockPos(player.getPosition().getX() + (int) Math.round(Math.cos(a) * 35), 0,
                        player.getPosition().getZ() + (int) Math.round(Math.sin(a) * 35));
                BlockPos far = new BlockPos(player.getPosition().getX() - (int) Math.round(Math.cos(a) * 150), 0,
                        player.getPosition().getZ() - (int) Math.round(Math.sin(a) * 150));
                t.route.add(near);
                t.route.add(far);
                t.x = near.getX() + 0.5;
                t.z = near.getZ() + 0.5;
                data.add(t);
                msg(sender, TextFormatting.GREEN + "Strategic trader caravan registered (L" + level
                        + (t.escorts > 0 ? ", 2 escorts" : "") + ") — trade route ping-ponging past you.");
                msg(sender, TextFormatting.GRAY + "Merchant + chest cart (config traffic.cartEntityId to swap "
                        + "the cart entity). Kill the merchant and the route dies.");
                break;
            }
            case "test": {
                // MINIMAL FORCED-MOVEMENT PROBE (debug protocol): nearest AW2 npc within 30 blocks gets
                // the order task injected + a locked order to walk to a point ~6 blocks ahead of you,
                // bypassing the whole plan pipeline. If it walks, the hijack core works and the bug is
                // in slots/assignment/cleanup; if not, injection/uuid/mutex/navigator is broken.
                if (!(world instanceof net.minecraft.world.WorldServer)) break;
                net.minecraft.world.WorldServer ws = (net.minecraft.world.WorldServer) world;
                net.minecraft.entity.EntityCreature nearest = null;
                double bd = 30 * 30;
                for (net.minecraft.entity.EntityCreature c : world.getEntitiesWithinAABB(
                        net.minecraft.entity.EntityCreature.class,
                        player.getEntityBoundingBox().grow(30))) {
                    if (c == null || c.isDead) continue;
                    boolean aw2 = studio.ERM.strategic.defense.Aw2Npc.isAw2Npc(c)
                            || String.valueOf(net.minecraft.entity.EntityList.getKey(c)).contains("ancientwarfare");
                    if (!aw2) continue;
                    double d = player.getDistanceSq(c);
                    if (d < bd) { bd = d; nearest = c; }
                }
                if (nearest == null) {
                    msg(sender, TextFormatting.RED + "No AW2 npc within 30 blocks. "
                            + "Classifier: " + (studio.ERM.strategic.defense.Aw2Npc.isAw2Npc(player) ? "?" : "loaded (see log)"));
                    break;
                }
                Vec3d look = player.getLookVec();
                BlockPos dest = new BlockPos(player.posX + look.x * 6.0, player.posY, player.posZ + look.z * 6.0);
                studio.ERM.strategic.defense.DefensePlanExecutor.debugOrder(ws, nearest, dest);
                msg(sender, TextFormatting.GREEN + "Forced-movement probe: " + nearest.getName()
                        + " ordered to " + posStr(dest) + ". Watch it walk + the [DefenseAI] log.");
                msg(sender, TextFormatting.GRAY + studio.ERM.strategic.defense.Aw2Npc.describe(nearest));
                break;
            }
            case "testclear": {
                int n = studio.ERM.strategic.defense.DefensePlanExecutor.clearDebugOrders();
                msg(sender, TextFormatting.GREEN + "Cleared " + n + " debug order(s).");
                break;
            }
            case "clear": {
                int n = data.objects.size();
                for (studio.ERM.strategic.StrategicObject o : new java.util.ArrayList<>(data.objects.values())) {
                    if (o.materialized && world instanceof net.minecraft.world.WorldServer) {
                        try { o.dematerialize((net.minecraft.world.WorldServer) world); } catch (Throwable ignored) {}
                    }
                    data.remove(o.id);
                }
                msg(sender, TextFormatting.GREEN + "Cleared " + n + " strategic object(s).");
                break;
            }
            case "list":
            default: {
                if (data.objects.isEmpty()) {
                    msg(sender, TextFormatting.GRAY + "No strategic objects. Use /war strat patrol to add one.");
                    break;
                }
                msg(sender, TextFormatting.GOLD + "=== Strategic Map (" + data.objects.size() + ") ===");
                for (studio.ERM.strategic.StrategicObject o : data.objects.values()) {
                    int dist = (int) Math.hypot(o.x - player.posX, o.z - player.posZ);
                    msg(sender, TextFormatting.YELLOW + o.label() + TextFormatting.WHITE
                            + " @ " + (int) o.x + ", " + (int) o.z
                            + TextFormatting.GRAY + "  (" + dist + "m away, "
                            + (o.materialized ? TextFormatting.AQUA + "LIVE" : TextFormatting.DARK_GRAY + "on map")
                            + TextFormatting.GRAY + ", wp " + (o.routeIndex + 1) + "/" + o.route.size() + ")");
                }
                break;
            }
        }
    }

    // ===== /war summon <vehicle> [count] =====

    private void summonVehicle(ICommandSender sender, String[] args) throws CommandException {
        EntityPlayerMP player = getCommandSenderAsPlayer(sender);
        World world = player.world;
        if (world.isRemote) return;

        if (args.length < 2) {
            msg(sender, TextFormatting.RED + "Usage: /war summon <flansVehicleShortName> [count]");
            msg(sender, TextFormatting.GRAY + "Example: /war summon abrams 2");
            return;
        }

        String vehicle = args[1];
        int count = (args.length >= 3) ? parseInt(args[2], 1, 10) : 1;

        // Spawn a few blocks in front of the player so the (large) vehicle has room.
        Vec3d look = player.getLookVec();
        double baseX = player.posX + look.x * 5.0;
        double baseZ = player.posZ + look.z * 5.0;

        int spawned = 0;
        for (int i = 0; i < count; i++) {
            double x = baseX + (i % 3) * 3.0;
            double z = baseZ + (i / 3) * 3.0;
            try {
                EntityAIPilot pilot = new EntityAIPilot(world);
                pilot.setPosition(x, player.posY, z);
                // RIVAL team => hostile to the player; the pilot builds + mounts the vehicle on its
                // first tick via spawnAndMountVehicle(), independent of the remount AI.
                pilot.setMcmTeam(RivalCityState.RIVAL_FACTION_NAME);
                pilot.setVehicleType(vehicle);
                world.spawnEntity(pilot);
                spawned++;
            } catch (Throwable t) {
                EpochRunnerMod.logger.error("[/war summon] failed: " + t.getMessage());
            }
        }

        if (spawned > 0) {
            msg(sender, TextFormatting.GREEN + "Summoning " + spawned + "x enemy '" + vehicle
                    + "'. If nothing appears, that Flan's vehicle shortName isn't loaded.");
        } else {
            msg(sender, TextFormatting.RED + "Failed to summon '" + vehicle + "' (see server log).");
        }
    }

    // ===== /war rival ... =====

    private void rival(ICommandSender sender, String[] args) throws CommandException {
        EntityPlayerMP player = getCommandSenderAsPlayer(sender);
        World world = player.world;
        if (world.isRemote) return;

        String op = (args.length >= 2) ? args[1].toLowerCase(java.util.Locale.ROOT) : "status";
        switch (op) {
            case "guards":
            case "guard": {
                int count = (args.length >= 3) ? parseInt(args[2], 1, 20) : 4;
                int level = (args.length >= 4) ? parseInt(args[3], 1, 10) : nearestRivalLevel(world, player.getPosition(), 3);
                BlockPos at = player.getPosition();
                int spawned = 0;
                for (int i = 0; i < count; i++) {
                    BlockPos p = at.add((i % 4) - 2, 0, (i / 4) - 2);
                    try { RivalCitySpawner.spawnModernGuard(world, p, level); spawned++; }
                    catch (Throwable t) { EpochRunnerMod.logger.error("[/war rival guards] " + t.getMessage()); }
                }
                msg(sender, TextFormatting.GREEN + "Spawned " + spawned + " rival guard(s) (L" + level + ").");
                break;
            }
            case "city": {
                // /war rival city level [add|<n>] — adjust the nearest rival city's level in place.
                if (args.length >= 3 && "level".equalsIgnoreCase(args[2])) {
                    RivalCityState near = RivalCityManager.getNearestCity(world, player.getPosition());
                    if (near == null) {
                        msg(sender, TextFormatting.RED + "No rival city yet — use /war rival city first.");
                        break;
                    }
                    int cur = Math.max(1, near.level);
                    int target = (args.length >= 4 && !"add".equalsIgnoreCase(args[3]))
                            ? parseInt(args[3], 1, 10) : Math.min(10, cur + 1);
                    try { RivalCityManager.setLevel(world, player, target); }
                    catch (Throwable t) { EpochRunnerMod.logger.error("[/war rival city level] failed", t); }
                    msg(sender, TextFormatting.GREEN + "Rival level " + cur + " -> " + target
                            + " (nations advance to their towns at level 2).");
                    break;
                }
                // A fresh seed defaults to LEVEL 1 (was 3 — the bug that spawned nations at L3 and
                // triggered their tier-2 town build immediately).
                int level = (args.length >= 3) ? parseInt(args[2], 1, 10) : 1;
                try {
                    // Delegate to the Rival City module: offsets the city to a believable
                    // distance (not the player's feet) AND registers RIVAL chunk ownership
                    // so the settlement shows up as red territory on the tactical war map.
                    BlockPos center = RivalCityManager.seedCityAtDistance(world, player, level);
                    if (center == null) {
                        msg(sender, TextFormatting.RED + "Rival city generation failed (busy or invalid world).");
                        break;
                    }
                    int dist = (int) Math.sqrt(center.distanceSq(player.getPosition()));
                    msg(sender, TextFormatting.GREEN + "Seeded rival city L" + level + " at " + posStr(center)
                            + TextFormatting.YELLOW + " (" + dist + "m away)" + TextFormatting.GREEN
                            + ". Its claimed land now shows on the war map.");
                    // The world gets POPULATED with the rival: nation states ring the player too
                    // (tribal camps now; their one-shot permanent towns arrive at rival level 2).
                    try {
                        studio.ERM.strategic.nation.NationStateManager.spawnRing(
                                (net.minecraft.world.WorldServer) world, player, center);
                    } catch (Throwable t) {
                        EpochRunnerMod.logger.error("[/war rival city] nation ring failed", t);
                    }
                } catch (Throwable t) {
                    msg(sender, TextFormatting.RED + "Rival city generation failed: " + t.getMessage());
                    EpochRunnerMod.logger.error("[/war rival city] failed", t);
                }
                break;
            }
            case "tp": {
                RivalCityState near = RivalCityManager.getNearestCity(world, player.getPosition());
                if (near == null || near.center == null) {
                    msg(sender, TextFormatting.GRAY + "No rival city to teleport to. Use /war rival city first.");
                    break;
                }
                BlockPos c = near.center;
                player.setPositionAndUpdate(c.getX() + 0.5, c.getY() + 1.0, c.getZ() + 0.5);
                msg(sender, TextFormatting.GREEN + "Teleported to rival city L" + near.level + " @ " + posStr(c) + ".");
                break;
            }
            case "status":
            default: {
                RivalCityState near = RivalCityManager.getNearestCity(world, player.getPosition());
                if (near == null || near.center == null) {
                    msg(sender, TextFormatting.GRAY + "No rival city in this dimension. Use /war rival city to seed one.");
                } else {
                    msg(sender, TextFormatting.GOLD + "Nearest rival city: " + TextFormatting.WHITE
                            + "L" + near.level + " @ " + posStr(near.center)
                            + " (" + (int) Math.sqrt(near.center.distanceSq(player.getPosition())) + "m away)");
                }
                break;
            }
        }
    }

    private static int nearestRivalLevel(World world, BlockPos pos, int fallback) {
        try {
            RivalCityState near = RivalCityManager.getNearestCity(world, pos);
            if (near != null) return Math.max(1, near.level);
        } catch (Throwable ignored) {}
        return fallback;
    }

    // ===== /war claim | unclaim [radius] =====

    private void claim(ICommandSender sender, String[] args, boolean doClaim) throws CommandException {
        EntityPlayerMP player = getCommandSenderAsPlayer(sender);
        World world = player.world;
        if (world.isRemote) return;

        int radius = (args.length >= 2) ? parseInt(args[1], 0, 8) : 0;
        ChunkPos center = new ChunkPos(player.getPosition());
        List<ChunkPos> chunks = new ArrayList<>();
        for (int dx = -radius; dx <= radius; dx++) {
            for (int dz = -radius; dz <= radius; dz++) {
                chunks.add(new ChunkPos(center.x + dx, center.z + dz));
            }
        }

        if (doClaim) {
            WarClaimHandler.BatchClaimResult r = WarClaimHandler.batchClaim(player, chunks);
            if (r.claimed > 0) {
                msg(sender, TextFormatting.GREEN + "Claimed " + r.claimed + " chunk(s) for " + r.totalCost + " CP.");
            }
            if (r.failed > 0) {
                msg(sender, TextFormatting.YELLOW + "" + r.failed + " chunk(s) failed (rival-owned, taken, or not enough CP — try /war cp 1000).");
            }
            if (r.claimed == 0 && r.failed == 0) {
                msg(sender, TextFormatting.GRAY + "Nothing to claim (already yours).");
            }
        } else {
            int n = WarClaimHandler.batchUnclaim(player, chunks);
            msg(sender, (n > 0 ? TextFormatting.GREEN + "Unclaimed " + n + " chunk(s)."
                               : TextFormatting.GRAY + "No owned chunks here to unclaim."));
        }

        // Push the updated ownership + stats to the client so the tactical map reflects it.
        WarClaimHandler.syncTerritoryToPlayer(player);
    }

    // ===== /war cp <amount> =====

    private void grantCp(ICommandSender sender, String[] args) throws CommandException {
        EntityPlayerMP player = getCommandSenderAsPlayer(sender);
        if (args.length < 2) { msg(sender, TextFormatting.RED + "Usage: /war cp <amount>"); return; }
        int amount = parseInt(args[1]);

        WarWorldData data = WarWorldData.get(player.world);
        if (data == null) { msg(sender, TextFormatting.RED + "No war data for this world."); return; }
        WarWorldData.FactionStats stats = data.getStats(player.getUniqueID().toString());
        stats.commandPoints += amount;
        data.markDirty();

        msg(sender, TextFormatting.GREEN + "Command Points " + (amount >= 0 ? "+" : "") + amount
                + " (now " + stats.commandPoints + ").");
        WarClaimHandler.syncTerritoryToPlayer(player);
    }

    // ===== /war sync =====

    private void syncMap(ICommandSender sender) throws CommandException {
        EntityPlayerMP player = getCommandSenderAsPlayer(sender);
        WarClaimHandler.syncTerritoryToPlayer(player);
        msg(sender, TextFormatting.GREEN + "Pushed territory + stats snapshot to your client.");
    }

    // ===== help =====

    private void help(ICommandSender sender) {
        msg(sender, TextFormatting.GOLD + "=== /war ===");
        for (String line : Arrays.asList(
                TextFormatting.YELLOW + "/war siege" + TextFormatting.GRAY + " - start the 5-phase siege at your position",
                TextFormatting.YELLOW + "/war debug" + TextFormatting.GRAY + " - start the debug circle battle",
                TextFormatting.YELLOW + "/war stop" + TextFormatting.GRAY + " - force-end the active battle",
                TextFormatting.YELLOW + "/war status" + TextFormatting.GRAY + " - show faction/battle/raid status",
                TextFormatting.YELLOW + "/war airstrike [1-10]" + TextFormatting.GRAY + " - give yourself an Air Target Designator",
                TextFormatting.YELLOW + "/war chinook" + TextFormatting.GRAY + " - call a Chinook insertion (fast-rope) in front of you",
                TextFormatting.YELLOW + "/war strat patrol [n] [lvl]" + TextFormatting.GRAY + " - register a strategic patrol circuit (Phase 2 demo)",
                TextFormatting.YELLOW + "/war strat list|clear" + TextFormatting.GRAY + " - inspect / wipe the strategic map",
                TextFormatting.YELLOW + "/war fastrope [heli]" + TextFormatting.GRAY + " - test a heli fast-rope (default LittleBird)",
                TextFormatting.YELLOW + "/war summon <vehicle> [n]" + TextFormatting.GRAY + " - spawn enemy Flan's vehicles",
                TextFormatting.YELLOW + "/war rival guards [n] [lvl]" + TextFormatting.GRAY + " - spawn rival guards",
                TextFormatting.YELLOW + "/war rival city [lvl]" + TextFormatting.GRAY + " - seed a rival city here (heavy)",
                TextFormatting.YELLOW + "/war rival status" + TextFormatting.GRAY + " - nearest rival city info",
                TextFormatting.YELLOW + "/war claim [radius]" + TextFormatting.GRAY + " - claim chunks (costs CP)",
                TextFormatting.YELLOW + "/war unclaim [radius]" + TextFormatting.GRAY + " - unclaim your chunks",
                TextFormatting.YELLOW + "/war cp <amount>" + TextFormatting.GRAY + " - grant Command Points",
                TextFormatting.YELLOW + "/war sync" + TextFormatting.GRAY + " - refresh the tactical map data")) {
            sender.sendMessage(new TextComponentString(line));
        }
    }

    // ===== helpers =====

    private static void msg(ICommandSender sender, String text) {
        sender.sendMessage(new TextComponentString(text));
    }

    private static String posStr(BlockPos p) {
        return p.getX() + ", " + p.getY() + ", " + p.getZ();
    }
}
