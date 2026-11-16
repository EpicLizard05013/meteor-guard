package com.meteorsmp.antiesp;

import org.bstats.bukkit.Metrics;
import org.bukkit.plugin.java.JavaPlugin;

public class AntiESPMetrics {

    // Replace with your real bStats plugin ID from bstats.org if you registered one
    private static final int PLUGIN_ID = 23456;
    private Metrics metrics;

    public AntiESPMetrics(JavaPlugin plugin) {
        try {
            this.metrics = new Metrics(plugin, PLUGIN_ID);
        } catch (Exception e) {
            plugin.getLogger().warning("Could not initialize bStats metrics: " + e.getMessage());
        }
    }
}
