package ru.dscraft.mediaitems;

import org.bukkit.inventory.ItemStack;

/** Для других плагинов (MediaGens) через рефлексию: предмет из items.yml по id, null - нет такого. */
public final class ItemsApi {

    private static volatile Items items;

    private ItemsApi() {
    }

    static void init(Items i) {
        items = i;
    }

    public static ItemStack item(String id) {
        Items i = items;
        return i == null ? null : i.get(id);
    }
}
