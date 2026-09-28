package ru.stat;

import org.bukkit.configuration.file.FileConfiguration;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

/** Ранги (по убийствам) и знаки классности (по боевому рейтингу) из config.yml. */
public class Ranks {

    public record Rank(int index, String name, String icon, String color, int kills) {
        /** "&#..☠ Лич" - иконка и название цветом ранга. */
        public String display() {
            return color + icon + " " + name;
        }
    }

    private record ClassMark(int rating, String display) {
    }

    private final List<Rank> ranks = new ArrayList<>();
    private final List<ClassMark> classes = new ArrayList<>();

    public void load(FileConfiguration cfg) {
        ranks.clear();
        List<Rank> tmp = new ArrayList<>();
        for (Map<?, ?> m : cfg.getMapList("ranks")) {
            tmp.add(new Rank(0, str(m, "name", "?"), str(m, "icon", ""), str(m, "color", "&f"), num(m, "kills")));
        }
        tmp.sort(Comparator.comparingInt(Rank::kills));
        for (int i = 0; i < tmp.size(); i++) {
            Rank r = tmp.get(i);
            ranks.add(new Rank(i, r.name(), r.icon(), r.color(), r.kills()));
        }
        if (ranks.isEmpty()) ranks.add(new Rank(0, "Неофит", "☘", "&7", 0));

        classes.clear();
        for (Map<?, ?> m : cfg.getMapList("classes")) {
            classes.add(new ClassMark(num(m, "rating"), str(m, "display", "")));
        }
        classes.sort(Comparator.comparingInt(ClassMark::rating));
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

    /** Значение умения для ранга: от 0 на первом ранге до max на последнем. */
    public int skill(Rank rank, int max) {
        if (ranks.size() <= 1) return max;
        return Math.round((float) max * rank.index() / (ranks.size() - 1));
    }

    public String classFor(int rating) {
        String result = classes.isEmpty() ? "&7[Нет отличительных отметок]" : classes.get(0).display();
        for (ClassMark c : classes) {
            if (rating >= c.rating()) result = c.display();
        }
        return result;
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
