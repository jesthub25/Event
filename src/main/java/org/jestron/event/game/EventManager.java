package org.jestron.event.game;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.data.BlockData;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Hanging;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.player.PlayerRespawnEvent;
import org.bukkit.scheduler.BukkitTask;
import org.bukkit.util.BoundingBox;
import org.jestron.event.EventPlugin;
import org.jestron.event.game.Participant.State;
import org.jestron.event.model.EventDef;
import org.jestron.event.model.Pos;
import org.jestron.event.model.Room;
import org.jestron.event.storage.PlayerBackup;
import org.jestron.event.util.Messages;
import org.jestron.event.util.PlayerUtil;
import org.jestron.event.util.TimeUtil;

import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Turnuva motoru: zamanlama, katılım, eşleştirme, tur yönetimi, maçlar, eleme, sonuç ve ödüller.
 * Tüm işlemler ana thread üzerinde çalışır.
 */
public final class EventManager {

    public enum Reason {DEATH, QUIT, LEAVE, TIMEOUT}

    private final EventPlugin plugin;
    private final Messages msg;
    private EventSession session;
    private BukkitTask ticker;

    /** Eklentinin kendi ışınlamaları (teleport koruması bunları engellemez) */
    private final Set<UUID> allowedTeleport = new HashSet<>();
    /** Oyundan çıkmakta olan oyuncular (quit event sırasında "çevrimdışı" sayılır) */
    private final Set<UUID> quitting = new HashSet<>();
    /** Öldükten sonra doğacakları yer */
    private final Map<UUID, Location> respawnTo = new HashMap<>();
    /** Doğunca eşyaları iade edilip hub'a gönderilecekler */
    private final Set<UUID> restoreAfterRespawn = new HashSet<>();
    /** Maçı kazandıktan sonra ölen (ör. ateş) kazananlar: doğunca bekleme alanına */
    private final Set<UUID> resetAfterRespawn = new HashSet<>();

    public EventManager(EventPlugin plugin) {
        this.plugin = plugin;
        this.msg = plugin.messages();
    }

    // ------------------------------------------------------------------ yaşam döngüsü

    public void start() {
        ticker = Bukkit.getScheduler().runTaskTimer(plugin, this::tick, 20L, 20L);
    }

    public void shutdown() {
        if (session != null) stop("event-durduruldu");
        if (ticker != null) ticker.cancel();
        ticker = null;
    }

    public EventSession session() {
        return session;
    }

    public boolean isRunning(EventDef def) {
        return session != null && session.def == def;
    }

    /** Aktif (elenmemiş) katılımcı, yoksa null. */
    public Participant participant(Player p) {
        if (session == null || p == null) return null;
        Participant pt = session.get(p.getUniqueId());
        return pt != null && pt.active() ? pt : null;
    }

    public boolean isAllowedTeleport(UUID uuid) {
        return allowedTeleport.contains(uuid);
    }

    public Location eventSpawn() {
        if (session == null || session.def.spawn == null) return null;
        return session.def.spawn.toLocation();
    }

    // ------------------------------------------------------------------ ayarlar

    private int cfgInt(String key, int def) {
        return plugin.getConfig().getInt("ayarlar." + key, def);
    }

    private boolean cfgBool(String key, boolean def) {
        return plugin.getConfig().getBoolean("ayarlar." + key, def);
    }

    public ZoneId zone() {
        String z = plugin.getConfig().getString("ayarlar.saat-dilimi", "Europe/Istanbul");
        try {
            return ZoneId.of(z);
        } catch (Exception e) {
            return ZoneId.of("Europe/Istanbul");
        }
    }

    // ------------------------------------------------------------------ yardımcılar

    private Player online(UUID uuid) {
        if (uuid == null || quitting.contains(uuid)) return null;
        Player p = Bukkit.getPlayer(uuid);
        return p != null && p.isOnline() ? p : null;
    }

    public boolean teleport(Player p, Location l) {
        if (p == null || l == null) return false;
        UUID u = p.getUniqueId();
        allowedTeleport.add(u);
        try {
            if (p.isInsideVehicle()) p.leaveVehicle();
            return p.teleport(l);
        } finally {
            allowedTeleport.remove(u);
        }
    }

    private Location hub() {
        Pos h = plugin.store().hub;
        Location l = h == null ? null : h.toLocation();
        if (l == null && !Bukkit.getWorlds().isEmpty()) l = Bukkit.getWorlds().get(0).getSpawnLocation();
        return l;
    }

    private List<Player> onlineParticipants(boolean activeOnly) {
        List<Player> out = new ArrayList<>();
        if (session == null) return out;
        for (Participant pt : session.participants.values()) {
            if (activeOnly && !pt.active()) continue;
            Player p = online(pt.uuid);
            if (p != null) out.add(p);
        }
        return out;
    }

    private void tellParticipants(String key, Object... ph) {
        msg.sendTo(onlineParticipants(true), key, ph);
    }

    /** Eşyaları veritabanından iade eder ve hub'a gönderir. */
    public void restoreAndHub(Player p) {
        UUID u = p.getUniqueId();
        String data = plugin.database().getBackup(u);
        PlayerUtil.reset(p, true);
        if (data != null) {
            if (PlayerBackup.apply(p, data, plugin.getLogger())) {
                plugin.database().deleteBackup(u);
            } else {
                plugin.getLogger().severe(p.getName() + " icin yedek uygulanamadi, veritabaninda saklanmaya devam ediyor.");
            }
        }
        Location h = hub();
        if (h == null && data != null) h = PlayerBackup.location(data);
        teleport(p, h);
    }

    // ------------------------------------------------------------------ zamanlayıcı

    private void tick() {
        try {
            checkSchedule();
            if (session == null) return;
            if (session.phase == EventSession.Phase.JOIN) tickJoin();
            else if (session.phase == EventSession.Phase.RUNNING) tickRunning();
        } catch (Throwable t) {
            plugin.getLogger().log(java.util.logging.Level.SEVERE, "Event zamanlayici hatasi", t);
        }
    }

    private void checkSchedule() {
        ZonedDateTime now = ZonedDateTime.now(zone());
        String hm = String.format("%02d:%02d", now.getHour(), now.getMinute());
        String today = now.toLocalDate().toString();
        for (EventDef def : plugin.store().events.values()) {
            if (!def.time.equals(hm) || today.equals(def.lastRunDate)) continue;
            def.lastRunDate = today;
            plugin.store().save();
            if (session != null) {
                plugin.getLogger().warning(def.time + " eventi atlandi: zaten aktif bir event var (" + session.def.time + ").");
                continue;
            }
            open(def, null);
        }
    }

    /** Katılımı açar. by == null ise otomatik (zamanlanmış). */
    public boolean open(EventDef def, CommandSender by) {
        if (session != null) {
            msg.send(by, "zaten-aktif");
            return false;
        }
        List<String> missing = def.missing(plugin.store().hub);
        if (!missing.isEmpty()) {
            String m = String.join(", ", missing);
            if (by != null) msg.send(by, "event-acilamadi", "saat", def.time, "eksik", m);
            for (Player p : Bukkit.getOnlinePlayers()) {
                if (p != by && p.hasPermission("event.admin")) msg.send(p, "event-acilamadi", "saat", def.time, "eksik", m);
            }
            plugin.getLogger().warning(def.time + " eventi acilamadi. Eksikler: " + m);
            return false;
        }
        int join = Math.max(10, cfgInt("katilim-suresi", 300));
        session = new EventSession(def, join);
        msg.broadcast("event-acildi", "saat", def.time, "sure", TimeUtil.formatDuration(join));
        msg.broadcastJoinButton();
        for (Player p : Bukkit.getOnlinePlayers()) PlayerUtil.sound(p, "event-acildi");
        return true;
    }

    private void tickJoin() {
        EventSession s = session;
        if (s.joinLeft <= 0) {
            beginTournament();
            return;
        }
        int left = s.joinLeft;
        if (left != s.joinTotal && plugin.getConfig().getIntegerList("ayarlar.geri-sayim-duyurulari").contains(left)) {
            msg.broadcast("katilim-geri-sayim", "sure", TimeUtil.formatDuration(left), "sayi", s.participants.size());
            if (left >= 10) msg.broadcastJoinButton();
            for (Player p : Bukkit.getOnlinePlayers()) PlayerUtil.sound(p, "geri-sayim");
        }
        for (Player p : onlineParticipants(true)) {
            msg.actionBar(p, "katilim-actionbar", "sure", TimeUtil.formatDuration(left), "sayi", s.participants.size());
        }
        s.joinLeft--;
    }

    private void tickRunning() {
        EventSession s = session;
        for (Match m : new ArrayList<>(s.matches.values())) {
            if (session != s) return;
            tickMatch(m);
        }
        if (session != s) return;
        int alive = s.aliveCount();
        int queued = s.queue.size();
        for (Participant pt : s.participants.values()) {
            if (pt.state != State.WAITING && pt.state != State.QUEUED) continue;
            Player p = online(pt.uuid);
            if (p != null) {
                msg.actionBar(p, "bekleme-actionbar", "tur", Math.max(1, s.round), "kalan", alive,
                        "mac", s.matches.size(), "sira", queued);
            }
        }
    }

    // ------------------------------------------------------------------ katılım / ayrılma

    public void join(Player p) {
        EventSession s = session;
        if (s == null) {
            msg.send(p, "event-aktif-degil");
            Object[] next = TimeUtil.next(plugin.store().events.keySet(), zone());
            if (next != null) msg.send(p, "sonraki-event", "saat", next[0], "sure", TimeUtil.formatDuration((Long) next[1]));
            return;
        }
        if (s.phase != EventSession.Phase.JOIN) {
            if (participant(p) != null) msg.send(p, "zaten-katildin");
            else msg.send(p, "event-devam-ediyor");
            return;
        }
        UUID u = p.getUniqueId();
        if (s.get(u) != null) {
            msg.send(p, "zaten-katildin");
            return;
        }
        if (p.isDead()) return;
        Location spawn = s.def.spawn == null ? null : s.def.spawn.toLocation();
        if (spawn == null) {
            msg.send(p, "konum-hatasi");
            return;
        }
        // önceki eventten iade edilmemiş yedek varsa önce onu iade et (eşya kaybı olmaz)
        String stale = plugin.database().getBackup(u);
        if (stale != null) {
            PlayerUtil.reset(p, true);
            if (PlayerBackup.apply(p, stale, plugin.getLogger())) plugin.database().deleteBackup(u);
            else {
                msg.send(p, "veritabani-hatasi");
                return;
            }
        }
        if (cfgBool("envanter-bos-olmali", false) && !PlayerUtil.isInventoryEmpty(p)) {
            msg.send(p, "envanter-bos-olmali");
            return;
        }
        String data = PlayerBackup.capture(p);
        if (!plugin.database().saveBackup(u, p.getName(), data)) {
            msg.send(p, "veritabani-hatasi");
            return;
        }
        PlayerUtil.reset(p, true);
        if (!teleport(p, spawn)) {
            restoreAndHub(p);
            msg.send(p, "konum-hatasi");
            return;
        }
        s.participants.put(u, new Participant(u, p.getName()));
        msg.send(p, "katildin");
        tellParticipants("katildi", "player", p.getName(), "sayi", s.participants.size());
    }

    public void leave(Player p) {
        EventSession s = session;
        Participant pt = participant(p);
        if (s == null || pt == null) {
            msg.send(p, "evente-katilmadin");
            return;
        }
        if (s.phase == EventSession.Phase.JOIN) {
            s.participants.remove(p.getUniqueId());
            restoreAndHub(p);
            msg.send(p, "ayrildin");
            tellParticipants("ayrildi", "player", p.getName(), "sayi", s.participants.size());
            return;
        }
        if (pt.state == State.IN_MATCH && pt.match != null && !pt.match.ended) {
            finishMatch(pt.match, pt.match.opponent(pt.uuid), pt.uuid, Reason.LEAVE);
        } else {
            eliminate(pt, p, false, "ayrildin");
            dispatch();
        }
    }

    public void handleQuit(Player p) {
        UUID u = p.getUniqueId();
        respawnTo.remove(u);
        restoreAfterRespawn.remove(u);
        resetAfterRespawn.remove(u);
        EventSession s = session;
        Participant pt = participant(p);
        if (s == null || pt == null) return;
        quitting.add(u);
        try {
            // kit eşyaları oyuncu verisine kaydedilmesin; gerçek eşyalar veritabanında, girişte iade edilir
            p.getInventory().clear();
            if (s.phase == EventSession.Phase.JOIN) {
                s.participants.remove(u);
                tellParticipants("ayrildi", "player", p.getName(), "sayi", s.participants.size());
                return;
            }
            if (pt.state == State.IN_MATCH && pt.match != null && !pt.match.ended) {
                finishMatch(pt.match, pt.match.opponent(u), u, Reason.QUIT);
            } else {
                eliminate(pt, null, false, null);
                dispatch();
            }
        } finally {
            quitting.remove(u);
        }
    }

    /** Girişte: iade edilmemiş yedeği varsa iade et ve hub'a gönder. */
    public void handleJoin(Player p) {
        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            if (!p.isOnline()) return;
            if (participant(p) != null) return;
            if (!plugin.database().hasBackup(p.getUniqueId())) return;
            if (p.isDead()) {
                Location h = hub();
                if (h != null) respawnTo.put(p.getUniqueId(), h);
                restoreAfterRespawn.add(p.getUniqueId());
                return;
            }
            restoreAndHub(p);
            msg.send(p, "esyalar-iade");
        }, 10L);
    }

    // ------------------------------------------------------------------ turnuva akışı

    private void beginTournament() {
        EventSession s = session;
        s.participants.values().removeIf(pt -> online(pt.uuid) == null);
        int n = s.participants.size();
        int min = Math.max(2, cfgInt("minimum-oyuncu", 2));
        if (n < min) {
            msg.broadcast("yetersiz-oyuncu", "sayi", n, "min", min);
            stop(null);
            return;
        }
        s.phase = EventSession.Phase.RUNNING;
        msg.broadcast("turnuva-basladi", "saat", s.def.time, "sayi", n);
        startRoundLater(Math.max(1, cfgInt("ilk-tur-bekleme", 5)));
    }

    private void startRoundLater(int seconds) {
        EventSession s = session;
        if (s == null || s.roundTransition) return;
        s.roundTransition = true;
        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            if (session != s || s.phase != EventSession.Phase.RUNNING) return;
            s.roundTransition = false;
            startRound();
        }, Math.max(1, seconds) * 20L);
    }

    private void startRound() {
        EventSession s = session;
        List<Participant> alive = new ArrayList<>();
        for (Participant pt : s.participants.values()) {
            if (pt.active() && online(pt.uuid) != null) alive.add(pt);
        }
        if (alive.size() <= 1) {
            finish(alive.isEmpty() ? null : alive.get(0));
            return;
        }
        s.round++;
        Collections.shuffle(alive);
        Participant bye = null;
        if (alive.size() % 2 == 1) {
            // bay: en az bay geçmiş oyunculardan rastgele biri (aynı kişi sürekli bay geçmesin)
            int minByes = Integer.MAX_VALUE;
            for (Participant pt : alive) minByes = Math.min(minByes, pt.byes);
            List<Participant> cands = new ArrayList<>();
            for (Participant pt : alive) if (pt.byes == minByes) cands.add(pt);
            bye = cands.get(ThreadLocalRandom.current().nextInt(cands.size()));
            alive.remove(bye);
            bye.byes++;
            bye.state = State.WAITING;
        }
        s.queue.clear();
        for (int i = 0; i + 1 < alive.size(); i += 2) {
            Participant a = alive.get(i);
            Participant b = alive.get(i + 1);
            a.state = State.QUEUED;
            b.state = State.QUEUED;
            s.queue.add(new UUID[]{a.uuid, b.uuid});
        }
        tellParticipants("tur-basladi", "tur", s.round, "mac", s.queue.size(), "kalan", s.aliveCount());
        if (bye != null) {
            Player bp = online(bye.uuid);
            if (bp != null) msg.send(bp, "bay-gecti");
            tellParticipants("bay-duyuru", "player", bye.name);
        }
        dispatch();
        // boş oda yoksa sıradakilere bilgi ver
        for (UUID[] pair : s.queue) {
            for (UUID u : pair) {
                Player p = online(u);
                if (p != null) msg.send(p, "mac-sirada");
            }
        }
    }

    /** Sıradaki eşleşmeleri boş odalara yerleştirir; tur bittiyse sonraki turu planlar. */
    private void dispatch() {
        EventSession s = session;
        if (s == null || s.phase != EventSession.Phase.RUNNING || s.roundTransition) return;
        while (!s.queue.isEmpty()) {
            UUID[] pair = s.queue.peek();
            Participant pa = s.get(pair[0]);
            Participant pb = s.get(pair[1]);
            boolean okA = pa != null && pa.state == State.QUEUED && online(pa.uuid) != null;
            boolean okB = pb != null && pb.state == State.QUEUED && online(pb.uuid) != null;
            if (!okA || !okB) {
                s.queue.poll();
                if (okA) walkover(pa);
                if (okB) walkover(pb);
                continue;
            }
            Room room = freeRoom(s);
            if (room == null) break;
            s.queue.poll();
            startMatch(room, pa, pb);
            if (session != s) return;
        }
        if (s.queue.isEmpty() && s.matches.isEmpty()) {
            int wait = Math.max(1, cfgInt("tur-arasi-bekleme", 5));
            if (s.round > 0 && s.aliveCount() > 1) tellParticipants("tur-bitti", "tur", s.round, "sure", wait);
            startRoundLater(s.aliveCount() > 1 ? wait : 1);
        }
    }

    private void walkover(Participant pt) {
        pt.state = State.WAITING;
        Player p = online(pt.uuid);
        if (p != null) msg.send(p, "rakip-yok");
    }

    private Room freeRoom(EventSession s) {
        List<Room> rooms = new ArrayList<>(s.def.rooms.values());
        Collections.shuffle(rooms);
        for (Room r : rooms) {
            if (s.matches.containsKey(r.key())) continue;
            if (r.isReady()) return r;
        }
        return null;
    }

    private void startMatch(Room room, Participant pa, Participant pb) {
        EventSession s = session;
        Player a = online(pa.uuid);
        Player b = online(pb.uuid);
        Location la = room.spawn(1);
        Location lb = room.spawn(2);
        Match m = new Match(room, pa, pb, Math.max(0, cfgInt("mac-geri-sayim", 3)));
        s.matches.put(room.key(), m);
        Object[][] sides = {{pa, a, la, pb.name}, {pb, b, lb, pa.name}};
        for (Object[] side : sides) {
            Participant pt = (Participant) side[0];
            Player p = (Player) side[1];
            pt.state = State.IN_MATCH;
            pt.match = m;
            pt.matchesPlayed++;
            PlayerUtil.reset(p, true);
            teleport(p, (Location) side[2]);
            PlayerUtil.applyKit(p, s.def.kit);
            msg.send(p, "mac-basliyor", "rakip", side[3], "oda", room.name);
        }
        tellParticipants("mac-duyuru", "a", pa.name, "b", pb.name, "oda", room.name);
        if (m.countdown == 0) {
            m.started = true;
            m.timeLeft = cfgInt("mac-max-sure", 180);
        }
    }

    private void tickMatch(Match m) {
        if (m.ended) return;
        Player a = online(m.a);
        Player b = online(m.b);
        if (a == null || b == null) return;
        if (!m.started) {
            if (m.countdown > 0) {
                msg.title(a, "geri-sayim-title", "geri-sayim-subtitle", 0, 25, 0, "sayi", m.countdown, "rakip", m.bName);
                msg.title(b, "geri-sayim-title", "geri-sayim-subtitle", 0, 25, 0, "sayi", m.countdown, "rakip", m.aName);
                PlayerUtil.sound(a, "geri-sayim");
                PlayerUtil.sound(b, "geri-sayim");
                m.countdown--;
            } else {
                m.started = true;
                m.timeLeft = cfgInt("mac-max-sure", 180);
                for (Player p : new Player[]{a, b}) {
                    msg.title(p, "basla-title", "basla-subtitle", 0, 20, 10);
                    PlayerUtil.sound(p, "mac-basladi");
                }
            }
            return;
        }
        if (cfgInt("mac-max-sure", 180) <= 0) return;
        m.timeLeft--;
        if (m.timeLeft > 0 && m.timeLeft <= 10) {
            msg.actionBar(a, "sure-actionbar", "sure", m.timeLeft);
            msg.actionBar(b, "sure-actionbar", "sure", m.timeLeft);
        }
        if (m.timeLeft <= 0) timeout(m, a, b);
    }

    @SuppressWarnings("deprecation")
    private void timeout(Match m, Player a, Player b) {
        double ra = a.getHealth() / a.getMaxHealth();
        double rb = b.getHealth() / b.getMaxHealth();
        UUID winner;
        if (Math.abs(ra - rb) < 1e-6) winner = ThreadLocalRandom.current().nextBoolean() ? m.a : m.b;
        else winner = ra > rb ? m.a : m.b;
        msg.send(a, "zaman-asimi");
        msg.send(b, "zaman-asimi");
        finishMatch(m, winner, m.opponent(winner), Reason.TIMEOUT);
    }

    public void finishMatch(Match m, UUID winner, UUID loser, Reason reason) {
        EventSession s = session;
        if (s == null || m.ended) return;
        m.ended = true;
        m.winner = winner;
        Participant pw = s.get(winner);
        Participant pl = s.get(loser);
        Player wp = online(winner);
        Player lp = online(loser);
        boolean countKill = reason == Reason.DEATH
                || ((reason == Reason.QUIT || reason == Reason.LEAVE) && cfgBool("cikis-kill-sayilsin", false));
        if (pw != null && countKill) pw.kills++;
        String sebep = msg.get(switch (reason) {
            case DEATH -> "sebep-olum";
            case QUIT -> "sebep-cikis";
            case LEAVE -> "sebep-ayrildi";
            case TIMEOUT -> "sebep-sure";
        });
        String wName = pw != null ? pw.name : "?";
        String lName = pl != null ? pl.name : "?";
        tellParticipants("mac-sonucu", "kazanan", wName, "kaybeden", lName, "oda", m.room.name, "sebep", sebep);
        if (pl != null) {
            if (lp != null) {
                msg.title(lp, "kaybettin-title", "kaybettin-subtitle", 5, 40, 10);
                PlayerUtil.sound(lp, "kaybettin");
            }
            eliminate(pl, lp, reason == Reason.DEATH, reason == Reason.LEAVE ? "ayrildin" : "elendin");
        }
        if (wp != null && !wp.isDead()) {
            msg.title(wp, "kazandin-title", "kazandin-subtitle", 5, 40, 10);
            PlayerUtil.sound(wp, "kazandin");
            wp.setFireTicks(0);
        }
        int delay = Math.max(1, cfgInt("mac-sonu-bekleme", 3));
        Bukkit.getScheduler().runTaskLater(plugin, () -> finalizeMatch(s, m), delay * 20L);
    }

    private void finalizeMatch(EventSession s, Match m) {
        if (session != s || s.matches.get(m.room.key()) != m) return;
        Participant pw = s.get(m.winner);
        if (pw != null && pw.state == State.IN_MATCH && pw.match == m) {
            Player wp = online(pw.uuid);
            pw.match = null;
            pw.state = State.WAITING;
            if (wp != null && !wp.isDead()) {
                PlayerUtil.reset(wp, true);
                teleport(wp, eventSpawn());
                msg.send(wp, "tura-yukseldin");
            }
            // ölü ise doğunca resetAfterRespawn ile bekleme alanına gelir
        }
        restoreRoom(m);
        s.matches.remove(m.room.key());
        dispatch();
    }

    /** Odayı maç öncesi haline döndürür: değişen bloklar + yerdeki eşya/ok/xp vb. */
    private void restoreRoom(Match m) {
        World w = Bukkit.getWorld(m.room.region.world);
        if (w == null) return;
        for (Map.Entry<Match.BlockKey, BlockData> e : m.changes.entrySet()) {
            Match.BlockKey k = e.getKey();
            Block b = w.getBlockAt(k.x(), k.y(), k.z());
            b.setBlockData(e.getValue(), false);
        }
        m.changes.clear();
        var r = m.room.region;
        BoundingBox box = new BoundingBox(r.minX, r.minY, r.minZ, r.maxX + 1, r.maxY + 1, r.maxZ + 1);
        for (Entity e : w.getNearbyEntities(box)) {
            if (e instanceof Player || e instanceof LivingEntity || e instanceof Hanging) continue;
            e.remove();
        }
    }

    private void eliminate(Participant pt, Player p, boolean died, String messageKey) {
        EventSession s = session;
        if (s == null || !pt.active()) return;
        pt.state = State.ELIMINATED;
        pt.match = null;
        pt.eliminatedRound = s.round;
        pt.eliminationIndex = s.eliminationCounter++;
        if (p != null) {
            if (messageKey != null) msg.send(p, messageKey);
            if (died || p.isDead()) {
                Location h = hub();
                if (h != null) respawnTo.put(pt.uuid, h);
                restoreAfterRespawn.add(pt.uuid);
            } else {
                restoreAndHub(p);
            }
        }
        if (s.phase == EventSession.Phase.RUNNING) {
            tellParticipants("oyuncu-elendi", "player", pt.name, "kalan", s.aliveCount());
        }
    }

    private void finish(Participant champion) {
        EventSession s = session;
        s.phase = EventSession.Phase.ENDED;
        if (champion == null) {
            msg.broadcast("kazanan-yok");
            plugin.database().saveHistory(s.def.time, s.participants.size(), null, "");
            session = null;
            return;
        }
        champion.champion = true;
        champion.eliminatedRound = Integer.MAX_VALUE;
        List<Participant> ranking = new ArrayList<>(s.participants.values());
        ranking.sort(Comparator
                .comparing((Participant p) -> p.champion).reversed()
                .thenComparing((Participant p) -> p.eliminatedRound, Comparator.reverseOrder())
                .thenComparing((Participant p) -> p.kills, Comparator.reverseOrder())
                .thenComparing((Participant p) -> p.eliminationIndex, Comparator.reverseOrder()));

        int show = Math.max(1, cfgInt("sonuc-gosterilecek", 3));
        List<String> lines = new ArrayList<>(msg.list("sonuc-baslik", "saat", s.def.time, "sayi", ranking.size()));
        StringBuilder results = new StringBuilder();
        for (int i = 0; i < ranking.size(); i++) {
            Participant p = ranking.get(i);
            if (i < show) lines.add(msg.get("sonuc-satir", "sira", i + 1, "player", p.name, "kill", p.kills));
            if (results.length() > 0) results.append(';');
            results.append(i + 1).append(':').append(p.name).append(':').append(p.kills);
        }
        lines.addAll(msg.list("sonuc-alt", "saat", s.def.time, "sayi", ranking.size()));
        for (Player p : Bukkit.getOnlinePlayers()) for (String l : lines) p.sendMessage(l);
        for (String l : lines) Bukkit.getConsoleSender().sendMessage(l);

        Player cp = online(champion.uuid);
        if (cp != null) {
            msg.title(cp, "sampiyon-title", "sampiyon-subtitle", 10, 60, 20, "player", champion.name);
            PlayerUtil.sound(cp, "sampiyon");
        }
        plugin.database().saveHistory(s.def.time, ranking.size(), champion.name, results.toString());

        // ödüller
        for (int i = 0; i < ranking.size(); i++) {
            Participant p = ranking.get(i);
            List<String> cmds = new ArrayList<>(plugin.getConfig().getStringList("oduller." + (i + 1)));
            if (p.matchesPlayed > 0 || p.champion) cmds.addAll(plugin.getConfig().getStringList("katilim-odulu"));
            for (String c : cmds) {
                String cmd = c.replace("%player%", p.name).replace("%sira%", String.valueOf(i + 1))
                        .replace("%kill%", String.valueOf(p.kills));
                if (cmd.startsWith("/")) cmd = cmd.substring(1);
                try {
                    Bukkit.dispatchCommand(Bukkit.getConsoleSender(), cmd);
                } catch (Exception e) {
                    plugin.getLogger().warning("Odul komutu calistirilamadi: " + cmd + " (" + e.getMessage() + ")");
                }
            }
        }
        // kalan herkesi (şampiyon) iade et
        session = null;
        for (Participant pt : s.participants.values()) {
            if (pt.state == State.ELIMINATED) continue;
            pt.state = State.ELIMINATED;
            returnPlayer(pt.uuid);
        }
    }

    private void returnPlayer(UUID u) {
        Player p = online(u);
        if (p == null) return; // çevrimdışı: girişte iade edilir
        if (p.isDead()) {
            Location h = hub();
            if (h != null) respawnTo.put(u, h);
            restoreAfterRespawn.add(u);
        } else {
            restoreAndHub(p);
        }
    }

    /** Eventi durdurur, odaları onarır, herkesin eşyasını iade eder. messageKey null ise mesaj yok. */
    public void stop(String messageKey) {
        EventSession s = session;
        if (s == null) return;
        session = null;
        s.phase = EventSession.Phase.ENDED;
        for (Match m : s.matches.values()) {
            m.ended = true;
            restoreRoom(m);
        }
        s.matches.clear();
        s.queue.clear();
        for (Participant pt : s.participants.values()) {
            if (pt.state == State.ELIMINATED) continue;
            pt.state = State.ELIMINATED;
            returnPlayer(pt.uuid);
        }
        if (messageKey != null) msg.broadcast(messageKey);
    }

    // ------------------------------------------------------------------ ölüm / doğma

    @SuppressWarnings("deprecation")
    public void handleDeath(PlayerDeathEvent e) {
        Player p = e.getEntity();
        EventSession s = session;
        Participant pt = participant(p);
        if (s == null || pt == null) return;
        e.getDrops().clear();
        e.setDroppedExp(0);
        e.setKeepInventory(false);
        e.setKeepLevel(true);
        if (cfgBool("olum-mesaji-gizle", true)) e.setDeathMessage(null);
        UUID u = p.getUniqueId();
        if (s.phase == EventSession.Phase.JOIN) {
            s.participants.remove(u);
            Location h = hub();
            if (h != null) respawnTo.put(u, h);
            restoreAfterRespawn.add(u);
        } else if (pt.state == State.IN_MATCH && pt.match != null && !pt.match.ended) {
            finishMatch(pt.match, pt.match.opponent(u), u, Reason.DEATH);
        } else if (pt.state == State.IN_MATCH && pt.match != null) {
            // maçı kazandıktan hemen sonra öldü (yanma vb.) -> elenmez, bekleme alanında doğar
            Location sp = eventSpawn();
            if (sp != null) respawnTo.put(u, sp);
            resetAfterRespawn.add(u);
        } else {
            eliminate(pt, p, true, "elendin");
            dispatch();
        }
        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            if (p.isOnline() && p.isDead()) p.spigot().respawn();
        }, 2L);
    }

    public void handleRespawn(PlayerRespawnEvent e) {
        Player p = e.getPlayer();
        UUID u = p.getUniqueId();
        Location to = respawnTo.remove(u);
        if (to != null) e.setRespawnLocation(to);
        boolean restore = restoreAfterRespawn.remove(u);
        boolean reset = resetAfterRespawn.remove(u);
        if (!restore && !reset) return;
        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            if (!p.isOnline()) return;
            if (restore) {
                restoreAndHub(p);
            } else {
                Participant pt = participant(p);
                if (pt != null) {
                    PlayerUtil.reset(p, true);
                    teleport(p, eventSpawn());
                } else if (plugin.database().hasBackup(u)) {
                    restoreAndHub(p);
                }
            }
        }, 2L);
    }

    // ------------------------------------------------------------------ oda sorguları (dinleyiciler için)

    /** Bu bloğu içeren aktif maç */
    public Match matchAt(Block b) {
        if (session == null || session.matches.isEmpty()) return null;
        for (Match m : session.matches.values()) {
            if (m.room.region.contains(b)) return m;
        }
        return null;
    }

    /** Aktif eventin herhangi bir odası bu bloğu içeriyor mu (patlama koruması) */
    public boolean inAnyRoom(Block b) {
        if (session == null) return false;
        for (Room r : session.def.rooms.values()) {
            if (r.region.contains(b)) return true;
        }
        return false;
    }

    public boolean cfg(String key, boolean def) {
        return cfgBool(key, def);
    }
}
