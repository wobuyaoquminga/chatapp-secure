package com.example.chatandroid;

import android.content.Context;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.Signature;
import android.os.Build;
import android.os.SystemClock;

import org.json.JSONObject;

import java.io.File;
import java.io.ByteArrayOutputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.util.Locale;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import okhttp3.Call;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;

/** Performs all update I/O off the UI thread, scoped to one selected server. */
final class ServerUpdates {
    interface Listener {
        void status(long generation, String server, String message, UpdatePolicy.Release release, int percent, boolean ready);
    }

    private final Context context;
    private final Listener listener;
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private final OkHttpClient http = new OkHttpClient.Builder().followRedirects(false)
            .followSslRedirects(false).callTimeout(10, TimeUnit.MINUTES).build();
    private volatile long generation;
    private volatile Call active;
    private volatile boolean closed;
    private volatile UpdatePolicy.Release available;
    private volatile String availableServer = "";
    long generation() { return generation; }

    ServerUpdates(Context context, Listener listener) {
        this.context = context.getApplicationContext();
        this.listener = listener;
    }

    synchronized void check(String server) {
        if (closed) return;
        cancel();
        final long task = generation;
        worker.execute(() -> {
            try {
                ensureCurrent(task);
                URI origin = UpdatePolicy.origin(server);
                String supportedPackage = BuildConfig.DEBUG ? "com.example.chatandroid.debug" : "com.example.chatandroid";
                if (!supportedPackage.equals(context.getPackageName()))
                    throw new Exception("此安装渠道暂无服务器更新包");
                if (!java.util.Arrays.asList(Build.SUPPORTED_ABIS).contains("arm64-v8a"))
                    throw new Exception("此设备不支持 arm64-v8a 安装包");
                String platform = UpdatePolicy.platform(BuildConfig.DEBUG);
                URI endpoint = UpdatePolicy.endpoint(origin, "/api/updates/latest?platform=" + platform);
                Request request = new Request.Builder().url(endpoint.toString()).header("Accept", "application/json").build();
                Call call = http.newCall(request);
                call.timeout().timeout(30, TimeUnit.SECONDS);
                activate(task, call);
                try (Response response = call.execute()) {
                    ensureCurrent(task);
                    if (response.code() == 404) { report(task, server, "当前服务器暂无此渠道更新包", null, -1, false); return; }
                    if (!response.isSuccessful() || response.body() == null) throw new Exception("检查更新失败：HTTP " + response.code());
                    if (response.body().contentLength() > 64 * 1024) throw new Exception("更新清单过大");
                    try (InputStream input = response.body().byteStream()) {
                        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
                        byte[] chunk = new byte[4096];
                        for (int count; (count = input.read(chunk)) != -1;) {
                            bytes.write(chunk, 0, count);
                            if (bytes.size() > 64 * 1024) throw new Exception("更新清单过大");
                        }
                        byte[] raw = bytes.toByteArray();
                        if (raw.length > 64 * 1024) throw new Exception("更新清单过大");
                        int installed = (int) installedVersion();
                        UpdatePolicy.Release release = UpdatePolicy.parse(new JSONObject(new String(raw, java.nio.charset.StandardCharsets.UTF_8)), platform, installed);
                        UpdatePolicy.endpoint(origin, release.downloadPath);
                        synchronized (this) {
                            ensureCurrent(task);
                            available = release;
                            availableServer = server;
                        }
                        String notes = release.notes.isEmpty() ? "" : "\n" + release.notes;
                        report(task, server, "发现新版本 " + release.version + "（" + release.versionCode + "）" + notes, release, -1, false);
                    }
                } finally { if (active == call) active = null; }
            } catch (Exception error) { report(task, server, message(error), null, -1, false); }
        });
    }

    synchronized void download(String server) {
        if (closed) return;
        UpdatePolicy.Release release = available;
        if (release == null || !server.equals(availableServer)) return;
        download(server, release);
    }

    synchronized void download(String server, UpdatePolicy.Release release) {
        if (closed || release == null) return;
        cancelCallOnly();
        final long task = ++generation;
        worker.execute(() -> {
            File temporary = null;
            try {
                ensureCurrent(task);
                URI origin = UpdatePolicy.origin(server);
                URI url = UpdatePolicy.endpoint(origin, release.downloadPath);
                if (!folder().isDirectory() && !folder().mkdirs()) throw new Exception("无法创建下载目录");
                temporary = File.createTempFile("download-", ".tmp.apk", folder());
                Request request = new Request.Builder().url(url.toString()).header("Accept", "application/vnd.android.package-archive").build();
                Call call = http.newCall(request);
                call.timeout().timeout(30, TimeUnit.MINUTES);
                activate(task, call);
                try (Response response = call.execute()) {
                    ensureCurrent(task);
                    if (!response.isSuccessful() || response.body() == null) throw new Exception("下载失败：HTTP " + response.code());
                    long declared = response.body().contentLength();
                    if (declared > UpdatePolicy.MAX_BYTES || declared >= 0 && declared != release.size)
                        throw new Exception("下载大小与清单不符");
                    MessageDigest hash = MessageDigest.getInstance("SHA-256");
                    long total = 0;
                    try (InputStream input = response.body().byteStream(); FileOutputStream output = new FileOutputStream(temporary)) {
                        byte[] buffer = new byte[64 * 1024];
                        long lastTime = SystemClock.elapsedRealtime(), lastBytes = 0;
                        for (int n; (n = input.read(buffer)) != -1;) {
                            ensureCurrent(task);
                            total += n;
                            if (total > release.size || total > UpdatePolicy.MAX_BYTES) throw new Exception("下载内容超过清单大小");
                            output.write(buffer, 0, n);
                            hash.update(buffer, 0, n);
                            int percent = (int) (total * 100 / release.size);
                            long now = SystemClock.elapsedRealtime();
                            if (lastBytes == 0 || now - lastTime >= 1000) {
                                report(task, server, UpdateProgress.message(total, release.size,
                                        total - lastBytes, now - lastTime), release, percent, false);
                                lastTime = now;
                                lastBytes = total;
                            }
                        }
                        output.getFD().sync();
                    }
                    report(task, server, "下载完成，正在校验…（" + UpdateProgress.size(total) + "/"
                            + UpdateProgress.size(release.size) + "）", release, 100, false);
                    if (total != release.size || !hex(hash.digest()).equals(release.sha256)) throw new Exception("下载文件大小或 SHA256 校验失败");
                    verifyPackage(temporary, release);
                    ensureCurrent(task);
                    synchronized (this) {
                        ensureCurrent(task);
                        File target = installedFile();
                        Files.move(temporary.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
                        if (!context.getSharedPreferences("verified_update", 0).edit().putString("sha256", release.sha256).commit())
                            throw new Exception("无法保存安装包验证结果，请重新下载");
                    }
                    report(task, server, "已验证安装包：" + release.version + "。点击安装后由系统确认。", release, 100, true);
                } finally { if (active == call) active = null; }
            } catch (Exception error) { report(task, server, message(error), release, -1, false); }
            finally { if (temporary != null && temporary.exists()) temporary.delete(); }
        });
    }

    synchronized void cancel() {
        generation++;
        cancelCallOnly();
        context.getSharedPreferences("verified_update", 0).edit().remove("sha256").apply();
        available = null;
        availableServer = "";
    }

    synchronized void verifyForInstall(String server, UpdatePolicy.Release release, Runnable success) {
        if (closed) return;
        boolean downloaded = release != null && release.sha256.equals(context.getSharedPreferences("verified_update", 0)
                .getString("sha256", ""));
        if (release == null || !(available == release && server.equals(availableServer) || downloaded)) return;
        final long task = generation;
        worker.execute(() -> {
            try {
                ensureCurrent(task);
                verifyPackage(installedFile(), release);
                ensureCurrent(task);
                success.run();
            } catch (Exception error) { report(task, server, message(error), release, -1, false); }
        });
    }

    private synchronized void activate(long task, Call call) throws Exception {
        ensureCurrent(task);
        active = call;
    }
    private void cancelCallOnly() { Call call = active; if (call != null) call.cancel(); }
    synchronized void close() { closed = true; generation++; cancelCallOnly(); worker.shutdownNow(); }
    File installedFile() { return new File(folder(), "verified-update.apk"); }
    private File folder() { return new File(context.getFilesDir(), "updates"); }

    private void report(long task, String server, String message, UpdatePolicy.Release release, int percent, boolean ready) {
        if (!closed && task == generation) listener.status(task, server, message, release, percent, ready);
    }
    private void ensureCurrent(long task) throws Exception {
        if (closed || task != generation || Thread.currentThread().isInterrupted()) throw new Exception("下载已取消");
    }
    private static String message(Exception error) { return error.getMessage() == null ? "更新操作失败" : error.getMessage(); }
    private static String hex(byte[] bytes) {
        StringBuilder result = new StringBuilder();
        for (byte b : bytes) result.append(String.format(Locale.ROOT, "%02x", b & 255));
        return result.toString();
    }

    private long installedVersion() throws Exception { return version(installedInfo()); }
    private static long version(PackageInfo info) { return Build.VERSION.SDK_INT >= 28 ? info.getLongVersionCode() : info.versionCode; }

    private PackageInfo installedInfo() throws Exception {
        int flags = Build.VERSION.SDK_INT >= 28 ? PackageManager.GET_SIGNING_CERTIFICATES : PackageManager.GET_SIGNATURES;
        return context.getPackageManager().getPackageInfo(context.getPackageName(), flags);
    }

    void verifyPackage(File apk, UpdatePolicy.Release release) throws Exception {
        if (apk.length() != release.size || !UpdatePolicy.sha256(apk).equals(release.sha256))
            throw new Exception("安装包校验失败");
        int flags = Build.VERSION.SDK_INT >= 28 ? PackageManager.GET_SIGNING_CERTIFICATES : PackageManager.GET_SIGNATURES;
        PackageInfo candidate = context.getPackageManager().getPackageArchiveInfo(apk.getAbsolutePath(), flags);
        PackageInfo installed = installedInfo();
        if (candidate == null || !UpdatePolicy.validPackage(candidate.packageName, version(candidate),
                candidate.versionName, signers(candidate), context.getPackageName(), version(installed),
                signers(installed), release))
            throw new Exception("安装包包名、版本码或签名与当前应用不匹配");
    }

    private static String[] signers(PackageInfo info) throws Exception {
        Signature[] signatures = Build.VERSION.SDK_INT >= 28
                ? info.signingInfo == null ? null : info.signingInfo.getApkContentsSigners() : info.signatures;
        if (signatures == null || signatures.length == 0) return new String[0];
        String[] hashes = new String[signatures.length];
        for (int i = 0; i < signatures.length; i++)
            hashes[i] = hex(MessageDigest.getInstance("SHA-256").digest(signatures[i].toByteArray()));
        return hashes;
    }
}
