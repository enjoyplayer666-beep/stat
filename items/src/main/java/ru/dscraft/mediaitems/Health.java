package ru.dscraft.mediaitems;

import org.bukkit.Bukkit;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.scoreboard.Criteria;
import org.bukkit.scoreboard.DisplaySlot;
import org.bukkit.scoreboard.Objective;
import org.bukkit.scoreboard.Scoreboard;

import java.util.HashSet;
import java.util.Set;

/**
 * Здоровье: сколько бы HP ни давали сеты, в хотбаре всегда 10 сердечек (health scale),
 * а под ником у всех видно столько же сердечек, сколько у игрока в хотбаре - уменьшается при уроне,
 * восполняется при лечении.
 */
final class Health implements Listener {

    private static final String OBJ = "mi_hp";
    private final MediaItemsPlugin plugin;

    Health(MediaItemsPlugin plugin) {
        this.plugin = plugin;
    }

    private boolean enabled() {
        return plugin.getConfig().getBoolean("health.enabled", true);
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent e) {
        if (enabled()) scale(e.getPlayer());
    }

    private void scale(Player p) {
        p.setHealthScale(plugin.getConfig().getDouble("health.hotbar-hp", 20));
        p.setHealthScaled(true);
    }

    /** Сердечки в хотбаре: health / max * scale / 2, вверх до целого (как видит сам игрок). */
    private int hearts(Player p) {
        AttributeInstance max = p.getAttribute(maxHealth());
        double m = max == null ? 20 : max.getValue();
        double shown = p.getHealth() / m * p.getHealthScale();
        return (int) Math.ceil(shown / 2.0 - 1e-6);
    }

    @SuppressWarnings("deprecation")
    void tick() {
        if (!enabled()) return;
        Set<Scoreboard> boards = new HashSet<>();
        boards.add(Bukkit.getScoreboardManager().getMainScoreboard());
        for (Player p : Bukkit.getOnlinePlayers()) {
            if (!p.isHealthScaled()) scale(p);
            boards.add(p.getScoreboard());
        }
        String title = plugin.getConfig().getString("health.below-name", "<red>❤");
        for (Scoreboard b : boards) {
            Objective o = b.getObjective(OBJ);
            if (o == null) o = b.registerNewObjective(OBJ, Criteria.DUMMY, Text.mm(title));
            if (o.getDisplaySlot() != DisplaySlot.BELOW_NAME) o.setDisplaySlot(DisplaySlot.BELOW_NAME);
            for (Player p : Bukkit.getOnlinePlayers()) {
                int h = hearts(p);
                var score = o.getScore(p.getName());
                if (!score.isScoreSet() || score.getScore() != h) score.setScore(h);
            }
        }
    }

    private static Attribute maxHealth() {
        Attribute a = org.bukkit.Registry.ATTRIBUTE.get(org.bukkit.NamespacedKey.minecraft("generic.max_health"));
        return a != null ? a : org.bukkit.Registry.ATTRIBUTE.get(org.bukkit.NamespacedKey.minecraft("max_health"));
    }
}
