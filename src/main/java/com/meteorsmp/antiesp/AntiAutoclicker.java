package com.meteorsmp.antiesp;

import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerQuitEvent;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public class AntiAutoclicker implements Listener {

    private final AntiESPPlugin plugin;
    private final Map<UUID, List<Long>> clicks = new HashMap<>();
    private final Map<UUID, Integer> violations = new HashMap<>();
    private final Map<UUID, Long> lastViolation = new HashMap<>();

    private boolean enabled, macroEnabled, kick;
    private int maxCps, macroMinClicks, maxViolations;
    private double macroMaxStdDev;
    private long memoryMs;
    private String kickMessage;

    public AntiAutoclicker(AntiESPPlugin plugin) {
        this.plugin = plugin;
        Bukkit.getPluginManager().registerEvents(this, plugin);
    }

    public void reload() {
        var c = plugin.getConfig();
        String p = "combat.autoclicker.";
        enabled = c.getBoolean(p + "enabled", true);
        maxCps = Math.max(5, c.getInt(p + "max-cps", 18));
        macroEnabled = c.getBoolean(p + "macro-detection.enabled", true);
        macroMinClicks = Math.max(5, c.getInt(p + "macro-detection.min-clicks", 10));
        macroMaxStdDev = c.getDouble(p + "macro-detection.max-std-dev-ms", 0.5);
        maxViolations = Math.max(1, c.getInt(p + "violations-before-action", 5));
        memoryMs = Math.max(5, c.getInt(p + "violation-memory-seconds", 60)) * 1000L;
        kick = c.getString(p + "action", "kick").equalsIgnoreCase("kick");
        kickMessage = c.getString(p + "kick-message", "&cUnfair advantage detected: {reason}");
    }

    @EventHandler
    public void onPlayerClick(PlayerInteractEvent event) {
        if (!enabled) return;
        if (event.getAction() != Action.LEFT_CLICK_AIR && event.getAction() != Action.LEFT_CLICK_BLOCK) return;

        Player player = event.getPlayer();
        if (player.hasPermission("meteorguard.bypass")) return;

        UUID uuid = player.getUniqueId();
        long now = System.currentTimeMillis();
        List<Long> timestamps = clicks.computeIfAbsent(uuid, k -> new ArrayList<>());
        timestamps.add(now);
        timestamps.removeIf(time -> now - time > 1000);

        int cps = timestamps.size();
        if (cps > maxCps) {
            flag(player, "High CPS (" + cps + " CPS)");
            return;
        }
        if (macroEnabled && timestamps.size() >= macroMinClicks && isMacroPattern(timestamps)) {
            flag(player, "Autoclicker / Macro (inhuman click consistency)");
        }
    }

    private boolean isMacroPattern(List<Long> timestamps) {
        List<Long> intervals = new ArrayList<>();
        for (int i = 1; i < timestamps.size(); i++) intervals.add(timestamps.get(i) - timestamps.get(i - 1));
        double mean = intervals.stream().mapToLong(v -> v).average().orElse(0.0);
        double variance = intervals.stream().mapToDouble(v -> Math.pow(v - mean, 2)).average().orElse(0.0);
        return Math.sqrt(variance) < macroMaxStdDev && mean > 0;
    }

    private void flag(Player player, String reason) {
        UUID id = player.getUniqueId();
        long now = System.currentTimeMillis();
        boolean stale = now - lastViolation.getOrDefault(id, 0L) > memoryMs;
        int vl = (stale ? 0 : violations.getOrDefault(id, 0)) + 1;
        violations.put(id, vl);
        lastViolation.put(id, now);

        plugin.getAlerts().alert("AUTOCLICKER", player.getName(), reason + " (VL " + vl + ")");

        if (vl >= maxViolations) {
            violations.remove(id);
            lastViolation.remove(id);
            clicks.remove(id);
            if (kick) player.kick(AlertManager.c(kickMessage.replace("{reason}", reason)));
        }
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        UUID uuid = event.getPlayer().getUniqueId();
        clicks.remove(uuid);
        violations.remove(uuid);
        lastViolation.remove(uuid);
    }
}
