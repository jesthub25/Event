package org.jestron.event.listener;

import org.bukkit.Location;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.entity.Projectile;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.EntityPickupItemEvent;
import org.bukkit.event.entity.FoodLevelChangeEvent;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.inventory.InventoryOpenEvent;
import org.bukkit.event.inventory.InventoryType;
import org.bukkit.event.player.PlayerCommandPreprocessEvent;
import org.bukkit.event.player.PlayerDropItemEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerRespawnEvent;
import org.bukkit.event.player.PlayerTeleportEvent;
import org.bukkit.projectiles.ProjectileSource;
import org.jestron.event.EventPlugin;
import org.jestron.event.game.EventManager;
import org.jestron.event.game.EventSession;
import org.jestron.event.game.Match;
import org.jestron.event.game.Participant;
import org.jestron.event.model.Region;
import org.jestron.event.util.Text;

import java.util.List;

public final class PlayerListener implements Listener {

    private final EventPlugin plugin;
    private final EventManager manager;

    public PlayerListener(EventPlugin plugin) {
        this.plugin = plugin;
        this.manager = plugin.manager();
    }

    // ------------------------------------------------------------ giriş / çıkış / ölüm

    @EventHandler(priority = EventPriority.MONITOR)
    public void onJoin(PlayerJoinEvent e) {
        manager.handleJoin(e.getPlayer());
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onQuit(PlayerQuitEvent e) {
        manager.handleQuit(e.getPlayer());
        plugin.selection().clear(e.getPlayer().getUniqueId());
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onDeath(PlayerDeathEvent e) {
        manager.handleDeath(e);
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onRespawn(PlayerRespawnEvent e) {
        manager.handleRespawn(e);
    }

    // ------------------------------------------------------------ hareket / ışınlanma

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onMove(PlayerMoveEvent e) {
        Location to = e.getTo();
        if (to == null) return;
        Location from = e.getFrom();
        Participant pt = manager.participant(e.getPlayer());
        if (pt == null) return;
        EventSession s = manager.session();
        if (pt.state == Participant.State.IN_MATCH && pt.match != null) {
            Match m = pt.match;
            if (!m.started && !m.ended && (from.getX() != to.getX() || from.getZ() != to.getZ())) {
                Location back = from.clone();
                back.setYaw(to.getYaw());
                back.setPitch(to.getPitch());
                e.setTo(back);
                return;
            }
            if (!sameBlock(from, to) && !m.room.region.containsLoose(to, 1, 6)) {
                Location sp = m.room.spawn(m.a.equals(pt.uuid) ? 1 : 2);
                if (sp != null) e.setTo(sp);
            }
            return;
        }
        if (s == null || sameBlock(from, to)) return;
        Region area = s.def.area;
        if (area != null && !area.containsLoose(to, 0, 10)) {
            Location sp = manager.eventSpawn();
            if (sp != null) {
                e.setTo(sp);
                plugin.messages().actionBar(e.getPlayer(), "alan-disi");
            }
        }
    }

    private static boolean sameBlock(Location a, Location b) {
        return a.getBlockX() == b.getBlockX() && a.getBlockY() == b.getBlockY() && a.getBlockZ() == b.getBlockZ()
                && a.getWorld() == b.getWorld();
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onTeleport(PlayerTeleportEvent e) {
        Player p = e.getPlayer();
        if (manager.isAllowedTeleport(p.getUniqueId())) return;
        Participant pt = manager.participant(p);
        if (pt == null) return;
        Location to = e.getTo();
        EventSession s = manager.session();
        boolean ok;
        if (pt.state == Participant.State.IN_MATCH && pt.match != null) {
            ok = !pt.match.ended && pt.match.started && pt.match.room.region.containsLoose(to, 1, 6);
        } else {
            ok = s != null && s.def.area != null && s.def.area.containsLoose(to, 0, 10);
        }
        if (!ok) {
            e.setCancelled(true);
            plugin.messages().send(p, "alan-disi");
        }
    }

    // ------------------------------------------------------------ komut / envanter / eşya

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onCommand(PlayerCommandPreprocessEvent e) {
        Player p = e.getPlayer();
        if (manager.participant(p) == null) return;
        String raw = e.getMessage();
        if (raw.length() < 2) return;
        String label = raw.substring(1).split(" ")[0];
        int colon = label.indexOf(':');
        if (colon >= 0) label = label.substring(colon + 1);
        label = Text.normalize(label);
        if (label.equals("event") || label.equals("etkinlik")) return;
        List<String> allowed = plugin.getConfig().getStringList("ayarlar.izinli-komutlar");
        for (String a : allowed) {
            if (Text.normalize(a).equals(label)) return;
        }
        e.setCancelled(true);
        plugin.messages().send(p, "komut-engellendi");
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onInventoryOpen(InventoryOpenEvent e) {
        if (!(e.getPlayer() instanceof Player p)) return;
        if (manager.participant(p) == null) return;
        InventoryType t = e.getInventory().getType();
        if (t != InventoryType.PLAYER && t != InventoryType.CRAFTING) e.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onDrop(PlayerDropItemEvent e) {
        Participant pt = manager.participant(e.getPlayer());
        if (pt == null) return;
        if (!(pt.inLiveMatch() && manager.cfg("mac-esya-atma", false))) e.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onPickup(EntityPickupItemEvent e) {
        if (!(e.getEntity() instanceof Player p)) return;
        Participant pt = manager.participant(p);
        if (pt != null && pt.state != Participant.State.IN_MATCH) e.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onInteract(PlayerInteractEvent e) {
        Participant pt = manager.participant(e.getPlayer());
        if (pt == null) return;
        if (pt.state == Participant.State.IN_MATCH && pt.match != null && pt.match.started && !pt.match.ended) return;
        // geri sayımda / bekleme alanında eşya kullanımı (inci, iksir vb.) yok
        if (e.getAction() == Action.RIGHT_CLICK_AIR || e.getAction() == Action.RIGHT_CLICK_BLOCK) {
            e.setCancelled(true);
        }
    }

    // ------------------------------------------------------------ hasar / açlık

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onDamage(EntityDamageEvent e) {
        if (!(e.getEntity() instanceof Player p)) return;
        Participant pt = manager.participant(p);
        if (pt == null) return;
        if (pt.state == Participant.State.IN_MATCH && pt.match != null) {
            if (!pt.match.started || pt.match.ended) e.setCancelled(true);
            return;
        }
        if (!manager.cfg("bekleme-alani-hasar", false)) e.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onDamageByEntity(EntityDamageByEntityEvent e) {
        Player attacker = resolvePlayer(e.getDamager());
        Participant pa = attacker == null ? null : manager.participant(attacker);
        Player victim = e.getEntity() instanceof Player v ? v : null;
        Participant pv = victim == null ? null : manager.participant(victim);
        if (pa == null && pv == null) return;
        if (victim == null) {
            // katılımcı canlı vurmasın (canlı maç dışında)
            if (pa != null && !pa.inLiveMatch()) e.setCancelled(true);
            return;
        }
        if (attacker == null) {
            // çevresel varlık (mob, tnt vb.): genel hasar kuralı geçerli
            return;
        }
        boolean ok = pa != null && pv != null && pa.match != null && pa.match == pv.match
                && pa.match.started && !pa.match.ended;
        if (attacker == victim && pa != null && pa.inLiveMatch()) ok = true;
        if (!ok) {
            e.setCancelled(true);
        } else if (manager.cfg("izinleri-zorla", true)) {
            e.setCancelled(false);
        }
    }

    private static Player resolvePlayer(Entity damager) {
        if (damager instanceof Player p) return p;
        if (damager instanceof Projectile proj) {
            ProjectileSource src = proj.getShooter();
            if (src instanceof Player p) return p;
        }
        return null;
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onFood(FoodLevelChangeEvent e) {
        if (!(e.getEntity() instanceof Player p)) return;
        Participant pt = manager.participant(p);
        if (pt == null || pt.state == Participant.State.IN_MATCH) return;
        if (!manager.cfg("bekleme-alani-aclik", false) && e.getFoodLevel() < p.getFoodLevel()) e.setCancelled(true);
    }
}
