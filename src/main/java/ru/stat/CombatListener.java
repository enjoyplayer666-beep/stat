package ru.stat;

import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.PlayerDeathEvent;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Убийства игроков: боевой рейтинг, смерти, процент побед.
 * Убийства к рангу, бустер и умения ранга считает плагин DsRanks.
 */
public class CombatListener implements Listener {

    private final StatPlugin plugin;
    /** "убийца:жертва" -> время последнего засчитанного убийства. */
    private final Map<String, Long> lastKills = new ConcurrentHashMap<>();

    public CombatListener(StatPlugin plugin) {
        this.plugin = plugin;
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onDeath(PlayerDeathEvent event) {
        Player victim = event.getEntity();
        Player killer = victim.getKiller();
        if (killer == null || killer.equals(victim)) return;
        FileConfiguration cfg = plugin.getConfig();
        ThreadLocalRandom rnd = ThreadLocalRandom.current();

        // ---- убитый: смерть, каждые N смертей минус пара процентов побед ----
        String v = victim.getName();
        int deaths = plugin.integer(v, "deaths") + 1;
        plugin.set(v, "deaths", deaths);
        int everyDeaths = Math.max(1, cfg.getInt("combat.winrate-every-deaths", 10));
        if (deaths % everyDeaths == 0) {
            int pMin = cfg.getInt("combat.winrate-death-penalty-min", 2);
            int pMax = Math.max(pMin, cfg.getInt("combat.winrate-death-penalty-max", 3));
            int penalty = rnd.nextInt(pMin, pMax + 1);
            plugin.set(v, "winrate", Math.max(0, plugin.integer(v, "winrate") - penalty));
        }

        // ---- защита от фарма ----
        long cooldown = cfg.getLong("combat.anti-farm-seconds", 300) * 1000L;
        long now = System.currentTimeMillis();
        String pair = killer.getUniqueId() + ":" + victim.getUniqueId();
        if (cooldown > 0) {
            Long last = lastKills.get(pair);
            if (last != null && now - last < cooldown) return;
            lastKills.entrySet().removeIf(e -> now - e.getValue() >= cooldown);
        }
        lastKills.put(pair, now);

        String k = killer.getName();

        // ---- боевой рейтинг ----
        int min = cfg.getInt("combat.rating-min", 10);
        int max = Math.max(min, cfg.getInt("combat.rating-max", 30));
        plugin.set(k, "rating", plugin.integer(k, "rating") + rnd.nextInt(min, max + 1));

        // ---- процент побед: первое убийство - сразу 100%, дальше каждые N убийств + бонус ----
        int real = plugin.integer(k, "real-kills") + 1;
        plugin.set(k, "real-kills", real);
        int winrate = plugin.integer(k, "winrate");
        if (!plugin.isTrue(k, "winrate-started")) {
            // первое убийство по новым правилам (в том числе у тех, кто убивал на старой версии)
            winrate = cfg.getInt("combat.winrate-first-kill", 100);
            plugin.set(k, "winrate-started", true);
        } else if (real % Math.max(1, cfg.getInt("combat.winrate-every-kills", 10)) == 0) {
            winrate += cfg.getInt("combat.winrate-kill-bonus", 1);
        }
        plugin.set(k, "winrate", Math.max(0, Math.min(100, winrate)));
    }
}
