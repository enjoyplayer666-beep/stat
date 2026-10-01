package ru.dscraft.mediacoins;

import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.PluginCommand;
import org.bukkit.command.TabCompleter;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/** MediaCoins - коины игроков (plugins/MediaCoins/coins.yml). */
public final class MediaCoinsPlugin extends JavaPlugin implements TabCompleter {

    private static final MiniMessage MM = MiniMessage.miniMessage();

    private final Map<UUID, Long> balances = new ConcurrentHashMap<>();
    private File file;
    private volatile boolean dirty;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        getConfig().options().copyDefaults(true);
        saveConfig();
        file = new File(getDataFolder(), "coins.yml");
        if (file.exists()) load();
        else migrateFromLobby();
        CoinsApi.init(this);
        for (String name : new String[]{"coins", "coinsgive"}) {
            PluginCommand pc = getCommand(name);
            if (pc != null) {
                pc.setExecutor(this);
                pc.setTabCompleter(this);
            }
        }
        // /coins раньше был у DestroyLobby - забираем себе
        Bukkit.getScheduler().runTask(this, () -> {
            PluginCommand ours = getCommand("coins");
            if (ours != null) Bukkit.getCommandMap().getKnownCommands().put("coins", ours);
        });
        Bukkit.getScheduler().runTaskTimer(this, () -> {
            if (dirty) save();
        }, 20L * 30, 20L * 30);
    }

    @Override
    public void onDisable() {
        save();
    }

    // ---------------- данные ----------------

    long get(UUID uuid) {
        return balances.getOrDefault(uuid, 0L);
    }

    void set(UUID uuid, long amount) {
        balances.put(uuid, Math.max(0, amount));
        dirty = true;
    }

    void add(UUID uuid, long amount) {
        set(uuid, get(uuid) + amount);
    }

    synchronized boolean take(UUID uuid, long amount) {
        long have = get(uuid);
        if (have < amount) return false;
        set(uuid, have - amount);
        return true;
    }

    private void load() {
        YamlConfiguration yml = YamlConfiguration.loadConfiguration(file);
        for (String key : yml.getKeys(false)) {
            try {
                balances.put(UUID.fromString(key), Math.max(0, yml.getLong(key)));
            } catch (IllegalArgumentException ignored) {
            }
        }
    }

    synchronized void save() {
        YamlConfiguration yml = new YamlConfiguration();
        for (Map.Entry<UUID, Long> e : balances.entrySet()) {
            if (e.getValue() > 0) yml.set(e.getKey().toString(), e.getValue());
        }
        try {
            getDataFolder().mkdirs();
            yml.save(file);
            dirty = false;
        } catch (IOException e) {
            getLogger().warning("Не удалось сохранить coins.yml: " + e.getMessage());
        }
    }

    /** Первый запуск: коины, которые хранил DestroyLobby (plugins/DestroyLobby/playerdata.yml). */
    private void migrateFromLobby() {
        File old = new File(getDataFolder().getParentFile(), "DestroyLobby/playerdata.yml");
        if (old.exists()) {
            YamlConfiguration yml = YamlConfiguration.loadConfiguration(old);
            int n = 0;
            for (String key : yml.getKeys(false)) {
                long coins = yml.getLong(key + ".coins", 0);
                if (coins <= 0) continue;
                try {
                    balances.put(UUID.fromString(key), coins);
                    n++;
                } catch (IllegalArgumentException ignored) {
                }
            }
            getLogger().info("Перенесены коины из DestroyLobby: " + n + " игроков.");
        }
        save();
    }

    // ---------------- команды ----------------

    private void msg(CommandSender to, String key, TagResolver... resolvers) {
        to.sendMessage(MM.deserialize(getConfig().getString("messages." + key, key), resolvers));
    }

    private static OfflinePlayer find(String name) {
        Player online = Bukkit.getPlayerExact(name);
        if (online != null) return online;
        return Bukkit.getOfflinePlayerIfCached(name);
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        boolean admin = sender.hasPermission("mediacoins.admin");
        if (command.getName().equalsIgnoreCase("coinsgive")) {
            if (args.length < 2) {
                sender.sendMessage("§7/coinsgive <ник> <количество>");
                return true;
            }
            change(sender, "give", args[0], args[1]);
            return true;
        }
        // /coins give|take|set ник сумма - только опы
        if (admin && args.length >= 3 && List.of("give", "take", "set").contains(args[0].toLowerCase(Locale.ROOT))) {
            change(sender, args[0].toLowerCase(Locale.ROOT), args[1], args[2]);
            return true;
        }
        if (admin && args.length >= 1 && !args[0].equalsIgnoreCase("money")) {
            OfflinePlayer target = find(args[0]);
            if (target != null) {
                msg(sender, "balance", Placeholder.unparsed("amount", String.valueOf(get(target.getUniqueId()))));
                return true;
            }
        }
        if (sender instanceof Player p) {
            msg(p, "balance", Placeholder.unparsed("amount", String.valueOf(get(p.getUniqueId()))));
        }
        return true;
    }

    private void change(CommandSender sender, String action, String name, String rawAmount) {
        if (!sender.hasPermission("mediacoins.admin")) return;
        OfflinePlayer target = find(name);
        if (target == null) {
            msg(sender, "not-found", Placeholder.unparsed("player", name));
            return;
        }
        long amount;
        try {
            amount = Long.parseLong(rawAmount);
        } catch (NumberFormatException e) {
            amount = -1;
        }
        if (amount <= 0 && !(action.equals("set") && amount == 0)) {
            msg(sender, "bad-number");
            return;
        }
        UUID id = target.getUniqueId();
        switch (action) {
            case "give" -> add(id, amount);
            case "take" -> set(id, Math.max(0, get(id) - amount));
            default -> set(id, amount);
        }
        save();
        String shown = target.getName() != null ? target.getName() : name;
        msg(sender, "given", Placeholder.unparsed("player", shown), Placeholder.unparsed("amount", String.valueOf(amount)),
                Placeholder.unparsed("balance", String.valueOf(get(id))));
        if (action.equals("give") && target.getPlayer() != null && target.getPlayer() != sender) {
            msg(target.getPlayer(), "received", Placeholder.unparsed("amount", String.valueOf(amount)));
        }
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        List<String> out = new ArrayList<>();
        boolean admin = sender.hasPermission("mediacoins.admin");
        if (command.getName().equalsIgnoreCase("coinsgive")) {
            if (admin && args.length == 1) for (Player p : Bukkit.getOnlinePlayers()) out.add(p.getName());
        } else if (args.length == 1) {
            out.add("money");
            if (admin) out.addAll(List.of("give", "take", "set"));
        } else if (admin && args.length == 2) {
            for (Player p : Bukkit.getOnlinePlayers()) out.add(p.getName());
        }
        String prefix = args.length == 0 ? "" : args[args.length - 1].toLowerCase(Locale.ROOT);
        out.removeIf(s -> !s.toLowerCase(Locale.ROOT).startsWith(prefix));
        return out;
    }
}
