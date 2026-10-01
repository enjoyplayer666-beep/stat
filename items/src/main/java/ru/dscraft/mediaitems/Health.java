package ru.dscraft.mediaitems;

import io.papermc.paper.scoreboard.numbers.NumberFormat;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.NamespacedKey;
import org.bukkit.Registry;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.world.EntitiesLoadEvent;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.scoreboard.Criteria;
import org.bukkit.scoreboard.DisplaySlot;
import org.bukkit.scoreboard.Objective;
import org.bukkit.scoreboard.Score;
import org.bukkit.scoreboard.Scoreboard;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Здоровье: сколько бы HP ни давали сеты, в хотбаре всегда 10 сердечек (health scale).
 * Под ником - строка "440 ❤" (стандартная строка под ником: игра сама поднимает ник, строка не
 * перекрывается головой). Число и сердце задаются каждому игроку отдельно, поэтому у НПС их нет.
 * Кто стоит в мире из disabled-worlds (лобби) - не видит эту строку вообще.
 */
final class Health implements Listener {

    private static final String OBJ = "mi_hp";
    private final MediaItemsPlugin plugin;
    private final NamespacedKey oldTag;
    /** что уже выставлено на каждом табло: табло -> (ник -> текст), чтобы не слать лишние пакеты */
    private final Map<Scoreboard, Map<String, String>> sent = new HashMap<>();

    Health(MediaItemsPlugin plugin) {
        this.plugin = plugin;
        this.oldTag = new NamespacedKey(plugin, "hp");
    }

    private boolean enabled() {
        return plugin.getConfig().getBoolean("health.enabled", true);
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent e) {
        if (!enabled()) return;
        base(e.getPlayer());
        scale(e.getPlayer());
    }

    /** Надписи прошлой версии (над головой) убираем. */
    @EventHandler
    public void onEntitiesLoad(EntitiesLoadEvent e) {
        for (Entity ent : e.getEntities()) {
            if (ent.getPersistentDataContainer().has(oldTag, PersistentDataType.BYTE)) ent.remove();
        }
    }

    /** Здоровье без брони (health.base-hp): сеты добавляют своё сверху. */
    private void base(Player p) {
        AttributeInstance max = p.getAttribute(maxHealth());
        if (max == null) return;
        double base = Math.max(1, plugin.getConfig().getDouble("health.base-hp", 10));
        if (max.getBaseValue() != base) max.setBaseValue(base);
    }

    private void scale(Player p) {
        p.setHealthScale(plugin.getConfig().getDouble("health.hotbar-hp", 20));
        p.setHealthScaled(true);
    }

    private int hearts(Player p) {
        AttributeInstance max = p.getAttribute(maxHealth());
        double m = max == null ? 20 : max.getValue();
        double scaled = p.getHealth() / m * p.getHealthScale();
        return (int) Math.ceil(scaled / 2.0 - 1e-6);
    }

    void tick() {
        if (!enabled()) {
            shutdown();
            return;
        }
        List<String> off = plugin.getConfig().getStringList("health.disabled-worlds");
        String fmt = plugin.getConfig().getString("health.format", "<white>{hp} <dark_red>❤");
        Map<String, String> text = new HashMap<>();
        for (Player p : Bukkit.getOnlinePlayers()) {
            base(p);
            if (!p.isHealthScaled()) scale(p);
            int hp = (int) Math.ceil(p.getHealth() - 1e-6);
            text.put(p.getName(), fmt.replace("{hp}", String.valueOf(hp)).replace("{hearts}", String.valueOf(hearts(p))));
        }
        Map<Scoreboard, Boolean> boards = new HashMap<>();
        for (Player viewer : Bukkit.getOnlinePlayers()) {
            boolean show = !off.contains(viewer.getWorld().getName());
            // общее табло показываем, если хоть один его зритель не в лобби
            boards.merge(viewer.getScoreboard(), show, Boolean::logicalOr);
        }
        for (var entry : boards.entrySet()) {
            Scoreboard b = entry.getKey();
            Objective o = b.getObjective(OBJ);
            if (o == null) {
                o = b.registerNewObjective(OBJ, Criteria.DUMMY, Component.empty());
                o.numberFormat(NumberFormat.blank());
                sent.remove(b);
            }
            if (entry.getValue()) {
                if (o.getDisplaySlot() != DisplaySlot.BELOW_NAME) o.setDisplaySlot(DisplaySlot.BELOW_NAME);
            } else if (o.getDisplaySlot() == DisplaySlot.BELOW_NAME) {
                o.setDisplaySlot(null);
            }
            Map<String, String> done = sent.computeIfAbsent(b, k -> new HashMap<>());
            for (var t : text.entrySet()) {
                if (t.getValue().equals(done.get(t.getKey()))) continue;
                Score s = o.getScore(t.getKey());
                s.setScore(0);
                s.numberFormat(NumberFormat.fixed(Text.mm(t.getValue())));
                done.put(t.getKey(), t.getValue());
            }
        }
        sent.keySet().retainAll(boards.keySet());
    }

    void shutdown() {
        for (Scoreboard b : sent.keySet()) {
            Objective o = b.getObjective(OBJ);
            if (o != null) o.unregister();
        }
        Objective main = Bukkit.getScoreboardManager().getMainScoreboard().getObjective(OBJ);
        if (main != null) main.unregister();
        sent.clear();
    }

    private static Attribute maxHealth() {
        Attribute a = Registry.ATTRIBUTE.get(NamespacedKey.minecraft("generic.max_health"));
        return a != null ? a : Registry.ATTRIBUTE.get(NamespacedKey.minecraft("max_health"));
    }
}
