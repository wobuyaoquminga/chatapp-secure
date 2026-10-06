package com.example.chatandroid;

import org.json.JSONObject;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.Base64;
import java.util.Iterator;
import java.util.Locale;
import java.util.UUID;
import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

/** Encrypted attachment descriptor carried inside a normal Signal message. */
final class FilePayload {
    static final String PREFIX = "CHAT_FILE_V1:";
    static final int MAX_SIZE = 10 * 1024 * 1024;
    final String id, name, key, iv, sha256, expiresAt;
    final int size;

    private FilePayload(JSONObject json) throws Exception {
        if (json.length() != 8 || !(json.opt("v") instanceof Integer) || json.optInt("v") != 1)
            throw new Exception("文件描述格式无效");
        for (Iterator<String> fields = json.keys(); fields.hasNext();) {
            if (!java.util.Set.of("v", "id", "name", "size", "key", "iv", "sha256", "expiresAt")
                    .contains(fields.next())) throw new Exception("文件描述字段无效");
        }
        id = string(json, "id"); name = string(json, "name"); key = string(json, "key");
        iv = string(json, "iv"); sha256 = string(json, "sha256"); expiresAt = string(json, "expiresAt");
        if (!id.matches("[0-9a-f]{8}-[0-9a-f]{4}-[1-5][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}")
                || !UUID.fromString(id).toString().equals(id)) throw new Exception("文件编号无效");
        if (!name.equals(safeName(name))) throw new Exception("文件名无效");
        Object rawSize = json.opt("size");
        if (!(rawSize instanceof Integer || rawSize instanceof Long)
                || ((Number) rawSize).longValue() < 0 || ((Number) rawSize).longValue() > MAX_SIZE)
            throw new Exception("文件大小无效");
        size = ((Number) rawSize).intValue();
        if (decode(key, 32).length != 32 || decode(iv, 12).length != 12
                || !sha256.matches("[0-9a-f]{64}")
                || !expiresAt.matches("\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2}(?:\\.\\d{1,9})?Z"))
            throw new Exception("文件描述格式无效");
        Instant.parse(expiresAt);
    }

    static FilePayload parse(String body) {
        if (body == null || !body.startsWith(PREFIX) || body.length() >= 4000) return null;
        try { return new FilePayload(new JSONObject(body.substring(PREFIX.length()))); }
        catch (Exception invalid) { return null; }
    }

    String body() throws Exception {
        String result = PREFIX + new JSONObject().put("v", 1).put("id", id).put("name", name)
                .put("size", size).put("key", key).put("iv", iv).put("sha256", sha256)
                .put("expiresAt", expiresAt);
        if (result.length() >= 4000) throw new Exception("文件描述过长");
        return result;
    }

    static final class Encrypted {
        final FilePayload payload;
        final byte[] bytes;
        Encrypted(FilePayload payload, byte[] bytes) { this.payload = payload; this.bytes = bytes; }
    }

    static Encrypted encrypt(String id, String name, byte[] plain, String expiresAt) throws Exception {
        byte[] key = new byte[32], iv = new byte[12];
        SecureRandom random = new SecureRandom(); random.nextBytes(key); random.nextBytes(iv);
        return encrypt(id, name, plain, expiresAt, key, iv);
    }

    static Encrypted encrypt(String id, String name, byte[] plain, String expiresAt, byte[] key, byte[] iv) throws Exception {
        if (plain.length > MAX_SIZE || key.length != 32 || iv.length != 12) throw new Exception("文件过大或密钥无效");
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(key, "AES"), new GCMParameterSpec(128, iv));
        cipher.updateAAD(id.getBytes(StandardCharsets.US_ASCII));
        byte[] bytes = cipher.doFinal(plain);
        JSONObject descriptor = new JSONObject().put("v", 1).put("id", id).put("name", safeName(name))
                .put("size", plain.length).put("key", Base64.getEncoder().encodeToString(key))
                .put("iv", Base64.getEncoder().encodeToString(iv)).put("sha256", hex(bytes))
                .put("expiresAt", expiresAt);
        return new Encrypted(new FilePayload(descriptor), bytes);
    }

    static Encrypted encryptDescriptor(Encrypted encrypted, String expiresAt) throws Exception {
        JSONObject descriptor = new JSONObject(encrypted.payload.body().substring(PREFIX.length()));
        descriptor.put("expiresAt", expiresAt);
        return new Encrypted(new FilePayload(descriptor), encrypted.bytes);
    }

    byte[] decrypt(byte[] encrypted) throws Exception {
        if (encrypted.length != size + 16 || !MessageDigest.isEqual(
                sha256.getBytes(StandardCharsets.US_ASCII), hex(encrypted).getBytes(StandardCharsets.US_ASCII)))
            throw new SecurityException("文件校验失败");
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.DECRYPT_MODE, new SecretKeySpec(decode(key, 32), "AES"),
                new GCMParameterSpec(128, decode(iv, 12)));
        cipher.updateAAD(id.getBytes(StandardCharsets.US_ASCII));
        return cipher.doFinal(encrypted);
    }

    static String safeName(String value) {
        if (value == null) return "file";
        String name = value.replace('\\', '/');
        name = name.substring(name.lastIndexOf('/') + 1);
        name = name.replaceAll("^[\\x09-\\x0D\\x20\\xA0\\x{1680}\\x{2000}-\\x{200A}\\x{2028}\\x{2029}\\x{202F}\\x{205F}\\x{3000}\\x{FEFF}]+|[\\x09-\\x0D\\x20\\xA0\\x{1680}\\x{2000}-\\x{200A}\\x{2028}\\x{2029}\\x{202F}\\x{205F}\\x{3000}\\x{FEFF}]+$", "");
        name = name.replaceAll("[<>:\"/\\\\|?*\\x00-\\x1F\\x7F]", "_");
        name = name.replaceAll("[. ]+$", "");
        int count = name.codePointCount(0, name.length());
        if (count > 128) name = name.substring(0, name.offsetByCodePoints(0, 128)).replaceAll("[. ]+$", "");
        if (name.isEmpty() || name.matches("(?i)^(CON|PRN|AUX|NUL|COM[1-9]|LPT[1-9])(?:\\..*)?$"))
            return "file";
        return name;
    }

    private static String string(JSONObject json, String field) throws Exception {
        Object value = json.opt(field);
        if (!(value instanceof String)) throw new Exception("文件描述格式无效");
        return (String) value;
    }

    private static byte[] decode(String value, int expected) throws Exception {
        byte[] decoded = Base64.getDecoder().decode(value);
        if (decoded.length != expected || !Base64.getEncoder().encodeToString(decoded).equals(value))
            throw new Exception("文件密钥格式无效");
        return decoded;
    }

    private static String hex(byte[] bytes) throws Exception {
        byte[] digest = MessageDigest.getInstance("SHA-256").digest(bytes);
        StringBuilder result = new StringBuilder(64);
        for (byte b : digest) result.append(String.format(Locale.ROOT, "%02x", b & 255));
        return result.toString();
    }
}
