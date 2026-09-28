package com.example.chatandroid;

import static org.junit.Assert.*;

import java.util.Arrays;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;

public class MessageIndexKeyTest {
    private static volatile int consumed;

    private JSONObject message(String body, String status) throws Exception {
        return new JSONObject().put("sender", "me").put("recipient", "peer")
                .put("body", body).put("status", status).put("createdAt", "2026-09-26T00:00:00Z");
    }

    private JSONObject state(long revision, JSONArray messages) throws Exception {
        return new JSONObject().put("server", "https://a").put("username", "me")
                .put("messagesRevision", revision).put("messages", messages);
    }

    @Test public void unchangedRowsKeepShortKeysAndEditedStatusInvalidatesOnlyItsRow() throws Exception {
        JSONObject first = message("private body", "sending");
        JSONObject second = message("second body", "sent");
        MessageIndex original = MessageIndex.build(state(1, new JSONArray().put(first).put(second)));
        String firstKey = original.rowKey("peer", 0), secondKey = original.rowKey("peer", 1);
        assertEquals(43, firstKey.length());
        assertFalse(firstKey.contains("private body"));
        assertEquals(firstKey, MessageIndex.build(state(1, new JSONArray().put(first).put(second)), original).rowKey("peer", 0));

        JSONObject updated = message("private body", "sent");
        MessageIndex changed = MessageIndex.build(state(2, new JSONArray().put(updated).put(second)), original);
        assertNotEquals(firstKey, changed.rowKey("peer", 0));
        assertEquals(secondKey, changed.rowKey("peer", 1));
        assertEquals(firstKey, original.rowKey("peer", 0));
    }

    @Test public void appendKeepsOldKeyAndDifferentServerCannotReuseHistory() throws Exception {
        JSONObject first = message("one", "sent"), second = message("two", "sent");
        MessageIndex original = MessageIndex.build(state(1, new JSONArray().put(first)));
        JSONObject appendedState = state(2, new JSONArray().put(first).put(second))
                .put("messagesBaseRevision", 1).put("messagesAppendFrom", 1);
        MessageIndex appended = MessageIndex.build(appendedState, original);
        assertEquals(original.rowKey("peer", 0), appended.rowKey("peer", 0));
        assertEquals(MessageIndex.rowKey(second), appended.rowKey("peer", 1));
        JSONObject otherServer = state(2, new JSONArray()).put("server", "https://b");
        assertTrue(MessageIndex.build(otherServer, appended).messages("peer").isEmpty());
    }

    @Test public void fixedHistoryKeyWorkloadBenchmark() throws Exception {
        // Fixed synthetic workload: 2,000 records, 512-character bodies, 80 visible rows, 200 comparisons.
        JSONArray rows = new JSONArray();
        String body = "x".repeat(512);
        for (int i = 0; i < 2000; i++) rows.put(message(body + i, "sent"));
        MessageIndex index = MessageIndex.build(state(1, rows));
        for (int i = 0; i < 3; i++) { legacy(index, 20); cached(index, 20); }
        long[] oldNanos = new long[5], newNanos = new long[5];
        for (int i = 0; i < 5; i++) {
            long start = System.nanoTime(); legacy(index, 200); oldNanos[i] = System.nanoTime() - start;
            start = System.nanoTime(); cached(index, 200); newNanos[i] = System.nanoTime() - start;
        }
        Arrays.sort(oldNanos); Arrays.sort(newNanos);
        System.out.println("fixedHistoryKeyWorkload 2000 records / 80 visible / 200 passes: legacy="
                + oldNanos[2] / 1_000_000.0 + " ms, cached=" + newNanos[2] / 1_000_000.0 + " ms");
    }

    private void legacy(MessageIndex index, int passes) {
        for (int pass = 0; pass < passes; pass++) {
            StringBuilder key = new StringBuilder();
            for (int i = 1920; i < 2000; i++) key.append(index.messages("peer").get(i));
            consumed = key.length();
        }
    }

    private void cached(MessageIndex index, int passes) {
        for (int pass = 0; pass < passes; pass++) {
            StringBuilder key = new StringBuilder();
            for (int i = 1920; i < 2000; i++) key.append(index.rowKey("peer", i));
            consumed = key.length();
        }
    }
}
