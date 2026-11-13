package org.jestron.event.util;

import org.bukkit.GameMode;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.potion.PotionEffect;
import org.jestron.event.EventPlugin;

import java.util.ArrayList;

public final class PlayerUtil {

    private PlayerUtil() {
    }

    /** Oyuncuyu temiz bir duruma getirir: envanter, efektler, can, açlık, ateş, uçuş. */
    @SuppressWarnings("deprecation")
    public static void reset(Player p, boolean clearInventory) {
        if (clearInventory) {
            p.closeInventory();
            p.setItemOnCursor(null);
            PlayerInventory inv = p.getInventory();
            inv.clear();
            inv.setArmorContents(new ItemStack[4]);
            inv.setItemInOffHand(null);
        }
        for (PotionEffect e : new ArrayList<>(p.getActivePotionEffects())) {
            p.removePotionEffect(e.getType());
        }
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

    public static boolean isInventoryEmpty(Player p) {
        for (ItemStack it : p.getInventory().getContents()) {
            if (it != null && it.getType() != Material.AIR) return false;
        }
        return true;
    }

    public static void applyKit(Player p, ItemStack[] kit) {
        PlayerInventory inv = p.getInventory();
        int size = inv.getContents().length;
        ItemStack[] arr = new ItemStack[size];
        for (int i = 0; i < Math.min(size, kit.length); i++) {
            arr[i] = kit[i] == null ? null : kit[i].clone();
        }
        inv.setContents(arr);
        p.updateInventory();
    }

    public static void sound(Player p, String configKey) {
        String s = EventPlugin.get().getConfig().getString("sesler." + configKey, "");
        if (s == null || s.isEmpty()) return;
        try {
            p.playSound(p.getLocation(), s.toLowerCase(java.util.Locale.ROOT), 1f, 1f);
        } catch (Exception ignored) {
        }
    }
}
