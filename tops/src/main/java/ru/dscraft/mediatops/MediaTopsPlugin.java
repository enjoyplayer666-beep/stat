package ru.dscraft.mediatops;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Interaction;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** MediaTops: голограммы "Топ бойцов / кланов / активистов" за день и за вайп. */
public class MediaTopsPlugin extends ru.dscraft.destroypvp.Module implements Listener, TabCompleter {

    /** Оформление, которое обновляется из плагина при смене config-version (голограммы boards не трогаются). */
    private static final String[] STYLE_KEYS = {"background", "shadow", "line-spacing", "mode-day", "mode-wipe",
            "footer-day", "footer-wipe", "kills", "clans", "playtime"};

    private Stats stats;
    private Boards boards;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        migrateConfig();
        stats = new Stats(this);
        stats.load();
        boards = new Boards(this, stats);
        getServer().getPluginManager().registerEvents(this, this);
        getCommand("tops").setExecutor(this);
        getCommand("tops").setTabCompleter(this);

        // голограммы - после загрузки миров и остальных плагинов (MediaClans)
        Bukkit.getScheduler().runTask(this, boards::loadAll);
        long update = Math.max(5, getConfig().getLong("update-seconds", 30)) * 20L;
        Bukkit.getScheduler().runTaskTimer(this, () -> {
            for (Player p : Bukkit.getOnlinePlayers()) stats.update(p);
            boards.refresh();
        }, 20L, update);
        Bukkit.getScheduler().runTaskTimer(this, stats::saveIfDirty, 1200L, 1200L);
    }

    private void migrateConfig() {
        var cfg = getConfig();
        var d = cfg.getDefaults();
        if (d == null || cfg.getInt("config-version", 1) >= d.getInt("config-version", 1)) return;
        for (String k : STYLE_KEYS) cfg.set(k, d.get(k));
        cfg.set("anti-farm-seconds", null);
        cfg.set("empty-line", null);
        cfg.set("config-version", d.getInt("config-version"));
        saveConfig();
        getLogger().info("Оформление топов в config.yml обновлено.");
    }

    @Override
    public void onDisable() {
        if (boards != null) boards.removeAll();
        if (stats != null) stats.save();
    }

    // ---------------- события ----------------

    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        stats.update(event.getPlayer());
        boards.applyVisibility(event.getPlayer());
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        stats.update(event.getPlayer());
        boards.forget(event.getPlayer());
    }

    /** ПКМ по топу. */
    @EventHandler(ignoreCancelled = true)
    public void onInteract(PlayerInteractEntityEvent event) {
        if (event.getRightClicked() instanceof Interaction i && boards.handleClick(event.getPlayer(), i)) {
            event.setCancelled(true);
        }
    }

    /** ЛКМ по топу. */
    @EventHandler(ignoreCancelled = true)
    public void onHit(EntityDamageByEntityEvent event) {
        if (event.getEntity() instanceof Interaction i && event.getDamager() instanceof Player p && boards.handleClick(p, i)) {
            event.setCancelled(true);
        }
    }

    // ---------------- команды ----------------

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        String sub = args.length == 0 ? "help" : args[0].toLowerCase(Locale.ROOT);
        switch (sub) {
            case "create" -> {
                if (!(sender instanceof Player p) || args.length < 2) {
                    sender.sendMessage("§e/tops create <kills|clans|playtime> [id] §7- поставить топ там, где стоишь");
                    return true;
                }
                Boards.Type type = type(args[1]);
                if (type == null) {
                    sender.sendMessage("§cТип: kills (бойцы), clans (кланы), playtime (активисты).");
                    return true;
                }
                String id = args.length >= 3 ? args[2].toLowerCase(Locale.ROOT) : type.name().toLowerCase(Locale.ROOT);
                save(id, type, p.getLocation());
                sender.sendMessage("§aТоп §f" + id + " §aпоставлен. Двигать: §f/tops move " + id);
            }
            case "move" -> {
                if (!(sender instanceof Player p) || args.length < 2 || !getConfig().contains("boards." + args[1])) {
                    sender.sendMessage("§e/tops move <id> §7- перенести топ туда, где стоишь. Список: /tops list");
                    return true;
                }
                save(args[1], type(getConfig().getString("boards." + args[1] + ".type")), p.getLocation());
                sender.sendMessage("§aТоп §f" + args[1] + " §aперенесён.");
            }
            case "remove" -> {
                if (args.length < 2 || !getConfig().contains("boards." + args[1])) {
                    sender.sendMessage("§e/tops remove <id>§7. Список: /tops list");
                    return true;
                }
                getConfig().set("boards." + args[1], null);
                saveConfig();
                boards.loadAll();
                sender.sendMessage("§aТоп §f" + args[1] + " §aудалён.");
            }
            case "list" -> {
                var s = getConfig().getConfigurationSection("boards");
                if (s == null || s.getKeys(false).isEmpty()) {
                    sender.sendMessage("§7Топов пока нет. /tops create <kills|clans|playtime>");
                    return true;
                }
                for (String id : s.getKeys(false)) {
                    sender.sendMessage("§f" + id + " §7- " + s.getString(id + ".type") + ", " + s.getString(id + ".world")
                            + " " + Math.round(s.getDouble(id + ".x")) + " " + Math.round(s.getDouble(id + ".y")) + " " + Math.round(s.getDouble(id + ".z")));
                }
            }
            case "reload" -> {
                reloadConfig();
                boards.loadAll();
                sender.sendMessage("§aMediaTops перезагружен.");
            }
            default -> {
                sender.sendMessage("§e/tops create <kills|clans|playtime> [id] §7- поставить топ");
                sender.sendMessage("§e/tops move <id> §7- перенести сюда");
                sender.sendMessage("§e/tops remove <id> §7- удалить");
                sender.sendMessage("§e/tops list §7- список");
                sender.sendMessage("§e/tops reload §7- перезагрузить конфиг");
            }
        }
        return true;
    }

    private static Boards.Type type(String s) {
        if (s == null) return null;
        return switch (s.toLowerCase(Locale.ROOT)) {
            case "kills", "бойцы" -> Boards.Type.KILLS;
            case "clans", "кланы" -> Boards.Type.CLANS;
            case "playtime", "активисты", "time" -> Boards.Type.PLAYTIME;
            default -> null;
        };
    }

    /** Топ на месте игрока: текст чуть выше головы. */
    private void save(String id, Boards.Type type, Location loc) {
        String p = "boards." + id + ".";
        getConfig().set(p + "type", type.name().toLowerCase(Locale.ROOT));
        getConfig().set(p + "world", loc.getWorld().getName());
        getConfig().set(p + "x", Math.round(loc.getX() * 100) / 100.0);
        getConfig().set(p + "y", Math.round(loc.getY() * 100) / 100.0);
        getConfig().set(p + "z", Math.round(loc.getZ() * 100) / 100.0);
        saveConfig();
        boards.loadAll();
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        List<String> out = new ArrayList<>();
        if (args.length == 1) out.addAll(List.of("create", "move", "remove", "list", "reload"));
        else if (args.length == 2 && args[0].equalsIgnoreCase("create")) out.addAll(List.of("kills", "clans", "playtime"));
        else if (args.length == 2 && (args[0].equalsIgnoreCase("move") || args[0].equalsIgnoreCase("remove"))) out.addAll(boards.ids());
        String last = args[args.length - 1].toLowerCase(Locale.ROOT);
        out.removeIf(s -> !s.toLowerCase(Locale.ROOT).startsWith(last));
        return out;
    }
}
