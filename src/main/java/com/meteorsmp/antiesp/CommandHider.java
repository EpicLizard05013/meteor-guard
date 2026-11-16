package com.meteorsmp.antiesp;

import com.destroystokyo.paper.event.server.AsyncTabCompleteEvent;
import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerCommandPreprocessEvent;
import org.bukkit.event.player.PlayerCommandSendEvent;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/** Stops client mods (Meteor Client plugin scanner etc.) from learning which plugins the server runs. */
public final class CommandHider implements Listener {

    private final AntiESPPlugin plugin;
    private final AlertManager alerts;

    private boolean enabled, suggestionsOn, hideNamespaced, blockEnabled, blockNamespaced, alertOnAttempt, hideOwn;
    private String message = "&cUnknown command. Type \"/help\" for help.";
    private final Set<String> hiddenCmds = new HashSet<>();
    private final Set<String> blocked = new HashSet<>();

    public CommandHider(AntiESPPlugin plugin, AlertManager alerts) {
        this.plugin = plugin;
        this.alerts = alerts;
    }

    public void reload() {
        var c = plugin.getConfig();
        String p = "plugin-hider.";
        enabled = c.getBoolean(p + "enabled", true);
        hideOwn = c.getBoolean(p + "hide-own-commands", true);
        suggestionsOn = c.getBoolean(p + "suggestions.enabled", true);
        hideNamespaced = c.getBoolean(p + "suggestions.hide-namespaced", true);
        hiddenCmds.clear();
        for (String s : c.getStringList(p + "suggestions.hidden-commands")) hiddenCmds.add(norm(s));
        blockEnabled = c.getBoolean(p + "block-commands.enabled", true);
        blockNamespaced = c.getBoolean(p + "block-commands.block-namespaced", true);
        message = c.getString(p + "block-commands.message", message);
        blocked.clear();
        for (String s : c.getStringList(p + "block-commands.commands")) blocked.add(norm(s));
        alertOnAttempt = c.getBoolean(p + "alert-on-attempt", true);
    }

    private static String norm(String s) {
        String t = s.toLowerCase(Locale.ROOT).trim();
        return t.startsWith("/") ? t.substring(1) : t;
    }

    private boolean exempt(Player p) {
        return p.hasPermission("meteorguard.seecommands");
    }

    private static boolean canUseSuspicious(Player p) {
        return p.hasPermission("meteorguard.suspicious")
                || p.hasPermission("meteorguard.admin")
                || p.hasPermission("antifreecam.admin");
    }

    private static boolean canUseMeteor(Player p) {
        return canUseSuspicious(p) || p.hasPermission("meteorshield.alerts");
    }

    private static boolean isOwnCmd(String base) {
        return base.equals("meteor") || base.equals("meteorguard") || base.equals("suspicious");
    }

    private boolean canUseOwn(String base, Player p) {
        if (base.equals("suspicious")) return canUseSuspicious(p);
        if (base.equals("meteor") || base.equals("meteorguard")) return canUseMeteor(p);
        return true;
    }

    private boolean show(String raw, Player p) {
        String n = norm(raw);
        String base = n.substring(n.lastIndexOf(':') + 1);
        if (hideOwn && isOwnCmd(base) && !canUseOwn(base, p)) return false;
        if (blocked.contains(n) || blocked.contains(base)) return false;
        if (n.indexOf(':') >= 0 && hideNamespaced) return false;
        if (isOwnCmd(base) && canUseOwn(base, p)) return true;
        return !hiddenCmds.contains(n) && !hiddenCmds.contains(base);
    }

    // 1) the command list the client gets when joining (what the client mod reads)
    @EventHandler(priority = EventPriority.HIGHEST)
    public void onSend(PlayerCommandSendEvent e) {
        if (!enabled) return;
        Player p = e.getPlayer();
        if (exempt(p)) return;
        if (suggestionsOn) {
            e.getCommands().removeIf(name -> !show(name, p));
        } else if (hideOwn) {
            e.getCommands().removeIf(name -> {
                String n = norm(name);
                String base = n.substring(n.lastIndexOf(':') + 1);
                return isOwnCmd(base) && !canUseOwn(base, p);
            });
        }
    }

    // 2) tab-complete requests ("/" + letters), answered by us so hidden commands never leak
    @EventHandler(priority = EventPriority.HIGHEST)
    public void onTab(AsyncTabCompleteEvent e) {
        if (!enabled || !e.isCommand()) return;
        if (!(e.getSender() instanceof Player p) || exempt(p)) return;

        String buf = e.getBuffer();
        boolean slash = buf.startsWith("/");
        String body = slash ? buf.substring(1) : buf;
        int space = body.indexOf(' ');
        if (space >= 0) {
            String cmd = body.substring(0, space);
            String n = norm(cmd);
            String base = n.substring(n.lastIndexOf(':') + 1);
            if ((hideOwn && isOwnCmd(base) && !canUseOwn(base, p))
                    || (suggestionsOn && !show(cmd, p))) {
                e.setCompletions(List.of());
                e.setHandled(true);
            }
            return;
        }

        if (!suggestionsOn) return;

        String typed = body.toLowerCase(Locale.ROOT);
        List<String> out = new ArrayList<>();
        try {
            for (Map.Entry<String, Command> en : Bukkit.getCommandMap().getKnownCommands().entrySet()) {
                String name = en.getKey().toLowerCase(Locale.ROOT);
                if (!name.startsWith(typed) || !show(name, p)) continue;
                String perm = en.getValue().getPermission();
                if (perm != null && !p.hasPermission(perm)) continue;
                out.add((slash ? "/" : "") + name);
            }
        } catch (RuntimeException ignored) {
            // command map changed while we were reading it; answer with what we have
        }
        Collections.sort(out);
        e.setCompletions(out);
        e.setHandled(true);
    }

    // 3) typing the plugin-list or unpermitted MeteorGuard commands directly
    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onCommand(PlayerCommandPreprocessEvent e) {
        if (!enabled) return;
        Player p = e.getPlayer();
        if (exempt(p)) return;

        String msg = e.getMessage();
        String body = msg.startsWith("/") ? msg.substring(1) : msg;
        int sp = body.indexOf(' ');
        String label = norm(sp >= 0 ? body.substring(0, sp) : body);
        boolean namespaced = label.indexOf(':') >= 0;
        String base = label.substring(label.lastIndexOf(':') + 1);

        if (hideOwn && isOwnCmd(base)) {
            if (!canUseOwn(base, p)) {
                e.setCancelled(true);
                p.sendMessage(AlertManager.c(message));
                return;
            }
            if ((base.equals("meteor") || base.equals("meteorguard")) && sp >= 0) {
                String rest = body.substring(sp + 1).trim();
                int sp2 = rest.indexOf(' ');
                String sub = (sp2 >= 0 ? rest.substring(0, sp2) : rest).toLowerCase(Locale.ROOT);
                if (sub.equals("suspicious") && !canUseSuspicious(p)) {
                    e.setCancelled(true);
                    p.sendMessage(AlertManager.c(message));
                    return;
                }
            }
        }

        if (!blockEnabled) return;

        if (blocked.contains(label) || blocked.contains(base) || (blockNamespaced && namespaced)) {
            e.setCancelled(true);
            p.sendMessage(AlertManager.c(message));
            if (alertOnAttempt) {
                alerts.alert("PLUGIN-SCAN", p.getName(), "/" + (label.length() > 40 ? label.substring(0, 40) : label));
            }
        }
    }
}
