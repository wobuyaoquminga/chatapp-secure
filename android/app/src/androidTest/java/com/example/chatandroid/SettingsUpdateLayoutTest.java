package com.example.chatandroid;

import static org.junit.Assert.*;

import android.app.Instrumentation;
import android.view.View;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.TextView;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

/** Keeps the real Settings view steady across update and connection callbacks. */
@RunWith(AndroidJUnit4.class)
public class SettingsUpdateLayoutTest {
    private static final String FIRST = "https://first.example.invalid";
    private static final String SECOND = "https://second.example.invalid";
    private final Instrumentation instrumentation = InstrumentationRegistry.getInstrumentation();
    private MainActivity activity;

    @Before public void launch() throws Exception {
        activity = QaActivityLauncher.launch(instrumentation);
        QaActivityLauncher.settleInitialState(instrumentation, activity);
        publish(snapshot(FIRST, "在线"));
        openSettings();
    }

    @After public void finish() {
        if (activity != null) instrumentation.runOnMainSync(activity::finish);
    }

    @Test public void progressAndConnectionChangesKeepSettingsViewAndScroll() throws Exception {
        ScrollView scroll = (ScrollView) ((android.widget.FrameLayout) field("content")).getChildAt(0);
        TextView message = (TextView) field("updateMessageView");
        ProgressBar progress = (ProgressBar) field("updateProgressView");
        TextView accountStatus = (TextView) field("settingsAccountStatus");
        instrumentation.runOnMainSync(() -> scroll.scrollTo(0, 300));
        int y = scroll.getScrollY();
        assertTrue(y > 0);

        ServerUpdates updates = (ServerUpdates) field("serverUpdates");
        ServerUpdates.Listener listener = listener(updates);
        UpdatePolicy.Release release = new UpdatePolicy.Release("android-arm64-v8a-debug", "9.0", 900,
                "qa.apk", 100, "hash", "/qa.apk", "");
        listener.status(updates.generation(), FIRST, "正在下载 37%", release, 37, false);
        instrumentation.waitForIdleSync();
        assertSame(scroll, ((android.widget.FrameLayout) field("content")).getChildAt(0));
        assertSame(message, field("updateMessageView"));
        assertSame(progress, field("updateProgressView"));
        assertEquals(37, progress.getProgress());
        assertEquals(View.VISIBLE, progress.getVisibility());
        assertEquals(y, scroll.getScrollY());

        publish(snapshot(FIRST, "连接中"));
        assertSame(scroll, ((android.widget.FrameLayout) field("content")).getChildAt(0));
        assertSame(accountStatus, field("settingsAccountStatus"));
        assertEquals("连接中", accountStatus.getText().toString());
        assertEquals(y, scroll.getScrollY());
    }

    @Test public void rapidChecksStartOnlyOneRequest() throws Exception {
        ServerUpdates updates = (ServerUpdates) field("serverUpdates");
        long before = updates.generation();
        TextView check = (TextView) field("updateCheckButton");
        instrumentation.runOnMainSync(() -> {
            check.performClick();
            check.performClick();
            assertEquals(before + 1, updates.generation());
            assertFalse(check.isEnabled());
        });
        assertSame(check, field("updateCheckButton"));
    }

    @Test public void switchingServerRejectsOldUpdateResult() throws Exception {
        ServerUpdates updates = (ServerUpdates) field("serverUpdates");
        long oldGeneration = updates.generation();
        ServerUpdates.Listener listener = listener(updates);
        publish(snapshot(SECOND, "在线"));
        openSettings();
        TextView message = (TextView) field("updateMessageView");
        listener.status(oldGeneration, FIRST, "旧服务器有更新", null, -1, false);
        instrumentation.waitForIdleSync();
        assertEquals("", message.getText().toString());
        assertEquals(View.GONE, message.getVisibility());
        assertSame(message, field("updateMessageView"));
    }

    private JSONObject snapshot(String selected, String status) throws Exception {
        return new JSONObject().put("username", "qa-user").put("server", selected)
                .put("selectedServer", selected).put("status", status).put("online", "在线".equals(status))
                .put("servers", new JSONArray().put(new JSONObject().put("address", FIRST))
                        .put(new JSONObject().put("address", SECOND)));
    }

    private void publish(JSONObject snapshot) throws Exception {
        instrumentation.runOnMainSync(() -> activity.onState(snapshot, ""));
        ((ExecutorService) field("uiPreparation")).submit(() -> { }).get(5, TimeUnit.SECONDS);
        instrumentation.waitForIdleSync();
    }

    private void openSettings() {
        instrumentation.runOnMainSync(() -> {
            try {
                setField("page", "settings");
                Method render = MainActivity.class.getDeclaredMethod("render");
                render.setAccessible(true);
                render.invoke(activity);
            } catch (Exception failure) { throw new AssertionError(failure); }
        });
        instrumentation.waitForIdleSync();
    }

    private ServerUpdates.Listener listener(ServerUpdates updates) throws Exception {
        Field field = ServerUpdates.class.getDeclaredField("listener");
        field.setAccessible(true);
        return (ServerUpdates.Listener) field.get(updates);
    }

    private Object field(String name) {
        try {
            Field field = MainActivity.class.getDeclaredField(name);
            field.setAccessible(true);
            return field.get(activity);
        } catch (Exception failure) { throw new AssertionError(failure); }
    }

    private void setField(String name, Object value) throws Exception {
        Field field = MainActivity.class.getDeclaredField(name);
        field.setAccessible(true);
        field.set(activity, value);
    }
}
