package com.example.chatandroid;

import org.json.JSONObject;
import org.junit.Test;
import static org.junit.Assert.*;

public class HistoryRecordsTest {
    @Test public void rollbackRestoresUpdatesDeletesAndAddsWithoutCopyingUnchangedRows() throws Exception {
        HistoryRecords history = new HistoryRecords(new JSONObject()
                .put("alice:1", new JSONObject().put("body", "original").put("status", "queued"))
                .put("alice:2", new JSONObject().put("body", "unchanged")));
        JSONObject untouched = history.getJSONObject("alice:2");
        history.getJSONObject("alice:1").put("status", "sent");
        history.remove("alice:1");
        history.put("bob:3", new JSONObject().put("body", "new"));
        assertEquals(2, history.changedKeys().size());
        history.rollback();
        assertEquals("queued", history.getJSONObject("alice:1").getString("status"));
        assertFalse(history.has("bob:3"));
        assertSame(untouched, history.getJSONObject("alice:2"));
        assertTrue(history.changedKeys().isEmpty());
    }

    @Test public void commitAndReceiptJournalOnlyOneOfTwentyThousandRecords() throws Exception {
        JSONObject source = new JSONObject();
        for (int i = 0; i < 20000; i++) source.put("alice:" + i, new JSONObject().put("body", "record " + i));
        HistoryRecords history = new HistoryRecords(source);
        JSONObject untouched = history.getJSONObject("alice:0");
        history.getJSONObject("alice:19999").put("id", 7L).put("status", "delivered");
        assertEquals(java.util.Set.of("alice:19999"), history.changedKeys());
        history.committed();
        assertTrue(history.changedKeys().isEmpty());
        assertSame(untouched, history.getJSONObject("alice:0"));
    }

    @Test public void protocolCheckpointExcludesHistoryAndIsDetachedForRatchetRollback() throws Exception {
        JSONObject state = new JSONObject().put("messages", new JSONObject().put("one", new JSONObject().put("body", "secret")))
                .put("sessions", new JSONObject().put("alice", "ratchet-before"));
        JSONObject checkpoint = SecureVault.protocolCopy(state);
        assertFalse(checkpoint.has("messages"));
        state.getJSONObject("sessions").put("alice", "ratchet-after");
        assertEquals("ratchet-before", checkpoint.getJSONObject("sessions").getString("alice"));
    }
}
