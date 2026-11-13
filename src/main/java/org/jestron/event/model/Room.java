package org.jestron.event.model;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.block.Block;

import java.util.Locale;

/** Bir event saatine ait 1v1 odası. */
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

    /**
     * Odanın uzun ekseninin iki ucuna, birbirine bakan iki güvenli spawn hesaplar.
     */
    public static Pos autoSpawn(Region r, boolean first) {
        World w = Bukkit.getWorld(r.world);
        if (w == null) return null;
        int lenX = r.maxX - r.minX + 1;
        int lenZ = r.maxZ - r.minZ + 1;
        boolean alongX = lenX >= lenZ;
        int len = alongX ? lenX : lenZ;
        int inset = Math.max(1, Math.min(3, len / 4));
        if (len <= 2) inset = 0;
        int x;
        int z;
        float yaw;
        if (alongX) {
            x = first ? r.minX + inset : r.maxX - inset;
            z = Math.floorDiv(r.minZ + r.maxZ, 2);
            yaw = first ? -90f : 90f;
        } else {
            z = first ? r.minZ + inset : r.maxZ - inset;
            x = Math.floorDiv(r.minX + r.maxX, 2);
            yaw = first ? 0f : 180f;
        }
        int y = standY(w, x, z, r.minY, r.maxY);
        return new Pos(r.world, x + 0.5, y, z + 0.5, yaw, 0f);
    }

    private static int standY(World w, int x, int z, int minY, int maxY) {
        for (int y = maxY - 1; y >= minY; y--) {
            Block b = w.getBlockAt(x, y, z);
            if (b.getType().isSolid() && w.getBlockAt(x, y + 1, z).isPassable()
                    && w.getBlockAt(x, y + 2, z).isPassable()) {
                return y + 1;
            }
        }
        return minY + 1;
    }
}
