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

    private Items items;
    private Shops shops;
    private Npcs npcs;
    private Health health;
    private Titles titles;
    private Textures textures;
    private Auction auction;
    private int hdbRetries;

    @Override
    public void onEnable() {
        idKey = new NamespacedKey(this, "id");
        npcKey = new NamespacedKey(this, "npc");
        saveDefaultConfig();
        migrateHealth();
        for (String f : new String[]{"items.yml", "shops.yml"}) {
            if (!new File(getDataFolder(), f).exists()) saveResource(f, false);
        }
        items = new Items(idKey, getLogger());
        ItemsApi.init(items);
        shops = new Shops(this);
        npcs = new Npcs(this);
        loadAll();
        npcs.load();
        getServer().getPluginManager().registerEvents(npcs, this);
        getServer().getPluginManager().registerEvents(new Anvils(this), this);
        titles = new Titles(this);
        titles.load();
        TitlesApi.init(titles);
        getServer().getPluginManager().registerEvents(titles, this);
        textures = new Textures(this);
        getServer().getPluginManager().registerEvents(textures, this);
        auction = new Auction(this);
        getServer().getPluginManager().registerEvents(auction, this);
        health = new Health(this);
        getServer().getPluginManager().registerEvents(health, this);
        Bukkit.getScheduler().runTaskTimer(this, health::tick, 20L, 4L);
        getCommand("itemnpc").setTabCompleter(this);
        Bukkit.getScheduler().runTaskTimer(this, npcs::tick, 40L, 40L);
        getLogger().info("Предметов: " + items.all().size() + ", магазинов: " + shops.all().size() + ", НПС: " + npcs.all().size());
    }

    @Override
    public void onDisable() {
        if (npcs != null) npcs.save();
        if (titles != null) titles.save();
        if (health != null) health.shutdown();
    }

    private void loadAll() {
        reloadConfig();
        // нет файла (удалили/переименовали) - кладём стандартный, иначе магазинов будет 0 и НПС не откроются
        for (String f : new String[]{"items.yml", "shops.yml"}) {
            if (!new File(getDataFolder(), f).exists()) {
                saveResource(f, false);
                getLogger().warning(f + " не найден - создан стандартный.");
            }
        }
        YamlConfiguration itemsYml = YamlConfiguration.loadConfiguration(new File(getDataFolder(), "items.yml"));
        YamlConfiguration shopsYml = YamlConfiguration.loadConfiguration(new File(getDataFolder(), "shops.yml"));
        items.load(itemsYml.getConfigurationSection("items"), getConfig().getConfigurationSection("tooltip"));
        shops.load(shopsYml.getConfigurationSection("shops"), items);
        // головы из HeadDatabase: её база грузится после старта - перечитываем, пока все не найдутся
        if (items.hdbMissing && hdbRetries++ < 30) {
            Bukkit.getScheduler().runTaskLater(this, this::loadAll, 200L);
        } else if (!items.hdbMissing) {
            hdbRetries = 0;
        }
    }

    /** Старые значения по умолчанию строки HP (сердечки, высоко над ником) меняем на новые. */
    private void migrateHealth() {
        var c = getConfig();
        boolean changed = false;
        String f = c.getString("health.format", "");
        if (f.equals("<white>{hearts} <red>❤") || c.contains("health.below-name")) {
            c.set("health.format", "<white>{hp} <dark_red>❤");
            c.set("health.below-name", null);
            changed = true;
        }
        if (c.getDouble("health.offset", 0.03) == 0.3) {
            c.set("health.offset", 0.03);
            changed = true;
        }
        if (!c.contains("health.disabled-worlds") || c.getStringList("health.disabled-worlds").equals(java.util.List.of("lobby", "world_lobby", "hub"))) {
            c.set("health.disabled-worlds", java.util.List.of("world"));
            changed = true;
        }
        if (changed) saveConfig();
    }

    Items items() {
        return items;
    }

    Auction auction() {
        return auction;
    }

    Textures textures() {
        return textures;
    }

    Titles titles() {
        return titles;
    }

    Shops shops() {
        return shops;
    }

    String msg(String key) {
        return getConfig().getString("messages." + key, key);
    }

    @Override
    public boolean onCommand(CommandSender sender, Command cmd, String label, String[] a) {
        if (cmd.getName().equalsIgnoreCase("upgrade")) {
            if (sender instanceof Player p) new Anvils(this).open(p);
            return true;
        }
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
            case "sethead" -> {
                // /itemnpc sethead <предмет> [Value | хеш текстуры | ссылка textures.minecraft.net]
                // без текстуры - берётся голова из руки
                if (a.length < 2) return usage(sender, "/itemnpc sethead <предмет> [Value/хеш текстуры]  (или держи голову в руке)");
                String id = a[1].toLowerCase(Locale.ROOT);
                File f = new File(getDataFolder(), "items.yml");
                YamlConfiguration y = YamlConfiguration.loadConfiguration(f);
                if (!y.isConfigurationSection("items." + id)) return usage(sender, "Нет предмета '" + id + "' в items.yml");
                String tex = a.length > 2 ? a[2] : null;
                if (tex == null && sender instanceof Player p) {
                    ItemStack hand = p.getInventory().getItemInMainHand();
                    if (hand.getItemMeta() instanceof org.bukkit.inventory.meta.SkullMeta sm && sm.getPlayerProfile() != null) {
                        for (var prop : sm.getPlayerProfile().getProperties()) {
                            if (prop.getName().equals("textures")) tex = prop.getValue();
                        }
                    }
                }
                if (tex == null || tex.isBlank()) return usage(sender, "Укажи Value/хеш текстуры или возьми голову в руку.");
                y.set("items." + id + ".material", "PLAYER_HEAD");
                y.set("items." + id + ".head", tex);
                y.set("items." + id + ".model", null);
                y.set("items." + id + ".color", null);
                try {
                    y.save(f);
                } catch (java.io.IOException e) {
                    return usage(sender, "Не удалось сохранить items.yml: " + e.getMessage());
                }
                loadAll();
                npcs.respawnAll();
                sender.sendMessage(Text.mm("<green>Теперь <white>" + id + "</white> - голова. Выдать: /itemnpc give " + id));
            }
            case "title" -> {
                // /itemnpc title <игрок> <id титула> - открыть титул игроку
                if (a.length < 3) return usage(sender, "/itemnpc title <игрок> <id титула>");
                var target = Bukkit.getOfflinePlayer(a[1]);
                if (!titles.unlock(target.getUniqueId(), a[2])) return usage(sender, "Нет титула '" + a[2] + "' в titles.yml");
                sender.sendMessage(Text.mm("<green>Титул <white>" + a[2] + "</white> открыт игроку <white>" + a[1]));
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
                titles.load();
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
                <yellow>/itemnpc sethead <предмет> [текстура]</yellow> <gray>- сделать шлем головой (или голова в руке)
                <yellow>/itemnpc reload</yellow>"""));
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command cmd, String alias, String[] a) {
        List<String> out = new ArrayList<>();
        if (cmd.getName().equalsIgnoreCase("upgrade")) return out;
        if (a.length == 1) {
            out.addAll(List.of("create", "remove", "movehere", "list", "shops", "give", "sethead", "title", "reload"));
        } else if (a.length == 2) {
            switch (a[0].toLowerCase(Locale.ROOT)) {
                case "create" -> out.addAll(shops.all().keySet());
                case "remove", "movehere" -> out.addAll(npcs.all().keySet());
                case "give", "sethead" -> out.addAll(items.all().keySet());
                default -> {
                }
            }
        } else if (a.length == 3 && a[0].equalsIgnoreCase("title")) {
            out.addAll(titles.ids());
        } else if (a.length == 4 && a[0].equalsIgnoreCase("give")) {
            Bukkit.getOnlinePlayers().forEach(p -> out.add(p.getName()));
        }
        String last = a[a.length - 1].toLowerCase(Locale.ROOT);
        out.removeIf(s -> !s.toLowerCase(Locale.ROOT).startsWith(last));
        return out;
    }
}
