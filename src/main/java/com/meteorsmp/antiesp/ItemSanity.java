package com.meteorsmp.antiesp;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.block.ShulkerBox;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCreativeEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.BlockStateMeta;
import org.bukkit.inventory.meta.EnchantmentStorageMeta;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.scheduler.BukkitTask;

import java.util.EnumSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/** Catches illegal / crash / oversized items. Bukkit API only, no packets. */
public final class ItemSanity implements Listener {

    private final AntiESPPlugin plugin;
    private final AlertManager alerts;
    private final Set<Material> banned = EnumSet.noneOf(Material.class);
    private BukkitTask task;

    private boolean enabled, scanOnJoin, allowUnsafe;
    private String action = "remove";
    private int maxBytes, maxName, maxLoreLines, maxLoreLen, periodic;

    public ItemSanity(AntiESPPlugin plugin, AlertManager alerts) {
        this.plugin = plugin;
        this.alerts = alerts;
    }

    public void reload() {
        var c = plugin.getConfig();
        String p = "item-checks.";
        enabled = c.getBoolean(p + "enabled", true);
        action = c.getString(p + "action", "remove").toLowerCase(Locale.ROOT);
        scanOnJoin = c.getBoolean(p + "scan-on-join", true);
        periodic = Math.max(0, c.getInt(p + "periodic-scan-seconds", 60));
        maxBytes = c.getInt(p + "max-serialized-bytes", 20000);
        maxName = c.getInt(p + "max-name-length", 120);
        maxLoreLines = c.getInt(p + "max-lore-lines", 20);
        maxLoreLen = c.getInt(p + "max-lore-line-length", 120);
        allowUnsafe = c.getBoolean(p + "allow-unsafe-enchants", false);

        banned.clear();
        for (String s : c.getStringList(p + "banned-materials")) {
            Material m = Material.matchMaterial(s);
            if (m != null) banned.add(m);
            else plugin.getLogger().warning("Unknown material in item-checks.banned-materials: " + s);
        }

        if (task != null) task.cancel();
        task = null;
        if (enabled && periodic > 0) {
            task = Bukkit.getScheduler().runTaskTimer(plugin, this::scanAll, periodic * 20L, periodic * 20L);
        }
    }

    public void shutdown() {
        if (task != null) task.cancel();
    }

    private boolean exempt(Player p) {
        return p.hasPermission("meteorshield.bypass");
    }

    private static String plain(Component c) {
        return PlainTextComponentSerializer.plainText().serialize(c);
    }

    /** @return the reason the item is illegal, or null if it looks fine */
    private String check(ItemStack item, int depth) {
        if (item == null || item.getType().isAir()) return null;
        Material t = item.getType();
        if (banned.contains(t)) return "banned item " + t.name();
        if (item.getAmount() < 1 || item.getAmount() > item.getMaxStackSize()) {
            return "illegal stack " + item.getAmount() + "x " + t.name();
        }

        ItemMeta meta = item.getItemMeta();
        if (meta == null) return null;

        String r = checkEnchants(item, item.getEnchantments(), false);
        if (r == null && meta instanceof EnchantmentStorageMeta esm) r = checkEnchants(item, esm.getStoredEnchants(), true);
        if (r != null) return r;

        if (meta.hasDisplayName() && meta.displayName() != null) {
            int len = plain(meta.displayName()).length();
            if (len > maxName) return "name too long (" + len + ")";
        }
        List<Component> lore = meta.lore();
        if (lore != null) {
            if (lore.size() > maxLoreLines) return "lore too long (" + lore.size() + " lines)";
            for (Component line : lore) {
                if (plain(line).length() > maxLoreLen) return "lore line too long";
            }
        }
        try {
            int size = item.serializeAsBytes().length;
            if (size > maxBytes) return "oversized item data (" + size + " bytes)";
        } catch (Throwable ignored) {
        }

        if (depth < 3 && meta instanceof BlockStateMeta bsm && bsm.hasBlockState()
                && bsm.getBlockState() instanceof ShulkerBox box) {
            for (ItemStack in : box.getInventory().getContents()) {
                String rr = check(in, depth + 1);
                if (rr != null) return "in shulker: " + rr;
            }
        }
        return null;
    }

    private String checkEnchants(ItemStack item, Map<Enchantment, Integer> map, boolean stored) {
        for (Map.Entry<Enchantment, Integer> en : map.entrySet()) {
            Enchantment e = en.getKey();
            int lvl = en.getValue();
            String name = e.getKey().getKey();
            if (lvl < 1) return name + " invalid level " + lvl;
            if (allowUnsafe) continue;
            if (lvl > e.getMaxLevel()) return name + " level " + lvl + " (max " + e.getMaxLevel() + ")";
            if (stored) continue;
            if (!e.canEnchantItem(item)) return name + " cannot be on " + item.getType().name();
            for (Enchantment other : map.keySet()) {
                if (other != e && e.conflictsWith(other)) return name + " conflicts with " + other.getKey().getKey();
            }
        }
        return null;
    }

    private void handle(Player p, String reason, String where) {
        alerts.alert("ILLEGAL-ITEM", p.getName(), reason + " [" + where + "]");
        if (action.equals("kick")) {
            p.kick(AlertManager.c("&cYou were removed for carrying an illegal item."));
        }
    }

    /** Scans a whole inventory. Returns how many items were flagged. */
    private int scanInventory(Player p, Inventory inv, String where) {
        int flagged = 0;
        ItemStack[] contents = inv.getContents();
        for (int i = 0; i < contents.length; i++) {
            String r = check(contents[i], 0);
            if (r == null) continue;
            flagged++;
            handle(p, r, where);
            if (!action.equals("alert")) inv.setItem(i, null);
        }
        return flagged;
    }

    public int scan(Player p) {
        if (!enabled) return 0;
        return scanInventory(p, p.getInventory(), "inventory") + scanInventory(p, p.getEnderChest(), "enderchest");
    }

    private void scanAll() {
        for (Player p : Bukkit.getOnlinePlayers()) {
            if (!exempt(p)) scan(p);
        }
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent e) {
        if (!enabled || !scanOnJoin || exempt(e.getPlayer())) return;
        scan(e.getPlayer());
    }

    @EventHandler(ignoreCancelled = true)
    public void onCreative(InventoryCreativeEvent e) {
        if (!enabled || !(e.getWhoClicked() instanceof Player p) || exempt(p)) return;
        String r = check(e.getCursor(), 0);
        if (r == null) return;
        handle(p, r, "creative");
        if (!action.equals("alert")) e.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onClick(InventoryClickEvent e) {
        if (!enabled || !(e.getWhoClicked() instanceof Player p) || exempt(p)) return;
        String r = check(e.getCurrentItem(), 0);
        if (r != null) {
            handle(p, r, "click");
            if (!action.equals("alert")) e.setCurrentItem(null);
            return;
        }
        r = check(e.getCursor(), 0);
        if (r != null) {
            handle(p, r, "cursor");
            if (!action.equals("alert")) p.setItemOnCursor(null);
        }
    }
}
