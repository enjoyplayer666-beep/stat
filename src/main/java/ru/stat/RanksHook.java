package ru.stat;

import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;

import java.lang.reflect.Method;

/**
 * Ранг, убийства и бустер из плагина DsRanks (ru.dscraft.ranks.RanksApi) - через рефлексию,
 * чтобы плагины собирались независимо. Без DsRanks: ранга нет, убийств 0, бустер x1.
 */
final class RanksHook {

    private static Method rankDisplay, kills, booster, chatRank;
    private static ClassLoader loadedFrom;

    private RanksHook() {
    }

    /** Иконка и название ранга (&#RRGGBB-коды) или null. */
    static String rankDisplay(String name) {
        Object v = call(() -> rankDisplay, name);
        return v instanceof String s ? s : null;
    }

    static int kills(String name) {
        Object v = call(() -> kills, name);
        return v instanceof Integer i ? i : 0;
    }

    static int booster(String name) {
        Object v = call(() -> booster, name);
        return v instanceof Integer i ? i : 1;
    }

    /** Ранг для чата (§-цвета) или null. */
    static String chatRank(Player player) {
        if (!resolve()) return null;
        try {
            return (String) chatRank.invoke(null, player);
        } catch (Exception e) {
            return null;
        }
    }

    private interface MethodRef {
        Method get();
    }

    private static Object call(MethodRef ref, String name) {
        if (!resolve()) return null;
        try {
            return ref.get().invoke(null, name);
        } catch (Exception e) {
            return null;
        }
    }

    /** Находит RanksApi; после перезагрузки DsRanks (новый загрузчик классов) ищет заново. */
    private static synchronized boolean resolve() {
        Plugin ranks = Bukkit.getPluginManager().getPlugin("DestroyPvP");
        if (ranks == null || !ranks.isEnabled()) return false;
        ClassLoader cl = ranks.getClass().getClassLoader();
        if (cl == loadedFrom && rankDisplay != null) return true;
        try {
            Class<?> api = Class.forName("ru.dscraft.ranks.RanksApi", true, cl);
            rankDisplay = api.getMethod("rankDisplay", String.class);
            kills = api.getMethod("kills", String.class);
            booster = api.getMethod("booster", String.class);
            chatRank = api.getMethod("chatRank", Player.class);
            loadedFrom = cl;
            return true;
        } catch (Exception e) {
            loadedFrom = null;
            rankDisplay = null;
            return false;
        }
    }
}
