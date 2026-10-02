package com.example.chatandroid;

import static org.junit.Assert.*;
import android.app.Instrumentation;
import android.content.Context;
import android.os.SystemClock;
import android.util.Log;
import android.view.WindowManager;
import androidx.test.platform.app.InstrumentationRegistry;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Predicate;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;

/** Runs on Android, including the real hardware-backed/system Keystore provider and WebSocket. */
@RunWith(AndroidJUnit4.class)
public class DeviceIntegrationTest {
    private static final String TAG = "DeviceIntegrationTest";
    private final Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
    private static final AtomicLong nextDraftRevision = new AtomicLong();

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
        File file = new File(context.getNoBackupFilesDir(), "vaults/" + digest + ".db");
        assertFalse(new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8).contains(secret));
        byte[] original;
        try (android.database.sqlite.SQLiteDatabase db = android.database.sqlite.SQLiteDatabase.openDatabase(file.getPath(), null, 0)) {
            try (android.database.Cursor cursor = db.rawQuery("SELECT encrypted FROM state WHERE id=1", null)) {
                assertTrue(cursor.moveToFirst()); original = cursor.getBlob(0);
            }
            byte[] encrypted = original.clone(); encrypted[encrypted.length - 1] ^= 1;
            db.execSQL("UPDATE state SET encrypted=? WHERE id=1", new Object[]{encrypted});
        }
        try { vault.read(); fail("Tampered vault accepted"); } catch (Exception expected) { }
        try { vault.write(new JSONObject().put("secret", "replacement")); fail("Corrupt vault overwritten"); } catch (Exception expected) { }
        try (android.database.sqlite.SQLiteDatabase db = android.database.sqlite.SQLiteDatabase.openDatabase(file.getPath(), null, 0)) {
            db.execSQL("UPDATE state SET encrypted=? WHERE id=1", new Object[]{original});
        }
        assertEquals(secret, new SecureVault(context, "http://127.0.0.1:8083", user).read().getString("secret"));
    }

    private static final class Probe implements ChatController.Listener {
        final String name;
        final AtomicReference<JSONObject> latest = new AtomicReference<>();
        final AtomicReference<String> error = new AtomicReference<>("");
        final AtomicReference<String> sent = new AtomicReference<>("");
        Probe(String name) { this.name = name; }
        @Override public void onState(JSONObject state, String failure) {
            latest.set(state);
            if (failure != null && !failure.isEmpty()) error.set(failure);
            JSONArray messages = state.optJSONArray("messages");
            Log.i(TAG, name + " online=" + state.optBoolean("online")
                    + " status=" + state.optString("status")
                    + " messages=" + (messages == null ? 0 : messages.length())
                    + (failure == null || failure.isEmpty() ? "" : " error=" + failure));
        }
        @Override public void onSafety(String peer, JSONObject result) { }
        @Override public void onSent(String draftKey, String body, long draftRevision) {
            sent.set(body);
            Log.i(TAG, name + " locally sent: " + body);
        }
        @Override public void onCall(JSONObject frame, long context) { }
        @Override public void onCallReady(CallSession session, JSONArray iceServers, long context) { }
        @Override public void onCallContextLost() { }
        JSONObject await(Predicate<JSONObject> predicate) throws Exception {
            long end = SystemClock.elapsedRealtime() + 40_000;
            while (SystemClock.elapsedRealtime() < end) {
                checkError();
                JSONObject value = latest.get();
                if (value != null && predicate.test(value)) return value;
                Thread.sleep(100);
            }
            throw new AssertionError(name + " timed out: " + latest.get());
        }
        void awaitSent(String body) throws Exception {
            long end = SystemClock.elapsedRealtime() + 40_000;
            while (SystemClock.elapsedRealtime() < end) {
                checkError();
                if (body.equals(sent.get())) return;
                Thread.sleep(100);
            }
            throw new AssertionError(name + " did not finish local send: " + body + "; snapshot=" + latest.get());
        }
        private void checkError() {
            if (!error.get().isEmpty()) throw new AssertionError(name + ": " + error.get());
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

    private static void send(ChatController controller, String peer, String body) {
        controller.send(peer, body, "device-integration:" + peer, nextDraftRevision.incrementAndGet());
    }

    @Test public void serverAckOfflineAndRecreatedClient() throws Exception {
        String base = InstrumentationRegistry.getArguments().getString("server", "http://127.0.0.1:8083");
        String suffix = Long.toString(System.currentTimeMillis(), 36);
        String alice = "手机甲_" + suffix, bob = "手机乙_" + suffix, password = "device-test-123";
        // Keep the instrumented QA process visible on devices that freeze background apps.
        Instrumentation instrumentation = InstrumentationRegistry.getInstrumentation();
        MainActivity foregroundActivity = QaActivityLauncher.launch(instrumentation);
        instrumentation.runOnMainSync(() -> foregroundActivity.getWindow()
                .addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON));
        Probe a = new Probe("alice"), b = new Probe("bob");
        ChatController ca = new ChatController(context, a), cb = new ChatController(context, b);
        try {
            Log.i(TAG, "registering isolated accounts on " + base);
            ca.login(base, alice, password, true, false);
            cb.login(base, bob, password, true, false);
            a.await(s -> s.optBoolean("online")); b.await(s -> s.optBoolean("online"));
            send(ca, bob, "Android 真机加密 " + suffix);
            b.await(s -> hasMessage(s, "Android 真机加密 " + suffix, "已接收并安全保存"));
            a.await(s -> hasMessage(s, "Android 真机加密 " + suffix, "对方客户端已接收"));
            b.await(s -> hasRelation(s, alice, "pending_incoming"));
            cb.acceptContact(alice);
            b.await(s -> hasRelation(s, alice, "accepted"));
            a.await(s -> hasRelation(s, bob, "accepted"));
            send(cb, alice, "Android 回复 " + suffix);
            a.await(s -> hasMessage(s, "Android 回复 " + suffix, "已接收并安全保存"));
            cb.logout(); b.await(s -> s.optString("username").isEmpty());
            send(ca, bob, "Android 离线 " + suffix);
            a.await(s -> hasMessage(s, "Android 离线 " + suffix, "服务器已保存密文"));
            cb.close();
            Log.i(TAG, "recreating offline client's controller");
            Probe restored = new Probe("bob-restored");
            cb = new ChatController(context, restored);
            cb.login(base, bob, password, false, false);
            restored.await(s -> s.optBoolean("online")
                    && hasRelation(s, alice, "accepted")
                    && hasMessage(s, "Android 离线 " + suffix, "已接收并安全保存"));
            a.await(s -> hasMessage(s, "Android 离线 " + suffix, "对方客户端已接收"));
            a.await(s -> s.optBoolean("online") && hasRelation(s, bob, "accepted"));
            Log.i(TAG, "both clients online after restore; sending encrypted reply");
            send(cb, alice, "恢复后加密 " + suffix);
            restored.awaitSent("恢复后加密 " + suffix);
            a.await(s -> hasMessage(s, "恢复后加密 " + suffix, "已接收并安全保存"));
            ca.removeContact(bob);
            a.await(s -> hasNoRelation(s, bob) && hasMessage(s, "恢复后加密 " + suffix, null));
            restored.await(s -> hasNoRelation(s, alice) && hasMessage(s, "恢复后加密 " + suffix, null));
            send(cb, alice, "重新请求 " + suffix);
            restored.await(s -> hasMessage(s, "重新请求 " + suffix, null));
            a.await(s -> hasRelation(s, bob, "pending_incoming")
                    && hasMessage(s, "重新请求 " + suffix, "已接收并安全保存"));
            restored.await(s -> hasRelation(s, alice, "pending_outgoing"));
            ca.acceptContact(bob);
            a.await(s -> hasRelation(s, bob, "accepted"));
            restored.await(s -> hasRelation(s, alice, "accepted"));
        } finally {
            ca.close(); cb.close();
            instrumentation.runOnMainSync(foregroundActivity::finish);
        }
    }
}
