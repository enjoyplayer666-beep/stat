package ru.dscraft.mediagens;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Display;
import org.bukkit.entity.Entity;
import org.bukkit.entity.ItemDisplay;
import org.bukkit.entity.Player;
import org.bukkit.entity.TextDisplay;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.world.EntitiesLoadEvent;
import org.bukkit.event.world.WorldLoadEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.util.Transformation;
import org.joml.AxisAngle4f;
import org.joml.Vector3f;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/**
 * Генераторы: копят блоки (+amount раз в interval), над генератором - блок-иконка и "x{count}".
 * Игрок на площадке генератора забирает всё в инвентарь; с активным бустером - в N раз больше.
 * Бустеры - предметы MediaItems (по метке mediaitems:id), включаются ПКМ.
 */
public final class MediaGensPlugin extends JavaPlugin implements Listener {

    private static final MiniMessage MM = MiniMessage.miniMessage();

    /** Точка генератора. Точки одной группы работают синхронно: общий счётчик. */
    static final class Gen {
        String name;   // "группа:точка"
        String group;
        String point;
        String world;
        int x, y, z;
        Material material;
        UUID itemEntity, textEntity;
        String shown;
        /** угол поворота иконки (как у выпавшего предмета) */
        float angle;
    }

    private record Boost(int multiplier, long until) {
    }

    private final Map<String, Gen> gens = new LinkedHashMap<>();
    /** общий счётчик группы */
    private final Map<String, Integer> counts = new LinkedHashMap<>();
    private final Map<String, Material> materials = new LinkedHashMap<>();
    private final Map<UUID, Boost> boosts = new HashMap<>();
    private NamespacedKey genKey;
    private NamespacedKey itemsIdKey;
    private File dataFile;
    private int tickCounter;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        if (getConfig().getDouble("generator.item-scale") == 0.6) getConfig().set("generator.item-scale", 0.35);
        if (getConfig().getDouble("generator.item-offset") == 1.35) getConfig().set("generator.item-offset", 1.2);
        if (!getConfig().contains("generator.view-distance")) getConfig().set("generator.view-distance", 5);
        if (!getConfig().contains("generator.spin-degrees")) {
            getConfig().set("generator.spin-degrees", 15);
            getConfig().set("generator.bob", 0.06);
        }
        saveConfig();
        genKey = new NamespacedKey(this, "gen");
        itemsIdKey = new NamespacedKey("mediaitems", "id");
        dataFile = new File(getDataFolder(), "gens.yml");
        load();
        getServer().getPluginManager().registerEvents(this, this);
        Bukkit.getScheduler().runTaskTimer(this, this::tick, 20L, 1L);
        getLogger().info("Генераторов: " + gens.size());
    }

    @Override
    public void onDisable() {
        save();
        for (Gen g : gens.values()) removeDisplays(g);
    }

    private static Component mm(String s) {
        return MM.deserialize(s).decorationIfAbsent(TextDecoration.ITALIC, TextDecoration.State.FALSE);
    }

    private String msg(String key) {
        return getConfig().getString("messages." + key, "");
    }

    // ---------- хранение ----------

    private void load() {
        gens.clear();
        counts.clear();
        materials.clear();
        YamlConfiguration y = YamlConfiguration.loadConfiguration(dataFile);
        ConfigurationSection groups = y.getConfigurationSection("groups");
        if (groups != null) {
            for (String group : groups.getKeys(false)) {
                ConfigurationSection gs = groups.getConfigurationSection(group);
                Material m = Material.matchMaterial(gs.getString("material", "DIRT"));
                materials.put(group, m == null ? Material.DIRT : m);
                counts.put(group, gs.getInt("count"));
                ConfigurationSection pts = gs.getConfigurationSection("points");
                if (pts == null) continue;
                for (String point : pts.getKeys(false)) {
                    ConfigurationSection s = pts.getConfigurationSection(point);
                    addPoint(group, point, s.getString("world"), s.getInt("x"), s.getInt("y"), s.getInt("z"));
                }
            }
        }
        // старый формат (одиночные генераторы) -> группа с точкой p1
        ConfigurationSection old = y.getConfigurationSection("gens");
        if (old != null) {
            for (String name : old.getKeys(false)) {
                ConfigurationSection s = old.getConfigurationSection(name);
                Material m = Material.matchMaterial(s.getString("material", "DIRT"));
                materials.putIfAbsent(name, m == null ? Material.DIRT : m);
                counts.putIfAbsent(name, s.getInt("count"));
                addPoint(name, "p1", s.getString("world"), s.getInt("x"), s.getInt("y"), s.getInt("z"));
            }
        }
    }

    private Gen addPoint(String group, String point, String world, int x, int y, int z) {
        Gen g = new Gen();
        g.group = group;
        g.point = point;
        g.name = group + ":" + point;
        g.world = world;
        g.x = x;
        g.y = y;
        g.z = z;
        g.material = materials.getOrDefault(group, Material.DIRT);
        gens.put(g.name, g);
        return g;
    }

    private void save() {
        YamlConfiguration y = new YamlConfiguration();
        for (var e : materials.entrySet()) {
            y.set("groups." + e.getKey() + ".material", e.getValue().name());
            y.set("groups." + e.getKey() + ".count", counts.getOrDefault(e.getKey(), 0));
        }
        for (Gen g : gens.values()) {
            String p = "groups." + g.group + ".points." + g.point + ".";
            y.set(p + "world", g.world);
            y.set(p + "x", g.x);
            y.set(p + "y", g.y);
            y.set(p + "z", g.z);
        }
        try {
            y.save(dataFile);
        } catch (IOException e) {
            getLogger().warning("Не удалось сохранить gens.yml: " + e.getMessage());
        }
    }

    // ---------- тик ----------

    private void tick() {
        tickCounter++;
        var gc = getConfig();
        int interval = Math.max(1, gc.getInt("generator.interval-ticks", 20));
        boolean grow = tickCounter % interval == 0;
        boolean check = tickCounter % 5 == 0;
        if (grow) {
            int max = gc.getInt("generator.max", 512);
            boolean reset = "reset".equalsIgnoreCase(gc.getString("generator.on-full", "reset"));
            for (var e : counts.entrySet()) {
                int c = e.getValue();
                if (c >= max) {
                    if (reset) e.setValue(0);
                    continue;
                }
                e.setValue(Math.min(max, c + gc.getInt("generator.amount", 1)));
            }
        }
        if (check) {
            collect();
            for (Gen g : gens.values()) updateDisplay(g);
            boostBar();
        }
        if (tickCounter % 1200 == 0) save();
    }

    /** Игроки на площадке генератора забирают накопленное. */
    private void collect() {
        int r = getConfig().getInt("generator.radius", 1);
        for (Player p : Bukkit.getOnlinePlayers()) {
            if (p.isDead() || p.getGameMode() == org.bukkit.GameMode.SPECTATOR) continue;
            Location l = p.getLocation();
            for (Gen g : gens.values()) {
                int count = counts.getOrDefault(g.group, 0);
                if (count <= 0 || !l.getWorld().getName().equals(g.world)) continue;
                if (Math.abs(l.getBlockX() - g.x) > r || Math.abs(l.getBlockZ() - g.z) > r) continue;
                double dy = l.getY() - (g.y + 1);
                if (dy < -0.5 || dy > 2.5) continue;
                int mult = multiplier(p);
                int total = count * mult;
                int left = give(p, g.material, total);
                // что не влезло - остаётся в генераторе (в пересчёте без бустера); счётчик общий для всей группы
                counts.put(g.group, mult > 1 ? (left + mult - 1) / mult : left);
                if (total - left > 0) p.playSound(p.getLocation(), org.bukkit.Sound.ENTITY_ITEM_PICKUP, 0.4f, 1.4f);
            }
        }
    }

    /** Выдать amount блоков, вернуть сколько не влезло. */
    private static int give(Player p, Material m, int amount) {
        int left = 0;
        while (amount > 0) {
            int n = Math.min(amount, m.getMaxStackSize());
            amount -= n;
            for (ItemStack rest : p.getInventory().addItem(new ItemStack(m, n)).values()) left += rest.getAmount();
        }
        return left;
    }

    // ---------- отображение ----------

    private boolean loaded(Gen g) {
        World w = Bukkit.getWorld(g.world);
        return w != null && w.isChunkLoaded(g.x >> 4, g.z >> 4) && w.getChunkAt(g.x >> 4, g.z >> 4).isEntitiesLoaded();
    }

    private void updateDisplay(Gen g) {
        if (!loaded(g)) return;
        World w = Bukkit.getWorld(g.world);
        var gc = getConfig();
        Entity item = g.itemEntity == null ? null : Bukkit.getEntity(g.itemEntity);
        Entity text = g.textEntity == null ? null : Bukkit.getEntity(g.textEntity);
        int count = counts.getOrDefault(g.group, 0);
        if (count <= 0) {
            // пусто - блока-иконки нет, надпись тоже убираем
            if (item != null) item.remove();
            if (text != null) text.remove();
            g.itemEntity = g.textEntity = null;
            g.shown = null;
            return;
        }
        if (item == null || !item.isValid()) {
            float sc = (float) gc.getDouble("generator.item-scale", 0.35);
            Location at = new Location(w, g.x + 0.5, g.y + gc.getDouble("generator.item-offset", 1.2), g.z + 0.5);
            ItemDisplay d = w.spawn(at, ItemDisplay.class, e -> {
                e.setPersistent(false);
                e.getPersistentDataContainer().set(genKey, PersistentDataType.STRING, g.name);
                e.setItemStack(new ItemStack(g.material));
                e.setViewRange(viewRange());
                e.setTransformation(new Transformation(new Vector3f(), new AxisAngle4f(), new Vector3f(sc, sc, sc), new AxisAngle4f()));
            });
            g.itemEntity = d.getUniqueId();
        }
        if (text == null || !text.isValid()) {
            Location at = new Location(w, g.x + 0.5, g.y + gc.getDouble("generator.text-offset", 2.0), g.z + 0.5);
            TextDisplay d = w.spawn(at, TextDisplay.class, e -> {
                e.setPersistent(false);
                e.getPersistentDataContainer().set(genKey, PersistentDataType.STRING, g.name);
                e.setBillboard(Display.Billboard.CENTER);
                e.setViewRange(viewRange());
                e.setBackgroundColor(org.bukkit.Color.fromARGB(0, 0, 0, 0));
                e.setShadowed(true);
            });
            g.textEntity = d.getUniqueId();
            g.shown = null;
            text = d;
        }
        if (g.itemEntity != null && Bukkit.getEntity(g.itemEntity) instanceof ItemDisplay d && someoneNear(g)) spin(d, g);
        String s = gc.getString("generator.text", "<gray>x<aqua>{count}").replace("{count}", String.valueOf(count));
        if (!s.equals(g.shown) && text instanceof TextDisplay td) {
            td.text(mm(s));
            g.shown = s;
        }
    }

    /** Дальность видимости в блоках -> множитель Minecraft (1.0 = 64 блока). */
    private float viewRange() {
        return (float) (getConfig().getDouble("generator.view-distance", 5) / 64.0);
    }

    private boolean someoneNear(Gen g) {
        double r = getConfig().getDouble("generator.view-distance", 5) + 2;
        World w = Bukkit.getWorld(g.world);
        if (w == null) return false;
        Location c = new Location(w, g.x + 0.5, g.y + 1, g.z + 0.5);
        for (Player p : w.getPlayers()) {
            if (p.getLocation().distanceSquared(c) <= r * r) return true;
        }
        return false;
    }

    /** Поворот и покачивание, как у выпавшего предмета. */
    private void spin(ItemDisplay d, Gen g) {
        var gc = getConfig();
        float sc = (float) gc.getDouble("generator.item-scale", 0.35);
        // угол от общего времени - все точки крутятся одинаково (синхронно)
        double step = Math.toRadians(gc.getDouble("generator.spin-degrees", 15));
        g.angle = (float) (((tickCounter / 5) * step) % (Math.PI * 2));
        float bob = (float) (Math.sin(g.angle) * gc.getDouble("generator.bob", 0.06));
        d.setInterpolationDelay(0);
        d.setInterpolationDuration(5);
        d.setTransformation(new Transformation(new Vector3f(0, bob, 0), new AxisAngle4f(g.angle, 0, 1, 0),
                new Vector3f(sc, sc, sc), new AxisAngle4f()));
    }

    private void removeDisplays(Gen g) {
        for (UUID id : new UUID[]{g.itemEntity, g.textEntity}) {
            if (id == null) continue;
            Entity e = Bukkit.getEntity(id);
            if (e != null) e.remove();
        }
        g.itemEntity = g.textEntity = null;
    }

    /** Иконки, оставшиеся после краша, убираем - поставим заново. */
    @EventHandler
    public void onEntitiesLoad(EntitiesLoadEvent e) {
        for (Entity ent : e.getEntities()) {
            if (ent.getPersistentDataContainer().has(genKey, PersistentDataType.STRING)) {
                boolean ours = false;
                for (Gen g : gens.values()) {
                    if (ent.getUniqueId().equals(g.itemEntity) || ent.getUniqueId().equals(g.textEntity)) ours = true;
                }
                if (!ours) ent.remove();
            }
        }
    }

    @EventHandler
    public void onWorldLoad(WorldLoadEvent e) {
        // генераторы в мирах Multiverse появятся сами в следующем тике
    }

    // ---------- бустеры ----------

    private int multiplier(Player p) {
        Boost b = boosts.get(p.getUniqueId());
        if (b == null) return 1;
        if (b.until() < System.currentTimeMillis()) {
            boosts.remove(p.getUniqueId());
            p.sendMessage(mm(msg("ended")));
            return 1;
        }
        return b.multiplier();
    }

    private void boostBar() {
        long now = System.currentTimeMillis();
        for (Player p : Bukkit.getOnlinePlayers()) {
            Boost b = boosts.get(p.getUniqueId());
            if (b == null) continue;
            if (b.until() < now) {
                boosts.remove(p.getUniqueId());
                p.sendMessage(mm(msg("ended")));
                continue;
            }
            long left = (b.until() - now + 999) / 1000;
            p.sendActionBar(mm(msg("actionbar").replace("{mult}", String.valueOf(b.multiplier())).replace("{left}", String.valueOf(left))));
        }
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onUse(PlayerInteractEvent e) {
        if (e.getHand() != EquipmentSlot.HAND) return;
        if (e.getAction() != Action.RIGHT_CLICK_AIR && e.getAction() != Action.RIGHT_CLICK_BLOCK) return;
        ItemStack it = e.getItem();
        if (it == null || !it.hasItemMeta()) return;
        String id = it.getItemMeta().getPersistentDataContainer().get(itemsIdKey, PersistentDataType.STRING);
        if (id == null) return;
        ConfigurationSection b = getConfig().getConfigurationSection("boosters." + id);
        if (b == null) return;
        e.setCancelled(true);
        Player p = e.getPlayer();
        if (multiplier(p) > 1) {
            p.sendMessage(mm(msg("already")));
            return;
        }
        int mult = b.getInt("multiplier", 2);
        int sec = b.getInt("seconds", 60);
        boosts.put(p.getUniqueId(), new Boost(mult, System.currentTimeMillis() + sec * 1000L));
        it.setAmount(it.getAmount() - 1);
        p.sendMessage(mm(msg("activated").replace("{mult}", String.valueOf(mult)).replace("{sec}", String.valueOf(sec))));
        p.playSound(p.getLocation(), org.bukkit.Sound.ENTITY_PLAYER_LEVELUP, 0.7f, 1.5f);
    }

    // ---------- команды ----------

    @Override
    public boolean onCommand(CommandSender sender, Command cmd, String label, String[] a) {
        if (a.length == 0) {
            sender.sendMessage(mm("<gold>/gen create <группа> <точка> [материал]</gold> <gray>- точка генератора на блоке под тобой (dirt p1, dirt p2...)\n"
                    + "<gold>/gen remove [группа] [точка]</gold> <gray>- удалить точку/группу (без аргументов - ближайшую точку)\n"
                    + "<gold>/gen list</gold>, <gold>/gen reload"));
            return true;
        }
        switch (a[0].toLowerCase(Locale.ROOT)) {
            case "create" -> {
                // /gen create <группа> <точка> [материал]: точки одной группы работают синхронно (общий счётчик)
                if (!(sender instanceof Player p)) return true;
                if (a.length < 3) {
                    sender.sendMessage(mm("<red>/gen create <группа> <точка> [материал]  <gray>например: /gen create dirt p1"));
                    return true;
                }
                String group = a[1].toLowerCase(Locale.ROOT);
                String point = a[2].toLowerCase(Locale.ROOT);
                if (gens.containsKey(group + ":" + point)) {
                    sender.sendMessage(mm("<red>Точка " + point + " в группе " + group + " уже есть."));
                    return true;
                }
                Material m;
                if (a.length > 3) m = Material.matchMaterial(a[3]);
                else if (materials.containsKey(group)) m = materials.get(group);
                else {
                    // группа названа по блоку (dirt, stone...) - он и копится, иначе земля
                    Material byName = Material.matchMaterial(group);
                    m = byName != null && byName.isItem() ? byName : Material.DIRT;
                }
                if (m == null || !m.isItem()) {
                    sender.sendMessage(mm("<red>Нет такого предмета: " + a[3]));
                    return true;
                }
                materials.put(group, m);
                counts.putIfAbsent(group, 0);
                Block under = p.getLocation().subtract(0, 0.2, 0).getBlock();
                Gen g = addPoint(group, point, under.getWorld().getName(), under.getX(), under.getY(), under.getZ());
                for (Gen x : gens.values()) if (x.group.equals(group)) x.material = m;
                save();
                sender.sendMessage(mm("<green>Точка <white>" + point + "</white> группы <white>" + group + "</white> ("
                        + m.name().toLowerCase(Locale.ROOT) + ") на блоке " + g.x + " " + g.y + " " + g.z + "."));
            }
            case "remove" -> {
                // /gen remove <группа> [точка] - без точки удаляется вся группа; без аргументов - ближайшая точка
                List<Gen> del = new ArrayList<>();
                if (a.length > 2) {
                    Gen g = gens.get(a[1].toLowerCase(Locale.ROOT) + ":" + a[2].toLowerCase(Locale.ROOT));
                    if (g != null) del.add(g);
                } else if (a.length > 1) {
                    String group = a[1].toLowerCase(Locale.ROOT);
                    for (Gen g : gens.values()) if (g.group.equals(group)) del.add(g);
                } else if (sender instanceof Player p) {
                    Gen best = null;
                    double bd = 25;
                    for (Gen x : gens.values()) {
                        if (!x.world.equals(p.getWorld().getName())) continue;
                        double d = p.getLocation().distanceSquared(new Location(p.getWorld(), x.x + 0.5, x.y + 1, x.z + 0.5));
                        if (d < bd) {
                            bd = d;
                            best = x;
                        }
                    }
                    if (best != null) del.add(best);
                }
                if (del.isEmpty()) {
                    sender.sendMessage(mm("<red>Не найдено (/gen list)."));
                    return true;
                }
                for (Gen g : del) {
                    removeDisplays(g);
                    gens.remove(g.name);
                    sender.sendMessage(mm("<green>Удалена точка <white>" + g.point + "</white> группы <white>" + g.group));
                }
                // группа без точек больше не нужна
                materials.keySet().removeIf(gr -> gens.values().stream().noneMatch(g -> g.group.equals(gr)));
                counts.keySet().retainAll(materials.keySet());
                save();
            }
            case "list" -> {
                if (gens.isEmpty()) sender.sendMessage(mm("<gray>Генераторов нет."));
                for (var e : materials.entrySet()) {
                    sender.sendMessage(mm("<white>" + e.getKey() + "</white> <gray>(" + e.getValue().name().toLowerCase(Locale.ROOT)
                            + ") <aqua>x" + counts.getOrDefault(e.getKey(), 0)));
                    for (Gen g : gens.values()) {
                        if (g.group.equals(e.getKey())) sender.sendMessage(mm("<gray>  - " + g.point + ": " + g.world + " " + g.x + " " + g.y + " " + g.z));
                    }
                }
            }
            case "reload" -> {
                reloadConfig();
                for (Gen g : gens.values()) removeDisplays(g);
                sender.sendMessage(mm("<green>Конфиг перезагружен."));
            }
            default -> sender.sendMessage(mm("<red>/gen create|remove|list|reload"));
        }
        return true;
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command cmd, String alias, String[] a) {
        List<String> out = new ArrayList<>();
        if (a.length == 1) out.addAll(List.of("create", "remove", "list", "reload"));
        else if (a.length == 2 && (a[0].equalsIgnoreCase("remove") || a[0].equalsIgnoreCase("create"))) {
            out.addAll(materials.keySet());
            if (a[0].equalsIgnoreCase("create")) out.addAll(List.of("dirt", "stone", "coal", "iron_ingot", "diamond", "gunpowder", "gold_ingot"));
        } else if (a.length == 3) {
            for (Gen g : gens.values()) if (g.group.equals(a[1].toLowerCase(Locale.ROOT))) out.add(g.point);
            if (a[0].equalsIgnoreCase("create")) out.add("p" + (out.size() + 1));
        }
        String last = a[a.length - 1].toLowerCase(Locale.ROOT);
        out.removeIf(s -> !s.startsWith(last));
        return out;
    }
}
