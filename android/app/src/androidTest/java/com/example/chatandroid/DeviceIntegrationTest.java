package com.example.chatandroid;

import static org.junit.Assert.*;
import android.content.Context;
import androidx.test.platform.app.InstrumentationRegistry;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Predicate;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;

/** Runs on Android, including the real hardware-backed/system Keystore provider and WebSocket. */
@RunWith(AndroidJUnit4.class)
public class DeviceIntegrationTest {
    private final Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();

    @Test public void keystoreRoundTripAndTamperRejection() throws Exception {
        String user = "vault_" + System.nanoTime();
        SecureVault vault = new SecureVault(context, "http://127.0.0.1:8083", user);
        assertNull(vault.read());
        String secret = "phone plaintext must remain encrypted " + user;
        vault.write(new JSONObject().put("secret", secret));
        assertEquals(secret, new SecureVault(context, "http://127.0.0.1:8083", user).read().getString("secret"));
        byte[] hash = java.security.MessageDigest.getInstance("SHA-256")
                .digest(("http://127.0.0.1:8083\0" + user).getBytes(StandardCharsets.UTF_8));
        StringBuilder hex = new StringBuilder();
        for (byte item : hash) hex.append(String.format(java.util.Locale.ROOT, "%02x", item & 255));
        String digest = hex.toString();
        File file = new File(context.getNoBackupFilesDir(), "vaults/" + digest + ".vault");
        byte[] encrypted = Files.readAllBytes(file.toPath());
        assertFalse(new String(encrypted, StandardCharsets.UTF_8).contains(secret));
        byte[] original = encrypted.clone();
        encrypted[encrypted.length - 1] ^= 1;
        Files.write(file.toPath(), encrypted);
        try { vault.read(); fail("Tampered vault accepted"); } catch (Exception expected) { }
        Files.write(file.toPath(), original);
        assertEquals(secret, vault.read().getString("secret"));
    }

    private static final class Probe implements ChatController.Listener {
        final AtomicReference<JSONObject> latest = new AtomicReference<>();
        final AtomicReference<String> error = new AtomicReference<>("");
        @Override public void onState(JSONObject state, String message) { latest.set(state); if (message != null && !message.isEmpty()) error.set(message); }
        @Override public void onSafety(String peer, JSONObject result) { }
        @Override public void onSent() { }
        JSONObject await(Predicate<JSONObject> predicate) throws Exception {
            long end = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(40);
            while (System.nanoTime() < end) {
                if (!error.get().isEmpty()) throw new AssertionError(error.get());
                JSONObject value = latest.get();
                if (value != null && predicate.test(value)) return value;
                Thread.sleep(100);
            }
            throw new AssertionError("Timed out: " + latest.get());
        }
    }

    private static boolean hasMessage(JSONObject snapshot, String body, String status) {
        JSONArray messages = snapshot.optJSONArray("messages");
        if (messages == null) return false;
        for (int i = 0; i < messages.length(); i++) {
            JSONObject message = messages.optJSONObject(i);
            if (body.equals(message.optString("body")) && (status == null || status.equals(message.optString("status")))) return true;
        }
        return false;
    }

    private static boolean hasRelation(JSONObject snapshot, String peer, String status) {
        JSONObject relations = snapshot.optJSONObject("relationships");
        JSONObject relation = relations == null ? null : relations.optJSONObject(peer);
        return relation != null && status.equals(relation.optString("status"));
    }

    private static boolean hasNoRelation(JSONObject snapshot, String peer) {
        JSONObject relations = snapshot.optJSONObject("relationships");
        return relations != null && !relations.has(peer);
    }

    @Test public void serverAckOfflineAndRecreatedClient() throws Exception {
        String base = InstrumentationRegistry.getArguments().getString("server", "http://127.0.0.1:8083");
        String suffix = Long.toString(System.currentTimeMillis(), 36);
        String alice = "手机甲_" + suffix, bob = "手机乙_" + suffix, password = "device-test-123";
        Probe a = new Probe(), b = new Probe();
        ChatController ca = new ChatController(context, a), cb = new ChatController(context, b);
        try {
            ca.login(base, alice, password, true, false);
            cb.login(base, bob, password, true, false);
            a.await(s -> s.optBoolean("online")); b.await(s -> s.optBoolean("online"));
            ca.send(bob, "Android 真机加密 " + suffix);
            b.await(s -> hasMessage(s, "Android 真机加密 " + suffix, "已接收并安全保存"));
            a.await(s -> hasMessage(s, "Android 真机加密 " + suffix, "对方客户端已接收"));
            b.await(s -> hasRelation(s, alice, "pending_incoming"));
            cb.acceptContact(alice);
            b.await(s -> hasRelation(s, alice, "accepted"));
            a.await(s -> hasRelation(s, bob, "accepted"));
            cb.send(alice, "Android 回复 " + suffix);
            a.await(s -> hasMessage(s, "Android 回复 " + suffix, "已接收并安全保存"));
            cb.logout(); b.await(s -> s.optString("username").isEmpty());
            ca.send(bob, "Android 离线 " + suffix);
            a.await(s -> hasMessage(s, "Android 离线 " + suffix, "服务器已保存密文"));
            cb.close();
            Probe restored = new Probe();
            cb = new ChatController(context, restored);
            cb.login(base, bob, password, false, false);
            restored.await(s -> hasMessage(s, "Android 离线 " + suffix, "已接收并安全保存"));
            a.await(s -> hasMessage(s, "Android 离线 " + suffix, "对方客户端已接收"));
            cb.send(alice, "恢复后加密 " + suffix);
            a.await(s -> hasMessage(s, "恢复后加密 " + suffix, "已接收并安全保存"));
            ca.removeContact(bob);
            a.await(s -> hasNoRelation(s, bob) && hasMessage(s, "恢复后加密 " + suffix, null));
            restored.await(s -> hasNoRelation(s, alice) && hasMessage(s, "恢复后加密 " + suffix, null));
            cb.send(alice, "重新请求 " + suffix);
            restored.await(s -> hasMessage(s, "重新请求 " + suffix, null));
            a.await(s -> hasRelation(s, bob, "pending_incoming")
                    && hasMessage(s, "重新请求 " + suffix, "已接收并安全保存"));
            restored.await(s -> hasRelation(s, alice, "pending_outgoing"));
            ca.acceptContact(bob);
            a.await(s -> hasRelation(s, bob, "accepted"));
            restored.await(s -> hasRelation(s, alice, "accepted"));
        } finally { ca.close(); cb.close(); }
    }
}
