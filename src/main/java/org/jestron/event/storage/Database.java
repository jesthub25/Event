package org.jestron.event.storage;

import java.io.File;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * SQLite (sunucuda gömülü sürücü kullanılır, ek kütüphane gerekmez).
 * backups : evente giren oyuncuların envanter/durum yedeği (çökme / çıkışta bile kaybolmaz)
 * history : biten eventlerin sonuçları
 */
public final class Database {

    private final Logger log;
    private Connection conn;

    public Database(Logger log) {
        this.log = log;
    }

    public synchronized boolean connect(File file) {
        try {
            File parent = file.getParentFile();
            if (parent != null && !parent.exists()) parent.mkdirs();
            try {
                Class.forName("org.sqlite.JDBC");
            } catch (ClassNotFoundException ignored) {
                // DriverManager yine de bulabilir
            }
            conn = DriverManager.getConnection("jdbc:sqlite:" + file.getAbsolutePath());
            try (Statement st = conn.createStatement()) {
                st.execute("PRAGMA journal_mode=WAL");
                st.execute("PRAGMA synchronous=NORMAL");
                st.execute("CREATE TABLE IF NOT EXISTS backups ("
                        + "uuid TEXT PRIMARY KEY, name TEXT NOT NULL, data TEXT NOT NULL, created INTEGER NOT NULL)");
                st.execute("CREATE TABLE IF NOT EXISTS history ("
                        + "id INTEGER PRIMARY KEY AUTOINCREMENT, event_time TEXT NOT NULL, played_at INTEGER NOT NULL, "
                        + "participants INTEGER NOT NULL, winner TEXT, results TEXT NOT NULL)");
            }
            return true;
        } catch (SQLException e) {
            log.log(Level.SEVERE, "Veritabani baglantisi kurulamadi", e);
            return false;
        }
    }

    public synchronized void close() {
        if (conn != null) {
            try {
                conn.close();
            } catch (SQLException ignored) {
            }
            conn = null;
        }
    }

    public synchronized boolean saveBackup(UUID uuid, String name, String data) {
        if (conn == null) return false;
        try (PreparedStatement ps = conn.prepareStatement(
                "INSERT OR REPLACE INTO backups(uuid, name, data, created) VALUES(?,?,?,?)")) {
            ps.setString(1, uuid.toString());
            ps.setString(2, name);
            ps.setString(3, data);
            ps.setLong(4, System.currentTimeMillis());
            return ps.executeUpdate() > 0;
        } catch (SQLException e) {
            log.log(Level.SEVERE, "Yedek kaydedilemedi: " + name, e);
            return false;
        }
    }

    public synchronized String getBackup(UUID uuid) {
        if (conn == null) return null;
        try (PreparedStatement ps = conn.prepareStatement("SELECT data FROM backups WHERE uuid=?")) {
            ps.setString(1, uuid.toString());
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getString(1) : null;
            }
        } catch (SQLException e) {
            log.log(Level.SEVERE, "Yedek okunamadi: " + uuid, e);
            return null;
        }
    }

    public boolean hasBackup(UUID uuid) {
        return getBackup(uuid) != null;
    }

    public synchronized void deleteBackup(UUID uuid) {
        if (conn == null) return;
        try (PreparedStatement ps = conn.prepareStatement("DELETE FROM backups WHERE uuid=?")) {
            ps.setString(1, uuid.toString());
            ps.executeUpdate();
        } catch (SQLException e) {
            log.log(Level.SEVERE, "Yedek silinemedi: " + uuid, e);
        }
    }

    public synchronized int countBackups() {
        if (conn == null) return 0;
        try (Statement st = conn.createStatement(); ResultSet rs = st.executeQuery("SELECT COUNT(*) FROM backups")) {
            return rs.next() ? rs.getInt(1) : 0;
        } catch (SQLException e) {
            return 0;
        }
    }

    public synchronized void saveHistory(String time, int participants, String winner, String results) {
        if (conn == null) return;
        try (PreparedStatement ps = conn.prepareStatement(
                "INSERT INTO history(event_time, played_at, participants, winner, results) VALUES(?,?,?,?,?)")) {
            ps.setString(1, time);
            ps.setLong(2, System.currentTimeMillis());
            ps.setInt(3, participants);
            ps.setString(4, winner);
            ps.setString(5, results);
            ps.executeUpdate();
        } catch (SQLException e) {
            log.log(Level.WARNING, "Event gecmisi kaydedilemedi", e);
        }
    }

    /** [event_time, played_at(ms), participants, winner] */
    public synchronized List<String[]> recentHistory(int limit) {
        List<String[]> out = new ArrayList<>();
        if (conn == null) return out;
        try (PreparedStatement ps = conn.prepareStatement(
                "SELECT event_time, played_at, participants, winner FROM history ORDER BY id DESC LIMIT ?")) {
            ps.setInt(1, limit);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    out.add(new String[]{rs.getString(1), String.valueOf(rs.getLong(2)),
                            String.valueOf(rs.getInt(3)), rs.getString(4)});
                }
            }
        } catch (SQLException e) {
            log.log(Level.WARNING, "Event gecmisi okunamadi", e);
        }
        return out;
    }
}
