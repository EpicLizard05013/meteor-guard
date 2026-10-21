package com.meteorsmp.antiesp;

import org.bukkit.ChatColor;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.List;

public class CommandManager implements CommandExecutor, TabCompleter {

    private final AntiESPPlugin plugin;

    public CommandManager(AntiESPPlugin plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command, @NotNull String label, @NotNull String[] args) {
        if (args.length == 0 || args[0].equalsIgnoreCase("help")) {
            sendHelp(sender);
            return true;
        }

        String sub = args[0].toLowerCase();
        boolean admin = sender.hasPermission("meteorguard.admin") || sender.hasPermission("antifreecam.admin");
        boolean alertToggle = sub.equals("alerts") && sender.hasPermission("meteorshield.alerts");

        if (!admin && !alertToggle) {
            sender.sendMessage(ChatColor.RED + "You do not have permission to use this command.");
            return true;
        }

        if (sub.equals("reload") || sub.equals("toggle") || sub.equals("status") || sub.equals("bypass")
                || sub.equals("attack") || sub.equals("scan") || sub.equals("alerts")) {
            return plugin.onCommand(sender, command, label, args);
        }

        sendHelp(sender);
        return true;
    }

    @Override
    public List<String> onTabComplete(@NotNull CommandSender sender, @NotNull Command command,
                                     @NotNull String alias, @NotNull String[] args) {
        if (!sender.hasPermission("meteorguard.admin") && !sender.hasPermission("antifreecam.admin")) {
            return new ArrayList<>();
        }
        return plugin.onTabComplete(sender, command, alias, args);
    }

    private void sendHelp(CommandSender sender) {
        sender.sendMessage(ChatColor.GOLD + "=== MeteorGuard Commands ===");
        sender.sendMessage(ChatColor.GRAY + "/meteor reload - Reload the config");
        sender.sendMessage(ChatColor.GRAY + "/meteor toggle - Enable or disable anti-ESP");
        sender.sendMessage(ChatColor.GRAY + "/meteor status - Show the current status");
        sender.sendMessage(ChatColor.GRAY + "/meteor antifreecam bypass [player] - Toggle bypass for a player");
        sender.sendMessage(ChatColor.GRAY + "/meteor alerts - Toggle your personal alert view");
        sender.sendMessage(ChatColor.GRAY + "/meteor attack [on|off] - Force join-attack mode");
        sender.sendMessage(ChatColor.GRAY + "/meteor scan <player> - Scan a player for illegal items");
    }
}
