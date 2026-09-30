package ru.stat;

import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.OfflinePlayer;
import org.bukkit.Statistic;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.io.IOException;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class StatPlugin extends JavaPlugin implements CommandExecutor, TabCompleter, Listener {

    private static final List<String> FIELDS =
            Arrays.asList("privilege", "rating", "winrate", "deaths");
    private static final Pattern HEX = Pattern.compile("&#([A-Fa-f0-9]{6})");

    private File dataFile;
    private FileConfiguration data;
    private boolean dirty;
    private Method papiSet;
    private Method clanName;
    private ClassLoader clanLoader;
    private final Classes classes = new Classes();

    @Override
    public void onEnable() {
        saveDefaultConfig();
        migrateConfig();
        classes.load(getConfig());
        loadData();
        hookPapi();
        getCommand("stat").setExecutor(this);
        getCommand("stat").setTabCompleter(this);
        getCommand("mystat").setExecutor(this);
        getCommand("statadmin").setExecutor(this);
        getCommand("statadmin").setTabCompleter(this);
        getServer().getPluginManager().registerEvents(this, this);
        getServer().getPluginManager().registerEvents(new CombatListener(this), this);
        for (Player p : Bukkit.getOnlinePlayers()) touch(p);
        if (getServer().getPluginManager().getPlugin("LuckPerms") != null) {
            try {
                LuckPermsPrefix.listen(this);
            } catch (Throwable e) {
                getLogger().warning("Не удалось подписаться на LuckPerms: " + e.getMessage());
            }
        }
        // данные сохраняются раз в минуту, если что-то поменялось
        getServer().getScheduler().runTaskTimer(this, () -> {
            for (Player p : Bukkit.getOnlinePlayers()) refreshPrivilege(p);
            if (dirty) saveData();
        }, 1200L, 1200L);
    }

    @Override
    public void onDisable() {
        saveData();
    }

    /**
     * Обновление старого config.yml (saveDefaultConfig не трогает уже существующий файл) по config-version:
     * до 8 - рамка /stat, знаки классности, привилегии и бой берутся из плагина;
     * 9 - ранги, бустер и умения переехали в DsRanks и убираются отсюда (только когда DsRanks стоит,
     * он уже забрал их себе при своём первом запуске).
     */
    private void migrateConfig() {
        FileConfiguration cfg = getConfig();
        int version = cfg.contains("config-version", true) ? cfg.getInt("config-version") : 1;
        int latest = cfg.getDefaults() == null ? version : cfg.getDefaults().getInt("config-version", version);
        if (version >= latest || cfg.getDefaults() == null) return;
        if (version < 11) {
            // 11: лайки убраны - строка с {likes} снова обычная нижняя рамка
            List<String> lines = new ArrayList<>(cfg.getStringList("lines"));
            List<String> def = cfg.getDefaults().getStringList("lines");
            if (!def.isEmpty()) lines.replaceAll(l -> l.contains("{likes}") ? def.get(def.size() - 1) : l);
            cfg.set("lines", lines);
            cfg.set("likes", null);
            saveConfig();
        }
        if (version >= 8) {
            if (getServer().getPluginManager().getPlugin("DsRanks") == null) return; // ранги ещё не перенесены
            removeRankSettings(cfg);
            cfg.set("messages.admin-usage", null);
            cfg.set("messages.bad-field", null);
            cfg.set("config-version", latest);
            saveConfig();
            getLogger().info("Ранги, бустер и умения теперь в плагине DsRanks, из config.yml они убраны.");
            return;
        }
        var d = cfg.getDefaults();
        cfg.set("lines", d.getStringList("lines"));
        cfg.set("classes", d.getMapList("classes"));
        cfg.set("privileges", null);
        ConfigurationSection priv = d.getConfigurationSection("privileges");
        if (priv != null) {
            for (String k : priv.getKeys(false)) cfg.set("privileges." + k, priv.getString(k));
        }
        cfg.set("defaults.clan", d.getString("defaults.clan"));
        removeRankSettings(cfg);
        cfg.set("combat", null);
        ConfigurationSection combat = d.getConfigurationSection("combat");
        if (combat != null) {
            for (String k : combat.getKeys(false)) cfg.set("combat." + k, combat.get(k));
        }
        cfg.set("messages.kill", null);
        cfg.set("messages.winrate-up", null);
        for (String old : new String[]{"privilege", "rank", "rating", "class", "winrate"}) cfg.set("defaults." + old, null);
        cfg.set("status.last-seen", d.getString("status.last-seen"));
        cfg.set("status.last-seen-today", null);
        cfg.set("messages.admin-usage", null);
        cfg.set("messages.bad-field", null);
        cfg.set("config-version", latest);
        saveConfig();
        getLogger().info("config.yml обновлён до версии " + latest + ".");
    }

    private static void removeRankSettings(FileConfiguration cfg) {
        for (String k : new String[]{"ranks", "skills", "booster", "chat-rank", "messages.booster", "messages.rank-up",
                "messages.rank-on", "messages.rank-off", "messages.rank-usage"}) {
            cfg.set(k, null);
        }
    }

    // ---------------- данные ----------------

    private void loadData() {
        dataFile = new File(getDataFolder(), "data.yml");
        data = YamlConfiguration.loadConfiguration(dataFile);
    }

    void saveData() {
        dirty = false;
        try {
            data.save(dataFile);
        } catch (IOException e) {
            dirty = true;
            getLogger().warning("Не удалось сохранить data.yml: " + e.getMessage());
        }
    }

    static String key(String name) {
        return name.toLowerCase(Locale.ROOT);
    }

    /** Число из data.yml; старые значения вида "83%" тоже понимает. */
    double number(String name, String field) {
        Object v = data.get("players." + key(name) + "." + field);
        if (v instanceof Number n) return n.doubleValue();
        if (v == null) return 0;
        String s = String.valueOf(v).replaceAll("[^0-9.,-]", "").replace(',', '.');
        try {
            return s.isEmpty() ? 0 : Double.parseDouble(s);
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    int integer(String name, String field) {
        return (int) Math.round(number(name, field));
    }

    void set(String name, String field, Object value) {
        data.set("players." + key(name) + "." + field, value);
        dirty = true;
    }

    boolean isTrue(String name, String field) {
        return data.getBoolean("players." + key(name) + "." + field);
    }

    List<String> stringList(String name, String field) {
        return new ArrayList<>(data.getStringList("players." + key(name) + "." + field));
    }

    String string(String name, String field) {
        return data.getString("players." + key(name) + "." + field);
    }

    /** Все игроки, которые заходили: ключ -> ник. */
    List<String> allNames() {
        List<String> out = new ArrayList<>();
        ConfigurationSection s = data.getConfigurationSection("players");
        if (s == null) return out;
        for (String k : s.getKeys(false)) {
            String n = s.getString(k + ".name");
            out.add(n != null ? n : k);
        }
        return out;
    }

    boolean known(String name) {
        return data.contains("players." + key(name));
    }

    /** Запись игрока при входе и обновление ранга для чата. */
    void touch(Player player) {
        set(player.getName(), "name", player.getName());
        set(player.getName(), "uuid", player.getUniqueId().toString());
        refreshPrivilege(player);
    }

    /** Запоминает группу для строки "Привилегия" (первая из privileges, которая есть у игрока). */
    void refreshPrivilege(Player player) {
        String found = null;
        ConfigurationSection s = getConfig().getConfigurationSection("privileges");
        if (s != null) {
            for (String group : s.getKeys(false)) {
                if (!group.equalsIgnoreCase("default") && player.hasPermission("group." + group)) {
                    found = group;
                    break;
                }
            }
        }
        if (!Objects.equals(found, string(player.getName(), "privilege-group"))) {
            set(player.getName(), "privilege-group", found);
        }
        // обычный префикс LuckPerms - для всех остальных групп (⌜Luxe⌟ и т.п.)
        String prefix = null;
        if (getServer().getPluginManager().getPlugin("LuckPerms") != null) {
            try {
                prefix = LuckPermsPrefix.get(player);
            } catch (Throwable ignored) {
            }
        }
        if (!Objects.equals(prefix, string(player.getName(), "privilege-prefix"))) {
            set(player.getName(), "privilege-prefix", prefix);
        }
    }

    /** Привилегия для /stat: выданная вручную -> по группе из privileges -> префикс LuckPerms -> default. */
    String privilege(String name) {
        String manual = string(name, "privilege");
        if (manual != null) return manual;
        String group = string(name, "privilege-group");
        String byGroup = group == null ? null : getConfig().getString("privileges." + group);
        if (byGroup != null) return byGroup;
        String prefix = string(name, "privilege-prefix");
        return prefix != null ? prefix : getConfig().getString("privileges.default", "&f⌜&3Игрок&f⌟");
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        touch(event.getPlayer());
    }

    // ---------------- ранги (плагин DsRanks) ----------------

    /** Ранг для /stat: из DsRanks, без него - прочерк. */
    String rankDisplay(String name) {
        String r = RanksHook.rankDisplay(name);
        return r != null ? r : getConfig().getString("defaults.rank", "&7—");
    }

    // ---------------- оформление ----------------

    String color(String s) {
        Matcher m = HEX.matcher(s);
        StringBuilder sb = new StringBuilder();
        while (m.find()) {
            StringBuilder rep = new StringBuilder("§x");
            for (char ch : m.group(1).toCharArray()) rep.append('§').append(ch);
            m.appendReplacement(sb, Matcher.quoteReplacement(rep.toString()));
        }
        m.appendTail(sb);
        return ChatColor.translateAlternateColorCodes('&', sb.toString());
    }

    String msg(String key) {
        return getConfig().getString("messages." + key, key);
    }

    private void hookPapi() {
        papiSet = null;
        if (getServer().getPluginManager().getPlugin("PlaceholderAPI") == null) return;
        try {
            Class<?> c = Class.forName("me.clip.placeholderapi.PlaceholderAPI");
            papiSet = c.getMethod("setPlaceholders", OfflinePlayer.class, String.class);
        } catch (Exception e) {
            getLogger().warning("PlaceholderAPI не подключён: " + e.getMessage());
        }
    }

    private String papi(OfflinePlayer p, String text) {
        if (papiSet == null || p == null) return text;
        try {
            return (String) papiSet.invoke(null, p, text);
        } catch (Exception e) {
            return text;
        }
    }

    /** Клан из MediaClans (раньше - из DestroyChat), §-цвета, или null. */
    private String clan(UUID uuid) {
        if (uuid == null) return null;
        try {
            Plugin clans = getServer().getPluginManager().getPlugin("MediaClans");
            String api = "ru.dscraft.mediaclans.ClanApi";
            if (clans == null || !clans.isEnabled()) {
                clans = getServer().getPluginManager().getPlugin("DestroyChat");
                api = "ru.dscraft.destroychat.clan.ClanApi";
            }
            if (clans == null || !clans.isEnabled()) return null;
            ClassLoader cl = clans.getClass().getClassLoader();
            if (clanName == null || clanLoader != cl) {
                clanName = Class.forName(api, true, cl).getMethod("clanName", UUID.class);
                clanLoader = cl;
            }
            return (String) clanName.invoke(null, uuid);
        } catch (Exception e) {
            clanName = null;
            return null;
        }
    }

    OfflinePlayer findPlayer(String name) {
        Player online = Bukkit.getPlayerExact(name);
        if (online != null) return online;
        OfflinePlayer op = Bukkit.getOfflinePlayerIfCached(name);
        if (op == null && known(name)) {
            String uuid = string(name, "uuid");
            op = uuid != null ? Bukkit.getOfflinePlayer(UUID.fromString(uuid)) : Bukkit.getOfflinePlayer(name);
        }
        return op;
    }

    /** "5 минут", "3 ч.", "2 дн." */
    static String ago(long millis) {
        long minutes = Math.max(1, millis / 60_000L);
        if (minutes < 60) return minutes + " " + plural(minutes, "минуту", "минуты", "минут");
        long hours = minutes / 60;
        if (hours < 24) return hours + " ч.";
        return hours / 24 + " дн.";
    }

    /** Склонение: 1 час, 2 часа, 5 часов. */
    static String plural(long n, String one, String few, String many) {
        long m100 = n % 100, m10 = n % 10;
        if (m100 >= 11 && m100 <= 14) return many;
        if (m10 == 1) return one;
        if (m10 >= 2 && m10 <= 4) return few;
        return many;
    }

    /** Проценты всегда целые: 90%. */
    static String percent(double v) {
        return Math.round(v) + "%";
    }

    // ---------------- команды ----------------

    @Override
    public boolean onCommand(CommandSender sender, Command cmd, String label, String[] args) {
        if (cmd.getName().equalsIgnoreCase("mystat")) {
            if (sender instanceof Player p) showStat(sender, p.getName());
            else sender.sendMessage(color(msg("usage")));
            return true;
        }
        if (cmd.getName().equalsIgnoreCase("stat")) {
            if (!sender.hasPermission("stat.use")) {
                sender.sendMessage(color(msg("no-permission")));
                return true;
            }
            if (args.length < 1) {
                if (sender instanceof Player p) showStat(sender, p.getName());
                else sender.sendMessage(color(msg("usage")));
                return true;
            }
            showStat(sender, args[0]);
            return true;
        }
        return admin(sender, args);
    }

    private void showStat(CommandSender sender, String name) {
        OfflinePlayer op = findPlayer(name);
        if (op == null) {
            sender.sendMessage(color(msg("not-found").replace("{player}", name)));
            return;
        }
        String shown = op.getName() != null ? op.getName() : name;
        if (op instanceof Player online) refreshPrivilege(online); // группу могли только что сменить

        long ticks = 0;
        try {
            ticks = op.getStatistic(Statistic.PLAY_ONE_MINUTE);
        } catch (Exception ignored) {
        }
        long hours = ticks / 20 / 3600;
        String status = getConfig().getString(op.isOnline() ? "status.online" : "status.offline", "");
        if (!op.isOnline() && op.getLastSeen() > 0) {
            status += getConfig().getString("status.last-seen", "")
                    .replace("{time}", ago(System.currentTimeMillis() - op.getLastSeen()));
        }
        String privilege = privilege(shown);
        String clan = clan(op.getUniqueId());
        if (clan == null) clan = getConfig().getString("defaults.clan", "&7—");
        int rating = integer(shown, "rating");

        for (String line : getConfig().getStringList("lines")) {
            String out = line
                    .replace("{player}", shown)
                    .replace("{privilege}", privilege)
                    .replace("{rank}", rankDisplay(shown))
                    .replace("{clan}", clan)
                    .replace("{rating}", String.valueOf(rating))
                    .replace("{class}", classes.classFor(rating))
                    .replace("{winrate}", percent(number(shown, "winrate")))
                    .replace("{kills}", String.valueOf(RanksHook.kills(shown)))
                    .replace("{deaths}", String.valueOf(integer(shown, "deaths")))
                    .replace("{booster}", "x" + RanksHook.booster(shown))
                    .replace("{playtime}", String.valueOf(hours))
                    .replace("{hours}", plural(hours, "час", "часа", "часов"))
                    .replace("{status}", status);
            sender.sendMessage(color(papi(op, out)));
        }
    }

    private boolean admin(CommandSender sender, String[] args) {
        if (!sender.hasPermission("stat.admin")) {
            sender.sendMessage(color(msg("no-permission")));
            return true;
        }
        if (args.length == 0) {
            usage(sender);
            return true;
        }
        switch (args[0].toLowerCase(Locale.ROOT)) {
            case "reload" -> {
                saveData();
                reloadConfig();
                classes.load(getConfig());
                loadData();
                hookPapi();
                sender.sendMessage(color(msg("reload-ok")));
            }
            case "reset" -> {
                if (args.length < 2) {
                    usage(sender);
                    return true;
                }
                String name = data.getString("players." + key(args[1]) + ".name");
                String uuid = data.getString("players." + key(args[1]) + ".uuid");
                data.set("players." + key(args[1]), null);
                if (name != null) set(name, "name", name);
                if (uuid != null) set(args[1], "uuid", uuid);
                saveData();
                sender.sendMessage(color(msg("reset-ok").replace("{player}", args[1])));
            }
            case "set" -> {
                if (args.length < 4) {
                    usage(sender);
                    return true;
                }
                String f = args[2].toLowerCase(Locale.ROOT);
                if (!FIELDS.contains(f)) {
                    sender.sendMessage(color(msg("bad-field")));
                    return true;
                }
                String value = String.join(" ", Arrays.copyOfRange(args, 3, args.length));
                Object stored = value;
                if (f.equals("privilege") && (value.equalsIgnoreCase("reset") || value.equalsIgnoreCase("auto"))) {
                    stored = null; // снова по группе LuckPerms
                } else if (!f.equals("privilege")) {
                    try {
                        double d = Double.parseDouble(value.replace("%", "").replace(',', '.').trim());
                        if (f.equals("winrate")) stored = (int) Math.max(0, Math.min(100, Math.round(d)));
                        else stored = Math.max(0, (int) d);
                    } catch (NumberFormatException e) {
                        sender.sendMessage(color(msg("bad-number")));
                        return true;
                    }
                }
                set(args[1], f, stored);
                if (f.equals("winrate")) set(args[1], "winrate-started", true); // выданный процент не сбросится на 100
                saveData();
                sender.sendMessage(color(msg("set-ok")
                        .replace("{player}", args[1])
                        .replace("{field}", f)
                        .replace("{value}", stored == null ? "авто (по группе)" : String.valueOf(stored))));
            }
            default -> usage(sender);
        }
        return true;
    }

    private void usage(CommandSender sender) {
        for (String s : getConfig().getStringList("messages.admin-usage")) {
            sender.sendMessage(color(s));
        }
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command cmd, String label, String[] args) {
        List<String> res = new ArrayList<>();
        boolean isAdmin = cmd.getName().equalsIgnoreCase("statadmin");
        if (isAdmin) {
            if (!sender.hasPermission("stat.admin")) return res;
            if (args.length == 1) res.addAll(Arrays.asList("set", "reset", "reload"));
            else if (args.length == 2 && !args[0].equalsIgnoreCase("reload")) addPlayers(res);
            else if (args.length == 3 && args[0].equalsIgnoreCase("set")) res.addAll(FIELDS);
        } else if (args.length == 1) {
            addPlayers(res);
        }
        String last = args[args.length - 1].toLowerCase(Locale.ROOT);
        res.removeIf(s -> !s.toLowerCase(Locale.ROOT).startsWith(last));
        return res;
    }

    static void addPlayers(List<String> res) {
        for (Player p : Bukkit.getOnlinePlayers()) res.add(p.getName());
    }
}
