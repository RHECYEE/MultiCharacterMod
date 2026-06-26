package co.runed.multicharacter.commands;

import net.minecraft.command.CommandBase;
import net.minecraft.command.ICommandSender;
import net.minecraft.server.MinecraftServer;

public class CommandRemoveRole extends CommandBase {
    @Override
    public String getName() { return "removerole"; }

    @Override
    public String getUsage(ICommandSender sender) { return "/removerole <player> <role>"; }

    @Override
    public void execute(MinecraftServer server, ICommandSender sender, String[] args) {}
}
