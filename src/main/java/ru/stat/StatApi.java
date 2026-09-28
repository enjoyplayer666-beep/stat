package ru.stat;

import org.bukkit.entity.Player;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Для других плагинов: DestroyChat берёт отсюда ранг игрока для чата (через рефлексию,
 * без зависимости при сборке). Можно вызывать из асинхронного чата.
 */
public final class StatApi {

    private static final Map<UUID, String> CHAT_RANKS = new ConcurrentHashMap<>();

    private StatApi() {
    }

    /** Ранг для чата (§-цвета), например "§x..☠ Лич ", или null - ранг скрыт (/rank off). */
    public static String chatRank(Player player) {
        return CHAT_RANKS.get(player.getUniqueId());
    }

    static void set(UUID uuid, String value) {
        if (value == null) CHAT_RANKS.remove(uuid);
        else CHAT_RANKS.put(uuid, value);
    }

    static void clear() {
        CHAT_RANKS.clear();
    }
}
