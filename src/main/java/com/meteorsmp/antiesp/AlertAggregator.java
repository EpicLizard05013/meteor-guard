package com.meteorsmp.antiesp;

import org.bukkit.Bukkit;
import org.bukkit.scheduler.BukkitTask;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

public class AlertAggregator {

    private final AntiESPPlugin plugin;
    private final Map<String, AtomicInteger> pendingAlerts = new ConcurrentHashMap<>();
    private BukkitTask task;

    public AlertAggregator(AntiESPPlugin plugin) {
        this.plugin = plugin;
        startBatchTask();
    }

    public void addAlert(String playerName, String violationType) {
        String key = playerName + ":" + violationType;
        pendingAlerts.computeIfAbsent(key, k -> new AtomicInteger(0)).incrementAndGet();
    }

    private void startBatchTask() {
        // Runs every 200 ticks (10 seconds) to flush and summarize alerts
        this.task = Bukkit.getScheduler().runTaskTimerAsynchronously(plugin, () -> {
            if (pendingAlerts.isEmpty()) return;

            pendingAlerts.forEach((key, count) -> {
                String[] parts = key.split(":");
                String player = parts[0];
                String type = parts[1];
                int total = count.getAndSet(0);

                if (total > 0) {
                    String message = String.format("&c[AntiESP Alert] &e%s &7flagged for &f%s &7(x%d violations)", player, type, total);
                    // Broadcast to staff with permission
                    Bukkit.getScheduler().runTask(plugin, () -> 
                        Bukkit.broadcast(org.bukkit.ChatColor.translateAlternateColorCodes('&', message), "meteorsmp.staff")
                    );
                }
            });
            pendingAlerts.clear();
        }, 200L, 200L);
    }

    public void shutdown() {
        if (task != null) {
            task.cancel();
        }
    }
}
