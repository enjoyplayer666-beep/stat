package ru.stat;

import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.entity.Projectile;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.PlayerDeathEvent;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Убийства игроков: боевой рейтинг, убийства к рангу (с бустером), процент побед.
 * Урон между игроками: умения ранга (атака и защита).
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

        // ---- убитый: смерть и минус к проценту побед ----
        String v = victim.getName();
        plugin.set(v, "deaths", plugin.integer(v, "deaths") + 1);
        double penalty = cfg.getDouble("combat.winrate-death-penalty", 0.5);
        plugin.set(v, "winrate", round(Math.max(0, plugin.number(v, "winrate") - penalty)));

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
        Ranks.Rank before = plugin.rank(k);

        // ---- боевой рейтинг ----
        int min = cfg.getInt("combat.rating-min", 10);
        int max = Math.max(min, cfg.getInt("combat.rating-max", 30));
        int rating = rnd.nextInt(min, max + 1);
        plugin.set(k, "rating", plugin.integer(k, "rating") + rating);

        // ---- убийства к рангу, бустер ----
        int booster = plugin.booster(k);
        int credited = 1;
        if (booster > 1 && rnd.nextDouble(100) < cfg.getDouble("booster.chance", 30)) {
            credited = booster;
            killer.sendMessage(plugin.color(plugin.msg("booster")
                    .replace("{booster}", String.valueOf(booster))
                    .replace("{kills}", String.valueOf(credited))));
        }
        plugin.set(k, "kills", plugin.integer(k, "kills") + credited);

        // ---- процент побед: каждые N настоящих убийств ----
        int real = plugin.integer(k, "real-kills") + 1;
        plugin.set(k, "real-kills", real);
        int every = Math.max(1, cfg.getInt("combat.winrate-every-kills", 100));
        if (real % every == 0) {
            int bMin = cfg.getInt("combat.winrate-bonus-min", 10);
            int bMax = Math.max(bMin, cfg.getInt("combat.winrate-bonus-max", 20));
            int bonus = rnd.nextInt(bMin, bMax + 1);
            plugin.set(k, "winrate", round(Math.min(100, plugin.number(k, "winrate") + bonus)));
            killer.sendMessage(plugin.color(plugin.msg("winrate-up").replace("{bonus}", String.valueOf(bonus))));
        }

        killer.sendMessage(plugin.color(plugin.msg("kill")
                .replace("{rating}", String.valueOf(rating))
                .replace("{victim}", v)));

        Ranks.Rank after = plugin.rank(k);
        if (after.index() > before.index()) {
            killer.sendMessage(plugin.color(plugin.msg("rank-up").replace("{rank}", after.display())));
        }
        plugin.refreshChatRank(killer);
    }

    /** Умения ранга: атака (+% урона) у бьющего, защита (-% урона) у получающего. Только PvP. */
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onDamage(EntityDamageByEntityEvent event) {
        FileConfiguration cfg = plugin.getConfig();
        if (!cfg.getBoolean("skills.enabled", true)) return;
        if (!(event.getEntity() instanceof Player victim)) return;
        Player attacker = null;
        if (event.getDamager() instanceof Player p) attacker = p;
        else if (event.getDamager() instanceof Projectile pr && pr.getShooter() instanceof Player p) attacker = p;
        if (attacker == null || attacker.equals(victim)) return;

        ThreadLocalRandom rnd = ThreadLocalRandom.current();
        double damage = event.getDamage();

        int atk = plugin.rank(attacker.getName()).attack();
        if (atk > 0 && rnd.nextDouble(100) < cfg.getDouble("skills.attack-chance", 33)) damage *= 1 + atk / 100.0;

        int def = plugin.rank(victim.getName()).defense();
        if (def > 0 && rnd.nextDouble(100) < cfg.getDouble("skills.defense-chance", 33)) {
            damage *= Math.max(0, 1 - def / 100.0);
        }

        event.setDamage(damage);
    }

    private static double round(double v) {
        return Math.round(v * 10) / 10.0;
    }
}
