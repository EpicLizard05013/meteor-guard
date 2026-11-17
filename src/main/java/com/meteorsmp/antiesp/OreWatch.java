package com.meteorsmp.antiesp;

import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Material;
import org.bukkit.Tag;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.scheduler.BukkitTask;

import java.util.ArrayDeque;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Xray heuristics (alert + suspicious log only, never punishes by itself):
 *  - ratio: rare ores compared to plain stone mined
 *  - burst: many rare ores in a short time
 *  - hidden: ores mined that had NO air / water / lava next to them (found through solid rock)
 */
public final class OreWatch implements Listener {

    private static final BlockFace[] FACES = {
            BlockFace.UP, BlockFace.DOWN, BlockFace.NORTH, BlockFace.SOUTH, BlockFace.EAST, BlockFace.WEST
    };

    private static final class Stat {
        double base;
        double ores;
        int hiddenTotal;
        final Map<Material, Integer> oreCounts = new EnumMap<>(Material.class);
        final ArrayDeque<Long> recent = new ArrayDeque<>();
        final ArrayDeque<Long> hidden = new ArrayDeque<>();
        long lastAlert;
    }

    private final AntiESPPlugin plugin;
    private final AlertManager alerts;
    private final SuspiciousManager suspicious;
    private final Map<UUID, Stat> stats = new HashMap<>();
    private final Set<Material> tracked = EnumSet.noneOf(Material.class);
    private BukkitTask decayTask;

    private boolean enabled;
    private int minSample, burstCount, hiddenCount;
    private double maxRatio;
    private long burstMs, hiddenMs, cooldownMs;

    public OreWatch(AntiESPPlugin plugin, AlertManager alerts, SuspiciousManager suspicious) {
        this.plugin = plugin;
        this.alerts = alerts;
        this.suspicious = suspicious;
    }

    public void reload() {
        var c = plugin.getConfig();
        String p = "ore-watch.";
        enabled = c.getBoolean(p + "enabled", true);
        minSample = Math.max(20, c.getInt(p + "min-sample", 150));
        maxRatio = c.getDouble(p + "max-ratio", 0.06);
        burstCount = Math.max(2, c.getInt(p + "burst-count", 10));
        burstMs = Math.max(1, c.getInt(p + "burst-minutes", 10)) * 60_000L;
        hiddenCount = Math.max(2, c.getInt(p + "hidden-ore-count", 6));
        hiddenMs = Math.max(1, c.getInt(p + "hidden-ore-minutes", 15)) * 60_000L;
        cooldownMs = Math.max(1, c.getInt(p + "alert-cooldown-minutes", 5)) * 60_000L;

        tracked.clear();
        for (String s : c.getStringList(p + "tracked-ores")) {
            Material m = Material.matchMaterial(s);
            if (m != null) tracked.add(m);
            else plugin.getLogger().warning("Unknown material in ore-watch.tracked-ores: " + s);
        }
        if (tracked.isEmpty()) { // a missing/empty config section must not silently switch the feature off
            tracked.add(Material.DIAMOND_ORE);
            tracked.add(Material.DEEPSLATE_DIAMOND_ORE);
            tracked.add(Material.ANCIENT_DEBRIS);
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
            if (s.base < 1 && s.ores < 0.5 && s.recent.isEmpty() && s.hidden.isEmpty()) it.remove();
        }
    }

    /** An ore is "exposed" when something non-solid (air, water, lava, torch...) touches it, i.e. it sits in a cave. */
    private static boolean exposed(Block b) {
        for (BlockFace f : FACES) {
            if (!b.getRelative(f).getType().isSolid()) return true;
        }
        return false;
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBreak(BlockBreakEvent e) {
        if (!enabled) return;
        Player p = e.getPlayer();
        GameMode gm = p.getGameMode();
        if (gm == GameMode.CREATIVE || gm == GameMode.SPECTATOR || p.hasPermission("meteorshield.bypass")) return;

        Block block = e.getBlock();
        Material m = block.getType();
        boolean isOre = tracked.contains(m);
        boolean isBase = Tag.BASE_STONE_OVERWORLD.isTagged(m) || Tag.BASE_STONE_NETHER.isTagged(m);
        if (!isOre && !isBase) return;

        long now = System.currentTimeMillis();
        Stat s = stats.computeIfAbsent(p.getUniqueId(), k -> new Stat());
        if (isBase) s.base++;
        if (isOre) {
            s.ores++;
            s.oreCounts.merge(m, 1, Integer::sum);
            s.recent.addLast(now);
            if (!exposed(block)) {
                s.hidden.addLast(now);
                s.hiddenTotal++;
            }
        }
        while (!s.recent.isEmpty() && now - s.recent.peekFirst() > burstMs) s.recent.pollFirst();
        while (!s.hidden.isEmpty() && now - s.hidden.peekFirst() > hiddenMs) s.hidden.pollFirst();

        double total = s.base + s.ores;
        boolean ratioBad = total >= minSample && s.ores / total >= maxRatio;
        boolean burstBad = s.recent.size() >= burstCount;
        boolean hiddenBad = s.hidden.size() >= hiddenCount;
        if ((ratioBad || burstBad || hiddenBad) && now - s.lastAlert > cooldownMs) {
            s.lastAlert = now;
            String why;
            if (hiddenBad) {
                why = s.hidden.size() + " rare ores found inside solid rock in " + (hiddenMs / 60_000L) + " min";
            } else if (burstBad) {
                why = s.recent.size() + " rare ores in " + (burstMs / 60_000L) + " min";
            } else {
                why = String.format(Locale.US, "ratio %.1f%% (%d ores / %d blocks)", s.ores / total * 100.0, (int) s.ores, (int) total);
            }
            alerts.alert("ORE-RATIO", p.getName(), why);
            if (suspicious != null) {
                suspicious.record(p, why, (int) Math.round(s.ores), (int) Math.round(s.base), s.oreCounts,
                        block.getLocation(), s.hiddenTotal);
            }
        }
    }

    /** Staff see it when someone from the suspicious log joins. */
    @EventHandler
    public void onJoin(PlayerJoinEvent e) {
        if (!enabled || suspicious == null) return;
        SuspiciousManager.SuspiciousEntry en = suspicious.getEntry(e.getPlayer().getUniqueId());
        if (en != null) {
            alerts.alert("SUSPICIOUS-JOIN", e.getPlayer().getName(),
                    "is in the suspicious log: " + en.flags + " flags, " + en.totalOres + " rare ores");
        }
    }
}
