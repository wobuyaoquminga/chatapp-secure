package com.example.chatandroid;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;
import static org.junit.Assert.*;

public class AccountDeletionTest {
    private JSONObject bundle(SignalEngine engine, String name, String accountId) throws Exception {
        JSONObject result = new JSONObject(engine.publicBundle(2).toString());
        return result.put("username", name).put("accountId", accountId)
                .put("preKey", result.getJSONArray("preKeys").getJSONObject(0));
    }
    private JSONObject deletion(String id, String peer, String accountId, JSONObject bundle) throws Exception {
        return new JSONObject().put("id", id).put("username", peer).put("accountId", accountId)
                .put("identityKey", bundle.getString("identityKey"));
    }
    private JSONObject delivery(JSONObject request, String sender, String recipient) throws Exception {
        return new JSONObject().put("id", 1).put("sender", sender).put("recipient", recipient)
                .put("clientId", request.getString("clientId")).put("ciphertext", request.getString("ciphertext"));
    }

    @Test public void deletionRetiresOnlyPeerAndAllowsNewAccountAfterRestart() throws Exception {
        SignalEngine alice = SignalEngine.create("alice"), bob = SignalEngine.create("bob");
        SignalEngine carol = SignalEngine.create("carol");
        JSONObject oldBob = bundle(bob, "bob", "bob-old"), other = bundle(carol, "carol", "carol-one");
        alice.bindPeer("bob", oldBob, false); alice.establish("bob", oldBob);
        alice.bindPeer("carol", other, false); alice.establish("carol", other);
        alice.state().getJSONObject("verified").put("bob", oldBob.getString("identityKey"));
        JSONObject first = alice.encrypt("bob", "old history");
        bob.decrypt(delivery(first, "alice", "bob"));
        JSONObject otherSend = alice.encrypt("carol", "other pending");
        alice.state().getJSONObject("messages").getJSONObject("alice:" + first.getString("clientId")).put("id", 42);
        alice.state().getJSONObject("messages").getJSONObject("alice:" + otherSend.getString("clientId")).put("id", 43);
        String own = alice.state().getString("identity");
        String otherSession = alice.state().getJSONObject("sessions").getString("carol.1");
        JSONObject event = deletion("delete-bob", "bob", "bob-old", oldBob);
        assertTrue(alice.applyAccountDeletion(event));
        assertFalse(alice.hasSession("bob"));
        assertFalse(alice.state().getJSONObject("trusted").has("bob.1"));
        assertFalse(alice.state().getJSONObject("verified").has("bob"));
        assertFalse(alice.state().getJSONObject("outbox").has(first.getString("clientId")));
        assertEquals("身份已失效 · 未发送", alice.state().getJSONObject("messages")
                .getJSONObject("alice:" + first.getString("clientId")).getString("status"));
        assertTrue(alice.state().getJSONObject("outbox").has(otherSend.getString("clientId")));
        JSONObject archived = alice.state().getJSONObject("messages").getJSONObject("alice:" + first.getString("clientId"));
        assertEquals(42, archived.getInt("archivedServerId"));
        assertFalse(archived.has("id"));
        assertEquals("old history", archived.getString("body"));
        assertEquals(43, alice.state().getJSONObject("messages").getJSONObject("alice:" + otherSend.getString("clientId")).getInt("id"));
        assertEquals(own, alice.state().getString("identity"));
        assertEquals(otherSession, alice.state().getJSONObject("sessions").getString("carol.1"));
        assertThrows(SecurityException.class, () -> alice.encrypt("bob", "blocked"));
        assertThrows(SecurityException.class, () -> alice.bindPeer("bob", oldBob, true));
        SignalEngine restored = new SignalEngine(new JSONObject(alice.state().toString()));
        SignalEngine newBob = SignalEngine.create("bob");
        JSONObject newBundle = bundle(newBob, "bob", "bob-new");
        assertFalse(restored.bindPeer("bob", newBundle, true).getBoolean("verified"));
        restored.establish("bob", newBundle);
        JSONObject hello = restored.encrypt("bob", "new account hello");
        assertEquals("new account hello", newBob.decrypt(delivery(hello, "alice", "bob")).getString("body"));
        JSONObject reply = newBob.encrypt("alice", "new reply");
        assertEquals("new reply", restored.decrypt(delivery(reply, "bob", "alice")).getString("body"));
        String newSession = restored.state().getJSONObject("sessions").getString("bob.1");
        assertFalse(restored.applyAccountDeletion(event));
        assertFalse(restored.applyAccountDeletion(deletion("late-old", "bob", "bob-old", oldBob)));
        assertFalse(restored.isDeleted("bob"));
        assertEquals(newSession, restored.state().getJSONObject("sessions").getString("bob.1"));
        assertTrue(restored.state().getJSONObject("appliedAccountEvents").has("late-old"));
        assertEquals(4, restored.state().getJSONObject("messages").length());
    }

    @Test public void unprovenIdentityChangeRemainsBlockedAndLateKeyCannotClearNewPin() throws Exception {
        SignalEngine alice = SignalEngine.create("alice");
        JSONObject oldBob = bundle(SignalEngine.create("bob"), "bob", "old");
        JSONObject newBob = bundle(SignalEngine.create("bob"), "bob", "new");
        alice.bindPeer("bob", newBob, false);
        assertThrows(SecurityException.class, () -> alice.bindPeer("bob", oldBob, true));
        // Legacy vaults may lack an accountId; the old identity proof still cannot erase a new pin.
        alice.state().getJSONObject("peerAccountIds").remove("bob");
        assertFalse(alice.applyAccountDeletion(deletion("late", "bob", "old", oldBob)));
        assertFalse(alice.isDeleted("bob"));
        assertEquals(newBob.getString("identityKey"), alice.state().getJSONObject("trusted").getString("bob.1"));
    }

    @Test public void ackFailureStopsReplayAndSavedEventsSurviveRetry() throws Exception {
        SignalEngine alice = SignalEngine.create("alice");
        JSONObject oldBob = bundle(SignalEngine.create("bob"), "bob", "old");
        alice.bindPeer("bob", oldBob, false); alice.establish("bob", oldBob);
        alice.encrypt("bob", "queued");
        JSONArray batch = new JSONArray().put(deletion("deletion", "bob", "old", oldBob));
        String[] durable = {null};
        boolean[] replayed = {false};
        assertThrows(Exception.class, () -> {
            AccountEventSync.run(batch, events -> {
                alice.applyAccountDeletion(events.getJSONObject(0));
                durable[0] = alice.state().toString();
            }, ids -> {
                assertNotNull(durable[0]);
                throw new Exception("ACK unavailable");
            });
            replayed[0] = true;
        });
        assertFalse(replayed[0]);
        SignalEngine restored = new SignalEngine(new JSONObject(durable[0]));
        assertEquals(0, restored.state().getJSONObject("outbox").length());
        AccountEventSync.run(batch, events -> assertFalse(restored.applyAccountDeletion(events.getJSONObject(0))),
                ids -> assertEquals("deletion", ids.getString(0)));
        assertTrue(restored.isDeleted("bob"));
    }
    @Test public void acknowledgmentIsBatchedOnlyAfterWholeBatchIsSaved() throws Exception {
        JSONArray events = new JSONArray();
        for (int i = 0; i < 2001; i++) events.put(new JSONObject().put("id", "event-" + i));
        boolean[] saved = {false};
        int[] calls = {0}, count = {0};
        AccountEventSync.run(events, batch -> { assertEquals(2001, batch.length()); saved[0] = true; }, ids -> {
            assertTrue(saved[0]);
            assertTrue(ids.length() <= 1000);
            assertEquals("event-" + count[0], ids.getString(0));
            count[0] += ids.length(); calls[0]++;
        });
        assertEquals(3, calls[0]); assertEquals(2001, count[0]);
    }

    @Test public void acceptedNewAccountRestoresContactAfterOfflineDeletionSync() throws Exception {
        SignalEngine alice = SignalEngine.create("alice");
        JSONObject oldBob = bundle(SignalEngine.create("bob"), "bob", "old");
        alice.bindPeer("bob", oldBob, false);
        alice.applyAccountDeletion(deletion("deleted", "bob", "old", oldBob));
        assertTrue(alice.needsDeletedPeerRefresh("bob", "accepted"));
        assertTrue(alice.needsDeletedPeerRefresh("bob", "pending_incoming"));
        assertTrue(alice.needsDeletedPeerRefresh("bob", "pending_outgoing"));
        assertFalse(alice.needsDeletedPeerRefresh("bob", "removed"));
        // A relationship alone cannot authorize the old deleted generation.
        assertThrows(SecurityException.class, () -> alice.bindPeer("bob", oldBob, true));
        assertTrue(alice.isDeleted("bob"));
        JSONObject newBob = bundle(SignalEngine.create("bob"), "bob", "new");
        alice.bindPeer("bob", newBob, true);
        assertFalse(alice.isDeleted("bob"));
        assertFalse(alice.needsDeletedPeerRefresh("bob", "accepted"));
        assertEquals("new", alice.state().getJSONObject("peerAccountIds").getString("bob"));
        assertFalse(alice.state().getJSONObject("verified").has("bob"));
    }

}
