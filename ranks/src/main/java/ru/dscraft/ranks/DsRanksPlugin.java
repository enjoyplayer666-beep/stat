package ru.dscraft.ranks;

import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.OfflinePlayer;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class DsRanksPlugin extends JavaPlugin implements Listener {

    private static final Pattern HEX = Pattern.compile("&#([A-Fa-f0-9]{6})");

    private final Ranks ranks = new Ranks();
    /** Готовый ранг для чата у игроков в сети (для асинхронного чата DestroyChat). */
    private final Map<UUID, String> chatRanks = new ConcurrentHashMap<>();
    private File dataFile;
    private FileConfiguration data;
    private boolean dirty;

    @Override
    public void onEnable() {
        boolean freshConfig = !new File(getDataFolder(), "config.yml").exists();
        saveDefaultConfig();
        loadData();
        importFromStatPlugin(freshConfig);
        ranks.load(getConfig());
        RanksApi.init(this);

        RankCommand rankCommand = new RankCommand(this);
        getCommand("rank").setExecutor(rankCommand);
        getCommand("rank").setTabCompleter(rankCommand);
        BoosterCommand boosterCommand = new BoosterCommand(this);
        getCommand("booster").setExecutor(boosterCommand);
        getCommand("booster").setTabCompleter(boosterCommand);
        getServer().getPluginManager().registerEvents(this, this);
        getServer().getPluginManager().registerEvents(new KillListener(this), this);
        for (Player p : Bukkit.getOnlinePlayers()) touch(p);

        // данные сохраняются раз в минуту, если что-то поменялось
        getServer().getScheduler().runTaskTimer(this, () -> {
            if (dirty) saveData();
        }, 1200L, 1200L);
    }

    @Override
    public void onDisable() {
        saveData();
        chatRanks.clear();
        RanksApi.init(null);
    }

    void reload() {
        saveData();
        reloadConfig();
        ranks.load(getConfig());
        for (Player p : Bukkit.getOnlinePlayers()) refreshChatRank(p);
    }

    // ---------------- перенос из StatPlugin ----------------

    /**
     * Один раз: ранги, умения, бустер и сообщения из plugins/StatPlugin/config.yml (если наш конфиг только создан),
     * убийства, бустеры и /rank off игроков из plugins/StatPlugin/data.yml.
     */
    private void importFromStatPlugin(boolean freshConfig) {
        if (data.getBoolean("imported-from-statplugin")) return;
        File statFolder = new File(getDataFolder().getParentFile(), "StatPlugin");

        File statConfig = new File(statFolder, "config.yml");
        if (freshConfig && statConfig.exists()) {
            YamlConfiguration old = YamlConfiguration.loadConfiguration(statConfig);
            FileConfiguration cfg = getConfig();
            if (!old.getMapList("ranks").isEmpty()) cfg.set("ranks", old.getMapList("ranks"));
            for (String section : new String[]{"skills", "booster"}) {
                ConfigurationSection s = old.getConfigurationSection(section);
                if (s == null) continue;
                for (String k : s.getKeys(false)) cfg.set(section + "." + k, s.get(k));
            }
            if (old.contains("combat.anti-farm-seconds")) cfg.set("anti-farm-seconds", old.get("combat.anti-farm-seconds"));
            if (old.contains("chat-rank")) cfg.set("chat-rank", old.getString("chat-rank"));
            for (String m : new String[]{"not-found", "no-permission", "booster", "rank-up", "rank-on", "rank-off", "rank-usage"}) {
                if (old.contains("messages." + m)) cfg.set("messages." + m, old.get("messages." + m));
            }
            saveConfig();
            getLogger().info("Ранги, умения и бустер перенесены из StatPlugin/config.yml.");
        }

        File statData = new File(statFolder, "data.yml");
        int moved = 0;
        if (statData.exists()) {
            ConfigurationSection players = YamlConfiguration.loadConfiguration(statData).getConfigurationSection("players");
            if (players != null) {
                for (String key : players.getKeys(false)) {
                    ConfigurationSection p = players.getConfigurationSection(key);
                    if (p == null) continue;
                    String base = "players." + key + ".";
                    for (String field : new String[]{"name", "uuid", "kills", "booster", "rank-hidden"}) {
                        if (p.contains(field) && !data.contains(base + field)) data.set(base + field, p.get(field));
                    }
                    moved++;
                }
            }
        }
        data.set("imported-from-statplugin", true);
        saveData();
        if (moved > 0) getLogger().info("Перенесены ранги " + moved + " игроков из StatPlugin/data.yml.");
    }

    // ---------------- данные ----------------

    private void loadData() {
        dataFile = new File(getDataFolder(), "data.yml");
        data = YamlConfiguration.loadConfiguration(dataFile);
    }

    void saveData() {
        dirty = false;
        try {
            data.save(dataFile);
        } catch (IOException e) {
            dirty = true;
            getLogger().warning("Не удалось сохранить data.yml: " + e.getMessage());
        }
    }

    static String key(String name) {
        return name.toLowerCase(Locale.ROOT);
    }

    int integer(String name, String field) {
        Object v = data.get("players." + key(name) + "." + field);
        if (v instanceof Number n) return (int) Math.round(n.doubleValue());
        if (v == null) return 0;
        try {
            return (int) Math.round(Double.parseDouble(String.valueOf(v).replaceAll("[^0-9.,-]", "").replace(',', '.')));
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    /** Число (время в мс и т.п.) из data.yml, 0 - нет. */
    double number(String name, String field) {
        Object v = data.get("players." + key(name) + "." + field);
        return v instanceof Number n ? n.doubleValue() : 0;
    }

    void set(String name, String field, Object value) {
        data.set("players." + key(name) + "." + field, value);
        dirty = true;
    }

    String string(String name, String field) {
        return data.getString("players." + key(name) + "." + field);
    }

    boolean known(String name) {
        return data.contains("players." + key(name));
    }

    /** Сброс ранга, бустера и /rank off; ник и UUID остаются. */
    void resetPlayer(String name) {
        String shown = string(name, "name");
        String uuid = string(name, "uuid");
        data.set("players." + key(name), null);
        if (shown != null) set(name, "name", shown);
        if (uuid != null) set(name, "uuid", uuid);
        dirty = true;
    }

    /** Все игроки, которые заходили или были перенесены: ники. */
    List<String> allNames() {
        List<String> out = new ArrayList<>();
        ConfigurationSection s = data.getConfigurationSection("players");
        if (s == null) return out;
        for (String k : s.getKeys(false)) {
            String n = s.getString(k + ".name");
            out.add(n != null ? n : k);
        }
        return out;
    }

    OfflinePlayer findPlayer(String name) {
        Player online = Bukkit.getPlayerExact(name);
        if (online != null) return online;
        OfflinePlayer op = Bukkit.getOfflinePlayerIfCached(name);
        if (op == null && known(name)) {
            String uuid = string(name, "uuid");
            op = uuid != null ? Bukkit.getOfflinePlayer(UUID.fromString(uuid)) : Bukkit.getOfflinePlayer(name);
        }
        return op;
    }

    private void touch(Player player) {
        set(player.getName(), "name", player.getName());
        set(player.getName(), "uuid", player.getUniqueId().toString());
        // запоминаем, есть ли VIP - для /stat и /rank give, когда игрок не в сети
        set(player.getName(), "ranked", canUseRanks(player) ? true : null);
        refreshChatRank(player);
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        touch(event.getPlayer());
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        set(event.getPlayer().getName(), "ranked", canUseRanks(event.getPlayer()) ? true : null);
        chatRanks.remove(event.getPlayer().getUniqueId());
    }

    // ---------------- ранги / бустер / чат ----------------

    Ranks ranks() {
        return ranks;
    }

    Ranks.Rank rank(String name) {
        return ranks.rankFor(integer(name, "kills"));
    }

    /** Бустер игрока: наибольшее из выданного вручную и права stat.booster.N. */
    int booster(String name) {
        int max = Math.max(1, getConfig().getInt("booster.max", 15));
        int b = Math.max(1, integer(name, "booster"));
        Player online = Bukkit.getPlayerExact(name);
        if (online != null) {
            for (int i = max; i > b; i--) {
                if (online.hasPermission("stat.booster." + i)) {
                    b = i;
                    break;
                }
            }
        }
        return Math.min(b, max);
    }

    /** Ранги работают только с правом use-permission (ranks.use - с VIP); "" - у всех. */
    boolean canUseRanks(Player player) {
        String perm = getConfig().getString("use-permission", "ranks.use");
        return perm == null || perm.isBlank() || player.hasPermission(perm);
    }

    String chatRank(UUID uuid) {
        return chatRanks.get(uuid);
    }

    void refreshChatRank(Player player) {
        if (data.getBoolean("players." + key(player.getName()) + ".rank-hidden")) {
            chatRanks.remove(player.getUniqueId());
            return;
        }
        String text = getConfig().getString("chat-rank", "{rank} ").replace("{rank}", rank(player.getName()).display());
        chatRanks.put(player.getUniqueId(), color(text));
    }

    void setRankHidden(Player player, boolean hidden) {
        set(player.getName(), "rank-hidden", hidden ? true : null);
        refreshChatRank(player);
    }

    // ---------------- оформление ----------------

    String color(String s) {
        Matcher m = HEX.matcher(s);
        StringBuilder sb = new StringBuilder();
        while (m.find()) {
            StringBuilder rep = new StringBuilder("§x");
            for (char ch : m.group(1).toCharArray()) rep.append('§').append(ch);
            m.appendReplacement(sb, Matcher.quoteReplacement(rep.toString()));
        }
        m.appendTail(sb);
        return ChatColor.translateAlternateColorCodes('&', sb.toString());
    }

    String msg(String key) {
        String def = switch (key) {
            case "no-ranks" -> "&#E53232◆ &#C7C4B7Вас нет в базе данных!";
            case "give-no-vip" -> "&#E53232◆ &#C7C4B7У игрока &f{player} &#C7C4B7нет привилегии VIP - киллы выдать нельзя.";
            default -> key;
        };
        return getConfig().getString("messages." + key, def);
    }

    /** Есть ли ранги у игрока по нику: в сети - по праву, не в сети - как было при последнем входе/выходе. */
    boolean hasRanks(String name) {
        Player online = Bukkit.getPlayerExact(name);
        if (online != null) return canUseRanks(online);
        return data.getBoolean("players." + key(name) + ".ranked");
    }

    /** Склонение: 1 убийство, 2 убийства, 5 убийств. */
    static String plural(long n, String one, String few, String many) {
        long m100 = n % 100, m10 = n % 10;
        if (m100 >= 11 && m100 <= 14) return many;
        if (m10 == 1) return one;
        if (m10 >= 2 && m10 <= 4) return few;
        return many;
    }

    static void addPlayers(List<String> res) {
        for (Player p : Bukkit.getOnlinePlayers()) res.add(p.getName());
    }
}
