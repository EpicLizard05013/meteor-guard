package com.meteorsmp.antiesp;

import io.papermc.paper.event.player.AsyncChatEvent;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.player.PlayerCommandPreprocessEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerQuitEvent;

import java.util.ArrayDeque;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/** Chat / command / inventory-click / interaction rate limits. */
public final class RateLimiter implements Listener {

    private final AntiESPPlugin plugin;
    private final AlertManager alerts;
    private final Map<UUID, Map<String, ArrayDeque<Long>>> data = new ConcurrentHashMap<>();
    private final Map<UUID, String> lastChat = new ConcurrentHashMap<>();

    private boolean enabled, blockRepeats;
    private int chatMax, cmdMax, cmdLen, clickMax, interMax;
    private long chatWin, cmdWin, clickWin, interWin;

    public RateLimiter(AntiESPPlugin plugin, AlertManager alerts) {
        this.plugin = plugin;
        this.alerts = alerts;
    }

    public void reload() {
        var c = plugin.getConfig();
        enabled = c.getBoolean("rate-limits.enabled", true);
        chatMax = c.getInt("rate-limits.chat.max-messages", 5);
        chatWin = c.getInt("rate-limits.chat.window-seconds", 4) * 1000L;
        blockRepeats = c.getBoolean("rate-limits.chat.block-repeats", true);
        cmdMax = c.getInt("rate-limits.commands.max-commands", 8);
        cmdWin = c.getInt("rate-limits.commands.window-seconds", 4) * 1000L;
        cmdLen = c.getInt("rate-limits.commands.max-length", 256);
        clickMax = c.getInt("rate-limits.inventory-clicks.max-clicks", 60);
        clickWin = c.getInt("rate-limits.inventory-clicks.window-seconds", 1) * 1000L;
        interMax = c.getInt("rate-limits.interactions.max", 40);
        interWin = c.getInt("rate-limits.interactions.window-seconds", 1) * 1000L;
    }

    private boolean hit(UUID id, String type, int max, long windowMs) {
        long now = System.currentTimeMillis();
        Map<String, ArrayDeque<Long>> m = data.computeIfAbsent(id, k -> new ConcurrentHashMap<>());
        ArrayDeque<Long> dq = m.computeIfAbsent(type, k -> new ArrayDeque<>());
        synchronized (dq) {
            while (!dq.isEmpty() && now - dq.peekFirst() > windowMs) dq.pollFirst();
            dq.addLast(now);
            return dq.size() > max;
        }
    }

    private boolean exempt(Player p) {
        return p.hasPermission("meteorshield.bypass");
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onChat(AsyncChatEvent e) {
        if (!enabled) return;
        Player p = e.getPlayer();
        if (exempt(p)) return;
        String text = PlainTextComponentSerializer.plainText().serialize(e.message()).toLowerCase(Locale.ROOT).trim();
        boolean spam = hit(p.getUniqueId(), "chat", chatMax, chatWin);
        boolean repeat = false;
        if (blockRepeats && !text.isEmpty()) {
            String prev = lastChat.put(p.getUniqueId(), text);
            repeat = text.equals(prev);
        }
        if (spam || repeat) {
            e.setCancelled(true);
            p.sendMessage(AlertManager.c("&cSlow down, you're sending messages too fast."));
            alerts.alert("CHAT-SPAM", p.getName(), spam ? "too many messages" : "repeated message");
        }
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onCommand(PlayerCommandPreprocessEvent e) {
        if (!enabled) return;
        Player p = e.getPlayer();
        if (exempt(p)) return;
        if (e.getMessage().length() > cmdLen) {
            e.setCancelled(true);
            alerts.alert("COMMAND-LENGTH", p.getName(), e.getMessage().length() + " chars");
            return;
        }
        if (hit(p.getUniqueId(), "cmd", cmdMax, cmdWin)) {
            e.setCancelled(true);
            p.sendMessage(AlertManager.c("&cSlow down, you're using commands too fast."));
            alerts.alert("COMMAND-SPAM", p.getName(), "more than " + cmdMax + " commands in " + (cmdWin / 1000) + "s");
        }
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onClick(InventoryClickEvent e) {
        if (!enabled || !(e.getWhoClicked() instanceof Player p) || exempt(p)) return;
        if (hit(p.getUniqueId(), "click", clickMax, clickWin)) {
            e.setCancelled(true);
            alerts.alert("CLICK-RATE", p.getName(), "more than " + clickMax + " clicks in " + (clickWin / 1000) + "s");
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onInteract(PlayerInteractEvent e) {
        if (!enabled || exempt(e.getPlayer())) return;
        if (hit(e.getPlayer().getUniqueId(), "inter", interMax, interWin)) {
            alerts.alert("INTERACT-RATE", e.getPlayer().getName(), "more than " + interMax + " interactions in " + (interWin / 1000) + "s");
        }
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent e) {
        data.remove(e.getPlayer().getUniqueId());
        lastChat.remove(e.getPlayer().getUniqueId());
    }
}
