package ru.dscraft.mediapvp;

import net.kyori.adventure.bossbar.BossBar;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.JoinConfiguration;
import net.kyori.adventure.text.event.HoverEvent;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import org.bukkit.Bukkit;
import org.bukkit.NamespacedKey;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.attribute.AttributeModifier;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Player;
import org.bukkit.entity.Projectile;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.player.PlayerChangedWorldEvent;
import org.bukkit.event.player.PlayerCommandPreprocessEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.EquipmentSlotGroup;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/**
 * MediaPvP:
 * - режим PvP на 10 секунд после удара (боссбар с противником, таймер над хотбаром, команды запрещены,
 *   выход из игры в бою - смерть), кроме лобби и команды проекта;
 * - "ник убил ник" в чат (повтор того же игрока раньше минуты - без сообщения);
 * - серии убийств ("Двойное убийство", ... "ПРЕВОСХОДИТ БОГОВ") и их срыв;
 * - PvP 1.8 (без задержки удара и размашистых атак) с метеоритного сета и выше.
 */
public final class MediaPvPPlugin extends JavaPlugin implements Listener {

    private static final MiniMessage MM = MiniMessage.miniMessage();

    /** Режим PvP игрока. */
    private static final class Combat {
        UUID opponent;
        String opponentName;
        long until;
        BossBar bar;
    }

    private final Map<UUID, Combat> combats = new HashMap<>();
    /** Игрок -> до какого времени показывать "Вы больше не в бою". */
    private final Map<UUID, Long> endShown = new HashMap<>();
    /** "убийца:жертва" -> время последнего убийства (повтор раньше минуты не считается). */
    private final Map<String, Long> lastKills = new HashMap<>();
    /** Серия игрока и последние жертвы серии. */
    private final Map<UUID, Integer> streaks = new HashMap<>();
    private final Map<UUID, Deque<String>> streakVictims = new HashMap<>();

    private NamespacedKey legacyKey;
    private NamespacedKey itemsIdKey;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        getConfig().options().copyDefaults(true);
        saveConfig();
        legacyKey = new NamespacedKey(this, "legacy_attack_speed");
        itemsIdKey = new NamespacedKey("mediaitems", "id");
        getServer().getPluginManager().registerEvents(this, this);
        Bukkit.getScheduler().runTaskTimer(this, this::tickCombat, 5L, 5L);
        Bukkit.getScheduler().runTaskTimer(this, () -> {
            for (Player p : Bukkit.getOnlinePlayers()) updateLegacy(p);
        }, 20L, 10L);
        getLogger().info("MediaPvP включен.");
    }

    @Override
    public void onDisable() {
        for (Player p : Bukkit.getOnlinePlayers()) {
            Combat c = combats.get(p.getUniqueId());
            if (c != null && c.bar != null) p.hideBossBar(c.bar);
            setLegacy(p, false);
        }
        combats.clear();
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        reloadConfig();
        sender.sendMessage("§aMediaPvP: конфиг перезагружен.");
        return true;
    }

    // ---------------- кто есть кто ----------------

    private boolean inLobby(Player p) {
        return p.getWorld().getName().equalsIgnoreCase(getConfig().getString("lobby-world", "world"));
    }

    private boolean isStaff(Player p) {
        if (p.isOp()) return true;
        for (String g : getConfig().getStringList("staff-groups")) {
            if (p.hasPermission("group." + g.toLowerCase(Locale.ROOT))) return true;
        }
        return false;
    }

    private static Player attackerOf(EntityDamageByEntityEvent event) {
        if (event.getDamager() instanceof Player p) return p;
        if (event.getDamager() instanceof Projectile pr && pr.getShooter() instanceof Player p) return p;
        return null;
    }

    private Component mm(String path, String def, TagResolver... resolvers) {
        return MM.deserialize(getConfig().getString(path, def), resolvers);
    }

    // ---------------- режим PvP ----------------

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onHit(EntityDamageByEntityEvent event) {
        if (!(event.getEntity() instanceof Player victim)) return;
        Player attacker = attackerOf(event);
        if (attacker == null || attacker.equals(victim)) return;
        if (inLobby(victim) || inLobby(attacker)) return;
        tag(attacker, victim);
        tag(victim, attacker);
    }

    /**
     * Замах по стаффу: у стаффа в творческом/god урон не проходит (и события урона может не быть),
     * но игрок, который его бьёт, всё равно получает режим PvP. Сам стафф - без режима.
     */
    @EventHandler(priority = EventPriority.MONITOR)
    public void onSwing(io.papermc.paper.event.player.PrePlayerAttackEntityEvent event) {
        if (!(event.getAttacked() instanceof Player victim)) return;
        Player attacker = event.getPlayer();
        if (attacker.equals(victim) || inLobby(attacker) || inLobby(victim)) return;
        if (!isStaff(victim)) return;
        tag(attacker, victim);
    }

    /**
     * Замах по стаффу: у стаффа в творческом/god урон не проходит (и события урона может не быть),
     * но игрок, который его бьёт, всё равно получает режим PvP. Сам стафф - без режима.
     */
    @EventHandler(priority = EventPriority.MONITOR)
    public void onSwing(io.papermc.paper.event.player.PrePlayerAttackEntityEvent event) {
        if (!(event.getAttacked() instanceof Player victim)) return;
        Player attacker = event.getPlayer();
        if (attacker.equals(victim) || inLobby(attacker) || inLobby(victim)) return;
        if (!isStaff(victim)) return;
        tag(attacker, victim);
    }

    private void tag(Player player, Player opponent) {
        if (isStaff(player)) return; // команду проекта режим не трогает
        Combat c = combats.computeIfAbsent(player.getUniqueId(), k -> new Combat());
        c.opponent = opponent.getUniqueId();
        c.opponentName = opponent.getName();
        c.until = System.currentTimeMillis() + getConfig().getLong("combat.seconds", 10) * 1000L;
        endShown.remove(player.getUniqueId());
        if (c.bar == null) {
            c.bar = BossBar.bossBar(Component.empty(), 1f, BossBar.Color.RED, BossBar.Overlay.PROGRESS);
            player.showBossBar(c.bar);
        }
        render(player, c);
    }

    public boolean inCombat(Player p) {
        return combats.containsKey(p.getUniqueId());
    }

    private static String hearts(double health) {
        double h = Math.round(health / 2.0 * 10.0) / 10.0;
        if (h == Math.floor(h)) return String.valueOf((long) h);
        return String.valueOf(h).replace('.', ',');
    }

    /** Боссбар (здоровье противника) и таймер над хотбаром. */
    private void render(Player player, Combat c) {
        Player opp = Bukkit.getPlayer(c.opponent);
        double hp = opp == null ? 0 : opp.getHealth();
        AttributeInstance maxAttr = opp == null ? null : opp.getAttribute(Attribute.GENERIC_MAX_HEALTH);
        double max = maxAttr == null ? 20 : maxAttr.getValue();
        c.bar.name(mm("combat.bossbar", "<name>: <hp>/<max>",
                Placeholder.unparsed("name", c.opponentName),
                Placeholder.unparsed("hp", hearts(hp)),
                Placeholder.unparsed("max", hearts(max))));
        c.bar.progress((float) Math.max(0, Math.min(1, hp / max)));
        long left = Math.max(1, (c.until - System.currentTimeMillis() + 999) / 1000);
        player.sendActionBar(mm("combat.actionbar", "РЕЖИМ PvP | <sec> сек.", Placeholder.unparsed("sec", String.valueOf(left))));
    }

    private void tickCombat() {
        long now = System.currentTimeMillis();
        for (Map.Entry<UUID, Combat> e : new ArrayList<>(combats.entrySet())) {
            Player p = Bukkit.getPlayer(e.getKey());
            Combat c = e.getValue();
            if (p == null) {
                combats.remove(e.getKey());
                continue;
            }
            if (now >= c.until) endCombat(p, true);
            else render(p, c);
        }
        // "Вы больше не в бою" держится end-seconds секунд
        for (Map.Entry<UUID, Long> e : new ArrayList<>(endShown.entrySet())) {
            Player p = Bukkit.getPlayer(e.getKey());
            if (p == null || now >= e.getValue()) {
                endShown.remove(e.getKey());
                continue;
            }
            p.sendActionBar(mm("combat.end", "Вы больше не в бою. Команды доступны."));
        }
    }

    /** Конец режима: боссбар плавно уходит, над хотбаром "Вы больше не в бою". */
    private void endCombat(Player p, boolean announce) {
        Combat c = combats.remove(p.getUniqueId());
        if (c == null) return;
        BossBar bar = c.bar;
        if (bar != null) {
            float start = bar.progress();
            int steps = 10;
            for (int i = 1; i <= steps; i++) {
                float value = start * (steps - i) / steps;
                boolean last = i == steps;
                Bukkit.getScheduler().runTaskLater(this, () -> {
                    if (last) p.hideBossBar(bar);
                    else bar.progress(Math.max(0f, value));
                }, i);
            }
        }
        if (announce) {
            endShown.put(p.getUniqueId(), System.currentTimeMillis() + getConfig().getLong("combat.end-seconds", 3) * 1000L);
            p.sendActionBar(mm("combat.end", "Вы больше не в бою. Команды доступны."));
        }
    }

    private void clearCombat(Player p) {
        Combat c = combats.remove(p.getUniqueId());
        if (c != null && c.bar != null) p.hideBossBar(c.bar);
        endShown.remove(p.getUniqueId());
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onCommand(PlayerCommandPreprocessEvent event) {
        if (!inCombat(event.getPlayer())) return;
        event.setCancelled(true);
        event.getPlayer().sendMessage(mm("combat.command-blocked", "Вы не можете делать это в режиме PvP!"));
    }

    /** Вышел из игры в бою - смерть, убийство засчитывается противнику. */
    @EventHandler(priority = EventPriority.LOWEST)
    public void onQuit(PlayerQuitEvent event) {
        Player p = event.getPlayer();
        Combat c = combats.get(p.getUniqueId());
        if (c != null && getConfig().getBoolean("combat.kill-on-quit", true) && !p.isDead()) {
            Player opp = Bukkit.getPlayer(c.opponent);
            if (opp != null) p.damage(Math.max(1000, p.getHealth() * 10), opp);
            if (!p.isDead()) p.setHealth(0);
        }
        clearCombat(p);
        setLegacy(p, false);
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        setLegacy(event.getPlayer(), false); // мог остаться с прошлого раза - пересчитается через полсекунды
    }

    @EventHandler
    public void onWorldChange(PlayerChangedWorldEvent event) {
        if (inLobby(event.getPlayer())) clearCombat(event.getPlayer());
    }

    // ---------------- убийства и серии ----------------

    @EventHandler(priority = EventPriority.HIGH)
    public void onDeath(PlayerDeathEvent event) {
        Player victim = event.getEntity();
        Player killer = victim.getKiller();
        clearCombat(victim);

        // серия жертвы сгорает при любой смерти; от игрока - "прервал серию"
        int victimStreak = streaks.getOrDefault(victim.getUniqueId(), 0);
        streaks.remove(victim.getUniqueId());
        streakVictims.remove(victim.getUniqueId());

        if (killer == null || killer.equals(victim)) return;
        event.deathMessage(null); // вместо ванильного - своё, без предмета

        if (victimStreak >= 2) {
            Bukkit.getServer().sendMessage(mm("streak-broken", "<killer> прервал серию из <count> убийств у <victim>!",
                    Placeholder.unparsed("killer", killer.getName()),
                    Placeholder.unparsed("victim", victim.getName()),
                    Placeholder.unparsed("count", String.valueOf(victimStreak))));
        }

        long now = System.currentTimeMillis();
        long repeat = getConfig().getLong("kills.repeat-seconds", 60) * 1000L;
        String pair = killer.getUniqueId() + ":" + victim.getUniqueId();
        Long last = lastKills.get(pair);
        lastKills.put(pair, now);
        lastKills.entrySet().removeIf(e -> now - e.getValue() >= Math.max(repeat, 60_000L));
        if (last != null && now - last < repeat) return; // тот же игрок раньше минуты - ни сообщения, ни серии

        Bukkit.getServer().sendMessage(mm("kills.message", "<killer> убил <victim>",
                Placeholder.unparsed("killer", killer.getName()),
                Placeholder.unparsed("victim", victim.getName())));

        int streak = streaks.merge(killer.getUniqueId(), 1, Integer::sum);
        Deque<String> victims = streakVictims.computeIfAbsent(killer.getUniqueId(), k -> new ArrayDeque<>());
        victims.addFirst(victim.getName());
        while (victims.size() > 10) victims.removeLast();
        if (streak >= 2) announceStreak(killer, streak, victims);
    }

    private void announceStreak(Player killer, int streak, Deque<String> victims) {
        ConfigurationSection s = getConfig().getConfigurationSection("streaks");
        if (s == null) return;
        String template = null;
        int best = -1;
        for (String key : s.getKeys(false)) {
            try {
                int n = Integer.parseInt(key.trim());
                if (n <= streak && n > best) {
                    best = n;
                    template = s.getString(key);
                }
            } catch (NumberFormatException ignored) {
            }
        }
        if (template == null) return;
        List<Component> hover = new ArrayList<>();
        hover.add(mm("streak-hover-title", "Последние 10 жертв:"));
        for (String v : victims) hover.add(mm("streak-hover-line", "- <victim>", Placeholder.unparsed("victim", v)));
        Component msg = MM.deserialize(template,
                Placeholder.unparsed("name", killer.getName()),
                Placeholder.unparsed("count", String.valueOf(streak)))
                .hoverEvent(HoverEvent.showText(Component.join(JoinConfiguration.newlines(), hover)));
        Bukkit.getServer().sendMessage(msg);
    }

    // ---------------- PvP 1.8 ----------------

    /** Сет предмета MediaItems ("meteor_sword" -> индекс meteor в legacy-pvp.sets), -1 - не сет. */
    private int setIndex(ItemStack it, List<String> sets) {
        if (it == null || !it.hasItemMeta()) return -1;
        String id = it.getItemMeta().getPersistentDataContainer().get(itemsIdKey, PersistentDataType.STRING);
        if (id == null) return -1;
        int u = id.indexOf('_');
        String set = (u < 0 ? id : id.substring(0, u)).toLowerCase(Locale.ROOT);
        return sets.indexOf(set);
    }

    /** На игроке или в руке предмет сета from-set или старше. */
    private boolean legacyGear(Player p) {
        if (!getConfig().getBoolean("legacy-pvp.enabled", true)) return false;
        List<String> sets = getConfig().getStringList("legacy-pvp.sets").stream().map(x -> x.toLowerCase(Locale.ROOT)).toList();
        int from = sets.indexOf(getConfig().getString("legacy-pvp.from-set", "meteor").toLowerCase(Locale.ROOT));
        if (from < 0) return false;
        if (setIndex(p.getInventory().getItemInMainHand(), sets) >= from) return true;
        for (ItemStack armor : p.getInventory().getArmorContents()) {
            if (setIndex(armor, sets) >= from) return true;
        }
        return false;
    }

    private void updateLegacy(Player p) {
        setLegacy(p, !inLobby(p) && legacyGear(p));
    }

    /** 1.8: скорость атаки огромная - нет задержки удара и урон всегда полный. */
    private void setLegacy(Player p, boolean on) {
        AttributeInstance attr = p.getAttribute(Attribute.GENERIC_ATTACK_SPEED);
        if (attr == null) return;
        AttributeModifier current = null;
        for (AttributeModifier m : attr.getModifiers()) {
            if (legacyKey.equals(m.getKey())) current = m;
        }
        if (on && current == null) {
            attr.addModifier(new AttributeModifier(legacyKey, 1020.0, AttributeModifier.Operation.ADD_NUMBER, EquipmentSlotGroup.ANY));
        } else if (!on && current != null) {
            attr.removeModifier(current);
        }
    }

    /** 1.8: размашистой атаки мечом нет. */
    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onSweep(EntityDamageByEntityEvent event) {
        if (event.getCause() != EntityDamageEvent.DamageCause.ENTITY_SWEEP_ATTACK) return;
        if (event.getDamager() instanceof Player p && legacyGear(p)) event.setCancelled(true);
    }
}
