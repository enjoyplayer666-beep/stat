package ru.stat;

import net.luckperms.api.LuckPermsProvider;
import net.luckperms.api.event.user.UserDataRecalculateEvent;
import net.luckperms.api.model.group.Group;
import net.luckperms.api.model.user.User;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

/** Префикс игрока из LuckPerms. Вызывается только если LuckPerms установлен. */
final class LuckPermsPrefix {

    private LuckPermsPrefix() {
    }

    /**
     * Префикс донатной группы, а не личный префикс игрока: донатеры меняют себе префикс
     * (через меню Deluxe и т.п.), а в /stat должна быть привилегия. Берётся группа игрока
     * с наибольшим весом, у которой есть свой префикс.
     */
    static String get(Player player) {
        User user = LuckPermsProvider.get().getUserManager().getUser(player.getUniqueId());
        return user == null ? null : groupPrefix(user);
    }

    /** Игрок не в сети: данные из LuckPerms (группу могли сменить, пока его не было). null - не удалось. */
    static User load(java.util.UUID uuid) {
        try {
            return LuckPermsProvider.get().getUserManager().loadUser(uuid).get(3, java.util.concurrent.TimeUnit.SECONDS);
        } catch (Exception e) {
            return null;
        }
    }

    /** Все группы игрока с наследованием (имена в нижнем регистре). */
    static java.util.Set<String> groups(User user) {
        java.util.Set<String> out = new java.util.HashSet<>();
        for (Group g : user.getInheritedGroups(user.getQueryOptions())) out.add(g.getName().toLowerCase(java.util.Locale.ROOT));
        return out;
    }

    static String groupPrefix(User user) {
        Group best = null;
        String bestPrefix = null;
        for (Group g : user.getInheritedGroups(user.getQueryOptions())) {
            String p = g.getCachedData().getMetaData().getPrefix();
            if (p == null || p.isBlank()) continue;
            if (best == null || g.getWeight().orElse(0) > best.getWeight().orElse(0)) {
                best = g;
                bestPrefix = p;
            }
        }
        return bestPrefix == null ? null : bestPrefix.trim();
    }

    /** Смена группы / префикса в LuckPerms -> сразу обновить привилегию и ранг в /stat. */
    static void listen(StatPlugin plugin) {
        LuckPermsProvider.get().getEventBus().subscribe(plugin, UserDataRecalculateEvent.class, event ->
                Bukkit.getScheduler().runTask(plugin, () -> {
                    Player player = Bukkit.getPlayer(event.getUser().getUniqueId());
                    if (player != null) plugin.refreshPrivilege(player);
                }));
    }
}
