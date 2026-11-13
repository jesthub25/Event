package org.jestron.event.listener;

import org.bukkit.block.Block;
import org.bukkit.block.BlockState;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockBurnEvent;
import org.bukkit.event.block.BlockExplodeEvent;
import org.bukkit.event.block.BlockFadeEvent;
import org.bukkit.event.block.BlockFormEvent;
import org.bukkit.event.block.BlockFromToEvent;
import org.bukkit.event.block.BlockIgniteEvent;
import org.bukkit.event.block.BlockMultiPlaceEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.block.BlockSpreadEvent;
import org.bukkit.event.entity.EntityChangeBlockEvent;
import org.bukkit.event.entity.EntityExplodeEvent;
import org.bukkit.event.player.PlayerBucketEmptyEvent;
import org.bukkit.event.player.PlayerBucketFillEvent;
import org.jestron.event.EventPlugin;
import org.jestron.event.game.EventManager;
import org.jestron.event.game.Match;
import org.jestron.event.game.Participant;

/**
 * Oda koruması ve maç sonrası onarım için blok değişikliklerinin kaydı.
 * Maç sırasında değişen her bloğun orijinal hali saklanır; maç bitince oda birebir eski haline döner.
 */
public final class BlockListener implements Listener {

    private final EventManager manager;

    public BlockListener(EventPlugin plugin) {
        this.plugin = plugin;
        this.manager = plugin.manager();
    }

    private boolean isOwnLiveRoom(Participant pt, Match m) {
        return pt != null && m != null && pt.match == m && pt.inLiveMatch();
    }

    private final EventPlugin plugin;

    private boolean force() {
        return plugin.settings().force;
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onPlace(BlockPlaceEvent e) {
        Player p = e.getPlayer();
        Participant pt = manager.participant(p);
        Match m = manager.matchAt(e.getBlockPlaced());
        if (pt != null) {
            if (isOwnLiveRoom(pt, m) && plugin.settings().roomPlace) {
                if (e instanceof BlockMultiPlaceEvent multi) {
                    for (BlockState st : multi.getReplacedBlockStates()) {
                        if (!m.room.region.contains(st.getBlock())) {
                            e.setCancelled(true);
                            return;
                        }
                    }
                    for (BlockState st : multi.getReplacedBlockStates()) m.record(st);
                } else {
                    m.record(e.getBlockReplacedState());
                }
                if (force()) e.setCancelled(false);
            } else {
                e.setCancelled(true);
            }
            return;
        }
        if (m != null && !p.hasPermission("event.admin")) e.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onBreak(BlockBreakEvent e) {
        Player p = e.getPlayer();
        Block b = e.getBlock();
        Participant pt = manager.participant(p);
        Match m = manager.matchAt(b);
        if (pt != null) {
            if (isOwnLiveRoom(pt, m) && (m.isChanged(b) || plugin.settings().roomBreak)) {
                m.record(b);
                e.setDropItems(false);
                e.setExpToDrop(0);
                if (force()) e.setCancelled(false);
            } else {
                e.setCancelled(true);
            }
            return;
        }
        if (m != null && !p.hasPermission("event.admin")) e.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onBucketEmpty(PlayerBucketEmptyEvent e) {
        Participant pt = manager.participant(e.getPlayer());
        Block target = e.getBlock();
        Match m = manager.matchAt(target);
        if (pt != null) {
            if (isOwnLiveRoom(pt, m) && plugin.settings().roomPlace) {
                m.record(target);
                if (force()) e.setCancelled(false);
            } else {
                e.setCancelled(true);
            }
            return;
        }
        if (m != null && !e.getPlayer().hasPermission("event.admin")) e.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onBucketFill(PlayerBucketFillEvent e) {
        Participant pt = manager.participant(e.getPlayer());
        Block target = e.getBlockClicked();
        Match m = manager.matchAt(target);
        if (pt != null) {
            if (isOwnLiveRoom(pt, m) && (m.isChanged(target) || plugin.settings().roomBreak)) {
                m.record(target);
                if (force()) e.setCancelled(false);
            } else {
                e.setCancelled(true);
            }
            return;
        }
        if (m != null && !e.getPlayer().hasPermission("event.admin")) e.setCancelled(true);
    }

    // ---------------------------------------------------------- doğal değişimler (sıvı, ateş, form)

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onFlow(BlockFromToEvent e) {
        Match from = manager.matchAt(e.getBlock());
        Match to = manager.matchAt(e.getToBlock());
        if (from != null && to != from) {
            e.setCancelled(true); // sıvı odadan dışarı taşmasın
            return;
        }
        if (to != null) to.record(e.getToBlock());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onForm(BlockFormEvent e) {
        Match m = manager.matchAt(e.getBlock());
        if (m != null) m.record(e.getBlock());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onSpread(BlockSpreadEvent e) {
        Match m = manager.matchAt(e.getBlock());
        if (m != null) m.record(e.getBlock());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onFade(BlockFadeEvent e) {
        Match m = manager.matchAt(e.getBlock());
        if (m != null) m.record(e.getBlock());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onIgnite(BlockIgniteEvent e) {
        Match m = manager.matchAt(e.getBlock());
        if (m != null) m.record(e.getBlock());
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onBurn(BlockBurnEvent e) {
        if (manager.inAnyRoom(e.getBlock())) e.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onEntityChangeBlock(EntityChangeBlockEvent e) {
        Match m = manager.matchAt(e.getBlock());
        if (m != null) m.record(e.getBlock());
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onEntityExplode(EntityExplodeEvent e) {
        e.blockList().removeIf(manager::inAnyRoom);
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onBlockExplode(BlockExplodeEvent e) {
        e.blockList().removeIf(manager::inAnyRoom);
    }
}
