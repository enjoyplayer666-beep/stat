package ru.dscraft.mediatops;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.Chunk;
import org.bukkit.Color;
import org.bukkit.Location;
import org.bukkit.NamespacedKey;
import org.bukkit.World;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Display;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Interaction;
import org.bukkit.entity.Player;
import org.bukkit.entity.TextDisplay;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.util.Transformation;
import org.joml.AxisAngle4f;
import org.joml.Vector3f;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Голограммы-топы. На каждую - два текста (за день и за вайп) и невидимая зона клика.
 * Каждый игрок видит только текст своего режима; клик переключает режим сразу на всех топах.
 * Сущности не сохраняются в мире: создаются при запуске, чанк держится загруженным.
 */
public final class Boards {

    public enum Type { KILLS, CLANS, PLAYTIME }

    private static final MiniMessage MM = MiniMessage.miniMessage();

    private final class Board {
        final String id;
        final Type type;
        final Location location;
        TextDisplay day;
        TextDisplay wipe;
        Interaction click;

        Board(String id, Type type, Location location) {
            this.id = id;
            this.type = type;
            this.location = location;
        }
    }

    private final JavaPlugin plugin;
    private final Stats stats;
    private final NamespacedKey key;
    private final Map<String, Board> boards = new ConcurrentHashMap<>();
    /** true - игрок смотрит топы за вайп. */
    private final Map<UUID, Boolean> wipeMode = new ConcurrentHashMap<>();
    private final Map<UUID, Long> clickCooldown = new ConcurrentHashMap<>();

    public Boards(JavaPlugin plugin, Stats stats) {
        this.plugin = plugin;
        this.stats = stats;
        this.key = new NamespacedKey(plugin, "board");
    }

    // ---------------- загрузка / сущности ----------------

    public void loadAll() {
        removeAll();
        ConfigurationSection s = plugin.getConfig().getConfigurationSection("boards");
        if (s == null) return;
        for (String id : s.getKeys(false)) {
            ConfigurationSection b = s.getConfigurationSection(id);
            if (b == null) continue;
            World world = Bukkit.getWorld(b.getString("world", "world"));
            if (world == null) {
                plugin.getLogger().warning("Топ " + id + ": мир " + b.getString("world") + " не найден.");
                continue;
            }
            Type type;
            try {
                type = Type.valueOf(b.getString("type", "KILLS").toUpperCase());
            } catch (IllegalArgumentException e) {
                continue;
            }
            Location loc = new Location(world, b.getDouble("x"), b.getDouble("y"), b.getDouble("z"),
                    (float) b.getDouble("yaw"), 0f);
            Board board = new Board(id, type, loc);
            boards.put(id, board);
            spawn(board);
        }
        refresh();
    }

    private void spawn(Board board) {
        Location loc = board.location;
        Chunk chunk = loc.getChunk();
        chunk.addPluginChunkTicket(plugin);
        // остатки прошлого запуска (например после падения сервера)
        for (Entity e : chunk.getEntities()) {
            if (board.id.equals(e.getPersistentDataContainer().get(key, PersistentDataType.STRING))) e.remove();
        }
        board.day = text(board, loc);
        board.wipe = text(board, loc);
        board.click = loc.getWorld().spawn(loc, Interaction.class, i -> {
            i.setPersistent(false);
            i.setResponsive(true);
            i.getPersistentDataContainer().set(key, PersistentDataType.STRING, board.id);
        });
        for (Player p : Bukkit.getOnlinePlayers()) applyVisibility(p, board);
    }

    private TextDisplay text(Board board, Location loc) {
        float scale = (float) plugin.getConfig().getDouble("scale", 1.0);
        return loc.getWorld().spawn(loc, TextDisplay.class, t -> {
            t.setPersistent(false);
            t.setVisibleByDefault(false);
            t.setBillboard(Display.Billboard.CENTER);
            t.setAlignment(TextDisplay.TextAlignment.CENTER);
            t.setShadowed(true);
            t.setBackgroundColor(Color.fromARGB(0, 0, 0, 0));
            t.setLineWidth(400);
            t.setBrightness(new Display.Brightness(15, 15));
            t.setTransformation(new Transformation(new Vector3f(), new AxisAngle4f(), new Vector3f(scale, scale, scale), new AxisAngle4f()));
            t.getPersistentDataContainer().set(key, PersistentDataType.STRING, board.id);
        });
    }

    public void removeAll() {
        for (Board b : boards.values()) {
            if (b.day != null) b.day.remove();
            if (b.wipe != null) b.wipe.remove();
            if (b.click != null) b.click.remove();
            b.location.getChunk().removePluginChunkTicket(plugin);
        }
        boards.clear();
    }

    // ---------------- видимость и клики ----------------

    public void applyVisibility(Player player) {
        for (Board b : boards.values()) applyVisibility(player, b);
    }

    private void applyVisibility(Player player, Board b) {
        boolean wipe = wipeMode.getOrDefault(player.getUniqueId(), false);
        if (b.day == null || b.wipe == null) return;
        if (wipe) {
            player.hideEntity(plugin, b.day);
            player.showEntity(plugin, b.wipe);
        } else {
            player.hideEntity(plugin, b.wipe);
            player.showEntity(plugin, b.day);
        }
    }

    /** Клик по зоне топа: переключить режим игрока. */
    public boolean handleClick(Player player, Entity entity) {
        String id = entity.getPersistentDataContainer().get(key, PersistentDataType.STRING);
        if (id == null || !boards.containsKey(id)) return false;
        long now = System.currentTimeMillis();
        Long last = clickCooldown.get(player.getUniqueId());
        if (last != null && now - last < 400) return true;
        clickCooldown.put(player.getUniqueId(), now);
        wipeMode.put(player.getUniqueId(), !wipeMode.getOrDefault(player.getUniqueId(), false));
        applyVisibility(player);
        return true;
    }

    public void forget(Player player) {
        wipeMode.remove(player.getUniqueId());
        clickCooldown.remove(player.getUniqueId());
    }

    // ---------------- текст ----------------

    public void refresh() {
        int size = Math.max(1, plugin.getConfig().getInt("size", 10));
        List<Object[]> clans = clans();
        for (Board b : boards.values()) {
            if (b.day == null || !b.day.isValid()) {
                spawn(b);
            }
            Component day = render(b.type, false, size, clans);
            Component wipe = render(b.type, true, size, clans);
            b.day.text(day);
            b.wipe.text(wipe);
            int lines = size + 2;
            float scale = (float) plugin.getConfig().getDouble("scale", 1.0);
            b.click.setInteractionWidth(3.5f * scale);
            b.click.setInteractionHeight(lines * 0.27f * scale);
        }
    }

    private Component render(Type type, boolean wipe, int size, List<Object[]> clans) {
        var cfg = plugin.getConfig();
        String section = type.name().toLowerCase();
        String mode = cfg.getString(wipe ? "mode-wipe" : "mode-day", wipe ? "ЗА ВАЙП" : "ЗА ДЕНЬ");
        List<Component> out = new ArrayList<>();
        out.add(MM.deserialize(cfg.getString(section + ".title", "").replace("{mode}", mode)));

        List<Component> names = new ArrayList<>();
        List<Long> values = new ArrayList<>();
        switch (type) {
            case KILLS -> stats.top(e -> wipe ? e.killsWipe : e.killsDay, size).forEach(r -> {
                names.add(Component.text(r.name()));
                values.add(r.value());
            });
            case PLAYTIME -> stats.top(e -> (wipe ? e.minutesWipe : e.minutesDay) / 60, size).forEach(r -> {
                names.add(Component.text(r.name()));
                values.add(r.value());
            });
            case CLANS -> {
                List<Object[]> rows = new ArrayList<>();
                for (Object[] c : clans) {
                    int rating = (Integer) c[2];
                    long v = wipe ? rating : stats.clanToday((String) c[0], rating);
                    if (v > 0) rows.add(new Object[]{c[1], v});
                }
                rows.sort((a, b) -> Long.compare((Long) b[1], (Long) a[1]));
                for (int i = 0; i < rows.size() && i < size; i++) {
                    names.add(LegacyComponentSerializer.legacySection().deserialize((String) rows.get(i)[0]));
                    values.add((Long) rows.get(i)[1]);
                }
            }
        }
        String line = cfg.getString(section + ".line", "#{place} {name} {value}");
        for (int i = 0; i < size; i++) {
            String place = String.valueOf(i + 1);
            if (i < names.size()) {
                String l = line.replace("{place}", place).replace("{value}", String.valueOf(values.get(i))).replace("{name}", "<n>");
                out.add(MM.deserialize(l, Placeholder.component("n", names.get(i))));
            } else {
                out.add(MM.deserialize(cfg.getString("empty-line", "<dark_gray>#{place} ---</dark_gray>").replace("{place}", place)));
            }
        }
        out.add(MM.deserialize(cfg.getString(wipe ? "footer-wipe" : "footer-day", "")));
        return Component.join(net.kyori.adventure.text.JoinConfiguration.newlines(), out);
    }

    /** Кланы из MediaClans: {ID, название с §-цветами, рейтинг}. */
    @SuppressWarnings("unchecked")
    private List<Object[]> clans() {
        var p = Bukkit.getPluginManager().getPlugin("MediaClans");
        if (p == null || !p.isEnabled()) return List.of();
        try {
            Method all = Class.forName("ru.dscraft.mediaclans.ClanApi", true, p.getClass().getClassLoader()).getMethod("all");
            return (List<Object[]>) all.invoke(null);
        } catch (Exception e) {
            return List.of();
        }
    }

    // ---------------- для команд ----------------

    public List<String> ids() {
        return new ArrayList<>(boards.keySet());
    }
}
