package com.example.chatandroid;

import static org.junit.Assert.*;
import static org.junit.Assume.assumeTrue;

import android.app.Instrumentation;
import android.content.Intent;
import android.graphics.Insets;
import android.os.Build;
import android.view.View;
import android.view.WindowInsets;
import android.widget.EditText;
import android.widget.LinearLayout;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import java.lang.reflect.Field;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

/** Exercises the production Activity views with synthetic snapshots, without accounts or a server. */
@RunWith(AndroidJUnit4.class)
public class ConversationLayoutTest {
    private final Instrumentation instrumentation = InstrumentationRegistry.getInstrumentation();
    private MainActivity activity;

    @Before public void launch() throws Exception {
        Intent intent = new Intent(instrumentation.getTargetContext(), MainActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        activity = (MainActivity) instrumentation.startActivitySync(intent);
        instrumentation.runOnMainSync(() -> ((ChatController) field("controller")).close());
        publish(snapshot(240));
        instrumentation.runOnMainSync(() -> {
            try {
                setField("detailPeer", "peer");
                java.lang.reflect.Method render = MainActivity.class.getDeclaredMethod("render");
                render.setAccessible(true);
                render.invoke(activity);
            } catch (Exception failure) { throw new AssertionError(failure); }
        });
        instrumentation.waitForIdleSync();
    }

    @After public void finish() {
        if (activity != null) instrumentation.runOnMainSync(activity::finish);
    }

    @Test public void snapshotsReuseComposerAndBoundInitialHistory() throws Exception {
        EditText original = (EditText) field("composer");
        LinearLayout stream = (LinearLayout) field("conversationStream");
        assertEquals(81, stream.getChildCount()); // 80 messages and the real load-older control.
        View lastBubble = stream.getChildAt(80);
        instrumentation.runOnMainSync(() -> {
            original.requestFocus();
            original.setText("未发出的草稿");
            original.setSelection(3);
        });
        publish(snapshot(240));
        assertSame(original, field("composer"));
        assertSame(lastBubble, stream.getChildAt(80));
        publish(snapshot(241));
        assertSame(original, field("composer"));
        assertEquals("未发出的草稿", original.getText().toString());
        assertEquals(3, original.getSelectionStart());
        assertTrue(original.hasFocus());
        assertEquals(81, stream.getChildCount());
        instrumentation.runOnMainSync(() -> stream.getChildAt(0).performClick());
        instrumentation.waitForIdleSync();
        assertEquals(161, stream.getChildCount());
    }

    @Test public void imeInsetsKeepComposerAboveKeyboardInBothOrientations() {
        assumeTrue(Build.VERSION.SDK_INT >= 30);
        instrumentation.runOnMainSync(() -> {
            LinearLayout shell = (LinearLayout) field("shell");
            EditText composer = (EditText) field("composer");
            float density = activity.getResources().getDisplayMetrics().density;
            int keyboardHeight = Math.round(160 * density);
            WindowInsets insets = new WindowInsets.Builder()
                    .setInsets(WindowInsets.Type.systemBars(), Insets.of(0, Math.round(24 * density), 0, Math.round(24 * density)))
                    .setInsets(WindowInsets.Type.ime(), Insets.of(0, 0, 0, keyboardHeight))
                    .setVisible(WindowInsets.Type.ime(), true).build();
            for (int[] size : new int[][] {{360, 640}, {640, 360}}) {
                int width = Math.round(size[0] * density), height = Math.round(size[1] * density);
                shell.dispatchApplyWindowInsets(insets);
                shell.dispatchApplyWindowInsets(insets); // Reapplication cannot add padding twice.
                shell.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
                        View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY));
                shell.layout(0, 0, width, height);
                android.graphics.Rect bounds = new android.graphics.Rect();
                composer.getDrawingRect(bounds);
                shell.offsetDescendantRectToMyCoords(composer, bounds);
                assertEquals(keyboardHeight, shell.getPaddingBottom());
                assertTrue("composer below IME boundary", bounds.bottom <= height - keyboardHeight);
                assertTrue("composer collapsed on small screen", bounds.height() > 0);
                assertEquals(View.GONE, ((View) field("tabs")).getVisibility());
            }
        });
    }

    @Test public void identityChangeBlocksSendingAndDismissesOldSafetyCode() throws Exception {
        instrumentation.runOnMainSync(() -> {
            try {
                activity.onSafety("peer", new JSONObject().put("code", "1234512345").put("verified", true));
            } catch (Exception failure) { throw new AssertionError(failure); }
        });
        assertNotNull(field("safetyDialog"));
        publish(snapshot(240).put("identityChanges", new JSONObject().put("peer", "new-identity")));
        assertFalse(((EditText) field("composer")).isEnabled());
        assertNull(field("safetyDialog"));
        publish(snapshot(240).put("identityChanges", new JSONObject()));
        assertTrue(((EditText) field("composer")).isEnabled());
    }

    private JSONObject snapshot(int count) throws Exception {
        JSONArray messages = new JSONArray();
        for (int i = 0; i < count; i++) messages.put(new JSONObject()
                .put("sender", "own").put("recipient", "peer").put("clientId", "test-" + i)
                .put("body", "消息 " + i).put("createdAt", "2026-09-27T00:00:00Z").put("status", "已发送"));
        return new JSONObject().put("username", "own").put("server", "https://example.invalid")
                .put("selectedServer", "https://example.invalid").put("messages", messages)
                .put("conversations", new JSONArray().put("peer"))
                .put("relationships", new JSONObject().put("peer", new JSONObject().put("status", "accepted")))
                .put("contacts", new JSONArray().put("peer"));
    }

    private void publish(JSONObject snapshot) throws Exception {
        instrumentation.runOnMainSync(() -> activity.onState(snapshot, ""));
        // A barrier in the Activity's real preparation executor precedes the main-thread apply.
        java.util.concurrent.ExecutorService executor = (java.util.concurrent.ExecutorService) field("uiPreparation");
        executor.submit(() -> { }).get(5, java.util.concurrent.TimeUnit.SECONDS);
        instrumentation.waitForIdleSync();
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
