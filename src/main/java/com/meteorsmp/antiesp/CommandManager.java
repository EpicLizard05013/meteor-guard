package com.meteorsmp.antiesp;

import org.chat2color // (or standard ChatColor)
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
        if (!sender.hasPermission("meteorguard.admin")) {
            sender.sendMessage(ChatColor.RED + "You do not have permission to use this command.");
            return true;
        }

        if (args.length > 0 && args[0].equalsIgnoreCase("reload")) {
            plugin.getConfigManager().reloadConfig();
            sender.sendMessage(ChatColor.GREEN + "[MeteorGuard] Configuration reloaded successfully!");
            return true;
        }

        sender.sendMessage(ChatColor.YELLOW + "=== MeteorGuard Administration ===");
        sender.sendMessage(ChatColor.GRAY + "/meteorguard reload - Reloads the configuration file.");
        return true;
    }

    @Override
    public List<String> onTabComplete(@NotNull CommandSender sender, @NotNull Command command, @NotNull String alias, @NotNull String[] args) {
        List<String> completions = new ArrayList<>();
        if (args.length == 1 && sender.hasPermission("meteorguard.admin")) {
            completions.add("reload");
        }
        return completions;
    }
}
