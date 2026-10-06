package com.example.chatandroid;

import static org.junit.Assert.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.security.MessageDigest;
import java.util.Base64;
import org.json.JSONObject;
import org.junit.Test;

public class FilePayloadTest {
    private static JSONObject vector() throws Exception {
        return new JSONObject(new String(Files.readAllBytes(Paths.get(System.getProperty("chat.root"),
                "scripts", "file-transfer-vector.json")), StandardCharsets.UTF_8));
    }

    @Test public void sharedAesGcmVectorAgreesWithWindows() throws Exception {
        JSONObject vector = vector();
        byte[] plain = Base64.getDecoder().decode(vector.getString("plain"));
        byte[] ciphertext = Base64.getDecoder().decode(vector.getString("ciphertext"));
        FilePayload.Encrypted encrypted = FilePayload.encrypt(vector.getString("id"), vector.getString("name"),
                plain, vector.getString("expiresAt"), Base64.getDecoder().decode(vector.getString("key")),
                Base64.getDecoder().decode(vector.getString("iv")));
        assertArrayEquals(ciphertext, encrypted.bytes);
        assertEquals(vector.getString("sha256"), encrypted.payload.sha256);
        assertArrayEquals(plain, FilePayload.parse(encrypted.payload.body()).decrypt(ciphertext));
    }

    @Test public void invalidMetadataAndTamperingCannotBeSaved() throws Exception {
        FilePayload.Encrypted encrypted = FilePayload.encrypt("12345678-1234-4234-8234-123456789abc",
                "safe.txt", new byte[0], "2030-01-01T00:00:00Z");
        assertEquals(16, encrypted.bytes.length);
        assertEquals(0, encrypted.payload.decrypt(encrypted.bytes).length);
        assertEquals("[文件] safe.txt", HistorySearch.preview(
                new JSONObject().put("body", encrypted.payload.body())));
        byte[] tampered = encrypted.bytes.clone(); tampered[15] ^= 1;
        try { encrypted.payload.decrypt(tampered); fail("Ciphertext changed"); }
        catch (SecurityException expected) { }
        JSONObject descriptor = new JSONObject(encrypted.payload.body().substring(FilePayload.PREFIX.length()));
        byte[] digest = MessageDigest.getInstance("SHA-256").digest(tampered);
        StringBuilder hash = new StringBuilder();
        for (byte value : digest) hash.append(String.format("%02x", value & 255));
        FilePayload forged = FilePayload.parse(FilePayload.PREFIX
                + new JSONObject(descriptor.toString()).put("sha256", hash.toString()));
        assertNotNull(forged);
        try { forged.decrypt(tampered); fail("GCM tag changed"); }
        catch (javax.crypto.AEADBadTagException expected) { }
        assertNull(FilePayload.parse(FilePayload.PREFIX + new JSONObject(descriptor.toString()).put("v", "1")));
        assertNull(FilePayload.parse(FilePayload.PREFIX + new JSONObject(descriptor.toString()).put("size", 0.5)));
        assertNull(FilePayload.parse(FilePayload.PREFIX + new JSONObject(descriptor.toString()).put("name", "../bad")));
        assertNull(FilePayload.parse(FilePayload.PREFIX + new JSONObject(descriptor.toString()).put("key", "wrong")));
    }

    @Test public void filenameRulesMatchBrowserCodepointsAndReservedNames() {
        assertEquals("file", FilePayload.safeName(" C:\\tmp\\CON.txt. "));
        assertEquals("a_b.txt", FilePayload.safeName("/tmp/a?b.txt"));
        assertEquals("file", FilePayload.safeName("\u3000.\u3000"));
        String emoji = "😀".repeat(130);
        assertEquals(128, FilePayload.safeName(emoji).codePointCount(0, FilePayload.safeName(emoji).length()));
    }
}
