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
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerChangedWorldEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.scheduler.BukkitTask;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Hides deepslate caves from players at the surface and the surface from players underground
 * by filling those chunk sections with solid blocks in the chunk packets (PacketEvents).
 */
public final class DepthMask extends PacketListenerAbstract implements Listener {

    private enum Mode { SURFACE, UNDERGROUND }

    private static final class State {
        volatile Mode mode;      // null = not decided yet, nothing is masked
        volatile int minY;
        long lastChange;
    }

    private final AntiESPPlugin plugin;
    private final Map<UUID, State> states = new ConcurrentHashMap<>();
    private final Map<UUID, Integer> resending = new HashMap<>(); // main thread only
    private final int deepslateId;
    private final int stoneId;
    private BukkitTask task;

    private volatile Set<String> worlds = new HashSet<>();
    private volatile int maskBelowY, maskAboveY, switchDownY, switchUpY;
    private volatile long cooldownMs;

    public DepthMask(AntiESPPlugin plugin) {
        // HIGHEST = runs after GrimAC has read the real chunk data
        super(PacketListenerPriority.HIGHEST);
        this.plugin = plugin;
        this.deepslateId = StateTypes.DEEPSLATE.createBlockState().getGlobalId();
        this.stoneId = StateTypes.STONE.createBlockState().getGlobalId();
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
        String p = "depth-mask.";
        worlds = new HashSet<>(c.getStringList(p + "worlds"));
        maskBelowY = c.getInt(p + "surface-mode.mask-below-y", -8);
        maskAboveY = c.getInt(p + "underground-mode.mask-above-y", 64);
        switchDownY = c.getInt(p + "switch-down-y", 20);
        switchUpY = Math.max(switchDownY, c.getInt(p + "switch-up-y", 50));
        cooldownMs = Math.max(1, c.getInt(p + "resend-cooldown-seconds", 5)) * 1000L;
        // everyone is re-evaluated (and their chunks re-sent) on the next pass
        for (State s : states.values()) s.mode = null;
    }

    // ---------------- main thread: decide the mode per player ----------------
    private void update() {
        long now = System.currentTimeMillis();
        for (Player p : Bukkit.getOnlinePlayers()) {
            UUID id = p.getUniqueId();
            World w = p.getWorld();
            State st = states.get(id);

            if (!worlds.contains(w.getName()) || plugin.hasBypass(p)) {
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

            if (wanted != st.mode) {
                if (st.mode != null && now - st.lastChange < cooldownMs) continue;
                st.mode = wanted;
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
            Integer vd = resending.remove(id);
            if (vd != null && p.isOnline()) p.setSendViewDistance(vd);
        }, 10L);
    }

    @EventHandler
    public void onWorld(PlayerChangedWorldEvent e) {
        states.remove(e.getPlayer().getUniqueId());
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
                int filler;
                if (mode == Mode.SURFACE && secMax < maskBelowY) filler = deepslateId;
                else if (mode == Mode.UNDERGROUND && secMin > maskAboveY) filler = stoneId;
                else continue;
                fill(chunks[i], filler);
                changed = true;
            }
            if (changed) event.markForReEncode(true);
        } else {
            // a single block changing inside a masked area must not leak the real block
            int y = new WrapperPlayServerBlockChange(event).getBlockPosition().getY();
            boolean masked = mode == Mode.SURFACE ? y < maskBelowY : y > maskAboveY;
            if (masked) event.setCancelled(true);
        }
    }

    private static void fill(BaseChunk chunk, int id) {
        for (int y = 0; y < 16; y++) {
            for (int z = 0; z < 16; z++) {
                for (int x = 0; x < 16; x++) {
                    chunk.set(x, y, z, id);
                }
            }
        }
    }
}
