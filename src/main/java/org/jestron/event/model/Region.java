package org.jestron.event.model;

import org.bukkit.Location;
import org.bukkit.block.Block;
import org.bukkit.configuration.ConfigurationSection;

/** Blok koordinatlarıyla eksen hizalı kutu bölge (iki uç dahil). */
public final class Region {

    public final String world;
    public final int minX;
    public final int minY;
    public final int minZ;
    public final int maxX;
    public final int maxY;
    public final int maxZ;

    public Region(String world, int x1, int y1, int z1, int x2, int y2, int z2) {
        this.world = world;
        this.minX = Math.min(x1, x2);
        this.minY = Math.min(y1, y2);
        this.minZ = Math.min(z1, z2);
        this.maxX = Math.max(x1, x2);
        this.maxY = Math.max(y1, y2);
        this.maxZ = Math.max(z1, z2);
    }

    public boolean contains(String w, int x, int y, int z) {
        return world.equals(w) && x >= minX && x <= maxX && y >= minY && y <= maxY && z >= minZ && z <= maxZ;
    }

    public boolean contains(Block b) {
        return b != null && contains(b.getWorld().getName(), b.getX(), b.getY(), b.getZ());
    }

    public boolean contains(Location l) {
        if (l == null || l.getWorld() == null) return false;
        return contains(l.getWorld().getName(), l.getBlockX(), l.getBlockY(), l.getBlockZ());
    }

    /**
     * Yatayda pad kadar, aşağıda 2, yukarıda up kadar tolerans tanıyan kontrol.
     * Zıplama / küçük taşmalar yüzünden oyuncuların gereksiz ışınlanmasını önler.
     */
    public boolean containsLoose(Location l, int pad, int up) {
        if (l == null || l.getWorld() == null || !world.equals(l.getWorld().getName())) return false;
        int x = l.getBlockX();
        int y = l.getBlockY();
        int z = l.getBlockZ();
        return x >= minX - pad && x <= maxX + pad && z >= minZ - pad && z <= maxZ + pad
                && y >= minY - 2 && y <= maxY + up;
    }

    public boolean intersects(Region o) {
        return world.equals(o.world) && minX <= o.maxX && maxX >= o.minX && minY <= o.maxY && maxY >= o.minY
                && minZ <= o.maxZ && maxZ >= o.minZ;
    }

    public long volume() {
        return (long) (maxX - minX + 1) * (maxY - minY + 1) * (maxZ - minZ + 1);
    }

    public void save(ConfigurationSection s) {
        s.set("world", world);
        s.set("min-x", minX);
        s.set("min-y", minY);
        s.set("min-z", minZ);
        s.set("max-x", maxX);
        s.set("max-y", maxY);
        s.set("max-z", maxZ);
    }

    public static Region load(ConfigurationSection s) {
        if (s == null || !s.contains("world")) return null;
        return new Region(s.getString("world"), s.getInt("min-x"), s.getInt("min-y"), s.getInt("min-z"),
                s.getInt("max-x"), s.getInt("max-y"), s.getInt("max-z"));
    }

    public String describe() {
        return world + " [" + minX + "," + minY + "," + minZ + "] -> [" + maxX + "," + maxY + "," + maxZ + "] "
                + (maxX - minX + 1) + "x" + (maxY - minY + 1) + "x" + (maxZ - minZ + 1);
    }
}
