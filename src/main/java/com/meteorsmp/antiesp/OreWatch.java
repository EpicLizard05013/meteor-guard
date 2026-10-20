package com.meteorsmp.antiesp;

import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Material;
import org.bukkit.Tag;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.scheduler.BukkitTask;

import java.util.ArrayDeque;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/** Alert-only xray heuristic: how many diamonds / debris compared to plain stone mined. */
public final class OreWatch implements Listener {

    private static final class Stat {
        double base;
        double ores;
        final ArrayDeque<Long> recent = new ArrayDeque<>();
        long lastAlert;
    }

    private final AntiESPPlugin plugin;
    private final AlertManager alerts;
    private final Map<UUID, Stat> stats = new HashMap<>();
    private final Set<Material> tracked = EnumSet.noneOf(Material.class);
    private BukkitTask decayTask;

    private boolean enabled;
    private int minSample, burstCount;
    private double maxRatio;
    private long burstMs, cooldownMs;

    public OreWatch(AntiESPPlugin plugin, AlertManager alerts) {
        this.plugin = plugin;
        this.alerts = alerts;
    }

    public void reload() {
        var c = plugin.getConfig();
        String p = "ore-watch.";
        enabled = c.getBoolean(p + "enabled", true);
        minSample = Math.max(20, c.getInt(p + "min-sample", 150));
        maxRatio = c.getDouble(p + "max-ratio", 0.06);
        burstCount = Math.max(2, c.getInt(p + "burst-count", 10));
        burstMs = Math.max(1, c.getInt(p + "burst-minutes", 10)) * 60_000L;
        cooldownMs = Math.max(1, c.getInt(p + "alert-cooldown-minutes", 5)) * 60_000L;

        tracked.clear();
        for (String s : c.getStringList(p + "tracked-ores")) {
            Material m = Material.matchMaterial(s);
            if (m != null) tracked.add(m);
            else plugin.getLogger().warning("Unknown material in ore-watch.tracked-ores: " + s);
        }

        if (decayTask != null) decayTask.cancel();
        decayTask = Bukkit.getScheduler().runTaskTimer(plugin, this::decay, 2400L, 2400L);
    }

    public void shutdown() {
        if (decayTask != null) decayTask.cancel();
        stats.clear();
    }

    private void decay() {
        Iterator<Stat> it = stats.values().iterator();
        while (it.hasNext()) {
            Stat s = it.next();
            s.base *= 0.9;
            s.ores *= 0.9;
            if (s.base < 1 && s.ores < 0.5 && s.recent.isEmpty()) it.remove();
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBreak(BlockBreakEvent e) {
        if (!enabled) return;
        Player p = e.getPlayer();
        GameMode gm = p.getGameMode();
        if (gm == GameMode.CREATIVE || gm == GameMode.SPECTATOR || p.hasPermission("meteorshield.bypass")) return;

        Material m = e.getBlock().getType();
        boolean isOre = tracked.contains(m);
        boolean isBase = Tag.BASE_STONE_OVERWORLD.isTagged(m) || Tag.BASE_STONE_NETHER.isTagged(m);
        if (!isOre && !isBase) return;

        long now = System.currentTimeMillis();
        Stat s = stats.computeIfAbsent(p.getUniqueId(), k -> new Stat());
        if (isBase) s.base++;
        if (isOre) {
            s.ores++;
            s.recent.addLast(now);
        }
        while (!s.recent.isEmpty() && now - s.recent.peekFirst() > burstMs) s.recent.pollFirst();

        double total = s.base + s.ores;
        boolean ratioBad = total >= minSample && s.ores / total >= maxRatio;
        boolean burstBad = s.recent.size() >= burstCount;
        if ((ratioBad || burstBad) && now - s.lastAlert > cooldownMs) {
            s.lastAlert = now;
            String why = burstBad
                    ? s.recent.size() + " rare ores in " + (burstMs / 60_000L) + " min"
                    : String.format(Locale_US, "ratio %.1f%% (%d ores / %d blocks)", s.ores / total * 100.0, (int) s.ores, (int) total);
            alerts.alert("ORE-RATIO", p.getName(), why);
        }
    }

    private static final java.util.Locale Locale_US = java.util.Locale.US;
}
