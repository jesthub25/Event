package org.jestron.event.model;

import org.bukkit.Material;
import org.bukkit.inventory.ItemStack;
import org.jestron.event.util.TimeUtil;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** Bir event saati: alan, spawn, kit ve bu saate ayrılmış odalar. */
public final class EventDef {

    public final String time;
    public Region area;
    public Pos spawn;
    public ItemStack[] kit;
    /** Kitin etiketli kopyası; kit değişince sıfırlanır. */
    private ItemStack[] taggedKit;
    public String lastRunDate;
    public final Map<String, Room> rooms = new LinkedHashMap<>();

    public EventDef(String time) {
        this.time = time;
    }

    public String key() {
        return TimeUtil.key(time);
    }

    public Room room(String name) {
        return name == null ? null : rooms.get(name.toLowerCase(Locale.ROOT));
    }

    public void setKit(ItemStack[] kit) {
        this.kit = kit;
        this.taggedKit = null;
    }

    public ItemStack[] taggedKit() {
        if (taggedKit == null && kit != null) taggedKit = org.jestron.event.util.PlayerUtil.tagKit(kit);
        return taggedKit;
    }

    public int kitItemCount() {
        if (kit == null) return 0;
        int c = 0;
        for (ItemStack it : kit) {
            if (it != null && it.getType() != Material.AIR) c++;
        }
        return c;
    }

    public int readyRooms() {
        int c = 0;
        for (Room r : rooms.values()) if (r.isReady()) c++;
        return c;
    }

    /** Eventin açılabilmesi için eksik olanlar. Boş liste = hazır. */
    public List<String> missing(Pos hub) {
        List<String> m = new ArrayList<>();
        if (hub == null || hub.toLocation() == null) m.add("/event sethub");
        if (area == null) m.add("/event alan oluştur " + time);
        if (spawn == null || spawn.toLocation() == null) m.add("/event setspawn " + time);
        else if (area != null && !area.contains(spawn.toLocation())) m.add("spawn alanın dışında");
        if (kitItemCount() == 0) m.add("/event kit ayarla " + time);
        if (rooms.isEmpty()) m.add("/event oda oluştur <isim> " + time);
        else if (readyRooms() == 0) m.add("/event oda setspawn1|setspawn2 <oda>");
        return m;
    }
}
