package ru.dscraft.mediaclear;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.World;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Item;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.List;

/** Очистка предметов с земли раз в interval-seconds с предупреждениями; лобби не трогается. */
public class MediaClearPlugin extends JavaPlugin {

    private static final LegacyComponentSerializer LEGACY = LegacyComponentSerializer.builder()
            .character('&').hexColors().build();

    private int left;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        left = interval();
        Bukkit.getScheduler().runTaskTimer(this, this::tick, 20L, 20L);
    }

    private int interval() {
        return Math.max(10, getConfig().getInt("interval-seconds", 300));
    }

    private boolean excluded(World w) {
        List<String> ex = getConfig().getStringList("excluded-worlds");
        for (String s : ex) if (s.equalsIgnoreCase(w.getName())) return true;
        return false;
    }

    private void tick() {
        left--;
        String warn = getConfig().getString("warnings." + left);
        if (warn != null && left > 0) broadcast(warn.replace("{time}", String.valueOf(left)));
        if (left <= 0) {
            clear();
            left = interval();
        }
    }

    private void clear() {
        for (World w : Bukkit.getWorlds()) {
            if (excluded(w)) continue;
            for (Item item : w.getEntitiesByClass(Item.class)) item.remove();
        }
        broadcast(getConfig().getString("done", "&#E51510◆ &fОчистка завершена!"));
    }

    /** Только игрокам не в лобби. */
    private void broadcast(String text) {
        Component c = LEGACY.deserialize(text);
        for (Player p : Bukkit.getOnlinePlayers()) {
            if (!excluded(p.getWorld())) p.sendMessage(c);
        }
    }

    @Override
    public boolean onCommand(CommandSender sender, Command cmd, String label, String[] args) {
        if (args.length > 0 && args[0].equalsIgnoreCase("reload")) {
            reloadConfig();
            left = interval();
            sender.sendMessage(LEGACY.deserialize("&aMediaClear перезагружен, следующая очистка через " + left + " сек."));
            return true;
        }
        if (args.length > 0 && args[0].equalsIgnoreCase("now")) {
            left = Math.min(left, 31); // сразу пойдут предупреждения за 30 секунд
            sender.sendMessage(LEGACY.deserialize("&aОчистка через 30 секунд."));
            return true;
        }
        sender.sendMessage(LEGACY.deserialize("&e/mediaclear now &7- очистка через 30 сек.  &e/mediaclear reload"));
        return true;
    }
}
