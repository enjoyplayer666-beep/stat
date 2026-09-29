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
import org.bukkit.configuration.file.FileConfiguration;
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
 * Голограммы-топы. Каждая строка - отдельный текст со своей подложкой (как на образце),
 * у каждой голограммы два набора строк (за день и за всё время) и невидимая зона клика.
 * Каждый игрок видит только набор своего режима; клик переключает режим сразу на всех топах.
 * Сущности не сохраняются в мире: создаются при запуске, чанк держится загруженным.
 */
public final class Boards {

    public enum Type { KILLS, CLANS, PLAYTIME }

    private static final MiniMessage MM = MiniMessage.miniMessage();

    private static final class Board {
        final String id;
        final Type type;
        final Location location;
        final List<TextDisplay> day = new ArrayList<>();
        final List<TextDisplay> wipe = new ArrayList<>();
        Interaction click;

        Board(String id, Type type, Location location) {
            this.id = id;
            this.type = type;
            this.location = location;
        }

        boolean valid() {
            return click != null && click.isValid() && !day.isEmpty() && day.get(0).isValid();
        }
    }

    private final JavaPlugin plugin;
    private final Stats stats;
    private final NamespacedKey key;
    private final Map<String, Board> boards = new ConcurrentHashMap<>();
    /** true - игрок смотрит топы за всё время. */
    private final Map<UUID, Boolean> wipeMode = new ConcurrentHashMap<>();
    private final Map<UUID, Long> clickCooldown = new ConcurrentHashMap<>();

    public Boards(JavaPlugin plugin, Stats stats) {
        this.plugin = plugin;
        this.stats = stats;
        this.key = new NamespacedKey(plugin, "board");
    }

    private FileConfiguration cfg() {
        return plugin.getConfig();
    }

    private int size() {
        return Math.max(1, Math.min(30, cfg().getInt("size", 10)));
    }

    // ---------------- загрузка / сущности ----------------

    public void loadAll() {
        removeAll();
        ConfigurationSection s = cfg().getConfigurationSection("boards");
        if (s != null) {
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
                Board board = new Board(id, type, new Location(world, b.getDouble("x"), b.getDouble("y"), b.getDouble("z")));
                boards.put(id, board);
                spawn(board);
            }
        }
        refresh();
    }

    private void spawn(Board board) {
        despawn(board);
        Location base = board.location;
        Chunk chunk = base.getChunk();
        chunk.addPluginChunkTicket(plugin);
        // остатки прошлого запуска (например после падения сервера)
        for (Entity e : chunk.getEntities()) {
            if (board.id.equals(e.getPersistentDataContainer().get(key, PersistentDataType.STRING))) e.remove();
        }
        float scale = (float) cfg().getDouble("scale", 1.0);
        double spacing = cfg().getDouble("line-spacing", 0.27) * scale;
        int lines = size() + 2;
        // строки сверху вниз: первая (заголовок) - выше всех, последняя - у основания
        for (int i = 0; i < lines; i++) {
            Location loc = base.clone().add(0, (lines - 1 - i) * spacing, 0);
            board.day.add(line(board, loc, scale));
            board.wipe.add(line(board, loc, scale));
        }
        board.click = base.getWorld().spawn(base, Interaction.class, it -> {
            it.setPersistent(false);
            it.setResponsive(true);
            it.setInteractionWidth(4.0f * scale);
            it.setInteractionHeight((float) (lines * spacing + 0.1));
            it.getPersistentDataContainer().set(key, PersistentDataType.STRING, board.id);
        });
        for (Player p : Bukkit.getOnlinePlayers()) applyVisibility(p, board);
    }

    private TextDisplay line(Board board, Location loc, float scale) {
        int argb = parseArgb(cfg().getString("background", "40000000"));
        boolean shadow = cfg().getBoolean("shadow", false);
        return loc.getWorld().spawn(loc, TextDisplay.class, t -> {
            t.setPersistent(false);
            t.setVisibleByDefault(false);
            t.setBillboard(Display.Billboard.CENTER);
            t.setAlignment(TextDisplay.TextAlignment.CENTER);
            t.setShadowed(shadow);
            t.setBackgroundColor(Color.fromARGB(argb));
            t.setLineWidth(1000);
            t.setBrightness(new Display.Brightness(15, 15));
            t.setTransformation(new Transformation(new Vector3f(), new AxisAngle4f(),
                    new Vector3f(scale, scale, scale), new AxisAngle4f()));
            t.getPersistentDataContainer().set(key, PersistentDataType.STRING, board.id);
        });
    }

    private static int parseArgb(String hex) {
        try {
            return (int) Long.parseLong(hex.replace("#", ""), 16);
        } catch (Exception e) {
            return 0x40000000;
        }
    }

    private void despawn(Board b) {
        b.day.forEach(Entity::remove);
        b.wipe.forEach(Entity::remove);
        b.day.clear();
        b.wipe.clear();
        if (b.click != null) b.click.remove();
        b.click = null;
    }

    public void removeAll() {
        for (Board b : boards.values()) {
            despawn(b);
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
        for (TextDisplay t : wipe ? b.day : b.wipe) player.hideEntity(plugin, t);
        for (TextDisplay t : wipe ? b.wipe : b.day) player.showEntity(plugin, t);
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
        List<Object[]> clans = clans();
        for (Board b : boards.values()) {
            if (!b.valid()) spawn(b);
            int argb = parseArgb(cfg().getString("background", "40000000"));
            fill(b.day, render(b.type, false, clans), argb);
            fill(b.wipe, render(b.type, true, clans), argb);
        }
    }

    /** Пустые строки (игроков меньше, чем мест) - без подложки, чтобы не висели пустые плашки. */
    private static void fill(List<TextDisplay> displays, List<Component> lines, int argb) {
        for (int i = 0; i < displays.size(); i++) {
            TextDisplay t = displays.get(i);
            Component c = i < lines.size() ? lines.get(i) : Component.empty();
            if (!c.equals(t.text())) t.text(c);
            Color bg = Color.fromARGB(c.equals(Component.empty()) ? 0 : argb);
            if (!bg.equals(t.getBackgroundColor())) t.setBackgroundColor(bg);
        }
    }

    private List<Component> render(Type type, boolean wipe, List<Object[]> clans) {
        int size = size();
        String section = type.name().toLowerCase();
        String mode = cfg().getString(wipe ? "mode-wipe" : "mode-day", wipe ? "ЗА ВАЙП" : "ЗА ДЕНЬ");
        List<Component> out = new ArrayList<>();
        out.add(MM.deserialize(cfg().getString(section + ".title", "").replace("{mode}", mode)));

        List<Component> names = new ArrayList<>();
        List<Long> values = new ArrayList<>();
        switch (type) {
            case KILLS -> stats.top(e -> wipe ? e.kills : stats.killsDay(e), size).forEach(r -> {
                names.add(Component.text(r.name()));
                values.add(r.value());
            });
            case PLAYTIME -> stats.top(e -> wipe ? e.minutes / 60 : stats.hoursDay(e), size).forEach(r -> {
                names.add(Component.text(r.name()));
                values.add(r.value());
            });
            case CLANS -> {
                // все кланы: по значению, при равенстве - по общему рейтингу (список всегда полный)
                List<Object[]> rows = new ArrayList<>();
                for (Object[] c : clans) {
                    int rating = (Integer) c[2];
                    long v = wipe ? rating : stats.clanToday((String) c[0], rating);
                    rows.add(new Object[]{c[1], v, rating});
                }
                rows.sort((a, b) -> {
                    int byValue = Long.compare((Long) b[1], (Long) a[1]);
                    return byValue != 0 ? byValue : Integer.compare((Integer) b[2], (Integer) a[2]);
                });
                for (int i = 0; i < rows.size() && i < size; i++) {
                    names.add(LegacyComponentSerializer.legacySection().deserialize((String) rows.get(i)[0]));
                    values.add((Long) rows.get(i)[1]);
                }
            }
        }
        String line = cfg().getString(section + ".line", "#{place} {name} {value}");
        for (int i = 0; i < size; i++) {
            if (i < names.size()) {
                String l = line.replace("{place}", String.valueOf(i + 1))
                        .replace("{value}", String.valueOf(values.get(i))).replace("{name}", "<n>");
                out.add(MM.deserialize(l, Placeholder.component("n", names.get(i))));
            } else {
                out.add(Component.empty());
            }
        }
        out.add(MM.deserialize(cfg().getString(wipe ? "footer-wipe" : "footer-day", "")));
        return out;
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

    public List<String> ids() {
        return new ArrayList<>(boards.keySet());
    }
}
