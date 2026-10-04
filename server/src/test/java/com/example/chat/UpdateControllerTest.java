package com.example.chat;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.ServletOutputStream;
import jakarta.servlet.WriteListener;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.FileSystemException;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Assumptions;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.web.server.ResponseStatusException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(properties = {
    "spring.datasource.url=jdbc:h2:mem:update-test;DB_CLOSE_DELAY=-1",
    "chat.jwt-secret=update-test-secret-longer-than-thirty-two-bytes"
})
@AutoConfigureMockMvc
class UpdateControllerTest {
    private static final Path UPDATES;
    static {
        try { UPDATES = Files.createTempDirectory("chat-update-test-"); }
        catch (IOException e) { throw new ExceptionInInitializerError(e); }
    }
    @DynamicPropertySource static void updates(DynamicPropertyRegistry registry) {
        registry.add("chat.updates-directory", UPDATES::toString);
    }
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @Autowired UpdateController controller;

    @BeforeEach void reset() throws IOException {
        try (var paths = Files.walk(UPDATES)) {
            for (Path path : paths.sorted((a, b) -> b.getNameCount() - a.getNameCount()).toList()) {
                if (!path.equals(UPDATES)) Files.delete(path);
            }
        }
        Files.createDirectories(UPDATES.resolve("releases/0.6.1"));
    }

    @Test void publicMetadataAndDownloadsMatchRealZipBytes() throws Exception {
        byte[] windows = zip("Chat-win32-x64/Chat.exe", "windows-build");
        byte[] android = zip("AndroidManifest.xml", "android-build");
        var w = entry("windows-x64", "Chat-Client-Windows-x64.zip", windows, null);
        var a = entry("android-arm64-v8a-debug", "Chat-Android-arm64-v8a-debug.apk", android, 17);
        manifest(List.of(w, a));
        mvc.perform(get("/api/updates/latest").param("platform", "windows-x64"))
            .andExpect(status().isOk()).andExpect(jsonPath("$.version").value("0.6.1"))
            .andExpect(jsonPath("$.versionCode").doesNotExist())
            .andExpect(jsonPath("$.downloadPath").value("/api/updates/files/windows-x64"))
            .andExpect(jsonPath("$.sha256").value(w.get("sha256")));
        mvc.perform(get("/api/updates/latest").param("platform", "android-arm64-v8a-debug"))
            .andExpect(status().isOk()).andExpect(jsonPath("$.versionCode").value(17));
        var done = mvc.perform(get("/api/updates/files/windows-x64")).andExpect(status().isOk())
            .andExpect(request().asyncNotStarted())
            .andExpect(header().string("Content-Disposition", "attachment; filename=\"Chat-Client-Windows-x64.zip\""))
            .andReturn();
        assertThat(done.getResponse().getContentAsByteArray()).isEqualTo(windows);
        done = mvc.perform(get("/api/updates/files/android-arm64-v8a-debug"))
            .andExpect(status().isOk()).andExpect(request().asyncNotStarted()).andReturn();
        assertThat(done.getResponse().getContentAsByteArray()).isEqualTo(android);
    }

    @Test void failedResponseWriteReleasesBothDownloadSlots() throws Exception {
        byte[] windows = zip("Chat.exe", "content");
        manifest(List.of(entry("windows-x64", "Chat-Client-Windows-x64.zip", windows, null)));
        for (int attempt = 0; attempt < 2; attempt++) {
            var broken = new MockHttpServletResponse() {
                @Override public ServletOutputStream getOutputStream() {
                    return new ServletOutputStream() {
                        @Override public void write(int value) throws IOException { throw new IOException("client disconnected"); }
                        @Override public boolean isReady() { return true; }
                        @Override public void setWriteListener(WriteListener listener) {}
                    };
                }
            };
            assertThatThrownBy(() -> controller.download("windows-x64", broken))
                .isInstanceOf(ResponseStatusException.class);
        }
        mvc.perform(get("/api/updates/files/windows-x64"))
            .andExpect(status().isOk()).andExpect(request().asyncNotStarted());
    }

    @Test void missingUnknownAndPrivateRoutesStayClosed() throws Exception {
        mvc.perform(get("/api/updates/latest").param("platform", "android-arm64-v8a-release"))
            .andExpect(status().isNotFound());
        mvc.perform(get("/api/updates/latest").param("platform", "../../secret"))
            .andExpect(status().isBadRequest());
        mvc.perform(get("/api/updates/files/manifest.json")).andExpect(status().isBadRequest());
        mvc.perform(get("/api/account/status")).andExpect(status().isUnauthorized());
        mvc.perform(post("/api/updates/files/windows-x64")).andExpect(status().isUnauthorized());
    }

    @Test void tamperedFileAndUnsafeManifestCannotBeServed() throws Exception {
        byte[] zip = zip("Chat.exe", "original");
        var release = entry("windows-x64", "Chat-Client-Windows-x64.zip", zip, null);
        manifest(List.of(release));
        Files.write(UPDATES.resolve("releases/0.6.1/Chat-Client-Windows-x64.zip"), zip("Chat.exe", "tampered"));
        mvc.perform(get("/api/updates/latest").param("platform", "windows-x64"))
            .andExpect(status().isServiceUnavailable());
        mvc.perform(get("/api/updates/files/windows-x64"))
            .andExpect(status().isServiceUnavailable());
        release.put("fileName", "../../secret.txt");
        manifest(List.of(release));
        mvc.perform(get("/api/updates/files/windows-x64"))
            .andExpect(status().isServiceUnavailable());
    }

    @Test void absentFileAndOversizedManifestFailSafely() throws Exception {
        byte[] zip = zip("Chat.exe", "content");
        var release = entry("windows-x64", "Chat-Client-Windows-x64.zip", zip, null);
        manifest(List.of(release));
        Files.delete(UPDATES.resolve("releases/0.6.1/Chat-Client-Windows-x64.zip"));
        mvc.perform(get("/api/updates/files/windows-x64")).andExpect(status().isNotFound());
        Files.writeString(UPDATES.resolve("manifest.json"), " ".repeat(128 * 1024 + 1));
        mvc.perform(get("/api/updates/latest").param("platform", "windows-x64"))
            .andExpect(status().isServiceUnavailable());
    }

    @Test void symlinkCannotEscapeUpdateDirectory() throws Exception {
        byte[] zip = zip("Chat.exe", "private");
        var release = entry("windows-x64", "Chat-Client-Windows-x64.zip", zip, null);
        manifest(List.of(release));
        Path external = Files.createTempFile("chat-private-", ".zip");
        Files.write(external, zip);
        Path file = UPDATES.resolve("releases/0.6.1/Chat-Client-Windows-x64.zip");
        Files.delete(file);
        try { Files.createSymbolicLink(file, external); }
        catch (FileSystemException e) {
            Files.deleteIfExists(external);
            Assumptions.assumeTrue(false, "当前系统不允许创建符号链接");
        }
        mvc.perform(get("/api/updates/files/windows-x64"))
            .andExpect(status().isServiceUnavailable());
        Files.deleteIfExists(external);
    }

    private Map<String, Object> entry(String platform, String name, byte[] bytes, Integer code) throws Exception {
        Files.write(UPDATES.resolve("releases/0.6.1").resolve(name), bytes);
        var item = new java.util.LinkedHashMap<String, Object>();
        item.put("platform", platform);
        item.put("version", "0.6.1");
        if (code != null) item.put("versionCode", code);
        item.put("fileName", name);
        item.put("size", bytes.length);
        item.put("sha256", HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)));
        item.put("downloadPath", "/api/updates/files/" + platform);
        return item;
    }

    private void manifest(List<Map<String, Object>> releases) throws Exception {
        Files.write(UPDATES.resolve("manifest.json"), json.writeValueAsBytes(Map.of("schemaVersion", 1, "releases", releases)));
    }

    private static byte[] zip(String name, String content) throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        try (ZipOutputStream archive = new ZipOutputStream(output)) {
            archive.putNextEntry(new ZipEntry(name));
            archive.write(content.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            archive.closeEntry();
        }
        return output.toByteArray();
    }
}
