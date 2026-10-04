package com.example.chatandroid;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;
import org.json.JSONObject;
import org.junit.Test;

/** Exercises the actual Java and Node libsignal bindings against each other. */
public class SignalInteropTest {
    private static final String ALICE = "interop-alice";
    private static final String BOB = "interop-bob";

    private static final class NodePeer {
        JSONObject state;

        NodePeer(String username) throws Exception {
            state = call(new JSONObject().put("op", "create").put("username", username)).getJSONObject("state");
        }

        JSONObject run(String op, String key, Object value) throws Exception {
            JSONObject request = new JSONObject().put("op", op).put("state", state);
            if (key != null) request.put(key, value);
            JSONObject response = call(request);
            state = response.getJSONObject("state");
            return response.optJSONObject("result");
        }

        JSONObject publicBundle(int count) throws Exception { return run("publicBundle", "count", count); }
        JSONObject establish(String peer, JSONObject bundle) throws Exception {
            JSONObject response = call(new JSONObject().put("op", "establish").put("state", state)
                    .put("peer", peer).put("bundle", bundle));
            state = response.getJSONObject("state");
            return response;
        }
        JSONObject encrypt(String peer, String body) throws Exception {
            JSONObject response = call(new JSONObject().put("op", "encrypt").put("state", state)
                    .put("peer", peer).put("body", body));
            state = response.getJSONObject("state");
            return response.getJSONObject("result");
        }
        JSONObject decrypt(JSONObject message) throws Exception { return run("decrypt", "message", message); }
        JSONObject safety(String peer, String remotePublic) throws Exception {
            JSONObject response = call(new JSONObject().put("op", "safety").put("state", state)
                    .put("peer", peer).put("remotePublic", remotePublic));
            state = response.getJSONObject("state");
            return response.getJSONObject("result");
        }
    }

    private static File projectRoot() {
        String configured = System.getProperty("chat.root", "");
        if (!configured.isEmpty()) {
            File root = new File(configured);
            if (new File(root, "client/signal.cjs").isFile()
                    && new File(root, "android/test/node-signal-interop.cjs").isFile()) return root;
        }
        Path current = new File(System.getProperty("user.dir")).toPath().toAbsolutePath();
        while (current != null) {
            if (current.resolve("client/signal.cjs").toFile().isFile()
                    && current.resolve("android/test/node-signal-interop.cjs").toFile().isFile())
                return current.toFile();
            current = current.getParent();
        }
        throw new AssertionError("Cannot find the Chat project root from " + System.getProperty("user.dir"));
    }

    private static JSONObject call(JSONObject request) throws Exception {
        File root = projectRoot();
        Process process = new ProcessBuilder("node", new File(root, "android/test/node-signal-interop.cjs").getAbsolutePath())
                .directory(root).redirectErrorStream(true).start();
        try (OutputStream output = process.getOutputStream()) {
            output.write(request.toString().getBytes(StandardCharsets.UTF_8));
        }
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (InputStream input = process.getInputStream()) {
            byte[] buffer = new byte[8192];
            for (int read; (read = input.read(buffer)) != -1;) bytes.write(buffer, 0, read);
        }
        assertTrue("Node fixture timed out", process.waitFor(30, TimeUnit.SECONDS));
        String output = bytes.toString(StandardCharsets.UTF_8.name());
        assertEquals("Node fixture failed: " + output, 0, process.exitValue());
        return new JSONObject(output);
    }

    private static JSONObject selectedBundle(String username, JSONObject publicBundle) throws Exception {
        return new JSONObject(publicBundle.toString()).put("username", username)
                .put("preKey", publicBundle.getJSONArray("preKeys").getJSONObject(0));
    }

    private static JSONObject delivery(JSONObject request, String sender, String recipient) throws Exception {
        return new JSONObject().put("id", "server-1").put("clientId", request.getString("clientId"))
                .put("sender", sender).put("recipient", recipient)
                .put("ciphertext", request.getString("ciphertext"));
    }

    @Test public void nodeInitiatesAndJavaRepliesAcrossRestarts() throws Exception {
        NodePeer alice = new NodePeer(ALICE);
        SignalEngine bob = SignalEngine.create(BOB);
        JSONObject bobBundle = bob.publicBundle(2);
        alice.establish(BOB, selectedBundle(BOB, bobBundle));
        assertFalse(bob.hasSession(ALICE));

        JSONObject first = alice.encrypt(BOB, "Node → Java 中文 🔒");
        assertFalse(first.getString("ciphertext").contains("Node → Java"));
        assertEquals(3, new JSONObject(first.getString("ciphertext")).getInt("type"));
        SignalEngine resumedBob = new SignalEngine(new JSONObject(bob.state().toString()));
        assertEquals("Node → Java 中文 🔒", resumedBob.decrypt(delivery(first, ALICE, BOB)).getString("body"));
        assertTrue(resumedBob.hasSession(ALICE));

        JSONObject reply = resumedBob.encrypt(ALICE, "Java reply 日本語");
        assertEquals("Java reply 日本語", alice.decrypt(delivery(reply, BOB, ALICE)).getString("body"));
        SignalEngine restartedAgain = new SignalEngine(new JSONObject(resumedBob.state().toString()));
        JSONObject next = alice.encrypt(BOB, "ratchet after reload");
        assertEquals(2, new JSONObject(next.getString("ciphertext")).getInt("type"));
        assertEquals("ratchet after reload", restartedAgain.decrypt(delivery(next, ALICE, BOB)).getString("body"));
    }

    @Test public void javaInitiatesAndNodeRepliesAcrossRestarts() throws Exception {
        SignalEngine alice = SignalEngine.create(ALICE);
        NodePeer bob = new NodePeer(BOB);
        alice.establish(BOB, selectedBundle(BOB, bob.publicBundle(2)));
        JSONObject first = alice.encrypt(BOB, "Java → Node 🔐");
        assertEquals(3, new JSONObject(first.getString("ciphertext")).getInt("type"));
        assertEquals("Java → Node 🔐", bob.decrypt(delivery(first, ALICE, BOB)).getString("body"));

        JSONObject reply = bob.encrypt(ALICE, "Node reply");
        SignalEngine resumedAlice = new SignalEngine(new JSONObject(alice.state().toString()));
        assertEquals("Node reply", resumedAlice.decrypt(delivery(reply, BOB, ALICE)).getString("body"));
        JSONObject next = resumedAlice.encrypt(BOB, "after Java restart");
        assertEquals(2, new JSONObject(next.getString("ciphertext")).getInt("type"));
        assertEquals("after Java restart", bob.decrypt(delivery(next, ALICE, BOB)).getString("body"));
    }

    @Test public void encryptedLocationLiveAndStopCrossJavaNode() throws Exception {
        SignalEngine alice = SignalEngine.create(ALICE);
        NodePeer bob = new NodePeer(BOB);
        alice.establish(BOB,selectedBundle(BOB,bob.publicBundle(2)));
        long now = System.currentTimeMillis();
        String session = java.util.UUID.randomUUID().toString();
        String live = LocationPayload.encode("live",session,0,31.2,121.5,50,now,now+3600000);
        JSONObject first = alice.encrypt(BOB,live);
        assertFalse(first.getString("ciphertext").contains("31.2"));
        assertFalse(first.getString("ciphertext").contains(LocationPayload.PREFIX));
        String received = bob.decrypt(delivery(first,ALICE,BOB)).getString("body");
        assertEquals(live,received);
        assertEquals("live",LocationPayload.parse(received,now).kind);
        String stop = LocationPayload.encode("stop",session,1,0,0,0,now,now);
        JSONObject reply = bob.encrypt(ALICE,stop);
        SignalEngine restored = new SignalEngine(new JSONObject(alice.state().toString()));
        String decrypted = restored.decrypt(delivery(reply,BOB,ALICE)).getString("body");
        assertEquals(stop,decrypted);
        assertEquals("stop",LocationPayload.parse(decrypted,now).kind);
        assertEquals(session,LocationPayload.parse(decrypted,now).sessionId);
    }

    @Test public void chineseUsernamesExchangeKeysMessagesAndSafetyCodes() throws Exception {
        String aliceName = "小明", bobName = "测试_bob";
        assertTrue(Usernames.valid(aliceName));
        assertTrue(Usernames.valid(bobName));
        assertEquals("%E5%B0%8F%E6%98%8E", Usernames.path(aliceName));
        SignalEngine alice = SignalEngine.create(aliceName);
        NodePeer bob = new NodePeer(bobName);
        JSONObject bobBundle = selectedBundle(bobName, bob.publicBundle(2));
        alice.establish(bobName, bobBundle);
        String alicePublic = alice.publicBundle(0).getString("identityKey");
        assertEquals(alice.safety(bobName, bobBundle.getString("identityKey")).getString("code"),
                bob.safety(aliceName, alicePublic).getString("code"));
        JSONObject first = alice.encrypt(bobName, "你好，中文用户名");
        assertEquals("你好，中文用户名", bob.decrypt(delivery(first, aliceName, bobName)).getString("body"));
        JSONObject reply = bob.encrypt(aliceName, "收到");
        assertEquals("收到", alice.decrypt(delivery(reply, bobName, aliceName)).getString("body"));
    }

    @Test public void javaRejectsTamperingAndRouteSubstitution() throws Exception {
        NodePeer alice = new NodePeer(ALICE);
        SignalEngine bob = SignalEngine.create(BOB);
        alice.establish(BOB, selectedBundle(BOB, bob.publicBundle(1)));
        JSONObject request = alice.encrypt(BOB, "authentic");
        JSONObject delivery = delivery(request, ALICE, BOB);
        String snapshot = bob.state().toString();
        JSONObject envelope = new JSONObject(request.getString("ciphertext"));
        byte[] ciphertext = java.util.Base64.getDecoder().decode(envelope.getString("data"));
        ciphertext[ciphertext.length - 2] ^= 1;
        envelope.put("data", java.util.Base64.getEncoder().encodeToString(ciphertext));
        JSONObject tampered = new JSONObject(delivery.toString()).put("ciphertext", envelope.toString());
        assertThrows(RuntimeException.class, () -> bob.decrypt(tampered));

        SignalEngine cleanBob = new SignalEngine(new JSONObject(snapshot));
        JSONObject wrongRecipient = new JSONObject(delivery.toString()).put("recipient", "mallory");
        assertThrows(RuntimeException.class, () -> cleanBob.decrypt(wrongRecipient));
        SignalEngine cleanBobAfterRejectedRoute = new SignalEngine(new JSONObject(snapshot));
        assertEquals("authentic", cleanBobAfterRejectedRoute.decrypt(delivery).getString("body"));
        assertThrows(RuntimeException.class, () -> cleanBobAfterRejectedRoute.decrypt(wrongRecipient));
    }

    @Test public void identityAndSignatureChangesAreRejectedAcrossBindings() throws Exception {
        SignalEngine alice = SignalEngine.create(ALICE);
        NodePeer bob = new NodePeer(BOB);
        JSONObject bundle = selectedBundle(BOB, bob.publicBundle(1));
        alice.establish(BOB, bundle);
        String alicePublic = alice.publicBundle(0).getString("identityKey");
        String bobPublic = bundle.getString("identityKey");
        assertEquals(alice.safety(BOB, bobPublic).getString("code"), bob.safety(ALICE, alicePublic).getString("code"));

        NodePeer impostor = new NodePeer(BOB);
        String impostorPublic = impostor.publicBundle(0).getString("identityKey");
        assertThrows(RuntimeException.class, () -> alice.safety(BOB, impostorPublic));

        SignalEngine charlie = SignalEngine.create("interop-charlie");
        JSONObject invalid = new JSONObject(bundle.toString());
        invalid.getJSONObject("signedPreKey").put("signature", java.util.Base64.getEncoder().encodeToString(new byte[64]));
        assertThrows(RuntimeException.class, () -> charlie.establish(BOB, invalid));
    }

    @Test public void rejectedUnconfirmedEnvelopeSurvivesRestartUntilMatchingReceipt() throws Exception {
        SignalEngine alice = SignalEngine.create(ALICE);
        NodePeer bob = new NodePeer(BOB);
        alice.establish(BOB, selectedBundle(BOB, bob.publicBundle(1)));
        JSONObject original = alice.encrypt(BOB, "retry the saved envelope");
        String clientId = original.getString("clientId");
        String ciphertext = original.getString("ciphertext");
        alice.state().getJSONObject("rejectedOutbox").put(clientId, "server rejected");

        SignalEngine recovered = new SignalEngine(new JSONObject(alice.state().toString()));
        JSONObject retry = recovered.state().getJSONObject("outbox").getJSONObject(clientId);
        assertEquals(clientId, retry.getString("clientId"));
        assertEquals(ciphertext, retry.getString("ciphertext"));
        assertTrue(recovered.state().getJSONObject("rejectedOutbox").has(clientId));
        assertEquals("retry the saved envelope", bob.decrypt(delivery(retry, ALICE, BOB)).getString("body"));

        JSONObject wrongReceipt = delivery(retry, ALICE, BOB).put("ciphertext", ciphertext + "tampered");
        assertThrows(SecurityException.class, () -> recovered.accepted(wrongReceipt));
        assertTrue(recovered.state().getJSONObject("outbox").has(clientId));
        assertTrue(recovered.state().getJSONObject("rejectedOutbox").has(clientId));

        recovered.accepted(delivery(retry, ALICE, BOB));
        assertFalse(recovered.state().getJSONObject("outbox").has(clientId));
        assertFalse(recovered.state().getJSONObject("rejectedOutbox").has(clientId));
    }

    @Test public void repeatedServerReceiptCannotDowngradeDeviceDelivery() throws Exception {
        SignalEngine alice = SignalEngine.create(ALICE);
        NodePeer bob = new NodePeer(BOB);
        alice.establish(BOB, selectedBundle(BOB, bob.publicBundle(1)));
        JSONObject request = alice.encrypt(BOB, "delivery stays delivered");
        String clientId = request.getString("clientId");
        assertEquals("delivery stays delivered", bob.decrypt(delivery(request, ALICE, BOB)).getString("body"));

        alice.accepted(delivery(request, ALICE, BOB).put("acknowledged", true));
        alice.accepted(delivery(request, ALICE, BOB));
        assertEquals("对方客户端已接收", alice.state().getJSONObject("messages")
                .getJSONObject(ALICE + ":" + clientId).getString("status"));
        assertFalse(alice.state().getJSONObject("outbox").has(clientId));
    }
}
