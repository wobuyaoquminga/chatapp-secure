package com.example.chatandroid;

import static org.junit.Assert.*;
import android.app.AlertDialog;
import android.app.Instrumentation;
import android.content.Intent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.LinearLayout;
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

/** Compile-only contract suite unless explicitly run on an authorized device. No location/network fixtures. */
@RunWith(AndroidJUnit4.class)
public class ChatMenuHistoryTest {
    private final Instrumentation instrumentation = InstrumentationRegistry.getInstrumentation();
    private MainActivity activity;
    @Before public void launch() throws Exception {
        activity = (MainActivity) instrumentation.startActivitySync(new Intent(instrumentation.getTargetContext(), MainActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        instrumentation.runOnMainSync(() -> ((ChatController) field("controller")).close());
        publish(snapshot());
        instrumentation.runOnMainSync(() -> { set("detailPeer", "peer"); invoke("render"); });
        instrumentation.waitForIdleSync();
    }
    @After public void finish() { if (activity != null) instrumentation.runOnMainSync(activity::finish); }

    @Test public void historyJumpLoadsRecordOlderThanWindowAndPreservesComposer() throws Exception {
        EditText composer = (EditText) field("composer");
        LinearLayout stream = (LinearLayout) field("conversationStream");
        assertEquals(81, stream.getChildCount());
        JSONObject target = snapshot().getJSONArray("messages").getJSONObject(5);
        instrumentation.runOnMainSync(() -> {
            composer.setText("保留草稿"); composer.setSelection(2); composer.requestFocus();
            invoke("jumpToMessage", new Class<?>[]{JSONObject.class}, target);
        });
        instrumentation.waitForIdleSync();
        assertSame(composer, field("composer"));
        assertEquals("保留草稿", composer.getText().toString());
        assertEquals(2, composer.getSelectionStart());
        assertTrue(composer.hasFocus());
        assertTrue(stream.getChildCount() > 80);
        assertTrue(contains(stream, "消息 5"));
    }
    @Test public void deletedPeerStillHasHistoryDialogAndNoAutomaticLocationPermission() throws Exception {
        JSONObject deleted = snapshot().put("deletedPeers", new JSONObject().put("peer", new JSONObject()));
        publish(deleted);
        instrumentation.runOnMainSync(() -> invoke("showHistorySearch"));
        instrumentation.waitForIdleSync();
        AlertDialog dialog = (AlertDialog) field("historyDialog");
        assertTrue(dialog.isShowing());
        assertTrue(contains(dialog.getWindow().getDecorView(), "搜索消息正文"));
        assertEquals("", field("pendingLocationPeer"));
        assertEquals("", field("pendingLocationAction"));
    }
    @Test public void expiryRefreshChangesOnlyCardLabels() throws Exception {
        long now = System.currentTimeMillis();
        String session = "12345678-1234-4234-8234-123456789abc";
        JSONObject state = snapshot();
        state.getJSONArray("messages").put(new JSONObject().put("sender", "peer").put("recipient", "own")
                .put("body", LocationPayload.encode("live", session, 0, 30.0, 120.0, 20, now-2000, now-1000))
                .put("createdAt", java.time.Instant.ofEpochMilli(now-2000).toString()));
        publish(state);
        EditText composer = (EditText) field("composer");
        LinearLayout stream = (LinearLayout) field("conversationStream");
        View card = stream.getChildAt(stream.getChildCount()-1);
        instrumentation.runOnMainSync(() -> invoke("refreshLocationCards"));
        assertSame(composer, field("composer"));
        assertSame(card, stream.getChildAt(stream.getChildCount()-1));
        assertTrue(contains(card, "实时位置 · 已过期"));
    }
    private JSONObject snapshot() throws Exception {
        JSONArray messages = new JSONArray();
        for (int i = 0; i < 240; i++) messages.put(new JSONObject().put("sender", "own").put("recipient", "peer")
                .put("clientId", "history-"+i).put("body", "消息 "+i).put("createdAt", "2026-09-26T00:00:00Z"));
        return new JSONObject().put("username", "own").put("server", "https://example.invalid")
                .put("selectedServer", "https://example.invalid").put("messages", messages)
                .put("conversations", new JSONArray().put("peer")).put("contacts", new JSONArray().put("peer"))
                .put("relationships", new JSONObject().put("peer", new JSONObject().put("status", "accepted")));
    }
    private void publish(JSONObject snapshot) throws Exception {
        instrumentation.runOnMainSync(() -> activity.onState(snapshot, ""));
        ((ExecutorService)field("uiPreparation")).submit(() -> {}).get(5, TimeUnit.SECONDS);
        instrumentation.waitForIdleSync();
    }
    private boolean contains(View view, String text) {
        if (view instanceof TextView) {
            TextView label = (TextView)view;
            if (text.equals(label.getText().toString()) || label.getHint() != null && text.equals(label.getHint().toString())) return true;
        }
        if (view instanceof ViewGroup) for (int i=0; i<((ViewGroup)view).getChildCount(); i++)
            if (contains(((ViewGroup)view).getChildAt(i),text)) return true;
        return false;
    }
    private Object field(String name) {
        try { Field field = MainActivity.class.getDeclaredField(name); field.setAccessible(true); return field.get(activity); }
        catch (Exception failure) { throw new AssertionError(failure); }
    }
    private void set(String name, Object value) {
        try { Field field=MainActivity.class.getDeclaredField(name); field.setAccessible(true); field.set(activity,value); }
        catch(Exception failure) { throw new AssertionError(failure); }
    }
    private void invoke(String name) { invoke(name,new Class<?>[0]); }
    private void invoke(String name, Class<?>[] types, Object... args) {
        try { Method method=MainActivity.class.getDeclaredMethod(name,types); method.setAccessible(true); method.invoke(activity,args); }
        catch(Exception failure) { throw new AssertionError(failure); }
    }
}
