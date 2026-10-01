package ru.dscraft.mediaitems;

import org.bukkit.Material;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerItemDamageEvent;

/** Элитры (и обычные, и элитры сетов) не тратят прочность - настройка elytra-no-durability. */
final class ElytraDurability implements Listener {

    private final MediaItemsPlugin plugin;

    ElytraDurability(MediaItemsPlugin plugin) {
        this.plugin = plugin;
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onDamage(PlayerItemDamageEvent e) {
        if (e.getItem().getType() == Material.ELYTRA && plugin.getConfig().getBoolean("elytra-no-durability", true)) {
            e.setCancelled(true);
        }
    }
}
