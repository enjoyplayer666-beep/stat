package ru.dscraft.mediaitems;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
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

/**
 * /menu items - меню для стаффа: ресурсы обменников, сеты, мечи, талисманы, прочее.
 * Доступ: право mediaitems.staffmenu или группа из staff-menu.groups (LuckPerms: group.<имя>).
 */
final class StaffMenu implements Listener, CommandExecutor, org.bukkit.command.TabCompleter {

    /** порядок сетов - как идут улучшения */
    private static final String[] SETS = {"leather", "chain", "iron", "diamond", "netherite", "amethyst", "obsidian",
            "majestic", "jungle", "cloud", "magma", "ice", "void", "doge", "space", "moon", "comet", "planet", "meteor",
            // сеты магазинов за коины
            "dragon", "poseidon", "lava", "cerber", "lucifer", "iceknight", "angel", "warrior"};
    private static final String[] PARTS = {"helmet", "chestplate", "leggings", "boots", "sword", "elytra"};
    /** ванильные ресурсы генераторов и обменников */
    private static final Material[] VANILLA = {Material.DIRT, Material.STONE, Material.COAL, Material.IRON_INGOT,
            Material.DIAMOND, Material.GUNPOWDER, Material.GOLD_INGOT, Material.AMETHYST_SHARD, Material.CRYING_OBSIDIAN,
            Material.MAGENTA_CONCRETE, Material.LIME_CONCRETE, Material.BEACON};

    private final MediaItemsPlugin plugin;

    private static final class Menu implements InventoryHolder {
        final String kind;
        final String arg;
        final int page;
        final Map<Integer, ItemStack> give = new LinkedHashMap<>();
        final Map<Integer, Runnable> actions = new LinkedHashMap<>();
        Inventory inv;

        Menu(String kind, String arg, int page) {
            this.kind = kind;
            this.arg = arg;
            this.page = page;
        }

        @Override
        public Inventory getInventory() {
            return inv;
        }
    }

    StaffMenu(MediaItemsPlugin plugin) {
        this.plugin = plugin;
    }

    private ConfigurationSection cfg() {
        ConfigurationSection s = plugin.getConfig().getConfigurationSection("staff-menu");
        return s != null ? s : plugin.getConfig().createSection("staff-menu");
    }

    boolean allowed(CommandSender s) {
        if (s.hasPermission("mediaitems.staffmenu")) return true;
        for (String g : cfg().getStringList("groups")) {
            if (s.hasPermission("group." + g.toLowerCase(Locale.ROOT))) return true;
        }
        return false;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command cmd, String label, String[] a) {
        if (!(sender instanceof Player p)) return true;
        if (a.length == 0 || !a[0].equalsIgnoreCase("items")) {
            p.sendMessage(Text.mm("<red>/menu items"));
            return true;
        }
        if (!allowed(p)) {
            // для не-стаффа команды будто нет
            p.sendMessage(net.kyori.adventure.text.Component.translatable("command.unknown.command")
                    .color(net.kyori.adventure.text.format.NamedTextColor.RED));
            return true;
        }
        openMain(p);
        return true;
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command cmd, String alias, String[] a) {
        return allowed(sender) && a.length == 1 && "items".startsWith(a[0].toLowerCase(Locale.ROOT)) ? List.of("items") : List.of();
    }

    /**
     * /menu items - меню предметов для стаффа. Своей команды /menu у плагина нет (её занимает меню
     * сервера DsMenu), поэтому перехватываем ввод; не-стаффу команда уходит в обычное /menu.
     */
    @EventHandler(priority = org.bukkit.event.EventPriority.LOW, ignoreCancelled = true)
    public void onMenuItems(org.bukkit.event.player.PlayerCommandPreprocessEvent e) {
        String[] parts = e.getMessage().substring(1).trim().split("\\s+");
        if (parts.length < 2 || !parts[1].equalsIgnoreCase("items")) return;
        String label = parts[0].toLowerCase(Locale.ROOT);
        int colon = label.indexOf(':');
        if (colon >= 0) label = label.substring(colon + 1);
        if (!label.equals("menu") && !label.equals("меню")) return;
        if (!allowed(e.getPlayer())) return;
        e.setCancelled(true);
        openMain(e.getPlayer());
    }

    // ---------- что куда ----------

    private Map<String, ItemStack> items() {
        return plugin.items().all();
    }

    private boolean isSetPart(String id) {
        for (String set : SETS) {
            for (String part : PARTS) {
                if (id.equals(set + "_" + part) || id.equals(set + "_" + part + "_2")) return true;
            }
        }
        return false;
    }

    private static boolean isSword(String id) {
        return id.endsWith("_sword") || id.endsWith("_sword_2");
    }

    private static boolean isTalisman(String id) {
        return id.startsWith("talisman_");
    }

    private static boolean isOther(String id) {
        return id.startsWith("booster_") || id.startsWith("firework_") || id.startsWith("prism_") || id.equals("candy")
                || id.equals("accel_component") || id.equals("elytra") || id.equals("elytra_2") || id.endsWith("_elytra_trade")
                || id.equals("obsidian_elytra") || id.equals("majestic_elytra") || id.equals("jungle_elytra");
    }

    private static boolean hidden(String id) {
        return id.equals("separator") || id.equals("anvil_hint");
    }

    private List<ItemStack> resources() {
        List<ItemStack> out = new ArrayList<>();
        for (Material m : VANILLA) out.add(new ItemStack(m));
        for (var e : items().entrySet()) {
            String id = e.getKey();
            if (hidden(id) || isSetPart(id) || isSword(id) || isTalisman(id) || isOther(id)) continue;
            out.add(e.getValue());
        }
        return out;
    }

    private List<ItemStack> byFilter(java.util.function.Predicate<String> f) {
        List<ItemStack> out = new ArrayList<>();
        for (var e : items().entrySet()) if (!hidden(e.getKey()) && f.test(e.getKey())) out.add(e.getValue());
        return out;
    }

    private List<ItemStack> setItems(String set) {
        List<ItemStack> out = new ArrayList<>();
        for (String suffix : new String[]{"", "_2"}) {
            for (String part : PARTS) {
                ItemStack it = items().get(set + "_" + part + suffix);
                if (it != null) out.add(it);
            }
        }
        if (set.equals("leather")) {
            for (String id : new String[]{"wooden_sword", "wooden_sword_2"}) {
                ItemStack it = items().get(id);
                if (it != null) out.add(it);
            }
        }
        return out;
    }

    // ---------- меню ----------

    private ItemStack button(String spec, String name, List<String> lore) {
        ItemStack it = plugin.items().parse(spec);
        if (it == null) it = new ItemStack(Material.PAPER);
        it.setAmount(1);
        ItemMeta m = it.getItemMeta();
        m.displayName(Text.mm(name));
        m.lore(lore == null ? null : lore.stream().map(Text::mm).toList());
        m.addItemFlags(ItemFlag.values());
        it.setItemMeta(m);
        return it;
    }

    private Inventory create(Menu menu, int size, String title) {
        Inventory inv = Bukkit.createInventory(menu, size, Text.mm(title));
        menu.inv = inv;
        ItemStack glass = button("BLACK_STAINED_GLASS_PANE", " ", null);
        for (int i = size - 9; i < size; i++) inv.setItem(i, glass);
        return inv;
    }

    void openMain(Player p) {
        Menu menu = new Menu("main", null, 0);
        Inventory inv = create(menu, 45, "<dark_gray>Меню предметов");
        List<String> hint = List.of("<gray>Нажми, чтобы открыть");
        Object[][] cats = {
                {11, "item:compressed_dirt", "<gold><b>Ресурсы обменников", "resources"},
                {13, "item:majestic_chestplate", "<aqua><b>Сеты", "sets"},
                {15, "item:majestic_sword", "<red><b>Мечи", "swords"},
                {29, "item:talisman_super", "<light_purple><b>Талисманы", "talismans"},
                {33, "item:booster_x10", "<yellow><b>Бустеры и прочее", "other"}};
        for (Object[] c : cats) {
            int slot = (Integer) c[0];
            inv.setItem(slot, button((String) c[1], (String) c[2], hint));
            String kind = (String) c[3];
            menu.actions.put(slot, () -> {
                if (kind.equals("sets")) openSets(p);
                else openList(p, kind, null, 0);
            });
        }
        p.openInventory(inv);
    }

    private void openSets(Player p) {
        Menu menu = new Menu("sets", null, 0);
        Inventory inv = create(menu, 45, "<dark_gray>Меню предметов › Сеты");
        int slot = 0;
        for (String set : SETS) {
            List<ItemStack> parts = setItems(set);
            if (parts.isEmpty()) continue;
            ItemStack icon = items().getOrDefault(set + "_chestplate", parts.get(0)).clone();
            ItemMeta m = icon.getItemMeta();
            List<net.kyori.adventure.text.Component> lore = new ArrayList<>();
            lore.add(Text.mm("<gray>Нажми, чтобы открыть сет"));
            m.lore(lore);
            m.addItemFlags(ItemFlag.values());
            icon.setItemMeta(m);
            inv.setItem(slot, icon);
            menu.actions.put(slot, () -> openList(p, "set", set, 0));
            slot++;
        }
        back(menu, p);
        p.openInventory(inv);
    }

    private void openList(Player p, String kind, String arg, int page) {
        List<ItemStack> list = switch (kind) {
            case "resources" -> resources();
            case "swords" -> byFilter(StaffMenu::isSword);
            case "talismans" -> byFilter(StaffMenu::isTalisman);
            case "other" -> byFilter(StaffMenu::isOther);
            case "set" -> setItems(arg);
            default -> List.of();
        };
        String title = switch (kind) {
            case "resources" -> "Ресурсы";
            case "swords" -> "Мечи";
            case "talismans" -> "Талисманы";
            case "other" -> "Бустеры и прочее";
            default -> "Сет";
        };
        int per = 45;
        int pages = Math.max(1, (list.size() + per - 1) / per);
        page = Math.max(0, Math.min(page, pages - 1));
        Menu menu = new Menu(kind, arg, page);
        Inventory inv = create(menu, 54, "<dark_gray>Меню предметов › " + title + (pages > 1 ? " (" + (page + 1) + "/" + pages + ")" : ""));
        for (int i = 0; i < per && page * per + i < list.size(); i++) {
            ItemStack it = list.get(page * per + i);
            inv.setItem(i, it);
            menu.give.put(i, it);
        }
        final int pg = page;
        if (page > 0) {
            inv.setItem(45, button("ARROW", "<yellow>← Назад", null));
            menu.actions.put(45, () -> openList(p, kind, arg, pg - 1));
        }
        if (page < pages - 1) {
            inv.setItem(53, button("ARROW", "<yellow>Вперёд →", null));
            menu.actions.put(53, () -> openList(p, kind, arg, pg + 1));
        }
        if (kind.equals("set")) {
            inv.setItem(51, button("CHEST", "<green><b>Взять весь сет", List.of("<gray>Все вещи этого сета в инвентарь")));
            menu.actions.put(51, () -> {
                for (ItemStack it : list) give(p, it, 1);
            });
        }
        inv.setItem(47, button("PAPER", "<gray>Подсказка", List.of("<white>ЛКМ <gray>- 1 шт.", "<white>Shift+ЛКМ <gray>- стак")));
        inv.setItem(49, button("BARRIER", "<red>В меню", null));
        menu.actions.put(49, () -> {
            if (kind.equals("set")) openSets(p);
            else openMain(p);
        });
        p.openInventory(inv);
    }

    private void back(Menu menu, Player p) {
        menu.inv.setItem(menu.inv.getSize() - 5, button("BARRIER", "<red>В меню", null));
        menu.actions.put(menu.inv.getSize() - 5, () -> openMain(p));
    }

    private static void give(Player p, ItemStack proto, int amount) {
        ItemStack it = proto.clone();
        it.setAmount(Math.max(1, Math.min(amount, it.getMaxStackSize())));
        p.getInventory().addItem(it).values().forEach(l -> p.getWorld().dropItemNaturally(p.getLocation(), l));
    }

    @EventHandler
    public void onClick(InventoryClickEvent e) {
        if (!(e.getInventory().getHolder() instanceof Menu menu)) return;
        e.setCancelled(true);
        if (!(e.getWhoClicked() instanceof Player p) || e.getClickedInventory() != e.getInventory()) return;
        if (!allowed(p)) {
            p.closeInventory();
            return;
        }
        int slot = e.getRawSlot();
        Runnable r = menu.actions.get(slot);
        if (r != null) {
            r.run();
            return;
        }
        ItemStack it = menu.give.get(slot);
        if (it != null) give(p, it, e.isShiftClick() ? it.getMaxStackSize() : 1);
    }

    @EventHandler
    public void onDrag(InventoryDragEvent e) {
        if (e.getInventory().getHolder() instanceof Menu) e.setCancelled(true);
    }
}
