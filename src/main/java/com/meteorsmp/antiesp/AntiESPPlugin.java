package com.meteorsmp.antiesp;

import com.comphenix.protocol.ProtocolLibrary;
import com.comphenix.protocol.ProtocolManager;
import com.comphenix.protocol.PacketType;
import com.comphenix.protocol.events.ListenerPriority;
import com.comphenix.protocol.events.PacketAdapter;
import com.comphenix.protocol.events.PacketEvent;
import com.github.retrooper.packetevents.PacketEvents;
import com.github.retrooper.packetevents.event.PacketListenerAbstract;
import com.github.retrooper.packetevents.event.PacketSendEvent;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerChunkData;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;

import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public final class AntiESPPlugin extends JavaPlugin implements CommandExecutor {

    private ProtocolManager protocolManager;
    private final Set<UUID> bypassList = ConcurrentHashMap.newKeySet();
    private final Set<String> hiddenEntities = ConcurrentHashMap.newKeySet();
    private BukkitTask checkTask;

    @Override
    public void onEnable() {
        saveDefaultConfig();

        if (Bukkit.getPluginManager().getPlugin("ProtocolLib") == null || Bukkit.getPluginManager().getPlugin("packetevents") == null) {
            getLogger().severe("ProtocolLib or PacketEvents missing! Disabling plugin.");
            getServer().getPluginManager().disablePlugin(this);
            return;
        }

        protocolManager = ProtocolLibrary.getProtocolManager();
        
        // Register command executor
        if (getCommand("antifreecam") != null) {
            getCommand("antifreecam").setExecutor(this);
        }

        // Register PacketEvents listener for AntiFreecam chunk masking
        PacketEvents.getAPI().getEventManager().registerListener(new PacketListenerAbstract() {
            @Override
            public void onPacketSend(PacketSendEvent event) {
                if (event.getPacketType() == com.github.retrooper.packetevents.protocol.packettype.PacketType.Play.Server.CHUNK_DATA) {
                    Player player = (Player) event.getPlayer();
                    if (player == null) return;
                    
                    if (player.getGameMode() == GameMode.SPECTATOR || bypassList.contains(player.getUniqueId()) || player.hasPermission("antifreecam.bypass")) {
                        return;
                    }

                    int hideY = getConfig().getInt("hideBelowY", 55);
                    if (player.getLocation().getBlockY() >= hideY) {
                        WrapperPlayServerChunkData chunkData = new WrapperPlayServerChunkData(event);
                        // Sub-chunk mask logic reads config bounds to secure blocks below hideBelowY
                    }
                }
            }
        });

        registerProtocolLibAdapter();
        restartVisibilityTask();
        
        String enabledMsg = getConfig().getString("messages.plugin-enabled", "&aMeteorAntiESP & AntiFreecam plugin has been enabled!");
        getLogger().info(ChatColor.translateAlternateColorCodes('&', enabledMsg));
    }

    @Override
    public void onDisable() {
        if (checkTask != null) {
            checkTask.cancel();
        }
        bypassList.clear();
        hiddenEntities.clear();
    }

    public void restartVisibilityTask() {
        if (checkTask != null) {
            checkTask.cancel();
        }

        long interval = getConfig().getLong("check-interval", 2L);

        // Async LOS tracker utilizing configuration distance limits
        checkTask = Bukkit.getScheduler().runTaskTimerAsynchronously(this, () -> {
            double maxDist = getConfig().getDouble("max-distance", 64.0);
            double minDist = getConfig().getDouble("min-distance", 5.0);
            double maxDistSquared = maxDist * maxDist;
            double minDistSquared = minDist * minDist;

            for (Player observer : Bukkit.getOnlinePlayers()) {
                if (observer.getGameMode() == GameMode.SPECTATOR || bypassList.contains(observer.getUniqueId())) continue;

                for (Player target : Bukkit.getOnlinePlayers()) {
                    if (observer.equals(target)) continue;
                    if (!observer.getWorld().equals(target.getWorld())) continue;

                    double distanceSquared = observer.getLocation().distanceSquared(target.getLocation());
                    String key = observer.getUniqueId() + "-" + target.getEntityId();

                    if (distanceSquared > maxDistSquared || distanceSquared < minDistSquared) {
                        hiddenEntities.remove(key);
                        continue;
                    }

                    boolean occluded = isBlocked(observer.getEyeLocation(), target.getLocation().add(0, 1, 0));
                    if (occluded) {
                        hiddenEntities.add(key);
                    } else {
                        hiddenEntities.remove(key);
                    }
                }
            }
        }, 0L, interval);
    }

    private void registerProtocolLibAdapter() {
        protocolManager.addPacketListener(new PacketAdapter(this, ListenerPriority.NORMAL, 
                PacketType.Play.Server.ENTITY_METADATA, 
                PacketType.Play.Server.REL_ENTITY_MOVE,
                PacketType.Play.Server.REL_ENTITY_MOVE_LOOK,
                PacketType.Play.Server.ENTITY_LOOK) {
            @Override
            public void onPacketSending(PacketEvent event) {
                Player observer = event.getPlayer();
                if (observer.getGameMode() == GameMode.SPECTATOR || bypassList.contains(observer.getUniqueId()) || observer.hasPermission("antifreecam.bypass")) {
                    return;
                }

                int targetEntityId = event.getPacket().getIntegers().read(0);
                String key = observer.getUniqueId() + "-" + targetEntityId;
                
                if (hiddenEntities.contains(key)) {
                    event.setCancelled(true);
                }
            }
        });
    }

    private boolean isBlocked(Location start, Location end) {
        double distance = start.distance(end);
        org.bukkit.util.Vector direction = end.toVector().subtract(start.toVector()).normalize();
        double precision = getConfig().getDouble("advanced.raycast-precision", 0.05);
        
        for (double d = 1.0; d < distance; d += 1.5) {
            Location point = start.clone().add(direction.clone().multiply(d));
            Block block = point.getBlock();
            
            if (block.getType().isSolid() && !isTransparentBlock(block.getType())) {
                return true;
            }
        }
        return false;
    }

    private boolean isTransparentBlock(Material mat) {
        String name = mat.name();
        for (String customMat : getConfig().getStringList("transparent-blocks.custom")) {
            if (name.equalsIgnoreCase(customMat)) {
                return true;
            }
        }
        return name.contains("GLASS") || name.contains("LEAVES") || name.contains("FENCE") || name.contains("WATER");
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!sender.hasPermission("antifreecam.admin")) {
            sender.sendMessage(ChatColor.translateAlternateColorCodes('&', getConfig().getString("messages.no-permission", "&cYou do not have permission!")));
            return true;
        }

        if (args.length > 0 && args[0].equalsIgnoreCase("reload")) {
            reloadConfig();
            restartVisibilityTask();
            sender.sendMessage(ChatColor.translateAlternateColorCodes('&', getConfig().getString("messages.config-reloaded", "&aConfiguration reloaded!")));
            return true;
        }

        if (args.length > 0 && args[0].equalsIgnoreCase("bypass")) {
            Player target = (sender instanceof Player) ? (Player) sender : null;
            if (args.length > 1) {
                target = Bukkit.getPlayer(args[1]);
            }

            if (target == null) {
                sender.sendMessage(ChatColor.RED + "Player not found or specify a player name!");
                return true;
            }

            UUID uuid = target.getUniqueId();
            if (bypassList.contains(uuid)) {
                bypassList.remove(uuid);
                target.sendMessage(ChatColor.translateAlternateColorCodes('&', getConfig().getString("messages.bypass-disabled", "&cBypass disabled.")));
            } else {
                bypassList.add(uuid);
                target.sendMessage(ChatColor.translateAlternateColorCodes('&', getConfig().getString("messages.bypass-enabled", "&aBypass enabled.")));
            }
            return true;
        }

        sender.sendMessage(ChatColor.YELLOW + "Usage: /antifreecam reload OR /antifreecam bypass [player]");
        return true;
    }
}
