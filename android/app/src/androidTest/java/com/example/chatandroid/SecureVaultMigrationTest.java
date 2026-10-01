package com.example.chatandroid;

import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import android.util.AtomicFile;
import androidx.test.platform.app.InstrumentationRegistry;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import java.io.File;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import static org.junit.Assert.*;

@RunWith(AndroidJUnit4.class)
public class SecureVaultMigrationTest {
    private final Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
    private static final String SERVER = "https://vault-test.invalid";

    private String digest(String user) throws Exception {
        byte[] value = MessageDigest.getInstance("SHA-256").digest((SERVER + "\0" + user).getBytes(StandardCharsets.UTF_8));
        StringBuilder out = new StringBuilder();
        for (byte b : value) out.append(String.format(java.util.Locale.ROOT, "%02x", b & 255));
        return out.toString();
    }
    private File database(String user) throws Exception { return new File(context.getNoBackupFilesDir(), "vaults/" + digest(user) + ".db"); }
    private byte[] row(String user, String id) throws Exception {
        try (SQLiteDatabase db = SQLiteDatabase.openDatabase(database(user).getPath(), null, 0);
                Cursor cursor = db.rawQuery("SELECT encrypted FROM history WHERE id=?", new String[]{id})) {
            assertTrue(cursor.moveToFirst()); return cursor.getBlob(0);
        }
    }
    private String rowId(String key) throws Exception {
        StringBuilder out = new StringBuilder();
        for (byte b : MessageDigest.getInstance("SHA-256").digest(key.getBytes(StandardCharsets.UTF_8)))
            out.append(String.format(java.util.Locale.ROOT, "%02x", b & 255));
        return out.toString();
    }

    @Test public void legacyMigrationKeepsHistoryAndIncrementalSaveDoesNotReencryptUnchangedRows() throws Exception {
        String user = "migration_" + System.nanoTime();
        SecureVault vault = new SecureVault(context, SERVER, user);
        String alias = "chatapp.v1." + digest(user);
        KeyGenerator generator = KeyGenerator.getInstance("AES", "AndroidKeyStore");
        generator.init(new KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT | KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256).build());
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.ENCRYPT_MODE, generator.generateKey());
        cipher.updateAAD(alias.getBytes(StandardCharsets.UTF_8));
        JSONObject old = new JSONObject().put("sessions", new JSONObject().put("peer", "before"))
                .put("messages", new JSONObject().put("peer:1", new JSONObject().put("body", "legacy history")));
        byte[] ciphertext = cipher.doFinal(old.toString().getBytes(StandardCharsets.UTF_8));
        byte[] bytes = ByteBuffer.allocate(5 + 12 + ciphertext.length).put(new byte[]{'C','H','A','T',1})
                .put(cipher.getIV()).put(ciphertext).array();
        AtomicFile legacy = new AtomicFile(new File(context.getNoBackupFilesDir(), "vaults/" + digest(user) + ".vault"));
        java.io.FileOutputStream output = legacy.startWrite(); output.write(bytes); legacy.finishWrite(output);
        JSONObject migrated = vault.read();
        assertEquals("legacy history", migrated.getJSONObject("messages").getJSONObject("peer:1").getString("body"));
        vault.write(migrated);
        byte[] unchanged = row(user, rowId("peer:1"));
        HistoryRecords history = (HistoryRecords) migrated.getJSONObject("messages");
        history.put("peer:2", new JSONObject().put("body", "after migration"));
        vault.write(migrated);
        assertArrayEquals(unchanged, row(user, rowId("peer:1")));
        // Legacy bytes remain as a backup; a downgrade cannot read their stale ratchet.
        File backup = new File(legacy.getBaseFile().getPath() + ".migrated");
        assertFalse(legacy.getBaseFile().exists());
        assertArrayEquals(bytes, java.nio.file.Files.readAllBytes(backup.toPath()));
        // Even a corrupted retained backup must never override a committed database.
        java.nio.file.Files.write(backup.toPath(), new byte[]{0});
        JSONObject restored = new SecureVault(context, SERVER, user).read();
        assertEquals(2, restored.getJSONObject("messages").length());
        assertTrue(database(user).delete());
        try { new SecureVault(context, SERVER, user).read(); fail("Missing migrated checkpoint fell back to stale ratchet"); } catch (Exception expected) { }
    }

    @Test public void failedCheckpointRollsBackAllHistoryWritesAndPermitsCrashRecovery() throws Exception {
        String user = "rollback_" + System.nanoTime();
        SecureVault vault = new SecureVault(context, SERVER, user);
        HistoryRecords history = new HistoryRecords(new JSONObject().put("peer:1", new JSONObject().put("body", "before")));
        JSONObject state = new JSONObject().put("sessions", new JSONObject().put("peer", "ratchet-before")).put("messages", history);
        vault.write(state);
        byte[] original = row(user, rowId("peer:1"));
        try (SQLiteDatabase db = SQLiteDatabase.openDatabase(database(user).getPath(), null, 0)) {
            db.execSQL("CREATE TRIGGER reject_checkpoint BEFORE INSERT ON state BEGIN SELECT RAISE(ABORT,'simulated disk failure'); END");
        }
        history.getJSONObject("peer:1").put("body", "after");
        history.put("peer:2", new JSONObject().put("body", "new"));
        state.getJSONObject("sessions").put("peer", "ratchet-after");
        try { vault.write(state); fail("Checkpoint failure not reported"); } catch (Exception expected) { }
        history.rollback();
        assertEquals("before", history.getJSONObject("peer:1").getString("body"));
        assertArrayEquals(original, row(user, rowId("peer:1")));
        JSONObject recovered = new SecureVault(context, SERVER, user).read();
        assertEquals(1, recovered.getJSONObject("messages").length());
        assertEquals("ratchet-before", recovered.getJSONObject("sessions").getString("peer"));
    }

    @Test public void totalHistoryCanExceedSixteenMiBAndRowSwapFailsAuthentication() throws Exception {
        String user = "large_" + System.nanoTime();
        JSONObject source = new JSONObject();
        StringBuilder text = new StringBuilder();
        for (int i = 0; i < 500; i++) text.append("message plaintext ");
        String body = text.toString();
        for (int i = 0; i < 2000; i++) source.put("peer:" + i, new JSONObject().put("body", body));
        SecureVault vault = new SecureVault(context, SERVER, user);
        vault.write(new JSONObject().put("messages", new HistoryRecords(source)));
        assertTrue(database(user).length() > 16L * 1024 * 1024);
        assertEquals(2000, new SecureVault(context, SERVER, user).read().getJSONObject("messages").length());
        byte[] first = row(user, rowId("peer:0"));
        try (SQLiteDatabase db = SQLiteDatabase.openDatabase(database(user).getPath(), null, 0)) {
            db.execSQL("UPDATE history SET encrypted=? WHERE id=?", new Object[]{first, rowId("peer:1")});
        }
        try { new SecureVault(context, SERVER, user).read(); fail("AAD row swap accepted"); } catch (Exception expected) { }
    }
}
