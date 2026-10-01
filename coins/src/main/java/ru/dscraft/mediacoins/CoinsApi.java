package ru.dscraft.mediacoins;

import org.bukkit.OfflinePlayer;

import java.util.UUID;

/**
 * Коины для других плагинов (скорборд, хранилища, магазины). Можно звать через рефлексию:
 * Class.forName("ru.dscraft.mediacoins.CoinsApi").getMethod("coins", OfflinePlayer.class).
 */
public final class CoinsApi {

    private static MediaCoinsPlugin plugin;

    private CoinsApi() {
    }

    static void init(MediaCoinsPlugin instance) {
        plugin = instance;
    }

    public static long coins(OfflinePlayer player) {
        return plugin == null ? 0 : plugin.get(player.getUniqueId());
    }

    public static long coins(UUID uuid) {
        return plugin == null ? 0 : plugin.get(uuid);
    }

    public static void give(UUID uuid, long amount) {
        if (plugin != null) plugin.add(uuid, Math.max(0, amount));
    }

    /** Списать, если хватает. @return true - списано */
    public static boolean take(UUID uuid, long amount) {
        if (plugin == null || amount < 0) return false;
        return plugin.take(uuid, amount);
    }

    public static void set(UUID uuid, long amount) {
        if (plugin != null) plugin.set(uuid, amount);
    }
}
