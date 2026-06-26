package co.runed.multicharacter.addons.mpm.commands;

import net.minecraft.command.CommandBase;
import net.minecraft.command.ICommandSender;
import net.minecraft.server.MinecraftServer;

public class CommandTransform extends CommandBase {
    @Override
    public String getName() { return "transform"; }

    @Override
    public String getUsage(ICommandSender sender) { return "/transform"; }

    @Override
    public void execute(MinecraftServer server, ICommandSender sender, String[] args) {}
}
