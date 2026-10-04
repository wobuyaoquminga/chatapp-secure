package com.example.chatandroid;

import org.json.JSONObject;
import org.junit.Test;

import java.io.File;
import java.nio.file.Files;

import static org.junit.Assert.*;

public class UpdatePolicyTest {
    private static JSONObject valid() throws Exception {
        return new JSONObject().put("platform", "android-arm64-v8a-debug").put("version", "0.5.1")
                .put("versionCode", 17).put("fileName", "Chat-Android-arm64-v8a-debug.apk")
                .put("size", 123).put("sha256", "a".repeat(64))
                .put("downloadPath", "/api/updates/files/android-arm64-v8a-debug");
    }

    @Test public void acceptsOnlyExactChannelAndIncreasingVersion() throws Exception {
        assertEquals(17, UpdatePolicy.parse(valid(), UpdatePolicy.platform(true), 16).versionCode);
        reject(valid(), "android-arm64-v8a-release", 16);
        reject(valid(), "android-arm64-v8a-debug", 17);
        reject(valid().put("size", UpdatePolicy.MAX_BYTES + 1), "android-arm64-v8a-debug", 16);
        reject(valid().put("sha256", "A".repeat(64)), "android-arm64-v8a-debug", 16);
        reject(valid().put("downloadPath", "https://evil.invalid/apk"), "android-arm64-v8a-debug", 16);
        reject(valid().put("fileName", "other.apk"), "android-arm64-v8a-debug", 16);
        reject(valid().put("versionCode", 17.0), "android-arm64-v8a-debug", 16);
        reject(valid().put("size", 123.0), "android-arm64-v8a-debug", 16);
    }

    @Test public void originAndPathStayOnTrustedServer() throws Exception {
        assertEquals("https://chat.example/api/updates/files/android-arm64-v8a-debug",
                UpdatePolicy.endpoint(UpdatePolicy.origin("https://chat.example"),
                        "/api/updates/files/android-arm64-v8a-debug").toString());
        assertEquals("http://127.0.0.1:8080", UpdatePolicy.origin("http://127.0.0.1:8080").toString());
        assertEquals("http://[::1]:8080", UpdatePolicy.origin("http://[::1]:8080").toString());
        for (String address : new String[]{"http://192.168.1.2", "http://evil.invalid", "https://user@chat.example", "https://chat.example/base"}) {
            try { UpdatePolicy.origin(address); fail(address); } catch (Exception expected) { }
        }
        for (String path : new String[]{"//evil.invalid/file", "/api/updates/../admin", "/api/updates/files/%2e%2e"}) {
            try { UpdatePolicy.endpoint(UpdatePolicy.origin("https://chat.example"), path); fail(path); } catch (Exception expected) { }
        }
    }

    @Test public void verifiesHashAndExactSignerSet() throws Exception {
        File file = File.createTempFile("update-policy", ".apk");
        try {
            Files.write(file.toPath(), "abc".getBytes(java.nio.charset.StandardCharsets.UTF_8));
            assertEquals("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad", UpdatePolicy.sha256(file));
        } finally { file.delete(); }
        assertTrue(UpdatePolicy.sameSigners(new String[]{"a", "b"}, new String[]{"b", "a"}));
        assertFalse(UpdatePolicy.sameSigners(new String[]{"a"}, new String[]{"b"}));
        assertFalse(UpdatePolicy.sameSigners(new String[]{"a", "a"}, new String[]{"a"}));
        assertFalse(UpdatePolicy.sameSigners(new String[0], new String[0]));
        UpdatePolicy.Release release = UpdatePolicy.parse(valid(), UpdatePolicy.platform(true), 16);
        assertTrue(UpdatePolicy.validPackage("com.example.chatandroid.debug", 17, "0.5.1", new String[]{"signer"},
                "com.example.chatandroid.debug", 16, new String[]{"signer"}, release));
        assertFalse(UpdatePolicy.validPackage("com.example.chatandroid", 17, "0.5.1", new String[]{"signer"},
                "com.example.chatandroid.debug", 16, new String[]{"signer"}, release));
        assertFalse(UpdatePolicy.validPackage("com.example.chatandroid.debug", 17, "0.5.1", new String[]{"other"},
                "com.example.chatandroid.debug", 16, new String[]{"signer"}, release));
        assertFalse(UpdatePolicy.validPackage("com.example.chatandroid.debug", 16, "0.5.1", new String[]{"signer"},
                "com.example.chatandroid.debug", 16, new String[]{"signer"}, release));
    }

    private static void reject(JSONObject json, String platform, int installed) throws Exception {
        try { UpdatePolicy.parse(json, platform, installed); fail("Expected rejection"); }
        catch (Exception expected) { assertNotNull(expected.getMessage()); }
    }
}
