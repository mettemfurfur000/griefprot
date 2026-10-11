package com.tem.griefprot;

import java.util.Arrays;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;

/** Dispatches the public command to debug and administrative subcommands. */
public final class GriefProtCommand implements CommandExecutor {

    private final DebugOverlay debugOverlay;
    private final AdminCommand adminCommand;

    public GriefProtCommand(DebugOverlay debugOverlay, AdminCommand adminCommand) {
        this.debugOverlay = debugOverlay;
        this.adminCommand = adminCommand;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (args.length == 0) {
            sender.sendMessage("Usage: /griefprot <overlay|admin> ...");
            return true;
        }
        String[] subArgs = Arrays.copyOfRange(args, 1, args.length);
        return switch (args[0].toLowerCase(java.util.Locale.ROOT)) {
            case "overlay" -> {
                if (!sender.hasPermission("griefprot.debug")) {
                    sender.sendMessage("You do not have permission to use GriefProt debug overlays.");
                    yield true;
                }
                yield debugOverlay.onCommand(sender, command, label, args);
            }
            case "admin" -> adminCommand.onCommand(sender, command, label, subArgs);
            default -> {
                sender.sendMessage("Usage: /griefprot <overlay|admin> ...");
                yield true;
            }
        };
    }
}
