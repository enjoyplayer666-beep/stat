package ru.dscraft.mediaitems;

import org.bukkit.Bukkit;
import org.bukkit.NamespacedKey;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

public final class MediaItemsPlugin extends JavaPlugin implements TabCompleter {

    NamespacedKey idKey;
    NamespacedKey npcKey;
    NamespacedKey displayKey;

    private Items items;
    private Shops shops;
    private Npcs npcs;

    @Override
    public void onEnable() {
        idKey = new NamespacedKey(this, "id");
        npcKey = new NamespacedKey(this, "npc");
        displayKey = new NamespacedKey(this, "display");
        saveDefaultConfig();
        for (String f : new String[]{"items.yml", "shops.yml"}) {
            if (!new File(getDataFolder(), f).exists()) saveResource(f, false);
        }
        items = new Items(idKey, getLogger());
        shops = new Shops(this);
        npcs = new Npcs(this);
        loadAll();
        npcs.load();
        getServer().getPluginManager().registerEvents(npcs, this);
        getCommand("itemnpc").setTabCompleter(this);
        Bukkit.getScheduler().runTaskTimer(this, npcs::tick, 40L, 40L);
        getLogger().info("Предметов: " + items.all().size() + ", магазинов: " + shops.all().size() + ", НПС: " + npcs.all().size());
    }

    @Override
    public void onDisable() {
        if (npcs != null) npcs.save();
    }

    private void loadAll() {
        reloadConfig();
        YamlConfiguration itemsYml = YamlConfiguration.loadConfiguration(new File(getDataFolder(), "items.yml"));
        YamlConfiguration shopsYml = YamlConfiguration.loadConfiguration(new File(getDataFolder(), "shops.yml"));
        items.load(itemsYml.getConfigurationSection("items"), getConfig().getConfigurationSection("tooltip"));
        shops.load(shopsYml.getConfigurationSection("shops"), items);
    }

    Items items() {
        return items;
    }

    Shops shops() {
        return shops;
    }

    String msg(String key) {
        return getConfig().getString("messages." + key, key);
    }

    @Override
    public boolean onCommand(CommandSender sender, Command cmd, String label, String[] a) {
        if (a.length == 0) {
            help(sender);
            return true;
        }
        switch (a[0].toLowerCase(Locale.ROOT)) {
            case "create" -> {
                if (!(sender instanceof Player p)) return player(sender);
                if (a.length < 2) return usage(sender, "/itemnpc create <магазин>  (список: /itemnpc shops)");
                Shops.Shop shop = shops.get(a[1]);
                if (shop == null) return usage(sender, "Нет магазина '" + a[1] + "'. Список: /itemnpc shops");
                Npcs.Npc n = npcs.create(p, shop);
                sender.sendMessage(Text.mm("<green>НПС <white>" + n.id + "</white> поставлен (магазин <white>" + shop.id() + "</white>)."));
            }
            case "remove" -> {
                Npcs.Npc n = find(sender, a);
                if (n == null) return true;
                npcs.remove(n);
                sender.sendMessage(Text.mm("<green>НПС <white>" + n.id + "</white> удалён."));
            }
            case "movehere" -> {
                if (!(sender instanceof Player p)) return player(sender);
                Npcs.Npc n = find(sender, a);
                if (n == null) return true;
                npcs.moveHere(n, p);
                sender.sendMessage(Text.mm("<green>НПС <white>" + n.id + "</white> перенесён."));
            }
            case "list" -> {
                if (npcs.all().isEmpty()) sender.sendMessage(Text.mm("<gray>НПС пока нет."));
                for (Npcs.Npc n : npcs.all().values()) {
                    sender.sendMessage(Text.mm("<gray>- <white>" + n.id + "</white> (" + n.shop + ") "
                            + n.loc.getWorld().getName() + " " + n.loc.getBlockX() + " " + n.loc.getBlockY() + " " + n.loc.getBlockZ()
                            + (n.fancy != null ? " <aqua>[FancyNpcs " + n.fancy + "]" : "")));
                }
            }
            case "shops" -> {
                for (Shops.Shop s : shops.all().values()) {
                    sender.sendMessage(Text.mm("<gray>- <white>" + s.id() + "</white> ").append(Text.mm(s.name()))
                            .append(Text.mm("<gray> (обменов: " + s.trades().size() + ")")));
                }
            }
            case "give" -> {
                if (a.length < 2) return usage(sender, "/itemnpc give <предмет> [кол-во] [игрок]");
                ItemStack it = items.get(a[1]);
                if (it == null) return usage(sender, "Нет предмета '" + a[1] + "'. Все предметы в items.yml");
                if (a.length > 2) it.setAmount(Math.max(1, Integer.parseInt(a[2])));
                Player target = a.length > 3 ? Bukkit.getPlayerExact(a[3]) : sender instanceof Player p ? p : null;
                if (target == null) return usage(sender, "Игрок не найден.");
                target.getInventory().addItem(it).values().forEach(l -> target.getWorld().dropItemNaturally(target.getLocation(), l));
                sender.sendMessage(Text.mm("<green>Выдано."));
            }
            case "open" -> {
                // для FancyNpcs: console_command itemnpc open {player} <магазин>
                if (a.length < 3) return usage(sender, "/itemnpc open <игрок> <магазин>");
                Player target = Bukkit.getPlayerExact(a[1]);
                Shops.Shop shop = shops.get(a[2]);
                if (target != null && shop != null) npcs.open(target, shop);
            }
            case "reload" -> {
                loadAll();
                npcs.respawnAll();
                sender.sendMessage(Text.mm("<green>Перезагружено: предметов " + items.all().size()
                        + ", магазинов " + shops.all().size() + ". НПС пересозданы."));
            }
            default -> help(sender);
        }
        return true;
    }

    /** НПС по id из аргумента, иначе ближайший к игроку (5 блоков). */
    private Npcs.Npc find(CommandSender sender, String[] a) {
        Npcs.Npc n = null;
        if (a.length > 1) n = npcs.all().get(a[1]);
        else if (sender instanceof Player p) n = npcs.nearest(p.getLocation(), 5);
        if (n == null) sender.sendMessage(Text.mm("<red>НПС не найден. Укажи id (/itemnpc list) или встань рядом."));
        return n;
    }

    private boolean usage(CommandSender s, String text) {
        s.sendMessage(Text.mm("<red>" + text));
        return true;
    }

    private boolean player(CommandSender s) {
        return usage(s, "Только для игрока.");
    }

    private void help(CommandSender s) {
        s.sendMessage(Text.mm("""
                <gold><b>MediaItems</b></gold>
                <yellow>/itemnpc create <магазин></yellow> <gray>- поставить НПС тут
                <yellow>/itemnpc remove [id]</yellow> <gray>- удалить (без id - ближайший)
                <yellow>/itemnpc movehere [id]</yellow> <gray>- перенести к себе
                <yellow>/itemnpc list</yellow> <gray>- все НПС,</gray> <yellow>/itemnpc shops</yellow> <gray>- магазины
                <yellow>/itemnpc give <предмет> [кол-во] [игрок]</yellow>
                <yellow>/itemnpc reload</yellow>"""));
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command cmd, String alias, String[] a) {
        List<String> out = new ArrayList<>();
        if (a.length == 1) {
            out.addAll(List.of("create", "remove", "movehere", "list", "shops", "give", "reload"));
        } else if (a.length == 2) {
            switch (a[0].toLowerCase(Locale.ROOT)) {
                case "create" -> out.addAll(shops.all().keySet());
                case "remove", "movehere" -> out.addAll(npcs.all().keySet());
                case "give" -> out.addAll(items.all().keySet());
                default -> {
                }
            }
        } else if (a.length == 4 && a[0].equalsIgnoreCase("give")) {
            Bukkit.getOnlinePlayers().forEach(p -> out.add(p.getName()));
        }
        String last = a[a.length - 1].toLowerCase(Locale.ROOT);
        out.removeIf(s -> !s.toLowerCase(Locale.ROOT).startsWith(last));
        return out;
    }
}
