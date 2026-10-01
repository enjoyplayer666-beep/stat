package ru.dscraft.mediaeconomy;

import io.papermc.paper.plugin.lifecycle.event.LifecycleEventManager;
import org.bukkit.Server;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.PluginCommand;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.generator.BiomeProvider;
import org.bukkit.generator.ChunkGenerator;
import org.bukkit.plugin.InvalidDescriptionException;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.PluginDescriptionFile;
import org.bukkit.plugin.PluginLoader;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.StringReader;
import java.net.URL;
import java.net.URLConnection;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.List;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Часть объединённого плагина: бывший отдельный плагин, который теперь живёт внутри другого jar.
 * Для кода модуля всё как раньше (getConfig, getDataFolder, getCommand, события, задачи), только
 * папка - plugins/&lt;общий плагин&gt;/&lt;имя модуля&gt;/, а ресурсы лежат в jar в папке &lt;имя модуля&gt;/.
 * Имя модуля (= старое имя плагина) сохраняет ключи предметов и данных (NamespacedKey).
 */
public abstract class Module implements Plugin {

    private JavaPlugin host;
    private String name;
    private File dataFolder;
    private File configFile;
    private PluginDescriptionFile description;
    private Logger logger;
    private FileConfiguration config;
    boolean enabled;

    /** Кто сейчас создаётся (Modules.enable): имя и папка нужны уже в полях класса модуля. */
    static final ThreadLocal<Object[]> PENDING = new ThreadLocal<>();

    protected Module() {
        Object[] p = PENDING.get();
        if (p == null) throw new IllegalStateException("Модуль создаётся только через Modules.enable");
        attach((JavaPlugin) p[0], (String) p[1], (File) p[2]);
    }

    private void attach(JavaPlugin host, String name, File dataFolder) {
        this.host = host;
        this.name = name;
        this.dataFolder = dataFolder;
        this.configFile = new File(dataFolder, "config.yml");
        this.logger = Logger.getLogger(name);
        try {
            this.description = new PluginDescriptionFile(new StringReader("name: " + name
                    + "\nversion: '" + host.getPluginMeta().getVersion() + "'\nmain: " + getClass().getName()
                    + "\napi-version: '1.21'\n"));
        } catch (InvalidDescriptionException e) {
            throw new IllegalStateException(e);
        }
    }

    /** Общий плагин, в котором живёт модуль. */
    public final JavaPlugin getHost() {
        return host;
    }

    public PluginCommand getCommand(String name) {
        return host.getCommand(name);
    }

    @Override
    public File getDataFolder() {
        return dataFolder;
    }

    @Override
    public PluginDescriptionFile getDescription() {
        return description;
    }

    @SuppressWarnings("UnstableApiUsage")
    public io.papermc.paper.plugin.configuration.PluginMeta getPluginMeta() {
        return description;
    }

    @Override
    public FileConfiguration getConfig() {
        if (config == null) reloadConfig();
        return config;
    }

    @Override
    public void reloadConfig() {
        YamlConfiguration loaded = YamlConfiguration.loadConfiguration(configFile);
        InputStream in = getResource("config.yml");
        if (in != null) {
            loaded.setDefaults(YamlConfiguration.loadConfiguration(new InputStreamReader(in, StandardCharsets.UTF_8)));
        }
        config = loaded;
    }

    @Override
    public void saveConfig() {
        try {
            getConfig().save(configFile);
        } catch (IOException e) {
            getLogger().log(Level.SEVERE, "Could not save config to " + configFile, e);
        }
    }

    @Override
    public void saveDefaultConfig() {
        if (!configFile.exists()) saveResource("config.yml", false);
    }

    @Override
    public void saveResource(String resourcePath, boolean replace) {
        if (resourcePath == null || resourcePath.isEmpty()) {
            throw new IllegalArgumentException("ResourcePath cannot be null or empty");
        }
        resourcePath = resourcePath.replace('\\', '/');
        InputStream in = getResource(resourcePath);
        if (in == null) {
            throw new IllegalArgumentException("The embedded resource '" + resourcePath + "' cannot be found in " + name);
        }
        File out = new File(dataFolder, resourcePath);
        try (in) {
            out.getParentFile().mkdirs();
            if (!out.exists() || replace) Files.copy(in, out.toPath(), StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
            getLogger().log(Level.SEVERE, "Could not save " + out.getName() + " to " + out, e);
        }
    }

    @Override
    public InputStream getResource(String filename) {
        try {
            URL url = getClass().getClassLoader().getResource(name + "/" + filename);
            if (url == null) return null;
            URLConnection connection = url.openConnection();
            connection.setUseCaches(false);
            return connection.getInputStream();
        } catch (IOException e) {
            return null;
        }
    }

    @Override
    public PluginLoader getPluginLoader() {
        return host.getPluginLoader();
    }

    @Override
    public Server getServer() {
        return host.getServer();
    }

    @Override
    public boolean isEnabled() {
        return enabled && host.isEnabled();
    }

    @Override
    public void onDisable() {
    }

    @Override
    public void onLoad() {
    }

    @Override
    public void onEnable() {
    }

    @Override
    public boolean isNaggable() {
        return host.isNaggable();
    }

    @Override
    public void setNaggable(boolean canNag) {
        host.setNaggable(canNag);
    }

    @Override
    public ChunkGenerator getDefaultWorldGenerator(String worldName, String id) {
        return null;
    }

    @Override
    public BiomeProvider getDefaultBiomeProvider(String worldName, String id) {
        return null;
    }

    @Override
    public Logger getLogger() {
        return logger;
    }

    @Override
    public String getName() {
        return name;
    }

    @SuppressWarnings("UnstableApiUsage")
    public LifecycleEventManager<Plugin> getLifecycleManager() {
        return host.getLifecycleManager();
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        return false;
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        return null;
    }

    @Override
    public String toString() {
        return name;
    }
}
