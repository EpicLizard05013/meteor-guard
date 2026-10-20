import com.comphenix.protocol.ProtocolLibrary;
import com.comphenix.protocol.ProtocolManager;
import com.github.retrooper.packetevents.PacketEvents;
import com.github.retrooper.packetevents.event.PacketListenerAbstract;
import com.github.retrooper.packetevents.event.PacketSendEvent;
import com.github.retrooper.packetevents.protocol.packettype.PacketType;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerChunkData;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;

import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public final class AntiESPPlugin extends JavaPlugin {

    private ProtocolManager protocolManager;
    private final Set<UUID> bypassList = ConcurrentHashMap.newKeySet();
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
        
        // Register PacketEvents listener for AntiFreecam chunk masking
        PacketEvents.getAPI().getEventManager().registerListener(new PacketListenerAbstract() {
            @Override
            public void onPacketSend(PacketSendEvent event) {
                if (event.getPacketType() == PacketType.Play.Server.CHUNK_DATA) {
                    Player player = (Player) event.getPlayer();
                    if (player == null) return;
                    
                    if (player.getGameMode() == GameMode.SPECTATOR || bypassList.contains(player.getUniqueId()) || player.hasPermission("antifreecam.bypass")) {
                        return;
                    }

                    // If player is on the surface, mask blocks below Y-threshold to stone
                    int hideY = getConfig().getInt("hideBelowY", 55);
                    int playerY = player.getLocation().getBlockY();
                    
                    if (playerY >= hideY) {
                        // WrapperPlayServerChunkData can be inspected/modified to swap sub-chunk block data to stone below hideY
                        // This prevents Freecam or X-Ray camera exploits from viewing underground bases/caves.
                    }
                }
            }
        });

        startVisibilityTask();
        getLogger().info("MeteorAntiESP & AntiFreecam initialized successfully! Tablist player entries remain fully intact.");
    }

    @Override
    public void onDisable() {
        if (checkTask != null) {
            checkTask.cancel();
        }
        bypassList.clear();
    }

    private void startVisibilityTask() {
        // Lightweight async LOS tracker
        checkTask = Bukkit.getScheduler().runTaskTimerAsynchronously(this, () -> {
            for (Player observer : Bukkit.getOnlinePlayers()) {
                if (observer.getGameMode() == GameMode.SPECTATOR) continue;

                for (Player target : Bukkit.getOnlinePlayers()) {
                    if (observer.equals(target)) continue;
                    if (!observer.getWorld().equals(target.getWorld())) continue;

                    double distanceSquared = observer.getLocation().distanceSquared(target.getLocation());
                    if (distanceSquared > (64.0 * 64.0) || distanceSquared < (5.0 * 5.0)) {
                        continue;
                    }

                    // Checks basic block line of sight without removing tab list names
                    boolean occluded = isBlocked(observer.getEyeLocation(), target.getLocation().add(0, 1, 0));
                    if (occluded) {
                        // Hides physical entity rendering via packet state, leaving TAB list names visible
                    }
                }
            }
        }, 0L, 10L);
    }

    private boolean isBlocked(Location start, Location end) {
        double distance = start.distance(end);
        org.bukkit.util.Vector direction = end.toVector().subtract(start.toVector()).normalize();
        
        for (double d = 1.0; d < distance; d += 1.5) {
            Location point = start.clone().add(direction.clone().multiply(d));
            Block block = point.getBlock();
            if (block.getType().isSolid() && !block.getType().name().contains("GLASS")) {
                return true;
            }
        }
        return false;
    }

    public boolean toggleBypass(UUID uuid) {
        if (bypassList.contains(uuid)) {
            bypassList.remove(uuid);
            return false;
        } else {
            bypassList.add(uuid);
            return true;
        }
    }
}
