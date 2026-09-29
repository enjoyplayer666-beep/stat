package ru.dscraft.mediaitems;

import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.NamespacedKey;
import org.bukkit.Registry;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.entity.Display;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.entity.TextDisplay;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerChangedWorldEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerTeleportEvent;
import org.bukkit.event.world.EntitiesLoadEvent;
import org.bukkit.metadata.MetadataValue;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.scoreboard.Objective;
import org.bukkit.scoreboard.Scoreboard;
import org.bukkit.util.Transformation;
import org.joml.AxisAngle4f;
import org.joml.Vector3f;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Здоровье: сколько бы HP ни давали сеты, в хотбаре всегда 10 сердечек (health scale).
 * Над головой у каждого живого игрока - столько же сердечек, сколько у него в хотбаре
 * (надпись-пассажир, только у настоящих игроков: у НПС её нет; в мирах из списка не показывается).
 */
final class Health implements Listener {

    private final MediaItemsPlugin plugin;
    private final NamespacedKey tag;
    private final Map<UUID, TextDisplay> labels = new HashMap<>();
    private final Map<UUID, Integer> shown = new HashMap<>();

    Health(MediaItemsPlugin plugin) {
        this.plugin = plugin;
        this.tag = new NamespacedKey(plugin, "hp");
        cleanupOldScoreboard();
    }

    private boolean enabled() {
        return plugin.getConfig().getBoolean("health.enabled", true);
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent e) {
        if (enabled()) scale(e.getPlayer());
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent e) {
        remove(e.getPlayer().getUniqueId());
    }

    /** Перед телепортом снимаем надпись (с пассажиром телепорт может не пройти), в тике поставим заново. */
    @EventHandler(priority = EventPriority.LOWEST)
    public void onTeleport(PlayerTeleportEvent e) {
        remove(e.getPlayer().getUniqueId());
    }

    @EventHandler
    public void onWorld(PlayerChangedWorldEvent e) {
        remove(e.getPlayer().getUniqueId());
    }

    /** Надписи, оставшиеся после краша/перезапуска, убираем. */
    @EventHandler
    public void onEntitiesLoad(EntitiesLoadEvent e) {
        for (Entity ent : e.getEntities()) {
            if (ent.getPersistentDataContainer().has(tag, PersistentDataType.BYTE)) ent.remove();
        }
    }

    private void scale(Player p) {
        p.setHealthScale(plugin.getConfig().getDouble("health.hotbar-hp", 20));
        p.setHealthScaled(true);
    }

    /** Сердечки в хотбаре: health / max * scale / 2, вверх до целого (как видит сам игрок). */
    private int hearts(Player p) {
        AttributeInstance max = p.getAttribute(maxHealth());
        double m = max == null ? 20 : max.getValue();
        double scaled = p.getHealth() / m * p.getHealthScale();
        return (int) Math.ceil(scaled / 2.0 - 1e-6);
    }

    /** Показывать ли надпись над игроком. */
    private boolean visible(Player p) {
        if (p.isDead() || p.getGameMode() == GameMode.SPECTATOR) return false;
        if (p.isInvisible() || p.hasPotionEffect(PotionEffectType.INVISIBILITY)) return false;
        for (MetadataValue v : p.getMetadata("vanished")) if (v.asBoolean()) return false;
        List<String> off = plugin.getConfig().getStringList("health.disabled-worlds");
        return !off.contains(p.getWorld().getName());
    }

    void tick() {
        if (!enabled()) {
            for (UUID id : Set.copyOf(labels.keySet())) remove(id);
            return;
        }
        Set<UUID> online = new HashSet<>();
        for (Player p : Bukkit.getOnlinePlayers()) {
            online.add(p.getUniqueId());
            if (!p.isHealthScaled()) scale(p);
            if (!visible(p)) {
                remove(p.getUniqueId());
                continue;
            }
            TextDisplay d = labels.get(p.getUniqueId());
            if (d == null || !d.isValid() || d.getVehicle() != p) {
                remove(p.getUniqueId());
                d = spawn(p);
            }
            int h = hearts(p);
            Integer was = shown.get(p.getUniqueId());
            if (was == null || was != h) {
                d.text(Text.mm(plugin.getConfig().getString("health.format", "<white>{hearts} <red>❤")
                        .replace("{hearts}", String.valueOf(h))));
                shown.put(p.getUniqueId(), h);
            }
        }
        for (UUID id : Set.copyOf(labels.keySet())) if (!online.contains(id)) remove(id);
    }

    private TextDisplay spawn(Player p) {
        float y = (float) plugin.getConfig().getDouble("health.offset", 0.3);
        TextDisplay d = p.getWorld().spawn(p.getLocation(), TextDisplay.class, t -> {
            t.setPersistent(false);
            t.getPersistentDataContainer().set(tag, PersistentDataType.BYTE, (byte) 1);
            t.setBillboard(Display.Billboard.CENTER);
            t.setShadowed(true);
            t.setSeeThrough(false);
            t.setTransformation(new Transformation(new Vector3f(0, y, 0), new AxisAngle4f(), new Vector3f(1, 1, 1), new AxisAngle4f()));
        });
        p.addPassenger(d);
        p.hideEntity(plugin, d); // сам игрок свою надпись не видит
        labels.put(p.getUniqueId(), d);
        shown.remove(p.getUniqueId());
        return d;
    }

    private void remove(UUID id) {
        TextDisplay d = labels.remove(id);
        shown.remove(id);
        if (d != null && d.isValid()) d.remove();
    }

    void shutdown() {
        for (UUID id : Set.copyOf(labels.keySet())) remove(id);
    }

    /** Прошлая версия показывала HP счётом под ником - убираем его со всех табло. */
    private void cleanupOldScoreboard() {
        Set<Scoreboard> boards = new HashSet<>();
        boards.add(Bukkit.getScoreboardManager().getMainScoreboard());
        for (Player p : Bukkit.getOnlinePlayers()) boards.add(p.getScoreboard());
        for (Scoreboard b : boards) {
            Objective o = b.getObjective("mi_hp");
            if (o != null) o.unregister();
        }
    }

    private static Attribute maxHealth() {
        Attribute a = Registry.ATTRIBUTE.get(NamespacedKey.minecraft("generic.max_health"));
        return a != null ? a : Registry.ATTRIBUTE.get(NamespacedKey.minecraft("max_health"));
    }
}
