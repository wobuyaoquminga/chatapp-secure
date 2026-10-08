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

    @Test public void conversationKeyTracksLatestMessageAndOtherUiState() throws Exception {
        JSONObject first = message("first", "sending");
        JSONObject initialState = state(1, new JSONArray().put(first))
                .put("conversations", new JSONArray().put("peer").put("empty"))
                .put("contacts", new JSONArray().put("peer"));
        MessageIndex initial = MessageIndex.build(initialState);
        assertFalse(initial.messageRows.contains("first"));
        assertEquals(initial.messageRows, MessageIndex.build(initialState, initial).messageRows);

        JSONObject receipt = message("first", "sent");
        JSONObject receiptState = state(2, new JSONArray().put(receipt))
                .put("conversations", initialState.getJSONArray("conversations"))
                .put("contacts", initialState.getJSONArray("contacts"));
        MessageIndex changed = MessageIndex.build(receiptState, initial);
        assertNotEquals(initial.messageRows, changed.messageRows);
        assertEquals(initial.contactRows, changed.contactRows);

        JSONObject second = message("second", "sent");
        JSONObject appendedState = state(3, new JSONArray().put(receipt).put(second))
                .put("messagesBaseRevision", 2).put("messagesAppendFrom", 1)
                .put("conversations", initialState.getJSONArray("conversations"))
                .put("contacts", initialState.getJSONArray("contacts"));
        MessageIndex appended = MessageIndex.build(appendedState, changed);
        assertNotEquals(changed.messageRows, appended.messageRows);
        assertEquals(changed.contactRows, appended.contactRows);

        JSONObject unreadState = state(3, appendedState.getJSONArray("messages"))
                .put("conversations", initialState.getJSONArray("conversations"))
                .put("contacts", initialState.getJSONArray("contacts"))
                .put("unread", new JSONObject().put("peer", 1));
        MessageIndex unread = MessageIndex.build(unreadState, appended);
        assertNotEquals(appended.messageRows, unread.messageRows);
        assertEquals(appended.contactRows, unread.contactRows);

        unreadState.put("relationships", new JSONObject().put("peer", "accepted"));
        MessageIndex relation = MessageIndex.build(unreadState, unread);
        assertNotEquals(unread.messageRows, relation.messageRows);
        assertNotEquals(unread.contactRows, relation.contactRows);
        unreadState.put("deletedPeers", new JSONObject().put("peer", true));
        MessageIndex deleted = MessageIndex.build(unreadState, relation);
        assertNotEquals(relation.messageRows, deleted.messageRows);
        assertNotEquals(relation.contactRows, deleted.contactRows);
        unreadState.put("identityChanges", new JSONObject().put("peer", true));
        MessageIndex identity = MessageIndex.build(unreadState, deleted);
        assertNotEquals(deleted.messageRows, identity.messageRows);
        assertNotEquals(deleted.contactRows, identity.contactRows);

        MessageIndex empty = MessageIndex.build(state(1, new JSONArray())
                .put("conversations", new JSONArray().put("empty")));
        assertTrue(empty.messageRows.startsWith("[empty]null"));
        assertEquals(empty.messageRows, MessageIndex.build(state(1, new JSONArray())
                .put("conversations", new JSONArray().put("empty")), empty).messageRows);
    }

    @Test public void repeatedSnapshotBuildBenchmark() throws Exception {
        org.junit.Assume.assumeTrue("1".equals(System.getenv("CHAT_MESSAGE_INDEX_BENCHMARK")));
        JSONArray messages = new JSONArray(), conversations = new JSONArray();
        String body = "x".repeat(16 * 1024);
        for (int i = 0; i < 40; i++) {
            String peer = "peer" + i;
            messages.put(new JSONObject().put("sender", "me").put("recipient", peer)
                    .put("body", body).put("createdAt", "2026-09-26T00:00:00Z"));
            conversations.put(peer);
        }
        JSONObject snapshot = state(1, messages).put("conversations", conversations);
        MessageIndex index = MessageIndex.build(snapshot);
        for (int i = 0; i < 20; i++) index = MessageIndex.build(snapshot, index);
        long[] nanos = new long[7];
        for (int trial = 0; trial < nanos.length; trial++) {
            long start = System.nanoTime();
            for (int i = 0; i < 120; i++) index = MessageIndex.build(snapshot, index);
            nanos[trial] = System.nanoTime() - start;
        }
        Arrays.sort(nanos);
        consumed = index.messageRows.length();
        System.out.println("repeatedSnapshotBuild 40 peers / 16 KiB body / 120 reuse builds: median="
                + nanos[3] / 1_000_000.0 + " ms");
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
