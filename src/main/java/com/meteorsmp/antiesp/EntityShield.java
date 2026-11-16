package com.meteorsmp.antiesp;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Entity;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerChangedWorldEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.scheduler.BukkitTask;

import java.util.EnumSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Hides stash entities (items, item frames, chest minecarts, armor stands) behind walls
 * and hides non-player entities inside AntiFreecam masked layers.
 */
public final class EntityShield implements Listener {

    private final AntiESPPlugin plugin;
    private final DepthMask depthMask;

    private final Map<UUID, Set<UUID>> hidden = new HashMap<>();
    private final Set<EntityType> types = EnumSet.noneOf(EntityType.class);

    private BukkitTask task;
    private boolean enabled;
    private double range = 64.0;
    private double range2 = 64.0 * 64.0;
    private double minDist2 = 10.0 * 10.0;
    private int checkInterval = 10;
    private int maxPerPlayer = 400;

    public EntityShield(AntiESPPlugin plugin, DepthMask depthMask) {
        this.plugin = plugin;
        this.depthMask = depthMask;
    }

    public void start() {
        reload();
        Bukkit.getPluginManager().registerEvents(this, plugin);
    }

    public void stop() {
        if (task != null) {
            task.cancel();
            task = null;
        }
        for (Player p : Bukkit.getOnlinePlayers()) {
            revealAll(p);
        }
        hidden.clear();
    }

    public void reload() {
        var c = plugin.getConfig();
        String p = "entity-hiding.";
        enabled = c.getBoolean(p + "enabled", true);
        range = Math.max(8.0, c.getDouble(p + "range", 64.0));
        range2 = range * range;
        double minDist = Math.max(0.0, c.getDouble(p + "min-distance", 10.0));
        minDist2 = minDist * minDist;
        checkInterval = Math.max(2, c.getInt(p + "check-interval", 10));
        maxPerPlayer = Math.max(20, c.getInt(p + "max-entities-per-player", 400));

        types.clear();
        for (String s : c.getStringList(p + "types")) {
            String name = s.trim().toUpperCase(Locale.ROOT);
            if (name.equals("DROPPED_ITEM")) name = "ITEM";
            try {
                types.add(EntityType.valueOf(name));
            } catch (IllegalArgumentException ex) {
                plugin.getLogger().warning("Unknown entity type in entity-hiding.types: " + s);
            }
        }

        if (task != null) {
            task.cancel();
            task = null;
        }
        task = Bukkit.getScheduler().runTaskTimer(plugin, this::tick, 30L, checkInterval);
    }

    public int hiddenCount() {
        int total = 0;
        for (Set<UUID> set : hidden.values()) {
            total += set.size();
        }
        return total;
    }

    private void tick() {
        boolean freecamEntities = depthMask != null && depthMask.isHideEntities();
        boolean stashEnabled = enabled && plugin.isActive() && plugin.lagLevel() < 2;

        if (!freecamEntities && !stashEnabled) {
            for (Player p : Bukkit.getOnlinePlayers()) {
                revealAll(p);
            }
            return;
        }

        for (Player observer : Bukkit.getOnlinePlayers()) {
            if (!observer.isOnline() || plugin.hasBypass(observer)) {
                revealAll(observer);
                continue;
            }
            processObserver(observer, freecamEntities, stashEnabled);
        }
    }

    private void processObserver(Player observer, boolean freecamEntities, boolean stashEnabled) {
        UUID oid = observer.getUniqueId();
        World world = observer.getWorld();
        boolean worldEsp = stashEnabled && plugin.isEspWorld(world);

        Location eye = observer.getEyeLocation();
        double hRange = Math.max(range, 96.0);
        double vRange = freecamEntities ? 320.0 : range;

        Set<UUID> mine = hidden.computeIfAbsent(oid, k -> new HashSet<>());
        Set<UUID> keepHidden = new HashSet<>();
        int stashChecked = 0;

        for (Entity entity : world.getNearbyEntities(eye, hRange, vRange, hRange)) {
            if (entity instanceof Player || !entity.isValid()) continue;

            Location eloc = entity.getLocation();
            boolean hideFreecam = freecamEntities && depthMask.isMaskedAt(observer, eloc.getBlockY());

            boolean hideStash = false;
            if (!hideFreecam && worldEsp && types.contains(entity.getType()) && stashChecked < maxPerPlayer) {
                double d2 = eye.distanceSquared(eloc);
                if (d2 >= minDist2 && d2 <= range2) {
                    stashChecked++;
                    hideStash = plugin.losBlocked(eye, eloc);
                }
            }

            if (hideFreecam || hideStash) {
                UUID eid = entity.getUniqueId();
                keepHidden.add(eid);
                if (mine.add(eid)) {
                    observer.hideEntity(plugin, entity);
                }
            }
        }

        Iterator<UUID> it = mine.iterator();
        while (it.hasNext()) {
            UUID eid = it.next();
            if (keepHidden.contains(eid)) continue;
            it.remove();
            Entity e = Bukkit.getEntity(eid);
            if (e != null && e.isValid() && e.getWorld().equals(world)) {
                observer.showEntity(plugin, e);
            }
        }

        if (mine.isEmpty()) {
            hidden.remove(oid);
        }
    }

    private void revealAll(Player observer) {
        Set<UUID> mine = hidden.remove(observer.getUniqueId());
        if (mine == null) return;
        for (UUID eid : mine) {
            Entity e = Bukkit.getEntity(eid);
            if (e != null && e.isValid() && e.getWorld().equals(observer.getWorld())) {
                observer.showEntity(plugin, e);
            }
        }
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent e) {
        hidden.remove(e.getPlayer().getUniqueId());
    }

    @EventHandler
    public void onWorldChange(PlayerChangedWorldEvent e) {
        hidden.remove(e.getPlayer().getUniqueId());
    }
}
