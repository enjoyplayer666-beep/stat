package ru.dscraft.destroyskypvp;

import org.bukkit.plugin.java.JavaPlugin;

/** DestroySkyPvP - объединённый плагин: MediaItems, MediaGens, MediaClear. Каждый - отдельный модуль со своей папкой plugins/DestroySkyPvP/<модуль>/. */
public final class DestroySkyPvPPlugin extends JavaPlugin {

    private Modules modules;

    @Override
    public void onEnable() {
        modules = new Modules(this);
        modules.enable(ru.dscraft.mediaitems.MediaItemsPlugin::new, "MediaItems", "itemnpc", "upgrade");
        modules.enable(ru.dscraft.mediagens.MediaGensPlugin::new, "MediaGens", "gen");
        modules.enable(ru.dscraft.mediaclear.MediaClearPlugin::new, "MediaClear", "mediaclear");
    }

    @Override
    public void onDisable() {
        if (modules != null) modules.disableAll();
    }
}
