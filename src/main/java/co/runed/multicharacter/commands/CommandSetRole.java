package co.runed.multicharacter.commands;

import net.minecraft.command.CommandBase;
import net.minecraft.command.ICommandSender;
import net.minecraft.server.MinecraftServer;

public class CommandSetRole extends CommandBase {
    @Override
    public String getName() { return "setrole"; }

    @Override
    public String getUsage(ICommandSender sender) { return "/setrole <player> <role>"; }

    @Override
    public void execute(MinecraftServer server, ICommandSender sender, String[] args) {}
}
