package com.meteorsmp.antiesp;

import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Collection;
import java.util.List;
import java.util.logging.Logger;

final class TabListKeeper {

    private final Logger log;
    private Method getHandle;
    private Field connectionField;
    private Method sendMethod;
    private Method createInit;
    private boolean ready;

    TabListKeeper(Logger log) {
        this.log = log;
        try {
            Class<?> craftPlayer = Class.forName(Bukkit.getServer().getClass().getPackageName() + ".entity.CraftPlayer");
            getHandle = craftPlayer.getMethod("getHandle");
            Class<?> serverPlayer = Class.forName("net.minecraft.server.level.ServerPlayer");
            connectionField = serverPlayer.getField("connection");
            Class<?> packet = Class.forName("net.minecraft.network.protocol.Packet");
            Class<?> listener = Class.forName("net.minecraft.server.network.ServerGamePacketListenerImpl");
            sendMethod = listener.getMethod("send", packet);
            Class<?> info = Class.forName("net.minecraft.network.protocol.game.ClientboundPlayerInfoUpdatePacket");
            createInit = info.getMethod("createPlayerInitializing", Collection.class);
            ready = true;
        } catch (Throwable t) {
            log.warning("Tab list keeper could not start (" + t + "). Hidden players will leave the tab list.");
        }
    }

    boolean isReady() {
        return ready;
    }

    /** Puts target back into observer's tab list (call right after observer.hideEntity(target)). */
    void relist(Player observer, Player target) {
        if (!ready) return;
        try {
            Object obs = getHandle.invoke(observer);
            Object tgt = getHandle.invoke(target);
            Object connection = connectionField.get(obs);
            if (connection == null) return;
            sendMethod.invoke(connection, createInit.invoke(null, List.of(tgt)));
        } catch (Throwable t) {
            ready = false;
            log.warning("Tab list keeper stopped (" + t + "). Hidden players will leave the tab list.");
        }
    }
}
