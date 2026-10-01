package ru.dscraft.mediaeconomy;

import org.bukkit.Bukkit;
import org.bukkit.command.PluginCommand;
import org.bukkit.event.HandlerList;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;
import java.util.logging.Level;
import java.util.stream.Stream;

/**
 * Запуск модулей объединённого плагина. Ошибка одного модуля выключает только его, остальные работают.
 * Первый запуск: папка старого плагина plugins/&lt;имя&gt;/ копируется в plugins/&lt;общий&gt;/&lt;имя&gt;/
 * (старая остаётся как резервная копия - её можно удалить).
 */
public final class Modules {

    private final JavaPlugin host;
    private final List<Module> started = new ArrayList<>();

    public Modules(JavaPlugin host) {
        this.host = host;
    }

    /** @param commands команды модуля из его старого plugin.yml - без своего обработчика уходят модулю */
    public void enable(Supplier<? extends Module> factory, String name, String... commands) {
        File folder = new File(host.getDataFolder(), name);
        File old = new File(host.getDataFolder().getParentFile(), name);
        if (!folder.exists() && old.isDirectory()) {
            try {
                copy(old.toPath(), folder.toPath());
                host.getLogger().info(name + ": настройки и данные перенесены из plugins/" + name
                        + " в plugins/" + host.getName() + "/" + name + " (старую папку можно удалить).");
            } catch (IOException e) {
                host.getLogger().log(Level.SEVERE, name + ": не удалось перенести plugins/" + name, e);
            }
        }
        Module module;
        Module.PENDING.set(new Object[]{host, name, folder});
        try {
            module = factory.get();
        } catch (Throwable t) {
            host.getLogger().log(Level.SEVERE, name + " не запустился - этот модуль выключен, остальные работают", t);
            return;
        } finally {
            Module.PENDING.remove();
        }
        module.enabled = true;
        try {
            module.onLoad();
            module.onEnable();
        } catch (Throwable t) {
            host.getLogger().log(Level.SEVERE, name + " не запустился - этот модуль выключен, остальные работают", t);
            stop(module);
            return;
        }
        for (String command : commands) {
            PluginCommand pc = host.getCommand(command);
            if (pc != null && pc.getExecutor() == host) pc.setExecutor(module);
        }
        started.add(module);
        host.getLogger().info(name + " включён.");
    }

    public void disableAll() {
        for (int i = started.size() - 1; i >= 0; i--) {
            Module module = started.get(i);
            try {
                module.onDisable();
            } catch (Throwable t) {
                host.getLogger().log(Level.SEVERE, module.getName() + ": ошибка при выключении", t);
            }
            stop(module);
        }
        started.clear();
    }

    private static void stop(Module module) {
        module.enabled = false;
        HandlerList.unregisterAll(module);
        Bukkit.getScheduler().cancelTasks(module);
    }

    private static void copy(Path from, Path to) throws IOException {
        try (Stream<Path> paths = Files.walk(from)) {
            for (Path p : (Iterable<Path>) paths::iterator) {
                Path target = to.resolve(from.relativize(p).toString());
                if (Files.isDirectory(p)) Files.createDirectories(target);
                else Files.copy(p, target, StandardCopyOption.REPLACE_EXISTING);
            }
        }
    }
}
