package ru.dscraft.mediatops;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Interaction;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/** MediaTops: голограммы "Топ бойцов / кланов / активистов" за день и за вайп. */
public class MediaTopsPlugin extends JavaPlugin implements Listener, TabCompleter {

    private Stats stats;
    private Boards boards;
    private final Map<String, Long> lastKills = new ConcurrentHashMap<>();

    @Override
    public void onEnable() {
        saveDefaultConfig();
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
            stats.checkDay();
            boards.refresh();
        }, update, update);
        // наигранное время: каждую минуту +1 минута всем в сети
        Bukkit.getScheduler().runTaskTimer(this, () -> {
            for (Player p : Bukkit.getOnlinePlayers()) stats.addMinute(p.getUniqueId(), p.getName());
            stats.saveIfDirty();
        }, 1200L, 1200L);
    }

    @Override
    public void onDisable() {
        if (boards != null) boards.removeAll();
        if (stats != null) stats.save();
    }

    // ---------------- события ----------------

    @EventHandler(priority = EventPriority.MONITOR)
    public void onDeath(PlayerDeathEvent event) {
        Player victim = event.getEntity();
        Player killer = victim.getKiller();
        if (killer == null || killer.equals(victim)) return;
        long cooldown = getConfig().getLong("anti-farm-seconds", 300) * 1000L;
        long now = System.currentTimeMillis();
        String pair = killer.getUniqueId() + ":" + victim.getUniqueId();
        if (cooldown > 0) {
            Long last = lastKills.get(pair);
            if (last != null && now - last < cooldown) return;
            lastKills.entrySet().removeIf(e -> now - e.getValue() >= cooldown);
        }
        lastKills.put(pair, now);
        stats.addKill(killer.getUniqueId(), killer.getName());
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        stats.entry(event.getPlayer().getUniqueId(), event.getPlayer().getName());
        boards.applyVisibility(event.getPlayer());
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
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
            case "resetwipe" -> {
                if (args.length < 2 || !args[1].equalsIgnoreCase("confirm")) {
                    sender.sendMessage("§cОбнулит топы за вайп и за день у всех. Подтверди: /tops resetwipe confirm");
                    return true;
                }
                stats.resetWipe();
                boards.refresh();
                sender.sendMessage("§aТопы за вайп обнулены.");
            }
            default -> {
                sender.sendMessage("§e/tops create <kills|clans|playtime> [id] §7- поставить топ");
                sender.sendMessage("§e/tops move <id> §7- перенести сюда");
                sender.sendMessage("§e/tops remove <id> §7- удалить");
                sender.sendMessage("§e/tops list §7- список");
                sender.sendMessage("§e/tops reload §7- перезагрузить конфиг");
                sender.sendMessage("§e/tops resetwipe confirm §7- новый вайп: обнулить топы");
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
        if (args.length == 1) out.addAll(List.of("create", "move", "remove", "list", "reload", "resetwipe"));
        else if (args.length == 2 && args[0].equalsIgnoreCase("create")) out.addAll(List.of("kills", "clans", "playtime"));
        else if (args.length == 2 && (args[0].equalsIgnoreCase("move") || args[0].equalsIgnoreCase("remove"))) out.addAll(boards.ids());
        else if (args.length == 2 && args[0].equalsIgnoreCase("resetwipe")) out.add("confirm");
        String last = args[args.length - 1].toLowerCase(Locale.ROOT);
        out.removeIf(s -> !s.toLowerCase(Locale.ROOT).startsWith(last));
        return out;
    }
}
