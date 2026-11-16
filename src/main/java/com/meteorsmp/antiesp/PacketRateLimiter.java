package com.meteorsmp.antiesp;

import com.github.retrooper.packetevents.PacketEvents;
import com.github.retrooper.packetevents.event.PacketListenerAbstract;
import com.github.retrooper.packetevents.event.PacketListenerPriority;
import com.github.retrooper.packetevents.event.PacketReceiveEvent;
import com.github.retrooper.packetevents.protocol.player.User;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerQuitEvent;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Anti-crash / packet flood protection (PacketEvents). A normal client sends well under 200 packets a second.
 * Above max-per-second the extra packets are dropped and staff are alerted; above kick-over the player is kicked.
 */
public final class PacketRateLimiter extends PacketListenerAbstract implements Listener {

    private final AntiESPPlugin plugin;
    private final AlertManager alerts;
    private final Map<UUID, long[]> windows = new ConcurrentHashMap<>(); // {windowStartMs, count}
    private volatile boolean enabled = true;
    private volatile int maxPerSecond = 500;
    private volatile int kickOver = 2500;

    public PacketRateLimiter(AntiESPPlugin plugin, AlertManager alerts) {
        super(PacketListenerPriority.LOWEST);
        this.plugin = plugin;
        this.alerts = alerts;
    }

    public void start() {
        reload();
        PacketEvents.getAPI().getEventManager().registerListener(this);
        Bukkit.getPluginManager().registerEvents(this, plugin);
    }

    public void stop() {
        PacketEvents.getAPI().getEventManager().unregisterListener(this);
        windows.clear();
    }

    public void reload() {
        var c = plugin.getConfig();
        enabled = c.getBoolean("packet-limiter.enabled", true);
        maxPerSecond = Math.max(100, c.getInt("packet-limiter.max-per-second", 500));
        kickOver = Math.max(maxPerSecond + 1, c.getInt("packet-limiter.kick-over", 2500));
    }

    @Override
    public void onPacketReceive(PacketReceiveEvent event) {
        if (!enabled) return;
        User user = event.getUser();
        UUID id = user == null ? null : user.getUUID();
        if (id == null) return;

        long now = System.currentTimeMillis();
        long[] w = windows.computeIfAbsent(id, k -> new long[]{now, 0L});
        long count;
        synchronized (w) {
            if (now - w[0] >= 1000L) {
                w[0] = now;
                w[1] = 0L;
            }
            count = ++w[1];
        }
        if (count <= maxPerSecond) return;

        event.setCancelled(true);
        String name = user.getName() == null ? id.toString() : user.getName();
        if (count == maxPerSecond + 1) {
            alerts.alert("PACKET-FLOOD", name, "over " + maxPerSecond + " packets per second");
        }
        if (count == kickOver) {
            Bukkit.getScheduler().runTask(plugin, () -> {
                Player p = Bukkit.getPlayer(id);
                if (p != null) p.kick(AlertManager.c("&cDisconnected: packet flood."));
            });
        }
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent e) {
        windows.remove(e.getPlayer().getUniqueId());
    }
}
