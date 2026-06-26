package co.runed.multicharacter.commands;

import net.minecraft.command.CommandBase;
import net.minecraft.command.ICommandSender;
import net.minecraft.server.MinecraftServer;

public class CommandOpenCharacterGui extends CommandBase {
    @Override
    public String getName() { return "characters"; }

    @Override
    public String getUsage(ICommandSender sender) { return "/characters"; }

    @Override
    public void execute(MinecraftServer server, ICommandSender sender, String[] args) {}
}
