package ru.dscraft.mediaitems;

import java.util.Map;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemFlag;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

/** Меню "Аукцион" (menu: auction в shops.yml): пока только витрина, на каждом предмете "пока в разработке". */
final class Auction implements Listener {

    private static final LegacyComponentSerializer LEGACY = LegacyComponentSerializer.builder()
            .character('&').hexColors().useUnusualXRepeatedCharacterHexFormat().build();

    private final MediaItemsPlugin plugin;

    private static final class Menu implements InventoryHolder {
        Inventory inv;

        @Override
        public Inventory getInventory() {
            return inv;
        }
    }

    Auction(MediaItemsPlugin plugin) {
        this.plugin = plugin;
    }

    /** &-коды (&x&R&R&G&G&B&B) или MiniMessage. */
    private static Component text(String s) {
        if (s == null) return Component.empty();
        return s.indexOf('&') >= 0 ? LEGACY.deserialize(s) : Text.mm(s);
    }

    void open(Player p) {
        ConfigurationSection c = plugin.getConfig().getConfigurationSection("auction");
        if (c == null) return;
        Menu menu = new Menu();
        Inventory inv = Bukkit.createInventory(menu, 54, text(c.getString("title", "")));
        menu.inv = inv;
        Component name = text(c.getString("item-name", ""));
        for (Map<?, ?> m : c.getMapList("items")) {
            Object slot = m.get("slot");
            ItemStack it = plugin.items().parse(String.valueOf(m.get("item")));
            if (!(slot instanceof Number n) || it == null) continue;
            it.setAmount(1);
            ItemMeta meta = it.getItemMeta();
            meta.displayName(name);
            meta.lore(null);
            meta.addItemFlags(ItemFlag.values());
            it.setItemMeta(meta);
            inv.setItem(n.intValue(), it);
        }
        p.openInventory(inv);
    }

    @EventHandler
    public void onClick(InventoryClickEvent e) {
        if (e.getInventory().getHolder() instanceof Menu) e.setCancelled(true);
    }

    @EventHandler
    public void onDrag(InventoryDragEvent e) {
        if (e.getInventory().getHolder() instanceof Menu) e.setCancelled(true);
    }
}
