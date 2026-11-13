package org.jestron.event.selection;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.Plugin;
import org.jestron.event.EventPlugin;
import org.jestron.event.model.Region;
import org.jestron.event.util.Text;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Pos1/Pos2 seçimini önce WorldEdit / FastAsyncWorldEdit'ten (yansıma ile, sürüm bağımsız) okur,
 * yoksa eklentinin kendi seçim çubuğunu (/event wand) kullanır.
 */
public final class SelectionService {

    public static final String ERR_NONE = "secim-yok";
    public static final String ERR_WORLD = "secim-farkli-dunya";

    private final EventPlugin plugin;
    private final NamespacedKey wandKey;
    private final Map<UUID, Location[]> wandSelections = new HashMap<>();

    public SelectionService(EventPlugin plugin) {
        this.plugin = plugin;
        this.wandKey = new NamespacedKey(plugin, "event_wand");
    }

    public static final class Result {
        public final Region region;
        public final String error;

        Result(Region region, String error) {
            this.region = region;
            this.error = error;
        }
    }

    public Result get(Player p) {
        Region we = fromWorldEdit(p);
        if (we != null) return new Result(we, null);
        Location[] sel = wandSelections.get(p.getUniqueId());
        if (sel == null || sel[0] == null || sel[1] == null) return new Result(null, ERR_NONE);
        if (sel[0].getWorld() == null || !sel[0].getWorld().equals(sel[1].getWorld())) {
            return new Result(null, ERR_WORLD);
        }
        return new Result(new Region(sel[0].getWorld().getName(),
                sel[0].getBlockX(), sel[0].getBlockY(), sel[0].getBlockZ(),
                sel[1].getBlockX(), sel[1].getBlockY(), sel[1].getBlockZ()), null);
    }

    public void setWandPos(Player p, int index, Location l) {
        wandSelections.computeIfAbsent(p.getUniqueId(), k -> new Location[2])[index] = l.clone();
    }

    public void clear(UUID uuid) {
        wandSelections.remove(uuid);
    }

    public ItemStack createWand() {
        ItemStack it = new ItemStack(Material.BLAZE_ROD);
        ItemMeta meta = it.getItemMeta();
        if (meta != null) {
            meta.setDisplayName(Text.color("&6&lEvent Seçim Çubuğu"));
            meta.setLore(Arrays.asList(Text.color("&7Sol tık: &ePos1"), Text.color("&7Sağ tık: &ePos2")));
            meta.getPersistentDataContainer().set(wandKey, PersistentDataType.BYTE, (byte) 1);
            it.setItemMeta(meta);
        }
        return it;
    }

    public boolean isWand(ItemStack it) {
        if (it == null || it.getType() != Material.BLAZE_ROD || !it.hasItemMeta()) return false;
        ItemMeta meta = it.getItemMeta();
        return meta != null && meta.getPersistentDataContainer().has(wandKey, PersistentDataType.BYTE);
    }

    private Plugin worldEdit() {
        Plugin we = Bukkit.getPluginManager().getPlugin("WorldEdit");
        if (we == null || !we.isEnabled()) we = Bukkit.getPluginManager().getPlugin("FastAsyncWorldEdit");
        return (we != null && we.isEnabled()) ? we : null;
    }

    public boolean hasWorldEdit() {
        return worldEdit() != null;
    }

    private Region fromWorldEdit(Player p) {
        Plugin we = worldEdit();
        if (we == null) return null;
        try {
            Method getSession = we.getClass().getMethod("getSession", Player.class);
            Object session = getSession.invoke(we, p);
            if (session == null) return null;
            Object weWorld = session.getClass().getMethod("getSelectionWorld").invoke(session);
            if (weWorld == null) return null;
            Method getSelection = null;
            for (Method m : session.getClass().getMethods()) {
                if (m.getName().equals("getSelection") && m.getParameterCount() == 1) {
                    getSelection = m;
                    break;
                }
            }
            if (getSelection == null) return null;
            Object region;
            try {
                region = getSelection.invoke(session, weWorld);
            } catch (InvocationTargetException incomplete) {
                return null; // IncompleteRegionException
            }
            if (region == null) return null;
            Object min = region.getClass().getMethod("getMinimumPoint").invoke(region);
            Object max = region.getClass().getMethod("getMaximumPoint").invoke(region);
            String worldName = (String) weWorld.getClass().getMethod("getName").invoke(weWorld);
            if (Bukkit.getWorld(worldName) == null) return null;
            return new Region(worldName, coord(min, "x"), coord(min, "y"), coord(min, "z"),
                    coord(max, "x"), coord(max, "y"), coord(max, "z"));
        } catch (Throwable t) {
            plugin.getLogger().fine("WorldEdit secimi okunamadi: " + t);
            return null;
        }
    }

    /** BlockVector3: eski sürümlerde getBlockX(), yeni sürümlerde x() */
    private static int coord(Object vec, String axis) throws Exception {
        String upper = axis.toUpperCase();
        for (String name : new String[]{"getBlock" + upper, axis, "get" + upper}) {
            try {
                Object v = vec.getClass().getMethod(name).invoke(vec);
                return ((Number) v).intValue();
            } catch (NoSuchMethodException ignored) {
            }
        }
        throw new NoSuchMethodException("BlockVector3 koordinat metodu bulunamadi");
    }
}
