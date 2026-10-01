package ru.dscraft.ranks;

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
 * Убийства игроков: убийства к рангу (с бустером), сообщение о новом ранге.
 * Урон между игроками: умения ранга (атака и защита).
 * Боевой рейтинг, смерти и процент побед считает StatPlugin.
 */
public class KillListener implements Listener {

    private final DsRanksPlugin plugin;
    /** "убийца:жертва" -> время последнего засчитанного убийства. */
    private final Map<String, Long> lastKills = new ConcurrentHashMap<>();

    public KillListener(DsRanksPlugin plugin) {
        this.plugin = plugin;
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onDeath(PlayerDeathEvent event) {
        Player victim = event.getEntity();
        Player killer = victim.getKiller();
        if (killer == null || killer.equals(victim)) return;
        if (!plugin.canUseRanks(killer)) return; // ранги - с VIP
        FileConfiguration cfg = plugin.getConfig();

        // ---- защита от фарма ----
        long cooldown = cfg.getLong("anti-farm-seconds", 300) * 1000L;
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

        // ---- убийства к рангу, бустер ----
        int booster = plugin.booster(k);
        int credited = 1;
        if (booster > 1 && ThreadLocalRandom.current().nextDouble(100) < cfg.getDouble("booster.chance", 30)) {
            credited = booster;
        }
        plugin.set(k, "kills", plugin.integer(k, "kills") + credited);
        // ◆ Вы убили ник и получили N очков ранга! (N - с бустером x1-x15)
        killer.sendMessage(plugin.color(plugin.msg("kill-points")
                .replace("{player}", victim.getName())
                .replace("{points}", String.valueOf(credited))));

        Ranks.Rank after = plugin.rank(k);
        if (after.index() > before.index()) {
            plugin.rankUp(killer, after);
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

        // без рангов (нет VIP) умения не работают - только обычные криты Minecraft
        int atk = plugin.canUseRanks(attacker) ? plugin.rank(attacker.getName()).attack() : 0;
        if (atk > 0 && rnd.nextDouble(100) < cfg.getDouble("skills.attack-chance", 33)) {
            damage *= 1 + atk / 100.0;
            attacker.sendMessage(plugin.color(plugin.msg("skill-attack").replace("{percent}", String.valueOf(atk))));
        }

        int def = plugin.canUseRanks(victim) ? plugin.rank(victim.getName()).defense() : 0;
        if (def > 0 && rnd.nextDouble(100) < cfg.getDouble("skills.defense-chance", 33)) {
            damage *= Math.max(0, 1 - def / 100.0);
            victim.sendMessage(plugin.color(plugin.msg("skill-defense").replace("{percent}", String.valueOf(def))));
        }

        event.setDamage(damage);
    }
}
