package com.example.chatandroid;

import org.json.JSONObject;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** Searches only the already decrypted cache for one conversation. Dates use device time zone. */
final class HistorySearch {
    static final int PAGE_SIZE = 40;
    static List<Integer> find(List<JSONObject> history, String query, String from, String to, ZoneId zone) {
        LocalDate first = date(from), last = date(to);
        if (first != null && last != null && first.isAfter(last))
            throw new IllegalArgumentException("开始日期不能晚于结束日期");
        String needle = query.trim().toLowerCase(Locale.ROOT);
        List<Integer> matches = new ArrayList<>();
        for (int i = history.size() - 1; i >= 0; i--) {
            JSONObject item = history.get(i);
            if (!item.has("body") || !preview(item).toLowerCase(Locale.ROOT).contains(needle)) continue;
            if (first != null || last != null) {
                LocalDate day;
                try { day = Instant.parse(item.optString("createdAt")).atZone(zone).toLocalDate(); }
                catch (Exception invalid) { continue; }
                if (first != null && day.isBefore(first) || last != null && day.isAfter(last)) continue;
            }
            matches.add(i);
        }
        return matches;
    }
    static String preview(JSONObject item) {
        String body = item.optString("body");
        LocationPayload location = LocationPayload.parse(body, System.currentTimeMillis());
        if (location == null) return body;
        if (location.kind.equals("stop")) return "实时位置 · 已停止";
        return (location.kind.equals("pin") ? "当前位置" : "实时位置") + String.format(Locale.ROOT,
                " · 纬度 %.6f · 经度 %.6f · 精度约 %.0f 米", location.latitude, location.longitude, location.accuracy);
    }
    private static LocalDate date(String text) {
        if (text.trim().isEmpty()) return null;
        try { return LocalDate.parse(text.trim()); }
        catch (Exception invalid) { throw new IllegalArgumentException("日期格式应为 YYYY-MM-DD"); }
    }
}
