package ru.dscraft.mediaeconomy;

import org.bukkit.plugin.java.JavaPlugin;

/** MediaEconomy - объединённый плагин: MediaCoins, MediaVaults, DsMenu. Каждый - отдельный модуль со своей папкой plugins/MediaEconomy/<модуль>/. */
public final class MediaEconomyPlugin extends JavaPlugin {

    private Modules modules;

    @Override
    public void onEnable() {
        modules = new Modules(this);
        modules.enable(ru.dscraft.mediacoins.MediaCoinsPlugin::new, "MediaCoins", "coinsgive", "coins");
        modules.enable(ru.dscraft.mediavaults.MediaVaultsPlugin::new, "MediaVaults", "ec");
        modules.enable(ru.dscraft.dsmenu.DsMenuPlugin::new, "DsMenu", "menu", "donate", "warps", "dsmenu");
    }

    @Override
    public void onDisable() {
        if (modules != null) modules.disableAll();
    }
}
