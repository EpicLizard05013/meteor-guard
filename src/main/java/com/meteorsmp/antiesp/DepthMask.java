package com.meteorsmp.antiesp;

import com.github.retrooper.packetevents.PacketEvents;
import com.github.retrooper.packetevents.event.PacketListenerAbstract;
import com.github.retrooper.packetevents.event.PacketListenerPriority;
import com.github.retrooper.packetevents.event.PacketSendEvent;
import com.github.retrooper.packetevents.protocol.packettype.PacketType;
import com.github.retrooper.packetevents.protocol.world.chunk.BaseChunk;
import com.github.retrooper.packetevents.protocol.world.states.type.StateTypes;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerBlockChange;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerChunkData;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerChangedWorldEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerRespawnEvent;
import org.bukkit.event.player.PlayerTeleportEvent;
import org.bukkit.scheduler.BukkitTask;

import java.lang.reflect.Method;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Hides deepslate caves from players at the surface and the surface from players underground
 * by filling those chunk sections with solid blocks in the chunk packets (PacketEvents).
 */
public final class DepthMask extends PacketListenerAbstract implements Listener {

    private enum Mode { SURFACE, UNDERGROUND }

    private static final class State {
        volatile Mode mode;      // null = not decided yet, nothing is masked
        volatile int minY;
        volatile int maskAbove = 64; // underground: everything higher than this Y is hidden
        long lastChange;
    }

    private final AntiESPPlugin plugin;
    private final Map<UUID, State> states = new ConcurrentHashMap<>();
    private final Map<UUID, Integer> resending = new HashMap<>(); // main thread only
    private final int deepslateId;
    private final int stoneId;
    private final AtomicLong maskedChunks = new AtomicLong();
    private final AtomicLong blockedUpdates = new AtomicLong();
    private BukkitTask task;

    private volatile Set<String> worlds = new HashSet<>();
    private volatile int maskBelowY, maskAboveY, switchDownY, switchUpY;
    private volatile long cooldownMs;
    private volatile boolean hideEntities = true;
    private volatile boolean followPlayer = true;
    private volatile int layerMargin = 6, layerStep = 4;
    private volatile long layerCooldownMs = 1500L;
    private volatile int undergroundFiller;
    private static final Method REFRESH_CHUNK = lookupRefresh();

    public DepthMask(AntiESPPlugin plugin) {
        // HIGHEST = runs after GrimAC has read the real chunk data
        super(PacketListenerPriority.HIGHEST);
        this.plugin = plugin;
        this.deepslateId = StateTypes.DEEPSLATE.createBlockState().getGlobalId();
        this.stoneId = StateTypes.STONE.createBlockState().getGlobalId();
        this.undergroundFiller = deepslateId;
    }

    public void start() {
        reload();
        PacketEvents.getAPI().getEventManager().registerListener(this);
        Bukkit.getPluginManager().registerEvents(this, plugin);
        task = Bukkit.getScheduler().runTaskTimer(plugin, this::update, 20L, 10L);
        plugin.getLogger().info("Depth mask enabled (PacketEvents).");
    }

    public void stop() {
        if (task != null) task.cancel();
        PacketEvents.getAPI().getEventManager().unregisterListener(this);
        for (Map.Entry<UUID, Integer> en : resending.entrySet()) {
            Player p = Bukkit.getPlayer(en.getKey());
            if (p != null) p.setSendViewDistance(en.getValue());
        }
        resending.clear();
        states.clear();
    }

    public void reload() {
        var c = plugin.getConfig();
        String p = c.isConfigurationSection("antifreecam") ? "antifreecam." : "depth-mask.";
        List<String> wList = c.getStringList(p + "worlds");
        worlds = new HashSet<>(wList);
        maskBelowY = c.getInt(p + "surface-mode.mask-below-y", -8);
        maskAboveY = c.getInt(p + "underground-mode.mask-above-y", 64);
        switchDownY = c.getInt(p + "switch-down-y", 20);
        switchUpY = Math.max(switchDownY, c.getInt(p + "switch-up-y", 50));
        cooldownMs = Math.max(1, c.getInt(p + "resend-cooldown-seconds", 5)) * 1000L;
        hideEntities = c.getBoolean(p + "hide-entities", true);
        followPlayer = c.getBoolean(p + "underground-mode.follow-player", true);
        layerMargin = Math.max(2, c.getInt(p + "underground-mode.margin", 6));
        layerStep = Math.max(1, c.getInt(p + "underground-mode.step", 4));
        layerCooldownMs = Math.max(500, c.getInt(p + "underground-mode.layer-cooldown-ms", 1500));
        undergroundFiller = "stone".equalsIgnoreCase(c.getString(p + "underground-mode.filler", "deepslate"))
                ? stoneId : deepslateId;
        // everyone is re-evaluated (and their chunks re-sent) on the next pass
        for (State s : states.values()) s.mode = null;
    }

    private boolean appliesToWorld(World w) {
        if (w.getEnvironment() != World.Environment.NORMAL) return false;
        return worlds.isEmpty() || worlds.contains(w.getName());
    }

    public boolean isHideEntities() {
        return hideEntities;
    }

    public boolean isMaskedAt(Player p, int y) {
        State st = states.get(p.getUniqueId());
        if (st == null || st.mode == null) return false;
        return st.mode == Mode.SURFACE ? y < maskBelowY : y > st.maskAbove;
    }

    public String describe(Player p) {
        if (plugin.hasBypass(p)) return "bypassed";
        if (!appliesToWorld(p.getWorld())) return "inactive (" + p.getWorld().getName() + ")";
        State st = states.get(p.getUniqueId());
        if (st == null || st.mode == null) return "initializing";
        return st.mode == Mode.SURFACE
                ? "SURFACE (masking below Y=" + maskBelowY + ")"
                : "UNDERGROUND (everything above Y=" + st.maskAbove + " is hidden)";
    }

    public void refresh(Player p) {
        states.remove(p.getUniqueId());
        resend(p);
    }

    public int trackedPlayers() {
        return states.size();
    }

    public long chunksMasked() {
        return maskedChunks.get();
    }

    public long blockUpdatesBlocked() {
        return blockedUpdates.get();
    }

    public String summary() {
        return "Switch down < Y=" + switchDownY + ", up > Y=" + switchUpY
                + " | Surface hides below Y=" + maskBelowY + " | Underground hides "
                + (followPlayer ? "everything " + layerMargin + "+ blocks above you (steps of " + layerStep + ")" : "above Y=" + maskAboveY);
    }

    /** Highest Y an underground player at height y is allowed to see. */
    private int layerFor(int y) {
        if (!followPlayer) return maskAboveY;
        return Math.floorDiv(y, layerStep) * layerStep + layerMargin;
    }

    /**
     * Decide the layer BEFORE the first chunks are sent (join, respawn, long teleport), so the chunks around the
     * player are masked from the very first packet and never need a re-send.
     */
    private void prepare(Player p, World w, int y) {
        UUID id = p.getUniqueId();
        if (!appliesToWorld(w) || plugin.hasBypass(p)) {
            states.remove(id);
            return;
        }
        State st = new State();
        st.minY = w.getMinHeight();
        st.mode = y < switchDownY ? Mode.UNDERGROUND : Mode.SURFACE;
        st.maskAbove = layerFor(y);
        st.lastChange = System.currentTimeMillis();
        states.put(id, st);
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onJoin(PlayerJoinEvent e) {
        Player p = e.getPlayer();
        prepare(p, p.getWorld(), p.getLocation().getBlockY());
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onRespawn(PlayerRespawnEvent e) {
        Location l = e.getRespawnLocation();
        if (l.getWorld() != null) prepare(e.getPlayer(), l.getWorld(), l.getBlockY());
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onTeleport(PlayerTeleportEvent e) {
        Location from = e.getFrom();
        Location to = e.getTo();
        if (to == null || to.getWorld() == null) return;
        boolean far = from.getWorld() != to.getWorld() || from.distanceSquared(to) > 96.0 * 96.0;
        if (far) prepare(e.getPlayer(), to.getWorld(), to.getBlockY());
    }

    // ---------------- main thread: decide the mode per player ----------------
    private void update() {
        long now = System.currentTimeMillis();
        for (Player p : Bukkit.getOnlinePlayers()) {
            UUID id = p.getUniqueId();
            World w = p.getWorld();
            State st = states.get(id);

            if (!appliesToWorld(w) || plugin.hasBypass(p)) {
                if (st != null) {
                    states.remove(id);
                    resend(p); // give the client the real chunks back
                }
                continue;
            }

            if (st == null) {
                st = new State();
                states.put(id, st);
            }
            st.minY = w.getMinHeight();

            int y = p.getLocation().getBlockY();
            Mode wanted = st.mode;
            if (wanted == null) wanted = y < switchDownY ? Mode.UNDERGROUND : Mode.SURFACE;
            else if (wanted == Mode.SURFACE && y < switchDownY) wanted = Mode.UNDERGROUND;
            else if (wanted == Mode.UNDERGROUND && y > switchUpY) wanted = Mode.SURFACE;

            int wantedAbove = wanted == Mode.UNDERGROUND ? layerFor(y) : st.maskAbove;
            if (wanted != st.mode) {
                if (st.mode != null && now - st.lastChange < cooldownMs) continue;
                st.mode = wanted;
                st.maskAbove = wantedAbove;
                st.lastChange = now;
                resend(p);
            } else if (wanted == Mode.UNDERGROUND && wantedAbove != st.maskAbove
                    && now - st.lastChange >= layerCooldownMs) {
                // the player moved up/down: the hidden layer follows them
                st.maskAbove = wantedAbove;
                st.lastChange = now;
                resend(p);
            }
        }
    }

    /** Makes the server send the player's chunks again (they pass through onPacketSend below). */
    private void resend(Player p) {
        UUID id = p.getUniqueId();
        if (resending.containsKey(id)) return;
        resending.put(id, p.getSendViewDistance());
        p.setSendViewDistance(2);
        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            if (p.isOnline()) refreshNear(p);
        }, 3L);
        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            Integer vd = resending.remove(id);
            if (vd != null && p.isOnline()) p.setSendViewDistance(vd);
        }, 10L);
    }

    private static Method lookupRefresh() {
        try {
            return World.class.getMethod("refreshChunk", int.class, int.class);
        } catch (Throwable t) {
            return null;
        }
    }

    /**
     * The send-view-distance trick cannot re-send the chunks within 2 of the player (about 80x80 blocks), so
     * those used to stay unmasked after a layer change. Ask the server to refresh them too (reflection, so a
     * server without this method simply skips it).
     */
    private void refreshNear(Player p) {
        if (REFRESH_CHUNK == null) return;
        World w = p.getWorld();
        int cx = p.getLocation().getBlockX() >> 4;
        int cz = p.getLocation().getBlockZ() >> 4;
        for (int dx = -2; dx <= 2; dx++) {
            for (int dz = -2; dz <= 2; dz++) {
                try {
                    REFRESH_CHUNK.invoke(w, cx + dx, cz + dz);
                } catch (Throwable t) {
                    return;
                }
            }
        }
    }

    @EventHandler
    public void onWorld(PlayerChangedWorldEvent e) {
        Player p = e.getPlayer();
        states.remove(p.getUniqueId());
        prepare(p, p.getWorld(), p.getLocation().getBlockY());
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent e) {
        states.remove(e.getPlayer().getUniqueId());
        resending.remove(e.getPlayer().getUniqueId());
    }

    // ---------------- netty thread: rewrite the packets ----------------
    @Override
    public void onPacketSend(PacketSendEvent event) {
        if (event.getPacketType() != PacketType.Play.Server.CHUNK_DATA
                && event.getPacketType() != PacketType.Play.Server.BLOCK_CHANGE) return;

        UUID id = event.getUser().getUUID();
        if (id == null) return;
        State st = states.get(id);
        if (st == null || st.mode == null) return;
        Mode mode = st.mode;

        if (event.getPacketType() == PacketType.Play.Server.CHUNK_DATA) {
            WrapperPlayServerChunkData wrapper = new WrapperPlayServerChunkData(event);
            BaseChunk[] chunks = wrapper.getColumn().getChunks();
            boolean changed = false;
            for (int i = 0; i < chunks.length; i++) {
                if (chunks[i] == null) continue;
                int secMin = st.minY + i * 16;
                int secMax = secMin + 15;
                int filler, from, to; // local Y range inside this 16-block section
                if (mode == Mode.SURFACE) {
                    // surface player: deep area (below maskBelowY) is replaced by solid deepslate
                    if (secMin >= maskBelowY) continue;
                    filler = deepslateId;
                    from = 0;
                    to = Math.min(15, maskBelowY - 1 - secMin);
                } else {
                    // underground player: everything above their layer is replaced by solid deepslate.
                    // Whatever is below the layer is never touched.
                    int above = st.maskAbove;
                    if (secMax <= above) continue;
                    filler = undergroundFiller;
                    from = Math.max(0, above + 1 - secMin);
                    to = 15;
                }
                fillRange(chunks[i], from, to, filler);
                changed = true;
            }
            if (changed) {
                maskedChunks.incrementAndGet();
                event.markForReEncode(true);
            }
        } else {
            // a single block changing inside a masked area must not leak the real block
            int y = new WrapperPlayServerBlockChange(event).getBlockPosition().getY();
            boolean masked = mode == Mode.SURFACE ? y < maskBelowY : y > st.maskAbove;
            if (masked) {
                blockedUpdates.incrementAndGet();
                event.setCancelled(true);
            }
        }
    }

    private static void fillRange(BaseChunk chunk, int fromY, int toY, int id) {
        for (int y = fromY; y <= toY; y++) {
            for (int z = 0; z < 16; z++) {
                for (int x = 0; x < 16; x++) {
                    chunk.set(x, y, z, id);
                }
            }
        }
    }
}
