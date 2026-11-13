package org.jestron.event.storage;

import org.bukkit.Material;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.inventory.ItemStack;
import org.jestron.event.model.EventDef;
import org.jestron.event.model.Pos;
import org.jestron.event.model.Region;
import org.jestron.event.model.Room;
import org.jestron.event.util.TimeUtil;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.Map;
import java.util.TreeMap;
import java.util.logging.Level;
import java.util.logging.Logger;

/** data.yml : hub, event saatleri, alanlar, spawnlar, kitler ve odalar. */
public final class DataStore {

    private final File file;
    private final Logger log;
    public Pos hub;
    /** "20:00" -> EventDef (saate göre sıralı) */
    public final Map<String, EventDef> events = new TreeMap<>();

    public DataStore(File file, Logger log) {
        this.file = file;
        this.log = log;
    }

    public EventDef event(String time) {
        return time == null ? null : events.get(time);
    }

    public void load() {
        events.clear();
        hub = null;
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
                log.warning("data.yml: gecersiz event saati atlandi: " + key);
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
                def.kit = arr;
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
                    def.rooms.put(room.key(), room);
                }
            }
            events.put(time, def);
        }
    }

    public synchronized void save() {
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
        try {
            File parent = file.getParentFile();
            if (parent != null && !parent.exists()) parent.mkdirs();
            File tmp = new File(file.getParentFile(), file.getName() + ".tmp");
            y.save(tmp);
            Files.move(tmp.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
            log.log(Level.SEVERE, "data.yml kaydedilemedi", e);
        }
    }
}
