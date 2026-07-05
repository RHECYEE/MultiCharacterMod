package studio.ERM.war;

import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.util.text.TextComponentString;
import net.minecraft.util.text.TextFormatting;

public class WarNarratorManager {

    /**
     * Sends a two-line narrator message to the player. Styled to match the
     * existing "[The Narrator]" chat lines used elsewhere in the mod.
     */
    public static void sendNarratorMessage(EntityPlayer player, String line, String followUp) {
        if (player == null || player.world == null || player.world.isRemote) return;

        if (line != null && !line.isEmpty()) {
            player.sendMessage(new TextComponentString(
                    TextFormatting.GRAY + "[The Narrator] " + TextFormatting.RESET + line));
        }
        if (followUp != null && !followUp.isEmpty()) {
            player.sendMessage(new TextComponentString(
                    TextFormatting.GRAY + "[The Narrator] " + TextFormatting.RESET + followUp));
        }
    }
}
