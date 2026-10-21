package com.meteorsmp.antiesp;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public final class AlertManager {

    public static Component c(String s) {
        return LegacyComponentSerializer.legacyAmpersand().deserialize(s);
    }

    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private final AntiESPPlugin plugin;
    private final Set<UUID> muted = ConcurrentHashMap.newKeySet();
    private final Map<String, Long> lastShown = new ConcurrentHashMap<>();
    private volatile boolean enabled = true;
    private volatile boolean logToFile = true;
    private volatile boolean console = true;
    private volatile long cooldownMs = 10_000L;
    private volatile String format = "&8[&bShield&8] &f{player} &7- &c{check} &8(&7{details}&8)";

    public AlertManager(AntiESPPlugin plugin) {
        this.plugin = plugin;
    }

    public void reload() {
        var cfg = plugin.getConfig();
        enabled = cfg.getBoolean("alerts.enabled", true);
        logToFile = cfg.getBoolean("alerts.log-to-file", true);
        console = cfg.getBoolean("alerts.console", true);
        cooldownMs = Math.max(0, cfg.getInt("alerts.cooldown-seconds", 10)) * 1000L;
        format = cfg.getString("alerts.format", format);
    }

    /** @return true if the player now RECEIVES alerts, false if muted */
    public boolean toggle(Player p) {
        UUID id = p.getUniqueId();
        if (muted.remove(id)) return true;
        muted.add(id);
        return false;
    }

    public void alert(String check, String player, String details) {
        alert(check, player, details, true);
    }

    public void alert(String check, String player, String details, boolean broadcast) {
        if (!enabled || !plugin.isEnabled()) return;

        long now = System.currentTimeMillis();
        String key = check + ":" + player;
        Long prev = lastShown.get(key);
        if (prev != null && now - prev < cooldownMs) return;
        lastShown.put(key, now);
        if (lastShown.size() > 5000) lastShown.clear();

        String plain = check + " | " + player + " | " + details;
        if (logToFile) {
            String line = "[" + LocalDateTime.now().format(TIME) + "] " + plain;
            Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> writeLog(line));
        }

        String msg = format.replace("{player}", player).replace("{check}", check).replace("{details}", details);
        Runnable r = () -> {
            if (broadcast) {
                Component comp = c(msg);
                for (Player p : Bukkit.getOnlinePlayers()) {
                    if (p.hasPermission("meteorshield.alerts") && !muted.contains(p.getUniqueId())) p.sendMessage(comp);
                }
            }
            if (console) plugin.getLogger().info("[ALERT] " + plain);
        };
        
        if (Bukkit.isPrimaryThread()) {
            r.run();
        } else {
            Bukkit.getScheduler().runTask(plugin, r);
        }
    }

    private void writeLog(String line) {
        try {
            Path folder = plugin.getDataFolder().toPath().resolve("data/alerts");
            if (!Files.exists(folder)) {
                Files.createDirectories(folder);
            }
            Path file = folder.resolve(LocalDate.now() + ".log");
            Files.writeString(file, line + System.lineSeparator(), StandardCharsets.UTF_8, 
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (IOException e) {
            plugin.getLogger().warning("Failed to write alert log: " + e.getMessage());
        }
    }
}
