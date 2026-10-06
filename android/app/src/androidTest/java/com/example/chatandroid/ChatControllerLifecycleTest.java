package com.example.chatandroid;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import android.app.Instrumentation;
import android.os.Handler;
import android.os.Looper;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.io.OutputStream;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicBoolean;

@RunWith(AndroidJUnit4.class)
public class ChatControllerLifecycleTest {
    private static final class Probe implements ChatController.Listener {
        final AtomicInteger states = new AtomicInteger();
        @Override public void onState(JSONObject snapshot, String error) { states.incrementAndGet(); }
        @Override public void onSafety(String peer, JSONObject result) { }
        @Override public void onSent(String draftKey, String body, long revision) { }
        @Override public void onCall(JSONObject frame, long context) { }
        @Override public void onCallReady(CallSession session, JSONArray iceServers, long context) { }
        @Override public void onCallContextLost() { }
    }

    @Test public void closeDropsQueuedWorkerActionAndOldMainSnapshot() throws Exception {
        Instrumentation instrumentation = InstrumentationRegistry.getInstrumentation();
        Probe probe = new Probe();
        ChatController controller = new ChatController(instrumentation.getTargetContext(), probe);
        ScheduledExecutorService worker = worker(controller);
        CountDownLatch mainEntered = new CountDownLatch(1), releaseMain = new CountDownLatch(1);
        CountDownLatch workerEntered = new CountDownLatch(1), releaseWorker = new CountDownLatch(1);
        try {
            worker.submit(() -> { }).get(5, TimeUnit.SECONDS);
            instrumentation.waitForIdleSync();
            int before = probe.states.get();
            new Handler(Looper.getMainLooper()).post(() -> {
                mainEntered.countDown();
                try { releaseMain.await(30, TimeUnit.SECONDS); }
                catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); }
            });
            assertTrue(mainEntered.await(5, TimeUnit.SECONDS));

            field(controller, "username", "previous_account");
            worker.submit(() -> { invoke(controller, "publish", new Class<?>[]{String.class}, ""); return null; })
                    .get(5, TimeUnit.SECONDS);
            worker.execute(() -> {
                workerEntered.countDown();
                try { releaseWorker.await(30, TimeUnit.SECONDS); }
                catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); }
            });
            assertTrue(workerEntered.await(5, TimeUnit.SECONDS));
            controller.logout(); // Queued behind the blocked worker.
            controller.close();
            releaseWorker.countDown();
            assertTrue(worker.awaitTermination(5, TimeUnit.SECONDS));
            releaseMain.countDown();
            instrumentation.waitForIdleSync();
            assertEquals(before, probe.states.get());
        } finally {
            releaseWorker.countDown();
            releaseMain.countDown();
            controller.close();
        }
    }

    @Test public void disconnectRetiresEpochSoOldSocketFailureIsIgnored() throws Exception {
        ChatController controller = new ChatController(
                InstrumentationRegistry.getInstrumentation().getTargetContext(), new Probe());
        try {
            ScheduledExecutorService worker = worker(controller);
            worker.submit(() -> {
                field(controller, "token", "test-token");
                field(controller, "storageFailed", true); // Avoid scheduling a real reconnect.
                long oldEpoch = controller.locationContext();
                invoke(controller, "disconnected", new Class<?>[]{long.class, int.class}, oldEpoch, 0);
                long retiredEpoch = controller.locationContext();
                assertTrue(retiredEpoch > oldEpoch);
                invoke(controller, "disconnected", new Class<?>[]{long.class, int.class}, oldEpoch, 0);
                assertEquals(retiredEpoch, controller.locationContext());
                return null;
            }).get(5, TimeUnit.SECONDS);
        } finally {
            controller.close();
        }
    }

    @Test public void logoutClosesActiveDocumentOutput() throws Exception {
        ChatController controller = new ChatController(
                InstrumentationRegistry.getInstrumentation().getTargetContext(), new Probe());
        AtomicBoolean closed = new AtomicBoolean();
        OutputStream destination = new OutputStream() {
            @Override public void write(int value) { }
            @Override public void close() { closed.set(true); }
        };
        try {
            ScheduledExecutorService worker = worker(controller);
            worker.submit(() -> { field(controller, "fileOutput", destination); return null; })
                    .get(5, TimeUnit.SECONDS);
            controller.logout();
            worker.submit(() -> { }).get(5, TimeUnit.SECONDS);
            assertTrue(closed.get());
        } finally { controller.close(); }
    }

    private static ScheduledExecutorService worker(ChatController controller) throws Exception {
        Field field = ChatController.class.getDeclaredField("worker");
        field.setAccessible(true);
        return (ScheduledExecutorService) field.get(controller);
    }

    private static void field(ChatController controller, String name, Object value) throws Exception {
        Field field = ChatController.class.getDeclaredField(name);
        field.setAccessible(true);
        field.set(controller, value);
    }

    private static void invoke(ChatController controller, String name, Class<?>[] types, Object... args) throws Exception {
        Method method = ChatController.class.getDeclaredMethod(name, types);
        method.setAccessible(true);
        method.invoke(controller, args);
    }
}
