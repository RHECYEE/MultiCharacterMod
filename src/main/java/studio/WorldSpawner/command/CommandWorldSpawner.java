package studio.WorldSpawner.command;

import net.minecraft.command.CommandBase;
import net.minecraft.command.ICommandSender;
import net.minecraft.server.MinecraftServer;

public class CommandWorldSpawner extends CommandBase {
    @Override
    public String getName() {
        return "worldspawner";
    }

    @Override
    public String getUsage(ICommandSender sender) {
        return "/worldspawner";
    }

    @Override
    public void execute(MinecraftServer server, ICommandSender sender, String[] args) {
    }
}
