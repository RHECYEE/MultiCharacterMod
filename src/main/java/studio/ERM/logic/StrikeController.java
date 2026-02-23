package studio.ERM.logic;

import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.text.TextComponentString;
import net.minecraft.util.text.TextFormatting;
import studio.ERM.war.AirDefenseHandler;
import studio.ERM.handlers.InvasionHandler;
import java.util.Random;

public class StrikeController {
    private static final Random rand = new Random();

    private static final String[] PHASE_1 = {
            "Early warning systems have detected unusual launch activity.",
            "Radar contact established. Trajectory analysis underway.",
            "Attention: Seismic anomalies detected in your sector."
    };

    private static final String[] PHASE_2 = {
            "CONFIRMED: Ballistic missiles inbound. Take cover immediately.",
            "Calculated impact in T-minus 5 seconds.",
            "Air defense grid is engaging. Brace for impact."
    };

    private static final String[] PHASE_3C_IMPACT = {
            "Impact confirmed. Structure integrity critical.",
            "Detonation detected. Radiological hazard present.",
            "Strike successful. Assessing fallout levels."
    };

    public static void runNuclearSequence(EntityPlayer player, BlockPos invasionPos) {
        // Start with Phase 1
        executePhase(player, invasionPos, 1);
    }

    public static void executePhase(EntityPlayer player, BlockPos pos, int phase) {
        switch (phase) {
            case 1:
                narrate(player, TextFormatting.YELLOW + PHASE_1[rand.nextInt(PHASE_1.length)]);
                // Wait 100 ticks (5 seconds), then trigger Phase 2
                InvasionHandler.queueNarratorTask(player, pos, 2, 100);
                break;

            case 2:
                narrate(player, TextFormatting.GOLD + PHASE_2[rand.nextInt(PHASE_2.length)]);
                // Wait 100 ticks (5 seconds), then trigger Phase 3
                InvasionHandler.queueNarratorTask(player, pos, 3, 100);
                break;

            case 3:
                handleResolution(player, pos);
                break;
        }
    }

    private static void handleResolution(EntityPlayer player, BlockPos pos) {
        int score = AirDefenseHandler.getScore(player);
        float roll = rand.nextFloat() * 100;
        float strikeThreshold = 100 - score; // e.g. Score 80 means 20% chance of strike

        if (roll < strikeThreshold) {
            int range = (score >= 80) ? 500 : (score >= 50) ? 100 : 10;
            BlockPos strikePos = pos.add(rand.nextInt(range) - (range/2), 0, rand.nextInt(range) - (range/2));

            narrate(player, TextFormatting.RED + PHASE_3C_IMPACT[rand.nextInt(PHASE_3C_IMPACT.length)]);
            TrinityExplosionBridge.forceDetonation(player.world, strikePos, "trinity:salted_bomb_u235");
        } else {
            narrate(player, TextFormatting.GREEN + "Air Defense successful. Threat neutralized.");
        }
    }

    private static void narrate(EntityPlayer player, String message) {
        if (player != null) {
            player.sendMessage(new TextComponentString(TextFormatting.GRAY + "[The Narrator] " + TextFormatting.RESET + message));
        }
    }
}