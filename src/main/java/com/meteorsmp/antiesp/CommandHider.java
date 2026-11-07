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

    private boolean enabled, suggestionsOn, hideNamespaced, blockEnabled, blockNamespaced, alertOnAttempt;
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

    /** Should this command name be visible to a normal player? Only listed commands are hidden. */
    private boolean show(String raw) {
        String n = norm(raw);
        String base = n.substring(n.lastIndexOf(':') + 1);
        if (blocked.contains(n) || blocked.contains(base)) return false;
        if (n.indexOf(':') >= 0 && hideNamespaced) return false;
        return !hiddenCmds.contains(n) && !hiddenCmds.contains(base);
    }

    // 1) the command list the client gets when joining (what the client mod reads)
    @EventHandler(priority = EventPriority.HIGHEST)
    public void onSend(PlayerCommandSendEvent e) {
        if (!enabled || !suggestionsOn || exempt(e.getPlayer())) return;
        e.getCommands().removeIf(name -> !show(name));
    }

    // 2) tab-complete requests ("/" + letters), answered by us so hidden commands never leak
    @EventHandler(priority = EventPriority.HIGHEST)
    public void onTab(AsyncTabCompleteEvent e) {
        if (!enabled || !suggestionsOn || !e.isCommand()) return;
        if (!(e.getSender() instanceof Player p) || exempt(p)) return;

        String buf = e.getBuffer();
        boolean slash = buf.startsWith("/");
        String body = slash ? buf.substring(1) : buf;
        int space = body.indexOf(' ');
        if (space >= 0) {
            // arguments: only shut down hidden commands (/version <plugin>, /plugins ...)
            if (!show(body.substring(0, space))) {
                e.setCompletions(List.of());
                e.setHandled(true);
            }
            return;
        }

        String typed = body.toLowerCase(Locale.ROOT);
        List<String> out = new ArrayList<>();
        try {
            for (Map.Entry<String, Command> en : Bukkit.getCommandMap().getKnownCommands().entrySet()) {
                String name = en.getKey().toLowerCase(Locale.ROOT);
                if (!name.startsWith(typed) || !show(name)) continue;
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

    // 3) typing the plugin-list commands directly
    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onCommand(PlayerCommandPreprocessEvent e) {
        if (!enabled || !blockEnabled) return;
        Player p = e.getPlayer();
        if (exempt(p)) return;

        String msg = e.getMessage();
        String body = msg.startsWith("/") ? msg.substring(1) : msg;
        int sp = body.indexOf(' ');
        String label = norm(sp >= 0 ? body.substring(0, sp) : body);
        boolean namespaced = label.indexOf(':') >= 0;
        String base = label.substring(label.lastIndexOf(':') + 1);

        if (blocked.contains(label) || blocked.contains(base) || (blockNamespaced && namespaced)) {
            e.setCancelled(true);
            p.sendMessage(AlertManager.c(message));
            if (alertOnAttempt) {
                alerts.alert("PLUGIN-SCAN", p.getName(), "/" + (label.length() > 40 ? label.substring(0, 40) : label));
            }
        }
    }
}
