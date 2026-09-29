package ru.stat;

import org.bukkit.entity.Player;

/**
 * Для других плагинов. Ранги теперь в отдельном плагине DsRanks; этот метод оставлен,
 * чтобы старые версии DestroyChat, которые берут ранг отсюда, продолжали работать.
 */
public final class StatApi {

    private StatApi() {
    }

    /** Ранг для чата (§-цвета) из DsRanks или null. */
    public static String chatRank(Player player) {
        return RanksHook.chatRank(player);
    }
}
