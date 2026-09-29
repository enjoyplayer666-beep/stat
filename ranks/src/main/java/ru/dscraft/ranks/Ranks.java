package ru.dscraft.ranks;

import org.bukkit.configuration.file.FileConfiguration;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

/** Ранги (по убийствам) из config.yml. */
public class Ranks {

    /**
     * @param color  первый цвет градиента (#RRGGBB или &-код), им же красится иконка
     * @param color2 второй цвет градиента, null - без градиента
     */
    public record Rank(int index, String name, String icon, String color, String color2, int kills,
                       int attack, int defense) {
        /** Иконка и название с градиентом, в &#RRGGBB-кодах. */
        public String display() {
            return code(color) + icon + " " + gradient(name, color, color2);
        }
    }

    private final List<Rank> ranks = new ArrayList<>();

    public void load(FileConfiguration cfg) {
        ranks.clear();
        List<Rank> tmp = new ArrayList<>();
        for (Map<?, ?> m : cfg.getMapList("ranks")) {
            tmp.add(new Rank(0, str(m, "name", "?"), str(m, "icon", ""), str(m, "color", "&f"),
                    m.get("color2") == null ? null : String.valueOf(m.get("color2")),
                    num(m, "kills"), num(m, "attack"), num(m, "defense")));
        }
        tmp.sort(Comparator.comparingInt(Rank::kills));
        for (int i = 0; i < tmp.size(); i++) {
            Rank r = tmp.get(i);
            ranks.add(new Rank(i, r.name(), r.icon(), r.color(), r.color2(), r.kills(), r.attack(), r.defense()));
        }
        if (ranks.isEmpty()) ranks.add(new Rank(0, "Неофит", "☘", "&7", null, 0, 0, 0));
    }

    public List<Rank> all() {
        return ranks;
    }

    public Rank rankFor(int kills) {
        Rank result = ranks.get(0);
        for (Rank r : ranks) {
            if (kills >= r.kills()) result = r;
        }
        return result;
    }

    /** Следующий ранг или null, если ранг максимальный. */
    public Rank next(Rank rank) {
        return rank.index() + 1 < ranks.size() ? ranks.get(rank.index() + 1) : null;
    }

    /** "#RRGGBB" -> "&#RRGGBB", "&c" остаётся как есть. */
    static String code(String color) {
        return color.startsWith("#") ? "&" + color : color;
    }

    /** Текст с плавным переходом цвета по буквам. Без второго цвета - просто первым цветом. */
    static String gradient(String text, String from, String to) {
        int[] a = rgb(from);
        int[] b = rgb(to);
        if (a == null || b == null || text.length() < 2) return code(from) + text;
        StringBuilder sb = new StringBuilder();
        int n = text.length() - 1;
        for (int i = 0; i < text.length(); i++) {
            char ch = text.charAt(i);
            if (ch == ' ') {
                sb.append(ch);
                continue;
            }
            int r = Math.round(a[0] + (b[0] - a[0]) * (float) i / n);
            int g = Math.round(a[1] + (b[1] - a[1]) * (float) i / n);
            int bl = Math.round(a[2] + (b[2] - a[2]) * (float) i / n);
            sb.append(String.format("&#%02X%02X%02X", r, g, bl)).append(ch);
        }
        return sb.toString();
    }

    private static int[] rgb(String color) {
        if (color == null || !color.matches("#[0-9a-fA-F]{6}")) return null;
        int v = Integer.parseInt(color.substring(1), 16);
        return new int[]{(v >> 16) & 0xFF, (v >> 8) & 0xFF, v & 0xFF};
    }

    private static String str(Map<?, ?> m, String key, String def) {
        Object v = m.get(key);
        return v == null ? def : String.valueOf(v);
    }

    private static int num(Map<?, ?> m, String key) {
        Object v = m.get(key);
        if (v instanceof Number n) return n.intValue();
        try {
            return v == null ? 0 : Integer.parseInt(String.valueOf(v).trim());
        } catch (NumberFormatException e) {
            return 0;
        }
    }
}
