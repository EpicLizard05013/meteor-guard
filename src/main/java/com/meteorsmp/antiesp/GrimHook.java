package com.meteorsmp.antiesp;

import org.bukkit.Bukkit;
import org.bukkit.event.Event;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.plugin.Plugin;

import java.lang.reflect.Method;

/** Logs GrimAC flags into the Shield alert log. Uses reflection, so nothing extra to compile against. */
public final class GrimHook {

    private final AntiESPPlugin plugin;
    private final AlertManager alerts;
    private final Listener dummy = new Listener() {
    };
    private volatile boolean broadcast;
    private boolean registered;

    public GrimHook(AntiESPPlugin plugin, AlertManager alerts) {
        this.plugin = plugin;
        this.alerts = alerts;
    }

    @SuppressWarnings("unchecked")
    public void reload() {
        var c = plugin.getConfig();
        broadcast = c.getBoolean("grim-hook.broadcast", false);
        if (registered || !c.getBoolean("grim-hook.enabled", true)) return;

        Plugin grim = Bukkit.getPluginManager().getPlugin("GrimAC");
        if (grim == null || !grim.isEnabled()) return;
        try {
            Class<?> raw = Class.forName("ac.grim.grimac.api.events.FlagEvent", false, grim.getClass().getClassLoader());
            if (!Event.class.isAssignableFrom(raw)) return;
            Bukkit.getPluginManager().registerEvent((Class<? extends Event>) raw, dummy, EventPriority.MONITOR,
                    (listener, event) -> handle(event), plugin, true);
            registered = true;
            plugin.getLogger().info("Hooked into GrimAC flag events.");
        } catch (Throwable t) {
            plugin.getLogger().warning("Could not hook GrimAC: " + t);
        }
    }

    private void handle(Event ev) {
        try {
            Object user = call(ev, "getPlayer");
            Object check = call(ev, "getCheck");
            String name = user == null ? "?" : String.valueOf(call(user, "getName"));
            String chk = check == null ? "?" : String.valueOf(call(check, "getCheckName"));
            Object verbose = call(ev, "getVerbose");
            alerts.alert("GRIM:" + chk, name, verbose == null ? "" : String.valueOf(verbose), broadcast);
        } catch (Throwable ignored) {
        }
    }

    private static Object call(Object o, String method) {
        try {
            Method m = o.getClass().getMethod(method);
            m.setAccessible(true);
            return m.invoke(o);
        } catch (Throwable t) {
            return null;
        }
    }
}
