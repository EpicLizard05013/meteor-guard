package com.meteorsmp.antiesp;

import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.plugin.Plugin;

import java.util.Set;

public class StorageHider implements Listener {

    private final AntiESPPlugin plugin;
    private final Set<Material> protectedContainers = Set.of(
            Material.CHEST, Material.TRAPPED_CHEST, Material.ENDER_CHEST,
            Material.SHULKER_BOX, Material.WHITE_SHULKER_BOX, Material.ORANGE_SHULKER_BOX,
            Material.MAGENTA_SHULKER_BOX, Material.LIGHT_BLUE_SHULKER_BOX, Material.YELLOW_SHULKER_BOX,
            Material.LIME_SHULKER_BOX, Material.PINK_SHULKER_BOX, Material.GRAY_SHULKER_BOX,
            Material.LIGHT_GRAY_SHULKER_BOX, Material.CYAN_SHULKER_BOX, Material.PURPLE_SHULKER_BOX,
            Material.BLUE_SHULKER_BOX, Material.BROWN_SHULKER_BOX, Material.GREEN_SHULKER_BOX,
            Material.RED_SHULKER_BOX, Material.BLACK_SHULKER_BOX, Material.SPAWNER,
            Material.HOPPER, Material.BARREL, Material.DROPPER, Material.DISPENSER
    );

    public StorageHider(AntiESPPlugin plugin) {
        this.plugin = plugin;
        Bukkit.getPluginManager().registerEvents(this, plugin);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBlockPlace(BlockPlaceEvent event) {
        Block block = event.getBlock();
        if (protectedContainers.contains(block.getType())) {
            // Log or track container placement for anti-ESP validation if needed
        }
    }

    public boolean shouldHideContainer(Player player, Block block) {
        if (!protectedContainers.contains(block.getType())) {
            return false;
        }
        // If player is within 4 blocks or looking directly at it within line of sight, allow packet
        double distanceSquared = player.getLocation().distanceSquared(block.getLocation());
        if (distanceSquared <= 16.0) {
            return false;
        }
        return !player.hasLineOfSight(block);
    }
}
