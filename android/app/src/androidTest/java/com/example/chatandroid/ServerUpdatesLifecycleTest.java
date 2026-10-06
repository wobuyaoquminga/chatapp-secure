package com.example.chatandroid;

import static org.junit.Assert.*;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.concurrent.atomic.AtomicInteger;
import okhttp3.Call;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import org.junit.Test;
import org.junit.runner.RunWith;

@RunWith(AndroidJUnit4.class)
public class ServerUpdatesLifecycleTest {
    @Test public void operationsAfterCloseDoNotSubmitToStoppedWorker() {
        AtomicInteger callbacks = new AtomicInteger();
        ServerUpdates updates = new ServerUpdates(InstrumentationRegistry.getInstrumentation().getTargetContext(),
                (generation, server, message, release, percent, ready) -> callbacks.incrementAndGet());
        updates.close();
        updates.check("https://updates.example.invalid");
        updates.download("https://updates.example.invalid");
        updates.verifyForInstall("https://updates.example.invalid", null, () -> fail("Closed installer callback"));
        updates.close();
        assertEquals(0, callbacks.get());
    }

    @Test public void cancelledGenerationCannotRegisterNewNetworkCall() throws Exception {
        ServerUpdates updates = new ServerUpdates(InstrumentationRegistry.getInstrumentation().getTargetContext(),
                (generation, server, message, release, percent, ready) -> { });
        OkHttpClient client = new OkHttpClient();
        try {
            long previous = updates.generation();
            updates.cancel();
            Call call = client.newCall(new Request.Builder().url("https://updates.example.invalid").build());
            Method activate = ServerUpdates.class.getDeclaredMethod("activate", long.class, Call.class);
            activate.setAccessible(true);
            try {
                activate.invoke(updates, previous, call);
                fail("Retired request must not become active");
            } catch (InvocationTargetException expected) {
                assertEquals("下载已取消", expected.getCause().getMessage());
            }
            activate.invoke(updates, updates.generation(), call);
            updates.cancel();
            assertTrue(call.isCanceled());
        } finally {
            updates.close();
            client.dispatcher().executorService().shutdown();
            client.connectionPool().evictAll();
        }
    }
}
