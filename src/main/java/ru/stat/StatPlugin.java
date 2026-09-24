package ru.stat;

import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.OfflinePlayer;
import org.bukkit.Statistic;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.io.IOException;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class StatPlugin extends JavaPlugin implements CommandExecutor, TabCompleter {

    private static final List<String> FIELDS =
            Arrays.asList("privilege", "rank", "clan", "rating", "class", "winrate");
    private static final Pattern HEX = Pattern.compile("&#([A-Fa-f0-9]{6})");

    private File dataFile;
    private FileConfiguration data;
    private Method papiSet;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        loadData();
        hookPapi();
        getCommand("stat").setExecutor(this);
        getCommand("stat").setTabCompleter(this);
        getCommand("statadmin").setExecutor(this);
        getCommand("statadmin").setTabCompleter(this);
    }

    private void loadData() {
        dataFile = new File(getDataFolder(), "data.yml");
        data = YamlConfiguration.loadConfiguration(dataFile);
    }

    private void saveData() {
        try {
            data.save(dataFile);
        } catch (IOException e) {
            getLogger().warning("Не удалось сохранить data.yml: " + e.getMessage());
        }
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

    private String color(String s) {
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

    private String msg(String key) {
        return getConfig().getString("messages." + key, key);
    }

    private String papi(OfflinePlayer p, String text) {
        if (papiSet == null || p == null) return text;
        try {
            return (String) papiSet.invoke(null, p, text);
        } catch (Exception e) {
            return text;
        }
    }

    private String field(String nameLower, String field) {
        String v = data.getString("players." + nameLower + "." + field);
        return v != null ? v : getConfig().getString("defaults." + field, "");
    }

    private OfflinePlayer findPlayer(String name) {
        Player online = Bukkit.getPlayerExact(name);
        if (online != null) return online;
        OfflinePlayer op = Bukkit.getOfflinePlayerIfCached(name);
        if (op == null && data.contains("players." + name.toLowerCase(Locale.ROOT))) {
            op = Bukkit.getOfflinePlayer(name);
        }
        return op;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command cmd, String label, String[] args) {
        if (cmd.getName().equalsIgnoreCase("stat")) {
            if (!sender.hasPermission("stat.use")) {
                sender.sendMessage(color(msg("no-permission")));
                return true;
            }
            if (args.length < 1) {
                sender.sendMessage(color(msg("usage")));
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
        String key = shown.toLowerCase(Locale.ROOT);

        long ticks = 0;
        try {
            ticks = op.getStatistic(Statistic.PLAY_ONE_MINUTE);
        } catch (Exception ignored) {
        }
        long hours = ticks / 20 / 3600;
        String status = getConfig().getString(op.isOnline() ? "status.online" : "status.offline");

        for (String line : getConfig().getStringList("lines")) {
            String out = line
                    .replace("{player}", shown)
                    .replace("{privilege}", field(key, "privilege"))
                    .replace("{rank}", field(key, "rank"))
                    .replace("{clan}", field(key, "clan"))
                    .replace("{rating}", field(key, "rating"))
                    .replace("{class}", field(key, "class"))
                    .replace("{winrate}", field(key, "winrate"))
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
                reloadConfig();
                loadData();
                hookPapi();
                sender.sendMessage(color(msg("reload-ok")));
            }
            case "reset" -> {
                if (args.length < 2) {
                    usage(sender);
                    return true;
                }
                data.set("players." + args[1].toLowerCase(Locale.ROOT), null);
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
                data.set("players." + args[1].toLowerCase(Locale.ROOT) + "." + f, value);
                saveData();
                sender.sendMessage(color(msg("set-ok")
                        .replace("{player}", args[1])
                        .replace("{field}", f)
                        .replace("{value}", value)));
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

    private void addPlayers(List<String> res) {
        for (Player p : Bukkit.getOnlinePlayers()) res.add(p.getName());
    }
}
