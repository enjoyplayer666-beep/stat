package ru.dscraft.mediaborders;

import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.WorldBorder;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerChangedWorldEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerRespawnEvent;
import org.bukkit.event.player.PlayerTeleportEvent;
import org.bukkit.event.vehicle.VehicleMoveEvent;
import org.bukkit.util.Vector;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Барьеры: прямоугольная зона по двум углам (/barrier pos1, pos2, create). Игрок видит её край синей
 * стеной, как у границы мира (своя граница у каждого игрока, сервер её не считает), и не может
 * ни выйти из зоны, ни войти в неё - ни пешком, ни на элитрах, ни жемчугом. Команды телепорта
 * (/spawn, /warp, /tp) барьер не трогает.
 */
public final class MediaBordersPlugin extends ru.dscraft.destroyskypvp.Module implements Listener {

    private static final MiniMessage MM = MiniMessage.miniMessage();
    private static final Set<String> BLOCKED_TELEPORTS = Set.of("ENDER_PEARL", "CHORUS_FRUIT", "CONSUMABLE_EFFECT");

    /** Зона: блоки min..max включительно, по высоте - на весь мир. */
    record Zone(String name, String world, int minX, int minZ, int maxX, int maxZ) {
        boolean contains(Location l) {
            return l.getX() >= minX && l.getX() < maxX + 1 && l.getZ() >= minZ && l.getZ() < maxZ + 1;
        }

        /** Расстояние по горизонтали до края (0 - внутри). */
        double distance(Location l) {
            double dx = Math.max(Math.max(minX - l.getX(), 0), l.getX() - (maxX + 1));
            double dz = Math.max(Math.max(minZ - l.getZ(), 0), l.getZ() - (maxZ + 1));
            return Math.sqrt(dx * dx + dz * dz);
        }

        int width() {
            return maxX - minX + 1;
        }

        int depth() {
            return maxZ - minZ + 1;
        }
    }

    /** Какая стена сейчас показана игроку: центр и размер квадрата. */
    record Shown(double x, double z, double size) {
    }

    private final Map<String, Zone> zones = new LinkedHashMap<>();
    private final Map<UUID, Location[]> selections = new HashMap<>();
    private final Map<UUID, Shown> shown = new HashMap<>();
    private final Map<UUID, Long> lastMessage = new HashMap<>();
    private File zonesFile;
    /** Миры, где настоящую границу мира поставил этот плагин (чтобы вернуть её, когда барьеров не останется). */
    private final java.util.Set<String> managedWorlds = new java.util.LinkedHashSet<>();

    @Override
    public void onEnable() {
        saveDefaultConfig();
        getConfig().options().copyDefaults(true);
        saveConfig();
        zonesFile = new File(getDataFolder(), "zones.yml");
        loadZones();
        applyWorldBorders();
        getServer().getPluginManager().registerEvents(this, this);
        if (getCommand("barrier") != null) getCommand("barrier").setExecutor(this);
        Bukkit.getScheduler().runTask(this, this::refreshAll);
        getLogger().info("Барьеров: " + zones.size());
    }

    @Override
    public void onDisable() {
        for (Player p : Bukkit.getOnlinePlayers()) {
            if (shown.containsKey(p.getUniqueId())) p.setWorldBorder(null);
        }
        shown.clear();
    }

    // ---------------- зоны ----------------

    private void loadZones() {
        zones.clear();
        YamlConfiguration yml = YamlConfiguration.loadConfiguration(zonesFile);
        managedWorlds.clear();
        managedWorlds.addAll(yml.getStringList("managed-worlds"));
        ConfigurationSection s = yml.getConfigurationSection("zones");
        if (s == null) return;
        for (String name : s.getKeys(false)) {
            ConfigurationSection z = s.getConfigurationSection(name);
            if (z == null || z.getString("world") == null) continue;
            zones.put(name.toLowerCase(Locale.ROOT), new Zone(name, z.getString("world"),
                    z.getInt("min-x"), z.getInt("min-z"), z.getInt("max-x"), z.getInt("max-z")));
        }
    }

    private void saveZones() {
        YamlConfiguration yml = new YamlConfiguration();
        yml.set("managed-worlds", new ArrayList<>(managedWorlds));
        for (Zone z : zones.values()) {
            String p = "zones." + z.name() + ".";
            yml.set(p + "world", z.world());
            yml.set(p + "min-x", z.minX());
            yml.set(p + "min-z", z.minZ());
            yml.set(p + "max-x", z.maxX());
            yml.set(p + "max-z", z.maxZ());
        }
        try {
            getDataFolder().mkdirs();
            yml.save(zonesFile);
        } catch (IOException e) {
            getLogger().warning("Не удалось сохранить zones.yml: " + e.getMessage());
        }
    }

    /**
     * Настоящая граница мира вокруг всех барьеров мира (квадрат + запас): дальше неё сервер не грузит
     * и не создаёт чанки совсем - это и снимает нагрузку в бесконечных мирах. Мир без барьеров
     * получает обратно обычную границу.
     */
    private void applyWorldBorders() {
        boolean on = getConfig().getBoolean("world-border.enabled", true);
        double margin = Math.max(0, getConfig().getDouble("world-border.margin", 16));
        java.util.Set<String> withZones = new java.util.HashSet<>();
        for (World world : Bukkit.getWorlds()) {
            List<Zone> list = zonesIn(world);
            if (!on || list.isEmpty()) continue;
            int minX = Integer.MAX_VALUE, minZ = Integer.MAX_VALUE, maxX = Integer.MIN_VALUE, maxZ = Integer.MIN_VALUE;
            for (Zone z : list) {
                minX = Math.min(minX, z.minX());
                minZ = Math.min(minZ, z.minZ());
                maxX = Math.max(maxX, z.maxX() + 1);
                maxZ = Math.max(maxZ, z.maxZ() + 1);
            }
            WorldBorder wb = world.getWorldBorder();
            wb.setCenter((minX + maxX) / 2.0, (minZ + maxZ) / 2.0);
            wb.setSize(Math.max(maxX - minX, maxZ - minZ) + margin * 2);
            wb.setWarningDistance(0);
            withZones.add(world.getName());
            managedWorlds.add(world.getName());
        }
        for (String name : new ArrayList<>(managedWorlds)) {
            if (withZones.contains(name)) continue;
            World world = Bukkit.getWorld(name);
            if (world == null) continue;
            world.getWorldBorder().reset();
            managedWorlds.remove(name);
        }
        saveZones();
    }

    private List<Zone> zonesIn(World world) {
        List<Zone> out = new ArrayList<>();
        for (Zone z : zones.values()) if (z.world().equals(world.getName())) out.add(z);
        return out;
    }

    /** Первая зона, у которой from и to по разные стороны барьера. */
    private Zone crossed(Location from, Location to) {
        if (to == null || from.getWorld() == null || !from.getWorld().equals(to.getWorld())) return null;
        boolean exit = getConfig().getBoolean("block-exit", true);
        boolean enter = getConfig().getBoolean("block-enter", true);
        for (Zone z : zonesIn(from.getWorld())) {
            boolean a = z.contains(from), b = z.contains(to);
            if (a && !b && exit) return z;
            if (!a && b && enter) return z;
        }
        return null;
    }

    private boolean bypass(Player p) {
        if (p.hasPermission("mediaborders.bypass")) return true;
        GameMode m = p.getGameMode();
        if (m == GameMode.CREATIVE && getConfig().getBoolean("bypass-creative", true)) return true;
        return m == GameMode.SPECTATOR && getConfig().getBoolean("bypass-spectator", true);
    }

    // ---------------- стена ----------------

    /**
     * Своя граница мира для игрока по ближайшей зоне. Граница в игре только квадратная: у прямоугольника
     * её размер - по длинной стороне, а по короткой квадрат прижат к ближнему к игроку краю.
     * Так ближние стены всегда стоят ровно по барьеру, а лишняя сторона квадрата - снаружи зоны.
     */
    private void updateWall(Player p, Location at) {
        Zone zone = null;
        double best = getConfig().getDouble("show-distance", 48);
        for (Zone z : zonesIn(at.getWorld())) {
            double d = z.distance(at);
            if (d <= best) {
                best = d;
                zone = z;
                if (d == 0) break;
            }
        }
        UUID id = p.getUniqueId();
        if (zone == null) {
            if (shown.remove(id) != null) p.setWorldBorder(null);
            return;
        }
        double minX = zone.minX(), maxX = zone.maxX() + 1, minZ = zone.minZ(), maxZ = zone.maxZ() + 1;
        double size = Math.max(zone.width(), zone.depth());
        double cx = (minX + maxX) / 2, cz = (minZ + maxZ) / 2;
        if (zone.width() > zone.depth()) cz = at.getZ() < cz ? minZ + size / 2 : maxZ - size / 2;
        else if (zone.depth() > zone.width()) cx = at.getX() < cx ? minX + size / 2 : maxX - size / 2;
        Shown now = new Shown(cx, cz, size);
        if (now.equals(shown.get(id))) return;
        WorldBorder border = Bukkit.createWorldBorder();
        border.setCenter(cx, cz);
        border.setSize(size);
        border.setWarningDistance(Math.max(0, getConfig().getInt("warning-distance", 0)));
        border.setWarningTime(0);
        border.setDamageAmount(0);
        border.setDamageBuffer(0);
        p.setWorldBorder(border);
        shown.put(id, now);
    }

    private void refresh(Player p) {
        if (p.isOnline()) updateWall(p, p.getLocation());
    }

    private void refreshAll() {
        for (Player p : Bukkit.getOnlinePlayers()) {
            shown.remove(p.getUniqueId());
            p.setWorldBorder(null);
            refresh(p);
        }
    }

    /** Отбросить от барьера: внутрь зоны, если был внутри, иначе наружу. */
    private void push(Player p, Zone z, boolean wasInside) {
        Location l = p.getLocation();
        double cx = (z.minX() + z.maxX() + 1) / 2.0, cz = (z.minZ() + z.maxZ() + 1) / 2.0;
        Vector dir = new Vector(cx - l.getX(), 0, cz - l.getZ());
        if (dir.lengthSquared() < 1.0E-4) return;
        dir.normalize().multiply(wasInside ? 0.5 : -0.5).setY(0.15);
        p.setVelocity(dir);
    }

    private void blockedMessage(Player p) {
        long now = System.currentTimeMillis();
        Long last = lastMessage.get(p.getUniqueId());
        if (last != null && now - last < 1500) return;
        lastMessage.put(p.getUniqueId(), now);
        String text = getConfig().getString("messages.blocked", "");
        if (!text.isEmpty()) p.sendActionBar(MM.deserialize(text));
    }

    // ---------------- события ----------------

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onMove(PlayerMoveEvent e) {
        Location from = e.getFrom(), to = e.getTo();
        if (from.getBlockX() == to.getBlockX() && from.getBlockZ() == to.getBlockZ()) return;
        Player p = e.getPlayer();
        if (!bypass(p)) {
            Zone z = crossed(from, to);
            if (z != null) {
                Location back = from.clone();
                back.setYaw(to.getYaw());
                back.setPitch(to.getPitch());
                e.setTo(back);
                push(p, z, z.contains(from));
                blockedMessage(p);
                return;
            }
        }
        updateWall(p, to);
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onTeleport(PlayerTeleportEvent e) {
        Player p = e.getPlayer();
        if (BLOCKED_TELEPORTS.contains(e.getCause().name()) && !bypass(p) && crossed(e.getFrom(), e.getTo()) != null) {
            e.setCancelled(true);
            blockedMessage(p);
            return;
        }
        Bukkit.getScheduler().runTask(this, () -> refresh(p));
    }

    @EventHandler(ignoreCancelled = true)
    public void onVehicleMove(VehicleMoveEvent e) {
        Location from = e.getFrom(), to = e.getTo();
        if (from.getBlockX() == to.getBlockX() && from.getBlockZ() == to.getBlockZ()) return;
        Zone z = crossed(from, to);
        if (z == null) return;
        for (Entity passenger : new ArrayList<>(e.getVehicle().getPassengers())) {
            if (!(passenger instanceof Player p) || bypass(p)) continue;
            e.getVehicle().removePassenger(p);
            Location back = from.clone();
            back.setYaw(p.getLocation().getYaw());
            back.setPitch(p.getLocation().getPitch());
            p.teleport(back);
            blockedMessage(p);
        }
    }

    /** Мир подгрузили позже (Multiverse) - ставим и ему границу. */
    @EventHandler
    public void onWorldLoad(org.bukkit.event.world.WorldLoadEvent e) {
        if (!zonesIn(e.getWorld()).isEmpty()) applyWorldBorders();
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent e) {
        Player p = e.getPlayer();
        Bukkit.getScheduler().runTaskLater(this, () -> refresh(p), 5L);
    }

    @EventHandler
    public void onWorld(PlayerChangedWorldEvent e) {
        Player p = e.getPlayer();
        shown.remove(p.getUniqueId());
        p.setWorldBorder(null);
        Bukkit.getScheduler().runTask(this, () -> refresh(p));
    }

    @EventHandler
    public void onRespawn(PlayerRespawnEvent e) {
        Player p = e.getPlayer();
        Bukkit.getScheduler().runTask(this, () -> refresh(p));
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent e) {
        UUID id = e.getPlayer().getUniqueId();
        shown.remove(id);
        selections.remove(id);
        lastMessage.remove(id);
    }

    // ---------------- команды ----------------

    private void msg(CommandSender to, String key, TagResolver... r) {
        String text = getConfig().getString("messages." + key, key);
        if (!text.isEmpty()) to.sendMessage(MM.deserialize(text, r));
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!sender.hasPermission("mediaborders.admin")) return true;
        String sub = args.length == 0 ? "" : args[0].toLowerCase(Locale.ROOT);
        switch (sub) {
            case "pos1", "pos2" -> {
                if (!(sender instanceof Player p)) return true;
                Location l = p.getLocation().getBlock().getLocation();
                Location[] sel = selections.computeIfAbsent(p.getUniqueId(), k -> new Location[2]);
                sel[sub.equals("pos1") ? 0 : 1] = l;
                msg(p, "pos", Placeholder.unparsed("n", sub.substring(3)),
                        Placeholder.unparsed("x", String.valueOf(l.getBlockX())),
                        Placeholder.unparsed("z", String.valueOf(l.getBlockZ())));
            }
            case "create" -> {
                if (!(sender instanceof Player p)) return true;
                if (args.length < 2) {
                    msg(p, "usage");
                    return true;
                }
                String name = args[1];
                Location[] sel = selections.get(p.getUniqueId());
                if (sel == null || sel[0] == null || sel[1] == null) {
                    msg(p, "no-selection");
                    return true;
                }
                if (!sel[0].getWorld().equals(sel[1].getWorld())) {
                    msg(p, "other-world");
                    return true;
                }
                if (zones.containsKey(name.toLowerCase(Locale.ROOT))) {
                    msg(p, "exists", Placeholder.unparsed("name", name));
                    return true;
                }
                Zone z = new Zone(name, sel[0].getWorld().getName(),
                        Math.min(sel[0].getBlockX(), sel[1].getBlockX()), Math.min(sel[0].getBlockZ(), sel[1].getBlockZ()),
                        Math.max(sel[0].getBlockX(), sel[1].getBlockX()), Math.max(sel[0].getBlockZ(), sel[1].getBlockZ()));
                zones.put(name.toLowerCase(Locale.ROOT), z);
                saveZones();
                applyWorldBorders();
                refreshAll();
                msg(p, "created", Placeholder.unparsed("name", name), Placeholder.unparsed("world", z.world()),
                        Placeholder.unparsed("w", String.valueOf(z.width())), Placeholder.unparsed("d", String.valueOf(z.depth())));
            }
            case "remove" -> {
                if (args.length < 2) {
                    msg(sender, "usage");
                    return true;
                }
                Zone z = zones.remove(args[1].toLowerCase(Locale.ROOT));
                if (z == null) {
                    msg(sender, "not-found", Placeholder.unparsed("name", args[1]));
                    return true;
                }
                saveZones();
                applyWorldBorders();
                refreshAll();
                msg(sender, "removed", Placeholder.unparsed("name", z.name()));
            }
            case "list" -> {
                if (zones.isEmpty()) {
                    msg(sender, "list-empty");
                    return true;
                }
                msg(sender, "list-header", Placeholder.unparsed("count", String.valueOf(zones.size())));
                for (Zone z : zones.values()) {
                    msg(sender, "list-line", Placeholder.unparsed("name", z.name()), Placeholder.unparsed("world", z.world()),
                            Placeholder.unparsed("w", String.valueOf(z.width())), Placeholder.unparsed("d", String.valueOf(z.depth())),
                            Placeholder.unparsed("x1", String.valueOf(z.minX())), Placeholder.unparsed("z1", String.valueOf(z.minZ())),
                            Placeholder.unparsed("x2", String.valueOf(z.maxX())), Placeholder.unparsed("z2", String.valueOf(z.maxZ())));
                }
            }
            case "reload" -> {
                reloadConfig();
                loadZones();
                applyWorldBorders();
                refreshAll();
                msg(sender, "reloaded");
            }
            default -> msg(sender, "usage");
        }
        return true;
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        List<String> out = new ArrayList<>();
        if (!sender.hasPermission("mediaborders.admin")) return out;
        if (args.length == 1) out.addAll(List.of("pos1", "pos2", "create", "remove", "list", "reload"));
        else if (args.length == 2 && args[0].equalsIgnoreCase("remove")) for (Zone z : zones.values()) out.add(z.name());
        String prefix = args.length == 0 ? "" : args[args.length - 1].toLowerCase(Locale.ROOT);
        out.removeIf(s -> !s.toLowerCase(Locale.ROOT).startsWith(prefix));
        return out;
    }
}
