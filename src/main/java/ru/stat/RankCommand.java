package ru.stat;

import net.md_5.bungee.api.chat.ClickEvent;
import net.md_5.bungee.api.chat.TextComponent;
import org.bukkit.OfflinePlayer;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

/** /rank info [ник] | list | top [страница] | on | off */
public class RankCommand implements CommandExecutor, TabCompleter {

    private static final String B = "&#7B6FE0";
    private static final String LINE = B + "│ ";
    private static final String DASHES = "- - - - - - - - - - - ";
    private static final int TOP_PAGE = 10;

    private final StatPlugin plugin;

    public RankCommand(StatPlugin plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        String sub = args.length == 0 ? "info" : args[0].toLowerCase(Locale.ROOT);
        switch (sub) {
            case "info" -> {
                String name = args.length >= 2 ? args[1] : (sender instanceof Player p ? p.getName() : null);
                if (name == null) {
                    usage(sender);
                    return true;
                }
                info(sender, name);
            }
            case "list" -> list(sender);
            case "top" -> {
                int page = 1;
                if (args.length >= 2) {
                    try {
                        page = Integer.parseInt(args[1]);
                    } catch (NumberFormatException ignored) {
                    }
                }
                top(sender, page);
            }
            case "on", "off" -> {
                if (!(sender instanceof Player p)) return true;
                boolean off = sub.equals("off");
                plugin.setRankHidden(p, off);
                send(sender, plugin.msg(off ? "rank-off" : "rank-on"));
            }
            default -> usage(sender);
        }
        return true;
    }

    private void info(CommandSender sender, String name) {
        OfflinePlayer op = plugin.findPlayer(name);
        if (op == null || (!op.isOnline() && !plugin.known(name))) {
            send(sender, plugin.msg("not-found").replace("{player}", name));
            return;
        }
        String shown = op.getName() != null ? op.getName() : name;
        Ranks ranks = plugin.ranks();
        Ranks.Rank rank = plugin.rank(shown);
        Ranks.Rank next = ranks.next(rank);
        int kills = plugin.integer(shown, "kills");
        var cfg = plugin.getConfig();
        int atk = rank.attack();
        int def = rank.defense();
        int atkChance = atk > 0 ? cfg.getInt("skills.attack-chance", 33) : 0;
        int defChance = def > 0 ? cfg.getInt("skills.defense-chance", 33) : 0;

        send(sender, B + "╭" + DASHES + DASHES + "╮");
        send(sender, LINE + "&fНикнейм: &b" + shown);
        send(sender, LINE + "&fРанг: " + rank.display());
        send(sender, LINE + "&fБустер: &ax" + plugin.booster(shown));
        send(sender, LINE + "&fУбито игроков: &c" + kills);
        send(sender, LINE + "&fПрогресс: " + (next == null
                ? "&aМаксимальный ранг!"
                : "&fОсталось &c" + (next.kills() - kills) + " &f"
                        + StatPlugin.plural(next.kills() - kills, "убийство", "убийства", "убийств")));
        send(sender, LINE + "&fУмения:");
        send(sender, LINE + "&7[&#3CCFC0Атака&7] &c+" + atk + "% &fурона &7(Шанс: " + atkChance + "%)");
        send(sender, LINE + "&7[&aЗащита&7] &e-" + def + "% &fурона &7(Шанс: " + defChance + "%)");
        send(sender, B + "╰" + DASHES + DASHES + "╯");
    }

    private void list(CommandSender sender) {
        List<Ranks.Rank> all = plugin.ranks().all();
        send(sender, B + "╭" + DASHES + DASHES + "╮");
        send(sender, LINE + "&fВсе доступные ранги (всего &b" + all.size() + "&f):");
        for (Ranks.Rank r : all) {
            send(sender, LINE + "&7— " + r.display() + " &f(&c" + r.kills() + " &fубийств)");
        }
        send(sender, B + "╰" + DASHES + DASHES + "╯");
    }

    private void top(CommandSender sender, int page) {
        List<String> names = plugin.allNames();
        names.sort(Comparator.comparingInt((String n) -> -plugin.integer(n, "kills"))
                .thenComparing(String.CASE_INSENSITIVE_ORDER));
        int pages = Math.max(1, (names.size() + TOP_PAGE - 1) / TOP_PAGE);
        page = Math.max(1, Math.min(page, pages));

        send(sender, B + "╭" + DASHES + "&f[Топ рангов]" + B + DASHES + "╮");
        for (int i = (page - 1) * TOP_PAGE; i < Math.min(names.size(), page * TOP_PAGE); i++) {
            String n = names.get(i);
            send(sender, LINE + "&c" + (i + 1) + ". &f" + n + " &7- " + plugin.rank(n).display()
                    + " &7[&c" + plugin.integer(n, "kills") + "&7]");
        }
        if (page < pages) {
            String cmd = "/rank top " + (page + 1);
            TextComponent next = new TextComponent(TextComponent.fromLegacyText(
                    plugin.color(LINE + "&#C8C8C8Следующая страница: &b" + cmd)));
            next.setClickEvent(new ClickEvent(ClickEvent.Action.RUN_COMMAND, cmd));
            sender.spigot().sendMessage(next);
        }
        send(sender, B + "╰" + DASHES + DASHES + DASHES + "╯");
    }

    private void usage(CommandSender sender) {
        for (String s : plugin.getConfig().getStringList("messages.rank-usage")) send(sender, s);
    }

    private void send(CommandSender sender, String text) {
        sender.sendMessage(plugin.color(text));
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        List<String> res = new ArrayList<>();
        if (args.length == 1) res.addAll(Arrays.asList("info", "list", "top", "on", "off"));
        else if (args.length == 2 && args[0].equalsIgnoreCase("info")) StatPlugin.addPlayers(res);
        String last = args[args.length - 1].toLowerCase(Locale.ROOT);
        res.removeIf(s -> !s.toLowerCase(Locale.ROOT).startsWith(last));
        return res;
    }
}
