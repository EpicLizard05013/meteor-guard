package com.meteorsmp.antiesp;

import org.bukkit.Material;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.EnumSet;
import java.util.HashSet;
import java.util.Set;

public class ConfigManager {
    private final JavaPlugin plugin;
    private boolean debug;
    private boolean teamExempt;
    private int checkInterval;
    private int maxPairsPerTick;
    private double maxDist;
    private double minDist;
    private boolean thorough;
    private boolean ignoreSpectators;
    private Set<String> enabledWorlds = new HashSet<>();
    private final Set<Material> alwaysOcclude = EnumSet.noneOf(Material.class);
    private final Set<Material> neverOcclude = EnumSet.noneOf(Material.class);

    public ConfigManager(JavaPlugin plugin) {
        this.plugin = plugin;
        reload();
    }

    public void reload() {
        plugin.reloadConfig();
        FileConfiguration config = plugin.getConfig();

        debug = config.getBoolean("debug", false);
        teamExempt = config.getBoolean("team-exemption.enabled", true);
        checkInterval = Math.max(1, config.getInt("check-interval", 2));
        maxPairsPerTick = Math.max(20, config.getInt("max-pairs-per-tick", 300));
        maxDist = Math.max(8.0, config.getDouble("max-distance", 128.0));
        minDist = Math.max(0.0, config.getDouble("min-distance", 6.0));
        thorough = config.getBoolean("thorough-check", false);
        ignoreSpectators = config.getBoolean("ignore-spectators", true);
        enabledWorlds = new HashSet<>(config.getStringList("enabled-worlds"));
        loadMaterials("occlusion.always-occlude", alwaysOcclude);
        loadMaterials("occlusion.never-occlude", neverOcclude);
    }

    private void loadMaterials(String path, Set<Material> out) {
        out.clear();
        for (String value : plugin.getConfig().getStringList(path)) {
            Material material = Material.matchMaterial(value);
            if (material != null) {
                out.add(material);
            } else {
                plugin.getLogger().warning("Unknown material in " + path + ": " + value);
            }
        }
    }

    public boolean isDebug() {
        return debug;
    }

    public boolean isTeamExempt() {
        return teamExempt;
    }

    public int getCheckInterval() {
        return checkInterval;
    }

    public int getMaxPairsPerTick() {
        return maxPairsPerTick;
    }

    public double getMaxDist() {
        return maxDist;
    }

    public double getMinDist() {
        return minDist;
    }

    public boolean isThorough() {
        return thorough;
    }

    public boolean isIgnoreSpectators() {
        return ignoreSpectators;
    }

    public Set<String> getEnabledWorlds() {
        return enabledWorlds;
    }

    public Set<Material> getAlwaysOcclude() {
        return alwaysOcclude;
    }

    public Set<Material> getNeverOcclude() {
        return neverOcclude;
    }
}
