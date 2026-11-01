package com.meteorsmp.antiesp;

import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.plugin.Plugin;

import java.util.*;

public class AntiAutoclicker implements Listener {

    private final Plugin plugin;
    private final Map<UUID, List<Long>> clickTimestamps = new HashMap<>();
    private final Map<UUID, Integer> violationLevels = new HashMap<>();

    private boolean enabled = true;
    private int maxCps = 18;
    private int minSamples = 10;
    private double macroVarianceThreshold = 0.5;
    private int kickThreshold = 5;

    public AntiAutoclicker(Plugin plugin) {
        this.plugin = plugin;
        reload();
        Bukkit.getPluginManager().registerEvents(this, plugin);
    }

    public void reload() {
        var c = plugin.getConfig();
        String p = "combat-checks.autoclicker.";
        enabled = c.getBoolean(p + "enabled", true);
        maxCps = Math.max(1, c.getInt(p + "max-cps", 18));
        minSamples = Math.max(2, c.getInt(p + "min-sample-clicks", 10));
        macroVarianceThreshold = Math.max(0.0, c.getDouble(p + "macro-variance-threshold", 0.5));
        kickThreshold = Math.max(1, c.getInt(p + "kick-violation-threshold", 5));
    }

    @EventHandler
    public void onPlayerClick(PlayerInteractEvent event) {
        if (!enabled) return;
        if (event.getAction() != Action.LEFT_CLICK_AIR && event.getAction() != Action.LEFT_CLICK_BLOCK) {
            return;
        }

        Player player = event.getPlayer();
        if (player.hasPermission("meteorshield.bypass")) {
            return;
        }

        UUID uuid = player.getUniqueId();
        long now = System.currentTimeMillis();

        clickTimestamps.putIfAbsent(uuid, new ArrayList<>());
        List<Long> timestamps = clickTimestamps.get(uuid);

        timestamps.add(now);

        // Remove clicks older than 1 second (1000ms)
        timestamps.removeIf(time -> now - time > 1000);

        int currentCps = timestamps.size();

        // Check 1: Raw CPS limit exceeded
        if (currentCps > maxCps) {
            handleViolation(player, "High CPS (" + currentCps + " CPS)");
            return;
        }

        // Check 2: Macro Check (Inhuman click interval consistency)
        if (timestamps.size() >= minSamples) {
            if (isMacroPattern(timestamps)) {
                handleViolation(player, "Autoclicker / Macro Detected (Inhuman Consistency)");
            }
        }
    }

    private boolean isMacroPattern(List<Long> timestamps) {
        List<Long> intervals = new ArrayList<>();
        for (int i = 1; i < timestamps.size(); i++) {
            intervals.add(timestamps.get(i) - timestamps.get(i - 1));
        }

        double mean = intervals.stream().mapToLong(val -> val).average().orElse(0.0);
        double variance = intervals.stream().mapToDouble(val -> Math.pow(val - mean, 2)).average().orElse(0.0);
        double stdDev = Math.sqrt(variance);

        // If standard deviation of click intervals is extremely low, it's an automated macro
        return stdDev < macroVarianceThreshold && mean > 0;
    }

    private void handleViolation(Player player, String reason) {
        UUID uuid = player.getUniqueId();
        int vl = violationLevels.getOrDefault(uuid, 0) + 1;
        violationLevels.put(uuid, vl);

        // Alert staff & console
        Bukkit.getScheduler().runTask(plugin, () -> {
            Bukkit.getConsoleSender().sendMessage("§c[MeteorGuard] " + player.getName() + " failed combat checks: " + reason + " (VL: " + vl + ")");
            for (Player p : Bukkit.getOnlinePlayers()) {
                if (p.hasPermission("meteorshield.alerts")) {
                    p.sendMessage("§8[§cMG§8] §e" + player.getName() + " §7failed §c" + reason);
                }
            }

            if (vl >= kickThreshold) {
                player.kickPlayer("§cUnfair advantage detected: " + reason);
                violationLevels.remove(uuid);
            }
        });
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        UUID uuid = event.getPlayer().getUniqueId();
        clickTimestamps.remove(uuid);
        violationLevels.remove(uuid);
    }
}
