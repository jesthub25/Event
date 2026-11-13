package org.jestron.event.util;

import net.md_5.bungee.api.ChatMessageType;
import net.md_5.bungee.api.chat.BaseComponent;
import net.md_5.bungee.api.chat.ClickEvent;
import net.md_5.bungee.api.chat.HoverEvent;
import net.md_5.bungee.api.chat.TextComponent;
import net.md_5.bungee.api.chat.hover.content.Text;
import org.bukkit.Bukkit;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Mesajlar yüklemede renklendirilip önbelleğe alınır; gönderimde sadece değişkenler değiştirilir. */
public final class Messages {

    private final Map<String, List<String>> cache = new HashMap<>();
    private BaseComponent[] joinButton;

    public void load(FileConfiguration c) {
        cache.clear();
        String prefix = c.getString("mesajlar.prefix", "&8[&61v1 Event&8] &r");
        ConfigurationSection s = c.getConfigurationSection("mesajlar");
        if (s != null) {
            for (String key : s.getKeys(false)) {
                List<String> raw = s.isList(key) ? s.getStringList(key) : Collections.singletonList(s.getString(key, ""));
                List<String> out = new ArrayList<>(raw.size());
                for (String line : raw) {
                    if (line == null || (raw.size() == 1 && line.isEmpty())) continue;
                    out.add(org.jestron.event.util.Text.color(line.replace("%prefix%", prefix)));
                }
                cache.put(key, out);
            }
        }
        List<String> label = cache.getOrDefault("katil-butonu", Collections.emptyList());
        if (label.isEmpty()) {
            joinButton = null;
        } else {
            TextComponent button = new TextComponent(TextComponent.fromLegacyText(label.get(0)));
            button.setClickEvent(new ClickEvent(ClickEvent.Action.RUN_COMMAND, "/event"));
            button.setHoverEvent(new HoverEvent(HoverEvent.Action.SHOW_TEXT,
                    new Text(TextComponent.fromLegacyText(get("katil-butonu-hover")))));
            joinButton = new BaseComponent[]{button};
        }
    }

    private static String apply(String s, Object[] ph) {
        for (int i = 0; i + 1 < ph.length; i += 2) {
            s = s.replace("%" + ph[i] + "%", String.valueOf(ph[i + 1]));
        }
        return s;
    }

    public List<String> list(String key, Object... ph) {
        List<String> raw = cache.get(key);
        if (raw == null) return Collections.singletonList("<" + key + ">");
        if (ph.length == 0 || raw.isEmpty()) return raw;
        List<String> out = new ArrayList<>(raw.size());
        for (String l : raw) out.add(apply(l, ph));
        return out;
    }

    public String get(String key, Object... ph) {
        List<String> l = list(key, ph);
        return l.isEmpty() ? "" : (l.size() == 1 ? l.get(0) : String.join("\n", l));
    }

    public void send(CommandSender to, String key, Object... ph) {
        if (to == null) return;
        for (String line : list(key, ph)) to.sendMessage(line);
    }

    public void broadcast(String key, Object... ph) {
        List<String> lines = list(key, ph);
        if (lines.isEmpty()) return;
        for (Player p : Bukkit.getOnlinePlayers()) for (String l : lines) p.sendMessage(l);
        for (String l : lines) Bukkit.getConsoleSender().sendMessage(l);
    }

    public void sendTo(Collection<? extends Player> players, String key, Object... ph) {
        List<String> lines = list(key, ph);
        if (lines.isEmpty()) return;
        for (Player p : players) for (String l : lines) p.sendMessage(l);
    }

    public void actionBar(Player p, String key, Object... ph) {
        String m = get(key, ph);
        if (!m.isEmpty()) p.spigot().sendMessage(ChatMessageType.ACTION_BAR, TextComponent.fromLegacyText(m));
    }

    public void title(Player p, String titleKey, String subKey, int fadeIn, int stay, int fadeOut, Object... ph) {
        p.sendTitle(titleKey == null ? "" : get(titleKey, ph), subKey == null ? "" : get(subKey, ph), fadeIn, stay, fadeOut);
    }

    public void broadcastJoinButton() {
        if (joinButton == null) return;
        for (Player p : Bukkit.getOnlinePlayers()) p.spigot().sendMessage(joinButton);
    }
}
