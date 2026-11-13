package org.jestron.event.util;

import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.Collection;

public final class TimeUtil {

    private TimeUtil() {
    }

    /**
     * "20:00", "8:05", "20.00", "2000" -> "20:00" ; geçersizse null
     */
    public static String parseTime(String in) {
        if (in == null) return null;
        String s = in.trim().replace('.', ':').replace('-', ':');
        int h;
        int m;
        try {
            if (s.contains(":")) {
                String[] p = s.split(":");
                if (p.length != 2 || p[0].isEmpty() || p[1].length() != 2) return null;
                h = Integer.parseInt(p[0]);
                m = Integer.parseInt(p[1]);
            } else if (s.length() == 4) {
                h = Integer.parseInt(s.substring(0, 2));
                m = Integer.parseInt(s.substring(2));
            } else if (s.length() <= 2) {
                h = Integer.parseInt(s);
                m = 0;
            } else {
                return null;
            }
        } catch (NumberFormatException e) {
            return null;
        }
        if (h < 0 || h > 23 || m < 0 || m > 59) return null;
        return String.format("%02d:%02d", h, m);
    }

    /** data.yml anahtarı: "20:00" -> "20-00" (YAML'ın saati sayıya çevirmesini önler) */
    public static String key(String time) {
        return time.replace(':', '-');
    }

    public static String fromKey(String key) {
        return key.replace('-', ':');
    }

    public static String formatDuration(long seconds) {
        if (seconds < 0) seconds = 0;
        long h = seconds / 3600;
        long m = (seconds % 3600) / 60;
        long s = seconds % 60;
        StringBuilder sb = new StringBuilder();
        if (h > 0) sb.append(h).append(" sa ");
        if (m > 0) sb.append(m).append(" dk ");
        if (s > 0 || sb.length() == 0) sb.append(s).append(" sn");
        return sb.toString().trim();
    }

    /** Verilen saatler içinde en yakın gelecek olanı döndürür: [saat, saniye] ; yoksa null */
    public static Object[] next(Collection<String> times, ZoneId zone) {
        ZonedDateTime now = ZonedDateTime.now(zone);
        String best = null;
        long bestSec = Long.MAX_VALUE;
        for (String t : times) {
            String[] p = t.split(":");
            LocalTime lt = LocalTime.of(Integer.parseInt(p[0]), Integer.parseInt(p[1]));
            ZonedDateTime cand = ZonedDateTime.of(LocalDate.now(zone), lt, zone);
            if (!cand.isAfter(now)) cand = cand.plusDays(1);
            long sec = Duration.between(now, cand).getSeconds();
            if (sec < bestSec) {
                bestSec = sec;
                best = t;
            }
        }
        return best == null ? null : new Object[]{best, bestSec};
    }
}
