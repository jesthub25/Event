package org.jestron.event.game;

import org.bukkit.Bukkit;
import org.bukkit.GameMode;
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
import org.bukkit.inventory.ItemStack;
import org.bukkit.scheduler.BukkitTask;
import org.bukkit.util.BoundingBox;
import org.jestron.event.EventPlugin;
import org.jestron.event.game.Participant.State;
import org.jestron.event.model.EventDef;
import org.jestron.event.model.Pos;
import org.jestron.event.model.Region;
import org.jestron.event.model.Room;
import org.jestron.event.util.Messages;
import org.jestron.event.util.PlayerUtil;
import org.jestron.event.util.Settings;
import org.jestron.event.util.TimeUtil;

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
import java.util.logging.Level;

/**
 * Turnuva motoru: zamanlama, katılım, eşleştirme, turlar, maçlar, eleme, sonuç ve ödüller.
 * Tamamen ana thread üzerinde, saniyede bir tick ile çalışır.
 */
public final class EventManager {

    public enum Reason {DEATH, QUIT, LEAVE, TIMEOUT}

    private final EventPlugin plugin;
    private final Messages msg;
    private EventSession session;
    private BukkitTask ticker;
    private int lastScheduleMinute = -1;

    /** Eklentinin kendi ışınlamaları (koruma bunları engellemez) */
    private final Set<UUID> allowedTeleport = new HashSet<>();
    /** Quit event sırasında "çevrimdışı" sayılanlar */
    private final Set<UUID> quitting = new HashSet<>();
    /** Ölünce doğacakları yer */
    private final Map<UUID, Location> respawnTo = new HashMap<>();
    /** Doğunca eventten çıkarılacaklar -> eski oyun modu */
    private final Map<UUID, GameMode> releaseAfterRespawn = new HashMap<>();
    /** Maçı kazandıktan sonra ölen kazananlar: doğunca bekleme alanına */
    private final Set<UUID> resetAfterRespawn = new HashSet<>();

    public EventManager(EventPlugin plugin) {
        this.plugin = plugin;
        this.msg = plugin.messages();
    }

    private Settings cfg() {
        return plugin.settings();
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

    /** Aktif (elenmemiş) katılımcı, yoksa null. Event yokken tek bir null kontrolü. */
    public Participant participant(Player p) {
        EventSession s = session;
        if (s == null || p == null) return null;
        Participant pt = s.participants.get(p.getUniqueId());
        return pt != null && pt.active() ? pt : null;
    }

    public boolean isAllowedTeleport(UUID uuid) {
        return allowedTeleport.contains(uuid);
    }

    public Location eventSpawn() {
        EventSession s = session;
        return s == null || s.def.spawn == null ? null : s.def.spawn.toLocation();
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

    private List<Player> onlineParticipants() {
        List<Player> out = new ArrayList<>();
        if (session == null) return out;
        for (Participant pt : session.participants.values()) {
            if (!pt.active()) continue;
            Player p = online(pt.uuid);
            if (p != null) out.add(p);
        }
        return out;
    }

    private void tellParticipants(String key, Object... ph) {
        msg.sendTo(onlineParticipants(), key, ph);
    }

    /** Oyuncuyu eventten çıkarır: kit silinir, eski oyun modu geri verilir, hub'a gönderilir. */
    private void release(Player p, GameMode gm) {
        PlayerUtil.reset(p);
        if (gm != null && p.getGameMode() != gm) p.setGameMode(gm);
        teleport(p, hub());
    }

    /** Ölü ise doğunca, değilse hemen çıkarır. Çevrimdışı ise bir şey yapmaz (girişte kit silinir). */
    private void releaseLater(Participant pt) {
        Player p = online(pt.uuid);
        if (p == null) return;
        if (p.isDead()) {
            Location h = hub();
            if (h != null) respawnTo.put(pt.uuid, h);
            releaseAfterRespawn.put(pt.uuid, pt.gameMode);
        } else {
            release(p, pt.gameMode);
        }
    }

    // ------------------------------------------------------------------ zamanlayıcı

    private void tick() {
        try {
            checkSchedule();
            EventSession s = session;
            if (s == null) return;
            if (s.phase == EventSession.Phase.JOIN) tickJoin(s);
            else if (s.phase == EventSession.Phase.RUNNING) tickRunning(s);
        } catch (Throwable t) {
            plugin.getLogger().log(Level.SEVERE, "Event zamanlayici hatasi", t);
        }
    }

    private void checkSchedule() {
        ZonedDateTime now = ZonedDateTime.now(cfg().zone);
        int minute = now.getHour() * 60 + now.getMinute();
        if (minute == lastScheduleMinute) return; // dakikada bir kontrol yeterli
        lastScheduleMinute = minute;
        sendReminders(minute);
        String hm = String.format("%02d:%02d", now.getHour(), now.getMinute());
        EventDef def = plugin.store().event(hm);
        if (def == null) return;
        String today = now.toLocalDate().toString();
        if (today.equals(def.lastRunDate)) return;
        def.lastRunDate = today;
        plugin.store().save();
        if (session != null) {
            plugin.getLogger().warning(def.time + " eventi atlandi: zaten aktif bir event var (" + session.def.time + ").");
            return;
        }
        open(def, null);
    }

    /** Event saatinden 60/30/10/5/1 dk (config) önce tüm oyunculara title + chat hatırlatması. */
    private void sendReminders(int nowMinute) {
        if (cfg().reminders.isEmpty() || Bukkit.getOnlinePlayers().isEmpty()) return;
        for (EventDef def : plugin.store().events.values()) {
            String[] hm = def.time.split(":");
            int eventMinute = Integer.parseInt(hm[0]) * 60 + Integer.parseInt(hm[1]);
            int diff = Math.floorMod(eventMinute - nowMinute, 1440);
            if (diff == 0 || !cfg().reminders.contains(diff)) continue;
            if (!def.missing(plugin.store().hub).isEmpty()) continue; // açılamayacak eventi duyurma
            String sure = diff % 60 == 0 ? (diff / 60) + " saat" : diff + " dakika";
            for (Player p : Bukkit.getOnlinePlayers()) {
                msg.title(p, "hatirlatma-title", "hatirlatma-subtitle", 10, 60, 15, "saat", def.time, "sure", sure);
                PlayerUtil.sound(p, cfg(), "hatirlatma");
            }
            msg.broadcast("hatirlatma-chat", "saat", def.time, "sure", sure, "maks", def.maxText());
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
        def.taggedKit(); // kiti bir kez hazırla
        session = new EventSession(def, cfg().joinSeconds);
        msg.broadcast("event-acildi", "saat", def.time, "sure", TimeUtil.formatDuration(cfg().joinSeconds),
                "maks", def.maxText());
        msg.broadcastJoinButton();
        for (Player p : Bukkit.getOnlinePlayers()) PlayerUtil.sound(p, cfg(), "event-acildi");
        return true;
    }

    private void tickJoin(EventSession s) {
        if (s.joinLeft <= 0) {
            beginTournament();
            return;
        }
        int left = s.joinLeft;
        String sure = TimeUtil.formatDuration(left);
        if (left != s.joinTotal && cfg().announce.contains(left)) {
            msg.broadcast("katilim-geri-sayim", "sure", sure, "sayi", s.participants.size(), "maks", s.def.maxText());
            if (left >= 10) msg.broadcastJoinButton();
            for (Player p : Bukkit.getOnlinePlayers()) PlayerUtil.sound(p, cfg(), "geri-sayim");
        }
        for (Player p : onlineParticipants()) {
            enforceEmpty(p);
            msg.actionBar(p, "katilim-actionbar", "sure", sure, "sayi", s.participants.size(), "maks", s.def.maxText());
        }
        s.joinLeft--;
    }

    private void tickRunning(EventSession s) {
        for (Match m : new ArrayList<>(s.matches.values())) {
            if (session != s) return;
            tickMatch(m);
        }
        if (session != s) return;
        int alive = s.aliveCount();
        int round = Math.max(1, s.round);
        int matches = s.matches.size();
        int queued = s.queue.size();
        for (Participant pt : s.participants.values()) {
            if (pt.state != State.WAITING && pt.state != State.QUEUED) continue;
            Player p = online(pt.uuid);
            if (p == null) continue;
            enforceEmpty(p);
            msg.actionBar(p, "bekleme-actionbar", "tur", round, "kalan", alive, "mac", matches, "sira", queued);
        }
    }

    /** Bekleme alanında envanter boş kalır: sistem dışı gelen her eşya silinir. */
    private void enforceEmpty(Player p) {
        if (!PlayerUtil.isInventoryEmpty(p)) {
            PlayerUtil.clearInventory(p);
            p.updateInventory();
        }
    }

    // ------------------------------------------------------------------ katılım / ayrılma

    public void join(Player p) {
        EventSession s = session;
        if (s == null) {
            msg.send(p, "event-aktif-degil");
            Object[] next = TimeUtil.next(plugin.store().events.keySet(), cfg().zone);
            if (next != null) msg.send(p, "sonraki-event", "saat", next[0], "sure", TimeUtil.formatDuration((Long) next[1]));
            return;
        }
        if (s.phase != EventSession.Phase.JOIN) {
            msg.send(p, participant(p) != null ? "zaten-katildin" : "event-devam-ediyor");
            return;
        }
        UUID u = p.getUniqueId();
        if (s.participants.containsKey(u)) {
            msg.send(p, "zaten-katildin");
            return;
        }
        if (p.isDead()) return;
        if (s.def.maxPlayers > 0 && s.participants.size() >= s.def.maxPlayers) {
            msg.send(p, "event-dolu", "maks", s.def.maxPlayers);
            return;
        }
        if (!PlayerUtil.isInventoryEmpty(p)) {
            msg.send(p, "envanter-bos-degil");
            return;
        }
        Location spawn = s.def.spawn.toLocation();
        if (spawn == null) {
            msg.send(p, "konum-hatasi");
            return;
        }
        GameMode gm = p.getGameMode();
        PlayerUtil.reset(p);
        if (!teleport(p, spawn)) {
            if (p.getGameMode() != gm) p.setGameMode(gm);
            msg.send(p, "konum-hatasi");
            return;
        }
        s.participants.put(u, new Participant(u, p.getName(), gm));
        msg.send(p, "katildin");
        tellParticipants("katildi", "player", p.getName(), "sayi", s.participants.size(), "maks", s.def.maxText());
        if (s.def.maxPlayers > 0 && s.participants.size() >= s.def.maxPlayers) {
            msg.broadcast("event-doldu", "saat", s.def.time, "maks", s.def.maxPlayers);
        }
    }

    public void leave(Player p) {
        EventSession s = session;
        Participant pt = participant(p);
        if (s == null || pt == null) {
            msg.send(p, "evente-katilmadin");
            return;
        }
        if (s.phase == EventSession.Phase.JOIN) {
            s.participants.remove(pt.uuid);
            release(p, pt.gameMode);
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
        releaseAfterRespawn.remove(u);
        resetAfterRespawn.remove(u);
        EventSession s = session;
        Participant pt = participant(p);
        if (s == null || pt == null) return;
        quitting.add(u);
        try {
            // kit eşyası oyuncu verisine kaydedilmesin
            PlayerUtil.clearInventory(p);
            if (pt.gameMode != null && p.getGameMode() != pt.gameMode) p.setGameMode(pt.gameMode);
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

    /** Girişte kit eşyalarını sil (sunucu çökmesi sonrası sızıntı güvenliği). */
    public void handleJoin(Player p) {
        PlayerUtil.stripKitItems(p);
    }

    // ------------------------------------------------------------------ turnuva akışı

    private void beginTournament() {
        EventSession s = session;
        s.participants.values().removeIf(pt -> online(pt.uuid) == null);
        int n = s.participants.size();
        if (n < cfg().minPlayers) {
            msg.broadcast("yetersiz-oyuncu", "sayi", n, "min", cfg().minPlayers);
            stop(null);
            return;
        }
        s.phase = EventSession.Phase.RUNNING;
        msg.broadcast("turnuva-basladi", "saat", s.def.time, "sayi", n);
        startRoundLater(cfg().firstRoundDelay);
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
        if ((alive.size() & 1) == 1) {
            // tek kalan: en az bekleyenlerden rastgele biri (aynı kişi üst üste beklemez)
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
        }
        s.wave = 0;
        dispatch();
    }

    /**
     * Dalga sistemi: önceki dalganın bütün maçları bitmeden yeni maç başlamaz.
     * Dalga boşken sıradaki eşleşmeler boş odaların hepsine birden yerleştirilir ve
     * aynı anda geri sayımla başlar. Oda yetmeyenler bir sonraki dalgaya kalır.
     * Sıra bitince tur biter ve sonraki tur planlanır.
     */
    private void dispatch() {
        EventSession s = session;
        if (s == null || s.phase != EventSession.Phase.RUNNING || s.roundTransition) return;
        if (!s.matches.isEmpty()) return; // dalga sürüyor
        List<Room> free = new ArrayList<>();
        for (Room r : s.def.rooms.values()) if (r.isReady()) free.add(r);
        Collections.shuffle(free);
        List<Match> wave = new ArrayList<>();
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
            if (free.isEmpty()) break;
            s.queue.poll();
            wave.add(startMatch(s, free.remove(free.size() - 1), pa, pb));
            if (session != s) return;
        }
        if (!wave.isEmpty()) {
            s.wave++;
            tellParticipants("dalga-basliyor", "dalga", s.wave, "mac", wave.size(), "sira", s.queue.size());
            startWaveCountdown(s, wave);
            for (UUID[] pair : s.queue) {
                for (UUID u : pair) {
                    Player p = online(u);
                    if (p != null) msg.send(p, "mac-sirada");
                }
            }
            return;
        }
        if (!s.queue.isEmpty()) {
            // hiç kullanılabilir oda kalmadı (dünya yüklü değil vb.)
            plugin.getLogger().severe(s.def.time + " eventinde kullanilabilir oda yok, event durduruluyor.");
            stop("event-durduruldu");
            return;
        }
        int alive = s.aliveCount();
        if (s.round > 0 && alive > 1) tellParticipants("tur-bitti", "tur", s.round, "sure", cfg().roundDelay);
        startRoundLater(alive > 1 ? cfg().roundDelay : 1);
    }

    private void walkover(Participant pt) {
        pt.state = State.WAITING;
        Player p = online(pt.uuid);
        if (p != null) msg.send(p, "rakip-yok");
    }

    /** Dalgadaki bütün maçlar için tek sayaç: herkes aynı saniyede 3-2-1 görür ve aynı anda başlar. */
    private void startWaveCountdown(EventSession s, List<Match> wave) {
        if (cfg().countdown <= 0) return; // startMatch zaten başlattı
        int[] left = {cfg().countdown};
        BukkitTask[] task = new BukkitTask[1];
        task[0] = Bukkit.getScheduler().runTaskTimer(plugin, () -> {
            if (session != s) {
                task[0].cancel();
                return;
            }
            for (Match m : wave) {
                if (m.ended || m.started) continue;
                Player a = online(m.a);
                Player b = online(m.b);
                if (left[0] > 0) {
                    if (a != null) countdownTitle(a, left[0], m.bName);
                    if (b != null) countdownTitle(b, left[0], m.aName);
                } else {
                    m.started = true;
                    m.timeLeft = cfg().maxDuration;
                    for (Player p : new Player[]{a, b}) {
                        if (p == null) continue;
                        msg.title(p, "basla-title", "basla-subtitle", 0, 20, 10);
                        PlayerUtil.sound(p, cfg(), "mac-basladi");
                    }
                }
            }
            if (left[0]-- <= 0) task[0].cancel();
        }, 0L, 20L);
    }

    private void countdownTitle(Player p, int n, String rival) {
        msg.title(p, "geri-sayim-title", "geri-sayim-subtitle", 0, 25, 0, "sayi", n, "rakip", rival);
        PlayerUtil.sound(p, cfg(), "geri-sayim");
    }

    private Match startMatch(EventSession s, Room room, Participant pa, Participant pb) {
        Match m = new Match(room, pa, pb, cfg().countdown);
        s.matches.put(room.key(), m);
        ItemStack[] kit = s.def.taggedKit();
        prepare(online(pa.uuid), pa, m, room.spawn(1), kit, pb.name, room.name);
        prepare(online(pb.uuid), pb, m, room.spawn(2), kit, pa.name, room.name);
        tellParticipants("mac-duyuru", "a", pa.name, "b", pb.name, "oda", room.name);
        if (m.countdown == 0) {
            m.started = true;
            m.timeLeft = cfg().maxDuration;
        }
        return m;
    }

    private void prepare(Player p, Participant pt, Match m, Location spawn, ItemStack[] kit, String rival, String room) {
        pt.state = State.IN_MATCH;
        pt.match = m;
        pt.matchesPlayed++;
        PlayerUtil.reset(p);
        teleport(p, spawn);
        PlayerUtil.applyKit(p, kit);
        msg.send(p, "mac-basliyor", "rakip", rival, "oda", room);
    }

    private void tickMatch(Match m) {
        if (m.ended) return;
        Player a = online(m.a);
        Player b = online(m.b);
        if (a == null || b == null) return;
        if (!m.started) return; // geri sayım dalga sayacında
        if (cfg().maxDuration <= 0) return;
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
        UUID winner = Math.abs(ra - rb) < 1e-6
                ? (ThreadLocalRandom.current().nextBoolean() ? m.a : m.b)
                : (ra > rb ? m.a : m.b);
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
                || ((reason == Reason.QUIT || reason == Reason.LEAVE) && cfg().killOnQuit);
        if (pw != null && countKill) pw.kills++;
        String sebep = msg.get(switch (reason) {
            case DEATH -> "sebep-olum";
            case QUIT -> "sebep-cikis";
            case LEAVE -> "sebep-ayrildi";
            case TIMEOUT -> "sebep-sure";
        });
        tellParticipants("mac-sonucu", "kazanan", pw != null ? pw.name : "?", "kaybeden", pl != null ? pl.name : "?",
                "oda", m.room.name, "sebep", sebep);
        if (pl != null) {
            if (lp != null) {
                msg.title(lp, "kaybettin-title", "kaybettin-subtitle", 5, 40, 10);
                PlayerUtil.sound(lp, cfg(), "kaybettin");
            }
            eliminate(pl, lp, reason == Reason.DEATH, reason == Reason.LEAVE ? "ayrildin" : "elendin");
        }
        if (wp != null && !wp.isDead()) {
            // kazananın kiti hemen silinir; sonraki eşleşmede kit yeniden, eksiksiz verilir
            PlayerUtil.reset(wp);
            msg.title(wp, "kazandin-title", "kazandin-subtitle", 5, 40, 10);
            PlayerUtil.sound(wp, cfg(), "kazandin");
            wp.setFireTicks(0);
        }
        Bukkit.getScheduler().runTaskLater(plugin, () -> finalizeMatch(s, m), cfg().endDelay * 20L);
    }

    private void finalizeMatch(EventSession s, Match m) {
        if (session != s || s.matches.get(m.room.key()) != m) return;
        Participant pw = s.get(m.winner);
        if (pw != null && pw.state == State.IN_MATCH && pw.match == m) {
            Player wp = online(pw.uuid);
            pw.match = null;
            pw.state = State.WAITING;
            if (wp != null && !wp.isDead()) {
                PlayerUtil.reset(wp);
                teleport(wp, eventSpawn());
                msg.send(wp, "tura-yukseldin");
            }
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
        Region r = m.room.region;
        BoundingBox box = new BoundingBox(r.minX, r.minY, r.minZ, r.maxX + 1, r.maxY + 1, r.maxZ + 1);
        for (Entity e : w.getNearbyEntities(box)) {
            if (e instanceof LivingEntity || e instanceof Hanging) continue;
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
                releaseAfterRespawn.put(pt.uuid, pt.gameMode);
            } else {
                release(p, pt.gameMode);
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
            plugin.store().addHistory(s.def.time, s.participants.size(), null);
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

        List<String> lines = new ArrayList<>(msg.list("sonuc-baslik", "saat", s.def.time, "sayi", ranking.size()));
        int show = Math.min(cfg().showTop, ranking.size());
        for (int i = 0; i < show; i++) {
            Participant p = ranking.get(i);
            lines.add(msg.get("sonuc-satir", "sira", i + 1, "player", p.name, "kill", p.kills));
        }
        lines.addAll(msg.list("sonuc-alt", "saat", s.def.time, "sayi", ranking.size()));
        for (Player p : Bukkit.getOnlinePlayers()) for (String l : lines) p.sendMessage(l);
        for (String l : lines) Bukkit.getConsoleSender().sendMessage(l);

        Player cp = online(champion.uuid);
        if (cp != null) {
            msg.title(cp, "sampiyon-title", "sampiyon-subtitle", 10, 60, 20, "player", champion.name);
            PlayerUtil.sound(cp, cfg(), "sampiyon");
        }
        plugin.store().addHistory(s.def.time, ranking.size(), champion.name);

        for (int i = 0; i < ranking.size(); i++) {
            Participant p = ranking.get(i);
            List<String> cmds = new ArrayList<>(cfg().rewards.getOrDefault(i + 1, Collections.emptyList()));
            if (p.matchesPlayed > 0) cmds.addAll(cfg().participationReward);
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
        session = null;
        for (Participant pt : s.participants.values()) {
            if (pt.state == State.ELIMINATED) continue;
            pt.state = State.ELIMINATED;
            releaseLater(pt);
        }
    }

    /** Eventi durdurur, odaları onarır, herkesi çıkarır. messageKey null ise mesaj yok. */
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
            releaseLater(pt);
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
        if (cfg().hideDeath) e.setDeathMessage(null);
        UUID u = p.getUniqueId();
        if (s.phase == EventSession.Phase.JOIN) {
            s.participants.remove(u);
            Location h = hub();
            if (h != null) respawnTo.put(u, h);
            releaseAfterRespawn.put(u, pt.gameMode);
        } else if (pt.state == State.IN_MATCH && pt.match != null && !pt.match.ended) {
            finishMatch(pt.match, pt.match.opponent(u), u, Reason.DEATH);
        } else if (pt.state == State.IN_MATCH && pt.match != null) {
            // maçı kazandıktan hemen sonra öldü (yanma vb.): elenmez, bekleme alanında doğar
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
        boolean release = releaseAfterRespawn.containsKey(u);
        GameMode gm = releaseAfterRespawn.remove(u);
        boolean reset = resetAfterRespawn.remove(u);
        if (!release && !reset) return;
        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            if (!p.isOnline()) return;
            if (release) {
                release(p, gm);
            } else if (participant(p) != null) {
                PlayerUtil.reset(p);
                teleport(p, eventSpawn());
            } else {
                PlayerUtil.reset(p);
                teleport(p, hub());
            }
        }, 2L);
    }

    // ------------------------------------------------------------------ dinleyiciler için sorgular

    /** Bu bloğu içeren aktif maç. Maç yokken anında null. */
    public Match matchAt(Block b) {
        EventSession s = session;
        if (s == null || s.matches.isEmpty()) return null;
        for (Match m : s.matches.values()) {
            if (m.room.region.contains(b)) return m;
        }
        return null;
    }

    /** Aktif eventin herhangi bir odası bu bloğu içeriyor mu (patlama koruması) */
    public boolean inAnyRoom(Block b) {
        EventSession s = session;
        if (s == null) return false;
        for (Room r : s.def.rooms.values()) {
            if (r.region.contains(b)) return true;
        }
        return false;
    }
}
