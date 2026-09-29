package ru.dscraft.mediaitems;

import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.RegisteredServiceProvider;

import java.lang.reflect.Method;

/**
 * Коины через рефлексию: DestroyLobby (ru.dscraft.destroylobby.api.LobbyApi), иначе экономика Vault.
 * Так плагин собирается и работает без них.
 */
final class Coins {

    private Coins() {
    }

    /** Списать; true - получилось. */
    static boolean take(Player player, long amount) {
        Plugin lobby = Bukkit.getPluginManager().getPlugin("DestroyLobby");
        if (lobby != null && lobby.isEnabled()) {
            try {
                Class<?> api = Class.forName("ru.dscraft.destroylobby.api.LobbyApi", true, lobby.getClass().getClassLoader());
                Method take = api.getMethod("takeCoins", Player.class, long.class);
                return (Boolean) take.invoke(null, player, amount);
            } catch (Exception ignored) {
                // старая версия DestroyLobby без takeCoins - пробуем Vault
            }
        }
        try {
            Class<?> eco = Class.forName("net.milkbowl.vault.economy.Economy");
            RegisteredServiceProvider<?> rsp = Bukkit.getServicesManager().getRegistration(eco);
            if (rsp == null) return false;
            Object provider = rsp.getProvider();
            boolean has = (Boolean) eco.getMethod("has", OfflinePlayer.class, double.class).invoke(provider, player, (double) amount);
            if (!has) return false;
            Object resp = eco.getMethod("withdrawPlayer", OfflinePlayer.class, double.class).invoke(provider, player, (double) amount);
            return (Boolean) resp.getClass().getMethod("transactionSuccess").invoke(resp);
        } catch (Exception e) {
            return false;
        }
    }
}
