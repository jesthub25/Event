package org.jestron.event.command;

import org.bukkit.Location;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabExecutor;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.jestron.event.EventPlugin;
import org.jestron.event.game.EventManager;
import org.jestron.event.game.EventSession;
import org.jestron.event.model.EventDef;
import org.jestron.event.model.Pos;
import org.jestron.event.model.Region;
import org.jestron.event.model.Room;
import org.jestron.event.selection.SelectionService;
import org.jestron.event.storage.DataStore;
import org.jestron.event.util.Messages;
import org.jestron.event.util.PlayerUtil;
import org.jestron.event.util.Text;
import org.jestron.event.util.TimeUtil;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Date;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.TimeZone;
import java.util.regex.Pattern;

public final class EventCommand implements TabExecutor {

    private static final Pattern ROOM_NAME = Pattern.compile("[A-Za-z0-9_\\-]{1,32}");
    private static final List<String> PLAYER_SUBS = Arrays.asList("katil", "ayril", "bilgi", "gecmis", "yardim");
    private static final List<String> ADMIN_SUBS = Arrays.asList("oluştur", "sil", "liste", "setspawn", "sethub",
            "alan", "oda", "kit", "başlat", "durdur", "wand", "reload");

    private final EventPlugin plugin;
    private final Messages msg;

    public EventCommand(EventPlugin plugin) {
        this.plugin = plugin;
        this.msg = plugin.messages();
    }

    private DataStore store() {
        return plugin.store();
    }

    private EventManager manager() {
        return plugin.manager();
    }

    private static boolean admin(CommandSender s) {
        return s.hasPermission("event.admin");
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (args.length == 0) {
            if (sender instanceof Player p) manager().join(p);
            else help(sender);
            return true;
        }
        String sub = Text.normalize(args[0]);
        switch (sub) {
            case "katil", "join" -> {
                Player p = player(sender);
                if (p != null) manager().join(p);
            }
            case "ayril", "cik", "leave" -> {
                Player p = player(sender);
                if (p != null) manager().leave(p);
            }
            case "bilgi", "durum", "info" -> info(sender);
            case "gecmis", "history" -> history(sender);
            case "yardim", "help" -> help(sender);
            default -> {
                if (!admin(sender)) {
                    msg.send(sender, "yetki-yok");
                    return true;
                }
                adminCommand(sender, sub, args);
            }
        }
        return true;
    }

    private Player player(CommandSender s) {
        if (s instanceof Player p) return p;
        msg.send(s, "sadece-oyuncu");
        return null;
    }

    private void adminCommand(CommandSender sender, String sub, String[] args) {
        switch (sub) {
            case "olustur", "create" -> create(sender, args);
            case "sil", "delete" -> delete(sender, args);
            case "liste", "list" -> list(sender);
            case "setspawn" -> setSpawn(sender, args);
            case "sethub" -> setHub(sender);
            case "alan" -> area(sender, args);
            case "oda" -> room(sender, args);
            case "kit" -> kit(sender, args);
            case "baslat", "start" -> start(sender, args);
            case "durdur", "stop" -> {
                if (manager().session() == null) msg.send(sender, "aktif-event-yok");
                else manager().stop("event-durduruldu");
            }
            case "wand" -> {
                Player p = player(sender);
                if (p == null) return;
                p.getInventory().addItem(plugin.selection().createWand());
                msg.send(p, "wand-verildi");
            }
            case "reload" -> {
                plugin.reloadPlugin();
                msg.send(sender, "reload");
            }
            default -> help(sender);
        }
    }

    // ------------------------------------------------------------ yardımcılar

    private EventDef requireEvent(CommandSender s, String raw) {
        String time = TimeUtil.parseTime(raw);
        if (time == null) {
            msg.send(s, "gecersiz-saat", "saat", raw);
            return null;
        }
        EventDef def = store().event(time);
        if (def == null) msg.send(s, "event-bulunamadi", "saat", time);
        return def;
    }

    private Region requireSelection(Player p) {
        SelectionService.Result r = plugin.selection().get(p);
        if (r.region == null) {
            msg.send(p, r.error);
            return null;
        }
        return r.region;
    }

    private static String yesNo(boolean b) {
        return b ? "&a✔" : "&c✘";
    }

    // ------------------------------------------------------------ event saatleri

    private void create(CommandSender s, String[] args) {
        if (args.length < 2) {
            msg.send(s, "kullanim", "kullanim", "/event oluştur <saat>  (örn 20:00)");
            return;
        }
        String time = TimeUtil.parseTime(args[1]);
        if (time == null) {
            msg.send(s, "gecersiz-saat", "saat", args[1]);
            return;
        }
        if (store().event(time) != null) {
            msg.send(s, "event-zaten-var", "saat", time);
            return;
        }
        EventDef def = new EventDef(time);
        // bugün bu saat geçtiyse bugün tekrar tetiklenmesin diye değil; sadece tanım oluşturulur
        store().events.put(time, def);
        store().save();
        msg.send(s, "event-olusturuldu", "saat", time);
    }

    private void delete(CommandSender s, String[] args) {
        if (args.length < 2) {
            msg.send(s, "kullanim", "kullanim", "/event sil <saat>");
            return;
        }
        EventDef def = requireEvent(s, args[1]);
        if (def == null) return;
        if (manager().isRunning(def)) {
            msg.send(s, "event-calisirken-olmaz");
            return;
        }
        store().events.remove(def.time);
        store().save();
        msg.send(s, "event-silindi", "saat", def.time);
    }

    private void list(CommandSender s) {
        if (store().events.isEmpty()) {
            msg.send(s, "liste-bos");
            return;
        }
        msg.send(s, "liste-baslik");
        for (EventDef def : store().events.values()) {
            msg.send(s, "liste-satir", "saat", def.time,
                    "alan", Text.color(yesNo(def.area != null)),
                    "spawn", Text.color(yesNo(def.spawn != null)),
                    "kit", Text.color(yesNo(def.kitItemCount() > 0)),
                    "oda", def.readyRooms() + "/" + def.rooms.size());
        }
    }

    private void setHub(CommandSender s) {
        Player p = player(s);
        if (p == null) return;
        store().hub = Pos.of(p.getLocation());
        store().save();
        msg.send(p, "hub-ayarlandi", "konum", store().hub.describe());
    }

    private void setSpawn(CommandSender s, String[] args) {
        Player p = player(s);
        if (p == null) return;
        EventDef def;
        if (args.length >= 2) {
            def = requireEvent(p, args[1]);
            if (def == null) return;
        } else {
            def = null;
            if (store().events.size() == 1) {
                def = store().events.values().iterator().next();
            } else {
                for (EventDef d : store().events.values()) {
                    if (d.area != null && d.area.contains(p.getLocation())) {
                        def = d;
                        break;
                    }
                }
            }
            if (def == null) {
                msg.send(p, store().events.isEmpty() ? "liste-bos" : "spawn-saat-belirt");
                return;
            }
        }
        if (def.area != null && !def.area.contains(p.getLocation())) {
            msg.send(p, "spawn-alan-disinda", "saat", def.time);
            return;
        }
        def.spawn = Pos.of(p.getLocation());
        store().save();
        msg.send(p, "spawn-ayarlandi", "saat", def.time, "konum", def.spawn.describe());
    }

    // ------------------------------------------------------------ alan

    private void area(CommandSender s, String[] args) {
        if (args.length < 3) {
            msg.send(s, "kullanim", "kullanim", "/event alan oluştur|sil <saat>");
            return;
        }
        String action = Text.normalize(args[1]);
        EventDef def = requireEvent(s, args[2]);
        if (def == null) return;
        if (manager().isRunning(def)) {
            msg.send(s, "event-calisirken-olmaz");
            return;
        }
        switch (action) {
            case "olustur", "ayarla", "create", "set" -> {
                Player p = player(s);
                if (p == null) return;
                Region r = requireSelection(p);
                if (r == null) return;
                def.area = r;
                store().save();
                msg.send(p, "alan-ayarlandi", "saat", def.time, "alan", r.describe());
                if (def.spawn != null && !r.contains(def.spawn.toLocation())) {
                    msg.send(p, "spawn-alan-disinda", "saat", def.time);
                }
            }
            case "sil", "delete" -> {
                def.area = null;
                store().save();
                msg.send(s, "alan-silindi", "saat", def.time);
            }
            default -> msg.send(s, "kullanim", "kullanim", "/event alan oluştur|sil <saat>");
        }
    }

    // ------------------------------------------------------------ odalar

    private void room(CommandSender s, String[] args) {
        if (args.length < 2) {
            msg.send(s, "kullanim", "kullanim", "/event oda oluştur|sil|spawn|liste ...");
            return;
        }
        String action = Text.normalize(args[1]);
        switch (action) {
            case "olustur", "create" -> roomCreate(s, args);
            case "sil", "delete" -> roomDelete(s, args);
            case "spawn", "setspawn" -> roomSpawn(s, args);
            case "liste", "list" -> roomList(s, args);
            default -> msg.send(s, "kullanim", "kullanim", "/event oda oluştur|sil|spawn|liste ...");
        }
    }

    private void roomCreate(CommandSender s, String[] args) {
        if (args.length < 4) {
            msg.send(s, "kullanim", "kullanim", "/event oda oluştur <isim> <saat>");
            return;
        }
        Player p = player(s);
        if (p == null) return;
        String name = args[2];
        if (!ROOM_NAME.matcher(name).matches()) {
            msg.send(p, "oda-isim-gecersiz");
            return;
        }
        EventDef def = requireEvent(p, args[3]);
        if (def == null) return;
        if (def.room(name) != null) {
            msg.send(p, "oda-zaten-var", "oda", name);
            return;
        }
        if (manager().isRunning(def)) {
            msg.send(p, "event-calisirken-olmaz");
            return;
        }
        Region r = requireSelection(p);
        if (r == null) return;
        Room room = new Room(name, r, Room.autoSpawn(r, true), Room.autoSpawn(r, false));
        def.rooms.put(room.key(), room);
        store().save();
        msg.send(p, "oda-olusturuldu", "oda", name, "saat", def.time,
                "s1", room.spawn1 == null ? "-" : room.spawn1.describe(),
                "s2", room.spawn2 == null ? "-" : room.spawn2.describe());
        msg.send(p, "oda-spawn-ipucu", "oda", name, "saat", def.time);
    }

    private void roomDelete(CommandSender s, String[] args) {
        if (args.length < 4) {
            msg.send(s, "kullanim", "kullanim", "/event oda sil <isim> <saat>");
            return;
        }
        EventDef def = requireEvent(s, args[3]);
        if (def == null) return;
        Room room = def.room(args[2]);
        if (room == null) {
            msg.send(s, "oda-bulunamadi", "oda", args[2], "saat", def.time);
            return;
        }
        EventSession session = manager().session();
        if (session != null && session.def == def && session.matches.containsKey(room.key())) {
            msg.send(s, "oda-kullanimda");
            return;
        }
        def.rooms.remove(room.key());
        store().save();
        msg.send(s, "oda-silindi", "oda", room.name, "saat", def.time);
    }

    private void roomSpawn(CommandSender s, String[] args) {
        if (args.length < 5) {
            msg.send(s, "kullanim", "kullanim", "/event oda spawn <isim> <saat> <1|2>");
            return;
        }
        Player p = player(s);
        if (p == null) return;
        EventDef def = requireEvent(p, args[3]);
        if (def == null) return;
        Room room = def.room(args[2]);
        if (room == null) {
            msg.send(p, "oda-bulunamadi", "oda", args[2], "saat", def.time);
            return;
        }
        int no;
        if (args[4].equals("1")) no = 1;
        else if (args[4].equals("2")) no = 2;
        else {
            msg.send(p, "kullanim", "kullanim", "/event oda spawn <isim> <saat> <1|2>");
            return;
        }
        Location l = p.getLocation();
        if (!room.region.containsLoose(l, 0, 2)) {
            msg.send(p, "oda-spawn-disinda");
            return;
        }
        Pos pos = Pos.of(l);
        if (no == 1) room.spawn1 = pos;
        else room.spawn2 = pos;
        store().save();
        msg.send(p, "oda-spawn-ayarlandi", "oda", room.name, "no", no, "konum", pos.describe());
    }

    private void roomList(CommandSender s, String[] args) {
        List<EventDef> defs = new ArrayList<>();
        if (args.length >= 3) {
            EventDef def = requireEvent(s, args[2]);
            if (def == null) return;
            defs.add(def);
        } else {
            defs.addAll(store().events.values());
        }
        if (defs.isEmpty()) {
            msg.send(s, "liste-bos");
            return;
        }
        for (EventDef def : defs) {
            if (def.rooms.isEmpty()) {
                msg.send(s, "oda-liste-baslik", "saat", def.time, "sayi", 0);
                msg.send(s, "oda-liste-bos");
                continue;
            }
            msg.send(s, "oda-liste-baslik", "saat", def.time, "sayi", def.rooms.size());
            for (Room r : def.rooms.values()) {
                msg.send(s, "oda-liste-satir", "oda", r.name, "alan", r.region.describe());
            }
        }
    }

    // ------------------------------------------------------------ kit

    private void kit(CommandSender s, String[] args) {
        if (args.length < 3) {
            msg.send(s, "kullanim", "kullanim", "/event kit ayarla|al|sil <saat>");
            return;
        }
        String action = Text.normalize(args[1]);
        EventDef def = requireEvent(s, args[2]);
        if (def == null) return;
        switch (action) {
            case "ayarla", "set", "kaydet" -> {
                Player p = player(s);
                if (p == null) return;
                if (manager().isRunning(def)) {
                    msg.send(p, "event-calisirken-olmaz");
                    return;
                }
                ItemStack[] contents = p.getInventory().getContents();
                ItemStack[] copy = new ItemStack[contents.length];
                int count = 0;
                for (int i = 0; i < contents.length; i++) {
                    if (contents[i] != null && !contents[i].getType().isAir()) {
                        copy[i] = contents[i].clone();
                        count++;
                    }
                }
                if (count == 0) {
                    msg.send(p, "kit-bos");
                    return;
                }
                def.kit = copy;
                store().save();
                msg.send(p, "kit-ayarlandi", "saat", def.time, "sayi", count);
            }
            case "al", "yukle", "gor" -> {
                Player p = player(s);
                if (p == null) return;
                if (def.kitItemCount() == 0) {
                    msg.send(p, "kit-yok");
                    return;
                }
                if (manager().participant(p) != null) {
                    msg.send(p, "zaten-katildin");
                    return;
                }
                if (!PlayerUtil.isInventoryEmpty(p)) {
                    msg.send(p, "kit-envanter-dolu");
                    return;
                }
                PlayerUtil.applyKit(p, def.kit);
                msg.send(p, "kit-alindi", "saat", def.time);
            }
            case "sil", "delete" -> {
                if (manager().isRunning(def)) {
                    msg.send(s, "event-calisirken-olmaz");
                    return;
                }
                def.kit = null;
                store().save();
                msg.send(s, "kit-silindi", "saat", def.time);
            }
            default -> msg.send(s, "kullanim", "kullanim", "/event kit ayarla|al|sil <saat>");
        }
    }

    // ------------------------------------------------------------ başlat / bilgi / geçmiş / yardım

    private void start(CommandSender s, String[] args) {
        if (args.length < 2) {
            msg.send(s, "kullanim", "kullanim", "/event başlat <saat>");
            return;
        }
        EventDef def = requireEvent(s, args[1]);
        if (def == null) return;
        if (manager().open(def, s)) msg.send(s, "event-zorla-acildi", "saat", def.time);
    }

    private void info(CommandSender s) {
        EventSession session = manager().session();
        if (session == null) {
            msg.send(s, "bilgi-yok");
            Object[] next = TimeUtil.next(store().events.keySet(), manager().zone());
            if (next != null) msg.send(s, "sonraki-event", "saat", next[0], "sure", TimeUtil.formatDuration((Long) next[1]));
            return;
        }
        if (session.phase == EventSession.Phase.JOIN) {
            msg.send(s, "bilgi-katilim", "saat", session.def.time, "sure", TimeUtil.formatDuration(session.joinLeft),
                    "sayi", session.participants.size());
        } else {
            msg.send(s, "bilgi-turnuva", "saat", session.def.time, "tur", Math.max(1, session.round),
                    "kalan", session.aliveCount(), "mac", session.matches.size());
        }
    }

    private void history(CommandSender s) {
        List<String[]> rows = plugin.database().recentHistory(5);
        if (rows.isEmpty()) {
            msg.send(s, "gecmis-bos");
            return;
        }
        SimpleDateFormat f = new SimpleDateFormat("dd.MM.yyyy HH:mm", new Locale("tr", "TR"));
        f.setTimeZone(TimeZone.getTimeZone(manager().zone()));
        msg.send(s, "gecmis-baslik");
        for (String[] r : rows) {
            msg.send(s, "gecmis-satir", "tarih", f.format(new Date(Long.parseLong(r[1]))), "saat", r[0],
                    "kazanan", r[3] == null ? "-" : r[3], "sayi", r[2]);
        }
    }

    private void help(CommandSender s) {
        msg.send(s, "yardim-oyuncu");
        if (admin(s)) msg.send(s, "yardim-admin");
    }

    // ------------------------------------------------------------ tab tamamlama

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        boolean admin = admin(sender);
        List<String> out = new ArrayList<>();
        if (args.length == 1) {
            out.addAll(PLAYER_SUBS);
            if (admin) out.addAll(ADMIN_SUBS);
            return filter(out, args[0]);
        }
        if (!admin) return Collections.emptyList();
        String sub = Text.normalize(args[0]);
        List<String> times = new ArrayList<>(store().events.keySet());
        switch (sub) {
            case "olustur" -> {
                if (args.length == 2) out.addAll(Arrays.asList("20:00", "21:00", "22:00"));
            }
            case "sil", "setspawn", "baslat" -> {
                if (args.length == 2) out.addAll(times);
            }
            case "alan", "kit" -> {
                if (args.length == 2) {
                    out.addAll(sub.equals("alan") ? Arrays.asList("oluştur", "sil") : Arrays.asList("ayarla", "al", "sil"));
                } else if (args.length == 3) {
                    out.addAll(times);
                }
            }
            case "oda" -> {
                if (args.length == 2) {
                    out.addAll(Arrays.asList("oluştur", "sil", "spawn", "liste"));
                } else {
                    String action = Text.normalize(args[1]);
                    if (action.equals("olustur")) {
                        if (args.length == 3) out.add("<isim>");
                        else if (args.length == 4) out.addAll(times);
                    } else if (action.equals("sil") || action.equals("spawn")) {
                        if (args.length == 3) {
                            Set<String> names = new LinkedHashSet<>();
                            for (EventDef d : store().events.values()) {
                                for (Room r : d.rooms.values()) names.add(r.name);
                            }
                            out.addAll(names);
                        } else if (args.length == 4) {
                            for (EventDef d : store().events.values()) {
                                if (d.room(args[2]) != null) out.add(d.time);
                            }
                        } else if (args.length == 5 && action.equals("spawn")) {
                            out.addAll(Arrays.asList("1", "2"));
                        }
                    } else if (action.equals("liste") && args.length == 3) {
                        out.addAll(times);
                    }
                }
            }
            default -> {
            }
        }
        return filter(out, args[args.length - 1]);
    }

    private static List<String> filter(List<String> in, String typed) {
        String t = Text.normalize(typed);
        List<String> out = new ArrayList<>();
        for (String s : in) {
            if (Text.normalize(s).startsWith(t)) out.add(s);
        }
        return out;
    }
}
