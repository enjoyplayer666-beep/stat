package ru.stat;

import net.luckperms.api.LuckPermsProvider;
import net.luckperms.api.model.user.User;
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
}
