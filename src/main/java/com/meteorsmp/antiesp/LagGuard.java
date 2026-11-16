package com.meteorsmp.antiesp;

import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Chunk;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Entity;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.Item;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.CreatureSpawnEvent;
import org.bukkit.scheduler.BukkitTask;

import java.util.EnumSet;
import java.util.Locale;
import java.util.Set;

/**
 * Monitors server TPS to auto-throttle MeteorGuard checks, optionally caps mobs per chunk,
 * and provides optional timed item clearing plus /meteor perf.
 */
public final class LagGuard implements Listener {

    private final AntiESPPlugin plugin;
    private BukkitTask tpsTask;
    private BukkitTask itemTask;
    private BukkitTask warnTask;

    private volatile int currentLevel = 0;

    private boolean throttleEnabled = true;
    private double busyTps = 18.0;
    private double overloadedTps = 15.0;

    private boolean entityLimitEnabled = false;
    private int maxSameTypePerChunk = 30;

    private boolean itemClearEnabled = false;
    private int itemIntervalSec = 300;
    private int itemMinAgeSec = 60;
    private int itemWarnSec = 10;
    private final Set<Material> keepMaterials = EnumSet.noneOf(Material.class);

    public LagGuard(AntiESPPlugin plugin) {
        this.plugin = plugin;
    }

    public void start() {
        reload();
        Bukkit.getPluginManager().registerEvents(this, plugin);
        tpsTask = Bukkit.getScheduler().runTaskTimer(plugin, this::updateTps, 40L, 40L);
    }

    public void stop() {
        if (tpsTask != null) tpsTask.cancel();
        if (itemTask != null) itemTask.cancel();
        if (warnTask != null) warnTask.cancel();
        tpsTask = null;
        itemTask = null;
        warnTask = null;
    }

    public void reload() {
        var c = plugin.getConfig();
        String p = "lag-protection.";
        throttleEnabled = c.getBoolean(p + "auto-throttle.enabled", true);
        busyTps = c.getDouble(p + "auto-throttle.busy-tps", 18.0);
        overloadedTps = c.getDouble(p + "auto-throttle.overloaded-tps", 15.0);

        entityLimitEnabled = c.getBoolean(p + "entity-limit.enabled", false);
        maxSameTypePerChunk = Math.max(1, c.getInt(p + "entity-limit.max-same-type-per-chunk", 30));

        itemClearEnabled = c.getBoolean(p + "item-clear.enabled", false);
        itemIntervalSec = Math.max(30, c.getInt(p + "item-clear.interval-seconds", 300));
        itemMinAgeSec = Math.max(0, c.getInt(p + "item-clear.min-age-seconds", 60));
        itemWarnSec = Math.max(0, c.getInt(p + "item-clear.warn-seconds", 10));

        keepMaterials.clear();
        for (String s : c.getStringList(p + "item-clear.keep-materials")) {
            Material m = Material.matchMaterial(s);
            if (m != null) keepMaterials.add(m);
            else plugin.getLogger().warning("Unknown material in lag-protection.item-clear.keep-materials: " + s);
        }

        if (itemTask != null) {
            itemTask.cancel();
            itemTask = null;
        }
        if (warnTask != null) {
            warnTask.cancel();
            warnTask = null;
        }

        if (itemClearEnabled) {
            long periodTicks = itemIntervalSec * 20L;
            if (itemWarnSec > 0 && itemWarnSec < itemIntervalSec) {
                long firstWarn = (itemIntervalSec - itemWarnSec) * 20L;
                warnTask = Bukkit.getScheduler().runTaskTimer(plugin,
                        () -> Bukkit.broadcast(AlertManager.c("&eDropped items older than " + itemMinAgeSec + "s will be cleared in " + itemWarnSec + "s.")),
                        firstWarn, periodTicks);
            }
            itemTask = Bukkit.getScheduler().runTaskTimer(plugin, () -> clearOldItems(false), periodTicks, periodTicks);
        }
    }

    private void updateTps() {
        if (!throttleEnabled) {
            currentLevel = 0;
            return;
        }
        double tps = Bukkit.getTPS()[0];
        if (tps < overloadedTps) currentLevel = 2;
        else if (tps < busyTps) currentLevel = 1;
        else currentLevel = 0;
    }

    public int level() {
        return currentLevel;
    }

    public int multiplier() {
        return switch (currentLevel) {
            case 2 -> 4;
            case 1 -> 2;
            default -> 1;
        };
    }

    public int clearOldItems(boolean announce) {
        int minTicks = itemMinAgeSec * 20;
        int removed = 0;
        for (World w : Bukkit.getWorlds()) {
            for (Item item : w.getEntitiesByClass(Item.class)) {
                if (!item.isValid()) continue;
                if (item.getTicksLived() < minTicks) continue;
                if (keepMaterials.contains(item.getItemStack().getType())) continue;
                item.remove();
                removed++;
            }
        }
        if (announce && removed > 0) {
            Bukkit.broadcast(AlertManager.c("&7Cleared &f" + removed + " &7old dropped item(s)."));
        }
        return removed;
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onCreatureSpawn(CreatureSpawnEvent e) {
        if (!entityLimitEnabled) return;
        CreatureSpawnEvent.SpawnReason r = e.getSpawnReason();
        if (r != CreatureSpawnEvent.SpawnReason.NATURAL
                && r != CreatureSpawnEvent.SpawnReason.SPAWNER
                && r != CreatureSpawnEvent.SpawnReason.BREEDING
                && r != CreatureSpawnEvent.SpawnReason.EGG
                && r != CreatureSpawnEvent.SpawnReason.DISPENSE_EGG
                && r != CreatureSpawnEvent.SpawnReason.SPAWNER_EGG) {
            return;
        }

        Chunk chunk = e.getLocation().getChunk();
        EntityType type = e.getEntityType();
        int count = 0;
        for (Entity ent : chunk.getEntities()) {
            if (ent.getType() == type) {
                count++;
                if (count >= maxSameTypePerChunk) {
                    e.setCancelled(true);
                    return;
                }
            }
        }
    }

    public boolean command(CommandSender sender, String label, String[] args) {
        if (args.length > 1 && args[1].equalsIgnoreCase("clear")) {
            int removed = clearOldItems(false);
            sender.sendMessage(ChatColor.GREEN + "Cleared " + removed + " dropped item(s) older than " + itemMinAgeSec + "s.");
            return true;
        }

        double[] tps = Bukkit.getTPS();
        Runtime rt = Runtime.getRuntime();
        long usedMb = (rt.totalMemory() - rt.freeMemory()) / (1024 * 1024);
        long maxMb = rt.maxMemory() / (1024 * 1024);

        int chunks = 0;
        int entities = 0;
        for (World w : Bukkit.getWorlds()) {
            chunks += w.getLoadedChunks().length;
            entities += w.getEntityCount();
        }

        String state = switch (currentLevel) {
            case 2 -> ChatColor.RED + "OVERLOADED (x4 throttle, entity hiding paused)";
            case 1 -> ChatColor.YELLOW + "BUSY (x2 throttle)";
            default -> ChatColor.GREEN + "NORMAL (x1)";
        };

        sender.sendMessage(ChatColor.GOLD + "=== MeteorGuard Performance ===");
        sender.sendMessage(ChatColor.GRAY + "TPS (1m/5m/15m): " + ChatColor.WHITE
                + String.format(Locale.US, "%.2f, %.2f, %.2f", Math.min(20.0, tps[0]), Math.min(20.0, tps[1]), Math.min(20.0, tps[2])));
        sender.sendMessage(ChatColor.GRAY + "Lag state: " + state);
        sender.sendMessage(ChatColor.GRAY + "Memory: " + ChatColor.WHITE + usedMb + "MB / " + maxMb + "MB"
                + ChatColor.GRAY + " | Chunks: " + ChatColor.WHITE + chunks
                + ChatColor.GRAY + " | Entities: " + ChatColor.WHITE + entities);
        sender.sendMessage(ChatColor.GRAY + "Chunk mob cap: " + (entityLimitEnabled ? ChatColor.GREEN + "ON (" + maxSameTypePerChunk + ")" : ChatColor.RED + "OFF")
                + ChatColor.GRAY + " | Timed item clear: " + (itemClearEnabled ? ChatColor.GREEN + "ON" : ChatColor.RED + "OFF"));
        sender.sendMessage(ChatColor.DARK_GRAY + "Run /" + label + " perf clear to remove old dropped items now.");
        return true;
    }
}
