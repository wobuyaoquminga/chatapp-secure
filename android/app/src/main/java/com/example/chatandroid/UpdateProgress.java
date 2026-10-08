package com.example.chatandroid;

import java.util.Locale;

final class UpdateProgress {
    static String message(long downloaded, long total, long intervalBytes, long intervalMillis) {
        long speed = intervalBytes * 1000 / Math.max(1, intervalMillis);
        return "正在下载 " + downloaded * 100 / Math.max(1, total) + "% · " + size(downloaded) + "/" + size(total)
                + " · " + size(speed) + "/秒";
    }

    static String size(long bytes) {
        return bytes >= 1024 * 1024 ? String.format(Locale.ROOT, "%.1f MB", bytes / 1048576.0)
                : bytes >= 1024 ? String.format(Locale.ROOT, "%.0f KB", bytes / 1024.0)
                : bytes + " B";
    }
}
