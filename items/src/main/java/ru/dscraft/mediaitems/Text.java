package ru.dscraft.mediaitems;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.TextComponent;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextColor;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.text.minimessage.MiniMessage;

/** Цвета и градиенты для названий и описаний предметов. */
final class Text {

    static final MiniMessage MM = MiniMessage.miniMessage();

    private Text() {
    }

    /** MiniMessage без курсива (в описании предмета он включён по умолчанию). */
    static Component mm(String s) {
        return MM.deserialize(s == null ? "" : s).decorationIfAbsent(TextDecoration.ITALIC, TextDecoration.State.FALSE);
    }

    static TextColor color(String s, TextColor def) {
        if (s == null || s.isEmpty()) return def;
        TextColor c = s.startsWith("#") ? TextColor.fromHexString(s) : NamedTextColor.NAMES.value(s.toLowerCase());
        return c != null ? c : def;
    }

    static Component plain(String s, TextColor c) {
        return Component.text(s, c).decoration(TextDecoration.ITALIC, false);
    }

    /**
     * Градиент по буквам (пробелы шаг не занимают, как на скринах).
     * span > 0 - градиент растягивается только на первые span букв, дальше цвет конца.
     */
    static Component gradient(String text, TextColor from, TextColor to, int span) {
        int letters = (int) text.codePoints().filter(cp -> !Character.isWhitespace(cp)).count();
        int n = span > 0 ? Math.min(span, letters) : letters;
        TextComponent.Builder b = Component.text().decoration(TextDecoration.ITALIC, false);
        int i = 0;
        for (int off = 0; off < text.length(); ) {
            int cp = text.codePointAt(off);
            String ch = new String(Character.toChars(cp));
            off += ch.length();
            if (Character.isWhitespace(cp)) {
                b.append(Component.text(ch));
                continue;
            }
            float t = n <= 1 ? 0f : Math.min(1f, i / (float) (n - 1));
            b.append(Component.text(ch, lerp(from, to, t)));
            i++;
        }
        return b.build();
    }

    /** Затухание белого: каждая буква темнее предыдущей на step (как подписи атрибутов). */
    static Component fade(String text, int start, int step, int min) {
        TextComponent.Builder b = Component.text().decoration(TextDecoration.ITALIC, false);
        int i = 0;
        for (int off = 0; off < text.length(); ) {
            int cp = text.codePointAt(off);
            String ch = new String(Character.toChars(cp));
            off += ch.length();
            if (Character.isWhitespace(cp)) {
                b.append(Component.text(ch));
                continue;
            }
            int v = Math.max(min, start - step * i++);
            b.append(Component.text(ch, TextColor.color(v, v, v)));
        }
        return b.build();
    }

    static TextColor lerp(TextColor a, TextColor b, float t) {
        return TextColor.color(
                Math.round(a.red() + (b.red() - a.red()) * t),
                Math.round(a.green() + (b.green() - a.green()) * t),
                Math.round(a.blue() + (b.blue() - a.blue()) * t));
    }

    static String roman(int n) {
        if (n <= 0 || n > 3999) return String.valueOf(n);
        int[] v = {1000, 900, 500, 400, 100, 90, 50, 40, 10, 9, 5, 4, 1};
        String[] s = {"M", "CM", "D", "CD", "C", "XC", "L", "XL", "X", "IX", "V", "IV", "I"};
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < v.length; i++) {
            while (n >= v[i]) {
                n -= v[i];
                sb.append(s[i]);
            }
        }
        return sb.toString();
    }

    /** 3 -> "3", 2.5 -> "2.5". */
    static String num(double d) {
        return d == Math.rint(d) ? String.valueOf((long) d) : String.valueOf(d);
    }
}
