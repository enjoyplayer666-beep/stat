package ru.dscraft.mediaitems;

import java.io.File;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemFlag;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

/**
 * Магазины за коины (coinshop.yml): НПС "Предметы" (категории -> вещи поштучно) и НПС "Сеты"
 * (ЛКМ - купить набор, ПКМ - предпросмотр). Покупка - в два клика: первый показывает
 * "Подтвердить покупку", второй списывает коины (MediaCoins из MediaEconomy).
 */
final class CoinShop implements Listener {

    private final MediaItemsPlugin plugin;
    private YamlConfiguration cfg = new YamlConfiguration();

    private static final class Menu implements InventoryHolder {
        final Map<Integer, Runnable> left = new HashMap<>();
        final Map<Integer, Runnable> right = new HashMap<>();
        /** слот -> что купить (подтверждение по второму клику) */
        final Map<Integer, Runnable> buy = new HashMap<>();
        final Map<Integer, ItemStack> shown = new HashMap<>();
        Integer confirm;
        Inventory inv;

        @Override
        public Inventory getInventory() {
            return inv;
        }
    }

    CoinShop(MediaItemsPlugin plugin) {
        this.plugin = plugin;
    }

    void load() {
        File f = new File(plugin.getDataFolder(), "coinshop.yml");
        if (!f.exists()) plugin.saveResource("coinshop.yml", false);
        cfg = YamlConfiguration.loadConfiguration(f);
    }

    // ---------------- окна ----------------

    private Inventory create(Menu menu, int size, String title, boolean border) {
        Inventory inv = Bukkit.createInventory(menu, size, Text.mm(title));
        menu.inv = inv;
        if (border) {
            ItemStack glass = icon(cfg.getString("border", "PURPLE_STAINED_GLASS_PANE"), " ", null);
            for (int i = 0; i < size; i++) {
                int row = i / 9, col = i % 9;
                if (row == 0 || row == size / 9 - 1 || col == 0 || col == 8) inv.setItem(i, glass);
            }
        }
        return inv;
    }

    private ItemStack icon(String spec, String name, List<String> lore) {
        ItemStack it = plugin.items().parse(spec);
        if (it == null) it = new ItemStack(Material.PAPER);
        it.setAmount(1);
        ItemMeta m = it.getItemMeta();
        if (name != null) m.displayName(Text.mm(name));
        if (lore != null) m.lore(lore.stream().map(Text::mm).toList());
        if (name != null && name.isBlank()) m.setHideTooltip(true);
        it.setItemMeta(m);
        return it;
    }

    /** Предмет витрины: описание предмета + пустая строка + цена. */
    private ItemStack priced(ItemStack proto, long price) {
        ItemStack it = proto.clone();
        ItemMeta m = it.getItemMeta();
        List<Component> lore = m.lore() == null ? new ArrayList<>() : new ArrayList<>(m.lore());
        lore.add(Component.empty());
        lore.add(Text.mm(cfg.getString("price-line", "<white>Цена: <yellow>{price}</yellow> коинов").replace("{price}", String.valueOf(price))));
        m.lore(lore);
        it.setItemMeta(m);
        return it;
    }

    /** Слоты внутри рамки 6-рядного окна: 10-16, 19-25, 28-34, 37-43. */
    private static final int[] INNER = {10, 11, 12, 13, 14, 15, 16, 19, 20, 21, 22, 23, 24, 25, 28, 29, 30, 31, 32, 33, 34, 37, 38, 39, 40, 41, 42, 43};

    /** НПС "Предметы": окно категорий. */
    void openCategories(Player p) {
        Menu menu = new Menu();
        Inventory inv = create(menu, 27, cfg.getString("items.title", "Категории товаров"), false);
        ConfigurationSection cats = cfg.getConfigurationSection("items.categories");
        if (cats != null) {
            for (String id : cats.getKeys(false)) {
                ConfigurationSection c = cats.getConfigurationSection(id);
                if (c == null) continue;
                int slot = c.getInt("slot");
                ItemStack it = icon(c.getString("icon", "CHEST"), c.getString("name", id), null);
                ItemMeta m = it.getItemMeta();
                m.addItemFlags(ItemFlag.HIDE_ENCHANTS);
                it.setItemMeta(m);
                inv.setItem(slot, it);
                menu.left.put(slot, () -> openCategory(p, id));
            }
        }
        p.openInventory(inv);
    }

    private void openCategory(Player p, String id) {
        ConfigurationSection c = cfg.getConfigurationSection("items.categories." + id);
        if (c == null) return;
        Menu menu = new Menu();
        Inventory inv = create(menu, 54, c.getString("title", id), true);
        int i = 0;
        for (Map<?, ?> e : c.getMapList("items")) {
            if (i >= INNER.length) break;
            ItemStack proto = plugin.items().parse("item:" + e.get("item"));
            Object priceObj = e.get("price");
            if (proto == null || !(priceObj instanceof Number n)) continue;
            long price = n.longValue();
            int slot = INNER[i++];
            ItemStack shown = priced(proto, price);
            inv.setItem(slot, shown);
            menu.shown.put(slot, shown);
            menu.buy.put(slot, () -> {
                if (pay(p, price)) give(p, List.of(proto));
            });
        }
        back(menu, p);
        p.openInventory(inv);
    }

    /** НПС "Сеты": наборы. */
    void openSets(Player p) {
        Menu menu = new Menu();
        Inventory inv = create(menu, 54, cfg.getString("sets.title", "Магазин Сетов"), true);
        ConfigurationSection sets = cfg.getConfigurationSection("sets.list");
        int i = 0;
        if (sets != null) {
            for (String id : sets.getKeys(false)) {
                ConfigurationSection s = sets.getConfigurationSection(id);
                if (s == null || i >= INNER.length) continue;
                long price = s.getLong("price");
                List<ItemStack> kit = kit(s);
                if (kit.isEmpty()) continue;
                int slot = INNER[i++];
                List<String> lore = new ArrayList<>();
                lore.add("");
                lore.add(cfg.getString("price-line", "<white>Цена: <yellow>{price}</yellow> коинов").replace("{price}", String.valueOf(price)));
                lore.add("");
                lore.addAll(cfg.getStringList("sets.hint"));
                ItemStack shown = icon("item:" + s.getString("icon", ""), s.getString("name", id), lore);
                ItemMeta m = shown.getItemMeta();
                m.addItemFlags(ItemFlag.values());
                shown.setItemMeta(m);
                inv.setItem(slot, shown);
                menu.shown.put(slot, shown);
                menu.buy.put(slot, () -> {
                    if (pay(p, price)) give(p, kit);
                });
                menu.right.put(slot, () -> openPreview(p, s, kit));
            }
        }
        p.openInventory(inv);
    }

    private void openPreview(Player p, ConfigurationSection s, List<ItemStack> kit) {
        Menu menu = new Menu();
        Inventory inv = create(menu, 54, s.getString("preview-title", s.getName()), false);
        for (int i = 0; i < kit.size() && i < 7; i++) inv.setItem(10 + i, kit.get(i));
        int backSlot = cfg.getInt("sets.preview-back.slot", 45);
        inv.setItem(backSlot, icon(cfg.getString("sets.preview-back.icon", "RED_CONCRETE"),
                cfg.getString("sets.preview-back.name", "<#FF8080>• Обратно"), null));
        menu.left.put(backSlot, () -> openSets(p));
        p.openInventory(inv);
    }

    private List<ItemStack> kit(ConfigurationSection s) {
        List<ItemStack> out = new ArrayList<>();
        for (String id : s.getStringList("items")) {
            ItemStack it = plugin.items().parse("item:" + id);
            if (it != null) out.add(it);
        }
        return out;
    }

    private void back(Menu menu, Player p) {
        int slot = cfg.getInt("back.slot", 49);
        menu.inv.setItem(slot, icon(cfg.getString("back.icon", "GRAY_SHULKER_BOX"), cfg.getString("back.name", "<#FF5555>Назад"), null));
        menu.left.put(slot, () -> openCategories(p));
    }

    // ---------------- покупка ----------------

    private boolean pay(Player p, long price) {
        if (!take(p.getUniqueId(), price)) {
            p.sendMessage(Text.mm(cfg.getString("messages.no-coins", "<red>• <white>Недостаточно коинов.")
                    .replace("{price}", String.valueOf(price)).replace("{coins}", String.valueOf(coins(p.getUniqueId())))));
            p.playSound(p.getLocation(), Sound.ENTITY_VILLAGER_NO, 1f, 1f);
            return false;
        }
        p.sendMessage(Text.mm(cfg.getString("messages.bought", "<green>• <white>Покупка совершена!").replace("{price}", String.valueOf(price))));
        p.playSound(p.getLocation(), Sound.ENTITY_EXPERIENCE_ORB_PICKUP, 1f, 1.2f);
        return true;
    }

    private static void give(Player p, List<ItemStack> items) {
        for (ItemStack proto : items) {
            p.getInventory().addItem(proto.clone()).values()
                    .forEach(l -> p.getWorld().dropItemNaturally(p.getLocation(), l));
        }
    }

    private static long coins(UUID id) {
        try {
            Class<?> api = Class.forName("ru.dscraft.mediacoins.CoinsApi", true,
                    Bukkit.getPluginManager().getPlugin("MediaEconomy").getClass().getClassLoader());
            return (long) api.getMethod("coins", UUID.class).invoke(null, id);
        } catch (Throwable t) {
            return 0;
        }
    }

    private static boolean take(UUID id, long amount) {
        try {
            Class<?> api = Class.forName("ru.dscraft.mediacoins.CoinsApi", true,
                    Bukkit.getPluginManager().getPlugin("MediaEconomy").getClass().getClassLoader());
            return (boolean) api.getMethod("take", UUID.class, long.class).invoke(null, id, amount);
        } catch (Throwable t) {
            return false;
        }
    }

    // ---------------- клики ----------------

    @EventHandler
    public void onClick(InventoryClickEvent e) {
        if (!(e.getInventory().getHolder() instanceof Menu menu)) return;
        e.setCancelled(true);
        if (!(e.getWhoClicked() instanceof Player p) || e.getClickedInventory() != e.getInventory()) return;
        int slot = e.getRawSlot();
        boolean right = e.getClick() == ClickType.RIGHT || e.getClick() == ClickType.SHIFT_RIGHT;
        if (right && menu.right.containsKey(slot)) {
            menu.right.get(slot).run();
            return;
        }
        Runnable r = menu.left.get(slot);
        if (r != null) {
            r.run();
            return;
        }
        Runnable buy = menu.buy.get(slot);
        if (buy == null) return;
        if (menu.confirm != null && menu.confirm == slot) {
            // второй клик - оплата, вернуть витрину
            menu.inv.setItem(slot, menu.shown.get(slot));
            menu.confirm = null;
            buy.run();
            return;
        }
        if (menu.confirm != null) menu.inv.setItem(menu.confirm, menu.shown.get(menu.confirm));
        menu.confirm = slot;
        menu.inv.setItem(slot, icon(cfg.getString("confirm.icon", "LIME_STAINED_GLASS_PANE"),
                cfg.getString("confirm.name", "<green>Подтвердить покупку"),
                cfg.getStringList("confirm.lore")));
        p.playSound(p.getLocation(), Sound.UI_BUTTON_CLICK, 0.6f, 1.4f);
    }

    @EventHandler
    public void onDrag(InventoryDragEvent e) {
        if (e.getInventory().getHolder() instanceof Menu) e.setCancelled(true);
    }
}
