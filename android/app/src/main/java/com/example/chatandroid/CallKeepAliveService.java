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

/** Keeps a user-accepted media call alive while its Activity is in the background. */
public final class CallKeepAliveService extends Service {
    private static final String CHANNEL = "chat_active_calls";
    private static final String MODE = "mode";
    private static final int ACTIVE_ID = 4202;
    private static volatile Runnable onTaskRemoved;

    static void setOnTaskRemoved(Runnable callback) { onTaskRemoved = callback; }

    static void start(Context context, String mode) {
        Intent intent = new Intent(context, CallKeepAliveService.class).putExtra(MODE, mode);
        try { context.startForegroundService(intent); }
        catch (RuntimeException ignored) { /* A visible call can continue without background protection. */ }
    }

    static void stop(Context context) {
        onTaskRemoved = null;
        context.stopService(new Intent(context, CallKeepAliveService.class));
    }

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        NotificationManager manager = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        if (manager != null) manager.createNotificationChannel(new NotificationChannel(
                CHANNEL, "Chat 通话中", NotificationManager.IMPORTANCE_LOW));
        Intent open = new Intent(this, MainActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        PendingIntent content = PendingIntent.getActivity(this, ACTIVE_ID, open,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        Notification notification = new Notification.Builder(this, CHANNEL)
                .setSmallIcon(R.mipmap.ic_launcher)
                .setContentTitle("Chat 通话中")
                .setContentText("点击返回通话")
                .setContentIntent(content)
                .setOngoing(true)
                .setCategory(Notification.CATEGORY_CALL)
                .build();
        boolean video = intent != null && "video".equals(intent.getStringExtra(MODE));
        try {
            if (Build.VERSION.SDK_INT >= 30) {
                int types = ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
                        | (video ? ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA : 0);
                startForeground(ACTIVE_ID, notification, types);
            } else startForeground(ACTIVE_ID, notification);
        } catch (RuntimeException denied) {
            stopSelf(startId);
            return START_NOT_STICKY;
        }
        return START_NOT_STICKY;
    }

    @Override public void onTaskRemoved(Intent rootIntent) {
        Runnable callback = onTaskRemoved;
        if (callback != null) new Handler(Looper.getMainLooper()).post(callback);
        stopSelf();
        super.onTaskRemoved(rootIntent);
    }

    @Override public IBinder onBind(Intent intent) { return null; }
}
