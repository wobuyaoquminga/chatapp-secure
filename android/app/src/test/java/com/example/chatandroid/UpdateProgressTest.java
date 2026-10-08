package com.example.chatandroid;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

public final class UpdateProgressTest {
    @Test public void displaysDownloadedTotalAndMeasuredSpeed() {
        assertEquals("正在下载 25% · 1.0 MB/4.0 MB · 512 KB/秒",
                UpdateProgress.message(1048576, 4194304, 524288, 1000));
        assertEquals("正在下载 100% · 4.0 MB/4.0 MB · 0 B/秒",
                UpdateProgress.message(4194304, 4194304, 0, 1000));
        assertEquals("正在下载 50% · 1 B/2 B · 1000 B/秒",
                UpdateProgress.message(1, 2, 1, 0));
        assertEquals("正在下载 0% · 0 B/0 B · 0 B/秒",
                UpdateProgress.message(0, 0, 0, 0));
    }
}
