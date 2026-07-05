package studio.ERM.war;

import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.util.text.TextComponentString;
import net.minecraft.util.text.TextFormatting;

public class AirDefenseHandler {
    private static final String AD_TAG = "ERM_AirDefense";

    public static int getScore(EntityPlayer player) {
        // Accessing the persistent Forge NBT data on the player entity
        return player.getEntityData().getInteger(AD_TAG);
    }

    public static void addScore(EntityPlayer player, int amount) {
        int current = getScore(player);
        setScore(player, current + amount);
    }

    public static void setScore(EntityPlayer player, int amount) {
        int finalScore = Math.min(100, Math.max(0, amount));
        player.getEntityData().setInteger(AD_TAG, finalScore);
        player.sendMessage(new TextComponentString(TextFormatting.BLUE + "[Air Defense] " + TextFormatting.GRAY + "System integrity now at " + finalScore + "/100."));
    }
}