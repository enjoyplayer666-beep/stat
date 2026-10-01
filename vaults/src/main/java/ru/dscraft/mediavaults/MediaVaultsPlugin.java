package ru.dscraft.mediavaults;

import com.destroystokyo.paper.profile.PlayerProfile;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.player.PlayerCommandPreprocessEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemFlag;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.inventory.meta.SkullMeta;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.profile.PlayerTextures;

import java.io.File;
import java.io.IOException;
import java.net.URL;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

/**
 * MediaVaults - хранилища как на сервере-образце: меню по /ec и по эндер-сундуку, 14 хранилищ по 54 слота.
 * Бесплатно по привилегии (default 1, vip 2, ultra 3, elite 4), остальные покупаются за коины (MediaCoins).
 */
public final class MediaVaultsPlugin extends JavaPlugin implements Listener {

    private static final MiniMessage MM = MiniMessage.miniMessage();
    /** Слоты хранилищ #1-#14 в меню. */
    private static final int[] SLOTS = {19, 20, 21, 22, 23, 24, 25, 28, 29, 30, 31, 32, 33, 34};
    private static final int[] RED = {0, 1, 7, 8, 9, 17, 36, 44, 45, 46, 52, 53};
    private static final int INFO = 4;
    private static final int CLOSE = 49;

    private static final String GREEN = "#30DA8E";
    private static final String GREEN_DOT = "#13EF8F";
    private static final String YELLOW = "#D8EF57";
    private static final String YELLOW_DOT = "#D7F243";
    private static final String ORANGE = "#E8A048";

    /** Меню выбора хранилища. */
    private static final class MenuHolder implements InventoryHolder {
        Inventory inv;

        @Override
        public Inventory getInventory() {
            return inv;
        }
    }

    /** Открытое хранилище: чьё и какое. */
    private static final class VaultHolder implements InventoryHolder {
        final UUID owner;
        final int number;
        Inventory inv;

        VaultHolder(UUID owner, int number) {
            this.owner = owner;
            this.number = number;
        }

        @Override
        public Inventory getInventory() {
            return inv;
        }
    }

    @Override
    public void onEnable() {
        saveDefaultConfig();
        getConfig().options().copyDefaults(true);
        saveConfig();
        getServer().getPluginManager().registerEvents(this, this);
        if (getCommand("ec") != null) getCommand("ec").setExecutor(this);
    }

    @Override
    public void onDisable() {
        for (Player p : Bukkit.getOnlinePlayers()) {
            if (p.getOpenInventory().getTopInventory().getHolder() instanceof VaultHolder v) save(v);
        }
    }

    // ---------------- коины (MediaCoins) ----------------

    private static long coins(UUID id) {
        try {
            Class<?> api = Class.forName("ru.dscraft.mediacoins.CoinsApi", true,
                    Bukkit.getPluginManager().getPlugin("MediaCoins").getClass().getClassLoader());
            return (long) api.getMethod("coins", UUID.class).invoke(null, id);
        } catch (Throwable t) {
            return 0;
        }
    }

    private static boolean take(UUID id, long amount) {
        try {
            Class<?> api = Class.forName("ru.dscraft.mediacoins.CoinsApi", true,
                    Bukkit.getPluginManager().getPlugin("MediaCoins").getClass().getClassLoader());
            return (boolean) api.getMethod("take", UUID.class, long.class).invoke(null, id, amount);
        } catch (Throwable t) {
            return false;
        }
    }

    // ---------------- доступ и данные ----------------

    private int total() {
        return Math.max(1, Math.min(SLOTS.length, getConfig().getInt("total", 14)));
    }

    private boolean staff(Player p) {
        if (p.isOp()) return true;
        for (String g : getConfig().getStringList("staff-groups")) {
            if (p.hasPermission("group." + g.toLowerCase(Locale.ROOT))) return true;
        }
        return false;
    }

    /** Бесплатные хранилища по привилегии. */
    private int free(Player p) {
        ConfigurationSection s = getConfig().getConfigurationSection("free");
        if (s == null) return 1;
        if (staff(p)) return s.getInt("elitesp", 4);
        for (String g : s.getKeys(false)) {
            if (g.equalsIgnoreCase("default")) continue;
            if (p.hasPermission("group." + g.toLowerCase(Locale.ROOT))) return s.getInt(g);
        }
        return s.getInt("default", 1);
    }

    private File file(UUID id) {
        return new File(getDataFolder(), "players/" + id + ".yml");
    }

    private YamlConfiguration data(UUID id) {
        return YamlConfiguration.loadConfiguration(file(id));
    }

    private int bought(UUID id) {
        return data(id).getInt("bought", 0);
    }

    /** Сколько хранилищ открыто: бесплатные + купленные. */
    private int owned(Player p) {
        return Math.min(total(), free(p) + bought(p.getUniqueId()));
    }

    // ---------------- меню ----------------

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (sender instanceof Player p) openMenu(p);
        return true;
    }

    /** /ec, /enderchest ... раньше Essentials. */
    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onCommand(PlayerCommandPreprocessEvent event) {
        String msg = event.getMessage();
        if (msg.length() < 2) return;
        String label = msg.substring(1).split(" ", 2)[0].toLowerCase(Locale.ROOT);
        int colon = label.indexOf(':');
        if (colon >= 0) label = label.substring(colon + 1);
        if (!List.of("ec", "enderchest", "echest", "vault", "vaults").contains(label)) return;
        // у опа /ec, /enderchest, /echest - от Essentials (в т.ч. /ec ник); хранилища - /vaults или эндер-сундук
        if (event.getPlayer().isOp() && List.of("ec", "enderchest", "echest").contains(label)) return;
        event.setCancelled(true);
        openMenu(event.getPlayer());
    }

    /** Эндер-сундук открывает хранилища. */
    @EventHandler(priority = EventPriority.HIGH)
    public void onEnderChest(PlayerInteractEvent event) {
        if (event.getAction() != Action.RIGHT_CLICK_BLOCK || event.getHand() != EquipmentSlot.HAND) return;
        if (event.getClickedBlock() == null || event.getClickedBlock().getType() != Material.ENDER_CHEST) return;
        if (event.getPlayer().isSneaking() && event.getItem() != null) return;
        event.setCancelled(true);
        openMenu(event.getPlayer());
    }

    private Component mm(String s, TagResolver... r) {
        return MM.deserialize(s, r).decorationIfAbsent(TextDecoration.ITALIC, TextDecoration.State.FALSE);
    }

    private List<Component> lore(String... lines) {
        List<Component> out = new ArrayList<>();
        for (String l : lines) out.add(l.isEmpty() ? Component.empty() : mm(l));
        return out;
    }

    private static String title(String color, String dot, String text) {
        return "<#AAAAAA>[</#AAAAAA><" + dot + ">●</" + dot + "><#AAAAAA>]</#AAAAAA>  <" + color + ">" + text + "</" + color + ">  "
                + "<#AAAAAA>[</#AAAAAA><" + dot + ">●</" + dot + "><#AAAAAA>]</#AAAAAA>";
    }

    void openMenu(Player p) {
        MenuHolder h = new MenuHolder();
        h.inv = Bukkit.createInventory(h, 54, mm("<#B41E1E>Хранилище</#B41E1E>"));
        ItemStack red = item(new ItemStack(Material.RED_STAINED_GLASS_PANE), mm(" "), List.of());
        for (int s : RED) h.inv.setItem(s, red);
        h.inv.setItem(INFO, item(head("info", Material.RED_CONCRETE), mm("<#FF5555>Хранилища</#FF5555>"), lore(
                "<#AAAAAA>Информация о хранилищах</#AAAAAA>",
                "",
                "<white>▸</white> <white>Открыто:</white> <" + GREEN + ">" + owned(p) + "</" + GREEN + "> <white>из</white> <"
                        + GREEN + ">" + total() + "</" + GREEN + ">",
                "<white>▸</white> <white>Коины:</white> <#FFFF55>" + coins(p.getUniqueId()) + "</#FFFF55>")));
        h.inv.setItem(CLOSE, item(new ItemStack(Material.REDSTONE), mm("<#FF5555>Закрыть</#FF5555>"), List.of()));

        int owned = owned(p);
        long price = getConfig().getLong("price", 50);
        for (int i = 0; i < total(); i++) {
            int n = i + 1;
            ItemStack it;
            if (n <= owned) {
                it = item(head("open", Material.PRISMARINE), mm(title(GREEN, GREEN_DOT, "Открыть хранилище #" + n)), lore(
                        "<#AAAAAA>Информация о хранилищах</#AAAAAA>",
                        "",
                        "<white>▸</white> <" + GREEN + "><bold>ИНФОРМАЦИЯ</bold></" + GREEN + ">",
                        "  <white>Личное хранилище</white>",
                        "  <white>на</white> <" + GREEN + ">54</" + GREEN + "> <white>слота.</white>",
                        "",
                        "<white>▸</white> <" + GREEN + ">Нажмите, чтобы открыть</" + GREEN + ">"));
            } else if (n == owned + 1) {
                it = item(head("buy", Material.GOLD_BLOCK), mm(title(YELLOW, YELLOW_DOT, "Покупка хранилища #" + n)), lore(
                        "<#AAAAAA>Информация о хранилищах</#AAAAAA>",
                        "",
                        "<white>▸</white> <" + YELLOW + "><bold>ИНФОРМАЦИЯ</bold></" + YELLOW + ">",
                        "  <white>Покупка хранилища для</white>",
                        "  <white>сохранения всех важных предметов.</white>",
                        "",
                        "<white>▸</white> <" + YELLOW + "><bold>ЦЕНА ХРАНИЛИЩА</bold></" + YELLOW + ">",
                        "  <white>Чтобы купить это хранилище,</white>",
                        "  <white>нужно иметь</white> <" + YELLOW + ">" + price + "</" + YELLOW + "> <white>коинов.</white>",
                        "  <white>Хранилище покупается на</white> <" + YELLOW + ">ВАЙП</" + YELLOW + "><white>.</white>",
                        "",
                        "<white>▸</white> <" + YELLOW + ">Нажмите, для покупки</" + YELLOW + ">"));
            } else {
                it = item(head("locked", Material.TERRACOTTA), mm(title(ORANGE, ORANGE, "Сначала купите предыдущее")), lore(
                        "<#AAAAAA>Информация о хранилищах</#AAAAAA>",
                        "",
                        "<white>▸</white> <" + ORANGE + "><bold>ИНФОРМАЦИЯ</bold></" + ORANGE + ">",
                        "  <white>Для открытия доступа</white>",
                        "  <white>купите все предыдущие хранилища.</white>"));
            }
            it.setAmount(n);
            h.inv.setItem(SLOTS[i], it);
        }
        p.openInventory(h.inv);
    }

    private ItemStack head(String key, Material fallback) {
        String texture = getConfig().getString("heads." + key, "");
        if ((texture == null || texture.isBlank()) && getConfig().getDefaults() != null) {
            texture = getConfig().getDefaults().getString("heads." + key, "");
        }
        if (texture == null || texture.isBlank()) return new ItemStack(fallback);
        String hash = texture.trim();
        if (hash.startsWith("http")) hash = hash.substring(hash.lastIndexOf('/') + 1);
        ItemStack head = new ItemStack(Material.PLAYER_HEAD);
        SkullMeta meta = (SkullMeta) head.getItemMeta();
        try {
            PlayerProfile profile = Bukkit.createProfile(UUID.nameUUIDFromBytes(("vault" + hash).getBytes()), "vault");
            PlayerTextures textures = profile.getTextures();
            textures.setSkin(new URL("http://textures.minecraft.net/texture/" + hash));
            profile.setTextures(textures);
            meta.setPlayerProfile(profile);
            head.setItemMeta(meta);
            return head;
        } catch (Exception e) {
            return new ItemStack(fallback);
        }
    }

    private static ItemStack item(ItemStack it, Component name, List<Component> lore) {
        ItemMeta meta = it.getItemMeta();
        meta.displayName(name);
        meta.lore(lore);
        meta.addItemFlags(ItemFlag.values());
        it.setItemMeta(meta);
        return it;
    }

    @EventHandler
    public void onMenuClick(InventoryClickEvent e) {
        if (!(e.getInventory().getHolder() instanceof MenuHolder)) return;
        e.setCancelled(true);
        if (!(e.getWhoClicked() instanceof Player p) || e.getClickedInventory() != e.getInventory()) return;
        int slot = e.getSlot();
        if (slot == CLOSE) {
            p.closeInventory();
            return;
        }
        int n = -1;
        for (int i = 0; i < total(); i++) if (SLOTS[i] == slot) n = i + 1;
        if (n < 0) return;
        int owned = owned(p);
        boolean needCoins = getConfig().getBoolean("open-requires-coins", true);
        if (n <= owned) {
            if (needCoins && coins(p.getUniqueId()) <= 0) {
                p.sendMessage(mm(getConfig().getString("messages.no-coins", "Без коинов хранилище не открывается.")));
                return;
            }
            openVault(p, n);
        } else if (n == owned + 1) {
            long price = getConfig().getLong("price", 50);
            if (!take(p.getUniqueId(), price)) {
                p.sendMessage(mm(getConfig().getString("messages.not-enough", "Недостаточно коинов!"),
                        Placeholder.unparsed("price", String.valueOf(price))));
                return;
            }
            YamlConfiguration d = data(p.getUniqueId());
            d.set("bought", d.getInt("bought", 0) + 1);
            saveData(p.getUniqueId(), d);
            p.sendMessage(mm(getConfig().getString("messages.bought", "Вы купили хранилище #<number>!"),
                    Placeholder.unparsed("number", String.valueOf(n))));
            openMenu(p); // иконка меняется, золотой блок переезжает на следующее
        }
    }

    @EventHandler
    public void onMenuDrag(InventoryDragEvent e) {
        if (e.getInventory().getHolder() instanceof MenuHolder) e.setCancelled(true);
    }

    // ---------------- хранилище ----------------

    private void openVault(Player p, int n) {
        VaultHolder h = new VaultHolder(p.getUniqueId(), n);
        h.inv = Bukkit.createInventory(h, 54, mm("<#B41E1E>Хранилище #" + n + "</#B41E1E>"));
        List<?> items = data(p.getUniqueId()).getList("vault." + n);
        if (items != null) {
            for (int i = 0; i < items.size() && i < 54; i++) {
                if (items.get(i) instanceof ItemStack it) h.inv.setItem(i, it);
            }
        }
        p.openInventory(h.inv);
    }

    @EventHandler
    public void onClose(InventoryCloseEvent e) {
        if (e.getInventory().getHolder() instanceof VaultHolder v) save(v);
    }

    private void save(VaultHolder v) {
        YamlConfiguration d = data(v.owner);
        d.set("vault." + v.number, java.util.Arrays.asList(v.inv.getContents()));
        saveData(v.owner, d);
    }

    private void saveData(UUID id, YamlConfiguration d) {
        File f = file(id);
        f.getParentFile().mkdirs();
        try {
            d.save(f);
        } catch (IOException ex) {
            getLogger().warning("Не удалось сохранить хранилище " + id + ": " + ex.getMessage());
        }
    }
}
