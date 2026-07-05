package co.runed.multicharacter.commands;

import net.minecraft.command.CommandBase;
import net.minecraft.command.ICommandSender;
import net.minecraft.server.MinecraftServer;

public class CommandAddRole extends CommandBase {
    @Override
    public String getName() { return "addrole"; }

    @Override
    public String getUsage(ICommandSender sender) { return "/addrole <player> <role>"; }

    @Override
    public void execute(MinecraftServer server, ICommandSender sender, String[] args) {}
}
