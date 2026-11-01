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

    // Maximum allowed CPS before flagging
    private static final int MAX_CPS = 18;
    // Threshold for macro detection (too consistent interval variance)
    private static final double MACRO_VARIANCE_THRESHOLD = 0.5;

    public AntiAutoclicker(Plugin plugin) {
        this.plugin = plugin;
        Bukkit.getPluginManager().registerEvents(this, plugin);
    }

    @EventHandler
    public void onPlayerClick(PlayerInteractEvent event) {
        if (event.getAction() != Action.LEFT_CLICK_AIR && event.getAction() != Action.LEFT_CLICK_BLOCK) {
            return;
        }

        Player player = event.getPlayer();
        if (player.hasPermission("meteorguard.bypass")) {
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
        if (currentCps > MAX_CPS) {
            handleViolation(player, "High CPS (" + currentCps + " CPS)");
            return;
        }

        // Check 2: Macro Check (Inhuman click interval consistency)
        if (timestamps.size() >= 10) {
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
        return stdDev < MACRO_VARIANCE_THRESHOLD && mean > 0;
    }

    private void handleViolation(Player player, String reason) {
        UUID uuid = player.getUniqueId();
        int vl = violationLevels.getOrDefault(uuid, 0) + 1;
        violationLevels.put(uuid, vl);

        // Alert staff & console
        Bukkit.getScheduler().runTask(plugin, () -> {
            Bukkit.getConsoleSender().sendMessage("§c[MeteorGuard] " + player.getName() + " failed combat checks: " + reason + " (VL: " + vl + ")");
            for (Player p : Bukkit.getOnlinePlayers()) {
                if (p.hasPermission("meteorguard.notify")) {
                    p.sendMessage("§8[§cMG§8] §e" + player.getName() + " §7failed §c" + reason);
                }
            }

            if (vl >= 5) {
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
