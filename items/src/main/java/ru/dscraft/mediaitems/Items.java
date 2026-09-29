package ru.dscraft.mediaitems;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Color;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Registry;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeModifier;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.inventory.EquipmentSlotGroup;
import org.bukkit.inventory.ItemFlag;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.inventory.meta.LeatherArmorMeta;
import org.bukkit.persistence.PersistentDataType;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.logging.Logger;

/**
 * Предметы из items.yml: модель из ресурспака (custom_model_data), градиентное название,
 * зачарования и атрибуты своими строками описания (иконка, цвета как на скринах).
 */
final class Items {

    private final NamespacedKey idKey;
    private final Logger log;
    private final Map<String, ItemStack> items = new LinkedHashMap<>();
    private ConfigurationSection tooltip;

    Items(NamespacedKey idKey, Logger log) {
        this.idKey = idKey;
        this.log = log;
    }

    void load(ConfigurationSection itemsSec, ConfigurationSection tooltip) {
        this.tooltip = tooltip;
        items.clear();
        if (itemsSec == null) return;
        for (String id : itemsSec.getKeys(false)) {
            ConfigurationSection s = itemsSec.getConfigurationSection(id);
            if (s == null) continue;
            try {
                items.put(id.toLowerCase(Locale.ROOT), build(id, s));
            } catch (Exception e) {
                log.warning("Предмет '" + id + "' пропущен: " + e.getMessage());
            }
        }
    }

    Map<String, ItemStack> all() {
        return items;
    }

    /** id предмета из items.yml или null для обычных предметов. */
    String idOf(ItemStack it) {
        if (it == null || !it.hasItemMeta()) return null;
        return it.getItemMeta().getPersistentDataContainer().get(idKey, PersistentDataType.STRING);
    }

    ItemStack get(String id) {
        ItemStack it = items.get(id.toLowerCase(Locale.ROOT));
        return it == null ? null : it.clone();
    }

    /**
     * "item:id:кол-во" - предмет из items.yml, "MATERIAL:кол-во" - обычный предмет, кол-во можно не писать.
     */
    ItemStack parse(String spec) {
        if (spec == null || spec.isBlank()) return null;
        String dye = null;
        int hash = spec.indexOf('#');
        if (hash > 0) {
            dye = spec.substring(hash);
            spec = spec.substring(0, hash);
        }
        String[] p = spec.trim().split(":");
        int amount = 1;
        ItemStack it;
        if (p[0].equalsIgnoreCase("item")) {
            if (p.length < 2) return null;
            it = get(p[1]);
            if (p.length > 2) amount = Integer.parseInt(p[2]);
        } else {
            Material m = Material.matchMaterial(p[0]);
            if (m == null || !m.isItem()) return null;
            it = new ItemStack(m);
            if (p.length > 1) amount = Integer.parseInt(p[1]);
        }
        if (it == null) return null;
        it.setAmount(Math.max(1, amount));
        if (dye != null && it.getItemMeta() instanceof LeatherArmorMeta leather) {
            leather.setColor(Color.fromRGB(Text.color(dye, NamedTextColor.WHITE).value()));
            it.setItemMeta(leather);
        }
        return it;
    }

    private ItemStack build(String id, ConfigurationSection s) {
        Material mat = Material.matchMaterial(s.getString("material", "STONE"));
        if (mat == null) throw new IllegalArgumentException("нет материала " + s.getString("material"));
        ItemStack it = new ItemStack(mat);
        ItemMeta meta = it.getItemMeta();

        if (s.contains("name")) meta.displayName(Text.mm(s.getString("name")));
        if (s.getInt("model", 0) > 0) meta.setCustomModelData(s.getInt("model"));
        if (meta instanceof LeatherArmorMeta leather && s.contains("color")) {
            TextColor c = Text.color(s.getString("color"), NamedTextColor.WHITE);
            leather.setColor(Color.fromRGB(c.value()));
        }
        // голова с текстурой: head - Value с minecraft-heads.com (base64), ссылка textures.minecraft.net или её хеш
        if (meta instanceof org.bukkit.inventory.meta.SkullMeta skull && s.contains("head")) {
            String v = s.getString("head", "").trim();
            if (!v.isEmpty()) {
                if (!v.startsWith("ey")) {
                    String url = v.startsWith("http") ? v : "http://textures.minecraft.net/texture/" + v;
                    v = java.util.Base64.getEncoder().encodeToString(
                            ("{\"textures\":{\"SKIN\":{\"url\":\"" + url + "\"}}}").getBytes(java.nio.charset.StandardCharsets.UTF_8));
                }
                var profile = org.bukkit.Bukkit.createProfile(java.util.UUID.nameUUIDFromBytes(v.getBytes()), "mi_head");
                profile.setProperty(new com.destroystokyo.paper.profile.ProfileProperty("textures", v));
                skull.setPlayerProfile(profile);
            }
        }
        // фейерверк: power - длительность полёта (Flight Duration)
        if (meta instanceof org.bukkit.inventory.meta.FireworkMeta fw && s.contains("power")) {
            fw.setPower(Math.max(0, Math.min(127, s.getInt("power"))));
        }

        List<Component> lore = new ArrayList<>();

        // зачарования: "protection:6" или "sharpness:9:#FFFFFF" (цвет уровня вручную)
        for (String e : s.getStringList("enchants")) {
            String[] p = e.split(":");
            Enchantment ench = Registry.ENCHANTMENT.get(NamespacedKey.minecraft(p[0].toLowerCase(Locale.ROOT)));
            if (ench == null) {
                log.warning(id + ": нет зачарования " + p[0]);
                continue;
            }
            int level = p.length > 1 ? Integer.parseInt(p[1]) : 1;
            meta.addEnchant(ench, Math.min(255, level), true);
            lore.add(enchantLine(p[0].toLowerCase(Locale.ROOT), level, p.length > 2 ? p[2] : null));
        }

        String slot = s.getString("slot", autoSlot(mat));
        boolean vanilla = "vanilla".equalsIgnoreCase(s.getString("lore-mode", "custom"));
        List<String> attrs = s.getStringList("attributes");
        if (!attrs.isEmpty()) {
            List<Component> attrLines = new ArrayList<>();
            for (String a : attrs) {
                String[] p = a.split(":");
                String key = p[0].toLowerCase(Locale.ROOT);
                double amount = Double.parseDouble(p[1]);
                Attribute attr = attribute(key);
                if (attr == null) {
                    log.warning(id + ": нет атрибута " + key);
                    continue;
                }
                meta.addAttributeModifier(attr, new AttributeModifier(
                        new NamespacedKey("mediaitems", id.toLowerCase(Locale.ROOT) + "_" + key),
                        amount, AttributeModifier.Operation.ADD_NUMBER, slotGroup(slot)));
                attrLines.add(attributeLine(key, amount));
            }
            if (!vanilla && !attrLines.isEmpty()) {
                if (!lore.isEmpty()) lore.add(Component.empty());
                lore.add(Text.plain(s.getString("slot-header", tooltip.getString("slots." + slot, "")), color("header-color", "#AAAAAA")));
                lore.addAll(attrLines);
            }
        }

        List<String> extra = s.getStringList("lore");
        for (String line : extra) lore.add(Text.mm(line));
        if (!lore.isEmpty()) meta.lore(lore);

        if (s.getBoolean("unbreakable", false)) meta.setUnbreakable(true);
        meta.addItemFlags(ItemFlag.HIDE_ENCHANTS, ItemFlag.HIDE_DYE, ItemFlag.HIDE_ARMOR_TRIM, ItemFlag.HIDE_ADDITIONAL_TOOLTIP);
        // show-unbreakable: true - строка "Unbreakable" как у кожаного сета [II] на скринах
        if (!s.getBoolean("show-unbreakable", false)) meta.addItemFlags(ItemFlag.HIDE_UNBREAKABLE);
        // hide-tooltip: true - без подсказки вообще (стекло-разделитель в окне обмена)
        if (s.getBoolean("hide-tooltip", false)) meta.setHideTooltip(true);
        if (!vanilla && !attrs.isEmpty()) meta.addItemFlags(ItemFlag.HIDE_ATTRIBUTES);
        if (s.contains("glint")) meta.setEnchantmentGlintOverride(s.getBoolean("glint"));

        meta.getPersistentDataContainer().set(idKey, PersistentDataType.STRING, id.toLowerCase(Locale.ROOT));
        it.setItemMeta(meta);
        return it;
    }

    /** [иконка] Название УРОВЕНЬ */
    private Component enchantLine(String key, int level, String levelColor) {
        ConfigurationSection st = tooltip.getConfigurationSection("enchants." + key);
        TextColor bracket = color("bracket-color", "#AAAAAA");
        String name = st != null ? st.getString("name", key) : key;
        TextColor iconColor = Text.color(st != null ? st.getString("icon-color") : null, NamedTextColor.GRAY);
        List<String> g = st != null ? st.getStringList("gradient") : List.of();
        TextColor from = Text.color(g.isEmpty() ? null : g.get(0), NamedTextColor.GRAY);
        TextColor to = Text.color(g.size() > 1 ? g.get(1) : null, from);

        String roman = Text.roman(level);
        Component lvl;
        if (levelColor != null) {
            lvl = Text.plain(roman, Text.color(levelColor, NamedTextColor.WHITE));
        } else if (roman.length() == 1) {
            lvl = Text.plain(roman, color("single-level-color", "#FFFFFF"));
        } else {
            List<String> lg = tooltip.getStringList("level-gradient");
            lvl = Text.gradient(roman, Text.color(lg.isEmpty() ? null : lg.get(0), NamedTextColor.GRAY),
                    Text.color(lg.size() > 1 ? lg.get(1) : null, NamedTextColor.DARK_GRAY), 0);
        }
        return Component.text("").decoration(TextDecoration.ITALIC, false)
                .append(Text.plain("[", bracket))
                .append(Text.plain(st != null ? st.getString("icon", "✦") : "✦", iconColor))
                .append(Text.plain("]", bracket))
                .append(Component.text(" "))
                .append(Text.gradient(name, from, to, st != null ? st.getInt("gradient-letters", 0) : 0))
                .append(Component.text(" "))
                .append(lvl);
    }

    /** +N [иконка] Название */
    private Component attributeLine(String key, double amount) {
        ConfigurationSection st = tooltip.getConfigurationSection("attributes." + key);
        String name = st != null ? st.getString("name", key) : key;
        return Component.text("").decoration(TextDecoration.ITALIC, false)
                .append(Text.plain(amount >= 0 ? "+" : "-", color("plus-color", "#FFFF55")))
                .append(Text.plain(Text.num(Math.abs(amount)), color("number-color", "#FFFFFF")))
                .append(Component.text(" "))
                .append(Text.plain(st != null ? st.getString("icon", "✦") : "✦",
                        Text.color(st != null ? st.getString("color") : null, NamedTextColor.GRAY)))
                .append(Component.text(" "))
                .append(Text.fade(name, tooltip.getInt("attribute-fade.start", 255),
                        tooltip.getInt("attribute-fade.step", 10), tooltip.getInt("attribute-fade.min", 64)));
    }

    private TextColor color(String path, String def) {
        return Text.color(tooltip.getString(path, def), TextColor.fromHexString(def));
    }

    private static String autoSlot(Material m) {
        String n = m.name();
        if (n.endsWith("_HELMET") || n.endsWith("_HEAD") || n.endsWith("_SKULL")) return "head";
        if (n.endsWith("_CHESTPLATE") || n.equals("ELYTRA")) return "chest";
        if (n.endsWith("_LEGGINGS")) return "legs";
        if (n.endsWith("_BOOTS")) return "feet";
        return "hand";
    }

    private static EquipmentSlotGroup slotGroup(String slot) {
        return switch (slot) {
            case "head" -> EquipmentSlotGroup.HEAD;
            case "chest" -> EquipmentSlotGroup.CHEST;
            case "legs" -> EquipmentSlotGroup.LEGS;
            case "feet" -> EquipmentSlotGroup.FEET;
            case "offhand" -> EquipmentSlotGroup.OFFHAND;
            case "any" -> EquipmentSlotGroup.ANY;
            default -> EquipmentSlotGroup.MAINHAND;
        };
    }

    /** Ключ атрибута без привязки к версии (в 1.21.1 "generic.armor", в новых "armor"). */
    private static Attribute attribute(String key) {
        Attribute a = Registry.ATTRIBUTE.get(NamespacedKey.minecraft("generic." + key));
        return a != null ? a : Registry.ATTRIBUTE.get(NamespacedKey.minecraft(key));
    }
}
