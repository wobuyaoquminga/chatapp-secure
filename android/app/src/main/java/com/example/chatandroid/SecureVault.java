package com.example.chatandroid;

import android.content.Context;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import android.util.AtomicFile;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.KeyStore;
import java.security.MessageDigest;
import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;

/** One account's complete Signal state, encrypted by a non-exportable AndroidKeyStore key. */
final class SecureVault {
    private static final byte[] MAGIC = { 'C', 'H', 'A', 'T', 1 };
    private static final String KEYSTORE = "AndroidKeyStore";
    private final AtomicFile file;
    private final String alias;

    SecureVault(Context context, String server, String username) throws Exception {
        byte[] digest = MessageDigest.getInstance("SHA-256").digest(
                (server + "\u0000" + username).getBytes(StandardCharsets.UTF_8));
        String name = toHex(digest);
        File directory = new File(context.getNoBackupFilesDir(), "vaults");
        if (!directory.isDirectory() && !directory.mkdirs()) {
            throw new Exception("无法建立本地安全存储目录");
        }
        file = new AtomicFile(new File(directory, name + ".vault"));
        alias = "chatapp.v1." + name;
    }

    JSONObject read() throws Exception {
        if (!vaultFileExists()) return null;
        FileInputStream input = null;
        try {
            // A missing key must never be replaced when ciphertext already exists.
            SecretKey key = existingKey();
            if (key == null) throw new Exception("本地密钥已丢失");
            input = file.openRead();
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            byte[] chunk = new byte[8192];
            int count;
            while ((count = input.read(chunk)) != -1) {
                bytes.write(chunk, 0, count);
                if (bytes.size() > 16 * 1024 * 1024) throw new Exception("本地数据异常大");
            }
            byte[] data = bytes.toByteArray();
            if (data.length < MAGIC.length + 12 + 16) throw new Exception("本地数据不完整");
            for (int i = 0; i < MAGIC.length; i++) {
                if (data[i] != MAGIC[i]) throw new Exception("本地数据版本不受支持");
            }
            byte[] nonce = new byte[12];
            System.arraycopy(data, MAGIC.length, nonce, 0, nonce.length);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(128, nonce));
            cipher.updateAAD(alias.getBytes(StandardCharsets.UTF_8));
            byte[] plain = cipher.doFinal(data, MAGIC.length + nonce.length,
                    data.length - MAGIC.length - nonce.length);
            return new JSONObject(new String(plain, StandardCharsets.UTF_8));
        } catch (Exception error) {
            throw new Exception("本地安全存储损坏或密钥已丢失；不会覆盖旧数据", error);
        } finally {
            if (input != null) input.close();
        }
    }

    void write(JSONObject state) throws Exception {
        byte[] plain = state.toString().getBytes(StandardCharsets.UTF_8);
        SecretKey key = existingKey();
        if (key == null) {
            // Only a truly new vault may create a key. An absent key for an existing
            // vault means restore or keystore loss and must fail closed.
            if (vaultFileExists()) throw new Exception("本地密钥已丢失，拒绝覆盖旧数据");
            key = newKey();
        }
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.ENCRYPT_MODE, key);
        byte[] nonce = cipher.getIV();
        if (nonce == null || nonce.length != 12) throw new Exception("安全存储随机数生成失败");
        cipher.updateAAD(alias.getBytes(StandardCharsets.UTF_8));
        byte[] encrypted = cipher.doFinal(plain);
        ByteBuffer output = ByteBuffer.allocate(MAGIC.length + nonce.length + encrypted.length);
        output.put(MAGIC).put(nonce).put(encrypted);
        FileOutputStream stream = null;
        try {
            stream = file.startWrite();
            stream.write(output.array());
            file.finishWrite(stream); // AtomicFile syncs data before replacing the previous state.
        } catch (Exception error) {
            if (stream != null) file.failWrite(stream);
            throw new Exception("本地加密保存失败", error);
        }
    }

    private SecretKey existingKey() throws Exception {
        KeyStore store = KeyStore.getInstance(KEYSTORE);
        store.load(null);
        KeyStore.Entry entry = store.getEntry(alias, null);
        if (entry == null) return null;
        if (!(entry instanceof KeyStore.SecretKeyEntry)) throw new Exception("安全密钥类型错误");
        return ((KeyStore.SecretKeyEntry) entry).getSecretKey();
    }

    private boolean vaultFileExists() {
        File base = file.getBaseFile();
        return base.exists() || new File(base.getPath() + ".bak").exists();
    }

    private SecretKey newKey() throws Exception {
        KeyGenerator generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE);
        generator.init(new KeyGenParameterSpec.Builder(alias,
                KeyProperties.PURPOSE_ENCRYPT | KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .setRandomizedEncryptionRequired(true)
                .build());
        return generator.generateKey();
    }

    private static String toHex(byte[] value) {
        char[] digits = "0123456789abcdef".toCharArray();
        char[] result = new char[value.length * 2];
        for (int i = 0; i < value.length; i++) {
            result[i * 2] = digits[(value[i] >>> 4) & 15];
            result[i * 2 + 1] = digits[value[i] & 15];
        }
        return new String(result);
    }
}
