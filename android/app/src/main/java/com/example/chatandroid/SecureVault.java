package com.example.chatandroid;

import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import android.util.AtomicFile;
import org.json.JSONObject;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.KeyStore;
import java.security.MessageDigest;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Map;
import java.util.Set;
import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;

/** Encrypted protocol checkpoint plus independently encrypted history rows in one SQLite transaction. */
final class SecureVault {
    private static final byte[] MAGIC = {'C', 'H', 'A', 'T', 1};
    private static final String KEYSTORE = "AndroidKeyStore";
    private static final int MAX_RECORD_BYTES = 16 * 1024 * 1024;
    private final AtomicFile legacy;
    private final File database;
    private final String alias;
    private final Context context;
    private HistoryRecords persistedRecords;
    private boolean readFailed;

    SecureVault(Context context, String server, String username) throws Exception {
        this.context = context.getApplicationContext();
        byte[] digest = MessageDigest.getInstance("SHA-256").digest(
                (server + "\u0000" + username).getBytes(StandardCharsets.UTF_8));
        String name = hex(digest);
        File directory = new File(context.getNoBackupFilesDir(), "vaults");
        if (!directory.isDirectory() && !directory.mkdirs()) throw new Exception("无法建立本地安全存储目录");
        legacy = new AtomicFile(new File(directory, name + ".vault"));
        database = new File(directory, name + ".db");
        alias = "chatapp.v1." + name;
    }

    JSONObject read() throws Exception {
        try {
            SecretKey key = existingKey();
            if (database.exists()) {
                if (key == null) throw new Exception("本地密钥已丢失");
                try (Store store = new Store()) {
                    SQLiteDatabase db = store.getReadableDatabase();
                    byte[] checkpoint = null;
                    try (Cursor cursor = db.rawQuery("SELECT encrypted FROM state WHERE id=1", null)) {
                        if (cursor.moveToFirst()) checkpoint = cursor.getBlob(0);
                    }
                    if (checkpoint != null) {
                        JSONObject state = new JSONObject(new String(decrypt(key, "checkpoint-v2", checkpoint), StandardCharsets.UTF_8));
                        JSONObject messages = new JSONObject();

                        try (Cursor cursor = db.rawQuery("SELECT id,encrypted FROM history", null)) {
                            while (cursor.moveToNext()) {
                                String id = cursor.getString(0);
                                JSONObject record = new JSONObject(new String(decrypt(key, "history:" + id, cursor.getBlob(1)), StandardCharsets.UTF_8));
                                String messageKey = record.getString("key");
                                JSONObject message = record.getJSONObject("value");
                                messages.put(messageKey, message);
                                if (!id.equals(messageId(messageKey))) throw new Exception("历史记录索引不匹配");
                            }
                        }
                        if (state.optInt("_historyCount", -1) != messages.length()) throw new Exception("历史记录数量与检查点不一致");
                        state.remove("_historyCount");
                        persistedRecords = new HistoryRecords(messages);
                        state.put("messages", persistedRecords);
                        retireLegacy();
                        return state;
                    }
                    try (Cursor cursor = db.rawQuery("SELECT COUNT(*) FROM history", null)) {
                        if (cursor.moveToFirst() && cursor.getLong(0) != 0) throw new Exception("协议检查点缺失");
                    }
                }
            }
            if (migratedLegacyExists()) throw new Exception("已迁移的协议检查点缺失，拒绝读取旧密钥状态");
            if (!legacyExists()) return null;
            if (key == null) throw new Exception("本地密钥已丢失");
            try (FileInputStream input = legacy.openRead()) {
                ByteArrayOutputStream bytes = new ByteArrayOutputStream();
                byte[] chunk = new byte[8192]; int count;
                while ((count = input.read(chunk)) != -1) {
                    if (bytes.size() + count > MAX_RECORD_BYTES + 64) throw new Exception("旧版本地数据异常大");
                    bytes.write(chunk, 0, count);
                }
                return new JSONObject(new String(decrypt(key, "legacy", bytes.toByteArray()), StandardCharsets.UTF_8));
            }
        } catch (Exception error) {
            readFailed = true;
            throw new Exception("本地安全存储损坏或密钥已丢失；不会覆盖旧数据", error);
        }
    }

    void write(JSONObject state) throws Exception {
        if (readFailed) throw new Exception("本地安全存储读取失败，拒绝覆盖旧数据");
        SecretKey key = existingKey();
        if (key == null) {
            if (legacyExists() || database.exists()) throw new Exception("本地密钥已丢失，拒绝覆盖旧数据");
            key = newKey();
        }
        JSONObject messages = state.optJSONObject("messages");
        if (messages == null) { messages = new HistoryRecords(new JSONObject()); state.put("messages", messages); }
        HistoryRecords records = messages instanceof HistoryRecords ? (HistoryRecords) messages : new HistoryRecords(messages);
        state.put("messages", records);
        byte[] encryptedState = encrypt(key, "checkpoint-v2", protocolCopy(state).put("_historyCount", records.length()).toString());
        boolean replace = records != persistedRecords;
        Set<String> changed = replace ? new HashSet<>() : records.changedKeys();
        if (replace) for (Iterator<String> keys = records.keys(); keys.hasNext();) changed.add(keys.next());
        Map<String, byte[]> encrypted = new HashMap<>();
        for (String messageKey : changed) {
            JSONObject message = records.optJSONObject(messageKey);
            if (message != null) encrypted.put(messageId(messageKey), encrypt(key, "history:" + messageId(messageKey),
                    new JSONObject().put("key", messageKey).put("value", message).toString()));
        }
        try (Store store = new Store()) {
            SQLiteDatabase db = store.getWritableDatabase();
            db.beginTransaction();
            try {
                if (replace) db.execSQL("DELETE FROM history");
                for (String messageKey : changed) {
                    String id = messageId(messageKey);
                    if (!encrypted.containsKey(id)) db.execSQL("DELETE FROM history WHERE id=?", new Object[]{id});
                    else db.execSQL("INSERT OR REPLACE INTO history(id,encrypted) VALUES(?,?)", new Object[]{id, encrypted.get(id)});
                }
                db.execSQL("INSERT OR REPLACE INTO state(id,encrypted) VALUES(1,?)", new Object[]{encryptedState});
                db.setTransactionSuccessful();
            } finally { db.endTransaction(); }
        } catch (Exception error) { throw new Exception("本地加密保存失败", error); }
        persistedRecords = records;
        retireLegacy();
        records.committed();
        // Preserve legacy bytes under names that old clients cannot mistake for a live ratchet.
    }

    static JSONObject protocolCopy(JSONObject state) throws Exception {
        JSONObject checkpoint = new JSONObject();
        for (Iterator<String> fields = state.keys(); fields.hasNext();) {
            String field = fields.next();
            if (!field.equals("messages")) checkpoint.put(field, state.get(field));
        }
        return new JSONObject(checkpoint.toString());
    }

    private static String messageId(String key) throws Exception {
        return hex(MessageDigest.getInstance("SHA-256").digest(key.getBytes(StandardCharsets.UTF_8)));
    }

    private byte[] encrypt(SecretKey key, String domain, String content) throws Exception {
        byte[] plain = content.getBytes(StandardCharsets.UTF_8);
        if (plain.length > MAX_RECORD_BYTES) throw new Exception("单条本地加密记录过大");
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.ENCRYPT_MODE, key);
        byte[] nonce = cipher.getIV();
        if (nonce == null || nonce.length != 12) throw new Exception("安全存储随机数生成失败");
        cipher.updateAAD((alias + (domain.equals("legacy") ? "" : "\u0000" + domain)).getBytes(StandardCharsets.UTF_8));
        byte[] encrypted = cipher.doFinal(plain);
        return ByteBuffer.allocate(MAGIC.length + nonce.length + encrypted.length)
                .put(MAGIC).put(nonce).put(encrypted).array();
    }

    private byte[] decrypt(SecretKey key, String domain, byte[] data) throws Exception {
        if (data.length > MAX_RECORD_BYTES + 64 || data.length < MAGIC.length + 12 + 16)
            throw new Exception("本地数据大小无效");
        for (int i = 0; i < MAGIC.length; i++) if (data[i] != MAGIC[i]) throw new Exception("本地数据版本不受支持");
        byte[] nonce = new byte[12];
        System.arraycopy(data, MAGIC.length, nonce, 0, nonce.length);
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(128, nonce));
        cipher.updateAAD((alias + (domain.equals("legacy") ? "" : "\u0000" + domain)).getBytes(StandardCharsets.UTF_8));
        return cipher.doFinal(data, MAGIC.length + nonce.length, data.length - MAGIC.length - nonce.length);
    }

    private SecretKey existingKey() throws Exception {
        KeyStore store = KeyStore.getInstance(KEYSTORE);
        store.load(null);
        KeyStore.Entry entry = store.getEntry(alias, null);
        if (entry == null) return null;
        if (!(entry instanceof KeyStore.SecretKeyEntry)) throw new Exception("安全密钥类型错误");
        return ((KeyStore.SecretKeyEntry) entry).getSecretKey();
    }

    private boolean migratedLegacyExists() {
        String path = legacy.getBaseFile().getPath();
        return new File(path + ".migrated").exists() || new File(path + ".bak.migrated").exists()
                || new File(path + ".new.migrated").exists();
    }

    private void retireLegacy() throws Exception {
        String path = legacy.getBaseFile().getPath();
        for (String suffix : new String[]{"", ".bak", ".new"}) {
            File source = new File(path + suffix);
            if (!source.exists()) continue;
            File backup = new File(source.getPath() + ".migrated");
            if (backup.exists()) backup = new File(backup.getPath() + "." + System.nanoTime());
            java.nio.file.Files.move(source.toPath(), backup.toPath(), java.nio.file.StandardCopyOption.ATOMIC_MOVE);
        }
    }

    private boolean legacyExists() {
        File base = legacy.getBaseFile();
        return base.exists() || new File(base.getPath() + ".bak").exists()
                || new File(base.getPath() + ".new").exists();
    }

    private SecretKey newKey() throws Exception {
        KeyGenerator generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE);
        generator.init(new KeyGenParameterSpec.Builder(alias,
                KeyProperties.PURPOSE_ENCRYPT | KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256).setRandomizedEncryptionRequired(true).build());
        return generator.generateKey();
    }

    private static String hex(byte[] value) {
        char[] alphabet = "0123456789abcdef".toCharArray();
        char[] digits = new char[value.length * 2];
        for (int i = 0; i < value.length; i++) {
            digits[i * 2] = alphabet[(value[i] >>> 4) & 15];
            digits[i * 2 + 1] = alphabet[value[i] & 15];
        }
        return new String(digits);
    }

    private final class Store extends SQLiteOpenHelper implements AutoCloseable {
        Store() { super(context, database.getAbsolutePath(), null, 1); }
        @Override public void onConfigure(SQLiteDatabase db) {
            db.execSQL("PRAGMA synchronous=FULL");
        }
        @Override public void onCreate(SQLiteDatabase db) {
            db.execSQL("CREATE TABLE state(id INTEGER PRIMARY KEY, encrypted BLOB NOT NULL)");
            db.execSQL("CREATE TABLE history(id TEXT PRIMARY KEY, encrypted BLOB NOT NULL)");
        }
        @Override public void onUpgrade(SQLiteDatabase db, int oldVersion, int newVersion) {
            throw new IllegalStateException("不支持的本地数据版本");
        }
    }
}
