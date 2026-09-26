package com.example.chatandroid;

import org.json.JSONArray;
import org.json.JSONObject;

/** Durable application must finish before acknowledgment or outbound replay. */
final class AccountEventSync {
    interface Save { void apply(JSONArray events) throws Exception; }
    interface Ack { void send(JSONArray ids) throws Exception; }
    static void run(JSONArray events, Save save, Ack ack) throws Exception {
        if (events.length() == 0) return;
        save.apply(events);
        for (int offset = 0; offset < events.length(); offset += 1000) {
            JSONArray ids = new JSONArray();
            for (int i = offset; i < Math.min(offset + 1000, events.length()); i++)
                ids.put(events.getJSONObject(i).getString("id"));
            ack.send(ids);
        }
    }
    private AccountEventSync() { }
}
