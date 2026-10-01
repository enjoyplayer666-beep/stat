package ru.dscraft.mediatops;

import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.Statistic;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;

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
 * Счётчики для топов из статистики Minecraft (убийства игроков, наигранное время):
 * "за всё время" - сама статистика, "за день" - сколько прибавилось с начала суток.
 * Рейтинг кланов на начало дня - чтобы посчитать, сколько клан заработал сегодня.
 */
public final class Stats {

    public static final class Entry {
        volatile String name;
        volatile long lastSeen;
        /** Всего за всё время (из статистики, обновляется, пока игрок в сети). */
        volatile long kills, minutes;
        /** Значения на начало дня baseDay. */
        volatile long killsBase, minutesBase;
        volatile String baseDay;

        Entry(String name) {
            this.name = name;
        }
    }

    public record Row(String name, long value) {
    }

    private final Plugin plugin;
    private final File file;
    private final Map<UUID, Entry> players = new ConcurrentHashMap<>();
    /** ID клана -> рейтинг на начало дня (или на момент, когда клан впервые попался сегодня). */
    private final Map<String, Integer> clanBaseline = new ConcurrentHashMap<>();
    private volatile String clanDay;
    private volatile boolean dirty;

    public Stats(Plugin plugin) {
        this.plugin = plugin;
        this.file = new File(plugin.getDataFolder(), "data.yml");
    }

    public String today() {
        String zone = plugin.getConfig().getString("timezone", "Europe/Moscow");
        try {
            return LocalDate.now(ZoneId.of(zone)).toString();
        } catch (Exception e) {
            return LocalDate.now().toString();
        }
    }

    private Entry entry(UUID uuid, String name) {
        Entry e = players.computeIfAbsent(uuid, u -> new Entry(name));
        if (name != null && !name.equals(e.name)) e.name = name;
        return e;
    }

    /** Обновить из живой статистики игрока в сети; в новый день - запомнить начало дня. */
    public void update(Player p) {
        Entry e = entry(p.getUniqueId(), p.getName());
        e.kills = p.getStatistic(Statistic.PLAYER_KILLS);
        e.minutes = p.getStatistic(Statistic.PLAY_ONE_MINUTE) / 20L / 60L;
        e.lastSeen = System.currentTimeMillis();
        String day = today();
        if (!day.equals(e.baseDay)) {
            e.baseDay = day;
            e.killsBase = e.kills;
            e.minutesBase = e.minutes;
        }
        dirty = true;
    }

    public long killsDay(Entry e) {
        return today().equals(e.baseDay) ? Math.max(0, e.kills - e.killsBase) : 0;
    }

    public long hoursDay(Entry e) {
        return today().equals(e.baseDay) ? Math.max(0, e.minutes - e.minutesBase) / 60 : 0;
    }

    /** Сколько клан заработал сегодня: текущий рейтинг минус рейтинг на начало дня. */
    public int clanToday(String clanId, int rating) {
        String day = today();
        if (!day.equals(clanDay)) {
            clanDay = day;
            clanBaseline.clear();
            dirty = true;
        }
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

    /**
     * Топ всегда полный: по значению, при равенстве - кто заходил позже.
     * Так "за день" список заполнен никами даже с нулями.
     */
    public List<Row> top(ToLongFunction<Entry> value, int size) {
        List<Entry> all = new ArrayList<>(players.values());
        all.sort(Comparator.comparingLong((Entry e) -> -value.applyAsLong(e))
                .thenComparingLong(e -> -e.lastSeen));
        List<Row> rows = new ArrayList<>();
        for (int i = 0; i < all.size() && rows.size() < size; i++) {
            Entry e = all.get(i);
            if (e.name == null) continue;
            rows.add(new Row(e.name, value.applyAsLong(e)));
        }
        return rows;
    }

    // ---------------- хранение ----------------

    public void load() {
        players.clear();
        clanBaseline.clear();
        YamlConfiguration yml = YamlConfiguration.loadConfiguration(file);
        clanDay = yml.getString("clans-day");
        ConfigurationSection ps = yml.getConfigurationSection("players");
        if (ps != null && yml.getInt("version") >= 2) {
            for (String key : ps.getKeys(false)) {
                try {
                    ConfigurationSection s = ps.getConfigurationSection(key);
                    if (s == null) continue;
                    Entry e = new Entry(s.getString("name"));
                    e.lastSeen = s.getLong("last-seen");
                    e.kills = s.getLong("kills");
                    e.minutes = s.getLong("minutes");
                    e.killsBase = s.getLong("kills-base");
                    e.minutesBase = s.getLong("minutes-base");
                    e.baseDay = s.getString("base-day");
                    players.put(UUID.fromString(key), e);
                } catch (IllegalArgumentException ignored) {
                }
            }
        }
        ConfigurationSection cs = yml.getConfigurationSection("clans-day-start");
        if (cs != null) for (String k : cs.getKeys(false)) clanBaseline.put(k, cs.getInt(k));
        if (yml.getInt("version") < 2) importAll();
    }

    public void saveIfDirty() {
        if (dirty) save();
    }

    public void save() {
        YamlConfiguration yml = new YamlConfiguration();
        yml.set("version", 2);
        yml.set("clans-day", clanDay);
        for (Map.Entry<UUID, Entry> me : players.entrySet()) {
            String p = "players." + me.getKey() + ".";
            Entry e = me.getValue();
            yml.set(p + "name", e.name);
            yml.set(p + "last-seen", e.lastSeen);
            yml.set(p + "kills", e.kills);
            yml.set(p + "minutes", e.minutes);
            yml.set(p + "kills-base", e.killsBase);
            yml.set(p + "minutes-base", e.minutesBase);
            yml.set(p + "base-day", e.baseDay);
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

    /** Один раз: статистика всех, кто когда-либо заходил на сервер (убийства игроков и время). */
    private void importAll() {
        int count = 0;
        for (OfflinePlayer op : Bukkit.getOfflinePlayers()) {
            if (op.getName() == null) continue;
            try {
                Entry e = entry(op.getUniqueId(), op.getName());
                e.kills = op.getStatistic(Statistic.PLAYER_KILLS);
                e.minutes = op.getStatistic(Statistic.PLAY_ONE_MINUTE) / 20L / 60L;
                e.lastSeen = Math.max(op.getLastSeen(), op.getLastLogin());
                count++;
            } catch (Exception ignored) {
            }
        }
        plugin.getLogger().info("Загружена статистика " + count + " игроков.");
        dirty = true;
        save();
    }
}
