package ru.dscraft.destroypvp;

import org.bukkit.plugin.java.JavaPlugin;

/** DestroyPvP - объединённый плагин: DsRanks, StatPlugin, MediaTops, MediaPvP. Каждый - отдельный модуль со своей папкой plugins/DestroyPvP/<модуль>/. */
public final class DestroyPvPPlugin extends JavaPlugin {

    private Modules modules;

    @Override
    public void onEnable() {
        modules = new Modules(this);
        modules.enable(ru.dscraft.ranks.DsRanksPlugin::new, "DsRanks", "rank", "booster");
        modules.enable(ru.stat.StatPlugin::new, "StatPlugin", "stat", "mystat", "statadmin");
        modules.enable(ru.dscraft.mediatops.MediaTopsPlugin::new, "MediaTops", "tops");
        modules.enable(ru.dscraft.mediapvp.MediaPvPPlugin::new, "MediaPvP", "mediapvp");
    }

    @Override
    public void onDisable() {
        if (modules != null) modules.disableAll();
    }
}
