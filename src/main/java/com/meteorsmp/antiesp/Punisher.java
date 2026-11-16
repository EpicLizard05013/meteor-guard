package com.meteorsmp.antiesp;

import org.bukkit.Bukkit;
import org.bukkit.configuration.ConfigurationSection;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Optional automatic punishments: when one player triggers the same alert often enough inside a time window,
 * the configured console commands run (for example kick). Off by default. Main thread only.
 */
final class Punisher {

    private record Rule(int threshold, List<String> commands) {}

    private final AntiESPPlugin plugin;
    private final Map<String, Rule> rules = new HashMap<>();
    private final Map<String, Deque<Long>> hits = new HashMap<>();
    private boolean enabled;
    private long windowMs = 300_000L;

    Punisher(AntiESPPlugin plugin) {
        this.plugin = plugin;
    }

    void reload() {
        var c = plugin.getConfig();
        enabled = c.getBoolean("punishments.enabled", false);
        windowMs = Math.max(10, c.getInt("punishments.window-seconds", 300)) * 1000L;
        rules.clear();
        ConfigurationSection sec = c.getConfigurationSection("punishments.rules");
        if (sec != null) {
            for (String key : sec.getKeys(false)) {
                int threshold = Math.max(1, sec.getInt(key + ".threshold", 1));
                rules.put(key.toUpperCase(Locale.ROOT), new Rule(threshold, sec.getStringList(key + ".commands")));
            }
        }
    }

    void record(String check, String player) {
        if (!enabled) return;
        Rule rule = rules.get(check.toUpperCase(Locale.ROOT));
        if (rule == null) return;

        long now = System.currentTimeMillis();
        Deque<Long> dq = hits.computeIfAbsent(check + ":" + player, k -> new ArrayDeque<>());
        dq.addLast(now);
        while (!dq.isEmpty() && now - dq.peekFirst() > windowMs) dq.pollFirst();

        if (dq.size() >= rule.threshold()) {
            dq.clear();
            plugin.getLogger().info("[PUNISH] " + player + " reached " + rule.threshold() + "x " + check);
            for (String cmd : rule.commands()) {
                Bukkit.dispatchCommand(Bukkit.getConsoleSender(), cmd.replace("%player%", player));
            }
        }
        if (hits.size() > 5000) hits.clear();
    }
}
