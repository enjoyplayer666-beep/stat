package ru.dscraft.mediaitems;

import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.MerchantRecipe;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.logging.Logger;

/** Магазины из shops.yml: название, внешний вид НПС, обмены и улучшения через наковальню. */
final class Shops {

    /** Один обмен: 1-2 предмета -> результат. displayOnly - строка только для показа (улучшение в наковальне). */
    record Trade(List<ItemStack> ingredients, ItemStack result, ItemStack display, boolean displayOnly) {
    }

    /** Улучшение в наковальне: item (1 шт.) + with (count шт.) -> result. */
    record Upgrade(String item, String with, int count, ItemStack result) {
    }

    record Shop(String id, String title, String name, ConfigurationSection npc, List<Trade> trades) {
    }

    private final Map<String, Shop> shops = new LinkedHashMap<>();
    private final List<Upgrade> upgrades = new ArrayList<>();
    private final MediaItemsPlugin plugin;
    private final Logger log;

    Shops(MediaItemsPlugin plugin) {
        this.plugin = plugin;
        this.log = plugin.getLogger();
    }

    void load(ConfigurationSection sec, Items items) {
        shops.clear();
        upgrades.clear();
        if (sec == null) return;
        ItemStack hint = items.get("anvil_hint");
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
            // anvil: улучшения через наковальню (/anvil, /upgrade или любая наковальня);
            // у НПС показываются строками "вещь + ресурс -> наковальня", обменять их у НПС нельзя
            n = 0;
            for (Map<?, ?> t : s.getMapList("anvil")) {
                n++;
                try {
                    String item = str(t.get("item"));
                    String with = str(t.get("with"));
                    ItemStack base = items.parse(item);
                    ItemStack cost = items.parse(with);
                    ItemStack result = items.parse(str(t.get("result")));
                    if (base == null || cost == null || result == null || items.idOf(base) == null || items.idOf(cost) == null) {
                        log.warning("Магазин " + id + ", наковальня #" + n + ": не найден предмет из items.yml");
                        continue;
                    }
                    upgrades.add(new Upgrade(items.idOf(base), items.idOf(cost), cost.getAmount(), result));
                    ItemStack shown = hint != null ? hint : result.clone();
                    trades.add(new Trade(List.of(base, cost), result, shown, true));
                } catch (Exception e) {
                    log.warning("Магазин " + id + ", наковальня #" + n + ": " + e.getMessage());
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
        List<ItemStack> ing = new ArrayList<>();
        for (String k : new String[]{"buy", "buy2"}) {
            if (t.get(k) == null) continue;
            ItemStack it = items.parse(str(t.get(k)));
            if (it == null) return null;
            ing.add(it);
        }
        if (ing.isEmpty()) return null;
        return new Trade(ing, result, result.clone(), false);
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

    List<Upgrade> upgrades() {
        return upgrades;
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
