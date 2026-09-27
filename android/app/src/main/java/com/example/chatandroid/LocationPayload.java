package com.example.chatandroid;

import org.json.JSONObject;
import java.time.Instant;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.UUID;

/** Plaintext location protocol; only invoke after Signal decryption. */
public final class LocationPayload {
    public static final String PREFIX = "CHAT_LOCATION_V1:";
    public static final long MAX_DURATION = 3600000L;
    public final String kind, sessionId, recordedAt, expiresAt;
    public final int seq;
    public final double latitude, longitude, accuracy;
    public final long recordedMillis, expiresMillis;
    public final boolean expired;
    private LocationPayload(JSONObject json, long now) throws Exception {
        if (json.length() != (json.has("latitude") ? 9 : 6)) throw new Exception("Invalid location fields");
        for (Iterator<String> it = json.keys(); it.hasNext();) {
            String key = it.next();
            if (!java.util.Arrays.asList("v","kind","sessionId","seq","latitude","longitude","accuracy","recordedAt","expiresAt").contains(key)) throw new Exception("Unknown field");
        }
        if (integer(json.opt("v")) != 1) throw new Exception("Version");
        kind = string(json, "kind");
        if (!kind.equals("pin") && !kind.equals("live") && !kind.equals("stop")) throw new Exception("Kind");
        sessionId = string(json, "sessionId");
        if (!sessionId.matches("(?i)^[0-9a-f]{8}-[0-9a-f]{4}-[1-5][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$")) throw new Exception("UUID");
        seq = integer(json.opt("seq"));
        if (seq < 0) throw new Exception("Sequence");
        recordedAt = string(json, "recordedAt"); expiresAt = string(json, "expiresAt");
        if (!utcIso(recordedAt) || !utcIso(expiresAt)) throw new Exception("UTC required");
        recordedMillis = strictInstant(recordedAt); expiresMillis = strictInstant(expiresAt);
        if (expiresMillis < recordedMillis || recordedMillis > now + 300000L ||
                expiresMillis - recordedMillis > MAX_DURATION) throw new Exception("Invalid time bounds");
        boolean coords = json.has("latitude");
        if (!coords && !kind.equals("stop")) throw new Exception("Coordinates required");
        latitude = coords ? number(json, "latitude", -90, 90) : Double.NaN;
        longitude = coords ? number(json, "longitude", -180, 180) : Double.NaN;
        accuracy = coords ? number(json, "accuracy", 0, 100000) : Double.NaN;
        expired = now >= expiresMillis;
    }
    public static LocationPayload parse(String body, long now) {
        if (body == null || !body.startsWith(PREFIX) || body.length() > 1500) return null;
        try { return new LocationPayload(new JSONObject(body.substring(PREFIX.length())), now); }
        catch (Exception ignored) { return null; }
    }
    public static String encode(String kind, String session, int seq, double latitude, double longitude, double accuracy, long now, long expiry) throws Exception {
        JSONObject json = new JSONObject().put("v",1).put("kind",kind).put("sessionId",session).put("seq",seq)
                .put("recordedAt",Instant.ofEpochMilli(now).toString()).put("expiresAt",Instant.ofEpochMilli(expiry).toString());
        if (!kind.equals("stop")) json.put("latitude",latitude).put("longitude",longitude).put("accuracy",accuracy);
        String body = PREFIX + json;
        if (parse(body,now) == null) throw new IllegalArgumentException("位置数据无效");
        return body;
    }
    private static long strictInstant(String value) throws Exception {
        Instant instant = Instant.parse(value);
        if (!instant.toString().equals(value.replace(".000Z","Z"))) throw new Exception("Invalid calendar timestamp");
        return instant.toEpochMilli();
    }
    private static boolean utcIso(String value) { return value.matches("^\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2}(?:\\.\\d{3})?Z$"); }
    private static String string(JSONObject object, String key) throws Exception {
        Object value = object.opt(key); if (!(value instanceof String)) throw new Exception("String required"); return (String)value;
    }
    private static int integer(Object value) throws Exception {
        if (!(value instanceof Number)) throw new Exception("Integer required");
        double number = ((Number)value).doubleValue();
        if (!Double.isFinite(number) || number != Math.rint(number) || number < 0 || number > Integer.MAX_VALUE) throw new Exception("Integer range");
        return (int)number;
    }
    private static double number(JSONObject object, String key, double low, double high) throws Exception {
        Object value = object.opt(key); if (!(value instanceof Number)) throw new Exception("Number required");
        double result = ((Number)value).doubleValue(); if (!Double.isFinite(result) || result < low || result > high) throw new Exception("Number range"); return result;
    }
    public String mapsUrl() { return "https://www.openstreetmap.org/?mlat=" + latitude + "&mlon=" + longitude + "#map=16/" + latitude + "/" + longitude; }
    /** Session stop is irrevocable; late/out-of-order packets cannot restart it. */
    public static final class Tracker {
        private final Map<String, LocationPayload> latest = new HashMap<>();
        public boolean accept(String peer, LocationPayload item) {
            if (item == null) return false;
            String key = peer + ":" + item.sessionId;
            LocationPayload previous = latest.get(key);
            if (previous != null && (previous.kind.equals("stop") || !item.kind.equals("stop") && (item.seq <= previous.seq || item.expiresMillis > previous.expiresMillis))) return false;
            latest.put(key,item); return true;
        }
        public LocationPayload latest(String peer, String sessionId) { return latest.get(peer + ":" + sessionId); }
        public boolean active(String peer, String sessionId, long now) {
            LocationPayload item = latest(peer,sessionId); return item != null && item.kind.equals("live") && now < item.expiresMillis;
        }
    }
}
