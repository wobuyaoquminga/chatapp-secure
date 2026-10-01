package com.example.chatandroid;

import android.Manifest;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Build;

/** Notifications from the existing live connection, only after successful encrypted persistence. */
final class MessageNotifier {
    private static final String CHANNEL = "chat_messages";
    private static final String TAG = "chat_message";
    private MessageNotifier() { }

    static void show(Context context, String peer) {
        if (Build.VERSION.SDK_INT >= 33 && context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED) return;
        NotificationManager manager = context.getSystemService(NotificationManager.class);
        if (manager == null) return;
        manager.createNotificationChannel(new NotificationChannel(CHANNEL, "Chat 新消息", NotificationManager.IMPORTANCE_DEFAULT));
        Intent open = new Intent(context, MainActivity.class).putExtra("messagePeer", peer)
                .setData(android.net.Uri.parse("chat-message:" + android.net.Uri.encode(peer)))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        PendingIntent content = PendingIntent.getActivity(context, 0, open,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        Notification publicVersion = new Notification.Builder(context, CHANNEL).setSmallIcon(R.mipmap.ic_launcher)
                .setContentTitle("Chat 新消息").setContentText("打开 Chat 查看").build();
        Notification notification = new Notification.Builder(context, CHANNEL).setSmallIcon(R.mipmap.ic_launcher)
                .setContentTitle(peer).setContentText("收到新消息，点击查看")
                .setCategory(Notification.CATEGORY_MESSAGE).setContentIntent(content).setAutoCancel(true)
                .setVisibility(Notification.VISIBILITY_PRIVATE).setPublicVersion(publicVersion).build();
        try { manager.notify(TAG + ":" + peer, 0, notification); }
        catch (SecurityException ignored) { /* Permission can be revoked while a frame is being persisted. */ }
    }

    static void cancel(Context context, String peer) {
        NotificationManager manager = context.getSystemService(NotificationManager.class);
        if (manager != null) manager.cancel(TAG + ":" + peer, 0);
    }

    static void cancelAll(Context context) {
        NotificationManager manager = context.getSystemService(NotificationManager.class);
        if (manager != null) for (android.service.notification.StatusBarNotification item : manager.getActiveNotifications())
            if (item.getTag() != null && item.getTag().startsWith(TAG + ":")) manager.cancel(item.getTag(), item.getId());
    }
}
