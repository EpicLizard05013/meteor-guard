package com.meteorsmp.antiesp;

import org.bukkit.Bukkit;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.entity.Player;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

public final class PunishmentManager {

    private static final Map<UUID, Integer> violationCounts = new HashMap<>();

    public static void handleViolation(AntiESPPlugin plugin, Player player, String reason) {
        UUID uuid = player.getUniqueId();
        FileConfiguration cfg = plugin.getConfig();

        boolean autoPunish = cfg.getBoolean("punishments.auto-punish", true);
        if (!autoPunish) return;

        int violations = violationCounts.getOrDefault(uuid, 0) + 1;
        violationCounts.put(uuid, violations);

        int maxViolations = cfg.getInt("punishments.threshold", 3);
        String commandToExecute = cfg.getString("punishments.command", "tempban %player% 1d Auto-Punished for Cheat Detection: %reason%");

        if (violations >= maxViolations) {
            violationCounts.remove(uuid);

            String parsedCommand = commandToExecute
                    .replace("%player%", player.getName())
                    .replace("%reason%", reason);

            Bukkit.getScheduler().runTask(plugin, () -> {
                Bukkit.dispatchCommand(Bukkit.getConsoleSender(), parsedCommand);
            });
        }
    }

    public static void clearViolations(Player player) {
        violationCounts.remove(player.getUniqueId());
    }
}
