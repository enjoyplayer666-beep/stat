package ru.dscraft.mediaitems;

import org.bukkit.Sound;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.PrepareAnvilEvent;
import org.bukkit.event.player.PlayerCommandPreprocessEvent;
import org.bukkit.inventory.AnvilInventory;
import org.bukkit.inventory.ItemStack;

import java.util.Locale;

/**
 * Улучшения через наковальню (любую, /anvil или /upgrade): вещь в первый слот, ресурс во второй -
 * в результате появляется новая вещь. Опыт не нужен, забирается ровно нужное количество ресурса.
 */
final class Anvils implements Listener {

    private final MediaItemsPlugin plugin;

    Anvils(MediaItemsPlugin plugin) {
        this.plugin = plugin;
    }

    private Shops.Upgrade find(ItemStack first, ItemStack second) {
        String a = plugin.items().idOf(first);
        String b = plugin.items().idOf(second);
        if (a == null || b == null || first.getAmount() < 1) return null;
        for (Shops.Upgrade u : plugin.shops().upgrades()) {
            if (u.item().equals(a) && u.with().equals(b) && second.getAmount() >= u.count()) return u;
        }
        return null;
    }

    @EventHandler(priority = EventPriority.HIGH)
    @SuppressWarnings("deprecation")
    public void onPrepare(PrepareAnvilEvent e) {
        AnvilInventory inv = e.getInventory();
        Shops.Upgrade u = find(inv.getItem(0), inv.getItem(1));
        if (u == null) {
            // наши ресурсы не должны чинить/зачаровывать вещи по-ванильному
            if (plugin.items().idOf(inv.getItem(1)) != null && plugin.items().idOf(inv.getItem(0)) != null) e.setResult(null);
            return;
        }
        e.setResult(u.result().clone());
        inv.setRepairCost(0);
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onTake(InventoryClickEvent e) {
        if (!(e.getInventory() instanceof AnvilInventory inv) || e.getRawSlot() != 2) return;
        if (!(e.getWhoClicked() instanceof Player p)) return;
        ItemStack first = inv.getItem(0);
        ItemStack second = inv.getItem(1);
        Shops.Upgrade u = find(first, second);
        if (u == null) return;
        e.setCancelled(true);
        ItemStack result = u.result().clone();
        boolean shift = e.isShiftClick();
        ItemStack cursor = p.getItemOnCursor();
        if (!shift && cursor != null && !cursor.getType().isAir()) return; // курсор занят - ничего не делаем
        // списываем: 1 вещь и нужное количество ресурса
        if (first.getAmount() > 1) first.setAmount(first.getAmount() - 1);
        else first = null;
        if (second.getAmount() > u.count()) second.setAmount(second.getAmount() - u.count());
        else second = null;
        inv.setItem(0, first);
        inv.setItem(1, second);
        inv.setItem(2, null);
        if (shift) {
            p.getInventory().addItem(result).values().forEach(l -> p.getWorld().dropItemNaturally(p.getLocation(), l));
        } else {
            p.setItemOnCursor(result);
        }
        p.playSound(p.getLocation(), Sound.BLOCK_ANVIL_USE, 1f, 1f);
        plugin.getServer().getScheduler().runTask(plugin, p::updateInventory);
    }

    /** /anvil и /upgrade открывают наковальню (перехватываем раньше Essentials). */
    @EventHandler(priority = EventPriority.LOWEST)
    public void onCommand(PlayerCommandPreprocessEvent e) {
        String cmd = e.getMessage().toLowerCase(Locale.ROOT).trim();
        int space = cmd.indexOf(' ');
        if (space > 0) cmd = cmd.substring(0, space);
        if (!plugin.getConfig().getStringList("anvil-commands").contains(cmd)) return;
        e.setCancelled(true);
        open(e.getPlayer());
    }

    @SuppressWarnings("deprecation")
    void open(Player p) {
        p.openAnvil(null, true);
    }
}
