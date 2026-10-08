package com.example.chatandroid;

import static org.junit.Assert.assertTrue;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertSame;

import android.content.Context;
import android.content.Intent;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import java.net.ServerSocket;
import java.net.Socket;
import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.Test;
import org.junit.runner.RunWith;

@RunWith(AndroidJUnit4.class)
public class UpdateDownloadBackgroundTest {
    @Test public void cancellingWhileBackgroundedClearsBusyStateWhenSettingsReturns() throws Exception {
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        try (ServerSocket server = new ServerSocket(0)) {
            CountDownLatch connected = new CountDownLatch(1);
            Thread responder = new Thread(() -> {
                try (Socket connection = server.accept()) {
                    connection.getInputStream().read(new byte[4096]);
                    connection.getOutputStream().write(("HTTP/1.1 200 OK\r\nContent-Length: 4\r\n\r\na")
                            .getBytes(StandardCharsets.US_ASCII));
                    connection.getOutputStream().flush();
                    connected.countDown();
                    Thread.sleep(10000);
                } catch (Exception ignored) { }
            });
            responder.start();
            Intent open = new Intent(context, MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK
                    | Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP);
            MainActivity activity = (MainActivity) InstrumentationRegistry.getInstrumentation().startActivitySync(open);
            try {
                UpdatePolicy.Release release = new UpdatePolicy.Release(UpdatePolicy.platform(BuildConfig.DEBUG),
                        "99.0.0", 999999, "test.apk", 4, "0".repeat(64),
                        "/api/updates/files/" + UpdatePolicy.platform(BuildConfig.DEBUG), "");
                InstrumentationRegistry.getInstrumentation().runOnMainSync(() ->
                        UpdateDownloadService.start(context, "http://127.0.0.1:" + server.getLocalPort(), release));
                assertTrue("Foreground service did not connect", connected.await(5, TimeUnit.SECONDS));
                InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> assertTrue(activity.moveTaskToBack(true)));
                InstrumentationRegistry.getInstrumentation().waitForIdleSync();
                InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> UpdateDownloadService.cancel(context));
                MainActivity resumed = (MainActivity) InstrumentationRegistry.getInstrumentation().startActivitySync(open);
                assertSame(activity, resumed);
                InstrumentationRegistry.getInstrumentation().waitForIdleSync();
                Field phase = MainActivity.class.getDeclaredField("updatePhase");
                Field available = MainActivity.class.getDeclaredField("updateRelease");
                phase.setAccessible(true);
                available.setAccessible(true);
                assertEquals("", phase.get(activity));
                assertNotNull("Checked release should remain available to retry", available.get(activity));
            } finally {
                InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
                    UpdateDownloadService.cancel(context);
                    activity.finish();
                });
                responder.interrupt();
            }
        }
    }

    @Test public void finishingSettingsActivityDoesNotCancelActiveDownload() throws Exception {
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        try (ServerSocket server = new ServerSocket(0)) {
            CountDownLatch connected = new CountDownLatch(1);
            Thread responder = new Thread(() -> {
                try (Socket connection = server.accept()) {
                    connection.getInputStream().read(new byte[4096]);
                    connection.getOutputStream().write(("HTTP/1.1 200 OK\r\nContent-Length: 4\r\n\r\na")
                            .getBytes(StandardCharsets.US_ASCII));
                    connection.getOutputStream().flush();
                    connected.countDown();
                    Thread.sleep(10000);
                } catch (Exception ignored) { }
            });
            responder.start();
            MainActivity activity = (MainActivity) InstrumentationRegistry.getInstrumentation()
                    .startActivitySync(new Intent(context, MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            try {
                UpdatePolicy.Release release = new UpdatePolicy.Release(UpdatePolicy.platform(BuildConfig.DEBUG),
                        "99.0.0", 999999, "test.apk", 4, "0".repeat(64),
                        "/api/updates/files/" + UpdatePolicy.platform(BuildConfig.DEBUG), "");
                InstrumentationRegistry.getInstrumentation().runOnMainSync(() ->
                        UpdateDownloadService.start(context, "http://127.0.0.1:" + server.getLocalPort(), release));
                assertTrue("Foreground service did not connect", connected.await(5, TimeUnit.SECONDS));
                InstrumentationRegistry.getInstrumentation().runOnMainSync(activity::finish);
                InstrumentationRegistry.getInstrumentation().waitForIdleSync();
                assertTrue(UpdateDownloadService.current() != null && UpdateDownloadService.current().active);
            } finally {
                InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> UpdateDownloadService.cancel(context));
                responder.interrupt();
            }
        }
    }
}
