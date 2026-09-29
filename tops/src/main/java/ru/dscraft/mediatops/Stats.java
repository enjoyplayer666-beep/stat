package ru.dscraft.mediatops;

import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.Statistic;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.io.IOException;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.ToLongFunction;

/**
 * Счётчики для топов: убийства и наигранные минуты - за сегодня и за вайп,
 * рейтинг кланов на начало дня (чтобы посчитать, сколько клан заработал сегодня).
 */
public final class Stats {

    public static final class Entry {
        volatile String name;
        volatile long killsDay, killsWipe, minutesDay, minutesWipe;

        Entry(String name) {
            this.name = name;
        }
    }

    public record Row(String name, long value) {
    }

    private final JavaPlugin plugin;
    private final File file;
    private final Map<UUID, Entry> players = new ConcurrentHashMap<>();
    /** ID клана -> рейтинг на начало дня (или на момент, когда клан впервые попался сегодня). */
    private final Map<String, Integer> clanBaseline = new ConcurrentHashMap<>();
    private volatile String day;
    private volatile boolean dirty;

    public Stats(JavaPlugin plugin) {
        this.plugin = plugin;
        this.file = new File(plugin.getDataFolder(), "data.yml");
    }

    private String today() {
        String zone = plugin.getConfig().getString("timezone", "Europe/Moscow");
        try {
            return LocalDate.now(ZoneId.of(zone)).toString();
        } catch (Exception e) {
            return LocalDate.now().toString();
        }
    }

    /** Наступил новый день - обнулить дневные счётчики. */
    public boolean checkDay() {
        String now = today();
        if (now.equals(day)) return false;
        day = now;
        for (Entry e : players.values()) {
            e.killsDay = 0;
            e.minutesDay = 0;
        }
        clanBaseline.clear();
        dirty = true;
        return true;
    }

    public Entry entry(UUID uuid, String name) {
        Entry e = players.computeIfAbsent(uuid, u -> new Entry(name));
        if (name != null && !name.equals(e.name)) {
            e.name = name;
            dirty = true;
        }
        return e;
    }

    public void addKill(UUID uuid, String name) {
        checkDay();
        Entry e = entry(uuid, name);
        e.killsDay++;
        e.killsWipe++;
        dirty = true;
    }

    public void addMinute(UUID uuid, String name) {
        checkDay();
        Entry e = entry(uuid, name);
        e.minutesDay++;
        e.minutesWipe++;
        dirty = true;
    }

    /** Сколько клан заработал сегодня: текущий рейтинг минус рейтинг на начало дня. */
    public int clanToday(String clanId, int rating) {
        checkDay();
        Integer base = clanBaseline.putIfAbsent(clanId, rating);
        if (base == null) {
            dirty = true;
            return 0;
        }
        if (rating < base) {
            clanBaseline.put(clanId, rating);
            return 0;
        }
        return rating - base;
    }

    public List<Row> top(ToLongFunction<Entry> value, int size) {
        List<Row> rows = new ArrayList<>();
        for (Entry e : players.values()) {
            long v = value.applyAsLong(e);
            if (v > 0) rows.add(new Row(e.name == null ? "?" : e.name, v));
        }
        rows.sort(Comparator.comparingLong((Row r) -> -r.value()).thenComparing(Row::name, String.CASE_INSENSITIVE_ORDER));
        return rows.size() > size ? rows.subList(0, size) : rows;
    }

    public void resetWipe() {
        for (Entry e : players.values()) {
            e.killsDay = e.killsWipe = e.minutesDay = e.minutesWipe = 0;
        }
        clanBaseline.clear();
        dirty = true;
        save();
    }

    // ---------------- хранение ----------------

    public void load() {
        players.clear();
        clanBaseline.clear();
        YamlConfiguration yml = YamlConfiguration.loadConfiguration(file);
        day = yml.getString("day");
        ConfigurationSection ps = yml.getConfigurationSection("players");
        if (ps != null) {
            for (String key : ps.getKeys(false)) {
                try {
                    ConfigurationSection s = ps.getConfigurationSection(key);
                    if (s == null) continue;
                    Entry e = new Entry(s.getString("name", "?"));
                    e.killsDay = s.getLong("kills-day");
                    e.killsWipe = s.getLong("kills-wipe");
                    e.minutesDay = s.getLong("minutes-day");
                    e.minutesWipe = s.getLong("minutes-wipe");
                    players.put(UUID.fromString(key), e);
                } catch (IllegalArgumentException ignored) {
                }
            }
        }
        ConfigurationSection cs = yml.getConfigurationSection("clans-day-start");
        if (cs != null) for (String k : cs.getKeys(false)) clanBaseline.put(k, cs.getInt(k));
        if (!yml.getBoolean("imported")) importOld();
        checkDay();
    }

    public void saveIfDirty() {
        if (dirty) save();
    }

    public void save() {
        YamlConfiguration yml = new YamlConfiguration();
        yml.set("imported", true);
        yml.set("day", day);
        for (Map.Entry<UUID, Entry> me : players.entrySet()) {
            String p = "players." + me.getKey() + ".";
            Entry e = me.getValue();
            yml.set(p + "name", e.name);
            yml.set(p + "kills-day", e.killsDay);
            yml.set(p + "kills-wipe", e.killsWipe);
            yml.set(p + "minutes-day", e.minutesDay);
            yml.set(p + "minutes-wipe", e.minutesWipe);
        }
        for (Map.Entry<String, Integer> c : clanBaseline.entrySet()) yml.set("clans-day-start." + c.getKey(), c.getValue());
        dirty = false;
        try {
            if (!plugin.getDataFolder().exists()) plugin.getDataFolder().mkdirs();
            yml.save(file);
        } catch (IOException e) {
            dirty = true;
            plugin.getLogger().warning("Не удалось сохранить data.yml: " + e.getMessage());
        }
    }

    /**
     * Первый запуск: убийства за вайп - из StatPlugin (real-kills), наигранное время за вайп -
     * из статистики Minecraft у всех игроков, которые заходили.
     */
    private void importOld() {
        int kills = 0;
        File stat = new File(plugin.getDataFolder().getParentFile(), "StatPlugin/data.yml");
        if (stat.exists()) {
            ConfigurationSection ps = YamlConfiguration.loadConfiguration(stat).getConfigurationSection("players");
            if (ps != null) {
                for (String key : ps.getKeys(false)) {
                    ConfigurationSection s = ps.getConfigurationSection(key);
                    if (s == null || !s.contains("uuid")) continue;
                    try {
                        Entry e = entry(UUID.fromString(s.getString("uuid")), s.getString("name", key));
                        e.killsWipe = Math.max(e.killsWipe, s.getLong("real-kills"));
                        kills++;
                    } catch (IllegalArgumentException ignored) {
                    }
                }
            }
        }
        int played = 0;
        for (OfflinePlayer op : Bukkit.getOfflinePlayers()) {
            try {
                long minutes = op.getStatistic(Statistic.PLAY_ONE_MINUTE) / 20L / 60L;
                if (minutes <= 0) continue;
                Entry e = entry(op.getUniqueId(), op.getName());
                e.minutesWipe = Math.max(e.minutesWipe, minutes);
                played++;
            } catch (Exception ignored) {
            }
        }
        plugin.getLogger().info("Перенесено: убийства " + kills + " игроков (StatPlugin), время " + played + " игроков.");
        dirty = true;
        save();
    }
}
