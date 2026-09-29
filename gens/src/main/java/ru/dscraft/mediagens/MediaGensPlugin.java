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

    static final class Gen {
        String name;
        String world;
        int x, y, z;
        Material material;
        int count;
        UUID itemEntity, textEntity;
        String shown;
        /** угол поворота иконки (как у выпавшего предмета) */
        float angle;
    }

    private record Boost(int multiplier, long until) {
    }

    private final Map<String, Gen> gens = new LinkedHashMap<>();
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
        YamlConfiguration y = YamlConfiguration.loadConfiguration(dataFile);
        ConfigurationSection sec = y.getConfigurationSection("gens");
        if (sec == null) return;
        for (String name : sec.getKeys(false)) {
            ConfigurationSection s = sec.getConfigurationSection(name);
            Gen g = new Gen();
            g.name = name;
            g.world = s.getString("world");
            g.x = s.getInt("x");
            g.y = s.getInt("y");
            g.z = s.getInt("z");
            Material m = Material.matchMaterial(s.getString("material", "DIRT"));
            g.material = m == null ? Material.DIRT : m;
            g.count = s.getInt("count");
            gens.put(name, g);
        }
    }

    private void save() {
        YamlConfiguration y = new YamlConfiguration();
        for (Gen g : gens.values()) {
            String p = "gens." + g.name + ".";
            y.set(p + "world", g.world);
            y.set(p + "x", g.x);
            y.set(p + "y", g.y);
            y.set(p + "z", g.z);
            y.set(p + "material", g.material.name());
            y.set(p + "count", g.count);
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
            for (Gen g : gens.values()) {
                if (g.count >= max) {
                    if (reset) g.count = 0;
                    continue;
                }
                g.count = Math.min(max, g.count + gc.getInt("generator.amount", 1));
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
                if (g.count <= 0 || !l.getWorld().getName().equals(g.world)) continue;
                if (Math.abs(l.getBlockX() - g.x) > r || Math.abs(l.getBlockZ() - g.z) > r) continue;
                double dy = l.getY() - (g.y + 1);
                if (dy < -0.5 || dy > 2.5) continue;
                int mult = multiplier(p);
                int total = g.count * mult;
                int left = give(p, g.material, total);
                // что не влезло - остаётся в генераторе (в пересчёте без бустера)
                g.count = mult > 1 ? (left + mult - 1) / mult : left;
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
        if (g.count <= 0) {
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
        String s = gc.getString("generator.text", "<gray>x<aqua>{count}").replace("{count}", String.valueOf(g.count));
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
        g.angle += (float) Math.toRadians(gc.getDouble("generator.spin-degrees", 15));
        if (g.angle > Math.PI * 2) g.angle -= (float) (Math.PI * 2);
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
            sender.sendMessage(mm("<gold>/gen create <название> [материал]</gold> <gray>- генератор на блоке под тобой\n"
                    + "<gold>/gen remove [название]</gold> <gray>- удалить (без названия - ближайший)\n"
                    + "<gold>/gen list</gold>, <gold>/gen reload"));
            return true;
        }
        switch (a[0].toLowerCase(Locale.ROOT)) {
            case "create" -> {
                if (!(sender instanceof Player p)) return true;
                if (a.length < 2) {
                    sender.sendMessage(mm("<red>/gen create <название> [материал]"));
                    return true;
                }
                String name = a[1].toLowerCase(Locale.ROOT);
                if (gens.containsKey(name)) {
                    sender.sendMessage(mm("<red>Генератор '" + name + "' уже есть."));
                    return true;
                }
                Material m = a.length > 2 ? Material.matchMaterial(a[2]) : Material.DIRT;
                if (m == null || !m.isItem()) {
                    sender.sendMessage(mm("<red>Нет такого предмета: " + a[2]));
                    return true;
                }
                Block under = p.getLocation().subtract(0, 0.2, 0).getBlock();
                Gen g = new Gen();
                g.name = name;
                g.world = under.getWorld().getName();
                g.x = under.getX();
                g.y = under.getY();
                g.z = under.getZ();
                g.material = m;
                gens.put(name, g);
                save();
                sender.sendMessage(mm("<green>Генератор <white>" + name + "</white> (" + m.name().toLowerCase(Locale.ROOT)
                        + ") поставлен на блок " + g.x + " " + g.y + " " + g.z + "."));
            }
            case "remove" -> {
                Gen g = null;
                if (a.length > 1) g = gens.get(a[1].toLowerCase(Locale.ROOT));
                else if (sender instanceof Player p) {
                    double best = 25;
                    for (Gen x : gens.values()) {
                        if (!x.world.equals(p.getWorld().getName())) continue;
                        double d = p.getLocation().distanceSquared(new Location(p.getWorld(), x.x + 0.5, x.y + 1, x.z + 0.5));
                        if (d < best) {
                            best = d;
                            g = x;
                        }
                    }
                }
                if (g == null) {
                    sender.sendMessage(mm("<red>Генератор не найден (/gen list)."));
                    return true;
                }
                removeDisplays(g);
                gens.remove(g.name);
                save();
                sender.sendMessage(mm("<green>Генератор <white>" + g.name + "</white> удалён."));
            }
            case "list" -> {
                if (gens.isEmpty()) sender.sendMessage(mm("<gray>Генераторов нет."));
                for (Gen g : gens.values()) {
                    sender.sendMessage(mm("<gray>- <white>" + g.name + "</white> " + g.material.name().toLowerCase(Locale.ROOT)
                            + " " + g.world + " " + g.x + " " + g.y + " " + g.z + " <aqua>x" + g.count));
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
        else if (a.length == 2 && a[0].equalsIgnoreCase("remove")) out.addAll(gens.keySet());
        else if (a.length == 3 && a[0].equalsIgnoreCase("create")) out.addAll(List.of("dirt", "stone", "coal", "iron_ingot", "diamond", "gunpowder", "gold_ingot"));
        String last = a[a.length - 1].toLowerCase(Locale.ROOT);
        out.removeIf(s -> !s.startsWith(last));
        return out;
    }
}
