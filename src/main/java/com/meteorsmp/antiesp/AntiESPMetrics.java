package com.meteorsmp.antiesp;

import org.bukkit.plugin.java.JavaPlugin;

public final class AntiESPMetrics {
    private static final int PLUGIN_ID = 0; // Replace with your actual bStats ID when you get one
    private final org.bstats.bukkit.Metrics metrics;

    public AntiESPMetrics(JavaPlugin plugin) {
        if (PLUGIN_ID > 0) {
            this.metrics = new org.bstats.bukkit.Metrics(plugin, PLUGIN_ID);
        } else {
            this.metrics = null;
        }
    }

    public boolean isEnabled() {
        return metrics != null;
    }
}
