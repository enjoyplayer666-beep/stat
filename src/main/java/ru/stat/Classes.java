package ru.stat;

import org.bukkit.configuration.file.FileConfiguration;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

/** Знаки классности (по боевому рейтингу) из config.yml. */
public class Classes {

    private record ClassMark(int rating, String display) {
    }

    private final List<ClassMark> classes = new ArrayList<>();

    public void load(FileConfiguration cfg) {
        classes.clear();
        for (Map<?, ?> m : cfg.getMapList("classes")) {
            classes.add(new ClassMark(num(m, "rating"), str(m, "display", "")));
        }
        classes.sort(Comparator.comparingInt(ClassMark::rating));
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
