package com.example.chatandroid;

import android.Manifest;
import android.app.Activity;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Build;

/** Shows an incoming call when the chat screen is not visible. */
final class CallNotifier {
    private static final String CHANNEL = "chat_incoming_calls";
    private static final int INCOMING_ID = 4201;
    private static final int PERMISSION_REQUEST = 503;
    private static final String PERMISSION_ASKED = "call_notifications_asked";

    private CallNotifier() { }

    static void ensurePermission(Activity activity) {
        if (Build.VERSION.SDK_INT < 33
                || activity.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED)
            return;
        if (activity.getPreferences(Context.MODE_PRIVATE).getBoolean(PERMISSION_ASKED, false)) return;
        activity.getPreferences(Context.MODE_PRIVATE).edit().putBoolean(PERMISSION_ASKED, true).apply();
        activity.requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, PERMISSION_REQUEST);
    }

    static void showIncoming(Activity activity, CallSession session) {
        NotificationManager manager = (NotificationManager) activity.getSystemService(Context.NOTIFICATION_SERVICE);
        if (manager == null) return;
        manager.createNotificationChannel(new NotificationChannel(CHANNEL, "Chat 来电",
                NotificationManager.IMPORTANCE_HIGH));
        Intent open = new Intent(activity, MainActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        PendingIntent content = PendingIntent.getActivity(activity, INCOMING_ID, open,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        String kind = session.mode.equals("video") ? "视频" : "语音";
        Notification notification = new Notification.Builder(activity, CHANNEL)
                .setSmallIcon(R.mipmap.ic_launcher)
                .setContentTitle(session.peer + " 邀请你" + kind + "通话")
                .setContentText("点击查看并接听")
                .setCategory(Notification.CATEGORY_CALL)
                .setPriority(Notification.PRIORITY_HIGH)
                .setContentIntent(content)
                .setAutoCancel(true)
                .setVisibility(Notification.VISIBILITY_PRIVATE)
                .build();
        manager.notify(INCOMING_ID, notification);
    }

    static void cancel(Context context) {
        NotificationManager manager = (NotificationManager) context.getSystemService(Context.NOTIFICATION_SERVICE);
        if (manager != null) manager.cancel(INCOMING_ID);
    }
}
