package com.meteorsmp.antiesp;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.inventory.meta.SkullMeta;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/**
 * Paginated double-chest GUI for viewing players flagged for finding suspicious amounts of rare ores.
 */
public final class SuspiciousGUI implements Listener, CommandExecutor, TabCompleter {

    private static final int PAGE_SIZE = 45;
    private static final int SIZE = 54;
    private static final DateTimeFormatter TIME_FMT =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm").withZone(ZoneId.systemDefault());

    private static final class SuspiciousHolder implements InventoryHolder {
        Inventory inventory;
        int page;
        final UUID[] slotUuids = new UUID[PAGE_SIZE];

        @Override
        public Inventory getInventory() {
            return inventory;
        }
    }

    private final AntiESPPlugin plugin;
    private final SuspiciousManager manager;

    public SuspiciousGUI(AntiESPPlugin plugin, SuspiciousManager manager) {
        this.plugin = plugin;
        this.manager = manager;
    }

    public boolean canAccess(CommandSender sender) {
        return sender.hasPermission("meteorguard.suspicious")
                || sender.hasPermission("meteorguard.admin")
                || sender.hasPermission("antifreecam.admin");
    }

    public void open(Player viewer, int requestedPage) {
        List<SuspiciousManager.SuspiciousEntry> list = manager.getEntriesSorted();
        int totalPages = Math.max(1, (int) Math.ceil(list.size() / (double) PAGE_SIZE));
        int page = Math.max(0, Math.min(requestedPage, totalPages - 1));

        SuspiciousHolder holder = new SuspiciousHolder();
        holder.page = page;
        holder.inventory = Bukkit.createInventory(holder, SIZE,
                noItalic("&8Suspicious Players &7(" + (page + 1) + "/" + totalPages + ")"));

        int start = page * PAGE_SIZE;
        int end = Math.min(list.size(), start + PAGE_SIZE);
        for (int i = start; i < end; i++) {
            int slot = i - start;
            SuspiciousManager.SuspiciousEntry entry = list.get(i);
            holder.slotUuids[slot] = entry.uuid;
            holder.inventory.setItem(slot, createHead(entry));
        }

        ItemStack filler = button(Material.GRAY_STAINED_GLASS_PANE, " ", List.of());
        for (int slot = PAGE_SIZE; slot < SIZE; slot++) {
            holder.inventory.setItem(slot, filler);
        }

        if (page > 0) {
            holder.inventory.setItem(45, button(Material.ARROW, "&ePrevious Page",
                    List.of("&7Go to page " + page)));
        }

        holder.inventory.setItem(49, button(Material.BOOK, "&6Suspicious Log",
                List.of("&7Flagged players: &f" + list.size(),
                        "&7Page: &f" + (page + 1) + " / " + totalPages,
                        "&8Click a player head for options.")));

        holder.inventory.setItem(51, button(Material.CLOCK, "&bRefresh",
                List.of("&7Reload the suspicious list.")));

        if (page + 1 < totalPages) {
            holder.inventory.setItem(53, button(Material.ARROW, "&eNext Page",
                    List.of("&7Go to page " + (page + 2))));
        }

        viewer.openInventory(holder.inventory);
    }

    private ItemStack createHead(SuspiciousManager.SuspiciousEntry entry) {
        ItemStack head = new ItemStack(Material.PLAYER_HEAD);
        SkullMeta meta = (SkullMeta) head.getItemMeta();
        meta.setOwningPlayer(Bukkit.getOfflinePlayer(entry.uuid));

        Player online = Bukkit.getPlayer(entry.uuid);
        String status = online != null ? "&aOnline" : "&7Offline";
        meta.displayName(noItalic("&c" + entry.name + " &8(" + status + "&8)"));

        int totalBlocks = entry.totalBase + entry.totalOres;
        double ratio = totalBlocks > 0 ? (entry.totalOres * 100.0 / totalBlocks) : 0.0;

        List<Component> lore = new ArrayList<>();
        lore.add(noItalic("&7Flags: &c" + entry.flags + "x"));
        lore.add(noItalic(String.format(Locale.US, "&7Rare Ores: &b%d &8(&7Ratio: &e%.1f%%&8)", entry.totalOres, ratio)));

        if (!entry.oreBreakdown.isEmpty()) {
            lore.add(noItalic("&7Breakdown:"));
            for (Map.Entry<String, Integer> ore : entry.oreBreakdown.entrySet()) {
                lore.add(noItalic("  &8- &f" + prettify(ore.getKey()) + "&7: &b" + ore.getValue()));
            }
        }

        lore.add(noItalic("&7Last Reason: &f" + (entry.lastReason == null || entry.lastReason.isEmpty() ? "N/A" : entry.lastReason)));
        lore.add(noItalic("&7Last Location: &f" + entry.lastWorld + " " + entry.lastX + ", " + entry.lastY + ", " + entry.lastZ));
        lore.add(noItalic("&7Last Flagged: &f" + formatTime(entry.lastFlagged)));
        lore.add(noItalic("&7First Flagged: &f" + formatTime(entry.firstFlagged)));
        lore.add(noItalic(" "));
        lore.add(noItalic("&eLeft-Click: &7Teleport to player / location"));
        lore.add(noItalic("&bRight-Click: &7Inspect player"));
        lore.add(noItalic("&cShift+Right-Click: &7Remove from log"));

        meta.lore(lore);
        head.setItemMeta(meta);
        return head;
    }

    private static String formatTime(long ms) {
        if (ms <= 0) return "unknown";
        return TIME_FMT.format(Instant.ofEpochMilli(ms));
    }

    private static String prettify(String mat) {
        String lower = mat.toLowerCase(Locale.ROOT).replace('_', ' ');
        StringBuilder sb = new StringBuilder(lower.length());
        boolean cap = true;
        for (int i = 0; i < lower.length(); i++) {
            char ch = lower.charAt(i);
            if (cap && Character.isLetter(ch)) {
                sb.append(Character.toUpperCase(ch));
                cap = false;
            } else {
                sb.append(ch);
                cap = (ch == ' ');
            }
        }
        return sb.toString();
    }

    private static ItemStack button(Material mat, String name, List<String> loreLines) {
        ItemStack item = new ItemStack(mat);
        ItemMeta meta = item.getItemMeta();
        meta.displayName(noItalic(name));
        if (!loreLines.isEmpty()) {
            List<Component> lore = new ArrayList<>(loreLines.size());
            for (String line : loreLines) lore.add(noItalic(line));
            meta.lore(lore);
        }
        item.setItemMeta(meta);
        return item;
    }

    private static Component noItalic(String s) {
        return AlertManager.c(s).decoration(TextDecoration.ITALIC, false);
    }

    @EventHandler
    public void onClick(InventoryClickEvent event) {
        if (!(event.getInventory().getHolder() instanceof SuspiciousHolder holder)) return;
        event.setCancelled(true);
        if (!(event.getWhoClicked() instanceof Player player)) return;
        if (!canAccess(player)) {
            player.closeInventory();
            return;
        }

        int slot = event.getRawSlot();
        if (slot < 0 || slot >= SIZE) return;

        if (slot == 45 && holder.page > 0) {
            open(player, holder.page - 1);
            return;
        }
        if (slot == 51) {
            open(player, holder.page);
            return;
        }
        if (slot == 53) {
            open(player, holder.page + 1);
            return;
        }

        if (slot >= PAGE_SIZE) return;
        UUID targetId = holder.slotUuids[slot];
        if (targetId == null) return;

        SuspiciousManager.SuspiciousEntry entry = manager.getEntry(targetId);
        if (entry == null) {
            open(player, holder.page);
            return;
        }

        if (event.getClick() == ClickType.SHIFT_RIGHT) {
            manager.remove(targetId);
            player.sendMessage(AlertManager.c("&aRemoved &f" + entry.name + " &afrom the suspicious log."));
            open(player, holder.page);
            return;
        }

        if (event.isRightClick()) {
            Player online = Bukkit.getPlayer(targetId);
            if (online != null) {
                plugin.sendInspect(player, online);
            } else {
                player.sendMessage(AlertManager.c("&6=== " + entry.name + " (Offline) ==="));
                player.sendMessage(AlertManager.c("&7Flags: &c" + entry.flags + "x &7| Ores: &b" + entry.totalOres));
                player.sendMessage(AlertManager.c("&7Last Location: &f" + entry.lastWorld + " "
                        + entry.lastX + ", " + entry.lastY + ", " + entry.lastZ));
            }
            return;
        }

        if (event.isLeftClick()) {
            player.closeInventory();
            Player online = Bukkit.getPlayer(targetId);
            if (online != null) {
                player.teleport(online.getLocation());
                player.sendMessage(AlertManager.c("&aTeleported to &f" + online.getName() + "&a."));
            } else {
                World w = Bukkit.getWorld(entry.lastWorld);
                if (w == null) {
                    player.sendMessage(AlertManager.c("&cWorld '" + entry.lastWorld + "' is not loaded."));
                } else {
                    player.teleport(new Location(w, entry.lastX + 0.5, entry.lastY + 1.0, entry.lastZ + 0.5));
                    player.sendMessage(AlertManager.c("&aTeleported to &f" + entry.name + "&a's last flagged location."));
                }
            }
        }
    }

    public void sendConsoleList(CommandSender sender) {
        List<SuspiciousManager.SuspiciousEntry> list = manager.getEntriesSorted();
        sender.sendMessage(ChatColor.GOLD + "=== Suspicious Players (" + list.size() + ") ===");
        for (SuspiciousManager.SuspiciousEntry e : list) {
            sender.sendMessage(ChatColor.YELLOW + e.name + ChatColor.GRAY
                    + " | flags=" + e.flags + " | ores=" + e.totalOres
                    + " | last=" + e.lastWorld + " " + e.lastX + "," + e.lastY + "," + e.lastZ
                    + " | " + e.lastReason);
        }
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!canAccess(sender)) {
            if (plugin.getConfig().getBoolean("plugin-hider.hide-own-commands", true)) {
                String unknown = plugin.getConfig().getString("plugin-hider.block-commands.message",
                        "&cUnknown command. Type \"/help\" for help.");
                sender.sendMessage(AlertManager.c(unknown));
            } else {
                sender.sendMessage(AlertManager.c(plugin.getConfig().getString("messages.no-permission", "&cYou do not have permission!")));
            }
            return true;
        }

        if (sender instanceof Player player) {
            open(player, 0);
        } else {
            sendConsoleList(sender);
        }
        return true;
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        return List.of();
    }
}
