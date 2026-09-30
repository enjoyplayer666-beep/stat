package ru.dscraft.mediaitems;

import org.bukkit.Material;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;

/** Шлемы-блоки (метеоритный шлем - тонированное стекло): ПКМ надевает на голову, как обычный шлем. */
final class HeadWear implements Listener {

    private final MediaItemsPlugin plugin;

    HeadWear(MediaItemsPlugin plugin) {
        this.plugin = plugin;
    }

    @EventHandler
    public void onUse(PlayerInteractEvent e) {
        if (e.getHand() != EquipmentSlot.HAND) return;
        if (e.getAction() != Action.RIGHT_CLICK_AIR && e.getAction() != Action.RIGHT_CLICK_BLOCK) return;
        ItemStack it = e.getItem();
        if (it == null || !it.getType().isBlock() || it.getType() == Material.PLAYER_HEAD) return;
        String id = plugin.items().idOf(it);
        if (id == null || !id.endsWith("_helmet")) return;
        e.setCancelled(true);
        PlayerInventory inv = e.getPlayer().getInventory();
        ItemStack old = inv.getHelmet();
        inv.setHelmet(it.clone());
        inv.setItemInMainHand(old == null ? null : old);
        e.getPlayer().playSound(e.getPlayer().getLocation(), org.bukkit.Sound.ITEM_ARMOR_EQUIP_GENERIC, 1f, 1f);
    }
}
