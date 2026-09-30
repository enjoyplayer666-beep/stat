package ru.dscraft.mediaitems;

import java.util.HexFormat;
import java.util.List;
import org.bukkit.Bukkit;
import org.bukkit.Material;
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
import org.bukkit.packs.ResourcePack;

/** Меню "Текстуры" (menu: textures в shops.yml): автоматическая установка пака и ссылка для ручной. */
final class Textures implements Listener {

    private final MediaItemsPlugin plugin;

    private static final class Menu implements InventoryHolder {
        Inventory inv;

        @Override
        public Inventory getInventory() {
            return inv;
        }
    }

    Textures(MediaItemsPlugin plugin) {
        this.plugin = plugin;
    }

    private ConfigurationSection cfg() {
        ConfigurationSection s = plugin.getConfig().getConfigurationSection("textures");
        return s != null ? s : plugin.getConfig().createSection("textures");
    }

    private ItemStack item(String spec, String name, List<String> lore) {
        ItemStack it = plugin.items().parse(spec);
        if (it == null) it = new ItemStack(Material.PLAYER_HEAD);
        it.setAmount(1);
        ItemMeta m = it.getItemMeta();
        if (name != null) m.displayName(Text.mm(name));
        if (lore != null) m.lore(lore.stream().map(Text::mm).toList());
        m.addItemFlags(ItemFlag.values());
        it.setItemMeta(m);
        return it;
    }

    void open(Player p) {
        ConfigurationSection c = cfg();
        Menu menu = new Menu();
        Inventory inv = Bukkit.createInventory(menu, 27, Text.mm(c.getString("title", "<#8A6DC1>Текстуры")));
        menu.inv = inv;
        inv.setItem(c.getInt("auto.slot", 11), item(c.getString("auto.icon", "PLAYER_HEAD"),
                c.getString("auto.name", "<green>Автоматическая установка"), c.getStringList("auto.lore")));
        inv.setItem(c.getInt("manual.slot", 15), item(c.getString("manual.icon", "PLAYER_HEAD"),
                c.getString("manual.name", "<white>Ручная установка"), c.getStringList("manual.lore")));
        p.openInventory(inv);
    }

    @EventHandler
    public void onClick(InventoryClickEvent e) {
        if (!(e.getInventory().getHolder() instanceof Menu)) return;
        e.setCancelled(true);
        if (!(e.getWhoClicked() instanceof Player p) || e.getClickedInventory() != e.getInventory()) return;
        ConfigurationSection c = cfg();
        int slot = e.getRawSlot();
        if (slot == c.getInt("auto.slot", 11)) {
            p.closeInventory();
            sendPack(p, c);
        } else if (slot == c.getInt("manual.slot", 15)) {
            p.closeInventory();
            p.sendMessage(Text.MM.deserialize(c.getString("manual.message", "")
                    .replace("{link}", c.getString("link", ""))));
        }
    }

    @EventHandler
    public void onDrag(InventoryDragEvent e) {
        if (e.getInventory().getHolder() instanceof Menu) e.setCancelled(true);
    }

    /** auto-url в конфиге или, если пусто, пак из server.properties (resource-pack). */
    private void sendPack(Player p, ConfigurationSection c) {
        String url = c.getString("auto-url", "");
        String hash = c.getString("auto-sha1", "");
        if (url.isBlank()) {
            ResourcePack rp = Bukkit.getServerResourcePack();
            if (rp != null) {
                url = rp.getUrl();
                hash = rp.getHash();
            }
        }
        if (url == null || url.isBlank()) {
            p.sendMessage(Text.MM.deserialize(c.getString("no-pack", "<red>Пак не настроен.")));
            return;
        }
        byte[] sha = null;
        if (hash != null && hash.length() == 40) {
            try {
                sha = HexFormat.of().parseHex(hash);
            } catch (IllegalArgumentException ignored) {
            }
        }
        p.setResourcePack(url, sha);
    }
}
