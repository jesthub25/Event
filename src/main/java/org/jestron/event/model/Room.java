package org.jestron.event.model;

import org.bukkit.Location;

import java.util.Locale;

/** Bir event saatine ait 1v1 odası. 1. oyuncu spawn1'e, 2. oyuncu spawn2'ye ışınlanır. */
public final class Room {

    public final String name;
    public final Region region;
    public Pos spawn1;
    public Pos spawn2;

    public Room(String name, Region region, Pos spawn1, Pos spawn2) {
        this.name = name;
        this.region = region;
        this.spawn1 = spawn1;
        this.spawn2 = spawn2;
    }

    public String key() {
        return name.toLowerCase(Locale.ROOT);
    }

    public Location spawn(int no) {
        Pos p = no == 1 ? spawn1 : spawn2;
        return p == null ? null : p.toLocation();
    }

    public boolean isReady() {
        return spawn(1) != null && spawn(2) != null;
    }

    /** Bloğun tam ortası, bakış yönü en yakın ana yöne (K/D/G/B) hizalı, düz bakış. */
    public static Pos alignedSpawn(Location l) {
        float yaw = Math.round(l.getYaw() / 90f) * 90f;
        yaw = ((yaw % 360f) + 360f) % 360f;
        if (yaw > 180f) yaw -= 360f;
        return new Pos(l.getWorld().getName(), l.getBlockX() + 0.5, l.getY(), l.getBlockZ() + 0.5, yaw, 0f);
    }
}
