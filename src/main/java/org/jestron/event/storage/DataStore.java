package org.jestron.event.storage;

import org.bukkit.Material;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.Plugin;
import org.jestron.event.model.EventDef;
import org.jestron.event.model.Pos;
import org.jestron.event.model.Region;
import org.jestron.event.model.Room;
import org.jestron.event.util.TimeUtil;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;

/**
 * data.yml (hub, event saatleri, alan, spawn, kit, odalar) ve history.yml (son eventler).
 * Kayıt: içerik ana thread'de metne çevrilir, diske yazma arka planda yapılır (sunucu donmaz).
 */
public final class DataStore {

    private static final int HISTORY_LIMIT = 20;

    private final Plugin plugin;
    /** Tek thread: kayıtlar sırayla yazılır, eski içerik yenisinin üstüne yazılamaz. */
    private final ExecutorService io = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "Event-IO");
        t.setDaemon(true);
        return t;
    });
    private final File file;
    private final File historyFile;
    public Pos hub;
    /** "20:00" -> EventDef (saate göre sıralı) */
    public final Map<String, EventDef> events = new TreeMap<>();
    /** [saat, zaman(ms), oyuncu sayısı, kazanan] */
    public final List<String[]> history = new ArrayList<>();

    public DataStore(Plugin plugin) {
        this.plugin = plugin;
        this.file = new File(plugin.getDataFolder(), "data.yml");
        this.historyFile = new File(plugin.getDataFolder(), "history.yml");
    }

    public EventDef event(String time) {
        return time == null ? null : events.get(time);
    }

    /** Oda isimleri tüm eventlerde benzersizdir. [EventDef, Room] ya da null. */
    public Object[] findRoom(String name) {
        for (EventDef d : events.values()) {
            Room r = d.room(name);
            if (r != null) return new Object[]{d, r};
        }
        return null;
    }

    public void load() {
        events.clear();
        hub = null;
        history.clear();
        if (historyFile.exists()) {
            YamlConfiguration h = YamlConfiguration.loadConfiguration(historyFile);
            for (String line : h.getStringList("history")) {
                String[] p = line.split("\\|", -1);
                if (p.length == 4) history.add(p);
            }
        }
        if (!file.exists()) return;
        YamlConfiguration y = YamlConfiguration.loadConfiguration(file);
        hub = Pos.load(y.getConfigurationSection("hub"));
        ConfigurationSection ev = y.getConfigurationSection("events");
        if (ev == null) return;
        for (String key : ev.getKeys(false)) {
            ConfigurationSection s = ev.getConfigurationSection(key);
            if (s == null) continue;
            String time = TimeUtil.parseTime(s.getString("time", TimeUtil.fromKey(key)));
            if (time == null) {
                plugin.getLogger().warning("data.yml: gecersiz event saati atlandi: " + key);
                continue;
            }
            EventDef def = new EventDef(time);
            def.area = Region.load(s.getConfigurationSection("area"));
            def.spawn = Pos.load(s.getConfigurationSection("spawn"));
            def.lastRunDate = s.getString("last-run");
            ConfigurationSection k = s.getConfigurationSection("kit");
            if (k != null) {
                int size = Math.max(41, s.getInt("kit-size", 41));
                ItemStack[] arr = new ItemStack[size];
                for (String slot : k.getKeys(false)) {
                    try {
                        int i = Integer.parseInt(slot);
                        if (i >= 0 && i < size) arr[i] = k.getItemStack(slot);
                    } catch (NumberFormatException ignored) {
                    }
                }
                def.setKit(arr);
            }
            ConfigurationSection rooms = s.getConfigurationSection("rooms");
            if (rooms != null) {
                for (String rk : rooms.getKeys(false)) {
                    ConfigurationSection rs = rooms.getConfigurationSection(rk);
                    if (rs == null) continue;
                    Region reg = Region.load(rs.getConfigurationSection("region"));
                    if (reg == null) continue;
                    Room room = new Room(rs.getString("name", rk), reg,
                            Pos.load(rs.getConfigurationSection("spawn1")),
                            Pos.load(rs.getConfigurationSection("spawn2")));
                    if (findRoom(room.name) != null) {
                        plugin.getLogger().warning("data.yml: ayni isimde oda atlandi: " + room.name);
                        continue;
                    }
                    def.rooms.put(room.key(), room);
                }
            }
            events.put(time, def);
        }
    }

    private String serialize() {
        YamlConfiguration y = new YamlConfiguration();
        if (hub != null) hub.save(y.createSection("hub"));
        ConfigurationSection ev = y.createSection("events");
        for (EventDef def : events.values()) {
            ConfigurationSection s = ev.createSection(def.key());
            s.set("time", def.time);
            if (def.lastRunDate != null) s.set("last-run", def.lastRunDate);
            if (def.area != null) def.area.save(s.createSection("area"));
            if (def.spawn != null) def.spawn.save(s.createSection("spawn"));
            if (def.kit != null) {
                s.set("kit-size", def.kit.length);
                ConfigurationSection k = s.createSection("kit");
                for (int i = 0; i < def.kit.length; i++) {
                    ItemStack it = def.kit[i];
                    if (it != null && it.getType() != Material.AIR) k.set(String.valueOf(i), it);
                }
            }
            ConfigurationSection rooms = s.createSection("rooms");
            for (Room r : def.rooms.values()) {
                ConfigurationSection rs = rooms.createSection(r.key());
                rs.set("name", r.name);
                r.region.save(rs.createSection("region"));
                if (r.spawn1 != null) r.spawn1.save(rs.createSection("spawn1"));
                if (r.spawn2 != null) r.spawn2.save(rs.createSection("spawn2"));
            }
        }
        return y.saveToString();
    }

    private String serializeHistory() {
        YamlConfiguration h = new YamlConfiguration();
        List<String> lines = new ArrayList<>();
        for (String[] r : history) lines.add(String.join("|", r));
        h.set("history", lines);
        return h.saveToString();
    }

    public void addHistory(String time, int players, String winner) {
        history.add(0, new String[]{time, String.valueOf(System.currentTimeMillis()), String.valueOf(players),
                winner == null ? "-" : winner});
        while (history.size() > HISTORY_LIMIT) history.remove(history.size() - 1);
        write(historyFile, serializeHistory(), true);
    }

    /** Arka planda kaydet. */
    public void save() {
        write(file, serialize(), true);
    }

    /** Hemen kaydet (kapanışta). */
    public void saveNow() {
        close();
        write(file, serialize(), false);
        write(historyFile, serializeHistory(), false);
    }

    private void write(File target, String content, boolean async) {
        if (async && !io.isShutdown()) io.execute(() -> writeFile(target, content));
        else writeFile(target, content);
    }

    /** Bekleyen yazmaları bitirip IO thread'ini kapatır. */
    public void close() {
        io.shutdown();
        try {
            io.awaitTermination(5, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private synchronized void writeFile(File target, String content) {
        try {
            File parent = target.getParentFile();
            if (parent != null && !parent.exists()) parent.mkdirs();
            File tmp = new File(parent, target.getName() + ".tmp");
            Files.write(tmp.toPath(), content.getBytes(StandardCharsets.UTF_8));
            Files.move(tmp.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
            plugin.getLogger().log(Level.SEVERE, target.getName() + " kaydedilemedi", e);
        }
    }
}
