package org.jestron.event.util;

import org.bukkit.GameMode;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.potion.PotionEffect;

import java.util.ArrayList;

public final class PlayerUtil {

    /** Kit eşyalarına basılan görünmez etiket; event dışına çıkan kit eşyası her yerde silinir. */
    private static NamespacedKey kitKey;

    private PlayerUtil() {
    }

    public static void init(NamespacedKey key) {
        kitKey = key;
    }

    /** Envanter, zırh, sol el ve imleç tamamen boş mu? */
    public static boolean isInventoryEmpty(Player p) {
        for (ItemStack it : p.getInventory().getContents()) {
            if (it != null && it.getType() != Material.AIR) return false;
        }
        ItemStack cursor = p.getItemOnCursor();
        return cursor == null || cursor.getType() == Material.AIR;
    }

    public static void clearInventory(Player p) {
        p.setItemOnCursor(null);
        PlayerInventory inv = p.getInventory();
        inv.clear();
        inv.setArmorContents(new ItemStack[4]);
        inv.setItemInOffHand(null);
    }

    /** Envanteri temizler, efektleri siler, canı/açlığı doldurur, ateşi ve uçuşu kapatır. */
    @SuppressWarnings("deprecation")
    public static void reset(Player p) {
        p.closeInventory();
        clearInventory(p);
        for (PotionEffect e : new ArrayList<>(p.getActivePotionEffects())) p.removePotionEffect(e.getType());
        if (p.getGameMode() != GameMode.SURVIVAL) p.setGameMode(GameMode.SURVIVAL);
        p.setFlying(false);
        p.setAllowFlight(false);
        p.setGliding(false);
        if (!p.isDead()) p.setHealth(p.getMaxHealth());
        p.setFoodLevel(20);
        p.setSaturation(20f);
        p.setExhaustion(0f);
        p.setFireTicks(0);
        p.setFallDistance(0f);
        p.updateInventory();
    }

    /** Kaydedilen kitin etiketli kopyası (event başına bir kez hazırlanır). */
    public static ItemStack[] tagKit(ItemStack[] kit) {
        ItemStack[] out = new ItemStack[kit.length];
        for (int i = 0; i < kit.length; i++) {
            ItemStack it = kit[i];
            if (it == null || it.getType() == Material.AIR) continue;
            ItemStack c = it.clone();
            ItemMeta meta = c.getItemMeta();
            if (meta != null) {
                meta.getPersistentDataContainer().set(kitKey, PersistentDataType.BYTE, (byte) 1);
                c.setItemMeta(meta);
            }
            out[i] = c;
        }
        return out;
    }

    public static void applyKit(Player p, ItemStack[] taggedKit) {
        PlayerInventory inv = p.getInventory();
        int size = inv.getContents().length;
        ItemStack[] arr = new ItemStack[size];
        for (int i = 0; i < Math.min(size, taggedKit.length); i++) {
            if (taggedKit[i] != null) arr[i] = taggedKit[i].clone();
        }
        inv.setContents(arr);
        p.updateInventory();
    }

    public static boolean isKitItem(ItemStack it) {
        if (it == null || it.getType() == Material.AIR || !it.hasItemMeta()) return false;
        ItemMeta meta = it.getItemMeta();
        return meta != null && meta.getPersistentDataContainer().has(kitKey, PersistentDataType.BYTE);
    }

    /** Envanterdeki kit eşyalarını siler (çökme / sızıntı güvenliği). */
    public static void stripKitItems(Player p) {
        PlayerInventory inv = p.getInventory();
        ItemStack[] c = inv.getContents();
        boolean changed = false;
        for (int i = 0; i < c.length; i++) {
            if (isKitItem(c[i])) {
                c[i] = null;
                changed = true;
            }
        }
        if (changed) {
            inv.setContents(c);
            p.updateInventory();
        }
        if (isKitItem(p.getItemOnCursor())) p.setItemOnCursor(null);
    }

    public static void sound(Player p, Settings s, String key) {
        String snd = s.sounds.get(key);
        if (snd == null) return;
        try {
            p.playSound(p.getLocation(), snd, 1f, 1f);
        } catch (Exception ignored) {
        }
    }
}
