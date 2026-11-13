package org.jestron.event;

import org.bukkit.Bukkit;
import org.bukkit.NamespacedKey;
import org.bukkit.command.PluginCommand;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;
import org.jestron.event.command.EventCommand;
import org.jestron.event.game.EventManager;
import org.jestron.event.listener.BlockListener;
import org.jestron.event.listener.PlayerListener;
import org.jestron.event.listener.WandListener;
import org.jestron.event.selection.SelectionService;
import org.jestron.event.storage.DataStore;
import org.jestron.event.util.Messages;
import org.jestron.event.util.PlayerUtil;
import org.jestron.event.util.Settings;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;

/**
 * Event - Otomatik zamanlanmış 1v1 turnuva sistemi.
 *
 * @author Jestron
 */
public final class EventPlugin extends JavaPlugin {

    private static EventPlugin instance;

    private final Messages messages = new Messages();
    private Settings settings;
    private DataStore store;
    private SelectionService selection;
    private EventManager manager;

    public static EventPlugin get() {
        return instance;
    }

    @Override
    public void onEnable() {
        instance = this;
        saveDefaultConfig();
        mergeConfigDefaults();

        settings = new Settings(getConfig());
        messages.load(getConfig());
        PlayerUtil.init(new NamespacedKey(this, "event_kit"));
        store = new DataStore(this);
        store.load();

        selection = new SelectionService(this);
        manager = new EventManager(this);

        Bukkit.getPluginManager().registerEvents(new PlayerListener(this), this);
        Bukkit.getPluginManager().registerEvents(new BlockListener(this), this);
        Bukkit.getPluginManager().registerEvents(new WandListener(this), this);

        PluginCommand cmd = getCommand("event");
        if (cmd != null) {
            EventCommand ec = new EventCommand(this);
            cmd.setExecutor(ec);
            cmd.setTabCompleter(ec);
        }

        manager.start();

        // /reload veya çökme sonrası: üzerinde kalmış kit eşyalarını sil
        for (Player p : Bukkit.getOnlinePlayers()) manager.handleJoin(p);

        getLogger().info("Event v" + getDescription().getVersion() + " aktif | " + store.events.size()
                + " event saati | WorldEdit: " + (selection.hasWorldEdit() ? "var" : "yok (/event wand)")
        );
    }

    @Override
    public void onDisable() {
        if (manager != null) manager.shutdown();
        if (store != null) store.saveNow();
        instance = null;
    }

    private void mergeConfigDefaults() {
        try (InputStream in = getResource("config.yml")) {
            if (in == null) return;
            YamlConfiguration def = YamlConfiguration.loadConfiguration(new InputStreamReader(in, StandardCharsets.UTF_8));
            getConfig().setDefaults(def);
            getConfig().options().copyDefaults(true);
            saveConfig();
        } catch (Exception e) {
            getLogger().warning("config.yml varsayilanlari birlestirilemedi: " + e.getMessage());
        }
    }

    public void reloadPlugin() {
        reloadConfig();
        mergeConfigDefaults();
        settings = new Settings(getConfig());
        messages.load(getConfig());
        if (manager.session() == null) store.load();
    }

    public Messages messages() {
        return messages;
    }

    public DataStore store() {
        return store;
    }

    public Settings settings() {
        return settings;
    }

    public SelectionService selection() {
        return selection;
    }

    public EventManager manager() {
        return manager;
    }
}
