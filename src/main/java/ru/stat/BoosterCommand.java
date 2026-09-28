package ru.stat;

import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;

/** /booster give <ник> <x1-x15> | take <ник> | info <ник> - бустер к убийствам ранга. */
public class BoosterCommand implements CommandExecutor, TabCompleter {

    public static final String PERMISSION = "stat.booster.give";

    private final StatPlugin plugin;

    public BoosterCommand(StatPlugin plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!sender.hasPermission(PERMISSION)) {
            send(sender, plugin.msg("no-permission"));
            return true;
        }
        int max = Math.max(1, plugin.getConfig().getInt("booster.max", 15));
        if (args.length < 2) {
            usage(sender, max);
            return true;
        }
        String name = args[1];
        if (plugin.findPlayer(name) == null && !plugin.known(name)) {
            send(sender, plugin.msg("not-found").replace("{player}", name));
            return true;
        }
        switch (args[0].toLowerCase(Locale.ROOT)) {
            case "give", "set", "выдать" -> {
                if (args.length < 3) {
                    usage(sender, max);
                    return true;
                }
                int value;
                try {
                    value = Integer.parseInt(args[2].toLowerCase(Locale.ROOT).replace("x", "").replace("х", "").trim());
                } catch (NumberFormatException e) {
                    send(sender, "&cБустер должен быть от x1 до x" + max + ", например: /booster give " + name + " x15");
                    return true;
                }
                if (value < 1 || value > max) {
                    send(sender, "&cБустер должен быть от x1 до x" + max + ".");
                    return true;
                }
                plugin.set(name, "booster", value);
                plugin.saveData();
                send(sender, "&aИгроку &f" + name + " &aвыдан бустер &ex" + value + "&a.");
                Player target = Bukkit.getPlayerExact(name);
                if (target != null && !target.equals(sender)) {
                    send(target, "&6Тебе выдан бустер &ex" + value + "&6 к убийствам ранга!");
                }
            }
            case "take", "remove", "reset", "забрать" -> {
                plugin.set(name, "booster", null);
                plugin.saveData();
                send(sender, "&aБустер игрока &f" + name + " &aсброшен до &ex1&a.");
            }
            case "info" -> send(sender, "&fБустер игрока &b" + name + "&f: &ax" + plugin.booster(name));
            default -> usage(sender, max);
        }
        return true;
    }

    private void usage(CommandSender sender, int max) {
        send(sender, "&e/booster give <ник> <x1-x" + max + "> &7- выдать бустер");
        send(sender, "&e/booster take <ник> &7- сбросить до x1");
        send(sender, "&e/booster info <ник> &7- посмотреть бустер");
    }

    private void send(CommandSender sender, String text) {
        sender.sendMessage(plugin.color(text));
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        List<String> res = new ArrayList<>();
        if (!sender.hasPermission(PERMISSION)) return res;
        if (args.length == 1) res.addAll(Arrays.asList("give", "take", "info"));
        else if (args.length == 2) StatPlugin.addPlayers(res);
        else if (args.length == 3 && args[0].equalsIgnoreCase("give")) {
            int max = Math.max(1, plugin.getConfig().getInt("booster.max", 15));
            for (int i = 1; i <= max; i++) res.add("x" + i);
        }
        String last = args[args.length - 1].toLowerCase(Locale.ROOT);
        res.removeIf(s -> !s.toLowerCase(Locale.ROOT).startsWith(last));
        return res;
    }
}
