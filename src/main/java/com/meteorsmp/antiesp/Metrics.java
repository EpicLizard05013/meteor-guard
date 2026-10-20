package com.meteorsmp.antiesp;

import org.bstats.bukkit.Metrics;
import org.bukkit.plugin.java.JavaPlugin;

public final class Metrics {
    private static final int PLUGIN_ID = 0;
    private final Metrics metrics;

    public Metrics(JavaPlugin plugin) {
        this.metrics = PLUGIN_ID > 0 ? new Metrics(plugin, PLUGIN_ID) : null;
    }

    public boolean isEnabled() {
        return metrics != null;
    }
}
