package com.example.chatandroid;

import org.json.JSONException;
import org.json.JSONObject;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

/** Worker-confined history with an undo journal of only the records changed since commit. */
final class HistoryRecords extends JSONObject {
    private final Map<String, String> originals = new HashMap<>();
    private final Map<String, Long> versions = new HashMap<>();
    private long revision;
    private boolean restoring;

    HistoryRecords(JSONObject source) throws JSONException {
        for (Iterator<String> keys = source.keys(); keys.hasNext();) {
            String key = keys.next();
            super.put(key, new Record(key, source.getJSONObject(key)));
            versions.put(key, ++revision);
        }
    }

    private void changing(String key) {
        if (restoring) return;
        if (!originals.containsKey(key)) {
            JSONObject old = optJSONObject(key);
            originals.put(key, old == null ? null : old.toString());
        }
        versions.put(key, ++revision);
    }

    @Override public JSONObject put(String key, Object value) throws JSONException {
        if (value != null && !(value instanceof JSONObject)) throw new JSONException("历史记录必须为对象");
        changing(key);
        return super.put(key, value == null ? null : new Record(key, (JSONObject) value));
    }

    @Override public Object remove(String key) { changing(key); return super.remove(key); }

    Set<String> changedKeys() { return new LinkedHashSet<>(originals.keySet()); }
    long revision() { return revision; }
    long version(String key) { return versions.getOrDefault(key, 0L); }
    void committed() { originals.clear(); }

    void rollback() throws JSONException {
        restoring = true;
        try {
            for (Map.Entry<String, String> entry : originals.entrySet()) {
                String key = entry.getKey();
                if (entry.getValue() == null) super.remove(key);
                else super.put(key, new Record(key, new JSONObject(entry.getValue())));
                versions.put(key, ++revision);
            }
            originals.clear();
        } finally { restoring = false; }
    }

    private final class Record extends JSONObject {
        private final String key;
        Record(String key, JSONObject source) throws JSONException {
            this.key = key;
            for (Iterator<String> keys = source.keys(); keys.hasNext();) {
                String field = keys.next();
                super.put(field, source.get(field));
            }
        }
        @Override public JSONObject put(String field, Object value) throws JSONException {
            changing(key); return super.put(field, value);
        }
        @Override public JSONObject put(String field, int value) throws JSONException { return put(field, Integer.valueOf(value)); }
        @Override public JSONObject put(String field, long value) throws JSONException { return put(field, Long.valueOf(value)); }
        @Override public JSONObject put(String field, double value) throws JSONException { return put(field, Double.valueOf(value)); }
        @Override public JSONObject put(String field, boolean value) throws JSONException { return put(field, Boolean.valueOf(value)); }
        @Override public Object remove(String field) { changing(key); return super.remove(field); }
    }
}
