package studio.ERM.handlers;

import net.minecraft.util.text.TextComponentString;
import net.minecraft.util.text.TextFormatting;
import net.minecraftforge.event.entity.player.PlayerSleepInBedEvent;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import studio.ERM.EpochRunnerMod;

public class SleepBlocker {

    @SubscribeEvent
    public void onSleepAttempt(PlayerSleepInBedEvent event) {
        if (!event.getEntityPlayer().world.isRemote) {
            // Check the InvasionHandler instance to see if sleeping should be blocked
            if (EpochRunnerMod.invasionHandlerInstance != null && EpochRunnerMod.invasionHandlerInstance.shouldBlockSleep()) {

                event.setResult(net.minecraft.entity.player.EntityPlayer.SleepResult.OTHER_PROBLEM);

                event.getEntityPlayer().sendMessage(new TextComponentString(
                        TextFormatting.RED + "[EpochRunner] " + TextFormatting.YELLOW + "The threat is too close! You cannot rest until the invasion is over."
                ));
            }
        }
    }
}