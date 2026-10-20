package com.meteorsmp.antiesp;

import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.PluginCommand;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerChangedWorldEvent;
import org.bukkit.event.player.PlayerGameModeChangeEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;
import org.bukkit.util.BoundingBox;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Hides players from each other when there is no line of sight (walls / terrain in between),
 * measured from the observer's REAL server-side eye position. That also defeats freecam
 * and ESP mods for player-tracking. Everything runs on the main thread.
 */
public final class AntiESPPlugin extends JavaPlugin implements CommandExecutor, TabCompleter, Listener {

    private final Set<UUID> bypass = new HashSet<>();
    // observer -> targets THIS plugin has hidden from them
    private final Map<UUID, Set<UUID>> hidden = new HashMap<>();
    private final ArrayDeque<UUID> queue = new ArrayDeque<>();
    private BukkitTask task;
    private int tickCounter = 0;
    private boolean enabled = true;

    private int checkInterval;
    private int maxPairsPerTick;
    private double maxDist;
    private double minDist;
    private boolean thorough;
    private boolean ignoreSpectators;
    private Set<String> enabledWorlds = new HashSet<>();
    private final Set<Material> alwaysOcclude = EnumSet.noneOf(Material.class);
    private final Set<Material> neverOcclude = EnumSet.noneOf(Material.class);

    @Override
    public void onEnable() {
        saveDefaultConfig();
        loadSettings();

        getServer().getPluginManager().registerEvents(this, this);

        PluginCommand cmd = getCommand("antifreecam");
        if (cmd != null) {
            cmd.setExecutor(this);
            cmd.setTabCompleter(this);
        } else {
            getLogger().severe("Command 'antifreecam' is missing from plugin.yml!");
        }

        task = Bukkit.getScheduler().runTaskTimer(this, this::tick, 20L, 1L);
        getLogger().info(color(getConfig().getString("messages.plugin-enabled", "&aMeteorAntiESP has been enabled!")));
    }

    @Override
    public void onDisable() {
        if (task != null) task.cancel();
        for (Player p : Bukkit.getOnlinePlayers()) revealAll(p);
        hidden.clear();
        queue.clear();
        bypass.clear();
    }

    private void loadSettings() {
        reloadConfig();
        checkInterval = Math.max(1, getConfig().getInt("check-interval", 2));
        maxPairsPerTick = Math.max(20, getConfig().getInt("max-pairs-per-tick", 300));
        maxDist = Math.max(8.0, getConfig().getDouble("max-distance", 128.0));
        minDist = Math.max(0.0, getConfig().getDouble("min-distance", 6.0));
        thorough = getConfig().getBoolean("thorough-check", false);
        ignoreSpectators = getConfig().getBoolean("ignore-spectators", true);
        enabledWorlds = new HashSet<>(getConfig().getStringList("enabled-worlds"));
        loadMaterials("occlusion.always-occlude", alwaysOcclude);
        loadMaterials("occlusion.never-occlude", neverOcclude);
    }

    private void loadMaterials(String path, Set<Material> out) {
        out.clear();
        for (String s : getConfig().getStringList(path)) {
            Material m = Material.matchMaterial(s);
            if (m != null) out.add(m);
            else getLogger().warning("Unknown material in " + path + ": " + s);
        }
    }

    private String color(String s) {
        return ChatColor.translateAlternateColorCodes('&', s);
    }

    private String msg(String key, String def) {
        return color(getConfig().getString("messages." + key, def));
    }

    // ------------------------------------------------------------------
    // Main loop (spread over ticks so it never lags the server)
    // ------------------------------------------------------------------

    private void tick() {
        if (!enabled) return;

        if (queue.isEmpty()) {
            tickCounter++;
            if (tickCounter < checkInterval) return;
            tickCounter = 0;
            for (Player p : Bukkit.getOnlinePlayers()) queue.add(p.getUniqueId());
        }

        int budget = maxPairsPerTick;
        while (!queue.isEmpty() && budget > 0) {
            Player observer = Bukkit.getPlayer(queue.poll());
            if (observer == null || !observer.isOnline()) continue;
            budget -= processObserver(observer);
        }
    }

    /** Returns how many pairs were checked (used as the lag budget). */
    private int processObserver(Player observer) {
        UUID oid = observer.getUniqueId();
        World world = observer.getWorld();

        if (isExempt(observer, world)) {
            revealAll(observer);
            return 1;
        }

        int cost = 1;
        Location eye = observer.getEyeLocation();

        for (Player target : world.getPlayers()) {
            if (target.getUniqueId().equals(oid)) continue;
            cost++;

            UUID tid = target.getUniqueId();
            Set<UUID> mine = hidden.get(oid);
            boolean weHid = mine != null && mine.contains(tid);

            // Someone else (vanish, etc.) already hides this player: leave it alone.
            if (!weHid && !observer.canSee(target)) continue;

            boolean shouldHide = shouldHide(eye, target);

            if (shouldHide && !weHid) {
                observer.hideEntity(this, target);
                hidden.computeIfAbsent(oid, k -> new HashSet<>()).add(tid);
            } else if (!shouldHide && weHid) {
                observer.showEntity(this, target);
                mine.remove(tid);
            }
        }
        return cost;
    }

    private boolean isExempt(Player observer, World world) {
        if (bypass.contains(observer.getUniqueId())) return true;
        if (observer.hasPermission("antiesp.bypass") || observer.hasPermission("antifreecam.bypass")) return true;
        if (ignoreSpectators && observer.getGameMode() == GameMode.SPECTATOR) return true;
        return !enabledWorlds.isEmpty() && !enabledWorlds.contains(world.getName());
    }

    // ------------------------------------------------------------------
    // Visibility
    // ------------------------------------------------------------------

    private boolean shouldHide(Location eye, Player target) {
        if (target.getGameMode() == GameMode.SPECTATOR) return false;

        double d2 = eye.distanceSquared(target.getLocation());
        if (d2 < minDist * minDist) return false;      // very close = always visible
        if (d2 > maxDist * maxDist) return false;      // too far = not checked

        World world = eye.getWorld();
        BoundingBox box = target.getBoundingBox();
        double cx = (box.getMinX() + box.getMaxX()) / 2.0;
        double cz = (box.getMinZ() + box.getMaxZ()) / 2.0;
        double low = box.getMinY() + 0.15;
        double mid = (box.getMinY() + box.getMaxY()) / 2.0;
        double high = box.getMaxY() - 0.1;

        double ex = eye.getX(), ey = eye.getY(), ez = eye.getZ();

        // visible as soon as ONE sample point has a clear line
        if (!isLineBlocked(world, ex, ey, ez, cx, high, cz)) return false;
        if (!isLineBlocked(world, ex, ey, ez, cx, mid, cz)) return false;
        if (!isLineBlocked(world, ex, ey, ez, cx, low, cz)) return false;

        if (thorough) {
            double[] xs = {box.getMinX() + 0.05, box.getMaxX() - 0.05};
            double[] zs = {box.getMinZ() + 0.05, box.getMaxZ() - 0.05};
            for (double x : xs) {
                for (double z : zs) {
                    if (!isLineBlocked(world, ex, ey, ez, x, high, z)) return false;
                    if (!isLineBlocked(world, ex, ey, ez, x, low, z)) return false;
                }
            }
        }
        return true;
    }

    /** Voxel walk (Amanatides-Woo): checks EVERY block between the two points, no skipping. */
    private boolean isLineBlocked(World world, double sx, double sy, double sz, double ex, double ey, double ez) {
        double dx = ex - sx, dy = ey - sy, dz = ez - sz;
        int x = floor(sx), y = floor(sy), z = floor(sz);
        int endX = floor(ex), endY = floor(ey), endZ = floor(ez);
        if (x == endX && y == endY && z == endZ) return false;

        int stepX = dx > 0 ? 1 : (dx < 0 ? -1 : 0);
        int stepY = dy > 0 ? 1 : (dy < 0 ? -1 : 0);
        int stepZ = dz > 0 ? 1 : (dz < 0 ? -1 : 0);

        double tDeltaX = stepX != 0 ? Math.abs(1.0 / dx) : Double.POSITIVE_INFINITY;
        double tDeltaY = stepY != 0 ? Math.abs(1.0 / dy) : Double.POSITIVE_INFINITY;
        double tDeltaZ = stepZ != 0 ? Math.abs(1.0 / dz) : Double.POSITIVE_INFINITY;

        double tMaxX = stepX > 0 ? (x + 1 - sx) * tDeltaX : (stepX < 0 ? (sx - x) * tDeltaX : Double.POSITIVE_INFINITY);
        double tMaxY = stepY > 0 ? (y + 1 - sy) * tDeltaY : (stepY < 0 ? (sy - y) * tDeltaY : Double.POSITIVE_INFINITY);
        double tMaxZ = stepZ > 0 ? (z + 1 - sz) * tDeltaZ : (stepZ < 0 ? (sz - z) * tDeltaZ : Double.POSITIVE_INFINITY);

        int maxSteps = (int) (Math.abs(dx) + Math.abs(dy) + Math.abs(dz)) + 3;
        for (int i = 0; i < maxSteps; i++) {
            if (tMaxX < tMaxY && tMaxX < tMaxZ) {
                x += stepX;
                tMaxX += tDeltaX;
            } else if (tMaxY < tMaxZ) {
                y += stepY;
                tMaxY += tDeltaY;
            } else {
                z += stepZ;
                tMaxZ += tDeltaZ;
            }
            if (x == endX && y == endY && z == endZ) return false;
            if (occludes(world, x, y, z)) return true;
        }
        return false;
    }

    private boolean occludes(World world, int x, int y, int z) {
        if (y < world.getMinHeight() || y >= world.getMaxHeight()) return false;
        if (!world.isChunkLoaded(x >> 4, z >> 4)) return false;   // never force-load chunks
        Material m = world.getBlockAt(x, y, z).getType();
        if (neverOcclude.contains(m)) return false;
        return alwaysOcclude.contains(m) || m.isOccluding();
    }

    private static int floor(double d) {
        return (int) Math.floor(d);
    }

    // ------------------------------------------------------------------
    // Cleanup
    // ------------------------------------------------------------------

    private void revealAll(Player observer) {
        Set<UUID> mine = hidden.remove(observer.getUniqueId());
        if (mine == null) return;
        for (UUID tid : mine) {
            Player t = Bukkit.getPlayer(tid);
            if (t != null) observer.showEntity(this, t);
        }
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent e) {
        UUID id = e.getPlayer().getUniqueId();
        hidden.remove(id);
        bypass.remove(id);
        for (Set<UUID> set : hidden.values()) set.remove(id);
    }

    @EventHandler
    public void onWorldChange(PlayerChangedWorldEvent e) {
        revealAll(e.getPlayer());
    }

    @EventHandler
    public void onGameMode(PlayerGameModeChangeEvent e) {
        if (e.getNewGameMode() == GameMode.SPECTATOR) revealAll(e.getPlayer());
    }

    // ------------------------------------------------------------------
    // Commands
    // ------------------------------------------------------------------

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!sender.hasPermission("antifreecam.admin")) {
            sender.sendMessage(msg("no-permission", "&cYou do not have permission!"));
            return true;
        }

        String sub = args.length > 0 ? args[0].toLowerCase(Locale.ROOT) : "";
        switch (sub) {
            case "reload":
                loadSettings();
                sender.sendMessage(msg("config-reloaded", "&aConfiguration reloaded!"));
                return true;

            case "toggle":
                enabled = !enabled;
                if (!enabled) {
                    for (Player p : Bukkit.getOnlinePlayers()) revealAll(p);
                }
                sender.sendMessage(color(enabled ? "&aAntiESP is now &lENABLED&a." : "&cAntiESP is now &lDISABLED&c."));
                return true;

            case "bypass": {
                Player target = (sender instanceof Player) ? (Player) sender : null;
                if (args.length > 1) target = Bukkit.getPlayer(args[1]);
                if (target == null) {
                    sender.sendMessage(ChatColor.RED + "Player not found or specify a player name!");
                    return true;
                }
                UUID id = target.getUniqueId();
                if (bypass.remove(id)) {
                    target.sendMessage(msg("bypass-disabled", "&cBypass disabled."));
                } else {
                    bypass.add(id);
                    revealAll(target);
                    target.sendMessage(msg("bypass-enabled", "&aBypass enabled."));
                }
                return true;
            }

            case "status": {
                int pairs = 0;
                for (Set<UUID> s : hidden.values()) pairs += s.size();
                sender.sendMessage(color("&eAntiESP: " + (enabled ? "&aON" : "&cOFF")
                        + " &7| hidden pairs: &f" + pairs
                        + " &7| range: &f" + minDist + "-" + maxDist
                        + " &7| thorough: &f" + thorough));
                return true;
            }

            default:
                sender.sendMessage(ChatColor.YELLOW + "Usage: /antifreecam <reload|toggle|status|bypass [player]>");
                return true;
        }
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        List<String> out = new ArrayList<>();
        if (args.length == 1) {
            for (String s : new String[]{"reload", "toggle", "status", "bypass"}) {
                if (s.startsWith(args[0].toLowerCase(Locale.ROOT))) out.add(s);
            }
        } else if (args.length == 2 && args[0].equalsIgnoreCase("bypass")) {
            for (Player p : Bukkit.getOnlinePlayers()) {
                if (p.getName().toLowerCase(Locale.ROOT).startsWith(args[1].toLowerCase(Locale.ROOT))) out.add(p.getName());
            }
        }
        return out;
    }
}
