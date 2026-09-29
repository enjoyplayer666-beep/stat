package ru.dscraft.mediaitems;

import io.papermc.paper.event.player.AsyncChatEvent;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import net.kyori.adventure.title.Title;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemFlag;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.scheduler.BukkitTask;

import java.io.File;
import java.io.IOException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Титулы: категории, закрытые титулы открываются из кейса случайно, выбранный титул виден после ника
 * в табе и над головой (MediaTab берёт его через {@link TitlesApi}). Цвет титула - из списка или свой
 * HEX/градиент через чат. Всё - в titles.yml, данные игроков - в titles-data.yml.
 */
final class Titles implements Listener {

    record Category(String id, String name, int slot, String menuIcon, String icon, String lockedIcon, String border, List<String[]> titles) {
    }

    /** Меню - по держателю инвентаря понимаем, какое окно открыто. */
    private static final class Menu implements InventoryHolder {
        final String kind;
        final String category;
        Inventory inv;
        boolean busy;

        Menu(String kind, String category) {
            this.kind = kind;
            this.category = category;
        }

        @Override
        public Inventory getInventory() {
            return inv;
        }
    }

    private static final Pattern HEX_INPUT = Pattern.compile("^##([0-9a-fA-F]{6})(?:##([0-9a-fA-F]{6}))?$");

    private final MediaItemsPlugin plugin;
    private final File dataFile;
    private YamlConfiguration cfg;
    private final Map<String, Category> categories = new LinkedHashMap<>();
    /** id титула -> [категория, текст] */
    private final Map<String, String[]> byId = new HashMap<>();

    private final Map<UUID, Set<String>> unlocked = new HashMap<>();
    private final Map<UUID, String> selected = new HashMap<>();
    private final Map<UUID, String> colors = new HashMap<>();

    /** ожидание своего цвета в чате: игрок -> задача таймера */
    private final Map<UUID, BukkitTask> waiting = new HashMap<>();
    private final Random random = new Random();

    Titles(MediaItemsPlugin plugin) {
        this.plugin = plugin;
        this.dataFile = new File(plugin.getDataFolder(), "titles-data.yml");
    }

    // ---------- загрузка ----------

    void load() {
        File f = new File(plugin.getDataFolder(), "titles.yml");
        if (!f.exists()) plugin.saveResource("titles.yml", false);
        cfg = YamlConfiguration.loadConfiguration(f);
        categories.clear();
        byId.clear();
        ConfigurationSection cats = cfg.getConfigurationSection("categories");
        if (cats != null) {
            for (String id : cats.getKeys(false)) {
                ConfigurationSection c = cats.getConfigurationSection(id);
                List<String[]> list = new ArrayList<>();
                for (Map<?, ?> t : c.getMapList("titles")) {
                    String tid = String.valueOf(t.get("id")).toLowerCase(Locale.ROOT);
                    String text = String.valueOf(t.get("text"));
                    list.add(new String[]{tid, text});
                    byId.put(tid, new String[]{id, text});
                }
                categories.put(id, new Category(id, c.getString("name", id), c.getInt("slot", 10),
                        c.getString("menu-icon", c.getString("icon", "NAME_TAG")), c.getString("icon", "NAME_TAG"), c.getString("locked-icon", "PURPLE_CONCRETE"),
                        c.getString("border", "PURPLE_STAINED_GLASS_PANE"), list));
            }
        }
        YamlConfiguration d = YamlConfiguration.loadConfiguration(dataFile);
        unlocked.clear();
        selected.clear();
        colors.clear();
        for (String key : d.getKeys(false)) {
            UUID id;
            try {
                id = UUID.fromString(key);
            } catch (IllegalArgumentException e) {
                continue;
            }
            unlocked.put(id, new HashSet<>(d.getStringList(key + ".unlocked")));
            if (d.contains(key + ".selected")) selected.put(id, d.getString(key + ".selected"));
            if (d.contains(key + ".color")) colors.put(id, d.getString(key + ".color"));
        }
    }

    void save() {
        YamlConfiguration d = new YamlConfiguration();
        Set<UUID> all = new HashSet<>(unlocked.keySet());
        all.addAll(selected.keySet());
        all.addAll(colors.keySet());
        for (UUID id : all) {
            d.set(id + ".unlocked", new ArrayList<>(unlocked.getOrDefault(id, Set.of())));
            d.set(id + ".selected", selected.get(id));
            d.set(id + ".color", colors.get(id));
        }
        try {
            d.save(dataFile);
        } catch (IOException e) {
            plugin.getLogger().warning("Не удалось сохранить titles-data.yml: " + e.getMessage());
        }
    }

    // ---------- для таба ----------

    /** Выбранный титул игрока с его цветом (или пусто). */
    Component title(Player p) {
        String id = selected.get(p.getUniqueId());
        if (id == null) return Component.empty();
        String[] t = byId.get(id);
        if (t == null || !unlocked.getOrDefault(p.getUniqueId(), Set.of()).contains(id)) return Component.empty();
        return Text.mm(colorize(colors.getOrDefault(p.getUniqueId(), cfg.getString("default-color", "#55FF55")), t[1]));
    }

    private static String colorize(String color, String text) {
        String safe = text.replace("<", "\\<");
        if (color.contains(":")) return "<gradient:" + color + ">" + safe + "</gradient>";
        return "<" + color + ">" + safe;
    }

    // ---------- меню ----------

    private ItemStack item(String spec, String name, List<String> lore) {
        ItemStack it = plugin.items().parse(spec);
        if (it == null) it = new ItemStack(Material.STONE);
        it.setAmount(1);
        ItemMeta m = it.getItemMeta();
        if (name != null) m.displayName(Text.mm(name));
        if (lore != null) m.lore(lore.stream().map(Text::mm).toList());
        m.addItemFlags(ItemFlag.values());
        it.setItemMeta(m);
        return it;
    }

    void openMain(Player p) {
        Menu menu = new Menu("main", null);
        Inventory inv = Bukkit.createInventory(menu, 54, Text.mm(cfg.getString("menu.title", "Выбор категорий")));
        menu.inv = inv;
        for (Category c : categories.values()) {
            inv.setItem(c.slot(), item(c.menuIcon(), c.name(), cfg.getStringList("menu.category-lore")));
        }
        inv.setItem(cfg.getInt("menu.color-slot", 48), item(cfg.getString("menu.color-icon", "WHITE_WOOL"),
                cfg.getString("menu.color-name", "<gold>Сменить цвет"), null));
        inv.setItem(cfg.getInt("menu.remove-slot", 50), item(cfg.getString("menu.remove-icon", "BARRIER"),
                cfg.getString("menu.remove-name", "<red>Снять титул"), null));
        p.openInventory(inv);
    }

    private void openCategory(Player p, Category c) {
        Menu menu = new Menu("category", c.id());
        Inventory inv = Bukkit.createInventory(menu, 54, Text.mm("<dark_gray>| </dark_gray>" + c.name() + "<dark_gray> |"));
        menu.inv = inv;
        ItemStack pane = item(c.border(), " ", null);
        for (int i = 0; i < 54; i++) {
            int row = i / 9, col = i % 9;
            if (row == 0 || row == 5 || col == 0 || col == 8) inv.setItem(i, pane);
        }
        inv.setItem(4, item(cfg.getString("case.icon", "CHEST"), cfg.getString("case.name", "<gold>Кейс с титулами"),
                cfg.getStringList("case.lore")));
        Set<String> have = unlocked.getOrDefault(p.getUniqueId(), Set.of());
        String sel = selected.get(p.getUniqueId());
        int slot = 10;
        for (String[] t : c.titles()) {
            while (slot < 45 && (slot % 9 == 0 || slot % 9 == 8)) slot++;
            if (slot >= 44) break;
            boolean open = have.contains(t[0]);
            List<String> lore = new ArrayList<>();
            lore.add(open ? (t[0].equals(sel) ? cfg.getString("title-selected", "<aqua>Выбран")
                    : cfg.getString("title-available", "<green>Доступен"))
                    : cfg.getString("title-locked", "<red>Заблокирован"));
            inv.setItem(slot, item(open ? c.icon() : c.lockedIcon(),
                    cfg.getString("title-name", "<white>Титул: <yellow>{title}").replace("{title}", t[1].replace("<", "\\<")), lore));
            slot++;
        }
        inv.setItem(49, item(cfg.getString("menu.back-icon", "ARROW"), cfg.getString("menu.back-name", "<gray>Назад"), null));
        p.openInventory(inv);
    }

    private void openColors(Player p) {
        Menu menu = new Menu("colors", null);
        Inventory inv = Bukkit.createInventory(menu, 54, Text.mm(cfg.getString("colors.title", "Выбор цвета")));
        menu.inv = inv;
        ConfigurationSection list = cfg.getConfigurationSection("colors.list");
        if (list != null) {
            for (String key : list.getKeys(false)) {
                ConfigurationSection c = list.getConfigurationSection(key);
                String color = c.getString("color", "#FFFFFF");
                inv.setItem(c.getInt("slot"), item(c.getString("icon", "WHITE_CONCRETE"),
                        "<" + color + ">" + c.getString("name", key), cfg.getStringList("colors.lore")));
            }
        }
        inv.setItem(cfg.getInt("colors.custom-slot", 49), item(cfg.getString("colors.custom-icon", "PAINTING"),
                cfg.getString("colors.custom-name", "<gradient:#F1DA9C:#9035B0><i>Свой цвет"), cfg.getStringList("colors.custom-lore")));
        inv.setItem(45, item(cfg.getString("menu.back-icon", "ARROW"), cfg.getString("menu.back-name", "<gray>Назад"), null));
        p.openInventory(inv);
    }

    // ---------- клики ----------

    @EventHandler(priority = EventPriority.HIGH)
    public void onClick(InventoryClickEvent e) {
        if (!(e.getInventory().getHolder() instanceof Menu menu)) return;
        e.setCancelled(true);
        if (!(e.getWhoClicked() instanceof Player p) || e.getClickedInventory() != e.getInventory()) return;
        int slot = e.getRawSlot();
        if (menu.busy) return;
        switch (menu.kind) {
            case "main" -> {
                if (slot == cfg.getInt("menu.color-slot", 48)) {
                    openColors(p);
                    return;
                }
                if (slot == cfg.getInt("menu.remove-slot", 50)) {
                    selected.remove(p.getUniqueId());
                    save();
                    p.sendMessage(Text.mm(cfg.getString("messages.removed", "<red>Титул снят.")));
                    p.playSound(p.getLocation(), Sound.UI_BUTTON_CLICK, 1f, 1f);
                    return;
                }
                for (Category c : categories.values()) {
                    if (c.slot() == slot) {
                        openCategory(p, c);
                        return;
                    }
                }
            }
            case "category" -> {
                Category c = categories.get(menu.category);
                if (c == null) return;
                if (slot == 49) {
                    openMain(p);
                    return;
                }
                if (slot == 4) {
                    openCase(p, c);
                    return;
                }
                int idx = 0;
                for (int s = 10; s < 44; s++) {
                    if (s % 9 == 0 || s % 9 == 8) continue;
                    if (s == slot && idx < c.titles().size()) {
                        String[] t = c.titles().get(idx);
                        if (!unlocked.getOrDefault(p.getUniqueId(), Set.of()).contains(t[0])) {
                            p.sendMessage(Text.mm(cfg.getString("messages.locked", "<red>Этот титул ещё не открыт - он выпадает из кейса.")));
                            p.playSound(p.getLocation(), Sound.ENTITY_VILLAGER_NO, 1f, 1f);
                            return;
                        }
                        selected.put(p.getUniqueId(), t[0]);
                        save();
                        p.sendMessage(Text.mm(cfg.getString("messages.selected", "<green>Титул выбран: ").replace("{title}", "")).append(title(p)));
                        p.playSound(p.getLocation(), Sound.ENTITY_EXPERIENCE_ORB_PICKUP, 1f, 1.2f);
                        openCategory(p, c);
                        return;
                    }
                    idx++;
                }
            }
            case "colors" -> {
                if (slot == 45) {
                    openMain(p);
                    return;
                }
                if (slot == cfg.getInt("colors.custom-slot", 49)) {
                    if (!p.hasPermission(cfg.getString("colors.custom-permission", "mediaitems.titles.customcolor"))) {
                        p.sendMessage(Text.mm(cfg.getString("messages.no-custom", "<red>Свой цвет недоступен.")));
                        return;
                    }
                    p.closeInventory();
                    askColor(p);
                    return;
                }
                ConfigurationSection list = cfg.getConfigurationSection("colors.list");
                if (list == null) return;
                for (String key : list.getKeys(false)) {
                    if (list.getInt(key + ".slot") == slot) {
                        colors.put(p.getUniqueId(), list.getString(key + ".color", "#FFFFFF"));
                        save();
                        p.sendMessage(Text.mm(cfg.getString("messages.color", "<green>Цвет титула изменён.")));
                        p.playSound(p.getLocation(), Sound.UI_BUTTON_CLICK, 1f, 1f);
                        return;
                    }
                }
            }
            default -> {
            }
        }
    }

    @EventHandler
    public void onDrag(InventoryDragEvent e) {
        if (e.getInventory().getHolder() instanceof Menu) e.setCancelled(true);
    }

    /** Кейс: случайный ещё не открытый титул категории. Стоимость - предмет из case.cost (пусто - бесплатно). */
    private void openCase(Player p, Category c) {
        Set<String> have = unlocked.computeIfAbsent(p.getUniqueId(), k -> new HashSet<>());
        List<String[]> left = c.titles().stream().filter(t -> !have.contains(t[0])).toList();
        if (left.isEmpty()) {
            p.sendMessage(Text.mm(cfg.getString("messages.all-open", "<yellow>Все титулы этой категории уже открыты!")));
            return;
        }
        String cost = cfg.getString("case.cost", "");
        if (cost != null && !cost.isBlank()) {
            ItemStack need = plugin.items().parse(cost);
            if (need != null && !p.getInventory().containsAtLeast(need, need.getAmount())) {
                p.sendMessage(Text.mm(cfg.getString("messages.no-key", "<red>Нужен ключ от кейса.")));
                p.playSound(p.getLocation(), Sound.ENTITY_VILLAGER_NO, 1f, 1f);
                return;
            }
            if (need != null) p.getInventory().removeItem(need);
        }
        String[] t = left.get(random.nextInt(left.size()));
        have.add(t[0]);
        save();
        p.playSound(p.getLocation(), Sound.ENTITY_PLAYER_LEVELUP, 1f, 1.2f);
        // на месте кейса: "Выпало: титул" и отсчёт, потом окно обновится
        if (!(p.getOpenInventory().getTopInventory().getHolder() instanceof Menu menu)) {
            openCategory(p, c);
            return;
        }
        menu.busy = true;
        int seconds = Math.max(1, cfg.getInt("case.reveal-seconds", 3));
        int[] left2 = {seconds};
        String titleText = t[1].replace("<", "\\<");
        Bukkit.getScheduler().runTaskTimer(plugin, task -> {
            if (!p.isOnline() || p.getOpenInventory().getTopInventory() != menu.inv) {
                task.cancel();
                return;
            }
            if (left2[0] <= 0) {
                task.cancel();
                openCategory(p, c);
                return;
            }
            menu.inv.setItem(4, item(c.icon(), cfg.getString("case.dropped-name", "<green>Выпало: <yellow>{title}").replace("{title}", titleText),
                    List.of(cfg.getString("case.dropped-lore", "<gray>Инвентарь обновится через {s} сек...").replace("{s}", String.valueOf(left2[0])))));
            left2[0]--;
        }, 0L, 20L);
    }

    // ---------- свой цвет через чат ----------

    private void askColor(Player p) {
        cancelWait(p.getUniqueId());
        int seconds = cfg.getInt("colors.input-seconds", 30);
        int[] left = {seconds};
        BukkitTask task = Bukkit.getScheduler().runTaskTimer(plugin, () -> {
            if (!p.isOnline() || left[0] <= 0) {
                cancelWait(p.getUniqueId());
                if (p.isOnline()) p.sendMessage(Text.mm(cfg.getString("messages.input-timeout", "<red>Время вышло.")));
                return;
            }
            p.showTitle(Title.title(Text.mm(cfg.getString("colors.input-title", "<aqua>{s}с.").replace("{s}", String.valueOf(left[0]))),
                    Text.mm(cfg.getString("colors.input-subtitle", "<white>Ожидание текста...")),
                    Title.Times.times(Duration.ZERO, Duration.ofMillis(1200), Duration.ZERO)));
            left[0]--;
        }, 0L, 20L);
        waiting.put(p.getUniqueId(), task);
        p.sendMessage(Text.mm(cfg.getString("messages.input", "<gray>Напиши в чат свой цвет, например <white>##F1DA9C</white> или <white>##F1DA9C##9035B0</white>. <gray>Отмена - <white>отмена")));
    }

    private void cancelWait(UUID id) {
        BukkitTask t = waiting.remove(id);
        if (t != null) t.cancel();
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onChat(AsyncChatEvent e) {
        Player p = e.getPlayer();
        if (!waiting.containsKey(p.getUniqueId())) return;
        e.setCancelled(true);
        String msg = PlainTextComponentSerializer.plainText().serialize(e.message()).trim();
        Bukkit.getScheduler().runTask(plugin, () -> {
            cancelWait(p.getUniqueId());
            p.resetTitle();
            if (msg.equalsIgnoreCase("отмена") || msg.equalsIgnoreCase("cancel")) {
                p.sendMessage(Text.mm(cfg.getString("messages.input-cancel", "<gray>Отменено.")));
                return;
            }
            Matcher m = HEX_INPUT.matcher(msg);
            if (!m.matches()) {
                p.sendMessage(Text.mm(cfg.getString("messages.input-bad", "<red>Неверный формат. Пример: <white>##F1DA9C</white> или <white>##F1DA9C##9035B0")));
                return;
            }
            String color = m.group(2) == null ? "#" + m.group(1).toUpperCase(Locale.ROOT)
                    : "#" + m.group(1).toUpperCase(Locale.ROOT) + ":#" + m.group(2).toUpperCase(Locale.ROOT);
            colors.put(p.getUniqueId(), color);
            save();
            p.sendMessage(Text.mm(cfg.getString("messages.color", "<green>Цвет титула изменён.")));
        });
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent e) {
        cancelWait(e.getPlayer().getUniqueId());
    }

    // ---------- для админа ----------

    boolean unlock(UUID player, String titleId) {
        if (!byId.containsKey(titleId.toLowerCase(Locale.ROOT))) return false;
        unlocked.computeIfAbsent(player, k -> new HashSet<>()).add(titleId.toLowerCase(Locale.ROOT));
        save();
        return true;
    }

    Set<String> ids() {
        return byId.keySet();
    }
}
