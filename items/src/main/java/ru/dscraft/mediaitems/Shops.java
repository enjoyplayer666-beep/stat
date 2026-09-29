package ru.dscraft.mediaitems;

import net.kyori.adventure.text.Component;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.MerchantRecipe;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.logging.Logger;

/** Магазины из shops.yml: название, внешний вид НПС и список обменов. */
final class Shops {

    /** Один обмен. coins > 0 - покупка за коины (ingredients тогда только для показа). */
    record Trade(List<ItemStack> ingredients, ItemStack result, ItemStack display, long coins) {
    }

    record Shop(String id, String title, String name, ConfigurationSection npc, List<Trade> trades) {
    }

    private final Map<String, Shop> shops = new LinkedHashMap<>();
    private final MediaItemsPlugin plugin;
    private final Logger log;

    Shops(MediaItemsPlugin plugin) {
        this.plugin = plugin;
        this.log = plugin.getLogger();
    }

    void load(ConfigurationSection sec, Items items) {
        shops.clear();
        if (sec == null) return;
        for (String id : sec.getKeys(false)) {
            ConfigurationSection s = sec.getConfigurationSection(id);
            if (s == null) continue;
            List<Trade> trades = new ArrayList<>();
            int n = 0;
            for (Map<?, ?> t : s.getMapList("trades")) {
                n++;
                try {
                    Trade trade = trade(t, items);
                    if (trade != null) trades.add(trade);
                    else log.warning("Магазин " + id + ", обмен #" + n + ": не найден предмет");
                } catch (Exception e) {
                    log.warning("Магазин " + id + ", обмен #" + n + ": " + e.getMessage());
                }
            }
            String key = id.toLowerCase(Locale.ROOT);
            shops.put(key, new Shop(key, s.getString("title", id), s.getString("name", id),
                    s.getConfigurationSection("npc"), trades));
        }
    }

    private Trade trade(Map<?, ?> t, Items items) {
        ItemStack result = items.parse(str(t.get("result")));
        if (result == null) return null;
        long coins = t.get("coins") instanceof Number num ? num.longValue() : 0;
        List<ItemStack> ing = new ArrayList<>();
        if (coins > 0) {
            ItemStack coin = items.parse(plugin.getConfig().getString("coins.icon", "FLINT"));
            if (coin == null) return null;
            ItemMeta m = coin.getItemMeta();
            m.displayName(Text.mm(plugin.getConfig().getString("coins.icon-name", "<yellow>{price} коинов")
                    .replace("{price}", String.valueOf(coins))));
            m.getPersistentDataContainer().set(plugin.displayKey, PersistentDataType.BYTE, (byte) 1);
            coin.setItemMeta(m);
            ing.add(coin);
        } else {
            for (String k : new String[]{"buy", "buy2"}) {
                if (t.get(k) == null) continue;
                ItemStack it = items.parse(str(t.get(k)));
                if (it == null) return null;
                ing.add(it);
            }
            if (ing.isEmpty()) return null;
        }
        // что видно в списке обменов: можно дописать строки (например "Цена: 200 коинов")
        ItemStack display = result.clone();
        Object extra = t.get("display-lore");
        if (extra instanceof List<?> lines && !lines.isEmpty()) {
            ItemMeta m = display.getItemMeta();
            List<Component> lore = m.lore() != null ? new ArrayList<>(m.lore()) : new ArrayList<>();
            for (Object line : lines) lore.add(Text.mm(String.valueOf(line)));
            m.lore(lore);
            display.setItemMeta(m);
        }
        return new Trade(ing, result, display, coins);
    }

    private static String str(Object o) {
        return o == null ? null : String.valueOf(o);
    }

    Shop get(String id) {
        return id == null ? null : shops.get(id.toLowerCase(Locale.ROOT));
    }

    Map<String, Shop> all() {
        return shops;
    }

    static List<MerchantRecipe> recipes(Shop shop) {
        List<MerchantRecipe> list = new ArrayList<>();
        for (Trade t : shop.trades()) {
            MerchantRecipe r = new MerchantRecipe(t.display(), 0, Integer.MAX_VALUE, false);
            r.setIngredients(t.ingredients());
            r.setIgnoreDiscounts(true);
            list.add(r);
        }
        return list;
    }
}
