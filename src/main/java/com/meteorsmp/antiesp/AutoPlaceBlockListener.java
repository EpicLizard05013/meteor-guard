package com.meteorsmp.antiesp;

import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.player.PlayerQuitEvent;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

public class AutoPlaceBlockListener implements Listener {

    private final AntiESPPlugin plugin;
    private final Map<UUID, Long> lastPlaceTime = new HashMap<>();
    private final Map<UUID, Integer> violations = new HashMap<>();
    private final Map<UUID, Long> lastViolation = new HashMap<>();

    private boolean enabled, kick;
    private long minInterval, memoryMs;
    private int maxViolations;
    private String kickMessage;

    public AutoPlaceBlockListener(AntiESPPlugin plugin) {
        this.plugin = plugin;
        Bukkit.getPluginManager().registerEvents(this, plugin);
    }

    public void reload() {
        var c = plugin.getConfig();
        String p = "combat.build-macro.";
        enabled = c.getBoolean(p + "enabled", true);
        minInterval = Math.max(1, c.getLong(p + "min-place-interval-ms", 45));
        maxViolations = Math.max(1, c.getInt(p + "violations-before-action", 10));
        memoryMs = Math.max(5, c.getInt(p + "violation-memory-seconds", 30)) * 1000L;
        kick = c.getString(p + "action", "kick").equalsIgnoreCase("kick");
        kickMessage = c.getString(p + "kick-message", "&cAutoplace / Build Macro detected!");
    }

    @EventHandler
    public void onBlockPlace(BlockPlaceEvent event) {
        if (!enabled) return;
        Player player = event.getPlayer();
        if (player.hasPermission("meteorguard.bypass")) return;

        UUID uuid = player.getUniqueId();
        long now = System.currentTimeMillis();
        Long last = lastPlaceTime.put(uuid, now);

        if (last != null && now - last < minInterval) {
            event.setCancelled(true);

            boolean stale = now - lastViolation.getOrDefault(uuid, 0L) > memoryMs;
            int vl = (stale ? 0 : violations.getOrDefault(uuid, 0)) + 1;
            violations.put(uuid, vl);
            lastViolation.put(uuid, now);

            plugin.getAlerts().alert("BUILD-MACRO", player.getName(),
                    "blocks placed " + (now - last) + "ms apart (VL " + vl + ")");

            if (vl >= maxViolations) {
                violations.remove(uuid);
                lastViolation.remove(uuid);
                if (kick) player.kick(AlertManager.c(kickMessage));
            }
        }
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        UUID uuid = event.getPlayer().getUniqueId();
        lastPlaceTime.remove(uuid);
        violations.remove(uuid);
        lastViolation.remove(uuid);
    }
}
