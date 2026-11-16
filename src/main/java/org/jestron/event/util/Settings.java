package org.jestron.event.util;

import org.bukkit.configuration.file.FileConfiguration;

import java.time.ZoneId;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/** config.yml'nin önbelleğe alınmış hali. Sıcak yollarda (hareket, hasar, blok) config okunmaz. */
public final class Settings {

    public final ZoneId zone;
    public final int joinSeconds;
    public final int minPlayers;
    public final int firstRoundDelay;
    public final int roundDelay;
    public final int countdown;
    public final int maxDuration;
    public final int endDelay;
    public final int showTop;
    public final Set<Integer> announce;
    /** Event saatinden kaç dakika önce herkese title ile hatırlatma yapılacağı */
    public final Set<Integer> reminders;
    public final boolean waitingDamage;
    public final boolean waitingHunger;
    public final boolean roomPlace;
    public final boolean roomBreak;
    public final boolean force;
    public final boolean killOnQuit;
    public final boolean hideDeath;
    public final Set<String> allowedCommands = new HashSet<>();
    public final Map<Integer, List<String>> rewards = new TreeMap<>();
    public final List<String> participationReward;
    public final Map<String, String> sounds = new java.util.HashMap<>();

    public Settings(FileConfiguration c) {
        ZoneId z;
        try {
            z = ZoneId.of(c.getString("ayarlar.saat-dilimi", "Europe/Istanbul"));
        } catch (Exception e) {
            z = ZoneId.of("Europe/Istanbul");
        }
        zone = z;
        joinSeconds = Math.max(10, c.getInt("ayarlar.katilim-suresi", 300));
        minPlayers = Math.max(2, c.getInt("ayarlar.minimum-oyuncu", 2));
        firstRoundDelay = Math.max(1, c.getInt("ayarlar.ilk-tur-bekleme", 5));
        roundDelay = Math.max(1, c.getInt("ayarlar.tur-arasi-bekleme", 5));
        countdown = Math.max(0, c.getInt("ayarlar.mac-geri-sayim", 3));
        maxDuration = Math.max(0, c.getInt("ayarlar.mac-max-sure", 180));
        endDelay = Math.max(1, c.getInt("ayarlar.mac-sonu-bekleme", 3));
        showTop = Math.max(1, c.getInt("ayarlar.sonuc-gosterilecek", 3));
        announce = new HashSet<>(c.getIntegerList("ayarlar.geri-sayim-duyurulari"));
        reminders = new HashSet<>(c.getIntegerList("ayarlar.hatirlatma-dakikalari"));
        waitingDamage = c.getBoolean("ayarlar.bekleme-alani-hasar", false);
        waitingHunger = c.getBoolean("ayarlar.bekleme-alani-aclik", false);
        roomPlace = c.getBoolean("ayarlar.oda-blok-koyma", true);
        roomBreak = c.getBoolean("ayarlar.oda-blok-kirma", false);
        force = c.getBoolean("ayarlar.izinleri-zorla", true);
        killOnQuit = c.getBoolean("ayarlar.cikis-kill-sayilsin", false);
        hideDeath = c.getBoolean("ayarlar.olum-mesaji-gizle", true);
        for (String s : c.getStringList("ayarlar.izinli-komutlar")) allowedCommands.add(Text.normalize(s));
        allowedCommands.add("event");
        allowedCommands.add("etkinlik");
        for (int i = 1; i <= 100; i++) {
            List<String> l = c.getStringList("oduller." + i);
            if (!l.isEmpty()) rewards.put(i, l);
        }
        participationReward = c.getStringList("katilim-odulu");
        for (String k : new String[]{"event-acildi", "geri-sayim", "mac-basladi", "kazandin", "kaybettin", "sampiyon", "hatirlatma"}) {
            String v = c.getString("sesler." + k, "");
            if (v != null && !v.isEmpty()) sounds.put(k, v.toLowerCase(java.util.Locale.ROOT));
        }
    }
}
