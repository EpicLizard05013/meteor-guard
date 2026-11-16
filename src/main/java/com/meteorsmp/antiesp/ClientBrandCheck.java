package com.meteorsmp.antiesp;

import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Checks client brand strings and detects vanilla brand spoofing when modded channels are registered.
 */
public final class ClientBrandCheck implements Listener {

    private final AntiESPPlugin plugin;
    private final AlertManager alerts;

    private boolean enabled = true;
    private int checkDelayTicks = 60;
    private boolean kickOnBlocked = false;
    private String kickMessage = "&cThat client is not allowed on this server.";
    private final List<String> blockedBrands = new ArrayList<>();
    private boolean detectSpoof = true;
    private boolean alertNonVanilla = false;

    public ClientBrandCheck(AntiESPPlugin plugin, AlertManager alerts) {
        this.plugin = plugin;
        this.alerts = alerts;
    }

    public void reload() {
        var c = plugin.getConfig();
        String p = "client-checks.brand.";
        enabled = c.getBoolean(p + "enabled", true);
        checkDelayTicks = Math.max(10, c.getInt(p + "check-delay-ticks", 60));
        kickOnBlocked = c.getString(p + "action", "alert").equalsIgnoreCase("kick");
        kickMessage = c.getString(p + "kick-message", "&cThat client is not allowed on this server.");
        detectSpoof = c.getBoolean(p + "detect-spoof", true);
        alertNonVanilla = c.getBoolean(p + "alert-non-vanilla", false);

        blockedBrands.clear();
        for (String b : c.getStringList(p + "blocked-brands")) {
            String trimmed = b.trim().toLowerCase(Locale.ROOT);
            if (!trimmed.isEmpty()) blockedBrands.add(trimmed);
        }
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent e) {
        if (!enabled) return;
        Player joined = e.getPlayer();
        if (joined.hasPermission("meteorshield.bypass")) return;

        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            if (!joined.isOnline() || joined.hasPermission("meteorshield.bypass")) return;
            inspect(joined);
        }, checkDelayTicks);
    }

    private void inspect(Player p) {
        String rawBrand = p.getClientBrandName();
        String brand = rawBrand == null ? "" : rawBrand.toLowerCase(Locale.ROOT);

        for (String bad : blockedBrands) {
            if (brand.contains(bad)) {
                alerts.alert("CLIENT-BRAND", p.getName(), "blocked brand: " + rawBrand);
                if (kickOnBlocked) {
                    p.kick(AlertManager.c(kickMessage));
                }
                return;
            }
        }

        if (detectSpoof && brand.equals("vanilla")) {
            for (String ch : p.getListeningPluginChannels()) {
                String lc = ch.toLowerCase(Locale.ROOT);
                if (lc.startsWith("fabric") || lc.startsWith("fml:") || lc.startsWith("forge")
                        || lc.startsWith("neoforge") || lc.startsWith("quilt")) {
                    alerts.alert("BRAND-SPOOF", p.getName(), "brand=vanilla with channel " + ch);
                    return;
                }
            }
        }

        if (alertNonVanilla && rawBrand != null && !brand.equals("vanilla")) {
            alerts.alert("CLIENT-BRAND", p.getName(), "non-vanilla brand: " + rawBrand);
        }
    }
}
