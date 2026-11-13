package org.jestron.event.util;

import net.md_5.bungee.api.ChatMessageType;
import net.md_5.bungee.api.chat.BaseComponent;
import net.md_5.bungee.api.chat.ClickEvent;
import net.md_5.bungee.api.chat.HoverEvent;
import net.md_5.bungee.api.chat.TextComponent;
import net.md_5.bungee.api.chat.hover.content.Text;
import org.bukkit.Bukkit;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.jestron.event.EventPlugin;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;

public final class Messages {

    private final EventPlugin plugin;

    public Messages(EventPlugin plugin) {
        this.plugin = plugin;
    }

    private String prefix() {
        return plugin.getConfig().getString("mesajlar.prefix", "&8[&61v1 Event&8] &r");
    }

    private String apply(String raw, Object... ph) {
        String s = raw.replace("%prefix%", prefix());
        for (int i = 0; i + 1 < ph.length; i += 2) {
            s = s.replace("%" + ph[i] + "%", String.valueOf(ph[i + 1]));
        }
        return org.jestron.event.util.Text.color(s);
    }

    /** Tek satırlık mesaj. Config'de liste ise satırlar birleştirilir. Boş string = kapalı. */
    public String get(String key, Object... ph) {
        String path = "mesajlar." + key;
        if (plugin.getConfig().isList(path)) {
            return String.join("\n", list(key, ph));
        }
        String raw = plugin.getConfig().getString(path);
        if (raw == null) raw = "&c<" + key + ">";
        if (raw.isEmpty()) return "";
        return apply(raw, ph);
    }

    public List<String> list(String key, Object... ph) {
        String path = "mesajlar." + key;
        if (!plugin.getConfig().isList(path)) {
            String single = plugin.getConfig().getString(path);
            if (single == null || single.isEmpty()) return Collections.emptyList();
            return Collections.singletonList(apply(single, ph));
        }
        List<String> out = new ArrayList<>();
        for (String line : plugin.getConfig().getStringList(path)) {
            out.add(apply(line, ph));
        }
        return out;
    }

    public void send(CommandSender to, String key, Object... ph) {
        if (to == null) return;
        for (String line : list(key, ph)) to.sendMessage(line);
    }

    public void broadcast(String key, Object... ph) {
        List<String> lines = list(key, ph);
        if (lines.isEmpty()) return;
        for (Player p : Bukkit.getOnlinePlayers()) {
            for (String l : lines) p.sendMessage(l);
        }
        for (String l : lines) Bukkit.getConsoleSender().sendMessage(l);
    }

    public void sendTo(Collection<? extends Player> players, String key, Object... ph) {
        List<String> lines = list(key, ph);
        if (lines.isEmpty()) return;
        for (Player p : players) {
            for (String l : lines) p.sendMessage(l);
        }
    }

    public void actionBar(Player p, String key, Object... ph) {
        String m = get(key, ph);
        if (m.isEmpty()) return;
        p.spigot().sendMessage(ChatMessageType.ACTION_BAR, TextComponent.fromLegacyText(m));
    }

    public void title(Player p, String titleKey, String subKey, int fadeIn, int stay, int fadeOut, Object... ph) {
        String t = titleKey == null ? "" : get(titleKey, ph);
        String s = subKey == null ? "" : get(subKey, ph);
        p.sendTitle(t, s, fadeIn, stay, fadeOut);
    }

    /** Herkese tıklanabilir "/event" butonu gönderir. */
    public void broadcastJoinButton() {
        String label = get("katil-butonu");
        if (label.isEmpty()) return;
        TextComponent button = new TextComponent(TextComponent.fromLegacyText(label));
        button.setClickEvent(new ClickEvent(ClickEvent.Action.RUN_COMMAND, "/event"));
        button.setHoverEvent(new HoverEvent(HoverEvent.Action.SHOW_TEXT,
                new Text(TextComponent.fromLegacyText(get("katil-butonu-hover")))));
        BaseComponent[] msg = new BaseComponent[]{button};
        for (Player p : Bukkit.getOnlinePlayers()) {
            p.spigot().sendMessage(msg);
        }
    }
}
