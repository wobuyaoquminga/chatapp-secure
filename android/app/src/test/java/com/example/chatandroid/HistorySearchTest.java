package com.example.chatandroid;

import static org.junit.Assert.*;
import java.time.ZoneId;
import java.util.List;
import java.util.ArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;

public class HistorySearchTest {
    private JSONObject record(String sender, String recipient, String body, String at) throws Exception {
        return new JSONObject().put("sender", sender).put("recipient", recipient).put("body", body).put("createdAt", at);
    }
    @Test public void searchesOnlySelectedConversationAndBodyCaseInsensitive() throws Exception {
        JSONObject state = new JSONObject().put("username", "me").put("messages", new JSONArray()
                .put(record("me", "a", "Needle", "2026-09-25T23:00:00Z"))
                .put(record("b", "me", "needle private", "2026-09-26T03:00:00Z"))
                .put(record("a", "me", "unrelated", "2026-09-26T05:00:00Z")));
        MessageIndex index = MessageIndex.build(state);
        assertEquals(List.of(0), HistorySearch.find(index.messages("a"), "NEEDLE", "", "", ZoneId.of("Asia/Shanghai")));
        assertTrue(HistorySearch.find(index.messages("a"), "private", "", "", ZoneId.of("Asia/Shanghai")).isEmpty());
    }
    @Test public void localDateIncludesBothBoundariesAndExcludesUnknownTimes() throws Exception {
        List<JSONObject> records = List.of(
                record("a", "me", "x", "2026-09-25T15:59:59Z"),
                record("a", "me", "x", "2026-09-25T16:00:00Z"),
                record("a", "me", "x", "2026-09-26T15:59:59Z"),
                record("a", "me", "x", "2026-09-26T16:00:00Z"),
                record("a", "me", "x", "bad"));
        assertEquals(List.of(2, 1), HistorySearch.find(records, "", "2026-09-26", "2026-09-26", ZoneId.of("Asia/Shanghai")));
    }
    @Test public void emptyQuerySkipsReadingBodyAndExcludedDatesSkipPreview() throws Exception {
        JSONObject unreadableBody = new JSONObject() {
            @Override public String optString(String name) {
                if (name.equals("body")) throw new AssertionError("Unnecessary body parsing");
                return super.optString(name);
            }
        }.put("body", "x").put("createdAt", "2026-09-26T00:00:00Z");
        assertEquals(List.of(0), HistorySearch.find(List.of(unreadableBody), "", "", "", ZoneId.of("UTC")));
        assertTrue(HistorySearch.find(List.of(unreadableBody), "needle", "2026-09-27", "", ZoneId.of("UTC")).isEmpty());
    }
    @Test public void invalidAndReversedDatesGiveReadableErrors() throws Exception {
        for (String[] bounds : new String[][]{{"2026-02-30", ""}, {"2026-09-27", "2026-09-26"}}) {
            try { HistorySearch.find(List.of(), "", bounds[0], bounds[1], ZoneId.of("UTC")); fail("expected validation"); }
            catch (IllegalArgumentException expected) { assertFalse(expected.getMessage().isEmpty()); }
        }
    }
    @Test public void cancelledSearchStopsBeforeScanningRemainingHistory() throws Exception {
        List<JSONObject> records = new ArrayList<>();
        for (int i = 0; i < 1000; i++) records.add(record("a", "me", "needle", "2026-09-26T00:00:00Z"));
        AtomicInteger checks = new AtomicInteger();
        List<Integer> matches = HistorySearch.find(records, "needle", "", "", ZoneId.of("UTC"),
                () -> checks.incrementAndGet() > 25);
        assertTrue(matches.isEmpty());
        assertEquals(26, checks.get());
    }
    @Test public void cacheRevisionReuseAndAccountChangeIsolation() throws Exception {
        JSONObject state = new JSONObject().put("username", "me").put("server", "https://a").put("messagesRevision", 1)
                .put("messages", new JSONArray().put(record("a", "me", "secret", "2026-09-26T00:00:00Z")));
        MessageIndex original = MessageIndex.build(state);
        assertSame(original.messages("a"), MessageIndex.build(state, original).messages("a"));
        state = new JSONObject().put("username", "other").put("server", "https://a").put("messagesRevision", 1).put("messages", new JSONArray());
        assertTrue(MessageIndex.build(state, original).messages("a").isEmpty());
    }
    @Test public void appendedLiveDeltaKeepsPreviousIndexImmutable() throws Exception {
        long now = System.currentTimeMillis();
        String session = "12345678-1234-4234-8234-123456789abc";
        JSONObject first = record("a", "me", LocationPayload.encode("live", session, 0, 30, 120, 12, now, now+3600000), java.time.Instant.ofEpochMilli(now).toString());
        JSONObject next = record("a", "me", LocationPayload.encode("live", session, 1, 31, 121, 13, now+1, now+3600000), java.time.Instant.ofEpochMilli(now+1).toString());
        JSONObject state = new JSONObject().put("username", "me").put("server", "https://a").put("messagesRevision", 1)
                .put("messages", new JSONArray().put(first));
        MessageIndex original = MessageIndex.build(state);
        state = new JSONObject().put("username", "me").put("server", "https://a").put("messagesRevision", 2)
                .put("messagesBaseRevision", 1).put("messagesAppendFrom", 1).put("messages", new JSONArray().put(first).put(next));
        MessageIndex appended = MessageIndex.build(state, original);
        assertEquals(1, original.messages("a").size()); assertEquals(2, appended.messages("a").size());
        assertFalse(original.hidden("a", 0)); assertTrue(appended.hidden("a", 0));
        assertEquals(0, original.card("a", 0).latest.seq); assertEquals(1, appended.card("a", 1).latest.seq);
        assertEquals(List.of(1,0), HistorySearch.find(appended.messages("a"), "位置", "", "", ZoneId.of("UTC")));
        assertFalse(HistorySearch.preview(next).contains(LocationPayload.PREFIX));
    }
    @Test public void liveCardCollapsesStalePacketsAndStopCannotRevive() throws Exception {
        long now = java.time.Instant.parse("2026-09-26T00:00:00Z").toEpochMilli();
        String session = "12345678-1234-4234-8234-123456789abc";
        JSONArray rows = new JSONArray();
        for (String body : new String[]{
            LocationPayload.encode("live", session, 3, 30, 120, 12, now, now + 3600000),
            LocationPayload.encode("live", session, 2, 31, 121, 12, now, now + 3600000),
            LocationPayload.encode("stop", session, 1, 0, 0, 0, now, now + 3600000),
            LocationPayload.encode("live", session, 9, 32, 122, 12, now, now + 3600000)})
            rows.put(record("a", "me", body, "2026-09-26T00:00:00Z"));
        MessageIndex index = MessageIndex.build(new JSONObject().put("username", "me").put("messages", rows));
        assertTrue(index.hidden("a", 0)); assertTrue(index.hidden("a", 1)); assertTrue(index.hidden("a", 3));
        MessageIndex.LocationCard card = index.card("a", 2);
        assertNotNull(card); assertTrue(card.stopped); assertEquals(30, card.coordinate.latitude, 0);
    }
}
