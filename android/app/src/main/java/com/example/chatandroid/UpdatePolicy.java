package com.example.chatandroid;

import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.net.URI;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.Locale;
import java.util.Set;
import java.util.TreeSet;

/** Strict, Android-independent checks for the server update protocol. */
final class UpdatePolicy {
    static final long MAX_BYTES = 512L * 1024 * 1024;

    static final class Release {
        final String platform, version, fileName, sha256, downloadPath, notes;
        final long size;
        final int versionCode;
        Release(String platform, String version, int versionCode, String fileName,
                long size, String sha256, String downloadPath, String notes) {
            this.platform = platform; this.version = version; this.versionCode = versionCode;
            this.fileName = fileName; this.size = size; this.sha256 = sha256;
            this.downloadPath = downloadPath; this.notes = notes;
        }
    }

    static String platform(boolean debug) { return "android-arm64-v8a-" + (debug ? "debug" : "release"); }

    static URI origin(String address) throws Exception {
        URI uri = new URI(address);
        String scheme = uri.getScheme(), host = uri.getHost();
        if (scheme == null || host == null || uri.getRawUserInfo() != null || uri.getRawQuery() != null
                || uri.getRawFragment() != null || uri.getPort() == 0 || uri.getPort() > 65535
                || !(uri.getRawPath() == null || uri.getRawPath().isEmpty() || uri.getRawPath().equals("/")))
            throw new Exception("服务器地址无效");
        boolean loopback = host.equalsIgnoreCase("localhost") || host.equals("127.0.0.1")
                || host.equals("::1") || host.equals("[::1]");
        if (!scheme.equalsIgnoreCase("https") && !(scheme.equalsIgnoreCase("http") && loopback))
            throw new Exception("更新只支持 HTTPS 服务器或开发回环地址");
        return new URI(scheme.toLowerCase(Locale.ROOT), null, host.toLowerCase(Locale.ROOT), uri.getPort(), null, null, null);
    }

    static URI endpoint(URI origin, String path) throws Exception {
        if (path == null || !path.matches("/api/updates/[A-Za-z0-9/_?=.-]+") || path.contains("..")
                || path.contains("//") || path.contains("\\")) throw new Exception("下载路径无效");
        URI uri = origin.resolve(path);
        if (!uri.getScheme().equals(origin.getScheme()) || !uri.getHost().equals(origin.getHost())
                || uri.getPort() != origin.getPort() || uri.getRawUserInfo() != null)
            throw new Exception("更新地址跨越当前服务器");
        return uri;
    }

    static Release parse(JSONObject json, String platform, int installedVersion) throws Exception {
        if (json == null || !platform.equals(requiredString(json, "platform"))) throw new Exception("更新平台不匹配");
        String version = requiredString(json, "version");
        String fileName = requiredString(json, "fileName");
        String sha = requiredString(json, "sha256");
        String path = requiredString(json, "downloadPath");
        Object code = json.opt("versionCode"), bytes = json.opt("size");
        if (!version.matches("(0|[1-9][0-9]{0,5})\\.(0|[1-9][0-9]{0,5})\\.(0|[1-9][0-9]{0,5})")
                || !fileName.equals("Chat-Android-arm64-v8a-" + (platform.endsWith("-debug") ? "debug" : "release") + ".apk")
                || !integer(code) || ((Number) code).longValue() <= 0
                || ((Number) code).longValue() > Integer.MAX_VALUE
                || !integer(bytes) || ((Number) bytes).longValue() <= 0
                || ((Number) bytes).longValue() > MAX_BYTES
                || !sha.matches("[0-9a-f]{64}") || !path.equals("/api/updates/files/" + platform))
            throw new Exception("更新清单字段无效或版本不高于当前版本");
        if (((Number) code).longValue() <= installedVersion)
            throw new Exception(((Number) code).longValue() == installedVersion
                    ? "当前已是最新版本" : "当前服务器没有更高版本");
        Object notesValue = json.opt("notes");
        if (notesValue != null && notesValue != JSONObject.NULL
                && (!(notesValue instanceof String) || ((String) notesValue).length() > 1000))
            throw new Exception("更新说明无效");
        return new Release(platform, version, (int) ((Number) code).longValue(), fileName,
                ((Number) bytes).longValue(), sha, path, notesValue instanceof String ? (String) notesValue : "");
    }

    private static String requiredString(JSONObject object, String key) throws Exception {
        Object value = object.opt(key);
        if (!(value instanceof String)) throw new Exception("更新清单缺少 " + key);
        return (String) value;
    }

    private static boolean integer(Object value) { return value instanceof Integer || value instanceof Long; }

    static boolean validPackage(String actualName, long actualVersion, String actualVersionName,
                                String[] actualSigners, String installedName, long installedVersion,
                                String[] installedSigners, Release release) {
        return installedName.equals(actualName) && actualVersion == release.versionCode
                && actualVersion > installedVersion && release.version.equals(actualVersionName)
                && sameSigners(installedSigners, actualSigners);
    }

    static boolean sameSigners(String[] installed, String[] candidate) {
        if (installed == null || candidate == null || installed.length == 0 || candidate.length == 0) return false;
        Set<String> a = new TreeSet<>(Arrays.asList(installed));
        Set<String> b = new TreeSet<>(Arrays.asList(candidate));
        return a.size() == installed.length && b.size() == candidate.length && a.equals(b);
    }

    static String sha256(File file) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        try (InputStream input = new FileInputStream(file)) {
            byte[] buffer = new byte[64 * 1024];
            for (int n; (n = input.read(buffer)) != -1;) {
                if (Thread.currentThread().isInterrupted()) throw new InterruptedException("下载已取消");
                digest.update(buffer, 0, n);
            }
        }
        StringBuilder result = new StringBuilder(64);
        for (byte b : digest.digest()) result.append(String.format(Locale.ROOT, "%02x", b & 255));
        return result.toString();
    }
}
