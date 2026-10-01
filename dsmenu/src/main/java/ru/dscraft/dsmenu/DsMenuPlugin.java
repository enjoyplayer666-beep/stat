package ru.dscraft.dsmenu;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.player.PlayerChangedWorldEvent;
import org.bukkit.event.player.PlayerDropItemEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerRespawnEvent;
import org.bukkit.event.player.PlayerSwapHandItemsEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemFlag;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * DsMenu: главное меню (/menu, предмет-кремень в игровых мирах) и меню привилегий (/donate).
 * Все меню и предмет настраиваются в config.yml.
 */
public final class DsMenuPlugin extends ru.dscraft.mediaeconomy.Module implements Listener {

    /** &-коды и hex вида &#RRGGBB. */
    private static final LegacyComponentSerializer LEGACY = LegacyComponentSerializer.builder()
            .character('&')
            .hexColors()
            .build();

    private NamespacedKey menuItemKey;

    /** Открытое меню - по нему отличаем свой инвентарь от чужих. */
    private static final class Menu implements InventoryHolder {
        private Inventory inventory;
        private final String id;
        private final Map<Integer, String> actions = new HashMap<>();

        Menu(String id) {
            this.id = id;
        }

        @Override
        public Inventory getInventory() {
            return inventory;
        }
    }

    @Override
    public void onEnable() {
        saveDefaultConfig();
        menuItemKey = new NamespacedKey(this, "menu_item");
        getServer().getPluginManager().registerEvents(this, this);
        for (Player p : Bukkit.getOnlinePlayers()) syncMenuItem(p);
        getLogger().info("DsMenu включен: /menu, /donate");
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        String name = command.getName().toLowerCase();
        if (name.equals("dsmenu")) {
            if (args.length >= 1 && args[0].equalsIgnoreCase("reload")) {
                reloadConfig();
                for (Player p : Bukkit.getOnlinePlayers()) syncMenuItem(p);
                sender.sendMessage(text("&a[DsMenu] Конфиг перезагружен."));
            } else {
                sender.sendMessage(text("&7Использование: /dsmenu reload"));
            }
            return true;
        }
        if (!(sender instanceof Player player)) {
            sender.sendMessage("Меню можно открыть только в игре.");
            return true;
        }
        String menu = menuForCommand(label);
        if (menu == null) menu = menuForCommand(name);
        open(player, menu == null ? "main" : menu);
        return true;
    }

    /** Меню, которое открывает команда (menus.<id>.commands), или null. */
    private String menuForCommand(String label) {
        ConfigurationSection menus = getConfig().getConfigurationSection("menus");
        if (menus == null) return null;
        String l = label.toLowerCase();
        int colon = l.indexOf(':');
        if (colon >= 0) l = l.substring(colon + 1); // essentials:warps -> warps
        for (String id : menus.getKeys(false)) {
            for (String c : menus.getStringList(id + ".commands")) {
                if (c.equalsIgnoreCase(l)) return id;
            }
        }
        return null;
    }

    /** Команды меню (например /warps) перехватываются раньше других плагинов (Essentials). */
    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onMenuCommand(org.bukkit.event.player.PlayerCommandPreprocessEvent event) {
        String msg = event.getMessage().trim();
        if (msg.length() < 2) return;
        String[] parts = msg.substring(1).split("\\s+");
        if (parts.length != 1) return; // /warps <что-то> - отдаём дальше
        String menu = menuForCommand(parts[0]);
        if (menu == null) return;
        event.setCancelled(true);
        open(event.getPlayer(), menu);
    }

    // ---------------- меню ----------------

    private void open(Player player, String id) {
        ConfigurationSection s = getConfig().getConfigurationSection("menus." + id);
        if (s == null) {
            player.sendMessage(text("&cМеню не найдено: " + id));
            return;
        }
        int rows = Math.max(1, Math.min(6, s.getInt("rows", 3)));
        Menu menu = new Menu(id);
        Inventory inv = Bukkit.createInventory(menu, rows * 9, text(s.getString("title", "")));
        menu.inventory = inv;

        ConfigurationSection items = s.getConfigurationSection("items");
        if (items != null) {
            for (String key : items.getKeys(false)) {
                ConfigurationSection it = items.getConfigurationSection(key);
                if (it == null) continue;
                int slot = it.getInt("slot", -1);
                if (slot < 0 || slot >= inv.getSize()) continue;
                inv.setItem(slot, buildItem(it));
                menu.actions.put(slot, it.getString("action", "none"));
            }
        }
        player.openInventory(inv);
    }

    private ItemStack buildItem(ConfigurationSection s) {
        Material material = Material.matchMaterial(s.getString("material", "STONE"));
        if (material == null || material.isAir()) material = Material.BARRIER;
        ItemStack item = new ItemStack(material);
        ItemMeta meta = item.getItemMeta();
        if (meta != null) {
            meta.displayName(noItalic(text(s.getString("name", ""))));
            List<Component> lore = new ArrayList<>();
            for (String line : s.getStringList("lore")) lore.add(noItalic(text(line)));
            meta.lore(lore);
            if (s.getBoolean("hide-attributes", true)) meta.addItemFlags(ItemFlag.values());
            if (s.contains("custom-model-data")) meta.setCustomModelData(s.getInt("custom-model-data"));
            item.setItemMeta(meta);
        }
        return item;
    }

    @EventHandler
    public void onMenuDrag(InventoryDragEvent event) {
        if (event.getInventory().getHolder() instanceof Menu) event.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onMenuClick(InventoryClickEvent event) {
        if (!(event.getWhoClicked() instanceof Player player)) return;

        // предмет меню нельзя вынуть или переложить; клик по нему открывает меню
        if (isMenuItem(event.getCurrentItem()) || isMenuItem(event.getCursor())
                || (event.getHotbarButton() >= 0 && isMenuItem(player.getInventory().getItem(event.getHotbarButton())))) {
            event.setCancelled(true);
            if (!(event.getInventory().getHolder() instanceof Menu) && isMenuItem(event.getCurrentItem())) {
                Bukkit.getScheduler().runTask(this, () -> openFromItem(player));
            }
            return;
        }

        if (!(event.getInventory().getHolder() instanceof Menu menu)) return;
        event.setCancelled(true);
        if (event.getClickedInventory() != event.getInventory()) return;

        String action = menu.actions.get(event.getRawSlot());
        if (action == null) return;
        String lower = action.toLowerCase();
        if (lower.equals("close")) {
            player.closeInventory();
        } else if (lower.equals("message")) {
            ConfigurationSection s = getConfig().getConfigurationSection("menus." + menu.id);
            String msg = s == null ? "" : s.getString("click-message", "");
            if (msg != null && !msg.isEmpty()) {
                Component c = text(msg);
                String url = s.getString("click-url", "");
                if (url != null && !url.isEmpty()) c = c.clickEvent(ClickEvent.openUrl(url));
                player.closeInventory();
                player.sendMessage(c);
            }
        } else if (lower.startsWith("command:")) {
            String cmd = action.substring(8).trim();
            if (cmd.startsWith("/")) cmd = cmd.substring(1);
            player.closeInventory();
            if (!cmd.isEmpty()) player.performCommand(cmd);
        } else if (lower.startsWith("open:")) {
            String target = action.substring(5).trim();
            Bukkit.getScheduler().runTask(this, () -> open(player, target));
        }
    }

    // ---------------- предмет меню ----------------

    private void openFromItem(Player player) {
        open(player, getConfig().getString("menu-item.opens", "main"));
    }

    private boolean isMenuItem(ItemStack item) {
        if (item == null || !item.hasItemMeta()) return false;
        return item.getItemMeta().getPersistentDataContainer().has(menuItemKey, PersistentDataType.BYTE);
    }

    private ItemStack menuItem() {
        ItemStack item = buildItem(getConfig().getConfigurationSection("menu-item"));
        ItemMeta meta = item.getItemMeta();
        meta.getPersistentDataContainer().set(menuItemKey, PersistentDataType.BYTE, (byte) 1);
        item.setItemMeta(meta);
        return item;
    }

    private boolean inLobby(Player player) {
        return getConfig().getStringList("menu-item.lobby-worlds").stream()
                .anyMatch(w -> w.equalsIgnoreCase(player.getWorld().getName()));
    }

    /** В игровом мире - выдать (если нет), в лобби - забрать. */
    private void syncMenuItem(Player player) {
        if (!player.isOnline()) return;
        PlayerInventory inv = player.getInventory();
        boolean want = getConfig().getBoolean("menu-item.enabled", true) && !inLobby(player);

        int has = -1;
        for (int i = 0; i < inv.getSize(); i++) {
            if (isMenuItem(inv.getItem(i))) {
                if (!want || has >= 0) inv.setItem(i, null);
                else has = i;
            }
        }
        if (!want || has >= 0) return;

        int slot = Math.max(0, Math.min(8, getConfig().getInt("menu-item.slot", 8)));
        ItemStack current = inv.getItem(slot);
        inv.setItem(slot, menuItem());
        if (current != null && !current.getType().isAir()) {
            // что лежало в слоте - в свободное место, если его нет - под ноги
            for (ItemStack left : inv.addItem(current).values()) {
                player.getWorld().dropItemNaturally(player.getLocation(), left);
            }
        }
    }

    private void syncLater(Player player, long ticks) {
        Bukkit.getScheduler().runTaskLater(this, () -> syncMenuItem(player), ticks);
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        // DestroyLobby переносит в лобби при заходе - проверяем после него
        syncLater(event.getPlayer(), 10L);
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onWorldChange(PlayerChangedWorldEvent event) {
        syncLater(event.getPlayer(), 2L);
    }

    @EventHandler
    public void onRespawn(PlayerRespawnEvent event) {
        syncLater(event.getPlayer(), 2L);
    }

    @EventHandler
    public void onDeath(PlayerDeathEvent event) {
        event.getDrops().removeIf(this::isMenuItem);
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onDrop(PlayerDropItemEvent event) {
        if (isMenuItem(event.getItemDrop().getItemStack())) event.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onSwap(PlayerSwapHandItemsEvent event) {
        if (isMenuItem(event.getMainHandItem()) || isMenuItem(event.getOffHandItem())) event.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onInteract(PlayerInteractEvent event) {
        if (event.getAction() != Action.RIGHT_CLICK_AIR && event.getAction() != Action.RIGHT_CLICK_BLOCK) return;
        if (!isMenuItem(event.getItem())) return;
        event.setCancelled(true);
        openFromItem(event.getPlayer());
    }

    // ---------------- текст ----------------

    private static Component text(String raw) {
        if (raw == null || raw.isEmpty()) return Component.empty();
        return LEGACY.deserialize(raw.replace('§', '&'));
    }

    private static Component noItalic(Component c) {
        return c.decorationIfAbsent(TextDecoration.ITALIC, TextDecoration.State.FALSE);
    }
}
