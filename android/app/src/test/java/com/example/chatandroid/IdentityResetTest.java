package com.example.chatandroid;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;
import static org.junit.Assert.*;

public class IdentityResetTest {
    private JSONObject bundle(SignalEngine engine, String name, String account) throws Exception {
        JSONObject value = new JSONObject(engine.publicBundle(2).toString());
        return value.put("username", name).put("accountId", account)
                .put("preKey", value.getJSONArray("preKeys").getJSONObject(0));
    }
    private JSONObject delivered(JSONObject request, String from, String to) throws Exception {
        return new JSONObject().put("id", "server-1").put("sender", from).put("recipient", to)
                .put("clientId", request.getString("clientId")).put("ciphertext", request.getString("ciphertext"));
    }
    private JSONObject reset(String id, JSONObject old, JSONObject next) throws Exception {
        return new JSONObject().put("id", id).put("kind", "identity_reset").put("username", "bob")
                .put("accountId", old.getString("accountId")).put("identityKey", old.getString("identityKey"))
                .put("newAccountId", next.getString("accountId")).put("newIdentityKey", next.getString("identityKey"));
    }

    @Test public void resetProofRestoresBidirectionalSignalAndRequiresSafetyConfirmation() throws Exception {
        SignalEngine alice = SignalEngine.create("alice"), oldBob = SignalEngine.create("bob");
        JSONObject old = bundle(oldBob, "bob", "old"), aliceBundle = bundle(alice, "alice", "alice-id");
        alice.bindPeer("bob", old, false); oldBob.bindPeer("alice", aliceBundle, false);
        alice.establish("bob", old);
        JSONObject carol = bundle(SignalEngine.create("carol"), "carol", "carol-id");
        alice.bindPeer("carol", carol, false); alice.establish("carol", carol);
        JSONObject unaffected = alice.encrypt("carol", "other contact");
        String carolSession = alice.state().getJSONObject("sessions").getString("carol.1");
        JSONObject prior = alice.encrypt("bob", "old local history");
        assertEquals("old", prior.getString("toAccountId"));
        oldBob.decrypt(delivered(prior, "alice", "bob"));
        alice.state().getJSONObject("verified").put("bob", old.getString("identityKey"));
        String ownPrivate = alice.state().getString("identity");
        SignalEngine newBob = SignalEngine.create("bob");
        JSONObject next = bundle(newBob, "bob", "next");
        assertThrows(SecurityException.class, () -> alice.bindPeer("bob", next, false));
        JSONObject event = reset("reset-1", old, next);
        assertTrue(alice.applyAccountEvent(event));
        assertEquals(ownPrivate, alice.state().getString("identity"));
        assertFalse(alice.hasSession("bob"));
        assertFalse(alice.isDeleted("bob"));
        assertFalse(alice.state().getJSONObject("verified").has("bob"));
        assertEquals(1, alice.state().getJSONObject("outbox").length());
        assertTrue(alice.state().getJSONObject("outbox").has(unaffected.getString("clientId")));
        assertEquals(carolSession, alice.state().getJSONObject("sessions").getString("carol.1"));
        assertEquals("old local history", alice.state().getJSONObject("messages")
                .getJSONObject("alice:" + prior.getString("clientId")).getString("body"));
        assertThrows(SecurityException.class, () -> alice.encrypt("bob", "unsafe"));
        SignalEngine restored = new SignalEngine(new JSONObject(alice.state().toString()));
        assertFalse(restored.bindPeer("bob", next, false).getBoolean("verified"));
        assertThrows(SecurityException.class, () -> restored.bindPeer("bob", old, false));
        restored.state().getJSONObject("verified").put("bob", next.getString("identityKey"));
        restored.state().getJSONObject("identityChanges").remove("bob");
        restored.establish("bob", next);
        JSONObject message = restored.encrypt("bob", "after reset");
        assertEquals("next", message.getString("toAccountId"));
        assertEquals("after reset", newBob.decrypt(delivered(message, "alice", "bob")).getString("body"));
        JSONObject reply = newBob.encrypt("alice", "new reply");
        assertEquals("new reply", restored.decrypt(delivered(reply, "bob", "alice")).getString("body"));
        String session = restored.state().getJSONObject("sessions").getString("bob.1");
        assertFalse(restored.applyAccountEvent(event));
        assertFalse(restored.applyAccountEvent(reset("late-reset", old, next)));
        JSONObject lateDeletion = new JSONObject(event.toString()).put("id", "late-delete")
                .put("kind", "account_deleted");
        assertFalse(restored.applyAccountEvent(lateDeletion));
        assertFalse(restored.isDeleted("bob"));
        assertEquals(session, restored.state().getJSONObject("sessions").getString("bob.1"));
        assertEquals(next.getString("identityKey"), restored.state().getJSONObject("verified").getString("bob"));
        assertFalse(restored.state().getJSONObject("identityChanges").has("bob"));
    }

    @Test public void stagedOwnResetCanRetryWithoutReplacingOriginalPrivateKeysOrHistory() throws Exception {
        SignalEngine alice = SignalEngine.create("alice"), bob = SignalEngine.create("bob");
        JSONObject peer = bundle(bob, "bob", "bob-id");
        alice.bindPeer("bob", peer, false); alice.establish("bob", peer);
        JSONObject queued = alice.encrypt("bob", "keep this encrypted locally");
        String oldIdentity = alice.state().getString("identity");
        SignalEngine candidate = alice.stageIdentityReset();
        candidate.publicBundle(30);
        alice.saveIdentityResetCandidate(candidate);
        // This serializable state is written only by SecureVault; a rejected reset retains it.
        String checkpoint = alice.state().toString();
        SignalEngine recovered = new SignalEngine(new JSONObject(checkpoint));
        assertEquals(oldIdentity, recovered.state().getString("identity"));
        assertEquals(1, recovered.state().getJSONObject("outbox").length());
        SignalEngine retry = recovered.stageIdentityReset();
        assertEquals(candidate.state().getString("identity"), retry.state().getString("identity"));
        assertEquals(30, retry.state().getJSONArray("pendingUpload").length());
        assertFalse(recovered.state().getJSONObject("pendingIdentityReset").has("messages"));
        assertEquals("keep this encrypted locally", retry.state().getJSONObject("messages")
                .getJSONObject("alice:" + queued.getString("clientId")).getString("body"));
        assertEquals(0, retry.state().getJSONObject("outbox").length());
        assertEquals(0, retry.state().getJSONObject("sessions").length());
        assertEquals(peer.getString("identityKey"), retry.state().getJSONObject("trusted").getString("bob.1"));
        assertFalse(retry.state().getJSONObject("archivedIdentity").has("messages"));
        JSONObject publicOnly = retry.publicBundle(0);
        assertEquals(4, publicOnly.length());
        assertFalse(publicOnly.toString().contains(oldIdentity));
        assertFalse(publicOnly.toString().contains(retry.state().getString("identity")));
        assertFalse(publicOnly.has("messages"));
        assertFalse(publicOnly.has("archivedIdentity"));
    }

    @Test public void oldRecipientEventAndSameKeyDifferentAccountCannotChangeCurrentBindings() throws Exception {
        SignalEngine alice = SignalEngine.create("alice");
        alice.state().put("accountId", "alice-new");
        JSONObject old = bundle(SignalEngine.create("bob"), "bob", "old");
        JSONObject next = bundle(SignalEngine.create("bob"), "bob", "new");
        alice.bindPeer("bob", old, false);
        JSONObject scoped = reset("scope", old, next).put("recipientAccountId", "alice-old");
        assertFalse(alice.applyAccountEvent(scoped));
        assertFalse(alice.state().getJSONObject("appliedAccountEvents").has("scope"));
        assertEquals(old.getString("identityKey"), alice.state().getJSONObject("trusted").getString("bob.1"));
        JSONObject unproven = new JSONObject(old.toString()).put("accountId", "other");
        assertThrows(SecurityException.class, () -> alice.bindPeer("bob", unproven, false));
    }

    @Test public void usernamesAllowExactlyTwoAsciiCharactersAndRetainRestrictions() {
        assertTrue(Usernames.valid("ab")); assertTrue(Usernames.valid("小明"));
        assertTrue(Usernames.valid("a_")); assertTrue(Usernames.valid("12"));
        assertFalse(Usernames.valid("a")); assertFalse(Usernames.valid("AB"));
        assertFalse(Usernames.valid("a b")); assertFalse(Usernames.valid("a-b"));
        assertTrue(Usernames.valid("a".repeat(32))); assertFalse(Usernames.valid("a".repeat(33)));
    }

    @Test public void deletionAfterUnconfirmedResetAllowsFreshSameNameAccount() throws Exception {
        SignalEngine alice = SignalEngine.create("alice");
        JSONObject old = bundle(SignalEngine.create("bob"), "bob", "old");
        JSONObject reset = bundle(SignalEngine.create("bob"), "bob", "reset");
        alice.bindPeer("bob", old, false);
        assertTrue(alice.applyAccountEvent(reset("reset", old, reset)));
        assertTrue(alice.state().getJSONObject("identityChanges").has("bob"));
        JSONObject deletion = new JSONObject().put("id", "delete").put("kind", "account_deleted")
                .put("username", "bob").put("accountId", "reset").put("identityKey", reset.getString("identityKey"));
        assertTrue(alice.applyAccountEvent(deletion));
        assertFalse(alice.state().getJSONObject("identityChanges").has("bob"));
        SignalEngine recreated = SignalEngine.create("bob");
        JSONObject fresh = bundle(recreated, "bob", "fresh");
        alice.bindPeer("bob", fresh, true); alice.establish("bob", fresh);
        JSONObject request = alice.encrypt("bob", "newly registered account");
        assertEquals("newly registered account", recreated.decrypt(delivered(request, "alice", "bob")).getString("body"));
    }
}
