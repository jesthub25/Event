package org.jestron.event.listener;

import org.bukkit.block.Block;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.jestron.event.EventPlugin;
import org.jestron.event.model.Pos;

public final class WandListener implements Listener {

    private final EventPlugin plugin;

    public WandListener(EventPlugin plugin) {
        this.plugin = plugin;
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onInteract(PlayerInteractEvent e) {
        if (e.getHand() != EquipmentSlot.HAND) return;
        Player p = e.getPlayer();
        if (!plugin.selection().isWand(e.getItem())) return;
        if (!p.hasPermission("event.admin")) return;
        Block b = e.getClickedBlock();
        if (b == null) return;
        int no;
        if (e.getAction() == Action.LEFT_CLICK_BLOCK) no = 1;
        else if (e.getAction() == Action.RIGHT_CLICK_BLOCK) no = 2;
        else return;
        e.setCancelled(true);
        plugin.selection().setWandPos(p, no - 1, b.getLocation());
        plugin.messages().send(p, "wand-pos", "no", no, "konum", Pos.of(b.getLocation()).describe());
    }
}
