package ru.dscraft.mediacases;

import com.destroystokyo.paper.profile.PlayerProfile;
import net.kyori.adventure.bossbar.BossBar;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import net.kyori.adventure.title.Title;
import org.bukkit.Bukkit;
import org.bukkit.Color;
import org.bukkit.FireworkEffect;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.OfflinePlayer;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.ConsoleCommandSender;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Display;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Firework;
import org.bukkit.entity.Interaction;
import org.bukkit.entity.ItemDisplay;
import org.bukkit.entity.Player;
import org.bukkit.entity.TextDisplay;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.event.world.EntitiesLoadEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemFlag;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.FireworkMeta;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.inventory.meta.SkullMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.profile.PlayerTextures;
import org.bukkit.util.Transformation;
import org.joml.AxisAngle4f;
import org.joml.Vector3f;
import ru.dscraft.mediacoins.CoinsApi;

import java.io.File;
import java.io.IOException;
import java.net.URL;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Донат-кейсы за серебро.
 * - Серебро: /silver give ник кол-во (сайт доната) - на счёт игрока + глобальный бустер генераторов,
 *   босс-бар и "Событие сервера" в чат (кроме лобби).
 * - НПС "Покупка кейсов": /cases shop ник - меню наборов кейсов за серебро.
 * - Точка открытия (/cases setpoint): модель кейса из пака, голограмма; ПКМ - меню выбора кейса,
 *   история выбитых призов. Открытие: призы летают вокруг точки, выпавший остаётся над ней, фейерверк,
 *   титр игроку и сообщение всем. Коины - сразу в MediaCoins (видно в скорборде), привилегии - командами.
 */
public final class MediaCasesPlugin extends ru.dscraft.mediaeconomy.Module implements Listener {

    private static final MiniMessage MM = MiniMessage.miniMessage();
    private static final int HISTORY_MAX = 45;

    record Reward(String id, String display, double chance, Color color, Material icon,
                  List<String> commands, long coins, String amount) {
    }

    record Pack(int amount, int price, String name) {
    }

    record CaseType(String id, String name, String openName, int menuSlot, Material menuIcon,
                    Material shopIcon, String shopHead, int shopColumn, String winTitle, String winChat,
                    List<String> lore, String rewardLine, List<Pack> packs, List<Reward> rewards) {
        double total() {
            double t = 0;
            for (Reward r : rewards) t += r.chance();
            return t;
        }
    }

    record HistoryEntry(String player, String caseId, String rewardId, long time) {
    }

    private static final class MainMenu implements InventoryHolder {
        Inventory inv;

        @Override
        public Inventory getInventory() {
            return inv;
        }
    }

    private static final class ShopMenu implements InventoryHolder {
        Inventory inv;
        final Map<Integer, Object[]> packs = new HashMap<>(); // слот -> {кейс, набор}

        @Override
        public Inventory getInventory() {
            return inv;
        }
    }

    private final Map<String, CaseType> cases = new LinkedHashMap<>();
    private final Map<UUID, Long> silver = new HashMap<>();
    private final Map<UUID, Map<String, Integer>> keys = new HashMap<>();
    private final List<HistoryEntry> history = new ArrayList<>();
    private File dataFile;
    private boolean dirty;

    private Location point;
    private final List<Entity> pointEntities = new ArrayList<>();
    private UUID interactionId;
    private NamespacedKey tagKey;

    /** идёт открытие - точка занята */
    private Spin spin;

    // глобальный бустер
    private long boostUntil;
    private int boostMult;
    private BossBar boostBar;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        getConfig().options().copyDefaults(true);
        migrateConfig();
        saveConfig();
        tagKey = new NamespacedKey(this, "entity");
        dataFile = new File(getDataFolder(), "data.yml");
        loadCases();
        loadData();
        getServer().getPluginManager().registerEvents(this, this);
        for (String c : new String[]{"cases", "silver"}) {
            if (getCommand(c) != null) getCommand(c).setExecutor(this);
        }
        Bukkit.getScheduler().runTaskTimer(this, this::tickPoint, 20L, 40L);
        Bukkit.getScheduler().runTaskTimer(this, this::tickBooster, 20L, 20L);
        Bukkit.getScheduler().runTaskTimer(this, () -> {
            if (dirty) saveData();
        }, 20L * 30, 20L * 30);
        getLogger().info("Кейсов: " + cases.size() + ", точка открытия: " + (point == null ? "не поставлена" : "есть"));
    }

    @Override
    public void onDisable() {
        if (spin != null) spin.finishNow();
        removePointEntities();
        if (boostBar != null) for (Player p : Bukkit.getOnlinePlayers()) p.hideBossBar(boostBar);
        saveData();
    }

    // =====================================================================
    //  конфиг и данные
    // =====================================================================

    /** v2: модель стоит ровно (transform NONE) и поворачивается по взгляду игрока при /cases setpoint */
    private void migrateConfig() {
        Object v = getConfig().get("config-version", null);
        int version = v instanceof Number n ? n.intValue() : 1;
        if (version < 2) {
            getConfig().set("point.model.transform", "NONE");
            getConfig().set("point.model.rotation-x", 0);
            getConfig().set("point.model.rotation-y", 0);
            getConfig().set("point.model.y-offset", 0.5);
        }
        if (version < 3) {
            // головы-сундуки в меню НПС
            if (getConfig().getString("cases.privileges.shop-head", "").isBlank()) {
                getConfig().set("cases.privileges.shop-head", "9115dc88e3214c38243d782d63edb0a6e06291eb6da8e600c7e2ea36e7f61b31");
            }
            if (getConfig().getString("cases.coins.shop-head", "").isBlank()) {
                getConfig().set("cases.coins.shop-head", "db6975af70724d6a44fd5946e60b2717737dfdb545b4dab1893351a9c9dd183c");
            }
        }
        getConfig().set("config-version", 3);
    }

    private void loadCases() {
        cases.clear();
        ConfigurationSection root = getConfig().getConfigurationSection("cases");
        if (root == null) return;
        for (String id : root.getKeys(false)) {
            ConfigurationSection c = root.getConfigurationSection(id);
            if (c == null) continue;
            List<Pack> packs = new ArrayList<>();
            for (Map<?, ?> m : c.getMapList("packs")) {
                packs.add(new Pack(num(m.get("amount"), 1), num(m.get("price"), 1), String.valueOf(m.get("name"))));
            }
            List<Reward> rewards = new ArrayList<>();
            ConfigurationSection rs = c.getConfigurationSection("rewards");
            if (rs != null) {
                for (String rid : rs.getKeys(false)) {
                    ConfigurationSection r = rs.getConfigurationSection(rid);
                    if (r == null) continue;
                    rewards.add(new Reward(rid, r.getString("display", rid), r.getDouble("chance", 1),
                            color(r.getString("color", "#FFFFFF")), material(r.getString("icon"), Material.PAPER),
                            r.getStringList("commands"), r.getLong("coins", 0), r.getString("amount", "")));
                }
            }
            cases.put(id, new CaseType(id, c.getString("name", id), c.getString("open-name", c.getString("name", id)),
                    c.getInt("menu-slot", 22), material(c.getString("menu-icon"), Material.CHEST),
                    material(c.getString("shop-icon"), Material.CHEST), c.getString("shop-head", ""),
                    c.getInt("shop-column", 4), c.getString("win-title", ""), c.getString("win-chat", ""),
                    c.getStringList("lore"), c.getString("reward-line", "<reward> - <chance>%"), packs, rewards));
        }
    }

    private void loadData() {
        YamlConfiguration y = YamlConfiguration.loadConfiguration(dataFile);
        ConfigurationSection s = y.getConfigurationSection("silver");
        if (s != null) for (String k : s.getKeys(false)) {
            try {
                silver.put(UUID.fromString(k), s.getLong(k));
            } catch (IllegalArgumentException ignored) {
            }
        }
        ConfigurationSection ks = y.getConfigurationSection("keys");
        if (ks != null) for (String k : ks.getKeys(false)) {
            ConfigurationSection pc = ks.getConfigurationSection(k);
            if (pc == null) continue;
            try {
                Map<String, Integer> m = new HashMap<>();
                for (String c : pc.getKeys(false)) m.put(c, pc.getInt(c));
                keys.put(UUID.fromString(k), m);
            } catch (IllegalArgumentException ignored) {
            }
        }
        for (Map<?, ?> m : y.getMapList("history")) {
            history.add(new HistoryEntry(String.valueOf(m.get("player")), String.valueOf(m.get("case")),
                    String.valueOf(m.get("reward")), m.get("time") instanceof Number n ? n.longValue() : 0));
        }
        if (y.isConfigurationSection("point") && Bukkit.getWorld(y.getString("point.world", "")) != null) {
            point = new Location(Bukkit.getWorld(y.getString("point.world")), y.getInt("point.x"), y.getInt("point.y"), y.getInt("point.z"),
                    (float) y.getDouble("point.yaw", 0), 0f);
        } else if (y.isConfigurationSection("point")) {
            pendingPoint = y.getConfigurationSection("point").getValues(false);
        }
        long until = y.getLong("booster.until", 0);
        if (until > System.currentTimeMillis()) {
            boostUntil = until;
            boostMult = y.getInt("booster.multiplier", 1);
            pushGensBoost();
        }
    }

    /** точка в мире, который ещё не загружен (Multiverse) - сохраняем как есть */
    private Map<String, Object> pendingPoint;

    private void saveData() {
        YamlConfiguration y = new YamlConfiguration();
        for (var e : silver.entrySet()) if (e.getValue() > 0) y.set("silver." + e.getKey(), e.getValue());
        for (var e : keys.entrySet()) {
            for (var k : e.getValue().entrySet()) {
                if (k.getValue() > 0) y.set("keys." + e.getKey() + "." + k.getKey(), k.getValue());
            }
        }
        List<Map<String, Object>> hist = new ArrayList<>();
        for (HistoryEntry h : history) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("player", h.player());
            m.put("case", h.caseId());
            m.put("reward", h.rewardId());
            m.put("time", h.time());
            hist.add(m);
        }
        y.set("history", hist);
        if (point != null) {
            y.set("point.world", point.getWorld().getName());
            y.set("point.x", point.getBlockX());
            y.set("point.yaw", point.getYaw());
            y.set("point.y", point.getBlockY());
            y.set("point.z", point.getBlockZ());
        } else if (pendingPoint != null) {
            y.set("point", pendingPoint);
        }
        if (boostUntil > System.currentTimeMillis()) {
            y.set("booster.until", boostUntil);
            y.set("booster.multiplier", boostMult);
        }
        try {
            getDataFolder().mkdirs();
            File tmp = new File(getDataFolder(), "data.yml.tmp");
            y.save(tmp);
            if (dataFile.exists()) java.nio.file.Files.copy(dataFile.toPath(), new File(getDataFolder(), "data.yml.bak").toPath(),
                    java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            java.nio.file.Files.move(tmp.toPath(), dataFile.toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            dirty = false;
        } catch (IOException e) {
            getLogger().warning("Не удалось сохранить data.yml: " + e.getMessage());
        }
    }

    long silver(UUID id) {
        return silver.getOrDefault(id, 0L);
    }

    private void setSilver(UUID id, long v) {
        silver.put(id, Math.max(0, v));
        dirty = true;
    }

    int keys(UUID id, String caseId) {
        Map<String, Integer> m = keys.get(id);
        return m == null ? 0 : m.getOrDefault(caseId, 0);
    }

    private void addKeys(UUID id, String caseId, int n) {
        Map<String, Integer> m = keys.computeIfAbsent(id, k -> new HashMap<>());
        m.put(caseId, Math.max(0, m.getOrDefault(caseId, 0) + n));
        dirty = true;
    }

    // =====================================================================
    //  точка открытия: модель, голограмма, клик
    // =====================================================================

    private Location center() {
        return point.clone().add(0.5, 0, 0.5);
    }

    private boolean pointReady() {
        return point != null && point.getWorld() != null && point.isChunkLoaded();
    }

    /** держим модель, голограмму и зону клика (после загрузки чанка, после /reload) */
    private void tickPoint() {
        if (point == null && pendingPoint != null) {
            World w = Bukkit.getWorld(String.valueOf(pendingPoint.get("world")));
            if (w != null) {
                Object yaw = pendingPoint.get("yaw");
                point = new Location(w, num(pendingPoint.get("x"), 0), num(pendingPoint.get("y"), 0), num(pendingPoint.get("z"), 0),
                        yaw instanceof Number n ? n.floatValue() : 0f, 0f);
                pendingPoint = null;
            }
        }
        if (!pointReady()) {
            pointEntities.clear();
            interactionId = null;
            return;
        }
        boolean alive = !pointEntities.isEmpty();
        for (Entity e : pointEntities) if (!e.isValid()) alive = false;
        if (!alive) spawnPointEntities();
    }

    private void spawnPointEntities() {
        removePointEntities();
        Location c = center();
        World w = c.getWorld();
        ConfigurationSection m = getConfig().getConfigurationSection("point.model");
        if (m != null && m.getBoolean("enabled", true)) {
            ItemStack it = new ItemStack(material(m.getString("material"), Material.IRON_INGOT));
            ItemMeta meta = it.getItemMeta();
            meta.setCustomModelData(m.getInt("custom-model-data", 10115));
            it.setItemMeta(meta);
            float s = (float) m.getDouble("scale", 1.0);
            float rx = (float) Math.toRadians(m.getDouble("rotation-x", 0));
            // лицом туда, куда смотрел игрок при /cases setpoint (+ rotation-y из конфига)
            // лицевая сторона модели - туда, куда смотрел игрок (сама игра ещё разворачивает предмет на 180)
            float ry = (float) (-Math.toRadians(point.getYaw()) + Math.toRadians(m.getDouble("rotation-y", 0)));
            ItemDisplay.ItemDisplayTransform tf;
            try {
                tf = ItemDisplay.ItemDisplayTransform.valueOf(m.getString("transform", "NONE").toUpperCase(Locale.ROOT));
            } catch (IllegalArgumentException ex) {
                tf = ItemDisplay.ItemDisplayTransform.NONE;
            }
            ItemDisplay.ItemDisplayTransform transform = tf;
            ItemDisplay d = w.spawn(c.clone().add(0, m.getDouble("y-offset", 0.5), 0), ItemDisplay.class, e -> {
                e.setItemStack(it);
                e.setItemDisplayTransform(transform);
                // NONE - модель как есть: стоит ровно, 16 пикселей модели = 1 блок
                e.setTransformation(new Transformation(new Vector3f(0, 0, 0), new AxisAngle4f(ry, 0, 1, 0),
                        new Vector3f(s, s, s), new AxisAngle4f(rx, 1, 0, 0)));
                tag(e);
            });
            pointEntities.add(d);
        }
        if (spin == null) pointEntities.add(spawnHologram());
        Interaction hit = w.spawn(c, Interaction.class, e -> {
            e.setInteractionWidth(1.6f);
            e.setInteractionHeight(2.2f);
            e.setResponsive(true);
            tag(e);
        });
        interactionId = hit.getUniqueId();
        pointEntities.add(hit);
    }

    private TextDisplay spawnHologram() {
        Location c = center().add(0, getConfig().getDouble("point.hologram-height", 2.4), 0);
        List<String> lines = getConfig().getStringList("point.holograms");
        Component text = Component.empty();
        for (int i = 0; i < lines.size(); i++) {
            if (i > 0) text = text.append(Component.newline());
            text = text.append(MM.deserialize(lines.get(i)));
        }
        Component finalText = text;
        return c.getWorld().spawn(c, TextDisplay.class, e -> {
            e.text(finalText);
            e.setBillboard(Display.Billboard.CENTER);
            e.setBackgroundColor(Color.fromARGB(0, 0, 0, 0));
            e.setShadowed(true);
            tag(e);
        });
    }

    private void removePointEntities() {
        for (Entity e : pointEntities) if (e.isValid()) e.remove();
        pointEntities.clear();
        interactionId = null;
    }

    private void tag(Entity e) {
        e.setPersistent(false);
        e.getPersistentDataContainer().set(tagKey, PersistentDataType.BYTE, (byte) 1);
    }

    /** после рестарта/краша в чанке могли остаться наши сущности - убираем */
    @EventHandler
    public void onEntitiesLoad(EntitiesLoadEvent e) {
        for (Entity ent : e.getEntities()) {
            if (ent.getPersistentDataContainer().has(tagKey, PersistentDataType.BYTE) && !pointEntities.contains(ent)) ent.remove();
        }
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onClickPoint(PlayerInteractEntityEvent e) {
        if (interactionId == null || !e.getRightClicked().getUniqueId().equals(interactionId)) return;
        e.setCancelled(true);
        if (e.getHand() != EquipmentSlot.HAND) return;
        openMain(e.getPlayer());
    }

    /** ЛКМ по точке - тоже меню */
    @EventHandler(priority = EventPriority.HIGH)
    public void onHitPoint(EntityDamageByEntityEvent e) {
        if (interactionId != null && e.getEntity().getUniqueId().equals(interactionId) && e.getDamager() instanceof Player p) {
            e.setCancelled(true);
            openMain(p);
        }
    }

    // =====================================================================
    //  меню выбора у точки
    // =====================================================================

    void openMain(Player p) {
        MainMenu h = new MainMenu();
        Inventory inv = Bukkit.createInventory(h, 54, MM.deserialize(getConfig().getString("menu.title", "Меню выбора")));
        h.inv = inv;
        ItemStack dark = pane(Material.GRAY_STAINED_GLASS_PANE), light = pane(Material.LIGHT_GRAY_STAINED_GLASS_PANE);
        for (int i = 0; i < 45; i++) {
            int row = i / 9, col = i % 9;
            boolean border = row == 0 || row == 4 || col == 0 || col == 8;
            inv.setItem(i, border ? dark : light);
        }
        for (CaseType c : cases.values()) {
            if (c.menuSlot() < 0 || c.menuSlot() >= 45) continue;
            inv.setItem(c.menuSlot(), caseItem(c, p));
        }
        ConfigurationSection hs = getConfig().getConfigurationSection("menu.history");
        inv.setItem(40, item(new ItemStack(Material.RECOVERY_COMPASS),
                mm(hs == null ? "История открытий" : hs.getString("name", "История открытий")),
                hs == null ? List.of() : hs.getStringList("lore").stream().map(this::mm).toList()));
        long now = System.currentTimeMillis();
        for (int i = 0; i < 9 && i < history.size(); i++) {
            HistoryEntry h1 = history.get(i);
            CaseType c = cases.get(h1.caseId());
            Reward r = c == null ? null : reward(c, h1.rewardId());
            TagResolver tr = TagResolver.resolver(
                    Placeholder.unparsed("n", String.valueOf(i + 1)),
                    Placeholder.component("reward", r == null ? Component.text(h1.rewardId()) : MM.deserialize(r.display())),
                    Placeholder.unparsed("player", h1.player()),
                    Placeholder.unparsed("case", c == null ? h1.caseId() : c.name()),
                    Placeholder.unparsed("ago", ago(now - h1.time())));
            List<Component> lore = new ArrayList<>();
            if (hs != null) for (String l : hs.getStringList("entry-lore")) lore.add(mm(l, tr));
            inv.setItem(45 + i, item(new ItemStack(r == null ? Material.PAPER : r.icon()),
                    mm(hs == null ? "#<n> <reward>" : hs.getString("entry-name", "#<n> <reward>"), tr), lore));
        }
        p.openInventory(inv);
    }

    private ItemStack caseItem(CaseType c, Player p) {
        List<Component> lore = new ArrayList<>();
        double total = c.total();
        for (String line : c.lore()) {
            if (line.contains("<rewards>")) {
                for (Reward r : c.rewards()) {
                    lore.add(mm(c.rewardLine(), TagResolver.resolver(
                            Placeholder.component("reward", MM.deserialize(r.display())),
                            Placeholder.unparsed("amount", r.amount()),
                            Placeholder.unparsed("chance", fmt(r.chance())))));
                }
                continue;
            }
            lore.add(mm(line, Placeholder.unparsed("keys", String.valueOf(keys(p.getUniqueId(), c.id())))));
        }
        return item(new ItemStack(c.menuIcon()),
                mm("<gray>[</gray><#FFAA00>" + esc(c.name()) + "</#FFAA00><gray>]</gray>"), lore);
    }

    // =====================================================================
    //  НПС "Покупка кейсов"
    // =====================================================================

    void openShop(Player p) {
        ShopMenu h = new ShopMenu();
        Inventory inv = Bukkit.createInventory(h, 45, MM.deserialize(getConfig().getString("shop.title", "У вас <silver> серебра"),
                Placeholder.unparsed("silver", String.valueOf(silver(p.getUniqueId())))));
        h.inv = inv;
        ItemStack light = pane(Material.LIGHT_GRAY_STAINED_GLASS_PANE);
        for (int i = 0; i < 45; i++) inv.setItem(i, light);
        for (CaseType c : cases.values()) {
            for (int i = 0; i < c.packs().size() && i < 3; i++) {
                Pack pk = c.packs().get(i);
                int slot = (i + 1) * 9 + Math.max(0, Math.min(8, c.shopColumn()));
                ItemStack icon = c.shopHead() == null || c.shopHead().isBlank() ? new ItemStack(c.shopIcon()) : head(c.shopHead(), c.shopIcon());
                icon.setAmount(Math.max(1, Math.min(64, pk.amount())));
                String price = getConfig().getString("shop.price", "Цена <price> <word>");
                inv.setItem(slot, item(icon, mm("<#FF5555>" + esc(pk.name()) + "</#FF5555>"),
                        List.of(mm(price, TagResolver.resolver(Placeholder.unparsed("price", String.valueOf(pk.price())),
                                Placeholder.unparsed("word", pk.price() == 1 ? "серебро" : "серебра"))))));
                h.packs.put(slot, new Object[]{c, pk});
            }
        }
        p.openInventory(inv);
    }

    @EventHandler
    public void onClick(InventoryClickEvent e) {
        InventoryHolder holder = e.getInventory().getHolder();
        if (!(holder instanceof MainMenu) && !(holder instanceof ShopMenu)) return;
        e.setCancelled(true);
        if (!(e.getWhoClicked() instanceof Player p) || e.getClickedInventory() != e.getInventory()) return;
        int slot = e.getRawSlot();
        if (holder instanceof ShopMenu shop) {
            Object[] pk = shop.packs.get(slot);
            if (pk == null) return;
            buy(p, (CaseType) pk[0], (Pack) pk[1]);
            return;
        }
        for (CaseType c : cases.values()) {
            if (c.menuSlot() == slot) {
                p.closeInventory();
                startOpen(p, c);
                return;
            }
        }
    }

    @EventHandler
    public void onDrag(InventoryDragEvent e) {
        InventoryHolder holder = e.getInventory().getHolder();
        if (holder instanceof MainMenu || holder instanceof ShopMenu) e.setCancelled(true);
    }

    private void buy(Player p, CaseType c, Pack pk) {
        UUID id = p.getUniqueId();
        long have = silver(id);
        if (have < pk.price()) {
            msg(p, "not-enough", Placeholder.unparsed("silver", String.valueOf(have)));
            p.playSound(p.getLocation(), Sound.ENTITY_VILLAGER_NO, 0.6f, 1f);
            return;
        }
        setSilver(id, have - pk.price());
        addKeys(id, c.id(), pk.amount());
        saveData();
        msg(p, "bought", Placeholder.unparsed("pack", pk.name()));
        p.playSound(p.getLocation(), Sound.ENTITY_PLAYER_LEVELUP, 0.6f, 1.6f);
        openShop(p);
    }

    // =====================================================================
    //  открытие
    // =====================================================================

    private void startOpen(Player p, CaseType c) {
        if (!pointReady()) {
            msg(p, "no-point");
            return;
        }
        if (spin != null) {
            msg(p, "busy");
            return;
        }
        if (c.rewards().isEmpty()) return;
        if (keys(p.getUniqueId(), c.id()) <= 0) {
            msg(p, "no-keys");
            return;
        }
        addKeys(p.getUniqueId(), c.id(), -1);
        saveData();
        Reward win = roll(c);
        Bukkit.broadcast(mm(getConfig().getString("messages.open", "<player> открывает <case>"), TagResolver.resolver(
                Placeholder.unparsed("player", p.getName()), Placeholder.component("case", MM.deserialize(c.openName())))));
        spin = new Spin(p.getUniqueId(), p.getName(), c, win);
        spin.start();
    }

    private Reward roll(CaseType c) {
        double r = ThreadLocalRandom.current().nextDouble() * c.total();
        for (Reward rw : c.rewards()) {
            r -= rw.chance();
            if (r < 0) return rw;
        }
        return c.rewards().get(c.rewards().size() - 1);
    }

    /** Одно открытие: призы кружат вокруг точки, потом выпавший висит над ней. */
    private final class Spin {
        final UUID player;
        final String name;
        final CaseType type;
        final Reward win;
        final List<TextDisplay> labels = new ArrayList<>();
        final List<ItemDisplay> icons = new ArrayList<>();
        final double[] phase;
        int tick;
        int task = -1;
        boolean rewarded;
        boolean revealed;

        Spin(UUID player, String name, CaseType type, Reward win) {
            this.player = player;
            this.name = name;
            this.type = type;
            this.win = win;
            this.phase = new double[type.rewards().size()];
        }

        void start() {
            // голограмма точки прячется на время открытия
            for (Entity e : new ArrayList<>(pointEntities)) {
                if (e instanceof TextDisplay) {
                    e.remove();
                    pointEntities.remove(e);
                }
            }
            Location c = center();
            int n = type.rewards().size();
            for (int i = 0; i < n; i++) {
                Reward r = type.rewards().get(i);
                phase[i] = Math.PI * 2 * i / n;
                labels.add(c.getWorld().spawn(c, TextDisplay.class, e -> {
                    e.text(MM.deserialize(r.display()));
                    e.setBillboard(Display.Billboard.CENTER);
                    e.setBackgroundColor(Color.fromARGB(0, 0, 0, 0));
                    e.setShadowed(true);
                    e.setTeleportDuration(2);
                    tag(e);
                }));
                if (r.coins() <= 0) {
                    icons.add(c.getWorld().spawn(c, ItemDisplay.class, e -> {
                        e.setItemStack(new ItemStack(r.icon()));
                        e.setBillboard(Display.Billboard.CENTER);
                        e.setTransformation(new Transformation(new Vector3f(), new AxisAngle4f(),
                                new Vector3f(0.35f, 0.35f, 0.35f), new AxisAngle4f()));
                        e.setTeleportDuration(2);
                        tag(e);
                    }));
                } else {
                    icons.add(null);
                }
            }
            task = Bukkit.getScheduler().runTaskTimer(MediaCasesPlugin.this, this::frame, 1L, 2L).getTaskId();
        }

        void frame() {
            tick += 2;
            int total = (int) (getConfig().getDouble("animation.seconds", 7) * 20);
            Location c = center();
            if (tick < total) {
                double prog = (double) tick / total;
                double speed = 0.30 * Math.pow(1 - prog, 1.4) + 0.02;
                double radius = getConfig().getDouble("animation.radius", 2.0);
                double height = getConfig().getDouble("animation.height", 1.4);
                for (int i = 0; i < labels.size(); i++) {
                    phase[i] += speed;
                    double a = phase[i];
                    double r = radius * (0.75 + 0.25 * Math.sin(tick * 0.09 + i * 1.7));
                    double y = height + 0.9 * Math.sin(a * 1.5 + i) + 0.3 * Math.cos(tick * 0.12 + i);
                    Location at = c.clone().add(Math.cos(a) * r, y, Math.sin(a) * r);
                    labels.get(i).teleport(at);
                    ItemDisplay ic = icons.get(i);
                    if (ic != null) ic.teleport(at.clone().add(0, -0.35, 0));
                    Reward rw = type.rewards().get(i);
                    c.getWorld().spawnParticle(Particle.DUST, at.clone().add(0, -0.2, 0), 3, 0.06, 0.06, 0.06, 0,
                            new Particle.DustOptions(rw.color(), 1.1f));
                    c.getWorld().spawnParticle(Particle.WITCH, at.clone().add(0, -0.2, 0), 1, 0.05, 0.05, 0.05, 0);
                }
                if (tick % 6 == 0) c.getWorld().playSound(c, Sound.BLOCK_NOTE_BLOCK_HAT, 0.5f, (float) (0.8 + prog));
                return;
            }
            if (!revealed) {
                revealed = true;
                reveal();
                return;
            }
            int resultTicks = (int) (getConfig().getDouble("animation.result-seconds", 3) * 20);
            if (tick >= total + resultTicks) finish();
        }

        void reveal() {
            Location c = center();
            int wi = type.rewards().indexOf(win);
            for (int i = 0; i < labels.size(); i++) {
                if (i == wi) continue;
                labels.get(i).remove();
                if (icons.get(i) != null) icons.get(i).remove();
            }
            TextDisplay label = labels.get(wi);
            label.teleport(c.clone().add(0, 1.9, 0));
            label.setTransformation(new Transformation(new Vector3f(), new AxisAngle4f(), new Vector3f(1.4f, 1.4f, 1.4f), new AxisAngle4f()));
            ItemDisplay ic = icons.get(wi);
            if (ic != null) ic.teleport(c.clone().add(0, 1.5, 0));
            // фейерверк (без урона) и искры
            Firework fw = c.getWorld().spawn(c.clone().add(0, 1.5, 0), Firework.class, f -> {
                FireworkMeta fm = f.getFireworkMeta();
                fm.addEffect(FireworkEffect.builder().with(FireworkEffect.Type.STAR)
                        .withColor(Color.fromRGB(0x55FF55), Color.fromRGB(0xFFFF55)).withFade(Color.fromRGB(0xB0FF60)).flicker(true).build());
                f.setFireworkMeta(fm);
                f.getPersistentDataContainer().set(tagKey, PersistentDataType.BYTE, (byte) 1);
            });
            Bukkit.getScheduler().runTaskLater(MediaCasesPlugin.this, fw::detonate, 1L);
            c.getWorld().spawnParticle(Particle.HAPPY_VILLAGER, c.clone().add(0, 1.4, 0), 60, 1.2, 1.0, 1.2, 0);
            c.getWorld().playSound(c, Sound.ENTITY_PLAYER_LEVELUP, 1f, 1.2f);
            giveReward();
        }

        void giveReward() {
            if (rewarded) return;
            rewarded = true;
            for (String cmd : win.commands()) {
                Bukkit.dispatchCommand(Bukkit.getConsoleSender(), cmd.replace("{player}", name));
            }
            if (win.coins() > 0) CoinsApi.give(player, win.coins());
            history.add(0, new HistoryEntry(name, type.id(), win.id(), System.currentTimeMillis()));
            while (history.size() > HISTORY_MAX) history.remove(history.size() - 1);
            dirty = true;
            Component reward = MM.deserialize(win.display());
            String plain = PlainTextComponentSerializer.plainText().serialize(reward);
            TagResolver tr = TagResolver.resolver(Placeholder.unparsed("player", name), Placeholder.component("reward", reward),
                    Placeholder.unparsed("reward-text", plain),
                    Placeholder.component("point", MM.deserialize(getConfig().getString("point.chat-name", "кейса"))));
            Player p = Bukkit.getPlayer(player);
            if (p != null && !type.winTitle().isEmpty()) {
                p.showTitle(Title.title(MM.deserialize(type.winTitle(), tr), Component.empty(),
                        Title.Times.times(java.time.Duration.ofMillis(150), java.time.Duration.ofMillis(2200), java.time.Duration.ofMillis(500))));
            }
            if (!type.winChat().isEmpty()) Bukkit.broadcast(MM.deserialize(type.winChat(), tr));
        }

        void finish() {
            if (task != -1) Bukkit.getScheduler().cancelTask(task);
            task = -1;
            for (TextDisplay t : labels) if (t.isValid()) t.remove();
            for (ItemDisplay i : icons) if (i != null && i.isValid()) i.remove();
            spin = null;
            if (pointReady()) pointEntities.add(spawnHologram());
        }

        /** плагин выключается посреди открытия - приз всё равно выдаём */
        void finishNow() {
            giveReward();
            finish();
        }
    }

    /** фейерверк кейса никого не ранит */
    @EventHandler(priority = EventPriority.LOWEST)
    public void onFireworkDamage(EntityDamageByEntityEvent e) {
        if (e.getDamager() instanceof Firework f && f.getPersistentDataContainer().has(tagKey, PersistentDataType.BYTE)) {
            e.setCancelled(true);
        }
    }

    // =====================================================================
    //  серебро и глобальный бустер
    // =====================================================================

    private void giveSilver(CommandSender sender, String name, long amount) {
        OfflinePlayer op = Bukkit.getOfflinePlayer(name);
        setSilver(op.getUniqueId(), silver(op.getUniqueId()) + amount);
        saveData();
        String shown = op.getName() != null ? op.getName() : name;
        sender.sendMessage(MM.deserialize("<green>Серебро: " + esc(shown) + " +" + amount + " (теперь " + silver(op.getUniqueId()) + ")"));
        if (op.getPlayer() != null && op.getPlayer() != sender) {
            msg(op.getPlayer(), "balance", Placeholder.unparsed("silver", String.valueOf(silver(op.getUniqueId()))));
        }
        if (getConfig().getBoolean("booster.enabled", true)) startBooster(shown);
    }

    private void startBooster(String name) {
        long now = System.currentTimeMillis();
        long add = (long) (getConfig().getDouble("booster.minutes", 3) * 60_000L);
        boostMult = Math.max(1, getConfig().getInt("booster.multiplier", 4));
        boostUntil = Math.max(boostUntil, now) + add;
        pushGensBoost();
        dirty = true;
        String lobby = getConfig().getString("lobby-world", "world");
        for (Player p : Bukkit.getOnlinePlayers()) {
            if (p.getWorld().getName().equalsIgnoreCase(lobby)) continue;
            for (String line : getConfig().getStringList("booster.announce")) {
                p.sendMessage(MM.deserialize(line, Placeholder.unparsed("player", name)));
            }
            p.playSound(p.getLocation(), Sound.UI_TOAST_CHALLENGE_COMPLETE, 0.5f, 1.2f);
        }
        tickBooster();
    }

    /** множитель и время - генераторам MediaGens (плагин MediaDestroySkyPvP) */
    private void pushGensBoost() {
        try {
            var host = Bukkit.getPluginManager().getPlugin("MediaDestroySkyPvP");
            if (host == null) return;
            Class.forName("ru.dscraft.mediagens.MediaGensPlugin", true, host.getClass().getClassLoader())
                    .getMethod("globalBoost", int.class, long.class).invoke(null, boostMult, boostUntil);
        } catch (Throwable t) {
            getLogger().warning("Не удалось включить бустер генераторов: " + t);
        }
    }

    private void tickBooster() {
        long left = boostUntil - System.currentTimeMillis();
        if (left <= 0) {
            if (boostBar != null) {
                for (Player p : Bukkit.getOnlinePlayers()) p.hideBossBar(boostBar);
                boostBar = null;
            }
            return;
        }
        long sec = left / 1000;
        String time = sec >= 60 ? (sec / 60) + "м, " + (sec % 60) + "с." : sec + "с.";
        Component title = MM.deserialize(getConfig().getString("booster.bossbar", "Бустер x<mult> - <time>"),
                TagResolver.resolver(Placeholder.unparsed("mult", String.valueOf(boostMult)), Placeholder.unparsed("time", time)));
        long full = (long) (getConfig().getDouble("booster.minutes", 3) * 60_000L);
        float progress = (float) Math.max(0, Math.min(1, (double) left / Math.max(full, left)));
        if (boostBar == null) {
            boostBar = BossBar.bossBar(title, progress, barColor(), barStyle());
        } else {
            boostBar.name(title);
            boostBar.progress(progress);
        }
        String lobby = getConfig().getString("lobby-world", "world");
        for (Player p : Bukkit.getOnlinePlayers()) {
            if (p.getWorld().getName().equalsIgnoreCase(lobby)) p.hideBossBar(boostBar);
            else p.showBossBar(boostBar);
        }
    }

    private BossBar.Color barColor() {
        try {
            return BossBar.Color.valueOf(getConfig().getString("booster.bossbar-color", "WHITE").toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            return BossBar.Color.WHITE;
        }
    }

    private BossBar.Overlay barStyle() {
        String s = getConfig().getString("booster.bossbar-style", "NOTCHED_20").toUpperCase(Locale.ROOT);
        try {
            return BossBar.Overlay.valueOf(s);
        } catch (IllegalArgumentException e) {
            return BossBar.Overlay.NOTCHED_20;
        }
    }

    // =====================================================================
    //  команды
    // =====================================================================

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] a) {
        boolean admin = sender.hasPermission("mediacases.admin") || sender instanceof ConsoleCommandSender;
        if (command.getName().equalsIgnoreCase("silver")) {
            if (a.length >= 3 && admin) {
                long n;
                try {
                    n = Long.parseLong(a[2]);
                } catch (NumberFormatException e) {
                    sender.sendMessage("§cКоличество - число.");
                    return true;
                }
                switch (a[0].toLowerCase(Locale.ROOT)) {
                    case "give" -> giveSilver(sender, a[1], Math.max(0, n));
                    case "take", "set" -> {
                        OfflinePlayer op = Bukkit.getOfflinePlayer(a[1]);
                        long v = a[0].equalsIgnoreCase("set") ? n : silver(op.getUniqueId()) - n;
                        setSilver(op.getUniqueId(), v);
                        saveData();
                        sender.sendMessage("§aСеребро " + a[1] + ": " + silver(op.getUniqueId()));
                    }
                    default -> sender.sendMessage("§7/silver give|take|set <ник> <кол-во>");
                }
                return true;
            }
            if (a.length >= 1 && admin && !(sender instanceof Player && a[0].equalsIgnoreCase(sender.getName()))) {
                OfflinePlayer op = Bukkit.getOfflinePlayer(a[0]);
                sender.sendMessage("§7Серебро " + a[0] + ": §f" + silver(op.getUniqueId()));
                return true;
            }
            if (sender instanceof Player p) msg(p, "balance", Placeholder.unparsed("silver", String.valueOf(silver(p.getUniqueId()))));
            return true;
        }
        // /cases
        if (!admin) return true;
        String sub = a.length == 0 ? "" : a[0].toLowerCase(Locale.ROOT);
        switch (sub) {
            case "setpoint" -> {
                if (!(sender instanceof Player p)) return true;
                removePointEntities();
                point = p.getLocation().getBlock().getLocation();
                // ровно по сторонам света - ближайшие 90 градусов к взгляду игрока
                point.setYaw(Math.round(p.getLocation().getYaw() / 90f) * 90f);
                pendingPoint = null;
                saveData();
                spawnPointEntities();
                sender.sendMessage("§aТочка открытия поставлена: " + point.getBlockX() + " " + point.getBlockY() + " " + point.getBlockZ());
            }
            case "rotate" -> {
                if (point == null) {
                    sender.sendMessage("§cТочка открытия не поставлена.");
                    return true;
                }
                point.setYaw((point.getYaw() + 90f) % 360f);
                saveData();
                if (spin == null) spawnPointEntities();
                sender.sendMessage("§aКейс повёрнут на 90°.");
            }
            case "removepoint" -> {
                removePointEntities();
                point = null;
                pendingPoint = null;
                saveData();
                sender.sendMessage("§aТочка открытия убрана.");
            }
            case "shop" -> {
                Player target = a.length >= 2 ? Bukkit.getPlayerExact(a[1]) : sender instanceof Player p ? p : null;
                if (target != null) openShop(target);
            }
            case "menu" -> {
                Player target = a.length >= 2 ? Bukkit.getPlayerExact(a[1]) : sender instanceof Player p ? p : null;
                if (target != null) openMain(target);
            }
            case "give" -> {
                if (a.length < 4 || !cases.containsKey(a[2])) {
                    sender.sendMessage("§7/cases give <ник> <" + String.join("|", cases.keySet()) + "> <кол-во>");
                    return true;
                }
                OfflinePlayer op = Bukkit.getOfflinePlayer(a[1]);
                int n;
                try {
                    n = Integer.parseInt(a[3]);
                } catch (NumberFormatException e) {
                    sender.sendMessage("§cКоличество - число.");
                    return true;
                }
                addKeys(op.getUniqueId(), a[2], n);
                saveData();
                sender.sendMessage("§aКейсов " + a[2] + " у " + a[1] + ": " + keys(op.getUniqueId(), a[2]));
            }
            case "reload" -> {
                reloadConfig();
                loadCases();
                if (spin == null && pointReady()) spawnPointEntities();
                sender.sendMessage("§aMediaCases: конфиг перезагружен.");
            }
            default -> sender.sendMessage("§7/cases setpoint | rotate | removepoint | shop [ник] | menu [ник] | give <ник> <кейс> <кол-во> | reload\n"
                    + "§7/silver give|take|set <ник> <кол-во>");
        }
        return true;
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] a) {
        List<String> out = new ArrayList<>();
        if (!sender.hasPermission("mediacases.admin")) return out;
        if (command.getName().equalsIgnoreCase("silver")) {
            if (a.length == 1) out.addAll(List.of("give", "take", "set"));
            else if (a.length == 2) for (Player p : Bukkit.getOnlinePlayers()) out.add(p.getName());
        } else {
            if (a.length == 1) out.addAll(List.of("setpoint", "rotate", "removepoint", "shop", "menu", "give", "reload"));
            else if (a.length == 2) for (Player p : Bukkit.getOnlinePlayers()) out.add(p.getName());
            else if (a.length == 3 && a[0].equalsIgnoreCase("give")) out.addAll(cases.keySet());
        }
        String pre = a.length == 0 ? "" : a[a.length - 1].toLowerCase(Locale.ROOT);
        out.removeIf(s -> !s.toLowerCase(Locale.ROOT).startsWith(pre));
        return out;
    }

    // =====================================================================
    //  мелочи
    // =====================================================================

    private void msg(CommandSender to, String key, TagResolver... r) {
        String t = getConfig().getString("messages." + key, "");
        if (!t.isEmpty()) to.sendMessage(MM.deserialize(t, r));
    }

    /** текст для предмета: без курсива по умолчанию */
    private Component mm(String s, TagResolver... r) {
        return MM.deserialize(s, r).decorationIfAbsent(TextDecoration.ITALIC, TextDecoration.State.FALSE);
    }

    private static String esc(String s) {
        return MM.escapeTags(s);
    }

    private static Reward reward(CaseType c, String id) {
        for (Reward r : c.rewards()) if (r.id().equals(id)) return r;
        return null;
    }

    private static ItemStack pane(Material m) {
        ItemStack it = new ItemStack(m);
        ItemMeta meta = it.getItemMeta();
        meta.setHideTooltip(true);
        it.setItemMeta(meta);
        return it;
    }

    private static ItemStack item(ItemStack it, Component name, List<Component> lore) {
        ItemMeta meta = it.getItemMeta();
        meta.displayName(name);
        meta.lore(lore);
        meta.addItemFlags(ItemFlag.values());
        it.setItemMeta(meta);
        return it;
    }

    private ItemStack head(String texture, Material fallback) {
        String hash = texture.trim();
        if (hash.startsWith("http")) hash = hash.substring(hash.lastIndexOf('/') + 1);
        ItemStack head = new ItemStack(Material.PLAYER_HEAD);
        SkullMeta meta = (SkullMeta) head.getItemMeta();
        try {
            PlayerProfile profile = Bukkit.createProfile(UUID.nameUUIDFromBytes(("case" + hash).getBytes()), "case");
            PlayerTextures textures = profile.getTextures();
            textures.setSkin(new URL("http://textures.minecraft.net/texture/" + hash));
            profile.setTextures(textures);
            meta.setPlayerProfile(profile);
            head.setItemMeta(meta);
            return head;
        } catch (Exception e) {
            return new ItemStack(fallback);
        }
    }

    private static Material material(String s, Material def) {
        if (s == null) return def;
        Material m = Material.matchMaterial(s.trim());
        return m == null ? def : m;
    }

    private static Color color(String hex) {
        try {
            return Color.fromRGB(Integer.parseInt(hex.replace("#", "").trim(), 16));
        } catch (Exception e) {
            return Color.WHITE;
        }
    }

    private static int num(Object o, int def) {
        if (o instanceof Number n) return n.intValue();
        try {
            return Integer.parseInt(String.valueOf(o).trim());
        } catch (Exception e) {
            return def;
        }
    }

    private static String fmt(double d) {
        return d == Math.floor(d) ? String.valueOf((long) d) : String.valueOf(d);
    }

    /** "1 день 16 часов", "5 минут" */
    private static String ago(long ms) {
        long m = Math.max(0, ms / 60000);
        long d = m / 1440, h = (m % 1440) / 60, min = m % 60;
        if (d > 0) return d + " " + plural(d, "день", "дня", "дней") + (h > 0 ? " " + h + " " + plural(h, "час", "часа", "часов") : "");
        if (h > 0) return h + " " + plural(h, "час", "часа", "часов") + (min > 0 ? " " + min + " " + plural(min, "минута", "минуты", "минут") : "");
        if (min > 0) return min + " " + plural(min, "минута", "минуты", "минут");
        return "меньше минуты";
    }

    private static String plural(long n, String one, String few, String many) {
        long n10 = n % 10, n100 = n % 100;
        if (n10 == 1 && n100 != 11) return one;
        if (n10 >= 2 && n10 <= 4 && (n100 < 12 || n100 > 14)) return few;
        return many;
    }
}
