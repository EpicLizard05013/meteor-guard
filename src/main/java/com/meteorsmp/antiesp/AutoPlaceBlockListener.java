package com.meteorsmp.antiesp;

import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.plugin.Plugin;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

public class AutoPlaceBlockListener implements Listener {

    private final Plugin plugin;
    private final Map<UUID, Long> lastPlaceTime = new HashMap<>();
    private final Map<UUID, Integer> fastPlaceViolations = new HashMap<>();

    private boolean enabled = true;
    private long minPlaceIntervalMs = 45L;
    private int kickThreshold = 10;

    public AutoPlaceBlockListener(Plugin plugin) {
        this.plugin = plugin;
        reload();
        Bukkit.getPluginManager().registerEvents(this, plugin);
    }

    public void reload() {
        var c = plugin.getConfig();
        String p = "combat-checks.autoplace.";
        enabled = c.getBoolean(p + "enabled", true);
        minPlaceIntervalMs = Math.max(1L, c.getLong(p + "min-place-interval-ms", 45L));
        kickThreshold = Math.max(1, c.getInt(p + "kick-violation-threshold", 10));
    }

    @EventHandler
    public void onBlockPlace(BlockPlaceEvent event) {
        if (!enabled) return;
        Player player = event.getPlayer();
        if (player.hasPermission("meteorshield.bypass")) {
            return;
        }

        UUID uuid = player.getUniqueId();
        long now = System.currentTimeMillis();

        if (lastPlaceTime.containsKey(uuid)) {
            long diff = now - lastPlaceTime.get(uuid);
            if (diff < minPlaceIntervalMs) {
                event.setCancelled(true);
                
                int vl = fastPlaceViolations.getOrDefault(uuid, 0) + 1;
                fastPlaceViolations.put(uuid, vl);

                if (vl >= kickThreshold) {
                    Bukkit.getScheduler().runTask(plugin, () -> 
                        player.kickPlayer("§cAutoplace / Build Macro detected!")
                    );
                }
            }
        }
        lastPlaceTime.put(uuid, now);
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        UUID uuid = event.getPlayer().getUniqueId();
        lastPlaceTime.remove(uuid);
        fastPlaceViolations.remove(uuid);
    }
}
