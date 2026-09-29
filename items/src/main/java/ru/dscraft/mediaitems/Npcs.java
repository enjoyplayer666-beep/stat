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

    Npcs(MediaItemsPlugin plugin) {
        this.plugin = plugin;
        this.file = new File(plugin.getDataFolder(), "npcs.yml");
    }

    // ---------- хранение ----------

    void load() {
        npcs.clear();
        YamlConfiguration y = YamlConfiguration.loadConfiguration(file);
        ConfigurationSection sec = y.getConfigurationSection("npcs");
        if (sec == null) return;
        for (String id : sec.getKeys(false)) {
            ConfigurationSection s = sec.getConfigurationSection(id);
            World w = Bukkit.getWorld(s.getString("world", "world"));
            if (w == null) {
                plugin.getLogger().warning("НПС " + id + ": мир " + s.getString("world") + " не загружен");
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
        try {
            y.save(file);
        } catch (IOException e) {
            plugin.getLogger().warning("Не удалось сохранить npcs.yml: " + e.getMessage());
        }
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
            if (shop == null || !n.loc.isChunkLoaded()) continue;
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
        if (type.equals("PLAYER") && fancyOk && !skin.isBlank() && creator != null) {
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
        creator.performCommand("npc skin " + name + " " + skin);
        creator.performCommand("npc displayname " + name + " " + shop.name());
        creator.performCommand("npc turn_to_player " + name + " true");
        creator.performCommand("npc action " + name + " ANY_CLICK add console_command itemnpc open {player} " + shop.id());
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

    private Npc byEntity(Entity e) {
        String id = e.getPersistentDataContainer().get(plugin.npcKey, PersistentDataType.STRING);
        return id == null ? null : npcs.get(id);
    }

    private static boolean tagged(Entity e, NamespacedKey key) {
        return e.getPersistentDataContainer().has(key, PersistentDataType.STRING);
    }

    // ---------- магазин ----------

    void open(Player p, Shops.Shop shop) {
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
        if (!openShops.containsKey(e.getWhoClicked().getUniqueId())) return;
        MerchantRecipe r = mi.getSelectedRecipe();
        if (r == null) return;
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
        if (n == null) {
            if (tagged(e.getRightClicked(), plugin.npcKey)) e.setCancelled(true);
            return;
        }
        e.setCancelled(true);
        if (e.getHand() != EquipmentSlot.HAND) return;
        Shops.Shop shop = plugin.shops().get(n.shop);
        if (shop != null) open(e.getPlayer(), shop);
    }

    @EventHandler(priority = EventPriority.LOW)
    public void onInteractAt(PlayerInteractAtEntityEvent e) {
        if (tagged(e.getRightClicked(), plugin.npcKey)) e.setCancelled(true);
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
            if (n == null || (n.entity != null && !n.entity.equals(ent.getUniqueId()))) ent.remove();
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
