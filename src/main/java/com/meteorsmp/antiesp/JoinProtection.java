package com.meteorsmp.antiesp;

import io.papermc.paper.event.player.AsyncChatEvent;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Statistic;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.player.AsyncPlayerPreLoginEvent;
import org.bukkit.event.player.PlayerCommandPreprocessEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.scheduler.BukkitTask;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicLong;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

/** Bot / join-flood protection: IP limits, name filter, attack mode, click-to-verify. */
public final class JoinProtection implements Listener {

    private static final class VerifyHolder implements InventoryHolder {
        Inventory inv;
        int correct;

        @Override
        public Inventory getInventory() {
            return inv;
        }
    }

    private final AntiESPPlugin plugin;
    private final AlertManager alerts;
    private final NamespacedKey verifiedKey;

    private final Map<String, ArrayDeque<Long>> ipJoins = new ConcurrentHashMap<>();
    private final Map<String, Set<UUID>> onlineByIp = new ConcurrentHashMap<>();
    private final Map<UUID, String> playerIp = new ConcurrentHashMap<>();
    private final ArrayDeque<Long> attempts = new ArrayDeque<>();
    private final Set<UUID> verifying = ConcurrentHashMap.newKeySet();
    private final AtomicLong attackUntil = new AtomicLong(0L);
    private BukkitTask cleanTask;

    private boolean enabled, ipEnabled, nameEnabled, attackEnabled, blockNew;
    private String bedrockPrefix = ".";
    private Set<String> ignoreIps = new HashSet<>();
    private int ipMaxJoins, ipMaxAccounts, attackThreshold, verifyTimeout, trustedMinutes;
    private long ipWindowMs, attackWindowMs, attackDurationMs;
    private String ipMsg, nameMsg, attackMsg, verifyMode, verifyTitle;
    private final List<Pattern> blockedNames = new ArrayList<>();

    public JoinProtection(AntiESPPlugin plugin, AlertManager alerts) {
        this.plugin = plugin;
        this.alerts = alerts;
        this.verifiedKey = new NamespacedKey(plugin, "shield_verified");
    }

    public void reload() {
        var cfg = plugin.getConfig();
        String p = "join-protection.";
        enabled = cfg.getBoolean(p + "enabled", true);
        bedrockPrefix = cfg.getString(p + "bedrock-prefix", ".");
        ignoreIps = new HashSet<>(cfg.getStringList(p + "ignore-ips"));

        ipEnabled = cfg.getBoolean(p + "ip-limits.enabled", true);
        ipMaxJoins = Math.max(1, cfg.getInt(p + "ip-limits.max-joins", 4));
        ipWindowMs = Math.max(1, cfg.getInt(p + "ip-limits.window-seconds", 30)) * 1000L;
        ipMaxAccounts = Math.max(1, cfg.getInt(p + "ip-limits.max-accounts-per-ip", 3));
        ipMsg = cfg.getString(p + "ip-limits.message", "&cToo many connections from your IP.");

        nameEnabled = cfg.getBoolean(p + "name-filter.enabled", true);
        nameMsg = cfg.getString(p + "name-filter.message", "&cThat username is not allowed.");
        blockedNames.clear();
        for (String rx : cfg.getStringList(p + "name-filter.blocked-regex")) {
            try {
                blockedNames.add(Pattern.compile(rx));
            } catch (PatternSyntaxException ex) {
                plugin.getLogger().warning("Bad regex in join-protection.name-filter: " + rx);
            }
        }

        attackEnabled = cfg.getBoolean(p + "attack-mode.enabled", true);
        attackThreshold = Math.max(2, cfg.getInt(p + "attack-mode.joins-threshold", 12));
        attackWindowMs = Math.max(1, cfg.getInt(p + "attack-mode.window-seconds", 10)) * 1000L;
        attackDurationMs = Math.max(10, cfg.getInt(p + "attack-mode.duration-seconds", 120)) * 1000L;
        blockNew = cfg.getBoolean(p + "attack-mode.block-new-players", false);
        attackMsg = cfg.getString(p + "attack-mode.message", "&cThe server is under a join attack. Try again later.");

        verifyMode = cfg.getString(p + "verification.mode", "attack").toLowerCase(Locale.ROOT);
        verifyTimeout = Math.max(5, cfg.getInt(p + "verification.timeout-seconds", 20));
        trustedMinutes = Math.max(0, cfg.getInt(p + "verification.trusted-playtime-minutes", 30));
        verifyTitle = cfg.getString(p + "verification.title", "&8Click the &aLIME &8pane to verify");

        if (cleanTask != null) cleanTask.cancel();
        cleanTask = Bukkit.getScheduler().runTaskTimerAsynchronously(plugin, this::cleanup, 1200L, 1200L);
    }

    public void shutdown() {
        if (cleanTask != null) cleanTask.cancel();
        verifying.clear();
    }

    // ------------------------------------------------------------------
    // Attack mode
    // ------------------------------------------------------------------

    public boolean attackActive() {
        long until = attackUntil.get();
        if (until == 0L) return false;
        if (System.currentTimeMillis() < until) return true;
        if (attackUntil.compareAndSet(until, 0L)) alerts.alert("ATTACK-MODE", "server", "join attack ended");
        return false;
    }

    public void setAttack(boolean on) {
        if (on) {
            attackUntil.set(System.currentTimeMillis() + attackDurationMs);
            alerts.alert("ATTACK-MODE", "server", "attack mode forced ON by staff");
        } else {
            attackUntil.set(0L);
            alerts.alert("ATTACK-MODE", "server", "attack mode forced OFF by staff");
        }
    }

    private void registerAttempt(long now) {
        if (!attackEnabled) return;
        boolean trigger;
        synchronized (attempts) {
            attempts.addLast(now);
            while (!attempts.isEmpty() && now - attempts.peekFirst() > attackWindowMs) attempts.pollFirst();
            trigger = attempts.size() >= attackThreshold;
        }
        if (!trigger) return;
        boolean wasActive = attackActive();
        attackUntil.set(now + attackDurationMs);
        if (!wasActive) {
            alerts.alert("ATTACK-MODE", "server", "join attack detected, protection ON for " + (attackDurationMs / 1000) + "s");
        }
    }

    // ------------------------------------------------------------------
    // Pre-login checks (async)
    // ------------------------------------------------------------------

    @EventHandler(priority = EventPriority.LOW)
    public void onPreLogin(AsyncPlayerPreLoginEvent e) {
        if (!enabled || e.getLoginResult() != AsyncPlayerPreLoginEvent.Result.ALLOWED) return;
        String name = e.getName();
        if (!bedrockPrefix.isEmpty() && name.startsWith(bedrockPrefix)) return;

        String ip = e.getAddress().getHostAddress();
        long now = System.currentTimeMillis();
        registerAttempt(now);

        if (nameEnabled) {
            for (Pattern pt : blockedNames) {
                if (pt.matcher(name).matches()) {
                    deny(e, nameMsg, "BLOCKED-NAME", name + " (" + ip + ")");
                    return;
                }
            }
        }

        if (ipEnabled && !ignoreIps.contains(ip)) {
            ArrayDeque<Long> dq = ipJoins.computeIfAbsent(ip, k -> new ArrayDeque<>());
            boolean tooMany;
            synchronized (dq) {
                while (!dq.isEmpty() && now - dq.peekFirst() > ipWindowMs) dq.pollFirst();
                tooMany = dq.size() >= ipMaxJoins;
                if (!tooMany) dq.addLast(now);
            }
            if (tooMany) {
                deny(e, ipMsg, "JOIN-FLOOD", ip + " (" + name + ")");
                return;
            }
            Set<UUID> online = onlineByIp.get(ip);
            if (online != null && !online.contains(e.getUniqueId()) && online.size() >= ipMaxAccounts) {
                deny(e, ipMsg, "TOO-MANY-ACCOUNTS", ip + " (" + name + ")");
                return;
            }
        }

        if (blockNew && attackActive() && !Bukkit.getOfflinePlayer(e.getUniqueId()).hasPlayedBefore()) {
            deny(e, attackMsg, "ATTACK-BLOCKED", name + " (" + ip + ")");
        }
    }

    private void deny(AsyncPlayerPreLoginEvent e, String msg, String check, String details) {
        e.disallow(AsyncPlayerPreLoginEvent.Result.KICK_OTHER, AlertManager.c(msg));
        alerts.alert(check, e.getAddress().getHostAddress(), details);
    }

    private void cleanup() {
        long now = System.currentTimeMillis();
        for (Map.Entry<String, ArrayDeque<Long>> en : ipJoins.entrySet()) {
            ArrayDeque<Long> dq = en.getValue();
            synchronized (dq) {
                while (!dq.isEmpty() && now - dq.peekFirst() > ipWindowMs) dq.pollFirst();
                if (dq.isEmpty()) ipJoins.remove(en.getKey(), dq);
            }
        }
    }

    // ------------------------------------------------------------------
    // Join / quit / verification
    // ------------------------------------------------------------------

    @EventHandler
    public void onJoin(PlayerJoinEvent e) {
        Player p = e.getPlayer();
        if (p.getAddress() != null && p.getAddress().getAddress() != null) {
            String ip = p.getAddress().getAddress().getHostAddress();
            playerIp.put(p.getUniqueId(), ip);
            onlineByIp.computeIfAbsent(ip, k -> ConcurrentHashMap.newKeySet()).add(p.getUniqueId());
        }
        if (!enabled || verifyMode.equals("off") || isTrusted(p)) return;
        if (verifyMode.equals("always") || (verifyMode.equals("attack") && attackActive())) startVerification(p);
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent e) {
        UUID id = e.getPlayer().getUniqueId();
        verifying.remove(id);
        String ip = playerIp.remove(id);
        if (ip != null) {
            Set<UUID> s = onlineByIp.get(ip);
            if (s != null) {
                s.remove(id);
                if (s.isEmpty()) onlineByIp.remove(ip, s);
            }
        }
    }

    private boolean isTrusted(Player p) {
        if (p.isOp() || p.hasPermission("meteorshield.bypass")) return true;
        if (!bedrockPrefix.isEmpty() && p.getName().startsWith(bedrockPrefix)) return true;
        if (p.getPersistentDataContainer().has(verifiedKey, PersistentDataType.BYTE)) return true;
        long minutes = p.getStatistic(Statistic.PLAY_ONE_MINUTE) / 20L / 60L;
        return trustedMinutes > 0 && minutes >= trustedMinutes;
    }

    private ItemStack pane(Material m, String name) {
        ItemStack it = new ItemStack(m);
        ItemMeta meta = it.getItemMeta();
        meta.displayName(AlertManager.c(name).decoration(TextDecoration.ITALIC, false));
        it.setItemMeta(meta);
        return it;
    }

    private void startVerification(Player p) {
        UUID id = p.getUniqueId();
        verifying.add(id);
        p.setInvulnerable(true);

        VerifyHolder holder = new VerifyHolder();
        holder.inv = Bukkit.createInventory(holder, 27, AlertManager.c(verifyTitle));
        holder.correct = ThreadLocalRandom.current().nextInt(27);
        for (int i = 0; i < 27; i++) {
            holder.inv.setItem(i, i == holder.correct
                    ? pane(Material.LIME_STAINED_GLASS_PANE, "&aClick me")
                    : pane(Material.RED_STAINED_GLASS_PANE, "&cNot this one"));
        }
        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            if (p.isOnline() && verifying.contains(id)) p.openInventory(holder.inv);
        }, 5L);
        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            if (p.isOnline() && verifying.contains(id)) fail(p, "timed out");
        }, verifyTimeout * 20L);
    }

    private void pass(Player p) {
        verifying.remove(p.getUniqueId());
        p.setInvulnerable(false);
        p.getPersistentDataContainer().set(verifiedKey, PersistentDataType.BYTE, (byte) 1);
        p.closeInventory();
        p.sendMessage(AlertManager.c("&aVerified. Welcome!"));
    }

    private void fail(Player p, String why) {
        verifying.remove(p.getUniqueId());
        p.setInvulnerable(false);
        alerts.alert("VERIFY-FAIL", p.getName(), why);
        p.kick(AlertManager.c("&cVerification failed. Please rejoin and click the lime pane."));
    }

    @EventHandler
    public void onClick(InventoryClickEvent e) {
        if (!(e.getInventory().getHolder() instanceof VerifyHolder h)) return;
        e.setCancelled(true);
        if (!(e.getWhoClicked() instanceof Player p) || !verifying.contains(p.getUniqueId())) return;
        if (e.getRawSlot() < 0 || e.getRawSlot() >= 27) return;
        if (e.getRawSlot() == h.correct) pass(p);
        else fail(p, "clicked the wrong pane");
    }

    @EventHandler
    public void onClose(InventoryCloseEvent e) {
        if (!(e.getInventory().getHolder() instanceof VerifyHolder h)) return;
        if (!(e.getPlayer() instanceof Player p) || !verifying.contains(p.getUniqueId())) return;
        Bukkit.getScheduler().runTask(plugin, () -> {
            if (p.isOnline() && verifying.contains(p.getUniqueId())) p.openInventory(h.inv);
        });
    }

    @EventHandler
    public void onMove(PlayerMoveEvent e) {
        if (verifying.contains(e.getPlayer().getUniqueId())) e.setCancelled(true);
    }

    @EventHandler
    public void onCommand(PlayerCommandPreprocessEvent e) {
        if (verifying.contains(e.getPlayer().getUniqueId())) e.setCancelled(true);
    }

    @EventHandler
    public void onChat(AsyncChatEvent e) {
        if (verifying.contains(e.getPlayer().getUniqueId())) e.setCancelled(true);
    }
}
