package ru.dscraft.ranks;

import org.bukkit.entity.Player;

/**
 * Для других плагинов через рефлексию, без зависимости при сборке:
 * StatPlugin берёт отсюда ранг, убийства и бустер для /stat, DestroyChat - ранг для чата.
 * Пока DsRanks выключен, методы возвращают null / 0 / 1.
 */
public final class RanksApi {

    private static volatile DsRanksPlugin plugin;

    private RanksApi() {
    }

    static void init(DsRanksPlugin instance) {
        plugin = instance;
    }

    /** Ранг для чата (§-цвета), например "§x..☠ Лич ", или null - ранг скрыт (/rank off). Можно из асинхронного чата. */
    public static String chatRank(Player player) {
        DsRanksPlugin p = plugin;
        // без VIP ранга в чате нет
        return p == null || !p.canUseRanks(player) ? null : p.chatRank(player.getUniqueId());
    }

    /** Иконка и название ранга с градиентом в &#RRGGBB-кодах, например "&#E4E4E4☠ &#E4E4E4Л..." */
    public static String rankDisplay(String name) {
        DsRanksPlugin p = plugin;
        // без VIP ранга нет и в /stat
        return p == null || !p.hasRanks(name) ? null : p.rank(name).display();
    }

    /** Убийства к рангу (с учётом бустера). */
    public static int kills(String name) {
        DsRanksPlugin p = plugin;
        return p == null ? 0 : p.integer(name, "kills");
    }

    /** Бустер игрока (x1-x15). */
    public static int booster(String name) {
        DsRanksPlugin p = plugin;
        return p == null ? 1 : p.booster(name);
    }
}
