package org.jestron.event.storage;

import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.InvalidConfigurationException;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.potion.PotionEffect;
import org.jestron.event.model.Pos;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Oyuncunun envanterini (slot slot), zırhını, sol elini, XP, can, açlık, oyun modu ve efektlerini
 * YAML metni olarak yedekler. ItemStack'ler Bukkit'in kendi serileştirmesiyle saklanır (NBT, büyü,
 * isim, lore, shulker içerikleri dahil eksiksiz) ve sürüm yükseltmelerinde otomatik dönüştürülür.
 */
public final class PlayerBackup {

    private PlayerBackup() {
    }

    public static String capture(Player p) {
        YamlConfiguration y = new YamlConfiguration();
        ItemStack[] c = p.getInventory().getContents();
        y.set("size", c.length);
        for (int i = 0; i < c.length; i++) {
            ItemStack it = c[i];
            if (it != null && it.getType() != Material.AIR) y.set("inv." + i, it);
        }
        ItemStack cursor = p.getItemOnCursor();
        if (cursor != null && cursor.getType() != Material.AIR) y.set("cursor", cursor);
        y.set("level", p.getLevel());
        y.set("exp", (double) p.getExp());
        y.set("health", p.getHealth());
        y.set("food", p.getFoodLevel());
        y.set("saturation", (double) p.getSaturation());
        y.set("gamemode", p.getGameMode().name());
        y.set("allow-flight", p.getAllowFlight());
        y.set("effects", new ArrayList<>(p.getActivePotionEffects()));
        Pos.of(p.getLocation()).save(y.createSection("location"));
        return y.saveToString();
    }

    /** Yedeği oyuncuya uygular. Başarılıysa true. */
    @SuppressWarnings("deprecation")
    public static boolean apply(Player p, String data, Logger log) {
        YamlConfiguration y = new YamlConfiguration();
        try {
            y.loadFromString(data);
        } catch (InvalidConfigurationException e) {
            log.log(Level.SEVERE, "Yedek cozumlenemedi: " + p.getName(), e);
            return false;
        }
        try {
            PlayerInventory inv = p.getInventory();
            int size = inv.getContents().length;
            ItemStack[] arr = new ItemStack[size];
            List<ItemStack> overflow = new ArrayList<>();
            ConfigurationSection s = y.getConfigurationSection("inv");
            if (s != null) {
                for (String key : s.getKeys(false)) {
                    ItemStack it = s.getItemStack(key);
                    if (it == null) continue;
                    int i;
                    try {
                        i = Integer.parseInt(key);
                    } catch (NumberFormatException e) {
                        overflow.add(it);
                        continue;
                    }
                    if (i >= 0 && i < size) arr[i] = it;
                    else overflow.add(it);
                }
            }
            ItemStack cursor = y.getItemStack("cursor");
            if (cursor != null) overflow.add(cursor);
            inv.setContents(arr);
            if (!overflow.isEmpty()) {
                HashMap<Integer, ItemStack> left = inv.addItem(overflow.toArray(new ItemStack[0]));
                for (Map.Entry<Integer, ItemStack> e : left.entrySet()) {
                    p.getWorld().dropItemNaturally(p.getLocation(), e.getValue());
                }
            }
            p.setLevel(Math.max(0, y.getInt("level", p.getLevel())));
            float exp = (float) y.getDouble("exp", p.getExp());
            p.setExp(Math.max(0f, Math.min(0.9999f, exp)));
            p.setFoodLevel(Math.max(0, Math.min(20, y.getInt("food", 20))));
            p.setSaturation((float) Math.max(0, y.getDouble("saturation", 5)));
            try {
                GameMode gm = GameMode.valueOf(y.getString("gamemode", "SURVIVAL"));
                if (p.getGameMode() != gm) p.setGameMode(gm);
            } catch (IllegalArgumentException ignored) {
            }
            if (y.getBoolean("allow-flight", false)) p.setAllowFlight(true);
            for (PotionEffect e : new ArrayList<>(p.getActivePotionEffects())) p.removePotionEffect(e.getType());
            List<?> effects = y.getList("effects");
            if (effects != null) {
                for (Object o : effects) {
                    if (o instanceof PotionEffect) p.addPotionEffect((PotionEffect) o);
                }
            }
            if (!p.isDead()) {
                double max = p.getMaxHealth();
                double hp = y.getDouble("health", max);
                p.setHealth(Math.max(1.0, Math.min(max, hp)));
            }
            p.setFireTicks(0);
            p.updateInventory();
            return true;
        } catch (Exception e) {
            log.log(Level.SEVERE, "Yedek uygulanamadi: " + p.getName(), e);
            return false;
        }
    }

    public static Location location(String data) {
        YamlConfiguration y = new YamlConfiguration();
        try {
            y.loadFromString(data);
        } catch (InvalidConfigurationException e) {
            return null;
        }
        Pos pos = Pos.load(y.getConfigurationSection("location"));
        return pos == null ? null : pos.toLocation();
    }
}
