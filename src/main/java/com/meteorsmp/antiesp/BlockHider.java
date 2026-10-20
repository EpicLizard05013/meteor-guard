package com.meteorsmp.antiesp;

import io.papermc.paper.event.packet.PlayerChunkLoadEvent;
import io.papermc.paper.event.packet.PlayerChunkUnloadEvent;
import org.bukkit.Bukkit;
import org.bukkit.Chunk;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockState;
import org.bukkit.block.data.BlockData;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.player.PlayerChangedWorldEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.world.ChunkLoadEvent;
import org.bukkit.event.world.ChunkUnloadEvent;
import org.bukkit.scheduler.BukkitTask;

import java.util.Arrays;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Hides chests / spawners / containers from players who are far away by sending a fake block,
 * and restores the real block when they come close. ESP mods that draw boxes on block entities
 * then have nothing to draw.
 */
public final class BlockHider implements Listener {

    private final AntiESPPlugin plugin;

    // world -> chunk key -> packed positions of hideable blocks
    private final Map<UUID, Map<Long, long[]>> registry = new HashMap<>();
    // player -> chunk key -> positions currently hidden from that player
    private final Map<UUID, Map<Long, Set<Long>>> hidden = new HashMap<>();
    private final Set<Material> targets = EnumSet.noneOf(Material.class);

    private BukkitTask task;
    private boolean enabled;
    private boolean hideShulkers;
    private double reveal2;
    private int radius;
    private int interval;
    private int resendEvery;
    private int passCounter;
    private BlockData fake = Material.AIR.createBlockData();

    public BlockHider(AntiESPPlugin plugin) {
        this.plugin = plugin;
    }

    // ------------------------------------------------------------------
    // Lifecycle
    // ------------------------------------------------------------------

    public void start() {
        load();
        plugin.getServer().getPluginManager().registerEvents(this, plugin);
        scanAllLoaded();
        schedule();
    }

    public void stop() {
        if (task != null) {
            task.cancel();
            task = null;
        }
        for (Player p : Bukkit.getOnlinePlayers()) revealAll(p);
        hidden.clear();
        registry.clear();
    }

    public void reload() {
        load();
        if (task != null) {
            task.cancel();
            task = null;
        }
        if (!enabled) {
            for (Player p : Bukkit.getOnlinePlayers()) revealAll(p);
            return;
        }
        registry.clear();
        scanAllLoaded();
        schedule();
    }

    private void schedule() {
        task = Bukkit.getScheduler().runTaskTimer(plugin, this::tick, 40L, interval);
    }

    private void scanAllLoaded() {
        for (World w : Bukkit.getWorlds()) {
            for (Chunk c : w.getLoadedChunks()) scan(c);
        }
    }

    private void load() {
        FileConfiguration cfg = plugin.getConfig();
        enabled = cfg.getBoolean("block-hiding.enabled", true);
        double reveal = Math.max(6.0, cfg.getDouble("block-hiding.reveal-distance", 16.0));
        reveal2 = reveal * reveal;
        radius = Math.max(2, cfg.getInt("block-hiding.scan-radius-chunks", 8));
        interval = Math.max(2, cfg.getInt("block-hiding.check-interval", 10));
        resendEvery = Math.max(0, cfg.getInt("block-hiding.resend-every-passes", 10));
        hideShulkers = cfg.getBoolean("block-hiding.hide-shulkers", true);

        Material fm = Material.matchMaterial(cfg.getString("block-hiding.fake-block", "AIR"));
        fake = ((fm != null && fm.isBlock()) ? fm : Material.AIR).createBlockData();

        targets.clear();
        for (String s : cfg.getStringList("block-hiding.blocks")) {
            Material m = Material.matchMaterial(s);
            if (m != null) targets.add(m);
            else plugin.getLogger().warning("Unknown material in block-hiding.blocks: " + s);
        }
    }

    private boolean matches(Material m) {
        return targets.contains(m) || (hideShulkers && m.name().endsWith("SHULKER_BOX"));
    }

    // ------------------------------------------------------------------
    // Main loop
    // ------------------------------------------------------------------

    private void tick() {
        if (!enabled) return;
        passCounter++;
        boolean resend = resendEvery > 0 && passCounter % resendEvery == 0;

        for (Player p : Bukkit.getOnlinePlayers()) {
            if (!plugin.isActive() || plugin.hasBypass(p)) {
                revealAll(p);
                continue;
            }
            Location loc = p.getLocation();
            int pcx = loc.getBlockX() >> 4;
            int pcz = loc.getBlockZ() >> 4;
            for (int cx = pcx - radius; cx <= pcx + radius; cx++) {
                for (int cz = pcz - radius; cz <= pcz + radius; cz++) {
                    processChunk(p, loc, cx, cz, resend);
                }
            }
        }
    }

    private void processChunk(Player p, Location loc, int cx, int cz, boolean resend) {
        World w = loc.getWorld();
        Map<Long, long[]> reg = registry.get(w.getUID());
        if (reg == null) return;

        long ck = chunkKey(cx, cz);
        long[] arr = reg.get(ck);
        if (arr == null) return;

        Map<Long, Set<Long>> mine = hidden.computeIfAbsent(p.getUniqueId(), k -> new HashMap<>());
        Set<Long> set = mine.get(ck);
        double px = loc.getX(), py = loc.getY(), pz = loc.getZ();

        for (long packed : arr) {
            int x = unX(packed), y = unY(packed), z = unZ(packed);
            double dx = x + 0.5 - px, dy = y + 0.5 - py, dz = z + 0.5 - pz;
            boolean far = dx * dx + dy * dy + dz * dz > reveal2;
            boolean isHidden = set != null && set.contains(packed);

            if (far && (!isHidden || resend)) {
                // make sure the block is still really there (explosions, pistons, ...)
                if (!matches(w.getBlockAt(x, y, z).getType())) continue;
                p.sendBlockChange(new Location(w, x, y, z), fake);
                if (set == null) {
                    set = new HashSet<>();
                    mine.put(ck, set);
                }
                set.add(packed);
            } else if (!far && isHidden) {
                Block b = w.getBlockAt(x, y, z);
                p.sendBlockChange(b.getLocation(), b.getBlockData());
                set.remove(packed);
            }
        }
    }

    public void revealAll(Player p) {
        Map<Long, Set<Long>> mine = hidden.remove(p.getUniqueId());
        if (mine == null) return;
        World w = p.getWorld();
        for (Set<Long> set : mine.values()) {
            for (long packed : set) {
                int x = unX(packed), y = unY(packed), z = unZ(packed);
                if (!w.isChunkLoaded(x >> 4, z >> 4)) continue;
                Block b = w.getBlockAt(x, y, z);
                p.sendBlockChange(b.getLocation(), b.getBlockData());
            }
        }
    }

    // ------------------------------------------------------------------
    // Registry of hideable blocks
    // ------------------------------------------------------------------

    private void scan(Chunk c) {
        long[] tmp = new long[16];
        int n = 0;
        for (BlockState bs : c.getTileEntities(false)) {
            if (!matches(bs.getType())) continue;
            if (n == tmp.length) tmp = Arrays.copyOf(tmp, n * 2);
            tmp[n++] = pack(bs.getX(), bs.getY(), bs.getZ());
        }
        Map<Long, long[]> reg = registry.computeIfAbsent(c.getWorld().getUID(), k -> new HashMap<>());
        long ck = chunkKey(c.getX(), c.getZ());
        if (n == 0) reg.remove(ck);
        else reg.put(ck, Arrays.copyOf(tmp, n));
    }

    private void addToRegistry(Block b) {
        Map<Long, long[]> reg = registry.computeIfAbsent(b.getWorld().getUID(), k -> new HashMap<>());
        long ck = chunkKey(b.getX() >> 4, b.getZ() >> 4);
        long packed = pack(b.getX(), b.getY(), b.getZ());
        long[] old = reg.get(ck);
        if (old == null) {
            reg.put(ck, new long[]{packed});
            return;
        }
        for (long l : old) if (l == packed) return;
        long[] n = Arrays.copyOf(old, old.length + 1);
        n[old.length] = packed;
        reg.put(ck, n);
    }

    private void removeFromRegistry(Block b) {
        long ck = chunkKey(b.getX() >> 4, b.getZ() >> 4);
        long packed = pack(b.getX(), b.getY(), b.getZ());

        Map<Long, long[]> reg = registry.get(b.getWorld().getUID());
        if (reg != null) {
            long[] old = reg.get(ck);
            if (old != null) {
                long[] n = new long[old.length];
                int c = 0;
                for (long l : old) if (l != packed) n[c++] = l;
                if (c == 0) reg.remove(ck);
                else reg.put(ck, Arrays.copyOf(n, c));
            }
        }
        for (Map<Long, Set<Long>> mine : hidden.values()) {
            Set<Long> s = mine.get(ck);
            if (s != null) s.remove(packed);
        }
    }

    // ------------------------------------------------------------------
    // Events
    // ------------------------------------------------------------------

    @EventHandler
    public void onChunkLoad(ChunkLoadEvent e) {
        scan(e.getChunk());
    }

    @EventHandler
    public void onChunkUnload(ChunkUnloadEvent e) {
        Map<Long, long[]> reg = registry.get(e.getWorld().getUID());
        if (reg != null) reg.remove(chunkKey(e.getChunk().getX(), e.getChunk().getZ()));
    }

    /** The client just received this chunk with REAL blocks - hide the far ones again right away. */
    @EventHandler
    public void onClientChunkLoad(PlayerChunkLoadEvent e) {
        if (!enabled) return;
        Player p = e.getPlayer();
        Chunk c = e.getChunk();
        scan(c);

        Map<Long, Set<Long>> mine = hidden.get(p.getUniqueId());
        if (mine != null) mine.remove(chunkKey(c.getX(), c.getZ()));

        if (!plugin.isActive() || plugin.hasBypass(p)) return;
        Bukkit.getScheduler().runTask(plugin, () -> {
            if (p.isOnline() && p.getWorld().equals(c.getWorld())) {
                processChunk(p, p.getLocation(), c.getX(), c.getZ(), false);
            }
        });
    }

    @EventHandler
    public void onClientChunkUnload(PlayerChunkUnloadEvent e) {
        Map<Long, Set<Long>> mine = hidden.get(e.getPlayer().getUniqueId());
        if (mine != null) mine.remove(chunkKey(e.getChunk().getX(), e.getChunk().getZ()));
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPlace(BlockPlaceEvent e) {
        if (matches(e.getBlockPlaced().getType())) addToRegistry(e.getBlockPlaced());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBreak(BlockBreakEvent e) {
        if (matches(e.getBlock().getType())) removeFromRegistry(e.getBlock());
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent e) {
        hidden.remove(e.getPlayer().getUniqueId());
    }

    @EventHandler
    public void onWorldChange(PlayerChangedWorldEvent e) {
        hidden.remove(e.getPlayer().getUniqueId());   // client reloads everything anyway
    }

    // ------------------------------------------------------------------
    // Packing helpers (same layout as vanilla BlockPos)
    // ------------------------------------------------------------------

    private static long chunkKey(int cx, int cz) {
        return ((long) cx << 32) | (cz & 0xFFFFFFFFL);
    }

    private static long pack(int x, int y, int z) {
        return ((long) (x & 0x3FFFFFF) << 38) | ((long) (z & 0x3FFFFFF) << 12) | (long) (y & 0xFFF);
    }

    private static int unX(long p) {
        return (int) (p >> 38);
    }

    private static int unZ(long p) {
        return (int) (p << 26 >> 38);
    }

    private static int unY(long p) {
        return (int) (p << 52 >> 52);
    }
}
