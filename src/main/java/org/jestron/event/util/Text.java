package org.jestron.event.util;

import org.bukkit.ChatColor;

import java.util.Locale;

public final class Text {

    private Text() {
    }

    public static String color(String s) {
        if (s == null) return "";
        return ChatColor.translateAlternateColorCodes('&', s);
    }

    /**
     * Komut argümanlarını Türkçe karakterlerden arındırıp küçük harfe çevirir.
     * "Oluştur", "OLUŞTUR", "olustur" -> "olustur"
     */
    public static String normalize(String s) {
        if (s == null) return "";
        String r = s.replace('İ', 'i').replace('I', 'i').replace('ı', 'i')
                .toLowerCase(Locale.ROOT)
                .replace("̇", "")
                .replace('ş', 's').replace('ğ', 'g').replace('ü', 'u')
                .replace('ö', 'o').replace('ç', 'c').replace('â', 'a');
        return r;
    }
}
