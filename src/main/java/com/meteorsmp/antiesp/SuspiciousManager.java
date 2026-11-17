package com.meteorsmp.antiesp;

import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Persistent log of players flagged for suspicious rare-ore mining.
 */
public final class SuspiciousManager {

    public static final class SuspiciousEntry {
        public final UUID uuid;
        public String name;
        public int flags;
        public int totalOres;
        public int totalBase;
        public int hiddenOres;
        public final Map<String, Integer> oreBreakdown = new LinkedHashMap<>();
        public String lastReason;
        public String lastWorld;
        public int lastX;
        public int lastY;
        public int lastZ;
        public long firstFlagged;
        public long lastFlagged;

        public SuspiciousEntry(UUID uuid, String name) {
            this.uuid = uuid;
            this.name = name;
        }
    }

    private final AntiESPPlugin plugin;
    private final Map<UUID, SuspiciousEntry> entries = new LinkedHashMap<>();
    private final File file;

    public SuspiciousManager(AntiESPPlugin plugin) {
        this.plugin = plugin;
        this.file = new File(plugin.getDataFolder(), "data/suspicious.yml");
    }

    public void load() {
        entries.clear();
        if (!file.exists()) return;
        YamlConfiguration yml = YamlConfiguration.loadConfiguration(file);
        ConfigurationSection root = yml.getConfigurationSection("players");
        if (root == null) return;

        for (String key : root.getKeys(false)) {
            try {
                UUID uuid = UUID.fromString(key);
                ConfigurationSection sec = root.getConfigurationSection(key);
                if (sec == null) continue;
                SuspiciousEntry entry = new SuspiciousEntry(uuid, sec.getString("name", key));
                entry.flags = sec.getInt("flags", 1);
                entry.totalOres = sec.getInt("total-ores", 0);
                entry.totalBase = sec.getInt("total-base", 0);
                entry.hiddenOres = sec.getInt("hidden-ores", 0);
                entry.lastReason = sec.getString("last-reason", "");
                entry.lastWorld = sec.getString("last-world", "world");
                entry.lastX = sec.getInt("last-x", 0);
                entry.lastY = sec.getInt("last-y", 0);
                entry.lastZ = sec.getInt("last-z", 0);
                entry.firstFlagged = sec.getLong("first-flagged", 0L);
                entry.lastFlagged = sec.getLong("last-flagged", 0L);

                ConfigurationSection oresSec = sec.getConfigurationSection("ores");
                if (oresSec != null) {
                    for (String mat : oresSec.getKeys(false)) {
                        entry.oreBreakdown.put(mat, oresSec.getInt(mat, 0));
                    }
                }
                entries.put(uuid, entry);
            } catch (IllegalArgumentException ignored) {
            }
        }
        // housekeeping: forget players who were not flagged again for a long time
        int days = plugin.getConfig().getInt("suspicious.expire-days", 30);
        if (days > 0) {
            long cutoff = System.currentTimeMillis() - days * 86_400_000L;
            if (entries.values().removeIf(en -> en.lastFlagged > 0 && en.lastFlagged < cutoff)) save();
        }
    }

    public void save() {
        File parent = file.getParentFile();
        if (parent != null && !parent.exists()) {
            parent.mkdirs();
        }
        YamlConfiguration yml = new YamlConfiguration();
        for (SuspiciousEntry e : entries.values()) {
            String path = "players." + e.uuid + ".";
            yml.set(path + "name", e.name);
            yml.set(path + "flags", e.flags);
            yml.set(path + "total-ores", e.totalOres);
            yml.set(path + "total-base", e.totalBase);
            yml.set(path + "hidden-ores", e.hiddenOres);
            yml.set(path + "last-reason", e.lastReason);
            yml.set(path + "last-world", e.lastWorld);
            yml.set(path + "last-x", e.lastX);
            yml.set(path + "last-y", e.lastY);
            yml.set(path + "last-z", e.lastZ);
            yml.set(path + "first-flagged", e.firstFlagged);
            yml.set(path + "last-flagged", e.lastFlagged);
            for (Map.Entry<String, Integer> ore : e.oreBreakdown.entrySet()) {
                yml.set(path + "ores." + ore.getKey(), ore.getValue());
            }
        }
        try {
            yml.save(file);
        } catch (IOException ex) {
            plugin.getLogger().warning("Failed to save suspicious.yml: " + ex.getMessage());
        }
    }

    public void record(Player player, String reason, int ores, int base, Map<Material, Integer> sessionOres, Location loc) {
        record(player, reason, ores, base, sessionOres, loc, 0);
    }

    public void record(Player player, String reason, int ores, int base, Map<Material, Integer> sessionOres,
                       Location loc, int hiddenOres) {
        long now = System.currentTimeMillis();
        SuspiciousEntry entry = entries.computeIfAbsent(player.getUniqueId(), k -> {
            SuspiciousEntry created = new SuspiciousEntry(k, player.getName());
            created.firstFlagged = now;
            return created;
        });

        entry.name = player.getName();
        entry.flags++;
        entry.totalOres = Math.max(entry.totalOres, ores);
        entry.totalBase = Math.max(entry.totalBase, base);
        entry.hiddenOres = Math.max(entry.hiddenOres, hiddenOres);
        entry.lastReason = reason;
        entry.lastFlagged = now;
        if (loc != null && loc.getWorld() != null) {
            entry.lastWorld = loc.getWorld().getName();
            entry.lastX = loc.getBlockX();
            entry.lastY = loc.getBlockY();
            entry.lastZ = loc.getBlockZ();
        }
        for (Map.Entry<Material, Integer> en : sessionOres.entrySet()) {
            String key = en.getKey().name();
            entry.oreBreakdown.put(key, Math.max(entry.oreBreakdown.getOrDefault(key, 0), en.getValue()));
        }
        save();
    }

    public List<SuspiciousEntry> getEntriesSorted() {
        return getEntriesSorted(0);
    }

    /** mode: 0 = most recent, 1 = most flags, 2 = most rare ores, 3 = highest severity. */
    public List<SuspiciousEntry> getEntriesSorted(int mode) {
        List<SuspiciousEntry> list = new ArrayList<>(entries.values());
        if (mode == 1) list.sort(Comparator.comparingInt((SuspiciousEntry e) -> e.flags).reversed());
        else if (mode == 2) list.sort(Comparator.comparingInt((SuspiciousEntry e) -> e.totalOres).reversed());
        else if (mode == 3) list.sort(Comparator.comparingInt((SuspiciousEntry e) -> score(e)).reversed());
        else list.sort(Comparator.comparingLong((SuspiciousEntry e) -> e.lastFlagged).reversed());
        return list;
    }

    /** Simple severity score: repeated flags, many rare ores and ores found inside solid rock all raise it. */
    public static int score(SuspiciousEntry e) {
        return e.flags * 2 + e.totalOres / 5 + e.hiddenOres * 3;
    }

    public static String level(int score) {
        if (score >= 25) return "HIGH";
        if (score >= 10) return "MEDIUM";
        return "LOW";
    }

    public boolean removeByName(String name) {
        for (SuspiciousEntry e : new ArrayList<>(entries.values())) {
            if (e.name != null && e.name.equalsIgnoreCase(name)) {
                remove(e.uuid);
                return true;
            }
        }
        return false;
    }

    public SuspiciousEntry getEntry(UUID uuid) {
        return entries.get(uuid);
    }

    public void remove(UUID uuid) {
        if (entries.remove(uuid) != null) {
            save();
        }
    }

    public int count() {
        return entries.size();
    }
}
