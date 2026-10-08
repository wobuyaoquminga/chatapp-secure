package com.example.chatandroid;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.PowerManager;
import android.os.SystemClock;

import java.lang.ref.WeakReference;

/** User-started update download, independent of the settings Activity. */
public final class UpdateDownloadService extends Service {
    private static final String CHANNEL = "chat_update_download";
    private static final int NOTIFICATION_ID = 4203;
    private static final long DOWNLOAD_LIMIT_MS = 30 * 60 * 1000L;
    private static final long WAKE_LIMIT_MS = 35 * 60 * 1000L;
    private static final String REQUEST_ID = "requestId";
    private static final String CANCEL = "cancel";
    private static long nextId;
    private static Request pending;
    private static volatile Snapshot snapshot;
    private static WeakReference<MainActivity> observer = new WeakReference<>(null);

    static final class Snapshot {
        final String server, message;
        final UpdatePolicy.Release release;
        final int percent;
        final boolean ready, active;
        Snapshot(String server, String message, UpdatePolicy.Release release, int percent, boolean ready, boolean active) {
            this.server = server; this.message = message; this.release = release;
            this.percent = percent; this.ready = ready; this.active = active;
        }
    }

    private static final class Request {
        final long id;
        final String server;
        final UpdatePolicy.Release release;
        Request(long id, String server, UpdatePolicy.Release release) {
            this.id = id; this.server = server; this.release = release;
        }
    }

    static void attach(MainActivity activity) {
        observer = new WeakReference<>(activity);
        activity.onUpdateDownloadSnapshot(snapshot);
    }

    static void detach(MainActivity activity) {
        if (observer.get() == activity) observer.clear();
    }

    static Snapshot current() { return snapshot; }

    static void clearResult() {
        if (snapshot != null && !snapshot.active) snapshot = null;
    }

    static void start(Context context, String server, UpdatePolicy.Release release) {
        long id = ++nextId;
        pending = new Request(id, server, release);
        updateSnapshot(new Snapshot(server, "正在连接下载…", release, 0, false, true));
        Intent intent = new Intent(context, UpdateDownloadService.class).putExtra(REQUEST_ID, id);
        try { context.startForegroundService(intent); }
        catch (RuntimeException error) {
            pending = null;
            updateSnapshot(new Snapshot(server, "无法启动后台下载：" + error.getMessage(), release, -1, false, false));
        }
    }

    static void cancel(Context context) {
        ++nextId;
        pending = null;
        updateSnapshot(null);
        context.getSharedPreferences("verified_update", 0).edit().remove("sha256").apply();
        NotificationManager manager = (NotificationManager) context.getSystemService(Context.NOTIFICATION_SERVICE);
        if (manager != null) manager.cancel(NOTIFICATION_ID);
        context.stopService(new Intent(context, UpdateDownloadService.class));
    }

    private static void updateSnapshot(Snapshot next) {
        snapshot = next;
        MainActivity activity = observer.get();
        if (activity != null) activity.onUpdateDownloadSnapshot(next);
    }

    private final Handler main = new Handler(Looper.getMainLooper());
    private ServerUpdates updates;
    private PowerManager.WakeLock wakeLock;
    private long runningId;
    private int runningStartId;
    private long lastProgressAt;
    private boolean foregroundStarted;
    private final Runnable deadline = () -> {
        Request request = pending;
        if (request == null || request.id != runningId || request.id != nextId
                || snapshot == null || !snapshot.active) return;
        if (updates != null) updates.cancel();
        finish(request.id, runningStartId, new Snapshot(request.server,
                "下载或校验超过 30 分钟，请重新下载", request.release, -1, false, false));
    };
    private final Runnable stalled = new Runnable() {
        @Override public void run() {
            Snapshot current = snapshot;
            if (current == null || !current.active || runningId != nextId) return;
            if (SystemClock.elapsedRealtime() - lastProgressAt >= 2000 && current.message.startsWith("正在下载 ")
                    && !current.message.endsWith("等待网络）")) {
                int speed = current.message.lastIndexOf(" · ");
                if (speed >= 0) {
                    Snapshot waiting = new Snapshot(current.server, current.message.substring(0, speed)
                            + " · 0 B/秒（等待网络）", current.release, current.percent, false, true);
                    updateSnapshot(waiting);
                    NotificationManager manager = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
                    if (manager != null) manager.notify(NOTIFICATION_ID, notification(waiting));
                }
            }
            main.postDelayed(this, 1000);
        }
    };

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && CANCEL.equals(intent.getAction())) {
            long id = intent.getLongExtra(REQUEST_ID, -1);
            if (id == runningId && id == nextId) cancel(this);
            else if (snapshot == null || !snapshot.active) stopSelf(startId);
            return START_NOT_STICKY;
        }
        Request request = pending;
        if (intent == null || request == null || request.id != intent.getLongExtra(REQUEST_ID, -1)) {
            stopSelf(startId);
            return START_NOT_STICKY;
        }
        main.removeCallbacks(stalled);
        main.removeCallbacks(deadline);
        releaseWakeLock();
        runningId = request.id;
        runningStartId = startId;
        lastProgressAt = SystemClock.elapsedRealtime();
        NotificationManager manager = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        if (manager != null) manager.createNotificationChannel(new NotificationChannel(
                CHANNEL, "Chat 更新下载", NotificationManager.IMPORTANCE_LOW));
        try {
            if (Build.VERSION.SDK_INT >= 29)
                startForeground(NOTIFICATION_ID, notification(snapshot), ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC);
            else startForeground(NOTIFICATION_ID, notification(snapshot));
            foregroundStarted = true;
            PowerManager power = (PowerManager) getSystemService(Context.POWER_SERVICE);
            if (power != null) {
                wakeLock = power.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "Chat:UpdateDownload");
                wakeLock.acquire(WAKE_LIMIT_MS);
            }
        } catch (RuntimeException error) {
            finish(request.id, startId, new Snapshot(request.server,
                    "无法启动后台下载：" + error.getMessage(), request.release, -1, false, false));
            return START_NOT_STICKY;
        }
        if (updates == null) updates = new ServerUpdates(this, (generation, server, message, release, percent, ready) ->
                main.post(() -> {
                    Request current = pending;
                    if (current == null || current.id != runningId || current.id != nextId
                            || updates == null || generation != updates.generation()) return;
                    boolean active = percent >= 0 && !ready;
                    Snapshot next = new Snapshot(server, message, release, percent, ready, active);
                    if (active) {
                        lastProgressAt = SystemClock.elapsedRealtime();
                        updateSnapshot(next);
                        NotificationManager notifications = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
                        if (notifications != null) notifications.notify(NOTIFICATION_ID, notification(next));
                    } else finish(current.id, runningStartId, next);
                }));
        updates.download(request.server, request.release);
        main.postDelayed(stalled, 1000);
        main.postDelayed(deadline, DOWNLOAD_LIMIT_MS);
        return START_NOT_STICKY;
    }

    private void finish(long id, int startId, Snapshot next) {
        if (id != runningId || id != nextId) return;
        releaseWakeLock();
        main.removeCallbacks(stalled);
        main.removeCallbacks(deadline);
        updateSnapshot(next);
        NotificationManager manager = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        if (manager != null) manager.notify(NOTIFICATION_ID, notification(next));
        if (foregroundStarted) stopForeground(STOP_FOREGROUND_DETACH);
        foregroundStarted = false;
        stopSelf(startId);
    }

    private Notification notification(Snapshot state) {
        Intent open = new Intent(this, MainActivity.class)
                .putExtra("openUpdates", true)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        PendingIntent content = PendingIntent.getActivity(this, NOTIFICATION_ID, open,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        Notification.Builder builder = new Notification.Builder(this, CHANNEL)
                .setSmallIcon(R.mipmap.ic_launcher)
                .setContentTitle(state != null && state.ready ? "更新已下载" : "Chat 更新")
                .setContentText(state == null ? "准备下载" : state.message)
                .setContentIntent(content)
                .setOnlyAlertOnce(true)
                .setOngoing(state != null && state.active);
        if (state != null && state.active) builder.setProgress(100, Math.max(0, state.percent), state.percent < 0);
        if (state != null && state.active) {
            Intent cancel = new Intent(this, UpdateDownloadService.class).setAction(CANCEL)
                    .putExtra(REQUEST_ID, runningId);
            PendingIntent action = PendingIntent.getForegroundService(this, (int) runningId, cancel,
                    PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
            builder.addAction(android.R.drawable.ic_menu_close_clear_cancel, "取消下载", action);
        }
        return builder.build();
    }

    private void releaseWakeLock() {
        if (wakeLock != null && wakeLock.isHeld()) wakeLock.release();
        wakeLock = null;
    }

    @Override public void onTimeout(int startId, int fgsType) {
        if (startId != runningStartId) return;
        if (updates != null) updates.cancel();
        Request request = pending;
        if (request != null && request.id == runningId)
            finish(request.id, runningStartId, new Snapshot(request.server, "后台下载超时，请重新下载", request.release, -1, false, false));
        else { releaseWakeLock(); stopSelf(); }
    }

    @Override public void onDestroy() {
        main.removeCallbacks(stalled);
        main.removeCallbacks(deadline);
        if (updates != null) updates.close();
        releaseWakeLock();
        if (snapshot != null && snapshot.active && runningId == nextId)
            updateSnapshot(new Snapshot(snapshot.server, "下载已中断，请重新下载", snapshot.release, -1, false, false));
        super.onDestroy();
    }

    @Override public IBinder onBind(Intent intent) { return null; }
}
