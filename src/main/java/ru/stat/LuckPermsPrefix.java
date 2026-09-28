package ru.stat;

import net.luckperms.api.LuckPermsProvider;
import net.luckperms.api.event.user.UserDataRecalculateEvent;
import net.luckperms.api.model.user.User;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

/** Префикс игрока из LuckPerms. Вызывается только если LuckPerms установлен. */
final class LuckPermsPrefix {

    private LuckPermsPrefix() {
    }

    static String get(Player player) {
        User user = LuckPermsProvider.get().getUserManager().getUser(player.getUniqueId());
        if (user == null) return null;
        String prefix = user.getCachedData().getMetaData().getPrefix();
        return prefix == null || prefix.isBlank() ? null : prefix.trim();
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
