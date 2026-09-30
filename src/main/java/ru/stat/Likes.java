package ru.stat;

import org.bukkit.OfflinePlayer;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Лайки в /stat: /like <ник> - один аккаунт лайкает другого только один раз;
 * /likegive - раз в сутки случайные лайки себе (группы из likes.give-groups, ULTRA).
 */
final class Likes implements CommandExecutor, TabCompleter {

    private final StatPlugin plugin;

    Likes(StatPlugin plugin) {
        this.plugin = plugin;
    }

    private void send(CommandSender s, String key, String... repl) {
        String m = plugin.getConfig().getString("likes.messages." + key, key);
        for (int i = 0; i + 1 < repl.length; i += 2) m = m.replace(repl[i], repl[i + 1]);
        s.sendMessage(plugin.color(m));
    }

    boolean canGive(CommandSender s) {
        if (s.hasPermission("stat.likegive")) return true;
        for (String g : plugin.getConfig().getStringList("likes.give-groups")) {
            if (s.hasPermission("group." + g.toLowerCase(Locale.ROOT))) return true;
        }
        return false;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command cmd, String label, String[] args) {
        if (!(sender instanceof Player p)) return true;
        if (cmd.getName().equalsIgnoreCase("likegive")) {
            likeGive(p, args);
            return true;
        }
        if (args.length < 1) {
            send(p, "usage");
            return true;
        }
        OfflinePlayer target = plugin.findPlayer(args[0]);
        if (target == null || target.getName() == null || (!target.isOnline() && !plugin.known(target.getName()))) {
            send(p, "not-found", "{player}", args[0]);
            return true;
        }
        String name = target.getName();
        if (target.getUniqueId().equals(p.getUniqueId())) {
            send(p, "self");
            return true;
        }
        List<String> by = plugin.stringList(name, "liked-by");
        String me = p.getUniqueId().toString();
        if (by.contains(me)) {
            send(p, "already", "{player}", name);
            return true;
        }
        by.add(me);
        plugin.set(name, "liked-by", by);
        plugin.set(name, "likes", plugin.integer(name, "likes") + 1);
        plugin.saveData();
        send(p, "liked", "{player}", name);
        if (target instanceof Player online) send(online, "got-like", "{player}", p.getName());
        return true;
    }

    private void likeGive(Player p, String[] args) {
        if (!canGive(p)) {
            send(p, "give-no-access");
            return;
        }
        var cfg = plugin.getConfig();
        long cooldown = cfg.getLong("likes.give-cooldown-hours", 24) * 3_600_000L;
        long last = (long) plugin.number(p.getName(), "likegive-last");
        long now = System.currentTimeMillis();
        if (now - last < cooldown) {
            long min = Math.max(1, (cooldown - (now - last)) / 60_000L);
            send(p, "give-cooldown", "{time}", min >= 60 ? (min / 60) + " ч. " + (min % 60) + " мин." : min + " мин.");
            return;
        }
        String name = p.getName();
        OfflinePlayer target = p;
        if (args.length >= 1) {
            target = plugin.findPlayer(args[0]);
            if (target == null || target.getName() == null || (!target.isOnline() && !plugin.known(target.getName()))) {
                send(p, "not-found", "{player}", args[0]);
                return;
            }
            name = target.getName();
        }
        int lo = cfg.getInt("likes.give-min", 7), hi = Math.max(lo, cfg.getInt("likes.give-max", 15));
        int amount = ThreadLocalRandom.current().nextInt(lo, hi + 1);
        plugin.set(name, "likes", plugin.integer(name, "likes") + amount);
        plugin.set(p.getName(), "likegive-last", now);
        plugin.saveData();
        send(p, "give-done", "{likes}", String.valueOf(amount), "{player}", name);
        if (target instanceof Player online && !online.equals(p)) {
            send(online, "give-got", "{likes}", String.valueOf(amount), "{player}", p.getName());
        }
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command cmd, String alias, String[] args) {
        List<String> res = new ArrayList<>();
        if (args.length == 1 && (cmd.getName().equalsIgnoreCase("like") || canGive(sender))) {
            StatPlugin.addPlayers(res);
            if (cmd.getName().equalsIgnoreCase("like")) res.remove(sender.getName());
            String last = args[0].toLowerCase(Locale.ROOT);
            res.removeIf(s -> !s.toLowerCase(Locale.ROOT).startsWith(last));
        }
        return res;
    }
}
