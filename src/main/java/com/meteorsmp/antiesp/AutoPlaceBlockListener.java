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

    // Minimum milliseconds allowed between block placements (e.g., 50ms = max 20 blocks/sec)
    private static final long MIN_PLACE_INTERVAL_MS = 45;

    public AutoPlaceBlockListener(Plugin plugin) {
        this.plugin = plugin;
        Bukkit.getPluginManager().registerEvents(this, plugin);
    }

    @EventHandler
    public void onBlockPlace(BlockPlaceEvent event) {
        Player player = event.getPlayer();
        if (player.hasPermission("meteorguard.bypass")) {
            return;
        }

        UUID uuid = player.getUniqueId();
        long now = System.currentTimeMillis();

        if (lastPlaceTime.containsKey(uuid)) {
            long diff = now - lastPlaceTime.get(uuid);
            if (diff < MIN_PLACE_INTERVAL_MS) {
                event.setCancelled(true);
                
                int vl = fastPlaceViolations.getOrDefault(uuid, 0) + 1;
                fastPlaceViolations.put(uuid, vl);

                if (vl >= 10) {
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
