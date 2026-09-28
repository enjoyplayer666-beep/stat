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
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.io.IOException;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class StatPlugin extends JavaPlugin implements CommandExecutor, TabCompleter, Listener {

    private static final List<String> FIELDS =
            Arrays.asList("privilege", "rating", "winrate", "kills", "deaths", "booster");
    private static final Pattern HEX = Pattern.compile("&#([A-Fa-f0-9]{6})");

    private File dataFile;
    private FileConfiguration data;
    private boolean dirty;
    private Method papiSet;
    private Method clanName;
    private final Ranks ranks = new Ranks();

    @Override
    public void onEnable() {
        saveDefaultConfig();
        migrateConfig();
        ranks.load(getConfig());
        loadData();
        hookPapi();
        getCommand("stat").setExecutor(this);
        getCommand("stat").setTabCompleter(this);
        getCommand("mystat").setExecutor(this);
        getCommand("statadmin").setExecutor(this);
        getCommand("statadmin").setTabCompleter(this);
        RankCommand rankCommand = new RankCommand(this);
        getCommand("rank").setExecutor(rankCommand);
        getCommand("rank").setTabCompleter(rankCommand);
        getServer().getPluginManager().registerEvents(this, this);
        getServer().getPluginManager().registerEvents(new CombatListener(this), this);
        for (Player p : Bukkit.getOnlinePlayers()) touch(p);
        // данные сохраняются раз в минуту, если что-то поменялось
        getServer().getScheduler().runTaskTimer(this, () -> {
            if (dirty) saveData();
        }, 1200L, 1200L);
    }

    @Override
    public void onDisable() {
        saveData();
        StatApi.clear();
    }

    /** Старый config.yml (без config-version): новая рамка /stat, остальное берётся из плагина. */
    private void migrateConfig() {
        FileConfiguration cfg = getConfig();
        if (cfg.contains("config-version", true)) return;
        if (cfg.getDefaults() != null) {
            cfg.set("lines", cfg.getDefaults().getStringList("lines"));
            cfg.set("defaults.clan", cfg.getDefaults().getString("defaults.clan"));
        }
        for (String old : new String[]{"rank", "rating", "class", "winrate"}) cfg.set("defaults." + old, null);
        cfg.set("messages.admin-usage", null);
        cfg.set("messages.bad-field", null);
        cfg.set("config-version", 2);
        saveConfig();
        getLogger().info("config.yml обновлён: ранги, боевой рейтинг и знаки классности.");
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
        refreshChatRank(player);
    }

    void refreshChatRank(Player player) {
        if (data.getBoolean("players." + key(player.getName()) + ".rank-hidden")) {
            StatApi.set(player.getUniqueId(), null);
            return;
        }
        Ranks.Rank r = rank(player.getName());
        String text = getConfig().getString("chat-rank", "{color}{icon} {name} ")
                .replace("{color}", r.color()).replace("{icon}", r.icon()).replace("{name}", r.name());
        StatApi.set(player.getUniqueId(), color(text));
    }

    void setRankHidden(Player player, boolean hidden) {
        set(player.getName(), "rank-hidden", hidden ? true : null);
        refreshChatRank(player);
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        touch(event.getPlayer());
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        StatApi.set(event.getPlayer().getUniqueId(), null);
    }

    // ---------------- ранги / бустер ----------------

    Ranks ranks() {
        return ranks;
    }

    Ranks.Rank rank(String name) {
        return ranks.rankFor(integer(name, "kills"));
    }

    /** Бустер игрока: наибольшее из выданного вручную и права stat.booster.N. */
    int booster(String name) {
        int max = Math.max(1, getConfig().getInt("booster.max", 15));
        int b = Math.max(1, integer(name, "booster"));
        Player online = Bukkit.getPlayerExact(name);
        if (online != null) {
            for (int i = max; i > b; i--) {
                if (online.hasPermission("stat.booster." + i)) {
                    b = i;
                    break;
                }
            }
        }
        return Math.min(b, max);
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

    /** Клан из DestroyChat (§-цвета) или null. */
    private String clan(UUID uuid) {
        if (uuid == null) return null;
        try {
            if (clanName == null) {
                Plugin chat = getServer().getPluginManager().getPlugin("DestroyChat");
                if (chat == null) return null;
                Class<?> api = Class.forName("ru.dscraft.destroychat.clan.ClanApi", true, chat.getClass().getClassLoader());
                clanName = api.getMethod("clanName", UUID.class);
            }
            return (String) clanName.invoke(null, uuid);
        } catch (Exception e) {
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

    static String percent(double v) {
        return (v == Math.floor(v) ? String.valueOf((long) v) : String.format(Locale.ROOT, "%.1f", v)) + "%";
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

        long ticks = 0;
        try {
            ticks = op.getStatistic(Statistic.PLAY_ONE_MINUTE);
        } catch (Exception ignored) {
        }
        long hours = ticks / 20 / 3600;
        String status = getConfig().getString(op.isOnline() ? "status.online" : "status.offline", "");
        if (!op.isOnline() && op.getLastSeen() > 0) {
            long days = (System.currentTimeMillis() - op.getLastSeen()) / 86_400_000L;
            status += days <= 0
                    ? getConfig().getString("status.last-seen-today", "")
                    : getConfig().getString("status.last-seen", "").replace("{days}", String.valueOf(days));
        }

        String privilege = string(shown, "privilege");
        if (privilege == null) privilege = getConfig().getString("defaults.privilege", "");
        String clan = clan(op.getUniqueId());
        if (clan == null) clan = getConfig().getString("defaults.clan", "&7—");
        int rating = integer(shown, "rating");

        for (String line : getConfig().getStringList("lines")) {
            String out = line
                    .replace("{player}", shown)
                    .replace("{privilege}", privilege)
                    .replace("{rank}", rank(shown).display())
                    .replace("{clan}", clan)
                    .replace("{rating}", String.valueOf(rating))
                    .replace("{class}", ranks.classFor(rating))
                    .replace("{winrate}", percent(number(shown, "winrate")))
                    .replace("{kills}", String.valueOf(integer(shown, "kills")))
                    .replace("{deaths}", String.valueOf(integer(shown, "deaths")))
                    .replace("{booster}", "x" + booster(shown))
                    .replace("{playtime}", String.valueOf(hours))
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
                ranks.load(getConfig());
                loadData();
                hookPapi();
                for (Player p : Bukkit.getOnlinePlayers()) refreshChatRank(p);
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
                Player online = Bukkit.getPlayerExact(args[1]);
                if (online != null) refreshChatRank(online);
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
                if (!f.equals("privilege")) {
                    try {
                        double d = Double.parseDouble(value.replace("%", "").replace(',', '.').trim());
                        if (f.equals("winrate")) stored = Math.max(0, Math.min(100, d));
                        else if (f.equals("booster")) stored = Math.max(1, Math.min(getConfig().getInt("booster.max", 15), (int) d));
                        else stored = Math.max(0, (int) d);
                    } catch (NumberFormatException e) {
                        sender.sendMessage(color(msg("bad-number")));
                        return true;
                    }
                }
                set(args[1], f, stored);
                saveData();
                Player online = Bukkit.getPlayerExact(args[1]);
                if (online != null) refreshChatRank(online);
                sender.sendMessage(color(msg("set-ok")
                        .replace("{player}", args[1])
                        .replace("{field}", f)
                        .replace("{value}", String.valueOf(stored))));
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
