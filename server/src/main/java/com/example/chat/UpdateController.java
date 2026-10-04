package com.example.chat;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.Semaphore;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.server.ResponseStatusException;

@Controller
public class UpdateController {
    private static final int MAX_MANIFEST_BYTES = 128 * 1024;
    private static final long MAX_PACKAGE_BYTES = 512L * 1024 * 1024;
    private static final Pattern VERSION = Pattern.compile("(0|[1-9][0-9]{0,5})\\.(0|[1-9][0-9]{0,5})\\.(0|[1-9][0-9]{0,5})");
    private static final Pattern ANDROID = Pattern.compile("android-(arm64-v8a|armeabi-v7a|x86|x86_64)-(debug|release)");
    private static final Set<String> SUPPORTED = Set.of("windows-x64", "android-arm64-v8a-debug", "android-arm64-v8a-release",
        "android-armeabi-v7a-debug", "android-armeabi-v7a-release", "android-x86-debug", "android-x86-release",
        "android-x86_64-debug", "android-x86_64-release");
    private final Path root;
    private final ObjectMapper json;
    private final Semaphore activeReads = new Semaphore(2, true);
    private final Map<Path, VerifiedFile> verified = Collections.synchronizedMap(new LinkedHashMap<>(32, .75f, true) {
        @Override protected boolean removeEldestEntry(Map.Entry<Path, VerifiedFile> entry) { return size() > 32; }
    });

    private record VerifiedFile(Object key, long size, long modifiedMillis, String sha256) {
        boolean matches(BasicFileAttributes attributes, String expectedHash) {
            return key != null && Objects.equals(key, attributes.fileKey()) && size == attributes.size()
                && modifiedMillis == attributes.lastModifiedTime().toMillis() && sha256.equals(expectedHash);
        }
    }

    public UpdateController(@Value("${chat.updates-directory}") String directory, ObjectMapper json) {
        root = Path.of(directory).toAbsolutePath().normalize();
        this.json = json;
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Release(String platform, String version, Integer versionCode, String fileName,
                          long size, String sha256, String downloadPath, String notes) {}

    @GetMapping("/api/updates/latest")
    public ResponseEntity<Release> latest(@RequestParam String platform) {
        Release release = select(platform);
        if (!activeReads.tryAcquire()) throw busy();
        try (FileChannel file = openVerified(release, true)) {
            return ResponseEntity.ok().cacheControl(org.springframework.http.CacheControl.noStore()).body(release);
        } catch (IOException e) {
            throw unavailable();
        } finally {
            activeReads.release();
        }
    }

    @GetMapping("/api/updates/files/{platform}")
    public void download(@PathVariable String platform, HttpServletResponse response) throws IOException {
        Release release = select(platform);
        if (!activeReads.tryAcquire()) throw busy();
        try (FileChannel file = openVerified(release, false)) {
            response.setContentType(release.fileName().endsWith(".apk")
                ? "application/vnd.android.package-archive" : "application/zip");
            response.setHeader(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + release.fileName() + "\"");
            response.setHeader("X-Content-Type-Options", "nosniff");
            response.setHeader(HttpHeaders.CACHE_CONTROL, "no-store");
            response.setContentLengthLong(release.size());
            ByteBuffer buffer = ByteBuffer.allocate(64 * 1024);
            var output = response.getOutputStream();
            while (file.read(buffer) != -1) {
                buffer.flip();
                output.write(buffer.array(), 0, buffer.remaining());
                buffer.clear();
            }
        } catch (IOException e) {
            if (!response.isCommitted()) throw unavailable();
            throw e;
        } finally {
            activeReads.release();
        }
    }

    private Release select(String platform) {
        if (!SUPPORTED.contains(platform)) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "未知平台");
        return releases().stream().filter(r -> r.platform().equals(platform))
            .max(Comparator.comparing(Release::version, UpdateController::compareVersions))
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "暂无此平台安装包"));
    }

    private List<Release> releases() {
        if (!Files.isDirectory(root, LinkOption.NOFOLLOW_LINKS)) return List.of();
        Path manifest = root.resolve("manifest.json");
        if (!Files.exists(manifest, LinkOption.NOFOLLOW_LINKS)) return List.of();
        if (!Files.isRegularFile(manifest, LinkOption.NOFOLLOW_LINKS)) throw unavailable();
        try (InputStream stream = Files.newInputStream(manifest, LinkOption.NOFOLLOW_LINKS)) {
            byte[] raw = stream.readNBytes(MAX_MANIFEST_BYTES + 1);
            if (raw.length > MAX_MANIFEST_BYTES) throw unavailable();
            JsonNode document = json.readTree(raw);
            if (document == null || !document.path("schemaVersion").isIntegralNumber()
                    || document.path("schemaVersion").asInt() != 1 || !document.path("releases").isArray()) throw unavailable();
            List<Release> parsed = new ArrayList<>();
            Set<String> seen = new HashSet<>();
            for (JsonNode item : document.path("releases")) {
                Release release = parse(item);
                if (!seen.add(release.platform() + "/" + release.version())) throw unavailable();
                parsed.add(release);
            }
            return parsed;
        } catch (IOException e) {
            throw unavailable();
        }
    }

    private Release parse(JsonNode item) {
        String platform = text(item, "platform");
        String version = text(item, "version");
        String fileName = text(item, "fileName");
        String hash = text(item, "sha256");
        if (!SUPPORTED.contains(platform) || !VERSION.matcher(version).matches()
                || !expectedName(platform).equals(fileName) || !hash.matches("[0-9a-f]{64}")) throw unavailable();
        JsonNode sizeNode = item.path("size");
        if (!sizeNode.isIntegralNumber() || !sizeNode.canConvertToLong() || sizeNode.asLong() <= 0
                || sizeNode.asLong() > MAX_PACKAGE_BYTES) throw unavailable();
        Integer versionCode = null;
        JsonNode codeNode = item.path("versionCode");
        if (platform.startsWith("android-")) {
            if (!codeNode.isIntegralNumber() || !codeNode.canConvertToInt() || codeNode.asInt() <= 0) throw unavailable();
            versionCode = codeNode.asInt();
        } else if (!codeNode.isMissingNode() && !codeNode.isNull()) throw unavailable();
        JsonNode notesNode = item.path("notes");
        String notes = null;
        if (!notesNode.isMissingNode() && !notesNode.isNull()) {
            if (!notesNode.isTextual() || notesNode.asText().length() > 1000) throw unavailable();
            notes = notesNode.asText();
        }
        JsonNode downloadNode = item.path("downloadPath");
        String downloadPath = "/api/updates/files/" + platform;
        if (!downloadNode.isMissingNode() && (!downloadNode.isTextual() || !downloadPath.equals(downloadNode.asText()))) throw unavailable();
        return new Release(platform, version, versionCode, fileName, sizeNode.asLong(), hash, downloadPath, notes);
    }

    private static String text(JsonNode node, String name) {
        JsonNode value = node.path(name);
        if (!value.isTextual()) throw unavailable();
        return value.asText();
    }

    private static String expectedName(String platform) {
        if (platform.equals("windows-x64")) return "Chat-Client-Windows-x64.zip";
        Matcher match = ANDROID.matcher(platform);
        if (!match.matches()) throw unavailable();
        return "Chat-Android-" + match.group(1) + "-" + match.group(2) + ".apk";
    }

    private FileChannel openVerified(Release release, boolean allowCache) throws IOException {
        Path releases = root.resolve("releases");
        Path version = releases.resolve(release.version());
        Path path = version.resolve(release.fileName());
        if (!Files.isDirectory(root, LinkOption.NOFOLLOW_LINKS)
                || !Files.isDirectory(releases, LinkOption.NOFOLLOW_LINKS)
                || !Files.isDirectory(version, LinkOption.NOFOLLOW_LINKS)) throw unavailable();
        if (!Files.exists(path, LinkOption.NOFOLLOW_LINKS))
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "暂无此平台安装包");
        if (!Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) throw unavailable();
        FileChannel channel = FileChannel.open(path, Set.of(StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS));
        try {
            if (channel.size() != release.size()) throw unavailable();
            BasicFileAttributes attributes = Files.readAttributes(path, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
            VerifiedFile cached = verified.get(path);
            if (allowCache && cached != null && cached.matches(attributes, release.sha256())) return channel;
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            ByteBuffer buffer = ByteBuffer.allocate(64 * 1024);
            while (channel.read(buffer) != -1) {
                buffer.flip();
                digest.update(buffer);
                buffer.clear();
            }
            if (!MessageDigest.isEqual(digest.digest(), HexFormat.of().parseHex(release.sha256()))) throw unavailable();
            verified.put(path, new VerifiedFile(attributes.fileKey(), attributes.size(),
                attributes.lastModifiedTime().toMillis(), release.sha256()));
            channel.position(0);
            return channel;
        } catch (IOException | RuntimeException e) {
            channel.close();
            throw e;
        } catch (NoSuchAlgorithmException e) {
            channel.close();
            throw new IllegalStateException(e);
        }
    }

    private static int compareVersions(String left, String right) {
        String[] a = left.split("\\."), b = right.split("\\.");
        for (int i = 0; i < 3; i++) {
            int cmp = Integer.compare(Integer.parseInt(a[i]), Integer.parseInt(b[i]));
            if (cmp != 0) return cmp;
        }
        return 0;
    }

    private static ResponseStatusException unavailable() {
        return new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "更新包暂不可用");
    }

    private static ResponseStatusException busy() {
        return new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS, "更新下载繁忙，请稍后重试");
    }
}
