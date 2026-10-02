package ru.dscraft.mediaitems;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.NamespacedKey;
import org.bukkit.Registry;
import org.bukkit.World;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Ageable;
import org.bukkit.entity.AbstractSkeleton;
import org.bukkit.entity.Entity;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Mob;
import org.bukkit.entity.Player;
import org.bukkit.entity.Villager;
import org.bukkit.entity.Zombie;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.entity.CreatureSpawnEvent;
import org.bukkit.event.entity.EntityCombustEvent;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.EntityTargetEvent;
import org.bukkit.event.entity.EntityTeleportEvent;
import org.bukkit.event.entity.EntityTransformEvent;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.inventory.PrepareItemCraftEvent;
import org.bukkit.event.inventory.InventoryType;
import org.bukkit.event.player.PlayerInteractAtEntityEvent;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.world.EntitiesLoadEvent;
import org.bukkit.inventory.EntityEquipment;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.Merchant;
import org.bukkit.inventory.MerchantInventory;
import org.bukkit.inventory.MerchantRecipe;
import org.bukkit.persistence.PersistentDataType;

import java.io.File;
import java.io.IOException;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/** Поставленные НПС: моб без ИИ (или игрок через FancyNpcs), по клику открывает обмен. */
final class Npcs implements Listener {

    static final class Npc {
        String id;
        String shop;
        Location loc;
        UUID entity;
        String fancy;
    }

    private final MediaItemsPlugin plugin;
    private final File file;
    private final Map<String, Npc> npcs = new LinkedHashMap<>();
    private final Map<UUID, Shops.Shop> openShops = new HashMap<>();
    /** НПС из миров, которые ещё не загружены: id -> данные из npcs.yml (сохраняются как есть) */
    private final Map<String, Map<String, Object>> pending = new LinkedHashMap<>();
    /** защита от двойного открытия (клик по мобу приходит двумя событиями) */
    private final Map<UUID, Long> lastOpen = new HashMap<>();

    Npcs(MediaItemsPlugin plugin) {
        this.plugin = plugin;
        this.file = new File(plugin.getDataFolder(), "npcs.yml");
    }

    // ---------- хранение ----------

    void load() {
        npcs.clear();
        pending.clear();
        YamlConfiguration y = YamlConfiguration.loadConfiguration(file);
        ConfigurationSection sec = y.getConfigurationSection("npcs");
        if (sec == null) return;
        for (String id : sec.getKeys(false)) {
            ConfigurationSection s = sec.getConfigurationSection(id);
            World w = Bukkit.getWorld(s.getString("world", "world"));
            if (w == null) {
                // мир ещё не загружен (Multiverse грузит миры позже) - держим НПС до загрузки мира, не теряем
                pending.put(id, s.getValues(true));
                continue;
            }
            Npc n = new Npc();
            n.id = id;
            n.shop = s.getString("shop");
            n.loc = new Location(w, s.getDouble("x"), s.getDouble("y"), s.getDouble("z"),
                    (float) s.getDouble("yaw"), 0f);
            String uuid = s.getString("entity");
            n.entity = uuid == null ? null : UUID.fromString(uuid);
            n.fancy = s.getString("fancy");
            npcs.put(id, n);
        }
    }

    void save() {
        YamlConfiguration y = new YamlConfiguration();
        for (Npc n : npcs.values()) {
            String p = "npcs." + n.id + ".";
            y.set(p + "shop", n.shop);
            y.set(p + "world", n.loc.getWorld().getName());
            y.set(p + "x", n.loc.getX());
            y.set(p + "y", n.loc.getY());
            y.set(p + "z", n.loc.getZ());
            y.set(p + "yaw", (double) n.loc.getYaw());
            y.set(p + "entity", n.entity == null ? null : n.entity.toString());
            y.set(p + "fancy", n.fancy);
        }
        for (var e : pending.entrySet()) {
            for (var v : e.getValue().entrySet()) y.set("npcs." + e.getKey() + "." + v.getKey(), v.getValue());
        }
        try {
            y.save(file);
        } catch (IOException e) {
            plugin.getLogger().warning("Не удалось сохранить npcs.yml: " + e.getMessage());
        }
    }

    @EventHandler
    public void onWorldLoad(org.bukkit.event.world.WorldLoadEvent e) {
        boolean any = false;
        for (var it = pending.entrySet().iterator(); it.hasNext(); ) {
            var en = it.next();
            Map<String, Object> d = en.getValue();
            if (!e.getWorld().getName().equals(String.valueOf(d.get("world")))) continue;
            Npc n = new Npc();
            n.id = en.getKey();
            n.shop = String.valueOf(d.get("shop"));
            n.loc = new Location(e.getWorld(), num(d.get("x")), num(d.get("y")), num(d.get("z")), (float) num(d.get("yaw")), 0f);
            Object uuid = d.get("entity");
            n.entity = uuid == null ? null : UUID.fromString(String.valueOf(uuid));
            n.fancy = d.get("fancy") == null ? null : String.valueOf(d.get("fancy"));
            npcs.put(n.id, n);
            it.remove();
            any = true;
        }
        if (any) plugin.getLogger().info("НПС мира " + e.getWorld().getName() + " загружены.");
    }

    private static double num(Object o) {
        return o instanceof Number n ? n.doubleValue() : 0;
    }

    Map<String, Npc> all() {
        return npcs;
    }

    // ---------- создание / удаление ----------

    Npc create(Player creator, Shops.Shop shop) {
        String id = shop.id();
        for (int i = 2; npcs.containsKey(id); i++) id = shop.id() + "_" + i;
        Location loc = creator.getLocation().clone();
        loc.setPitch(0f);
        Npc n = new Npc();
        n.id = id;
        n.shop = shop.id();
        n.loc = loc;
        npcs.put(id, n);
        spawn(n, shop, creator);
        save();
        return n;
    }

    void remove(Npc n) {
        despawn(n);
        npcs.remove(n.id);
        save();
    }

    void moveHere(Npc n, Player p) {
        despawn(n);
        n.loc = p.getLocation().clone();
        n.loc.setPitch(0f);
        Shops.Shop shop = plugin.shops().get(n.shop);
        if (shop != null) spawn(n, shop, p);
        save();
    }

    /** Пересоздать всех (после /itemnpc reload - новый внешний вид и названия). */
    void respawnAll() {
        for (Npc n : npcs.values()) {
            Shops.Shop shop = plugin.shops().get(n.shop);
            // НПС-игроков FancyNpcs не трогаем: их пересоздаёт только /itemnpc remove + create
            if (shop == null || n.fancy != null || !n.loc.isChunkLoaded()) continue;
            despawn(n);
            spawn(n, shop, null);
        }
        save();
    }

    private void despawn(Npc n) {
        if (n.entity != null) {
            Entity e = Bukkit.getEntity(n.entity);
            if (e != null) e.remove();
            n.entity = null;
        }
        if (n.fancy != null) {
            Bukkit.dispatchCommand(Bukkit.getConsoleSender(), "npc remove " + n.fancy);
            n.fancy = null;
        }
    }

    private void spawn(Npc n, Shops.Shop shop, Player creator) {
        ConfigurationSection s = shop.npc();
        String type = s == null ? "VILLAGER" : s.getString("type", "VILLAGER").toUpperCase(Locale.ROOT);
        String skin = s == null ? "" : s.getString("skin", "");
        boolean fancyOk = Bukkit.getPluginManager().isPluginEnabled("FancyNpcs");
        if (type.equals("PLAYER") && fancyOk && creator != null) {
            spawnFancy(n, shop, creator, skin);
            return;
        }
        if (type.equals("PLAYER")) type = s.getString("fallback-type", "ZOMBIE").toUpperCase(Locale.ROOT);
        EntityType et;
        try {
            et = EntityType.valueOf(type);
        } catch (IllegalArgumentException e) {
            plugin.getLogger().warning("Магазин " + shop.id() + ": нет типа моба " + type + ", ставлю VILLAGER");
            et = EntityType.VILLAGER;
        }
        Entity e = n.loc.getWorld().spawnEntity(n.loc, et, CreatureSpawnEvent.SpawnReason.CUSTOM,
                ent -> setup(ent, n, shop));
        n.entity = e.getUniqueId();
    }

    /** НПС-игрок со скином через команды FancyNpcs (создаётся там, где стоит администратор). */
    private void spawnFancy(Npc n, Shops.Shop shop, Player creator, String skin) {
        String name = "mi_" + n.id;
        n.fancy = name;
        creator.performCommand("npc create " + name);
        if (!skin.isBlank()) creator.performCommand("npc skin " + name + " " + skin);
        creator.performCommand("npc displayname " + name + " " + shop.name());
        boolean turn = shop.npc() == null || shop.npc().getBoolean("turn", true);
        creator.performCommand("npc turn_to_player " + name + " " + turn);
        creator.performCommand("npc action " + name + " any_click add console_command itemnpc open {player} " + shop.id());
        // экипировка: FancyNpcs берёт предмет из руки (@hand) - на секунду кладём его в руку администратору
        ConfigurationSection s = shop.npc();
        if (s == null) return;
        String[][] slots = {{"head", "head"}, {"chest", "chest"}, {"legs", "legs"}, {"feet", "feet"},
                {"hand", "mainhand"}, {"offhand", "offhand"}};
        ItemStack held = creator.getInventory().getItemInMainHand();
        try {
            for (String[] sl : slots) {
                ItemStack it = plugin.items().parse(s.getString("equipment." + sl[0]));
                if (it == null) continue;
                creator.getInventory().setItemInMainHand(it);
                creator.performCommand("npc equipment " + name + " set " + sl[1] + " @hand");
            }
        } finally {
            creator.getInventory().setItemInMainHand(held);
        }
    }

    private void setup(Entity ent, Npc n, Shops.Shop shop) {
        ConfigurationSection s = shop.npc();
        ent.getPersistentDataContainer().set(plugin.npcKey, PersistentDataType.STRING, n.id);
        ent.customName(Text.mm(shop.name()));
        ent.setCustomNameVisible(true);
        ent.setPersistent(true);
        ent.setSilent(true);
        ent.setInvulnerable(true);
        ent.setRotation(n.loc.getYaw(), 0f);
        if (ent instanceof LivingEntity le) {
            le.setAI(false);
            le.setRemoveWhenFarAway(false);
            le.setCollidable(false);
            le.setCanPickupItems(false);
            if (s != null && s.getDouble("scale", 0) > 0) {
                var scale = Registry.ATTRIBUTE.get(NamespacedKey.minecraft("generic.scale"));
                if (scale == null) scale = Registry.ATTRIBUTE.get(NamespacedKey.minecraft("scale"));
                if (scale != null && le.getAttribute(scale) != null) le.getAttribute(scale).setBaseValue(s.getDouble("scale"));
            }
            EntityEquipment eq = le.getEquipment();
            if (eq != null && s != null) {
                equip(eq, EquipmentSlot.HEAD, s.getString("equipment.head"));
                equip(eq, EquipmentSlot.CHEST, s.getString("equipment.chest"));
                equip(eq, EquipmentSlot.LEGS, s.getString("equipment.legs"));
                equip(eq, EquipmentSlot.FEET, s.getString("equipment.feet"));
                equip(eq, EquipmentSlot.HAND, s.getString("equipment.hand"));
                equip(eq, EquipmentSlot.OFF_HAND, s.getString("equipment.offhand"));
            }
        }
        if (ent instanceof Mob mob) mob.setAware(false);
        if (ent instanceof Ageable a) a.setAdult();
        if (ent instanceof Zombie z) z.setShouldBurnInDay(false);
        if (ent instanceof AbstractSkeleton sk) sk.setShouldBurnInDay(false);
        if (ent instanceof Villager v && s != null) {
            var prof = Registry.VILLAGER_PROFESSION.get(NamespacedKey.minecraft(s.getString("profession", "none").toLowerCase(Locale.ROOT)));
            if (prof != null) v.setProfession(prof);
            var vt = Registry.VILLAGER_TYPE.get(NamespacedKey.minecraft(s.getString("villager-type", "plains").toLowerCase(Locale.ROOT)));
            if (vt != null) v.setVillagerType(vt);
        }
    }

    private void equip(EntityEquipment eq, EquipmentSlot slot, String spec) {
        if (spec == null) return;
        ItemStack it = plugin.items().parse(spec);
        if (it == null) return;
        eq.setItem(slot, it);
        eq.setDropChance(slot, 0f);
    }

    /** Раз в 2 секунды: вернуть на место сдвинутых и поставить заново пропавших (если чанк загружен). */
    void tick() {
        for (Npc n : npcs.values()) {
            if (n.fancy != null) continue;
            World w = n.loc.getWorld();
            int cx = n.loc.getBlockX() >> 4, cz = n.loc.getBlockZ() >> 4;
            if (!w.isChunkLoaded(cx, cz) || !w.getChunkAt(cx, cz).isEntitiesLoaded()) continue;
            Entity e = n.entity == null ? null : Bukkit.getEntity(n.entity);
            if (e == null || e.isDead()) {
                Shops.Shop shop = plugin.shops().get(n.shop);
                if (shop == null) continue;
                spawn(n, shop, null);
                save();
            } else if (e.getLocation().distanceSquared(n.loc) > 0.01) {
                e.teleport(n.loc);
            }
        }
    }

    /**
     * Моб с меткой НПС, которого нет в списке (список потерялся) - возвращаем его в список:
     * магазин - по id без хвоста "_2", "_3". Тогда он снова открывается и удаляется через /itemnpc remove.
     */
    private Npc adopt(Entity e) {
        String id = e.getPersistentDataContainer().get(plugin.npcKey, PersistentDataType.STRING);
        if (id == null || npcs.containsKey(id) || pending.containsKey(id)) return null;
        String shopId = id.replaceFirst("_\\d+$", "");
        if (plugin.shops().get(shopId) == null) return null;
        Npc n = new Npc();
        n.id = id;
        n.shop = shopId;
        n.loc = e.getLocation().clone();
        n.loc.setPitch(0f);
        n.entity = e.getUniqueId();
        npcs.put(id, n);
        save();
        plugin.getLogger().info("НПС " + id + " возвращён в список (магазин " + shopId + ").");
        return n;
    }

    private Npc byEntity(Entity e) {
        String id = e.getPersistentDataContainer().get(plugin.npcKey, PersistentDataType.STRING);
        return id == null ? null : npcs.get(id);
    }

    private static boolean tagged(Entity e, NamespacedKey key) {
        return e.getPersistentDataContainer().has(key, PersistentDataType.STRING);
    }

    // ---------- магазин ----------

    void open(Player p, Shops.Shop shop) {
        // НПС, который выполняет команду из консоли (menu: "command:cases shop {player}" в shops.yml)
        String menu = shop.npc() == null ? null : shop.npc().getString("menu");
        if (menu != null && menu.regionMatches(true, 0, "command:", 0, 8)) {
            Bukkit.dispatchCommand(Bukkit.getConsoleSender(), menu.substring(8).trim().replace("{player}", p.getName()));
            return;
        }
        // НПС с меню вместо торговли (menu: titles в shops.yml)
        if (shop.npc() != null && "titles".equalsIgnoreCase(shop.npc().getString("menu"))) {
            plugin.titles().openMain(p);
            return;
        }
        if (shop.npc() != null && "textures".equalsIgnoreCase(shop.npc().getString("menu"))) {
            plugin.textures().open(p);
            return;
        }
        if (shop.npc() != null && "auction".equalsIgnoreCase(shop.npc().getString("menu"))) {
            plugin.auction().open(p);
            return;
        }
        Merchant m = Bukkit.createMerchant(Text.mm(shop.title()));
        m.setRecipes(Shops.recipes(shop));
        p.openMerchant(m, true);
        openShops.put(p.getUniqueId(), shop);
    }

    /**
     * Обмен, где нужен обычный предмет (например 16 железа), не должен съедать наши ресурсы
     * с той же основой (голова летучей мыши - это iron_ingot с моделью из пака).
     */
    @EventHandler(priority = EventPriority.LOW)
    public void onResultClick(InventoryClickEvent e) {
        if (!(e.getInventory() instanceof MerchantInventory mi) || e.getRawSlot() != 2) return;
        Shops.Shop shop = openShops.get(e.getWhoClicked().getUniqueId());
        if (shop == null) return;
        MerchantRecipe r = mi.getSelectedRecipe();
        if (r == null) return;
        int idx = mi.getSelectedRecipeIndex();
        if (idx >= 0 && idx < shop.trades().size() && shop.trades().get(idx).displayOnly()) {
            // строка-подсказка: такие вещи улучшаются в наковальне, у НПС их не выдаём
            e.setCancelled(true);
            e.getWhoClicked().sendMessage(Text.mm(plugin.msg("use-anvil")));
            return;
        }
        List<ItemStack> need = r.getIngredients();
        for (int i = 0; i < Math.min(2, need.size()); i++) {
            String want = plugin.items().idOf(need.get(i));
            String have = plugin.items().idOf(mi.getItem(i));
            if (want == null && have != null) {
                e.setCancelled(true);
                e.getWhoClicked().sendMessage(Text.mm(plugin.msg("wrong-ingredient")));
                return;
            }
        }
    }

    /** Бустеры сделаны на нитке - по ПКМ не ставим её на землю. */
    @EventHandler(ignoreCancelled = true)
    public void onPlace(BlockPlaceEvent e) {
        if (plugin.items().idOf(e.getItemInHand()) != null) e.setCancelled(true);
    }

    /** Талисманы сделаны на алмазной мотыге - не даём ими вспахивать землю. */
    @EventHandler(ignoreCancelled = true)
    public void onTill(org.bukkit.event.player.PlayerInteractEvent e) {
        if (e.getAction() != org.bukkit.event.block.Action.RIGHT_CLICK_BLOCK || e.getItem() == null) return;
        if (e.getItem().getType().name().endsWith("_HOE") && plugin.items().idOf(e.getItem()) != null) e.setCancelled(true);
    }

    /** Наши ресурсы нельзя переплавить/скрафтить как обычное железо. */
    @EventHandler
    public void onCraft(PrepareItemCraftEvent e) {
        for (ItemStack it : e.getInventory().getMatrix()) {
            if (plugin.items().idOf(it) != null) {
                e.getInventory().setResult(null);
                return;
            }
        }
    }

    @EventHandler
    public void onClose(InventoryCloseEvent e) {
        if (e.getInventory().getType() == InventoryType.MERCHANT) openShops.remove(e.getPlayer().getUniqueId());
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent e) {
        openShops.remove(e.getPlayer().getUniqueId());
    }

    // ---------- клики и защита ----------

    @EventHandler(priority = EventPriority.LOW)
    public void onInteract(PlayerInteractEntityEvent e) {
        Npc n = byEntity(e.getRightClicked());
        if (n == null) n = adopt(e.getRightClicked());
        if (n == null) {
            if (tagged(e.getRightClicked(), plugin.npcKey)) e.setCancelled(true);
            return;
        }
        e.setCancelled(true);
        if (e.getHand() != EquipmentSlot.HAND) return;
        clickOpen(e.getPlayer(), n);
    }

    @EventHandler(priority = EventPriority.LOW)
    public void onInteractAt(PlayerInteractAtEntityEvent e) {
        if (!tagged(e.getRightClicked(), plugin.npcKey)) return;
        e.setCancelled(true);
        Npc n = byEntity(e.getRightClicked());
        if (n == null) n = adopt(e.getRightClicked());
        if (n != null && e.getHand() == EquipmentSlot.HAND) clickOpen(e.getPlayer(), n);
    }

    private void clickOpen(Player p, Npc n) {
        long now = System.currentTimeMillis();
        Long last = lastOpen.get(p.getUniqueId());
        if (last != null && now - last < 300) return;
        lastOpen.put(p.getUniqueId(), now);
        Shops.Shop shop = plugin.shops().get(n.shop);
        if (shop == null) {
            plugin.getLogger().warning("НПС " + n.id + ": нет магазина '" + n.shop + "' в shops.yml");
            return;
        }
        try {
            open(p, shop);
        } catch (Exception ex) {
            plugin.getLogger().warning("НПС " + n.id + ": не удалось открыть " + shop.id() + ": " + ex);
        }
    }

    @EventHandler(priority = EventPriority.LOW)
    public void onDamage(EntityDamageEvent e) {
        if (!tagged(e.getEntity(), plugin.npcKey)) return;
        e.setCancelled(true);
        if (e instanceof EntityDamageByEntityEvent by && by.getDamager() instanceof Player p) {
            Npc n = byEntity(e.getEntity());
            Shops.Shop shop = n == null ? null : plugin.shops().get(n.shop);
            if (shop != null) open(p, shop);
        }
    }

    @EventHandler
    public void onCombust(EntityCombustEvent e) {
        if (tagged(e.getEntity(), plugin.npcKey)) e.setCancelled(true);
    }

    @EventHandler
    public void onTeleport(EntityTeleportEvent e) {
        if (tagged(e.getEntity(), plugin.npcKey)) e.setCancelled(true);
    }

    @EventHandler
    public void onTarget(EntityTargetEvent e) {
        if (tagged(e.getEntity(), plugin.npcKey)) e.setCancelled(true);
    }

    @EventHandler
    public void onTransform(EntityTransformEvent e) {
        if (tagged(e.getEntity(), plugin.npcKey)) e.setCancelled(true);
    }

    /** Лишние копии (после удаления НПС или сбоя) убираем при загрузке чанка. */
    @EventHandler
    public void onEntitiesLoad(EntitiesLoadEvent e) {
        for (Entity ent : e.getEntities()) {
            if (!tagged(ent, plugin.npcKey)) continue;
            Npc n = byEntity(ent);
            if (n == null) {
                adopt(ent);
                continue;
            }
            if (n.entity != null && !n.entity.equals(ent.getUniqueId()) && n.fancy == null) ent.remove();
        }
    }

    /** Ближайший НПС в радиусе. */
    Npc nearest(Location l, double radius) {
        Npc best = null;
        double bd = radius * radius;
        for (Npc n : npcs.values()) {
            if (n.loc.getWorld() != l.getWorld()) continue;
            double d = n.loc.distanceSquared(l);
            if (d <= bd) {
                bd = d;
                best = n;
            }
        }
        return best;
    }
}
