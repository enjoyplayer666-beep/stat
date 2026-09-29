package ru.dscraft.mediaitems;

import net.kyori.adventure.text.Component;
import org.bukkit.entity.Player;

/**
 * Для других плагинов (MediaTab) через рефлексию: выбранный титул игрока с его цветом.
 * Пустой компонент - титула нет.
 */
public final class TitlesApi {

    private static volatile Titles titles;

    private TitlesApi() {
    }

    static void init(Titles t) {
        titles = t;
    }

    public static Component title(Player player) {
        Titles t = titles;
        return t == null ? Component.empty() : t.title(player);
    }
}
